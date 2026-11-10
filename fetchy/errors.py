"""Errors that are safe to show, and friendly text for yt-dlp failures."""

from __future__ import annotations

import re
from typing import Any


class FriendlyError(Exception):
    """An expected error whose message is safe to show to the user."""

    def __init__(
        self, message: str, status_code: int = 400, code: str = "request_failed", detail: str = "",
        extra: dict[str, Any] | None = None,
    ):
        super().__init__(message)
        self.message = message
        self.detail = detail
        self.status_code = status_code
        self.code = code
        self.extra = extra or {}   # more fields for the JSON error body, e.g. a job_id the client can still use


class MediaFetchError(FriendlyError):
    def __init__(self, message: str, status_code: int = 502):
        super().__init__(message, status_code=status_code, code="media_fetch_failed")


LOGIN_HELP = (
    "This site needs a login. Log in to it in Firefox, then open Help > Cookies and press "
    "\"Use browser cookies\". Then try again."
)
LOGIN_HELP_BOT = (
    "YouTube wants to confirm you're not a bot. Log in to YouTube in Firefox, then open "
    "Help > Cookies and press \"Use browser cookies\". Then try again."
)

_ANSI_RE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")

_KNOWN_ERRORS = (
    (("ffmpeg", "not found"), "FFmpeg isn't installed or isn't on PATH. Install FFmpeg and restart Fetchy to merge high-quality video and audio."),
    (("ffprobe", "not found"), "FFmpeg is missing. Install FFmpeg and restart Fetchy."),
    (("private video",), "This video is private, so it can't be downloaded."),
    (("members-only",), "This video is members-only and needs an account."),
    (("age-restricted",), "This video is age-restricted and can't be fetched without a signed-in session."),
    (("sign in to confirm",), LOGIN_HELP_BOT),
    (("not a bot",), LOGIN_HELP_BOT),
    (("authentication is required",), LOGIN_HELP),
    (("login required",), LOGIN_HELP),
    (("log in",), LOGIN_HELP),
    (("empty media response",), LOGIN_HELP),
    (("--cookies",), LOGIN_HELP),
    (("video unavailable",), "This video isn't available. It may have been removed or blocked in your region."),
    (("this video is not available",), "This video isn't available in your region."),
    (("unsupported url",), "This site isn't supported. Paste a direct video or a supported page URL."),
    (("requested format is not available",), "That quality isn't available for this video. Fetch the link again and choose another quality."),
    (("live event",), "Live streams aren't supported. Wait until the stream has ended."),
    (("is live",), "Live streams aren't supported. Wait until the stream has ended."),
    (("http error 403",), "The site refused the download (HTTP 403). It may require cookies or a different link."),
    (("http error 404",), "The site returned \"not found\" for this link (HTTP 404)."),
    (("http error 429",), "The site is rate-limiting requests. Wait a minute and try again."),
    (("timed out",), "The site took too long to respond. Check the URL and try again."),
    (("playlist",), "Playlists aren't supported here. Paste a link to a single video."),
)


def strip_ansi(value: str) -> str:
    return _ANSI_RE.sub("", value)


def friendly_ytdlp_error(exc: Exception) -> str:
    """Translate the most common yt-dlp failures into actionable copy."""
    raw = strip_ansi(str(exc)).replace("\r", " ").strip()
    raw = re.sub(r"^ERROR:\s*", "", raw, flags=re.I)
    low = raw.lower()

    for needles, message in _KNOWN_ERRORS:
        if all(needle in low for needle in needles):
            return message

    if "geo" in low and "block" in low:
        return "This video is geo-blocked in your region."

    # Keep the useful part of unknown errors, but never expose a traceback.
    if raw:
        return f"Couldn't download this link. {raw[:230]}"
    return "Couldn't download this link. Please try a different URL."
