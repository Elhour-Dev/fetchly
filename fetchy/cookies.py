"""Login cookies for yt-dlp: a cookies.txt file or a browser on this computer.

Cookie values are never logged.
"""

from __future__ import annotations

import base64
import contextlib
import os
import threading

from . import config
from .config import logger
from .errors import FriendlyError

BROWSERS = {"firefox", "chrome", "edge", "brave", "opera", "chromium", "vivaldi", "safari", "whale"}

_browser = config.COOKIES_BROWSER
_warning_lock = threading.Lock()
_warning_emitted = False


def browser() -> str:
    return _browser


def set_browser(name: str) -> None:
    global _browser
    name = name.strip().lower()
    if name not in BROWSERS:
        raise FriendlyError("Pick a browser from the list.", 400, "bad_browser")
    _browser = name


def has_file() -> bool:
    try:
        return config.COOKIES_FILE.is_file()
    except OSError:
        return False


def status() -> dict[str, object]:
    return {"browser": _browser, "file": has_file()}


def save_file(text: str) -> None:
    """Save a Netscape cookies.txt after a basic format check."""
    text = text.replace("\r\n", "\n")
    rows = [ln for ln in text.split("\n") if ln.strip() and (not ln.startswith("#") or ln.startswith("#HttpOnly_"))]
    if not rows or not all(len(ln.split("\t")) == 7 for ln in rows):
        raise FriendlyError(
            "That does not look like a cookies.txt file (Netscape format). Export it again from your browser.",
            400,
            "bad_cookies_file",
        )
    config.COOKIES_FILE.write_text(text.rstrip("\n") + "\n", encoding="utf-8")
    with contextlib.suppress(OSError):
        os.chmod(config.COOKIES_FILE, 0o600)


def clear() -> None:
    global _browser
    _browser = ""
    config.COOKIES_FILE.unlink(missing_ok=True)


def materialize_from_env() -> None:
    """Write the cookie jar from a deployment secret when provided."""
    encoded = os.getenv("YOUTUBE_COOKIES_B64", "").strip()
    if not encoded:
        return
    try:
        text = base64.b64decode(encoded, validate=True).decode("utf-8")
        config.COOKIES_FILE.write_text(text, encoding="utf-8")
        with contextlib.suppress(OSError):
            os.chmod(config.COOKIES_FILE, 0o600)
        logger.info("Loaded YouTube cookies from YOUTUBE_COOKIES_B64")
    except Exception:
        logger.warning("Could not decode YOUTUBE_COOKIES_B64; continuing without it")


def ydl_options() -> dict[str, object]:
    """yt-dlp cookie options, or an empty dict when there are no cookies."""
    global _warning_emitted
    # A browser you picked wins over cookies.txt.
    if _browser:
        return {"cookiesfrombrowser": (_browser,)}
    if has_file():
        return {"cookiefile": str(config.COOKIES_FILE)}
    with _warning_lock:
        if not _warning_emitted:
            logger.warning(
                "cookies.txt not found at %s. Continuing without cookies; "
                "YouTube may block requests from this server IP.",
                config.COOKIES_FILE,
            )
            _warning_emitted = True
    return {}


def source_name() -> str | None:
    """Where cookies come from, for the startup log."""
    if _browser:
        return f"the {_browser} browser"
    if has_file():
        return str(config.COOKIES_FILE)
    return None
