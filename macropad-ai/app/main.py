"""FastAPI app: JSON API for the phone, HTML log for the browser."""

from __future__ import annotations

import asyncio
import contextlib
import hmac
import io
import json
import logging
import re
import shutil
import time
import uuid
from pathlib import Path
from typing import Any

from fastapi import (
    Depends,
    FastAPI,
    File,
    Form,
    Header,
    HTTPException,
    Request,
    UploadFile,
)
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from fastapi.templating import Jinja2Templates
from PIL import Image, ImageOps

from . import auth, backups, config, db, releases, schema, users
from .runner import job_dir, runner, tag_presets, thread_dir, thread_runner

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s"
)
log = logging.getLogger("macropad")

API = "/api/v1"


# --------------------------------------------------------------------- lifecycle


@contextlib.asynccontextmanager
async def lifespan(_app: FastAPI):
    config.ensure_dirs()
    releases.ensure_dir()
    backups.ensure_dir()
    users.bootstrap()
    db.connect()
    primary = users.primary_user()
    if primary is not None:
        # Anything created before ownership existed belongs to the first user.
        db.adopt_unowned(primary.id)
    for user in users.all_users():
        log.info("user %s (%s) key: %s", user.id, user.name, user.key)
    log.info("Model: %s, max concurrent: %d", config.MODEL, config.MAX_CONCURRENT)

    # A job that was mid-flight when the daemon stopped has no usable partial
    # state, so re-run it from the top rather than leaving it stuck.
    for job in db.list_active_jobs():
        log.info("requeueing %s after restart", job["id"])
        db.add_event(job["id"], "requeued", "daemon restarted")
        runner.submit(job["id"])

    sweeper = asyncio.create_task(_retention_loop())
    try:
        yield
    finally:
        sweeper.cancel()
        await runner.shutdown()
        await thread_runner.shutdown()


app = FastAPI(title="MacroPad AI", docs_url=None, redoc_url=None, lifespan=lifespan)
templates = Jinja2Templates(directory=str(Path(__file__).parent / "web" / "templates"))


async def _retention_loop() -> None:
    while True:
        try:
            sweep_old_images()
        except Exception:  # noqa: BLE001
            log.exception("image retention sweep failed")
        await asyncio.sleep(24 * 60 * 60)


def sweep_old_images() -> int:
    """Delete uploaded photos past the retention window. Job rows survive."""
    if config.IMAGE_RETENTION_DAYS <= 0:
        return 0
    cutoff = time.time() - config.IMAGE_RETENTION_DAYS * 86400
    removed = 0
    for directory in config.JOBS_DIR.glob("*"):
        if not directory.is_dir():
            continue
        for image in directory.glob("image_*"):
            if image.stat().st_mtime < cutoff:
                image.unlink(missing_ok=True)
                removed += 1
    if removed:
        log.info("retention sweep removed %d image(s)", removed)
    return removed


# ------------------------------------------------------------------------ helpers


def _store_image(directory: Path, index: int, upload: UploadFile, raw: bytes) -> str:
    """Normalise an upload to a right-way-up, sensibly sized JPEG."""
    name = f"image_{index}.jpg"
    target = directory / name
    try:
        with Image.open(io.BytesIO(raw)) as img:
            img = ImageOps.exif_transpose(img)
            if img.mode not in ("RGB", "L"):
                img = img.convert("RGB")
            longest = max(img.size)
            if longest > config.MAX_IMAGE_EDGE:
                scale = config.MAX_IMAGE_EDGE / longest
                img = img.resize(
                    (max(1, int(img.width * scale)), max(1, int(img.height * scale))),
                    Image.LANCZOS,
                )
            img.save(target, format="JPEG", quality=85, optimize=True)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(
            status_code=400,
            detail=f"Could not read image '{upload.filename}': {exc}",
        ) from exc
    return name


def _require_job(job_id: str, owner: str | None = None) -> dict[str, Any]:
    job = db.get_job(job_id, owner=owner)
    if job is None:
        # 404 rather than 403: another user's job should not be distinguishable
        # from one that never existed.
        raise HTTPException(status_code=404, detail="No such job")
    return job


