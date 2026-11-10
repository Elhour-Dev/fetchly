"""Shared yt-dlp options."""

from __future__ import annotations

from typing import Any

from . import cookies
from .config import USER_AGENT

# The UA sent by yt-dlp for single-video info and downloads.
YTDLP_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

# Keep mobile clients, but include web because yt-dlp skips android/ios
# whenever a cookie file is loaded.
def youtube_args() -> dict[str, Any]:
    return {"youtube": {"player_client": ["android", "ios", "web_embedded", "tv_samsung", "web"]}}


def base_options() -> dict[str, Any]:
    options: dict[str, Any] = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "cachedir": False,
        "socket_timeout": 30,
        "retries": 3,
        "fragment_retries": 3,
        "extractor_retries": 2,
        "nocheckcertificate": False,
        "http_headers": {"User-Agent": USER_AGENT},
        # YouTube often needs a JavaScript challenge solver before it shows
        # media formats. yt-dlp fetches the EJS solver from its GitHub repo.
        "remote_components": {"ejs:github"},
    }
    options.update(cookies.ydl_options())
    return options


def single_video_options() -> dict[str, Any]:
    """Base options plus the YouTube client list and UA used for one video."""
    options = base_options()
    options.update({
        "extractor_args": youtube_args(),
        "http_headers": {"User-Agent": YTDLP_USER_AGENT},
    })
    return options
