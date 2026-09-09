"""Runs macro-estimation jobs as Claude Code sessions.

One asyncio task per job, each owning a ClaudeSDKClient. Concurrency is bounded by
a semaphore; cancellation interrupts the session and tears down the CLI subprocess.
"""

from __future__ import annotations

import asyncio
import ipaddress
import json
import logging
import os
import socket
import uuid
from datetime import date
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from claude_agent_sdk import (
    AssistantMessage,
    query,
    ClaudeAgentOptions,
    ClaudeSDKClient,
    PermissionResultAllow,
    PermissionResultDeny,
    ResultMessage,
    SystemMessage,
    StreamEvent,
    TextBlock,
    ToolPermissionContext,
)

from . import config, db, prompts, schema

log = logging.getLogger("macropad.runner")


def job_dir(job_id: str) -> Path:
    return config.JOBS_DIR / job_id


def _is_private_host(host: str) -> bool:
    """True for anything that would let a prompt-injected page reach the LAN."""
    host = host.strip("[]").lower()
    if host in ("localhost", "host.docker.internal") or host.endswith(".local"):
        return True
    try:
        addr = ipaddress.ip_address(host)
    except ValueError:
        try:
            resolved = socket.getaddrinfo(host, None)
        except OSError:
            return False
        return any(
            ipaddress.ip_address(info[4][0]).is_private
            or ipaddress.ip_address(info[4][0]).is_loopback
            or ipaddress.ip_address(info[4][0]).is_link_local
            for info in resolved
        )
    return addr.is_private or addr.is_loopback or addr.is_link_local


def _make_permission_callback(directory: Path):
    """Deny anything the agent shouldn't be able to reach.

    The container holds live Claude credentials, so this callback — not the
    filesystem — is what stops a prompt injected via a photographed receipt from
    reading them. Read is confined to the job directory; WebFetch is kept off the
    LAN and off localhost.
    """
    root = directory.resolve()

    async def can_use_tool(
        tool_name: str, tool_input: dict[str, Any], context: ToolPermissionContext
    ):
        if tool_name == "Read":
            raw_path = tool_input.get("file_path") or tool_input.get("path") or ""
            try:
                target = (root / raw_path).resolve() if not Path(raw_path).is_absolute() else Path(raw_path).resolve()
            except (OSError, RuntimeError):
                return PermissionResultDeny(message="Unreadable path.")
            if target != root and root not in target.parents:
                log.warning("denied Read outside job dir: %s", raw_path)
                return PermissionResultDeny(
                    message="You may only read files in your working directory."
                )
            return PermissionResultAllow(updated_input=tool_input)

        if tool_name == "WebFetch":
            url = str(tool_input.get("url") or "")
            parsed = urlparse(url)
            if parsed.scheme not in ("http", "https"):
                return PermissionResultDeny(message="Only http(s) URLs may be fetched.")
            if not parsed.hostname or _is_private_host(parsed.hostname):
                log.warning("denied WebFetch to private host: %s", url)
                return PermissionResultDeny(
                    message="That host is not reachable. Use a public nutrition source."
                )
            return PermissionResultAllow(updated_input=tool_input)

        if tool_name in config.AGENT_AUTO_APPROVED_TOOLS:
            return PermissionResultAllow(updated_input=tool_input)

        log.warning("denied disallowed tool: %s", tool_name)
        return PermissionResultDeny(message=f"{tool_name} is not available.")

    return can_use_tool


def _build_options(directory: Path, resume_session: str | None) -> ClaudeAgentOptions:
    return ClaudeAgentOptions(
        model=config.MODEL,
        system_prompt=prompts.SYSTEM_PROMPT,
        cwd=str(directory),
        tools=list(config.AGENT_TOOLS),
        allowed_tools=list(config.AGENT_AUTO_APPROVED_TOOLS),
        disallowed_tools=list(config.AGENT_DISALLOWED_TOOLS),
        can_use_tool=_make_permission_callback(directory),
        permission_mode="default",
        # Don't inherit the host's CLAUDE.md, skills, or project settings — this
        # agent's instructions should come from prompts.py and nowhere else.
        setting_sources=[],
        max_turns=config.MAX_TURNS,
        output_format=schema.OUTPUT_FORMAT,
        # Estimating a meal takes as long as planning does and was showing nothing at
        # all while it worked, which reads as the app being broken.
        include_partial_messages=True,
        resume=resume_session,
        env={"CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1"},
    )