def _require_thread(thread_id: str, owner: str | None = None) -> dict[str, Any]:
    thread = db.get_thread(thread_id, owner=owner)
    if thread is None:
        raise HTTPException(status_code=404, detail="No such thread")
    return thread


# ---------------------------------------------------------------------- API: read


@app.get(f"{API}/health")
async def health() -> dict[str, Any]:
    """Unauthenticated liveness probe, also used by the app's Test connection."""
    return {
        "ok": True,
        "service": "macropad-ai",
        "model": config.MODEL,
        "active_jobs": len(db.list_active_jobs()),
    }


@app.get(f"{API}/jobs")
async def list_jobs(
    updated_since: int = 0,
    limit: int = 100,
    user: users.User = Depends(auth.current_user),
) -> dict[str, Any]:
    limit = max(1, min(limit, 500))
    jobs = db.list_jobs(user.id, updated_since=updated_since, limit=limit)
    return {
        "jobs": [db.job_to_api(j) for j in jobs],
        "server_time": db.now_ms(),
    }


@app.get(f"{API}/jobs/{{job_id}}")
async def get_job(
    job_id: str, user: users.User = Depends(auth.current_user)
) -> dict[str, Any]:
    return db.job_to_api(_require_job(job_id, user.id), include_events=True)


# --------------------------------------------------------------------- API: write


@app.post(f"{API}/jobs")
async def create_job(
    user: users.User = Depends(auth.current_user),
    text: str = Form(default=""),
    threshold_mode: str = Form(default="percent"),
    threshold_value: float = Form(default=10.0),
    client_job_id: str | None = Form(default=None),
    images: list[UploadFile] | None = File(default=None),
) -> dict[str, Any]:
    images = images or []
    if threshold_mode not in ("percent", "absolute"):
        raise HTTPException(status_code=400, detail="threshold_mode must be percent or absolute")
    if not text.strip() and not images:
        raise HTTPException(status_code=400, detail="Provide a photo, a note, or both")
    if len(images) > config.MAX_IMAGES_PER_JOB:
        raise HTTPException(
            status_code=400,
            detail=f"At most {config.MAX_IMAGES_PER_JOB} images per job",
        )

    # Retrying an upload over a flaky mobile connection must not double-log a meal.
    if client_job_id:
        existing = db.get_job_by_client_id(client_job_id, owner=user.id)
        if existing:
            return db.job_to_api(existing)

    job_id = uuid.uuid4().hex
    directory = job_dir(job_id)
    directory.mkdir(parents=True, exist_ok=True)

    stored = 0
    for index, upload in enumerate(images, start=1):
        raw = await upload.read()
        if not raw:
            continue
        _store_image(directory, index, upload, raw)
        stored += 1

    db.create_job(
        job_id=job_id,
        client_job_id=client_job_id,
        prompt_text=text,
        threshold_mode=threshold_mode,
        threshold_value=threshold_value,
        image_count=stored,
        owner=user.id,
    )
    runner.submit(job_id)
    log.info("job %s queued for %s (%d image(s))", job_id, user.id, stored)
    return db.job_to_api(_require_job(job_id, user.id))


@app.post(f"{API}/jobs/{{job_id}}/answers")
async def answer_job(
    job_id: str,
    payload: dict[str, Any],
    user: users.User = Depends(auth.current_user),
) -> dict[str, Any]:
    job = _require_job(job_id, user.id)
    if job["status"] != db.STATUS_NEEDS_INPUT:
        raise HTTPException(
            status_code=409, detail=f"Job is {job['status']}, not awaiting answers"
        )
    if not job["session_id"]:
        raise HTTPException(status_code=409, detail="Job has no resumable session")

    answers = payload.get("answers") or []
    if not isinstance(answers, list) or not answers:
        raise HTTPException(status_code=400, detail="answers must be a non-empty list")

    result = json.loads(job["result_json"]) if job["result_json"] else {}
    asked = {q["id"]: q["question"] for q in result.get("follow_up_questions", [])}

    pairs: list[tuple[str, str]] = []
    normalised: list[dict[str, str]] = []
    for answer in answers:
        if not isinstance(answer, dict):
            continue
        text = str(answer.get("answer") or "").strip()
        if not text:
            continue
        question_id = str(answer.get("question_id") or "")
        question = asked.get(question_id) or str(answer.get("question") or "")
        if not question:
            continue
        pairs.append((question, text))
        normalised.append(
            {"question_id": question_id, "question": question, "answer": text}
        )

    if not pairs:
        raise HTTPException(status_code=400, detail="No usable answers provided")

    db.add_answers(job_id, normalised)
    runner.submit(job_id, followup=pairs)
    return db.job_to_api(_require_job(job_id, user.id))


