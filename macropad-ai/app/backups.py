"""Per-user backup blobs.

Dropbox works fine for one person, but a Dropbox app registration has a linked-user
cap, and a household sharing this daemon already has somewhere private to put a
backup: here. One current file per user plus a few rotations, so a bad restore or a
truncated upload is recoverable.
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
KEEP_ROTATIONS = 3
MAX_BYTES = 32 * 1024 * 1024

#: Generations kept alongside the numbered rotations, oldest useful copy first.
#: Ages are minimums: the daily copy is replaced only once it is over a day old.
GENERATIONS = (("daily", 86_400), ("weekly", 604_800), ("monthly", 2_592_000))


def ensure_dir() -> None:
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)


def _safe(user_id: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_-]+", "", user_id)
    if not cleaned:
        raise ValueError("unusable user id")
    return cleaned


def _current(user_id: str) -> Path:
    return BACKUP_DIR / f"{_safe(user_id)}.json"


def save(user_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    ensure_dir()
    target = _current(user_id)
    body = json.dumps(payload, separators=(",", ":"))
    if len(body.encode()) > MAX_BYTES:
        raise ValueError("backup is too large")

    # Rotate before overwriting: the failure that matters is a good backup being
    # replaced by a broken one.
    if target.exists():
        for index in range(KEEP_ROTATIONS - 1, 0, -1):
            older = BACKUP_DIR / f"{_safe(user_id)}.{index}.json"
            newer = BACKUP_DIR / f"{_safe(user_id)}.{index + 1}.json"
            if older.exists():
                shutil.move(str(older), str(newer))
        shutil.copy2(target, BACKUP_DIR / f"{_safe(user_id)}.1.json")
        _age_generations(user_id, target)

    # Write beside the target and rename, so a dropped connection can't leave a
    # half-written backup in place of a whole one.
    staging = target.with_suffix(".partial")
    staging.write_text(body)
    staging.replace(target)
    log.info("stored backup for %s (%d bytes)", user_id, len(body))
    return meta(user_id)


def _age_generations(user_id: str, current: Path) -> None:
    """Keep a copy from a day, a week and a month ago.

    The numbered rotations only go three deep, which was several days of history
    when backups were taken by hand. Backups now happen minutes after a change, so
    three rotations can span an hour — and the mistake worth recovering from is not
    a lost phone but a deletion you notice on Thursday that happened on Monday.

    Each generation is replaced only once the copy in it is older than its own
    window, so these hold their distance no matter how often backups arrive.
    """
    now = time.time()
    for name, min_age_seconds in GENERATIONS:
        slot = BACKUP_DIR / f"{_safe(user_id)}.{name}.json"
        try:
            if slot.exists() and now - slot.stat().st_mtime < min_age_seconds:
                continue
            shutil.copy2(current, slot)
        except OSError:
            # A generation that cannot be written is not worth failing the backup for.
            log.warning("could not update the %s copy for %s", name, user_id)


def generations(user_id: str) -> list[dict[str, Any]]:
    """What older copies exist, newest first."""
    out = []
    for name, _ in GENERATIONS:
        slot = BACKUP_DIR / f"{_safe(user_id)}.{name}.json"
        if slot.exists():
            stat = slot.stat()
            out.append({"name": name, "updated_at": int(stat.st_mtime * 1000),
                        "size_bytes": stat.st_size})
    return out


def promote(user_id: str, name: str) -> dict[str, Any]:
    """Make an older copy the current one, keeping the current one as a rotation."""
    valid = {n for n, _ in GENERATIONS}
    if name not in valid:
        raise ValueError(f"unknown copy: {name} (have {', '.join(sorted(valid))})")
    slot = BACKUP_DIR / f"{_safe(user_id)}.{name}.json"
    if not slot.exists():
        raise ValueError(f"no {name} copy for {user_id}")
    # Round-trips through save() so the copy being replaced is itself rotated —
    # promoting the wrong one should not be the mistake you cannot undo.
    return save(user_id, json.loads(slot.read_text()))


def load(user_id: str) -> dict[str, Any] | None:
    target = _current(user_id)
    if not target.exists():
        return None
    try:
        return json.loads(target.read_text())
    except (OSError, json.JSONDecodeError):
        log.error("backup for %s is unreadable", user_id)
        return None


def meta(user_id: str) -> dict[str, Any]:
    target = _current(user_id)
    if not target.exists():
        return {"exists": False}
    stat = target.stat()
    payload = load(user_id) or {}
    return {
        "exists": True,
        "updated_at": int(stat.st_mtime * 1000),
        "size_bytes": stat.st_size,
        "export_date": payload.get("exportDate", ""),
        "device_id": payload.get("deviceId", ""),
        "days": len(payload.get("dailyMacros") or []),
        "presets": len(payload.get("presets") or []),
        "rotations": sum(
            1
            for index in range(1, KEEP_ROTATIONS + 1)
            if (BACKUP_DIR / f"{_safe(user_id)}.{index}.json").exists()
        ),
    }