class JobRunner:
    def __init__(self) -> None:
        self._semaphore = asyncio.Semaphore(config.MAX_CONCURRENT)
        self._tasks: dict[str, asyncio.Task[None]] = {}
        self._clients: dict[str, ClaudeSDKClient] = {}
        self._cancelling: set[str] = set()

    # ------------------------------------------------------------------ public

    def submit(
        self,
        job_id: str,
        followup: list[tuple[str, str]] | None = None,
        correction: str | None = None,
    ) -> None:
        if job_id in self._tasks and not self._tasks[job_id].done():
            return
        task = asyncio.create_task(self._guarded_run(job_id, followup, correction))
        self._tasks[job_id] = task
        task.add_done_callback(lambda _t, jid=job_id: self._tasks.pop(jid, None))

    def is_active(self, job_id: str) -> bool:
        task = self._tasks.get(job_id)
        return task is not None and not task.done()

    async def cancel(self, job_id: str) -> bool:
        task = self._tasks.get(job_id)
        if task is None or task.done():
            return False
        self._cancelling.add(job_id)

        client = self._clients.get(job_id)
        if client is not None:
            try:
                await asyncio.wait_for(client.interrupt(), timeout=5)
            except Exception as exc:  # interrupt is best-effort; the kill below is not
                log.info("interrupt failed for %s: %s", job_id, exc)

        task.cancel()
        try:
            await asyncio.wait_for(asyncio.shield(task), timeout=15)
        except (asyncio.CancelledError, asyncio.TimeoutError):
            pass
        except Exception:
            pass
        return True

    async def shutdown(self) -> None:
        for job_id in list(self._tasks):
            await self.cancel(job_id)

    # ----------------------------------------------------------------- internal

    async def _guarded_run(
        self,
        job_id: str,
        followup: list[tuple[str, str]] | None,
        correction: str | None = None,
    ) -> None:
        try:
            async with self._semaphore:
                if job_id in self._cancelling:
                    raise asyncio.CancelledError
                await self._run(job_id, followup, correction)
        except asyncio.CancelledError:
            db.set_status(job_id, db.STATUS_CANCELLED, "cancelled by user")
            log.info("job %s cancelled", job_id)
            raise
        except Exception as exc:  # noqa: BLE001 - the daemon must survive any job
            log.exception("job %s failed", job_id)
            db.update_job(job_id, error=f"{type(exc).__name__}: {exc}")
            db.set_status(job_id, db.STATUS_FAILED, str(exc))
        finally:
            self._cancelling.discard(job_id)
            self._clients.pop(job_id, None)

    async def _run(
        self,
        job_id: str,
        followup: list[tuple[str, str]] | None,
        correction: str | None = None,
    ) -> None:
        job = db.get_job(job_id)
        if job is None:
            return

        directory = job_dir(job_id)
        directory.mkdir(parents=True, exist_ok=True)
        transcript = directory / "transcript.jsonl"

        if correction:
            prompt = prompts.build_correction_prompt(correction)
            # Resuming is the entire point: a correction is a word to the session
            # that already did the research, not a fresh estimate.
            resume_session = job["session_id"]
            db.add_event(job_id, "correction_submitted", correction[:200])
        elif followup:
            prompt = prompts.build_followup_prompt(followup)
            resume_session = job["session_id"]
            db.add_event(job_id, "followup_submitted", json.dumps([q for q, _ in followup]))
        else:
            image_names = sorted(
                p.name for p in directory.glob("image_*") if p.is_file()
            )
            prompt = prompts.build_task_prompt(
                user_text=job["prompt_text"],
                image_names=image_names,
                today=date.today().isoformat(),
                threshold_mode=job["threshold_mode"],
                threshold_value=job["threshold_value"],
                alcohol_as_carbs=bool(
                    job["alcohol_as_carbs"] if "alcohol_as_carbs" in job.keys() else 1
                ),
            )
            resume_session = None

        db.set_status(job_id, db.STATUS_RUNNING)

        options = _build_options(directory, resume_session)
        raw_result: Any = None
        final_text = ""
        auth_error: str | None = None

        # Carry over what earlier passes did: answering a question or sending a
        # correction is more work on the same estimate, not a fresh start.
        live = LiveProgress(previous=db._steps_of(job))

        async with ClaudeSDKClient(options=options) as client:
            self._clients[job_id] = client
            await client.query(prompt)

            async for message in client.receive_response():
                _append_transcript(transcript, message)

                if isinstance(message, StreamEvent):
                    live.observe(message.event or {})
                    if live.should_write(db.now_ms()):
                        db.update_job(
                            job_id, progress=live.line(), steps=live.steps_json()
                        )
                    continue

                if isinstance(message, SystemMessage):
                    if message.subtype == "init":
                        session_id = message.data.get("session_id")
                        if session_id:
                            db.update_job(job_id, session_id=session_id)

                elif isinstance(message, AssistantMessage):
                    if message.session_id:
                        db.update_job(job_id, session_id=message.session_id)
                    if message.error:
                        auth_error = message.error
                    for block in message.content:
                        if isinstance(block, TextBlock):
                            final_text = block.text

                elif isinstance(message, ResultMessage):
                    db.update_job(
                        job_id,
                        progress="",
                        steps=live.steps_json(),
                        session_id=message.session_id,
                        cost_usd=message.total_cost_usd,
                        num_turns=message.num_turns,
                    )
                    if message.structured_output is not None:
                        raw_result = message.structured_output
                    elif message.result:
                        final_text = message.result
                    if message.is_error:
                        auth_error = auth_error or (
                            message.terminal_reason or "agent reported an error"
                        )

        if raw_result is None:
            raw_result = schema.extract_json(final_text)

        if raw_result is None:
            # Prefer whatever the agent actually said over the terse category. A run
            # that died on DNS reported itself as "server_error" while the text block
            # read "Unable to connect to API (ENOTIMP)" — the second one tells you
            # where to look, the first sends you hunting the wrong thing.
            spoken = final_text.strip()
            detail = (
                spoken
                if spoken.startswith("API Error")
                else (auth_error or spoken or "the agent did not return a JSON result")
            )
            raise schema.ResultError(detail[:300])

        result = schema.normalise(raw_result)
        result = schema.filter_questions(
            result, job["threshold_mode"], job["threshold_value"]
        )

        revision = (job["revision"] or 0) + (1 if (followup or correction) else 0)
        db.update_job(job_id, result_json=json.dumps(result), revision=revision, error=None)

        if result["follow_up_questions"]:
            db.set_status(job_id, db.STATUS_NEEDS_INPUT)
        else:
            db.set_status(job_id, db.STATUS_COMPLETED)

        log.info(
            "job %s -> %s (%d kcal, %d question(s))",
            job_id,
            db.get_job(job_id)["status"],
            result["total"]["calories"],
            len(result["follow_up_questions"]),
        )