@app.post(f"{API}/jobs/{{job_id}}/cancel")
async def cancel_job(
    job_id: str, user: users.User = Depends(auth.current_user)
) -> dict[str, Any]:
    job = _require_job(job_id, user.id)
    if job["status"] in (db.STATUS_COMPLETED, db.STATUS_FAILED, db.STATUS_CANCELLED):
        return db.job_to_api(job)
    cancelled = await runner.cancel(job_id)
    if not cancelled:
        db.set_status(job_id, db.STATUS_CANCELLED, "cancelled while queued")
    return db.job_to_api(_require_job(job_id, user.id))


@app.post(f"{API}/jobs/{{job_id}}/retry")
async def retry_job(
    job_id: str,
    user: users.User = Depends(auth.current_user),
    text: str | None = Form(default=None),
    threshold_mode: str | None = Form(default=None),
    threshold_value: float | None = Form(default=None),
    client_job_id: str | None = Form(default=None),
    images: list[UploadFile] | None = File(default=None),
) -> dict[str, Any]:
    """Re-run a job with corrected inputs, as a new job linked to the old one."""
    job = _require_job(job_id, user.id)
    images = images or []

    if client_job_id:
        existing = db.get_job_by_client_id(client_job_id, owner=user.id)
        if existing:
            return db.job_to_api(existing)

    if runner.is_active(job_id):
        await runner.cancel(job_id)

    new_id = uuid.uuid4().hex
    new_dir = job_dir(new_id)
    new_dir.mkdir(parents=True, exist_ok=True)

    stored = 0
    if images:
        for index, upload in enumerate(images, start=1):
            raw = await upload.read()
            if not raw:
                continue
            _store_image(new_dir, index, upload, raw)
            stored += 1
    else:
        for image in sorted(job_dir(job_id).glob("image_*")):
            shutil.copy2(image, new_dir / image.name)
            stored += 1

    db.create_job(
        job_id=new_id,
        client_job_id=client_job_id,
        prompt_text=text if text is not None else job["prompt_text"],
        threshold_mode=threshold_mode or job["threshold_mode"],
        threshold_value=(
            threshold_value if threshold_value is not None else job["threshold_value"]
        ),
        image_count=stored,
        owner=user.id,
        parent_job_id=job_id,
    )
    db.set_status(job_id, db.STATUS_SUPERSEDED, f"replaced by {new_id}")
    runner.submit(new_id)
    return db.job_to_api(_require_job(new_id, user.id))


@app.delete(f"{API}/jobs/{{job_id}}")
async def delete_job(
    job_id: str, user: users.User = Depends(auth.current_user)
) -> dict[str, Any]:
    _require_job(job_id, user.id)
    if runner.is_active(job_id):
        await runner.cancel(job_id)
    db.delete_job(job_id)
    with contextlib.suppress(OSError):
        shutil.rmtree(job_dir(job_id))
    return {"deleted": job_id}


# ------------------------------------------------------------------ API: planning


def _store_thread_images(directory: Path, images: list[UploadFile], raws: list[bytes]) -> int:
    """Replace the thread directory's images with this message's attachments.

    Only the newest message's images live on disk: earlier ones are already in the
    Claude session's own history, and leaving them would have the agent re-reading
    last week's lunch on every turn.
    """
    for stale in directory.glob("image_*"):
        stale.unlink(missing_ok=True)
    stored = 0
    for index, (upload, raw) in enumerate(zip(images, raws), start=1):
        if not raw:
            continue
        _store_image(directory, index, upload, raw)
        stored += 1
    return stored


