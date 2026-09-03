"""APK hosting, so the phone can update itself from this machine.

Releases live in ``data/releases``: one signed APK per build plus a
``releases.json`` index written by ``MacroPad/build_release.sh``. Nothing here
parses an APK — the build script already knows the version it just produced, so it
records it rather than making the daemon guess.
"""

from __future__ import annotations

import hashlib
import json
import logging
from pathlib import Path
from typing import Any

from . import config

log = logging.getLogger("macropad.releases")

RELEASES_DIR = config.DATA_DIR / "releases"
INDEX_PATH = RELEASES_DIR / "releases.json"


def ensure_dir() -> None:
    RELEASES_DIR.mkdir(parents=True, exist_ok=True)


def _load_index() -> list[dict[str, Any]]:
    if not INDEX_PATH.exists():
        return []
    try:
        data = json.loads(INDEX_PATH.read_text())
    except (json.JSONDecodeError, OSError):
        log.warning("releases.json is unreadable; ignoring it")
        return []
    if isinstance(data, dict):
        data = data.get("releases", [])
    return [r for r in data if isinstance(r, dict) and r.get("file")]


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def list_releases() -> list[dict[str, Any]]:
    """Indexed releases whose APK is actually still on disk, newest first."""
    ensure_dir()
    out: list[dict[str, Any]] = []
    for entry in _load_index():
        path = RELEASES_DIR / str(entry["file"])
        if not path.is_file():
            continue
        out.append(
            {
                "version_name": str(entry.get("version_name", "?")),
                "version_code": int(entry.get("version_code", 0) or 0),
                "notes": str(entry.get("notes", "")),
                "published_at": int(entry.get("published_at", 0) or 0),
                "file": path.name,
                "size_bytes": path.stat().st_size,
                "sha256": entry.get("sha256") or sha256_of(path),
            }
        )
    out.sort(key=lambda r: r["version_code"], reverse=True)
    return out


def latest_release() -> dict[str, Any] | None:
    releases = list_releases()
    return releases[0] if releases else None


def release_path(filename: str) -> Path | None:
    """Resolve a download request to a file, refusing anything outside the dir."""
    if "/" in filename or "\\" in filename or filename.startswith("."):
        return None
    path = (RELEASES_DIR / filename).resolve()
    if RELEASES_DIR.resolve() not in path.parents or not path.is_file():
        return None
    if path.suffix.lower() != ".apk":
        return None
    return path