def _append_transcript(path: Path, message: Any) -> None:
    """Best-effort human-readable log of the session, for the web UI."""
    try:
        entry: dict[str, Any] = {"ts": db.now_ms(), "type": type(message).__name__}
        if isinstance(message, AssistantMessage):
            parts = []
            for block in message.content:
                name = type(block).__name__
                if isinstance(block, TextBlock):
                    parts.append({"kind": "text", "text": block.text})
                elif name == "ToolUseBlock":
                    parts.append(
                        {
                            "kind": "tool_use",
                            "name": getattr(block, "name", "?"),
                            "input": getattr(block, "input", None),
                        }
                    )
                elif name in ("ServerToolUseBlock", "ServerToolResultBlock"):
                    parts.append({"kind": name, "name": getattr(block, "name", "?")})
                else:
                    parts.append({"kind": name})
            entry["content"] = parts
        elif isinstance(message, SystemMessage):
            entry["subtype"] = message.subtype
        elif isinstance(message, ResultMessage):
            entry["subtype"] = message.subtype
            entry["cost_usd"] = message.total_cost_usd
            entry["num_turns"] = message.num_turns
            entry["is_error"] = message.is_error
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, default=str) + "\n")
    except Exception:  # noqa: BLE001 - never let logging break a job
        log.debug("failed to append transcript", exc_info=True)


