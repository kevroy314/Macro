"""Runtime configuration, all overridable from data/env."""

from __future__ import annotations

import os
import secrets
from pathlib import Path

DATA_DIR = Path(os.environ.get("MACROPAD_DATA_DIR", "/data"))
JOBS_DIR = DATA_DIR / "jobs"
DB_PATH = DATA_DIR / "macropad.db"
API_KEY_PATH = DATA_DIR / "apikey"

MODEL = os.environ.get("MACROPAD_MODEL", "claude-opus-5")
# Background preset tagging is a labelling task, not a reasoning one, and nobody is
# waiting on it — a small fast model is the right trade.
SEARCH_MODEL = os.environ.get("MACROPAD_SEARCH_MODEL", "claude-haiku-4-5")
MAX_CONCURRENT = int(os.environ.get("MACROPAD_MAX_CONCURRENT", "2"))
MAX_TURNS = int(os.environ.get("MACROPAD_MAX_TURNS", "40"))
IMAGE_RETENTION_DAYS = int(os.environ.get("MACROPAD_IMAGE_RETENTION_DAYS", "90"))

# Long edge an uploaded photo is downscaled to before the agent reads it. Beyond
# this the model gains nothing and every extra pixel is billed.
MAX_IMAGE_EDGE = int(os.environ.get("MACROPAD_MAX_IMAGE_EDGE", "1600"))
MAX_IMAGES_PER_JOB = int(os.environ.get("MACROPAD_MAX_IMAGES", "6"))

# The only tools the agent gets. Everything else is refused three ways: it is not
# in the base tool set, it is named in disallowed_tools, and the can_use_tool
# callback in runner.py denies anything unrecognised.
AGENT_TOOLS = ["Read", "WebSearch", "WebFetch"]

# Read and WebFetch are deliberately *not* pre-approved: routing them through
# can_use_tool is what lets runner.py check the path and the host first. WebSearch
# has no such argument to check, so it is auto-approved.
AGENT_AUTO_APPROVED_TOOLS = ["WebSearch"]

AGENT_DISALLOWED_TOOLS = [
    "Bash",
    "BashOutput",
    "KillShell",
    "Write",
    "Edit",
    "NotebookEdit",
    "Task",
    "TodoWrite",
    "Glob",
    "Grep",
]


def ensure_dirs() -> None:
    JOBS_DIR.mkdir(parents=True, exist_ok=True)


def load_or_create_api_key() -> str:
    """Read the API key, generating one on first boot."""
    if API_KEY_PATH.exists():
        key = API_KEY_PATH.read_text().strip()
        if key:
            return key
    key = "mp_" + secrets.token_urlsafe(32)
    API_KEY_PATH.parent.mkdir(parents=True, exist_ok=True)
    API_KEY_PATH.write_text(key + "\n")
    try:
        API_KEY_PATH.chmod(0o600)
    except OSError:
        pass
    return key


def rotate_api_key() -> str:
    if API_KEY_PATH.exists():
        API_KEY_PATH.unlink()
    return load_or_create_api_key()