@app.post(f"{API}/threads")
async def create_thread(
    user: users.User = Depends(auth.current_user),
    text: str = Form(...),
    context: str = Form(default="{}"),
    client_thread_id: str | None = Form(default=None),
    images: list[UploadFile] | None = File(default=None),
) -> dict[str, Any]:
    images = images or []
    if not text.strip() and not images:
        raise HTTPException(status_code=400, detail="Say something to start a thread")
    if len(images) > config.MAX_IMAGES_PER_JOB:
        raise HTTPException(status_code=400, detail="Too many images")

    if client_thread_id:
        existing = db.get_thread_by_client_id(client_thread_id, owner=user.id)
        if existing:
            return db.thread_to_api(existing, include_messages=True)

    thread_id = uuid.uuid4().hex
    directory = thread_dir(thread_id)
    directory.mkdir(parents=True, exist_ok=True)

    raws = [await upload.read() for upload in images]
    stored = _store_thread_images(directory, images, raws)

    db.create_thread(thread_id, client_thread_id, owner=user.id)
    db.add_message(uuid.uuid4().hex, thread_id, db.ROLE_USER, text, image_count=stored)
    thread_runner.submit(thread_id, text, context)
    log.info("thread %s started by %s", thread_id, user.id)
    return db.thread_to_api(db.get_thread(thread_id), include_messages=True)


@app.get(f"{API}/threads")
async def list_threads(
    updated_since: int = 0,
    limit: int = 100,
    user: users.User = Depends(auth.current_user),
) -> dict[str, Any]:
    limit = max(1, min(limit, 200))
    return {
        "threads": [
            db.thread_to_api(t, include_messages=True)
            for t in db.list_threads(user.id, updated_since=updated_since, limit=limit)
        ],
        "server_time": db.now_ms(),
    }


@app.get(f"{API}/threads/{{thread_id}}")
async def get_thread(
    thread_id: str, user: users.User = Depends(auth.current_user)
) -> dict[str, Any]:
    return db.thread_to_api(_require_thread(thread_id, user.id), include_messages=True)


@app.post(f"{API}/threads/{{thread_id}}/messages")
async def send_thread_message(
    thread_id: str,
    user: users.User = Depends(auth.current_user),
    text: str = Form(...),
    context: str = Form(default="{}"),
    images: list[UploadFile] | None = File(default=None),
) -> dict[str, Any]:
    _require_thread(thread_id, user.id)
    if thread_runner.is_active(thread_id):
        raise HTTPException(status_code=409, detail="The assistant is still replying")

    images = images or []
    directory = thread_dir(thread_id)
    directory.mkdir(parents=True, exist_ok=True)
    raws = [await upload.read() for upload in images]
    stored = _store_thread_images(directory, images, raws)

    db.add_message(uuid.uuid4().hex, thread_id, db.ROLE_USER, text, image_count=stored)
    thread_runner.submit(thread_id, text, context)
    return db.thread_to_api(db.get_thread(thread_id), include_messages=True)


@app.post(f"{API}/threads/{{thread_id}}/cancel")
async def cancel_thread(
    thread_id: str, user: users.User = Depends(auth.current_user)
) -> dict[str, Any]:
    _require_thread(thread_id, user.id)
    await thread_runner.cancel(thread_id)
    db.update_thread(thread_id, status=db.THREAD_IDLE)
    return db.thread_to_api(db.get_thread(thread_id), include_messages=True)


@app.delete(f"{API}/threads/{{thread_id}}")
async def delete_thread(
    thread_id: str, user: users.User = Depends(auth.current_user)
) -> dict[str, Any]:
    _require_thread(thread_id, user.id)
    await thread_runner.cancel(thread_id)
    db.delete_thread(thread_id)
    with contextlib.suppress(OSError):
        shutil.rmtree(thread_dir(thread_id))
    return {"deleted": thread_id}