runner = JobRunner()


# ---------------------------------------------------------------- planning threads


def thread_dir(thread_id: str) -> Path:
    return config.DATA_DIR / "threads" / thread_id


def _build_planning_options(
    directory: Path, resume_session: str | None
) -> ClaudeAgentOptions:
    """Same sandbox as estimation — planning is another untrusted-input surface."""
    return ClaudeAgentOptions(
        model=config.MODEL,
        system_prompt=prompts.PLANNING_SYSTEM_PROMPT,
        cwd=str(directory),
        tools=list(config.AGENT_TOOLS),
        allowed_tools=list(config.AGENT_AUTO_APPROVED_TOOLS),
        disallowed_tools=list(config.AGENT_DISALLOWED_TOOLS),
        can_use_tool=_make_permission_callback(directory),
        permission_mode="default",
        setting_sources=[],
        max_turns=config.MAX_TURNS,
        output_format=schema.PLANNING_OUTPUT_FORMAT,
        # The stream is what makes a two-minute wait legible: thinking, tool calls,
        # and the reply arriving as it is written.
        include_partial_messages=True,
        resume=resume_session,
        env={"CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1"},
    )


class LiveProgress:
    """Turns the SDK's stream into the steps a waiting person sees.

    Only discrete steps — thinking, a search, a page being read. An earlier version
    also streamed the reply itself into this line, which arrived as a shifting window
    of half a sentence and read as a rendering fault rather than as progress. The
    answer is worth showing when it is finished; the work is worth showing while it
    happens, and those are different things.
    """

    #: A cap on the step list. A long run can search a dozen times; nobody reads more.
    MAX_STEPS = int(os.environ.get("MACROPAD_MAX_STEPS", "40"))

    def __init__(self, previous: list[dict[str, Any]] | None = None) -> None:
        self.status = ""
        self.steps: list[dict[str, Any]] = list(previous or [])
        self._written = ""

    def observe(self, event: dict[str, Any]) -> None:
        """Fold one streaming event in."""
        kind = event.get("type")

        if kind == "content_block_start":
            block = event.get("content_block") or {}
            if block.get("type") == "tool_use":
                note = _tool_note(block.get("name"), block.get("input"))
                if note:
                    self.status = note
                    self._record(note)
            elif block.get("type") == "thinking":
                self.status = "Thinking"
                self._record("Thinking")
            return

        if kind == "content_block_delta":
            delta = event.get("delta") or {}
            if delta.get("type") == "thinking_delta" and not self.status:
                self.status = "Thinking"
                self._record("Thinking")

    def _record(self, text: str) -> None:
        """Keep the step, so the work is still visible once the run is over.

        Consecutive duplicates are folded — a long answer can start several thinking
        blocks in a row, and "Thinking" five times says nothing "Thinking" once does
        not.
        """
        if not text:
            return
        if self.steps and self.steps[-1]["text"] == text:
            return
        self.steps.append({"at": db.now_ms(), "text": text})
        del self.steps[: max(0, len(self.steps) - self.MAX_STEPS)]

    def steps_json(self) -> str:
        return json.dumps(self.steps)

    def line(self) -> str:
        """The step in progress right now."""
        return self.status

    def should_write(self, _now_ms: float = 0.0) -> bool:
        """Only when something actually changed.

        Steps change a handful of times per run, so this needs no rate limiting —
        and writing on every token was what produced the flickering half-sentences.
        """
        current = self.line()
        if current == self._written:
            return False
        self._written = current
        return True


