"""Login cookies for yt-dlp: a cookies.txt file or a browser on this computer.

Cookie values are never logged.
"""

from __future__ import annotations

import base64
import contextlib
import os
import threading
import time

from . import config
from .config import logger
from .errors import FriendlyError

BROWSERS = {"firefox", "chrome", "edge", "brave", "opera", "chromium", "vivaldi", "safari", "whale"}

_browser = config.COOKIES_BROWSER
_warning_lock = threading.Lock()
_warning_emitted = False
_unreadable_warned: set[str] = set()
_check_lock = threading.Lock()
_check_cache: dict[str, tuple[float, bool]] = {}


def browser() -> str:
    return _browser


def _can_read(name: str) -> bool:
    """True when yt-dlp can read this browser's cookies right now (Chrome locks them while it is open)."""
    try:
        from yt_dlp.cookies import extract_cookies_from_browser

        extract_cookies_from_browser(name)
        return True
    except Exception:
        return False


def _still_readable(name: str) -> bool:
    """Same check, remembered for a minute so downloads do not repeat it."""
    now = time.monotonic()
    with _check_lock:
        hit = _check_cache.get(name)
        if hit and now - hit[0] < 60:
            return hit[1]
    ok = _can_read(name)
    with _check_lock:
        _check_cache[name] = (now, ok)
    return ok


def set_browser(name: str) -> None:
    global _browser
    name = name.strip().lower()
    if name not in BROWSERS:
        raise FriendlyError("Pick a browser from the list.", 400, "bad_browser")
    if not _can_read(name):
        raise FriendlyError(
            f"Fetchy cannot read {name.capitalize()}'s cookies. {name.capitalize()} locks them while it is open, and newer "
            "versions block other programs even when it is closed. Use Firefox, or upload a cookies.txt file.",
            400,
            "browser_cookies_unreadable",
        )
    with _check_lock:
        _check_cache[name] = (time.monotonic(), True)
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
    # A browser you picked wins over cookies.txt, unless it can no longer be read (then a broken browser
    # must not break every download: use cookies.txt, or none).
    if _browser:
        if _still_readable(_browser):
            return {"cookiesfrombrowser": (_browser,)}
        with _warning_lock:
            if _browser not in _unreadable_warned:
                _unreadable_warned.add(_browser)
                logger.warning("Cannot read %s cookies right now. Continuing without them.", _browser)
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