@app.post(f"{API}/preset-tags", dependencies=[Depends(auth.require_api_key)])
async def tag_presets_endpoint(payload: dict[str, Any]) -> dict[str, Any]:
    """Label presets with search keywords so the app can search them by meaning."""
    raw = payload.get("presets") or []
    presets: list[dict[str, Any]] = []
    ids: list[Any] = []
    for item in raw[:200]:
        if not isinstance(item, dict):
            continue
        name = str(item.get("name") or "").strip()
        if not name:
            continue
        ids.append(item.get("id"))
        presets.append(
            {
                "name": name[:80],
                "protein_g": int(item.get("protein_g") or 0),
                "carbs_g": int(item.get("carbs_g") or 0),
                "fat_g": int(item.get("fat_g") or 0),
                "calories": int(item.get("calories") or 0),
            }
        )

    if not presets:
        return {"tags": {}}

    try:
        tagged = await tag_presets(presets)
    except Exception as exc:  # noqa: BLE001
        log.warning("preset tagging failed: %s", exc)
        raise HTTPException(status_code=502, detail=f"Tagging failed: {exc}") from exc

    return {
        "tags": {
            str(ids[index]): keywords
            for index, keywords in tagged.items()
            if index < len(ids)
        }
    }


# ------------------------------------------------------------------ API: backups


@app.get(f"{API}/backup/meta")
async def backup_meta(user: users.User = Depends(auth.current_user)) -> dict[str, Any]:
    return backups.meta(user.id)


@app.get(f"{API}/backup")
async def get_backup(user: users.User = Depends(auth.current_user)) -> dict[str, Any]:
    payload = backups.load(user.id)
    if payload is None:
        raise HTTPException(status_code=404, detail="No backup stored yet")
    return {"backup": payload, "meta": backups.meta(user.id)}


@app.put(f"{API}/backup")
async def put_backup(
    payload: dict[str, Any], user: users.User = Depends(auth.current_user)
) -> dict[str, Any]:
    body = payload.get("backup")
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="Expected a backup object")
    try:
        return backups.save(user.id, body)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


# -------------------------------------------------------------------- API: users


def _require_primary(user: users.User) -> None:
    """Only the first user can mint keys.

    Any key could otherwise create more keys, which turns "share the server with my
    wife" into "anyone who ever held a key can add themselves back".
    """
    primary = users.primary_user()
    if primary is None or user.id != primary.id:
        raise HTTPException(status_code=403, detail="Only the primary user can do that")


@app.get(f"{API}/users")
async def list_users(user: users.User = Depends(auth.current_user)) -> dict[str, Any]:
    _require_primary(user)
    return {
        "users": [
            {"id": u.id, "name": u.name, "email": u.email, "is_primary": index == 0}
            for index, u in enumerate(users.all_users())
        ]
    }


@app.post(f"{API}/users")
async def create_user(
    payload: dict[str, Any], user: users.User = Depends(auth.current_user)
) -> dict[str, Any]:
    """Invite someone. Returns their key once — the app turns it into a QR code."""
    _require_primary(user)

    name = str(payload.get("name") or "").strip()
    if not name:
        raise HTTPException(status_code=400, detail="A name is required")
    email = str(payload.get("email") or "").strip()

    base = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") or "user"
    user_id = base
    suffix = 2
    while users.by_id(user_id) is not None:
        user_id = f"{base}{suffix}"
        suffix += 1

    created = users.add_user(user_id, name, email)
    log.info("user %s invited by %s", created.id, user.id)
    return {"id": created.id, "name": created.name, "email": created.email, "key": created.key}


@app.get(f"{API}/whoami")
async def whoami(user: users.User = Depends(auth.current_user)) -> dict[str, Any]:
    primary = users.primary_user()
    return {
        "id": user.id,
        "name": user.name,
        "email": user.email,
        "is_primary": primary is not None and primary.id == user.id,
    }


# ----------------------------------------------------------------- API: releases


@app.get(f"{API}/release/latest", dependencies=[Depends(auth.require_api_key)])
async def latest_release(request: Request) -> dict[str, Any]:
    """The newest build this machine is hosting, or `available: false`."""
    release = releases.latest_release()
    if release is None:
        return {"available": False}

    # Absolute, for pasting into a browser. X-Forwarded-Host carries the port the
    # client actually used; plain Host loses it behind a proxy on a non-443 port.
    # The app builds its own URL from its configured base and ignores this.
    scheme = request.headers.get("x-forwarded-proto") or request.url.scheme
    host = (
        request.headers.get("x-forwarded-host")
        or request.headers.get("host")
        or request.url.netloc
    )
    return {
        "available": True,
        **release,
        "download_url": f"{scheme}://{host}{API}/release/download/{release['file']}",
    }


