"""API-key authentication for /api/v1/*."""

from __future__ import annotations

from fastapi import Header, HTTPException, status

from . import config, users

_API_KEY: str | None = None


def api_key() -> str:
    """The primary user's key. Used for the release download link and the web UI."""
    primary = users.primary_user()
    if primary is not None:
        return primary.key
    global _API_KEY
    if _API_KEY is None:
        _API_KEY = config.load_or_create_api_key()
    return _API_KEY


def reload_api_key() -> str:
    global _API_KEY
    _API_KEY = config.load_or_create_api_key()
    return _API_KEY


def _presented(authorization: str | None, x_macropad_key: str | None) -> str | None:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization[7:].strip()
    if x_macropad_key:
        return x_macropad_key.strip()
    return None


async def current_user(
    authorization: str | None = Header(default=None),
    x_macropad_key: str | None = Header(default=None),
) -> users.User:
    """Resolve the caller from their key.

    Every job and thread is owned by whoever created it, so this is what keeps two
    phones pointed at the same daemon from seeing each other's food.
    """
    presented = _presented(authorization, x_macropad_key)
    user = users.by_key(presented) if presented else None
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing API key",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return user


async def require_api_key(
    authorization: str | None = Header(default=None),
    x_macropad_key: str | None = Header(default=None),
) -> None:
    """Any valid user. For endpoints that own no data, like preset tagging."""
    await current_user(authorization, x_macropad_key)
