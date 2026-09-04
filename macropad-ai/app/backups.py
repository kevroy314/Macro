"""Per-user backups, kept as a series rather than one file that gets overwritten.

Dropbox works fine for one person, but a Dropbox app registration has a linked-user
cap, and a household sharing this daemon already has somewhere private to put a
backup: here.

Each backup is written to `backups/<user>/<timestamp>.json` and nothing is ever
overwritten. That is the whole design. The failure worth recovering from is not a lost
phone, it is noticing on Thursday that something was deleted on Monday — and any scheme
that overwrites the good copy loses that race as soon as backups become frequent.
Keeping the series makes restoring "pick a time", and old copies are thinned by one
retention rule rather than shuffled between named slots.

The app is a single writer per user, so there is nothing to merge: the newest backup is
the current one.
"""

from __future__ import annotations

import json
import logging
import re
import shutil
import time
from pathlib import Path
from typing import Any

from . import config

log = logging.getLogger("macropad.backups")

BACKUP_DIR = config.DATA_DIR / "backups"
MAX_BYTES = 32 * 1024 * 1024

#: Retention, applied newest first: keep everything recent, then one per day, then one
#: per week. A year of history costs a few megabytes at this payload size.
KEEP_ALL_HOURS = 48
KEEP_DAILY_FOR_DAYS = 30
KEEP_WEEKLY_FOR_DAYS = 365


def ensure_dir() -> None:
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)


def _safe(user_id: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_-]+", "", user_id)
    if not cleaned:
        raise ValueError("unusable user id")
    return cleaned


def _dir(user_id: str) -> Path:
    return BACKUP_DIR / _safe(user_id)


def _entries(user_id: str) -> list[Path]:
    """Every backup for a user, newest first."""
    folder = _dir(user_id)
    if not folder.is_dir():
        return []
    files = [f for f in folder.glob("*.json") if f.stem.isdigit()]
    return sorted(files, key=lambda f: int(f.stem), reverse=True)


def _adopt_legacy(user_id: str) -> None:
    """Move pre-series backups into the folder, keeping their age.

    Earlier versions wrote `<user>.json` plus numbered and named rotations. Their
    modification times are the only record of when they were taken, so they become
    positions in the series rather than being thrown away.
    """
    safe = _safe(user_id)
    candidates = [BACKUP_DIR / f"{safe}.json"]
    candidates += [BACKUP_DIR / f"{safe}.{n}.json" for n in (1, 2, 3)]
    candidates += [BACKUP_DIR / f"{safe}.{n}.json" for n in ("daily", "weekly", "monthly")]

    moved = 0
    for path in candidates:
        if not path.is_file():
            continue
        folder = _dir(user_id)
        folder.mkdir(parents=True, exist_ok=True)
        stamp = int(path.stat().st_mtime * 1000)
        target = folder / f"{stamp}.json"
        while target.exists():
            stamp += 1
            target = folder / f"{stamp}.json"
        shutil.move(str(path), str(target))
        moved += 1
    if moved:
        log.info("adopted %d existing backup(s) for %s", moved, user_id)


def save(user_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    ensure_dir()
    _adopt_legacy(user_id)

    body = json.dumps(payload, separators=(",", ":"))
    if len(body.encode()) > MAX_BYTES:
        raise ValueError("backup is too large")

    folder = _dir(user_id)
    folder.mkdir(parents=True, exist_ok=True)

    stamp = int(time.time() * 1000)
    target = folder / f"{stamp}.json"
    while target.exists():
        stamp += 1
        target = folder / f"{stamp}.json"

    # Write beside the target and rename, so a dropped connection cannot leave a
    # half-written backup that looks like a whole one.
    staging = folder / f"{stamp}.partial"
    staging.write_text(body)
    staging.replace(target)

    prune(user_id)
    log.info("stored backup for %s (%d bytes)", user_id, len(body))
    return meta(user_id)


def prune(user_id: str) -> int:
    """Thin the series with age. Returns how many were removed.

    The newest backup is never removed whatever its age: a phone that has not been
    opened in a year should still find its data waiting.
    """
    now = time.time() * 1000
    kept_buckets: set[tuple[str, int]] = set()
    removed = 0

    for index, path in enumerate(_entries(user_id)):
        if index == 0:
            continue

        age_hours = (now - int(path.stem)) / 3_600_000
        age_days = age_hours / 24

        if age_hours <= KEEP_ALL_HOURS:
            continue

        if age_days <= KEEP_DAILY_FOR_DAYS:
            bucket: tuple[str, int] | None = ("day", int(age_days))
        elif age_days <= KEEP_WEEKLY_FOR_DAYS:
            bucket = ("week", int(age_days // 7))
        else:
            bucket = None

        # Entries are newest first, so the first seen in a bucket is the one to keep.
        if bucket is not None and bucket not in kept_buckets:
            kept_buckets.add(bucket)
            continue

        try:
            path.unlink()
            removed += 1
        except OSError:
            pass

    return removed


def history(user_id: str) -> list[dict[str, Any]]:
    """Every restorable backup, newest first, with enough detail to choose one."""
    _adopt_legacy(user_id)
    out: list[dict[str, Any]] = []
    for path in _entries(user_id):
        entry: dict[str, Any] = {
            "at": int(path.stem),
            "size_bytes": path.stat().st_size,
            "days": 0,
            "presets": 0,
            "entries": 0,
        }
        try:
            body = json.loads(path.read_text())
            entry["days"] = len(body.get("dailyMacros") or [])
            entry["presets"] = len(body.get("presets") or [])
            entry["entries"] = len(body.get("macroEntries") or [])
        except (OSError, json.JSONDecodeError):
            entry["unreadable"] = True
        out.append(entry)
    return out


def load(user_id: str, at: int | None = None) -> dict[str, Any] | None:
    """The newest backup, or the one taken at [at].

    Reading never disturbs the series, and restoring on the phone fills gaps rather
    than overwriting, so choosing the wrong one costs nothing.
    """
    _adopt_legacy(user_id)
    entries = _entries(user_id)
    if not entries:
        return None

    if at is None:
        target = entries[0]
    else:
        target = next((p for p in entries if int(p.stem) == at), None)
        if target is None:
            return None

    try:
        return json.loads(target.read_text())
    except (OSError, json.JSONDecodeError):
        log.error("backup %s for %s is unreadable", target.name, user_id)
        return None


def meta(user_id: str) -> dict[str, Any]:
    _adopt_legacy(user_id)
    entries = _entries(user_id)
    if not entries:
        return {"exists": False, "count": 0}

    newest = entries[0]
    info: dict[str, Any] = {
        "exists": True,
        "updated_at": int(newest.stem),
        "size_bytes": newest.stat().st_size,
        "count": len(entries),
        "oldest_at": int(entries[-1].stem),
        "export_date": "",
        "device_id": "",
        "days": 0,
        "presets": 0,
    }
    try:
        body = json.loads(newest.read_text())
        info["export_date"] = body.get("exportDate") or ""
        info["device_id"] = body.get("deviceId") or ""
        info["days"] = len(body.get("dailyMacros") or [])
        info["presets"] = len(body.get("presets") or [])
    except (OSError, json.JSONDecodeError):
        log.error("newest backup for %s is unreadable", user_id)
    return info