def _tool_note(name: str | None, payload: Any) -> str:
    """A tool call, described for the person waiting rather than by tool name."""
    tool = name or ""
    data = payload if isinstance(payload, dict) else {}

    if tool == "WebSearch":
        query = str(data.get("query") or "").strip()
        return f"Searching for {query}" if query else "Searching the web"
    if tool == "WebFetch":
        url = str(data.get("url") or "")
        host = urlparse(url).netloc.removeprefix("www.")
        return f"Reading {host}" if host else "Reading a page"
    if tool == "Read":
        return "Looking at your photo"
    # Anything else — the structured-output call the SDK makes to return JSON, for
    # instance — is machinery, not work anyone asked about. Say nothing rather than
    # padding the list with "Working".
    return ""


def _describe_step(block: Any) -> str:
    """Turn a tool call into something worth showing a waiting person.

    A planning answer can take a couple of minutes of searching, and a spinner with
    nothing behind it is indistinguishable from a hang — which is exactly how it gets
    reported. These are written for the person waiting, not as tool names.
    """
    name = type(block).__name__
    if name not in ("ToolUseBlock", "ServerToolUseBlock"):
        return ""

    return _tool_note(getattr(block, "name", ""), getattr(block, "input", None))


class ThreadRunner:
    """Runs one planning turn at a time per thread."""

    def __init__(self) -> None:
        self._tasks: dict[str, asyncio.Task[None]] = {}
        self._clients: dict[str, ClaudeSDKClient] = {}

    def is_active(self, thread_id: str) -> bool:
        task = self._tasks.get(thread_id)
        return task is not None and not task.done()

    def submit(self, thread_id: str, user_text: str, context_json: str) -> None:
        task = asyncio.create_task(self._guarded_run(thread_id, user_text, context_json))
        self._tasks[thread_id] = task
        task.add_done_callback(lambda _t, tid=thread_id: self._tasks.pop(tid, None))

    async def cancel(self, thread_id: str) -> bool:
        task = self._tasks.get(thread_id)
        if task is None or task.done():
            return False
        client = self._clients.get(thread_id)
        if client is not None:
            try:
                await asyncio.wait_for(client.interrupt(), timeout=5)
            except Exception as exc:
                log.info("thread interrupt failed for %s: %s", thread_id, exc)
        task.cancel()
        try:
            await asyncio.wait_for(asyncio.shield(task), timeout=15)
        except Exception:
            pass
        return True

    async def shutdown(self) -> None:
        for thread_id in list(self._tasks):
            await self.cancel(thread_id)

    async def _guarded_run(
        self, thread_id: str, user_text: str, context_json: str
    ) -> None:
        try:
            await self._run(thread_id, user_text, context_json)
        except asyncio.CancelledError:
            db.update_thread(thread_id, status=db.THREAD_IDLE, error="Cancelled", progress="")
            raise
        except Exception as exc:  # noqa: BLE001
            log.exception("planning turn failed for thread %s", thread_id)
            db.update_thread(
                thread_id,
                status=db.THREAD_FAILED,
                progress="",
                error=f"{type(exc).__name__}: {exc}",
            )
        finally:
            self._clients.pop(thread_id, None)

    async def _run(self, thread_id: str, user_text: str, context_json: str) -> None:
        thread = db.get_thread(thread_id)
        if thread is None:
            return

        directory = thread_dir(thread_id)
        directory.mkdir(parents=True, exist_ok=True)

        # Only the newest message's images are in the working directory; earlier ones
        # stay in the session's own history.
        image_names = sorted(p.name for p in directory.glob("image_*") if p.is_file())

        prompt = prompts.build_planning_prompt(
            user_text=user_text,
            image_names=image_names,
            context_json=context_json,
            is_first_turn=thread["session_id"] is None,
        )

        db.update_thread(thread_id, status=db.THREAD_RUNNING, error=None)

        options = _build_planning_options(directory, thread["session_id"])
        raw_result: Any = None
        final_text = ""
        cost = thread["cost_usd"] or 0.0

        async with ClaudeSDKClient(options=options) as client:
            self._clients[thread_id] = client
            await client.query(prompt)

            # Fresh per turn: these belong to this reply, not the conversation.
            live = LiveProgress()

            async for message in client.receive_response():
                _append_transcript(directory / "transcript.jsonl", message)

                if isinstance(message, StreamEvent):
                    live.observe(message.event or {})
                    if live.should_write(db.now_ms()):
                        db.update_thread(
                            thread_id, progress=live.line(), steps=live.steps_json()
                        )
                    continue

                if isinstance(message, SystemMessage) and message.subtype == "init":
                    session_id = message.data.get("session_id")
                    if session_id:
                        db.update_thread(thread_id, session_id=session_id)
                elif isinstance(message, AssistantMessage):
                    if message.session_id:
                        db.update_thread(thread_id, session_id=message.session_id)
                    for block in message.content:
                        if isinstance(block, TextBlock):
                            final_text = block.text
                        else:
                            # Fallback for when the partial stream is not delivering:
                            # better a coarse breadcrumb than a silent spinner.
                            note = _describe_step(block)
                            if note and not live.steps:
                                db.update_thread(thread_id, progress=note)
                elif isinstance(message, ResultMessage):
                    db.update_thread(thread_id, session_id=message.session_id)
                    cost += message.total_cost_usd or 0.0
                    if message.structured_output is not None:
                        raw_result = message.structured_output
                    elif message.result:
                        final_text = message.result

        if raw_result is None:
            raw_result = schema.extract_json(final_text)
        if raw_result is None:
            raise schema.ResultError("the assistant did not return a reply")

        # The steps move onto the reply; the thread's live copy is cleared so the
        # in-progress block disappears rather than lingering under the answer.
        turn_steps = live.steps_json()
        db.update_thread(thread_id, progress="", steps="[]")
        parsed = schema.normalise_planning(raw_result)

        db.add_message(
            message_id=uuid.uuid4().hex,
            thread_id=thread_id,
            role=db.ROLE_ASSISTANT,
            text=parsed["reply"],
            proposals=json.dumps(parsed["proposed_entries"]),
            steps=turn_steps,
        )

        fields: dict[str, Any] = {"status": db.THREAD_IDLE, "cost_usd": cost, "error": None}
        # The title is set once and then left alone, so a thread doesn't rename
        # itself out from under the user mid-conversation.
        if parsed["title"] and not thread["title"]:
            fields["title"] = parsed["title"]
        db.update_thread(thread_id, **fields)

        log.info(
            "thread %s replied (%d proposal(s))",
            thread_id,
            len(parsed["proposed_entries"]),
        )


