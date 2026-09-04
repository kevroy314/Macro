"""SQLite storage. Small enough that a hand-rolled DAO beats an ORM here."""

from __future__ import annotations

import json
import sqlite3
import threading
import time
from typing import Any, Iterable

from . import config

_conn: sqlite3.Connection | None = None
_lock = threading.RLock()

SCHEMA = """
CREATE TABLE IF NOT EXISTS jobs (
    id              TEXT PRIMARY KEY,
    client_job_id   TEXT UNIQUE,
    parent_job_id   TEXT,
    status          TEXT NOT NULL,
    prompt_text     TEXT NOT NULL DEFAULT '',
    threshold_mode  TEXT NOT NULL DEFAULT 'percent',
    threshold_value REAL NOT NULL DEFAULT 10,
    image_count     INTEGER NOT NULL DEFAULT 0,
    session_id      TEXT,
    result_json     TEXT,
    revision        INTEGER NOT NULL DEFAULT 0,
    error           TEXT,
    cost_usd        REAL,
    num_turns       INTEGER,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    finished_at     INTEGER
);
CREATE INDEX IF NOT EXISTS idx_jobs_updated ON jobs(updated_at);
CREATE INDEX IF NOT EXISTS idx_jobs_status  ON jobs(status);

CREATE TABLE IF NOT EXISTS answers (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id      TEXT NOT NULL,
    question_id TEXT NOT NULL,
    question    TEXT NOT NULL,
    answer      TEXT NOT NULL,
    created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_answers_job ON answers(job_id);

CREATE TABLE IF NOT EXISTS events (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id  TEXT NOT NULL,
    ts      INTEGER NOT NULL,
    kind    TEXT NOT NULL,
    detail  TEXT
);
CREATE INDEX IF NOT EXISTS idx_events_job ON events(job_id);
"""

# Terminal from the daemon's point of view. NEEDS_INPUT is not terminal: answering
# a follow-up resumes the same Claude session.
STATUS_QUEUED = "queued"
STATUS_RUNNING = "running"
STATUS_NEEDS_INPUT = "needs_input"
STATUS_COMPLETED = "completed"
STATUS_FAILED = "failed"
STATUS_CANCELLED = "cancelled"
STATUS_SUPERSEDED = "superseded"

ACTIVE_STATUSES = (STATUS_QUEUED, STATUS_RUNNING)


def now_ms() -> int:
    return int(time.time() * 1000)


def connect() -> sqlite3.Connection:
    global _conn
    with _lock:
        if _conn is None:
            config.DATA_DIR.mkdir(parents=True, exist_ok=True)
            _conn = sqlite3.connect(config.DB_PATH, check_same_thread=False)
            _conn.row_factory = sqlite3.Row
            _conn.execute("PRAGMA journal_mode=WAL")
            _conn.execute("PRAGMA busy_timeout=5000")
            _conn.executescript(SCHEMA)
            _conn.executescript(THREAD_SCHEMA)
            _add_owner_columns(_conn)
            _add_progress_column(_conn)
            _conn.commit()
        return _conn


def _add_progress_column(conn: sqlite3.Connection) -> None:
    """A line describing what a run is doing right now, for jobs and threads alike."""
    # Each reply carries the steps that produced it, so a long conversation shows
    # the work per turn instead of one growing pile at the bottom.
    columns = {row[1] for row in conn.execute("PRAGMA table_info(thread_messages)")}
    if "steps" not in columns:
        conn.execute(
            "ALTER TABLE thread_messages ADD COLUMN steps TEXT NOT NULL DEFAULT '[]'"
        )

    for table in ("threads", "jobs"):
        columns = {row[1] for row in conn.execute(f"PRAGMA table_info({table})")}
        if "progress" not in columns:
            conn.execute(
                f"ALTER TABLE {table} ADD COLUMN progress TEXT NOT NULL DEFAULT ''"
            )
        # The steps taken so far, as a JSON array. Kept alongside the live line so
        # the work stays visible after the run finishes.
        if "steps" not in columns:
            conn.execute(f"ALTER TABLE {table} ADD COLUMN steps TEXT NOT NULL DEFAULT '[]'")


