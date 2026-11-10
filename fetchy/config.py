"""Settings read from the environment, paths and fixed constants."""

from __future__ import annotations

import logging
import os
import re
from pathlib import Path

APP_NAME = "Fetchy"
APP_VERSION = "1.2.1"   # keep equal to desktop/package.json and the git tag
BASE_DIR = Path(__file__).resolve().parent.parent
TEMPLATES_DIR = BASE_DIR / "templates"
STATIC_DIR = BASE_DIR / "static"

# Desktop app (Electron wrapper): per-user data folder, updated tools, save folder.
DATA_DIR = Path(os.getenv("FETCHLY_DATA_DIR") or BASE_DIR)
DOWNLOADS_DIR = DATA_DIR / "downloads"
TOOLS_DIR = Path(os.environ["FETCHLY_TOOLS_DIR"]) if os.getenv("FETCHLY_TOOLS_DIR") else None
SAVE_DIR = Path(os.environ["FETCHLY_SAVE_DIR"]) if os.getenv("FETCHLY_SAVE_DIR") else None
ARCHIVE_FILE = DATA_DIR / "downloaded.txt"   # one "site id" line per finished playlist video

# Optional Netscape-format cookie jar used to authenticate yt-dlp requests.
# It is applied only when present; a missing file is a warning, never a crash.
COOKIES_FILE = DATA_DIR / "cookies.txt"

# Optional: read cookies straight from a browser on this computer (local use).
# Example: FETCHLY_COOKIES_BROWSER=chrome  (also: firefox, edge, brave, opera...)
COOKIES_BROWSER = os.getenv("FETCHLY_COOKIES_BROWSER", "").strip().lower()

# Optional HTTP Basic Auth for public deployments. Leave unset for local use.
APP_USERNAME = os.getenv("FETCHLY_USERNAME", "fetchly")
APP_PASSWORD = os.getenv("FETCHLY_PASSWORD", "").strip()


def _env_int(name: str, default: int, minimum: int = 1) -> int:
    """Read an integer environment variable without crashing on bad input."""
    try:
        return max(minimum, int(os.getenv(name, str(default))))
    except (TypeError, ValueError):
        return default


DOWNLOAD_TTL_SECONDS = _env_int("DOWNLOAD_TTL_SECONDS", 60 * 60, minimum=60)
CLEANUP_INTERVAL_SECONDS = _env_int("CLEANUP_INTERVAL_SECONDS", 10 * 60, minimum=30)
MAX_PREVIEW_BYTES = _env_int("MAX_PREVIEW_BYTES", 20 * 1024 * 1024, minimum=1024)
VIDEO_INFO_TIMEOUT_SECONDS = _env_int("VIDEO_INFO_TIMEOUT_SECONDS", 90, minimum=10)
MAX_REDIRECTS = 5
HOST = os.getenv("HOST", "127.0.0.1")
PORT = _env_int("PORT", 8001, minimum=1)

# A normal browser UA avoids many naive bot blocks.
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/131.0.0.0 Safari/537.36"
)
IMAGE_ACCEPT = "image/avif,image/webp,image/apng,image/png,image/jpeg,image/gif,image/*;q=0.8,*/*;q=0.5"
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".bmp"}

# yt-dlp format selectors. The "?" makes a resolution filter a preference
# instead of a hard failure when a site lacks that exact height.
QUALITY_FORMAT_SELECTORS = {
    "best": "bestvideo*+bestaudio/best",
    "audio": "bestaudio/best",
    "audio-m4a": "bestaudio[ext=m4a]/bestaudio/best",
}
AUDIO_KEYS = {"audio": "mp3", "audio-m4a": "m4a"}   # quality key -> file type
SUB_CODE_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$")

# Playlists (desktop app only).
PLAYLIST_PARALLEL = 3
MIX_CAP = 50   # auto-generated Mix playlists never end

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s  %(levelname)-8s %(name)s: %(message)s",
)
logger = logging.getLogger("fetchly")
