"""Update yt-dlp from inside the app ("Update tools" button)."""

from __future__ import annotations

import contextlib
import shutil
import subprocess
import sys
import threading
from typing import Any

from . import engine
from .config import TOOLS_DIR, logger
from .errors import FriendlyError

_update_lock = threading.Lock()


def _update_message(before: str | None, after: str | None, backup: bool = False) -> str:
    if before == after:
        return f"Already up to date (yt-dlp {after})"
    return f"Updated yt-dlp to version {after}." + (" Old version kept as backup." if backup else "")


def _timeout_error() -> FriendlyError:
    return FriendlyError("The update took too long. Check your internet and try again.", 504, "update_timeout")


def _update_desktop(before: str | None) -> dict[str, Any]:
    """Desktop app: install the new yt-dlp into the app data folder, keep a backup,
    and go back to the old version if anything fails. No admin rights needed."""
    assert TOOLS_DIR is not None
    new, current, backup = TOOLS_DIR / "new", TOOLS_DIR / "current", TOOLS_DIR / "backup"
    TOOLS_DIR.mkdir(parents=True, exist_ok=True)
    shutil.rmtree(new, ignore_errors=True)
    try:
        proc = subprocess.run(
            [sys.executable, "-m", "pip", "install", "-U", "--disable-pip-version-check",
             "--target", str(new), "yt-dlp"],
            capture_output=True, text=True, timeout=300,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    except subprocess.TimeoutExpired:
        shutil.rmtree(new, ignore_errors=True)
        raise _timeout_error() from None
    if proc.returncode != 0:
        logger.warning("Tool update failed: %s", proc.stderr[-500:])
        shutil.rmtree(new, ignore_errors=True)
        raise FriendlyError("Couldn't update. No internet? Your old version still works.", 500, "update_failed")
    # Swap: current -> backup, new -> current.
    shutil.rmtree(backup, ignore_errors=True)
    if current.exists():
        current.rename(backup)
    new.rename(current)
    if str(current) not in sys.path:
        sys.path.insert(0, str(current))
    try:
        engine.reload_tools()
        after = engine.tool_version()
        if not after:
            raise RuntimeError("yt-dlp did not load")
    except Exception:
        logger.warning("New yt-dlp failed to load; going back to the old version", exc_info=True)
        shutil.rmtree(current, ignore_errors=True)
        if backup.exists():
            backup.rename(current)
        else:
            with contextlib.suppress(ValueError):
                sys.path.remove(str(current))
        with contextlib.suppress(Exception):
            engine.reload_tools()
        raise FriendlyError("The new version did not work, so the old one is back.", 500, "update_rolled_back") from None
    return {"message": _update_message(before, after, True), "yt_dlp": after}


def _update_with_pip(before: str | None) -> dict[str, Any]:
    try:
        proc = subprocess.run(
            [sys.executable, "-m", "pip", "install", "-U", "--disable-pip-version-check", "yt-dlp"],
            capture_output=True, text=True, timeout=300,
        )
    except subprocess.TimeoutExpired:
        raise _timeout_error() from None
    if proc.returncode != 0:
        logger.warning("Tool update failed: %s", proc.stderr[-500:])
        raise FriendlyError(
            "Couldn't update here (no internet or no permission). Try running: pip install -U yt-dlp",
            500,
            "update_failed",
        )
    engine.reload_tools()
    return {"message": _update_message(before, engine.tool_version()), "yt_dlp": engine.tool_version()}


def update_tools_sync() -> dict[str, Any]:
    if not _update_lock.acquire(blocking=False):
        raise FriendlyError("An update is already running. Please wait.", 409, "update_busy")
    try:
        before = engine.tool_version()
        return _update_desktop(before) if TOOLS_DIR else _update_with_pip(before)
    finally:
        _update_lock.release()
