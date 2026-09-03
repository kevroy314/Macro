"""Who a request belongs to.

The daemon started single-user with one shared key. Now that a second phone points
at it, every job and thread has an owner, and a key identifies which. Users live in
`data/users.json`; the original `data/apikey` becomes the first user so nothing that
already works stops working.
"""

from __future__ import annotations

import hmac
import json
import logging
import secrets
import threading
from dataclasses import dataclass
from typing import Any

from . import config

log = logging.getLogger("macropad.users")

USERS_PATH = config.DATA_DIR / "users.json"
_lock = threading.RLock()


@dataclass(frozen=True)
class User:
    id: str
    name: str
    key: str
    email: str = ""


def _read() -> list[dict[str, Any]]:
    if not USERS_PATH.exists():
        return []
    try:
        data = json.loads(USERS_PATH.read_text())
    except (OSError, json.JSONDecodeError):
        log.error("users.json is unreadable; refusing to guess")
        return []
    if isinstance(data, dict):
        data = data.get("users", [])
    return [u for u in data if isinstance(u, dict) and u.get("id") and u.get("key")]


def _write(users: list[dict[str, Any]]) -> None:
    USERS_PATH.parent.mkdir(parents=True, exist_ok=True)
    USERS_PATH.write_text(json.dumps({"users": users}, indent=2) + "\n")
    try:
        USERS_PATH.chmod(0o600)
    except OSError:
        pass


def bootstrap() -> None:
    """Ensure a first user exists, adopting the pre-multi-user key."""
    with _lock:
        if _read():
            return
        _write(
            [
                {
                    "id": "kevin",
                    "name": "Kevin",
                    "email": "kevin.horecka@gmail.com",
                    "key": config.load_or_create_api_key(),
                }
            ]
        )
        log.info("created users.json with the existing API key as user 'kevin'")


def all_users() -> list[User]:
    with _lock:
        return [
            User(
                id=str(u["id"]),
                name=str(u.get("name") or u["id"]),
                key=str(u["key"]),
                email=str(u.get("email") or ""),
            )
            for u in _read()
        ]


def primary_user() -> User | None:
    users = all_users()
    return users[0] if users else None


def by_key(presented: str) -> User | None:
    """Constant-time key lookup."""
    if not presented:
        return None
    match = None
    for user in all_users():
        # Compare every key so a wrong key costs the same as a right one.
        if hmac.compare_digest(presented, user.key):
            match = user
    return match


def by_email(email: str) -> User | None:
    if not email:
        return None
    wanted = email.strip().lower()
    for user in all_users():
        if user.email.strip().lower() == wanted:
            return user
    return None


def by_id(user_id: str) -> User | None:
    for user in all_users():
        if user.id == user_id:
            return user
    return None


def add_user(user_id: str, name: str, email: str = "") -> User:
    with _lock:
        users = _read()
        if any(u["id"] == user_id for u in users):
            raise ValueError(f"user '{user_id}' already exists")
        user = {
            "id": user_id,
            "name": name or user_id,
            "email": email,
            "key": "mp_" + secrets.token_urlsafe(32),
        }
        users.append(user)
        _write(users)
        return User(id=user["id"], name=user["name"], key=user["key"], email=user["email"])


def rotate_key(user_id: str) -> User:
    with _lock:
        users = _read()
        for user in users:
            if user["id"] == user_id:
                user["key"] = "mp_" + secrets.token_urlsafe(32)
                _write(users)
                return User(
                    id=user["id"],
                    name=user.get("name") or user["id"],
                    key=user["key"],
                    email=user.get("email") or "",
                )
        raise ValueError(f"no such user: {user_id}")


def remove_user(user_id: str) -> None:
    with _lock:
        users = _read()
        remaining = [u for u in users if u["id"] != user_id]
        if len(remaining) == len(users):
            raise ValueError(f"no such user: {user_id}")
        if not remaining:
            raise ValueError("refusing to remove the last user")
        _write(remaining)