thread_runner = ThreadRunner()


# ------------------------------------------------------------------- preset tags


async def tag_presets(presets: list[dict[str, Any]]) -> dict[int, list[str]]:
    """Label saved foods with search keywords.

    A one-shot with no tools: this runs in the background when the preset list
    changes, so the search box itself stays instant and offline.
    """
    if not presets:
        return {}

    options = ClaudeAgentOptions(
        model=config.SEARCH_MODEL,
        system_prompt=prompts.TAG_SYSTEM_PROMPT,
        tools=[],
        allowed_tools=[],
        disallowed_tools=list(config.AGENT_DISALLOWED_TOOLS),
        setting_sources=[],
        output_format=schema.TAG_OUTPUT_FORMAT,
        env={"CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1"},
    )

    raw: Any = None
    final_text = ""
    async for message in query(
        prompt=prompts.build_tag_prompt(presets), options=options
    ):
        if isinstance(message, AssistantMessage):
            for block in message.content:
                if isinstance(block, TextBlock):
                    final_text = block.text
        elif isinstance(message, ResultMessage):
            if message.structured_output is not None:
                raw = message.structured_output
            elif message.result:
                final_text = message.result

    if raw is None:
        raw = schema.extract_json(final_text)
    if raw is None:
        raise schema.ResultError("the tagger returned nothing usable")
    return schema.normalise_tags(raw, len(presets))