@app.get(f"{API}/release/download/{{filename}}")
async def download_release(
    filename: str,
    key: str | None = None,
    authorization: str | None = Header(default=None),
    x_macropad_key: str | None = Header(default=None),
) -> Any:
    """Serve an APK.

    Also accepts `?key=` so the URL can be pasted into a browser or handed to a
    download manager that cannot set headers.
    """
    presented = key
    if not presented and authorization and authorization.lower().startswith("bearer "):
        presented = authorization[7:].strip()
    if not presented:
        presented = x_macropad_key
    if not presented or not hmac.compare_digest(presented.strip(), auth.api_key()):
        raise HTTPException(status_code=401, detail="Invalid or missing API key")

    path = releases.release_path(filename)
    if path is None:
        raise HTTPException(status_code=404, detail="No such release")
    return FileResponse(
        path,
        media_type="application/vnd.android.package-archive",
        filename=path.name,
    )


# ------------------------------------------------------------------------ web UI


def _web_user(request: Request) -> users.User | None:
    """Who is looking at the web log.

    oauth2-proxy passes the signed-in address as X-Email. Matching it to a user is
    what keeps the log showing only its owner's food, rather than everyone's.
    """
    email = request.headers.get("x-email") or request.headers.get("x-forwarded-email")
    user = users.by_email(email or "")
    if user is not None:
        return user
    # Running without the proxy (localhost, debugging) — fall back to the first user.
    if not email:
        return users.primary_user()
    return None


@app.get("/", response_class=HTMLResponse)
async def web_index(request: Request) -> Any:
    viewer = _web_user(request)
    jobs = (
        [db.job_to_api(j) for j in db.list_jobs(viewer.id, limit=200)]
        if viewer
        else []
    )
    return templates.TemplateResponse(
        "index.html",
        {
            "request": request,
            "jobs": jobs,
            "model": config.MODEL,
            "active": len(db.list_active_jobs()),
            "release": releases.latest_release(),
            "api_key": viewer.key if viewer else "",
            "viewer": viewer,
            "usage": db.usage_by_owner(),
        },
    )


@app.get("/jobs/{job_id}", response_class=HTMLResponse)
async def web_job(request: Request, job_id: str) -> Any:
    viewer = _web_user(request)
    if viewer is None:
        raise HTTPException(status_code=403, detail="Not a known user")
    job = _require_job(job_id, viewer.id)
    directory = job_dir(job_id)
    images = sorted(p.name for p in directory.glob("image_*") if p.is_file())
    transcript: list[dict[str, Any]] = []
    transcript_path = directory / "transcript.jsonl"
    if transcript_path.exists():
        for line in transcript_path.read_text(encoding="utf-8").splitlines():
            with contextlib.suppress(json.JSONDecodeError):
                transcript.append(json.loads(line))
    return templates.TemplateResponse(
        "job.html",
        {
            "request": request,
            "job": db.job_to_api(job, include_events=True),
            "images": images,
            "transcript": transcript,
        },
    )


@app.get("/jobs/{job_id}/images/{name}")
async def web_image(request: Request, job_id: str, name: str) -> Any:
    viewer = _web_user(request)
    if viewer is None:
        raise HTTPException(status_code=404, detail="No such image")
    # Photos are the most sensitive thing here; check the owner before serving one.
    _require_job(job_id, viewer.id)
    if not name.startswith("image_") or "/" in name or ".." in name:
        raise HTTPException(status_code=404, detail="No such image")
    path = job_dir(job_id) / name
    if not path.is_file():
        raise HTTPException(status_code=404, detail="No such image")
    return FileResponse(path, media_type="image/jpeg")


@app.post("/jobs/{job_id}/cancel-web")
async def web_cancel(request: Request, job_id: str) -> Any:
    viewer = _web_user(request)
    if viewer is None:
        raise HTTPException(status_code=403, detail="Not a known user")
    _require_job(job_id, viewer.id)
    await runner.cancel(job_id)
    return JSONResponse({"ok": True})