def _add_owner_columns(conn: sqlite3.Connection) -> None:
    """Introduce ownership on databases created before there were users."""
    for table in ("jobs", "threads"):
        columns = {row[1] for row in conn.execute(f"PRAGMA table_info({table})")}
        if "owner" not in columns:
            conn.execute(f"ALTER TABLE {table} ADD COLUMN owner TEXT NOT NULL DEFAULT ''")
            conn.execute(f"CREATE INDEX IF NOT EXISTS idx_{table}_owner ON {table}(owner)")


def adopt_unowned(owner: str) -> None:
    """Assign pre-multi-user rows to the first user."""
    if not owner:
        return
    for table in ("jobs", "threads"):
        _exec(f"UPDATE {table} SET owner = ? WHERE owner = ''", (owner,))


def _exec(sql: str, params: Iterable[Any] = ()) -> sqlite3.Cursor:
    conn = connect()
    with _lock:
        cur = conn.execute(sql, tuple(params))
        conn.commit()
        return cur


def _query(sql: str, params: Iterable[Any] = ()) -> list[sqlite3.Row]:
    conn = connect()
    with _lock:
        return conn.execute(sql, tuple(params)).fetchall()


# --------------------------------------------------------------------------- jobs


def create_job(
    job_id: str,
    client_job_id: str | None,
    prompt_text: str,
    threshold_mode: str,
    threshold_value: float,
    image_count: int,
    owner: str,
    parent_job_id: str | None = None,
) -> None:
    ts = now_ms()
    _exec(
        """INSERT INTO jobs (id, client_job_id, parent_job_id, status, prompt_text,
                             threshold_mode, threshold_value, image_count, owner,
                             created_at, updated_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
        (
            job_id,
            client_job_id,
            parent_job_id,
            STATUS_QUEUED,
            prompt_text,
            threshold_mode,
            threshold_value,
            image_count,
            owner,
            ts,
            ts,
        ),
    )
    add_event(job_id, "created", None)


def get_job(job_id: str, owner: str | None = None) -> dict[str, Any] | None:
    """Fetch a job. Pass `owner` to make another user's job simply not exist."""
    if owner is None:
        rows = _query("SELECT * FROM jobs WHERE id = ?", (job_id,))
    else:
        rows = _query("SELECT * FROM jobs WHERE id = ? AND owner = ?", (job_id, owner))
    return dict(rows[0]) if rows else None


def get_job_by_client_id(client_job_id: str, owner: str | None = None) -> dict[str, Any] | None:
    if owner is None:
        rows = _query("SELECT * FROM jobs WHERE client_job_id = ?", (client_job_id,))
    else:
        rows = _query(
            "SELECT * FROM jobs WHERE client_job_id = ? AND owner = ?",
            (client_job_id, owner),
        )
    return dict(rows[0]) if rows else None


def list_jobs(owner: str, updated_since: int = 0, limit: int = 100) -> list[dict[str, Any]]:
    rows = _query(
        """SELECT * FROM jobs WHERE owner = ? AND updated_at > ?
           ORDER BY updated_at DESC LIMIT ?""",
        (owner, updated_since, limit),
    )
    return [dict(r) for r in rows]


def usage_by_owner() -> list[dict[str, Any]]:
    """Job counts and reported cost per user, for the web log."""
    rows = _query(
        """SELECT owner, COUNT(*) AS jobs, COALESCE(SUM(cost_usd), 0) AS cost
           FROM jobs GROUP BY owner ORDER BY cost DESC"""
    )
    return [dict(r) for r in rows]


def list_active_jobs() -> list[dict[str, Any]]:
    rows = _query(
        "SELECT * FROM jobs WHERE status IN (?,?) ORDER BY created_at ASC",
        ACTIVE_STATUSES,
    )
    return [dict(r) for r in rows]


def update_job(job_id: str, **fields: Any) -> None:
    if not fields:
        return
    fields["updated_at"] = now_ms()
    assignments = ", ".join(f"{k} = ?" for k in fields)
    _exec(
        f"UPDATE jobs SET {assignments} WHERE id = ?",
        (*fields.values(), job_id),
    )


def _steps_of(row: dict[str, Any]) -> list[dict[str, Any]]:
    if "steps" not in row.keys() or not row["steps"]:
        return []
    try:
        parsed = json.loads(row["steps"])
        return parsed if isinstance(parsed, list) else []
    except (TypeError, ValueError):
        return []


def set_status(job_id: str, status: str, detail: str | None = None) -> None:
    fields: dict[str, Any] = {"status": status}
    if status != STATUS_RUNNING:
        # Clear the live breadcrumb here rather than at each call site, so no exit
        # path can leave a job showing what it was doing when it stopped.
        fields["progress"] = ""
    if status in (STATUS_COMPLETED, STATUS_FAILED, STATUS_CANCELLED, STATUS_SUPERSEDED):
        fields["finished_at"] = now_ms()
    update_job(job_id, **fields)
    add_event(job_id, status, detail)


def delete_job(job_id: str) -> None:
    _exec("DELETE FROM answers WHERE job_id = ?", (job_id,))
    _exec("DELETE FROM events WHERE job_id = ?", (job_id,))
    _exec("DELETE FROM jobs WHERE id = ?", (job_id,))


# ------------------------------------------------------------------------ answers


def add_answers(job_id: str, answers: list[dict[str, str]]) -> None:
    ts = now_ms()
    conn = connect()
    with _lock:
        conn.executemany(
            """INSERT INTO answers (job_id, question_id, question, answer, created_at)
               VALUES (?,?,?,?,?)""",
            [
                (job_id, a.get("question_id", ""), a.get("question", ""), a["answer"], ts)
                for a in answers
            ],
        )
        conn.commit()


def get_answers(job_id: str) -> list[dict[str, Any]]:
    rows = _query(
        "SELECT * FROM answers WHERE job_id = ? ORDER BY id ASC", (job_id,)
    )
    return [dict(r) for r in rows]


# ------------------------------------------------------------------------- events


def add_event(job_id: str, kind: str, detail: str | None = None) -> None:
    _exec(
        "INSERT INTO events (job_id, ts, kind, detail) VALUES (?,?,?,?)",
        (job_id, now_ms(), kind, detail),
    )


def get_events(job_id: str) -> list[dict[str, Any]]:
    rows = _query("SELECT * FROM events WHERE job_id = ? ORDER BY id ASC", (job_id,))
    return [dict(r) for r in rows]


# ------------------------------------------------------------------------- helpers


def job_to_api(job: dict[str, Any], include_events: bool = False) -> dict[str, Any]:
    """Shape a job row for the HTTP API."""
    out = {
        "id": job["id"],
        "client_job_id": job["client_job_id"],
        "parent_job_id": job["parent_job_id"],
        "status": job["status"],
        # What the run is doing right now, and everything it has done.
        "progress": job["progress"] if "progress" in job.keys() else "",
        "steps": _steps_of(job),
        "prompt_text": job["prompt_text"],
        "threshold_mode": job["threshold_mode"],
        "threshold_value": job["threshold_value"],
        "image_count": job["image_count"],
        "revision": job["revision"],
        "error": job["error"],
        "cost_usd": job["cost_usd"],
        "created_at": job["created_at"],
        "updated_at": job["updated_at"],
        "finished_at": job["finished_at"],
        "result": json.loads(job["result_json"]) if job["result_json"] else None,
    }
    if include_events:
        out["events"] = get_events(job["id"])
        out["answers"] = get_answers(job["id"])
    return out


# ======================================================================
# Planning threads
# ======================================================================

THREAD_SCHEMA = """
CREATE TABLE IF NOT EXISTS threads (
    id               TEXT PRIMARY KEY,
    client_thread_id TEXT UNIQUE,
    title            TEXT NOT NULL DEFAULT '',
    session_id       TEXT,
    status           TEXT NOT NULL DEFAULT 'idle',
    error            TEXT,
    cost_usd         REAL NOT NULL DEFAULT 0,
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_threads_updated ON threads(updated_at);

CREATE TABLE IF NOT EXISTS thread_messages (
    id           TEXT PRIMARY KEY,
    thread_id    TEXT NOT NULL,
    role         TEXT NOT NULL,
    text         TEXT NOT NULL DEFAULT '',
    image_count  INTEGER NOT NULL DEFAULT 0,
    proposals    TEXT,
    created_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_thread_messages_thread ON thread_messages(thread_id, created_at);
"""

THREAD_IDLE = "idle"
THREAD_RUNNING = "running"
THREAD_FAILED = "failed"

ROLE_USER = "user"
ROLE_ASSISTANT = "assistant"


def create_thread(
    thread_id: str, client_thread_id: str | None, owner: str, title: str = ""
) -> None:
    ts = now_ms()
    _exec(
        """INSERT INTO threads (id, client_thread_id, title, status, owner, created_at, updated_at)
           VALUES (?,?,?,?,?,?,?)""",
        (thread_id, client_thread_id, title, THREAD_IDLE, owner, ts, ts),
    )


def get_thread(thread_id: str, owner: str | None = None) -> dict[str, Any] | None:
    if owner is None:
        rows = _query("SELECT * FROM threads WHERE id = ?", (thread_id,))
    else:
        rows = _query("SELECT * FROM threads WHERE id = ? AND owner = ?", (thread_id, owner))
    return dict(rows[0]) if rows else None


def get_thread_by_client_id(
    client_thread_id: str, owner: str | None = None
) -> dict[str, Any] | None:
    if owner is None:
        rows = _query("SELECT * FROM threads WHERE client_thread_id = ?", (client_thread_id,))
    else:
        rows = _query(
            "SELECT * FROM threads WHERE client_thread_id = ? AND owner = ?",
            (client_thread_id, owner),
        )
    return dict(rows[0]) if rows else None


def list_threads(owner: str, updated_since: int = 0, limit: int = 100) -> list[dict[str, Any]]:
    rows = _query(
        "SELECT * FROM threads WHERE owner = ? AND updated_at > ? ORDER BY updated_at DESC LIMIT ?",
        (owner, updated_since, limit),
    )
    return [dict(r) for r in rows]


def list_active_threads() -> list[dict[str, Any]]:
    rows = _query("SELECT * FROM threads WHERE status = ?", (THREAD_RUNNING,))
    return [dict(r) for r in rows]


def update_thread(thread_id: str, **fields: Any) -> None:
    if not fields:
        return
    fields["updated_at"] = now_ms()
    assignments = ", ".join(f"{k} = ?" for k in fields)
    _exec(f"UPDATE threads SET {assignments} WHERE id = ?", (*fields.values(), thread_id))


def delete_thread(thread_id: str) -> None:
    _exec("DELETE FROM thread_messages WHERE thread_id = ?", (thread_id,))
    _exec("DELETE FROM threads WHERE id = ?", (thread_id,))


def add_message(
    message_id: str,
    thread_id: str,
    role: str,
    text: str,
    image_count: int = 0,
    proposals: str | None = None,
    steps: str = "[]",
) -> None:
    _exec(
        """INSERT INTO thread_messages
           (id, thread_id, role, text, image_count, proposals, steps, created_at)
           VALUES (?,?,?,?,?,?,?,?)""",
        (message_id, thread_id, role, text, image_count, proposals, steps, now_ms()),
    )
    update_thread(thread_id)


def get_messages(thread_id: str) -> list[dict[str, Any]]:
    rows = _query(
        "SELECT * FROM thread_messages WHERE thread_id = ? ORDER BY created_at ASC, rowid ASC",
        (thread_id,),
    )
    return [dict(r) for r in rows]


def thread_to_api(thread: dict[str, Any], include_messages: bool = False) -> dict[str, Any]:
    out = {
        "id": thread["id"],
        "client_thread_id": thread["client_thread_id"],
        "title": thread["title"],
        "status": thread["status"],
        "error": thread["error"],
        "cost_usd": thread["cost_usd"],
        "created_at": thread["created_at"],
        "updated_at": thread["updated_at"],
        # What the turn is doing right now, and everything it has done.
        "progress": thread["progress"] if "progress" in thread.keys() else "",
        "steps": _steps_of(thread),
    }
    if include_messages:
        out["messages"] = [
            {
                "id": m["id"],
                "role": m["role"],
                "text": m["text"],
                "image_count": m["image_count"],
                "proposals": json.loads(m["proposals"]) if m["proposals"] else [],
                "steps": _steps_of(m),
                "created_at": m["created_at"],
            }
            for m in get_messages(thread["id"])
        ]
    return out
