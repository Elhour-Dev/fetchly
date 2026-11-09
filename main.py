"""Fetchly - a small, self-hosted video and image downloader.

The backend intentionally keeps all state in memory. It is designed for a
single-user local deployment: jobs are UUID-addressed, generated files expire
after an hour, and every URL is checked against private/link-local address
ranges before the server makes a request.
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
import hashlib
import ipaddress
import json
import logging
import mimetypes
import os
import re
import secrets
import importlib
import shutil
import socket
import subprocess
import sys
import threading
import time
import unicodedata
import uuid
import zipfile
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from functools import lru_cache
from io import BytesIO
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urljoin, urlsplit, urlunsplit

import httpx
# Desktop app: use the newest downloaded tools (yt-dlp, gallery-dl) if present.
TOOLS_DIR = Path(os.environ["FETCHLY_TOOLS_DIR"]) if os.getenv("FETCHLY_TOOLS_DIR") else None
if TOOLS_DIR and (TOOLS_DIR / "current").is_dir():
    sys.path.insert(0, str(TOOLS_DIR / "current"))
import yt_dlp
from bs4 import BeautifulSoup
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, Field

try:
    from gallery_dl import config as gallery_config
    from gallery_dl import exception as gallery_exception
    from gallery_dl import extractor as gallery_extractor
    from gallery_dl import job as gallery_job
    from gallery_dl.extractor.message import Message as GalleryMessage

    GALLERY_DL_AVAILABLE = True
    GALLERY_DL_IMPORT_ERROR: str | None = None
except Exception as exc:  # pragma: no cover - depends on optional install state
    gallery_config = gallery_exception = gallery_extractor = gallery_job = None
    GalleryMessage = None
    GALLERY_DL_AVAILABLE = False
    GALLERY_DL_IMPORT_ERROR = str(exc)


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

APP_NAME = "Fetchly"
APP_VERSION = "1.0.0"
BASE_DIR = Path(__file__).resolve().parent
# Desktop app (Electron wrapper): per-user data folder and updated tools.
DATA_DIR = Path(os.getenv("FETCHLY_DATA_DIR") or BASE_DIR)
DOWNLOADS_DIR = DATA_DIR / "downloads"
TEMPLATES_DIR = BASE_DIR / "templates"
STATIC_DIR = BASE_DIR / "static"

# Optional Netscape-format cookie jar used to authenticate yt-dlp requests.
# It is applied only when present; a missing file is a warning, never a crash.
COOKIES_FILE = DATA_DIR / "cookies.txt"

# Optional: read cookies straight from a browser on this computer (local use).
# Example: FETCHLY_COOKIES_BROWSER=chrome  (also: firefox, edge, brave, opera...)
# Nothing is copied or saved by Fetchly; the tools read the browser when needed.
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
MAX_IMAGE_COUNT = _env_int("MAX_IMAGE_COUNT", 100, minimum=1)
MAX_IMAGE_DOWNLOAD_BYTES = _env_int("MAX_IMAGE_DOWNLOAD_BYTES", 25 * 1024 * 1024, minimum=1024)
MAX_PAGE_BYTES = _env_int("MAX_PAGE_BYTES", 8 * 1024 * 1024, minimum=1024)
MAX_PREVIEW_BYTES = _env_int("MAX_PREVIEW_BYTES", 20 * 1024 * 1024, minimum=1024)
MAX_ZIP_TOTAL_BYTES = _env_int("MAX_ZIP_TOTAL_BYTES", 300 * 1024 * 1024, minimum=1024)
VIDEO_INFO_TIMEOUT_SECONDS = _env_int("VIDEO_INFO_TIMEOUT_SECONDS", 90, minimum=10)
IMAGE_EXTRACT_TIMEOUT_SECONDS = _env_int("IMAGE_EXTRACT_TIMEOUT_SECONDS", 60, minimum=10)
GALLERY_EXTRACT_TIMEOUT_SECONDS = _env_int("GALLERY_EXTRACT_TIMEOUT_SECONDS", 50, minimum=10)
MAX_REDIRECTS = 5
HOST = os.getenv("HOST", "127.0.0.1")
PORT = _env_int("PORT", 8001, minimum=1)

# A normal browser UA avoids many naive bot blocks while still being honest in
# the README that this is a local downloader and not a crawler.
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/131.0.0.0 Safari/537.36"
)
IMAGE_ACCEPT = "image/avif,image/webp,image/apng,image/png,image/jpeg,image/gif,image/*;q=0.8,*/*;q=0.5"

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".bmp"}
VIDEO_EXTENSIONS = {".mp4", ".m4v", ".mkv", ".webm", ".mov", ".avi", ".flv", ".ts", ".m3u8"}
IMAGE_FORMAT_MAP: dict[str, tuple[str, str]] = {
    "JPEG": ("image/jpeg", ".jpg"),
    "MPO": ("image/jpeg", ".jpg"),
    "PNG": ("image/png", ".png"),
    "WEBP": ("image/webp", ".webp"),
    "GIF": ("image/gif", ".gif"),
    "AVIF": ("image/avif", ".avif"),
    "BMP": ("image/bmp", ".bmp"),
}

# yt-dlp format selectors. The "?" makes a resolution filter a preference
# instead of a hard failure when a site lacks that exact height.
QUALITY_FORMAT_SELECTORS = {
    "best": "bestvideo*+bestaudio/best",
    "audio": "bestaudio/best",
}


# ---------------------------------------------------------------------------
# Logging and errors
# ---------------------------------------------------------------------------

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s  %(levelname)-8s %(name)s: %(message)s",
)
logger = logging.getLogger("fetchly")
if GALLERY_DL_AVAILABLE:
    # gallery-dl is a library here; its CLI-oriented logger is noisy.
    logging.getLogger("gallery-dl").setLevel(logging.ERROR)


class FriendlyError(Exception):
    """An expected error whose message is safe to show to the user."""

    def __init__(self, message: str, status_code: int = 400, code: str = "request_failed", detail: str = ""):
        super().__init__(message)
        self.message = message
        self.detail = detail
        self.status_code = status_code
        self.code = code


class MediaFetchError(FriendlyError):
    def __init__(self, message: str, status_code: int = 502):
        super().__init__(message, status_code=status_code, code="media_fetch_failed")


class DirectImageFound(MediaFetchError):
    """Raised when the supplied URL is itself an image rather than a page."""

    def __init__(self, url: str):
        super().__init__("That URL is a direct image.", 200)
        self.url = url


# ---------------------------------------------------------------------------
# Small utilities
# ---------------------------------------------------------------------------

def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime | None) -> str | None:
    return dt.isoformat() if dt else None


def _coerce_int(value: Any) -> int | None:
    try:
        if value is None or value == "":
            return None
        return int(value)
    except (TypeError, ValueError, OverflowError):
        return None


def _coerce_float(value: Any) -> float | None:
    try:
        if value is None or value == "":
            return None
        result = float(value)
        return result if result == result else None  # discard NaN
    except (TypeError, ValueError, OverflowError):
        return None


_WINDOWS_RESERVED_NAMES = {
    "CON", "PRN", "AUX", "NUL",
    *(f"COM{i}" for i in range(1, 10)),
    *(f"LPT{i}" for i in range(1, 10)),
}
_INVALID_FILename_RE = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
_SPACE_RE = re.compile(r"\s+")


def sanitize_filename(
    value: str | None,
    *,
    fallback: str = "download",
    max_length: int = 100,
    extension: str | None = None,
) -> str:
    """Make a user-facing filename safe on every OS.

    Path separators are replaced, reserved names are prefixed, leading dots and
    spaces are removed, and the final name is length-bounded. The extension is
    always chosen by the server, never trusted from a remote URL.
    """
    clean = unicodedata.normalize("NFKC", value or "")
    clean = _INVALID_FILename_RE.sub(" ", clean)
    clean = _SPACE_RE.sub(" ", clean).strip(" .")
    if extension:
        ext = extension if extension.startswith(".") else f".{extension}"
        if clean.lower().endswith(ext.lower()):
            clean = clean[: -len(ext)]
        clean = clean.strip(" .")
    if not clean:
        clean = fallback
    if clean.upper() in _WINDOWS_RESERVED_NAMES:
        clean = f"{fallback}-{clean}"
    ext = extension or ""
    max_stem = max(1, max_length - len(ext))
    clean = clean[:max_stem].rstrip(" .")
    if not clean:
        clean = fallback
    return f"{clean}{ext}"


def filename_from_url(url: str, *, fallback: str = "image") -> str:
    try:
        raw = unquote(Path(urlsplit(url).path).name)
    except Exception:
        raw = ""
    return sanitize_filename(raw, fallback=fallback, max_length=90)


def page_title_or_slug(title: str | None, url: str) -> str:
    """Prefer a human page title, falling back to a readable URL slug."""
    value = (title or "").strip()
    lowered = value.lower()
    bad_title = (
        not value
        or lowered.startswith("file:")
        or lowered.endswith(tuple(IMAGE_EXTENSIONS | VIDEO_EXTENSIONS))
    )
    if not bad_title:
        return value[:200]
    try:
        slug = unquote(Path(urlsplit(url).path).stem).replace("_", " ").strip()
    except Exception:
        slug = ""
    if slug:
        return slug[:200]
    return (urlsplit(url).hostname or "Images")[:200]


LOGIN_HELP = (
    "This site needs a login. Log in to it in Firefox, then open Help > Cookies and press "
    "\"Use Firefox cookies\". Then try again."
)
INSTAGRAM_LOGIN_HELP = (
    "Instagram needs a login. Log in to Instagram in Firefox, then open Help > Cookies and "
    "press \"Use Firefox cookies\". Then try again."
)
LOGIN_HELP_BOT = (
    "YouTube wants to confirm you're not a bot. Sign in to YouTube in your browser, "
    "then start Fetchly with FETCHLY_COOKIES_BROWSER=chrome (or firefox, edge) and try again."
)


def _strip_ansi(value: str) -> str:
    return re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", value)


def friendly_ytdlp_error(exc: Exception) -> str:
    """Translate the most common yt-dlp failures into actionable copy."""
    raw = _strip_ansi(str(exc)).replace("\r", " ").strip()
    raw = re.sub(r"^ERROR:\s*", "", raw, flags=re.I)
    low = raw.lower()

    known = (
        (("ffmpeg", "not found"), "FFmpeg isn't installed or isn't on PATH. Install FFmpeg and restart Fetchly to merge high-quality video and audio."),
        (("ffprobe", "not found"), "FFmpeg is missing. Install FFmpeg and restart Fetchly."),
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
        (("http error 404",), "The site returned ”not found“ for this link (HTTP 404)."),
        (("http error 429",), "The site is rate-limiting requests. Wait a minute and try again."),
        (("timed out",), "The site took too long to respond. Check the URL and try again."),
        (("playlist",), "Playlists aren't supported here. Paste a link to a single video."),
    )
    for needles, message in known:
        if all(needle in low for needle in needles):
            return message

    if "geo" in low and "block" in low:
        return "This video is geo-blocked in your region."

    # Keep the useful part of unknown errors, but never expose a traceback.
    if raw:
        return f"Couldn't download this link. {raw[:230]}"
    return "Couldn't download this link. Please try a different URL."


# ---------------------------------------------------------------------------
# URL safety (basic SSRF guard)
# ---------------------------------------------------------------------------

_BLOCKED_HOST_SUFFIXES = (
    ".localhost", ".local", ".internal", ".lan", ".home", ".test", ".invalid",
)
_BLOCKED_HOSTS = {"localhost", "localhost.localdomain", "ip6-localhost"}


def normalize_url(raw: str) -> str:
    """Validate and canonicalize an HTTP(S) URL.

    This deliberately rejects non-web schemes, credentials, odd ports and
    whitespace. Host/IP reachability is checked separately in a thread.
    """
    if not isinstance(raw, str):
        raise FriendlyError("Please paste a valid URL.", 400, "invalid_url")
    value = raw.strip()
    if not value:
        raise FriendlyError("Paste a link to get started.", 400, "invalid_url")
    if len(value) > 4096:
        raise FriendlyError("That URL is too long.", 400, "invalid_url")
    if any(ord(char) < 32 for char in value) or " " in value or "\\" in value:
        raise FriendlyError("That doesn't look like a valid URL.", 400, "invalid_url")

    try:
        parts = urlsplit(value)
        scheme = parts.scheme.lower()
    except ValueError:
        raise FriendlyError("That doesn't look like a valid URL.", 400, "invalid_url") from None

    if scheme not in {"http", "https"}:
        raise FriendlyError("Only http:// and https:// links are supported.", 400, "invalid_url")
    if not parts.netloc:
        raise FriendlyError("That URL is missing a domain name.", 400, "invalid_url")
    if parts.username or parts.password:
        raise FriendlyError("Links with embedded usernames or passwords aren't supported.", 400, "invalid_url")

    try:
        host = parts.hostname
        port = parts.port
    except ValueError:
        raise FriendlyError("That URL has an invalid port.", 400, "invalid_url") from None

    if not host:
        raise FriendlyError("That URL is missing a domain name.", 400, "invalid_url")
    host = host.lower().rstrip(".")
    if host in _BLOCKED_HOSTS or host.endswith(_BLOCKED_HOST_SUFFIXES):
        raise FriendlyError("Local and private network addresses aren't allowed.", 400, "blocked_url")
    if port not in (None, 80, 443):
        raise FriendlyError("Only standard web ports (80 and 443) are supported.", 400, "invalid_url")

    host_repr = f"[{host}]" if ":" in host else host
    netloc = f"{host_repr}:{port}" if port else host_repr
    path = parts.path or "/"
    return urlunsplit((scheme, netloc, path, parts.query, ""))


@lru_cache(maxsize=1024)
def _host_is_public(host: str) -> bool:
    """Resolve a host and require every address to be globally routable.

    A cached DNS answer is acceptable for this local utility; the actual HTTP
    client still resolves independently, which is why this is described as a
    basic SSRF guard rather than a bank-grade egress proxy.
    """
    try:
        literal = ipaddress.ip_address(host)
        addresses = [literal]
    except ValueError:
        try:
            infos = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
        except socket.gaierror:
            raise FriendlyError("We couldn't resolve that domain name.", 400, "dns_failed") from None
        addresses = []
        for info in infos:
            raw_ip = info[4][0].split("%", 1)[0]
            try:
                addresses.append(ipaddress.ip_address(raw_ip))
            except ValueError:
                continue

    if not addresses:
        raise FriendlyError("We couldn't resolve that domain name.", 400, "dns_failed")
    for address in addresses:
        if not address.is_global:
            raise FriendlyError(
                "Local, private, and reserved network addresses aren't allowed.",
                400,
                "blocked_url",
            )
    return True


async def ensure_public_url(url: str) -> str:
    normalized = normalize_url(url)
    host = urlsplit(normalized).hostname
    if not host:
        raise FriendlyError("That URL is missing a domain name.", 400, "invalid_url")
    try:
        await asyncio.to_thread(_host_is_public, host)
    except FriendlyError:
        raise
    except Exception:
        raise FriendlyError("We couldn't verify that domain.", 400, "dns_failed") from None
    return normalized


# ---------------------------------------------------------------------------
# Job storage
# ---------------------------------------------------------------------------

TERMINAL_STATES = {"complete", "error"}


@dataclass
class Job:
    id: str
    kind: str
    url: str
    state: str = "working"
    created_at: datetime = field(default_factory=utc_now)
    updated_at: datetime = field(default_factory=utc_now)
    completed_at: datetime | None = None
    expires_at: datetime | None = None
    error: str | None = None
    file_path: Path | None = None
    download_name: str | None = None
    progress: dict[str, Any] = field(default_factory=lambda: {
        "stage": "idle",
        "percent": 0.0,
        "downloaded_bytes": 0,
        "total_bytes": None,
        "speed": None,
        "eta": None,
    })
    video: dict[str, Any] | None = None
    thumbnail_url: str | None = None
    page_title: str | None = None
    images: list[dict[str, Any]] = field(default_factory=list)
    image_map: dict[str, dict[str, Any]] = field(default_factory=dict)
    zip_source_job: str | None = None

    def public_status(self) -> dict[str, Any]:
        return {
            "job_id": self.id,
            "kind": self.kind,
            "state": self.state,
            "progress": dict(self.progress),
            "error": self.error,
            "download_url": f"/api/download/{self.id}" if self.state == "complete" and self.file_path else None,
            "file_name": self.download_name,
            "expires_at": iso(self.expires_at),
            "image_count": len(self.images),
        }


class JobStore:
    def __init__(self) -> None:
        self._jobs: dict[str, Job] = {}
        self._lock = threading.RLock()

    def create(self, *, kind: str, url: str, state: str = "working") -> Job:
        job = Job(id=str(uuid.uuid4()), kind=kind, url=url, state=state)
        with self._lock:
            self._jobs[job.id] = job
        return job

    def get(self, job_id: str) -> Job | None:
        with self._lock:
            return self._jobs.get(job_id)

    def update(self, job_id: str, **fields: Any) -> Job | None:
        with self._lock:
            job = self._jobs.get(job_id)
            if not job:
                return None
            for key, value in fields.items():
                setattr(job, key, value)
            job.updated_at = utc_now()
            return job

    def set_progress(self, job_id: str, **values: Any) -> None:
        with self._lock:
            job = self._jobs.get(job_id)
            if not job:
                return
            job.progress.update(values)
            job.updated_at = utc_now()

    def complete(self, job_id: str, *, file_path: Path, download_name: str) -> Job | None:
        now = utc_now()
        with self._lock:
            job = self._jobs.get(job_id)
            if not job:
                return None
            job.state = "complete"
            job.file_path = file_path
            job.download_name = download_name
            job.error = None
            job.completed_at = now
            job.expires_at = now + timedelta(seconds=DOWNLOAD_TTL_SECONDS)
            job.progress.update({
                "stage": "complete",
                "percent": 100.0,
                "speed": None,
                "eta": None,
            })
            job.updated_at = now
            return job

    def fail(self, job_id: str, message: str) -> Job | None:
        now = utc_now()
        with self._lock:
            job = self._jobs.get(job_id)
            if not job:
                return None
            job.state = "error"
            job.error = message
            job.completed_at = now
            job.expires_at = now + timedelta(seconds=DOWNLOAD_TTL_SECONDS)
            job.updated_at = now
            return job

    def status(self, job_id: str) -> dict[str, Any] | None:
        with self._lock:
            job = self._jobs.get(job_id)
            return job.public_status() if job else None

    def cleanup(self) -> int:
        """Remove expired jobs and on-disk files older than the TTL."""
        now = utc_now()
        removed_jobs = 0
        with self._lock:
            for job_id, job in list(self._jobs.items()):
                if job.state in {"downloading", "working", "fetching_info", "processing"}:
                    continue
                expiry = job.expires_at
                if expiry is None:
                    expiry = (job.completed_at or job.created_at) + timedelta(seconds=DOWNLOAD_TTL_SECONDS)
                    job.expires_at = expiry
                if expiry <= now:
                    self._jobs.pop(job_id, None)
                    removed_jobs += 1
            active_stems = {
                job.id for job in self._jobs.values()
                if job.state not in TERMINAL_STATES
            }

        removed_files = 0
        cutoff = time.time() - DOWNLOAD_TTL_SECONDS
        if DOWNLOADS_DIR.exists():
            for entry in DOWNLOADS_DIR.iterdir():
                if entry.name == ".gitkeep":
                    continue
                try:
                    if entry.is_dir():
                        # Temporary unzipping directories are named
                        # ".<job-id>.parts"; keep ones owned by active jobs.
                        active_dir = any(entry.name == f".{stem}.parts" for stem in active_stems)
                        if entry.stat().st_mtime < cutoff and not active_dir:
                            shutil.rmtree(entry, ignore_errors=True)
                            removed_files += 1
                        continue
                    if entry.stat().st_mtime >= cutoff:
                        continue
                    if entry.stem in active_stems:
                        continue
                    entry.unlink(missing_ok=True)
                    removed_files += 1
                except OSError:
                    logger.debug("Could not clean up %s", entry, exc_info=True)

        if removed_jobs or removed_files:
            logger.info("Cleanup removed %s jobs and %s files", removed_jobs, removed_files)
        return removed_files


job_store = JobStore()


# ---------------------------------------------------------------------------
# Rate limiting
# ---------------------------------------------------------------------------

class SlidingWindowLimiter:
    def __init__(self, limit: int, window_seconds: int) -> None:
        self.limit = limit
        self.window = window_seconds
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def hit(self, key: str) -> int | None:
        """Return retry-after seconds when limited, otherwise None."""
        now = time.monotonic()
        cutoff = now - self.window
        with self._lock:
            bucket = self._hits[key]
            while bucket and bucket[0] <= cutoff:
                bucket.popleft()
            if len(bucket) >= self.limit:
                return max(1, int(self.window - (now - bucket[0])) + 1)
            bucket.append(now)
            if len(self._hits) > 4096:
                for stale_key in [k for k, v in self._hits.items() if not v]:
                    self._hits.pop(stale_key, None)
            return None


_RATE_LIMITERS: dict[str, SlidingWindowLimiter] = {}
_RATE_LIMIT_LOCK = threading.Lock()


def rate_limit(scope: str, limit: int, window_seconds: int = 60):
    with _RATE_LIMIT_LOCK:
        limiter = _RATE_LIMITERS.setdefault(scope, SlidingWindowLimiter(limit, window_seconds))

    async def dependency(request: Request) -> None:
        client = request.client.host if request.client else "unknown"
        retry_after = limiter.hit(client)
        if retry_after is not None:
            raise HTTPException(
                status_code=429,
                detail="Too many requests. Please wait a moment and try again.",
                headers={"Retry-After": str(retry_after)},
            )

    return dependency


# ---------------------------------------------------------------------------
# HTTP fetching with redirect validation
# ---------------------------------------------------------------------------

_http_client: httpx.AsyncClient | None = None


def get_http_client() -> httpx.AsyncClient:
    if _http_client is None:
        raise FriendlyError("The server is still starting. Try again in a second.", 503)
    return _http_client


def _http_error_message(status_code: int) -> str:
    if status_code in (401, 403):
        return f"The site refused the request (HTTP {status_code}). It may require a sign-in or block downloading."
    if status_code == 404:
        return "The page or image wasn't found (HTTP 404)."
    if status_code == 429:
        return "The site is rate-limiting requests. Wait a minute and try again."
    if 500 <= status_code:
        return f"The remote site had a server error (HTTP {status_code})."
    return f"The remote site returned HTTP {status_code}."


async def fetch_public_bytes(
    url: str,
    *,
    max_bytes: int,
    referer: str | None = None,
    accept: str = IMAGE_ACCEPT,
    timeout: float = 25.0,
) -> tuple[bytes, str, str]:
    """Fetch a public URL with manual redirects and a hard size limit."""
    client = get_http_client()
    current = normalize_url(url)
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": accept,
        "Accept-Language": "en-US,en;q=0.8",
    }
    if referer:
        headers["Referer"] = referer

    for _ in range(MAX_REDIRECTS + 1):
        current = await ensure_public_url(current)
        try:
            async with client.stream(
                "GET",
                current,
                headers=headers,
                timeout=httpx.Timeout(timeout, connect=10.0),
                follow_redirects=False,
            ) as response:
                if response.status_code in {301, 302, 303, 307, 308}:
                    location = response.headers.get("location")
                    if not location:
                        raise MediaFetchError("The site returned a redirect without a destination.")
                    current = urljoin(current, location)
                    continue

                if response.status_code < 200 or response.status_code >= 300:
                    raise MediaFetchError(
                        _http_error_message(response.status_code),
                        502,
                    )

                length = _coerce_int(response.headers.get("content-length"))
                if length is not None and length > max_bytes:
                    raise MediaFetchError(
                        f"That file is larger than the {max_bytes // (1024 * 1024)} MB safety limit.",
                        413,
                    )

                body = bytearray()
                async for chunk in response.aiter_bytes(chunk_size=64 * 1024):
                    if len(body) + len(chunk) > max_bytes:
                        raise MediaFetchError(
                            f"That file is larger than the {max_bytes // (1024 * 1024)} MB safety limit.",
                            413,
                        )
                    body.extend(chunk)
                if not body:
                    raise MediaFetchError("The remote server returned an empty response.", 502)
                return bytes(body), current, response.headers.get("content-type", "").lower()
        except MediaFetchError:
            raise
        except FriendlyError:
            raise
        except httpx.TimeoutException:
            raise MediaFetchError("The site took too long to respond. Try again.", 504) from None
        except httpx.HTTPError as exc:
            logger.debug("HTTP fetch failed for %s: %s", current, exc)
            raise MediaFetchError("We couldn't reach that site. Check the URL and try again.", 502) from None

    raise MediaFetchError("Too many redirects. Try a different link.", 400)


async def fetch_page_html(url: str) -> tuple[str, str]:
    data, final_url, content_type = await fetch_public_bytes(
        url,
        max_bytes=MAX_PAGE_BYTES,
        accept="text/html,application/xhtml+xml;q=0.9,*/*;q=0.5",
        timeout=30.0,
    )
    if "html" not in content_type and not data.lstrip().startswith(b"<"):
        if content_type.startswith("image/") or Path(urlsplit(final_url).path).suffix.lower() in IMAGE_EXTENSIONS:
            raise DirectImageFound(final_url)
        raise MediaFetchError("That link doesn't point to a web page.", 400)
    try:
        html = data.decode("utf-8")
    except UnicodeDecodeError:
        html = data.decode("utf-8", errors="replace")
    return html, final_url


# ---------------------------------------------------------------------------
# Image identification and HTML parsing
# ---------------------------------------------------------------------------

def inspect_image_bytes(data: bytes) -> tuple[str, str]:
    """Verify that bytes are a raster image and return (extension, mime)."""
    try:
        with Image.open(BytesIO(data)) as image:
            fmt = (image.format or "").upper()
            image.verify()
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
        raise MediaFetchError("That URL didn't return a valid image.", 415) from None
    info = IMAGE_FORMAT_MAP.get(fmt)
    if not info:
        raise MediaFetchError("That image format isn't supported.", 415)
    return info[1], info[0]


def _parse_srcset(value: str | None) -> list[tuple[int, str]]:
    if not value:
        return []
    candidates: list[tuple[int, str]] = []
    for item in value.split(","):
        parts = item.strip().split()
        if not parts:
            continue
        source = parts[0]
        score = 1
        if len(parts) > 1:
            descriptor = parts[1].lower()
            try:
                if descriptor.endswith("w"):
                    score = int(float(descriptor[:-1]))
                elif descriptor.endswith("x"):
                    score = int(float(descriptor[:-1]) * 1000)
            except ValueError:
                score = 1
        candidates.append((score, source))
    return candidates


def _looks_like_ui_asset(url: str, classes: str, alt: str, width: int | None, height: int | None) -> bool:
    haystack = f"{url} {classes} {alt}".lower()
    tokens = ("logo", "favicon", "sprite", "icon", "avatar", "emoji", "badge", "tracking", "pixel")
    if any(token in haystack for token in tokens):
        return True
    if width is not None and height is not None and (width <= 2 or height <= 2):
        return True
    return False


def _meta_content(soup: BeautifulSoup, *, prop: str | None = None, name: str | None = None) -> list[str]:
    values: list[str] = []
    attrs_list: list[dict[str, str]] = []
    if prop:
        attrs_list.append({"property": prop})
        attrs_list.append({"name": prop})
    if name:
        attrs_list.append({"name": name})
        attrs_list.append({"property": name})
    for attrs in attrs_list:
        for tag in soup.find_all("meta", attrs=attrs):
            content = (tag.get("content") or "").strip()
            if content:
                values.append(content)
    return values


def _json_ld_image_urls(soup: BeautifulSoup) -> list[str]:
    results: list[str] = []

    def walk(value: Any) -> None:
        if isinstance(value, dict):
            for key, item in value.items():
                if key.lower() == "image":
                    if isinstance(item, str):
                        results.append(item)
                    elif isinstance(item, list):
                        for nested in item:
                            if isinstance(nested, str):
                                results.append(nested)
                            elif isinstance(nested, dict) and isinstance(nested.get("url"), str):
                                results.append(nested["url"])
                    elif isinstance(item, dict) and isinstance(item.get("url"), str):
                        results.append(item["url"])
                else:
                    walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)

    for script in soup.find_all("script", attrs={"type": "application/ld+json"}):
        raw = script.string or script.get_text() or ""
        if not raw.strip():
            continue
        try:
            walk(json.loads(raw))
        except (json.JSONDecodeError, TypeError):
            continue
    return results


def parse_html_images(html: str, page_url: str) -> tuple[list[dict[str, Any]], str, int]:
    """Extract likely content images from an arbitrary page.

    gallery-dl handles known gallery sites. This parser is the fallback for
    everything else and explicitly supports srcset, lazy-load attributes,
    <picture>, social metadata and JSON-LD images.
    """
    try:
        soup = BeautifulSoup(html, "lxml")
    except Exception:
        soup = BeautifulSoup(html, "html.parser")

    title = ""
    for candidate in (
        *_meta_content(soup, prop="og:title"),
        *_meta_content(soup, name="twitter:title"),
    ):
        if candidate:
            title = candidate
            break
    if not title and soup.title and soup.title.string:
        title = soup.title.string.strip()
    if not title:
        title = urlsplit(page_url).hostname or "Images"

    base_url = page_url
    base_tag = soup.find("base", href=True)
    if base_tag:
        possible_base = urljoin(page_url, base_tag["href"])
        if urlsplit(possible_base).scheme in {"http", "https"}:
            base_url = possible_base

    seen: dict[str, dict[str, Any]] = {}
    order = 0

    def add(
        raw_url: str | None,
        *,
        score: int,
        alt: str = "",
        width: int | None = None,
        height: int | None = None,
        origin: str = "img",
    ) -> None:
        nonlocal order
        if not raw_url:
            return
        raw_url = raw_url.strip()
        if not raw_url or raw_url.startswith(("data:", "blob:", "javascript:", "#")):
            return
        try:
            absolute = urljoin(base_url, raw_url)
            parts = urlsplit(absolute)
        except ValueError:
            return
        if parts.scheme.lower() not in {"http", "https"} or not parts.netloc:
            return
        if parts.username or parts.password:
            return
        try:
            _ = parts.port
        except ValueError:
            return
        cleaned = urlunsplit((parts.scheme.lower(), parts.netloc, parts.path, parts.query, ""))
        existing = seen.get(cleaned)
        if existing:
            existing["score"] = max(existing["score"], score)
            if alt and not existing["alt"]:
                existing["alt"] = alt
            return
        seen[cleaned] = {
            "url": cleaned,
            "score": score,
            "alt": alt[:300],
            "width": width,
            "height": height,
            "origin": origin,
            "order": order,
        }
        order += 1

    # Social / structured metadata is usually the publisher's chosen hero image.
    for meta_url in (
        *_meta_content(soup, prop="og:image"),
        *_meta_content(soup, name="twitter:image"),
    ):
        add(meta_url, score=1000, origin="meta")
    for meta_url in _json_ld_image_urls(soup)[:10]:
        add(meta_url, score=900, origin="json_ld")
    link_image = soup.find("link", rel=lambda value: value and "image_src" in value, href=True)
    if link_image:
        add(link_image.get("href"), score=850, origin="meta")

    for image in soup.find_all("img"):
        classes = " ".join(image.get("class") or [])
        alt = (image.get("alt") or "").strip()
        width = _coerce_int(image.get("width"))
        height = _coerce_int(image.get("height"))
        if width is None:
            width = _coerce_int(image.get("data-width"))
        if height is None:
            height = _coerce_int(image.get("data-height"))

        sources: list[tuple[int, str]] = []
        for attr in ("data-srcset", "data-lazy-srcset", "srcset"):
            for descriptor_score, source in _parse_srcset(image.get(attr)):
                sources.append((5000 + descriptor_score, source))
        for attr in ("data-src", "data-original", "data-lazy-src", "data-url", "data-image"):
            value = (image.get(attr) or "").strip()
            if value:
                sources.append((4000, value))
        src = (image.get("src") or "").strip()
        if src:
            sources.append((100, src))
        if not sources:
            continue
        _, best_source = max(sources, key=lambda item: item[0])

        score = 40
        if alt:
            score += 12
        if width and height:
            if min(width, height) < 32:
                score -= 90
            if width >= 800 or height >= 500:
                score += 45
            elif width >= 400:
                score += 20
        if any(attr in image.attrs for attr in ("srcset", "data-srcset", "data-src", "data-original")):
            score += 20
        for parent in image.parents:
            if getattr(parent, "name", None) in {"article", "main", "figure"}:
                score += 25
                break
            if getattr(parent, "name", None) in {"nav", "header", "footer"}:
                score -= 40
                break
        suffix = Path(urlsplit(best_source).path).suffix.lower()
        if suffix in IMAGE_EXTENSIONS:
            score += 10
        if _looks_like_ui_asset(best_source, classes, alt, width, height):
            score -= 140
        add(best_source, score=score, alt=alt, width=width, height=height)

    for source_tag in soup.find_all("source"):
        srcset = source_tag.get("srcset") or source_tag.get("data-srcset")
        parsed = _parse_srcset(srcset)
        if parsed:
            _, best_source = max(parsed, key=lambda item: item[0])
            add(best_source, score=55, origin="picture")

    # Linked full-size image files are a useful fallback on gallery pages.
    for anchor in soup.find_all("a", href=True):
        href = anchor.get("href") or ""
        suffix = Path(urlsplit(href).path).suffix.lower()
        if suffix in IMAGE_EXTENSIONS:
            add(href, score=30, origin="link")

    candidates = [
        item for item in seen.values()
        if item["score"] >= 0
    ]
    candidates.sort(key=lambda item: (-item["score"], item["order"]))

    images: list[dict[str, Any]] = []
    for item in candidates[:MAX_IMAGE_COUNT]:
        images.append({
            "url": item["url"],
            "filename": filename_from_url(item["url"]),
            "alt": item["alt"],
            "width": item["width"],
            "height": item["height"],
        })
    return images, title[:200], len(candidates)


# ---------------------------------------------------------------------------
# gallery-dl integration
# ---------------------------------------------------------------------------

def _make_limited_job():
    if not GALLERY_DL_AVAILABLE:
        return None

    class _LimitedDataJob(gallery_job.DataJob):  # type: ignore[misc]
        """Collect image URLs without downloading files, capped at max_items."""

        def __init__(self, url, parent=None, file=None, ensure_ascii=True, resolve=False, max_items=MAX_IMAGE_COUNT):
            self.max_items = max_items
            super().__init__(
                url,
                parent=parent,
                file=file,
                ensure_ascii=ensure_ascii,
                resolve=resolve,
            )

        def handle_url(self, url, kwdict):
            if len(self.data_urls) >= self.max_items:
                raise gallery_exception.StopExtraction()
            super().handle_url(url, kwdict)
            if len(self.data_urls) >= self.max_items:
                raise gallery_exception.StopExtraction()

    return _LimitedDataJob


_LimitedDataJob = _make_limited_job()


# ---------------------------------------------------------------------------
# Update tools (yt-dlp + gallery-dl)
# ---------------------------------------------------------------------------

_update_lock = threading.Lock()


def _tool_versions() -> tuple[str | None, str | None]:
    ytdlp_version = getattr(getattr(yt_dlp, "version", None), "__version__", None)
    gallery_version = None
    with contextlib.suppress(Exception):
        from gallery_dl.version import __version__ as gallery_version
    return ytdlp_version, gallery_version


def _reload_tools() -> None:
    """Load the freshly installed yt-dlp and gallery-dl without a restart."""
    global yt_dlp, gallery_config, gallery_exception, gallery_extractor, gallery_job
    global GalleryMessage, GALLERY_DL_AVAILABLE, _LimitedDataJob

    for name in list(sys.modules):
        if name.split(".")[0] in {"yt_dlp", "gallery_dl"}:
            del sys.modules[name]
    importlib.invalidate_caches()

    import yt_dlp as new_yt_dlp
    yt_dlp = new_yt_dlp
    try:
        from gallery_dl import config, exception, extractor, job
        from gallery_dl.extractor.message import Message
        gallery_config, gallery_exception = config, exception
        gallery_extractor, gallery_job, GalleryMessage = extractor, job, Message
        GALLERY_DL_AVAILABLE = True
    except Exception:
        GALLERY_DL_AVAILABLE = False
    _LimitedDataJob = _make_limited_job()
    configure_gallery_dl()


def _update_tools_desktop(before: tuple[str | None, str | None]) -> dict[str, Any]:
    """Desktop app: install new tools into the app data folder, keep a backup,
    and go back to the old version if anything fails. No admin rights needed."""
    assert TOOLS_DIR is not None
    new, current, backup = TOOLS_DIR / "new", TOOLS_DIR / "current", TOOLS_DIR / "backup"
    TOOLS_DIR.mkdir(parents=True, exist_ok=True)
    shutil.rmtree(new, ignore_errors=True)
    fail = FriendlyError("Couldn't update. No internet? Your old version still works.", 500, "update_failed")
    try:
        proc = subprocess.run(
            [sys.executable, "-m", "pip", "install", "-U", "--disable-pip-version-check",
             "--target", str(new), "yt-dlp", "gallery-dl"],
            capture_output=True, text=True, timeout=300,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    except subprocess.TimeoutExpired:
        shutil.rmtree(new, ignore_errors=True)
        raise FriendlyError("The update took too long. Check your internet and try again.", 504, "update_timeout") from None
    if proc.returncode != 0:
        logger.warning("Tool update failed: %s", proc.stderr[-500:])
        shutil.rmtree(new, ignore_errors=True)
        raise fail
    # Swap: current -> backup, new -> current.
    shutil.rmtree(backup, ignore_errors=True)
    if current.exists():
        current.rename(backup)
    new.rename(current)
    if str(current) not in sys.path:
        sys.path.insert(0, str(current))
    try:
        _reload_tools()
        ytdlp_version, gallery_version = _tool_versions()
        if not ytdlp_version:
            raise RuntimeError("yt-dlp did not load")
    except Exception:
        logger.warning("New tools failed to load; going back to the old version", exc_info=True)
        shutil.rmtree(current, ignore_errors=True)
        if backup.exists():
            backup.rename(current)
        else:
            with contextlib.suppress(ValueError):
                sys.path.remove(str(current))
        with contextlib.suppress(Exception):
            _reload_tools()
        raise FriendlyError("The new version did not work, so the old one is back.", 500, "update_rolled_back") from None
    if (ytdlp_version, gallery_version) == before:
        message = f"Already up to date (yt-dlp {ytdlp_version}, gallery-dl {gallery_version})"
    else:
        message = f"Updated to version {ytdlp_version} (yt-dlp) and {gallery_version} (gallery-dl). Old version kept as backup."
    return {"message": message, "yt_dlp": ytdlp_version, "gallery_dl": gallery_version}


def _update_tools_sync() -> dict[str, Any]:
    if not _update_lock.acquire(blocking=False):
        raise FriendlyError("An update is already running. Please wait.", 409, "update_busy")
    try:
        before = _tool_versions()
        if TOOLS_DIR:
            return _update_tools_desktop(before)
        try:
            proc = subprocess.run(
                [sys.executable, "-m", "pip", "install", "-U", "--disable-pip-version-check",
                 "yt-dlp", "gallery-dl"],
                capture_output=True, text=True, timeout=300,
            )
        except subprocess.TimeoutExpired:
            raise FriendlyError("The update took too long. Check your internet and try again.", 504, "update_timeout") from None
        if proc.returncode != 0:
            logger.warning("Tool update failed: %s", proc.stderr[-500:])
            raise FriendlyError(
                "Couldn't update here (no internet or no permission). "
                "Try running: pip install -U yt-dlp gallery-dl",
                500,
                "update_failed",
            )
        _reload_tools()
        ytdlp_version, gallery_version = _tool_versions()
        if (ytdlp_version, gallery_version) == before:
            message = f"Already up to date (yt-dlp {ytdlp_version}, gallery-dl {gallery_version})"
        else:
            message = f"Updated to version {ytdlp_version} (yt-dlp) and {gallery_version} (gallery-dl)"
        return {"message": message, "yt_dlp": ytdlp_version, "gallery_dl": gallery_version}
    finally:
        _update_lock.release()


def configure_gallery_dl() -> None:
    if not GALLERY_DL_AVAILABLE:
        return
    try:
        gallery_config.set(("extractor",), "timeout", 20)
        gallery_config.set(("extractor",), "retries", 1)
        gallery_config.set(("extractor",), "user-agent", USER_AGENT)
        gallery_config.set(("extractor",), "sleep-request", 0.1)
        # Same login source as yt-dlp: cookies.txt first, else a browser.
        if COOKIES_BROWSER:
            gallery_config.set(("extractor",), "cookies", [COOKIES_BROWSER])
        elif COOKIES_FILE.is_file():
            gallery_config.set(("extractor",), "cookies", str(COOKIES_FILE))
        else:
            gallery_config.set(("extractor",), "cookies", None)
    except Exception:
        logger.debug("Could not tune gallery-dl configuration", exc_info=True)


def _extract_gallery_images_sync(url: str) -> dict[str, Any] | None:
    """Use gallery-dl for sites with a dedicated extractor.

    gallery-dl's generic extractor is intentionally broad, so generic pages go
    through parse_html_images() instead. Site-specific extractors are very good
    at finding the actual gallery images and get a DataJob in no-download mode.
    """
    if not GALLERY_DL_AVAILABLE:
        return None
    try:
        extractor = gallery_extractor.find(url)
    except Exception:
        logger.debug("gallery-dl extractor lookup failed", exc_info=True)
        return None
    if extractor is None:
        return None
    if getattr(extractor, "category", "") in {"generic", "recursive"}:
        return None

    status = 0
    gallery_log: list[str] = []

    class _Capture(logging.Handler):
        def emit(self, record: logging.LogRecord) -> None:
            gallery_log.append(record.getMessage())

    capture = _Capture(level=logging.WARNING)
    tool_logger = logging.getLogger(str(getattr(extractor, "category", "") or "gallery-dl"))
    tool_logger.addHandler(capture)
    try:
        collector = _LimitedDataJob(extractor, file=None, resolve=True, max_items=MAX_IMAGE_COUNT)
        status = collector.run() or 0
    except Exception:
        logger.debug("gallery-dl extraction failed", exc_info=True)
        return None
    finally:
        tool_logger.removeHandler(capture)

    # The real reason is often only in gallery-dl's log (for example Instagram
    # redirecting to its login page). Do not hide it behind "No images found".
    for entry in collector.data:
        # gallery-dl stores extraction errors in its data as (-1, {"error", "message"}).
        if entry and entry[0] == -1 and len(entry) > 1 and isinstance(entry[1], dict):
            gallery_log.append(f"{entry[1].get('error')}: {entry[1].get('message')}")
    log_text = _strip_ansi(" | ".join(gallery_log))[:1500]
    if not collector.data_urls and log_text:
        low_log = log_text.lower()
        if any(word in low_log for word in ("login", "log in", "authentication", "sessionid", "csrftoken")):
            is_instagram = getattr(extractor, "category", "") == "instagram"
            raise FriendlyError(
                INSTAGRAM_LOGIN_HELP if is_instagram else LOGIN_HELP,
                422,
                "login_required",
                detail=log_text,
            )

    # gallery-dl exit-style flags: 4 = HTTP error, 16 = login needed.
    if status & 16 and not collector.data_urls:
        raise FriendlyError(LOGIN_HELP, 422, "login_required", detail=log_text)
    if status & 4 and not collector.data_urls:
        raise FriendlyError(
            "The site refused the request. It may need login or is limiting requests; try again later.",
            422,
            "site_refused",
            detail=log_text,
        )

    title = ""
    images: list[dict[str, Any]] = []
    seen: set[str] = set()

    for message in collector.data:
        if len(images) >= MAX_IMAGE_COUNT:
            break
        if not message:
            continue
        if GalleryMessage and message[0] == GalleryMessage.Directory and len(message) > 1:
            metadata = message[1] or {}
            title = title or str(
                metadata.get("title") or metadata.get("og_title") or metadata.get("name") or ""
            )
            continue
        if not GalleryMessage or message[0] != GalleryMessage.Url or len(message) < 2:
            continue
        image_url = message[1]
        metadata = message[2] if len(message) > 2 and isinstance(message[2], dict) else {}
        if not isinstance(image_url, str):
            continue
        try:
            parts = urlsplit(image_url)
        except ValueError:
            continue
        if parts.scheme.lower() not in {"http", "https"} or not parts.netloc:
            continue

        extension = str(metadata.get("extension") or "").lower().lstrip(".")
        path_suffix = Path(parts.path).suffix.lower().lstrip(".")
        if extension in {item.lstrip(".") for item in VIDEO_EXTENSIONS}:
            continue
        if path_suffix in {item.lstrip(".") for item in VIDEO_EXTENSIONS}:
            continue
        if extension and extension not in {item.lstrip(".") for item in IMAGE_EXTENSIONS}:
            if path_suffix not in {item.lstrip(".") for item in IMAGE_EXTENSIONS}:
                continue
        if path_suffix and path_suffix not in {item.lstrip(".") for item in IMAGE_EXTENSIONS}:
            if extension not in {item.lstrip(".") for item in IMAGE_EXTENSIONS}:
                continue

        normalized = urlunsplit((parts.scheme.lower(), parts.netloc, parts.path, parts.query, ""))
        if normalized in seen:
            continue
        seen.add(normalized)
        suggested = metadata.get("filename") or filename_from_url(normalized)
        images.append({
            "url": normalized,
            "filename": sanitize_filename(str(suggested), fallback="image", max_length=90),
            "alt": str(metadata.get("title") or metadata.get("description") or "")[:300],
            "width": _coerce_int(metadata.get("width")),
            "height": _coerce_int(metadata.get("height")),
        })

    if not images:
        return None
    return {"images": images, "title": page_title_or_slug(title, url)}


async def extract_gallery_images(url: str) -> dict[str, Any] | None:
    if not GALLERY_DL_AVAILABLE:
        return None
    try:
        return await asyncio.wait_for(
            asyncio.to_thread(_extract_gallery_images_sync, url),
            timeout=GALLERY_EXTRACT_TIMEOUT_SECONDS,
        )
    except asyncio.TimeoutError:
        logger.info("gallery-dl timed out for %s; trying generic HTML", url)
        return None
    except FriendlyError:
        raise
    except Exception:
        logger.debug("gallery-dl failed for %s", url, exc_info=True)
        return None


async def extract_images(url: str) -> dict[str, Any]:
    """Try a dedicated gallery extractor first, then a safe HTML fallback."""
    gallery_result = await extract_gallery_images(url)
    if gallery_result and gallery_result.get("images"):
        return {
            "images": gallery_result["images"],
            "title": gallery_result.get("title") or (urlsplit(url).hostname or "Images"),
            "extractor": "gallery-dl",
            "total_found": len(gallery_result["images"]),
        }

    try:
        html, final_url = await fetch_page_html(url)
        images, title, total_found = parse_html_images(html, final_url)
    except DirectImageFound as exc:
        images = [{
            "url": exc.url,
            "filename": filename_from_url(exc.url),
            "alt": "",
            "width": None,
            "height": None,
        }]
        title = filename_from_url(exc.url)
        total_found = 1

    if not images:
        raise FriendlyError(
            "No images were found on that page. Try a gallery, article, or direct image link.",
            422,
            "no_images",
        )
    return {
        "images": images,
        "title": title,
        "extractor": "html",
        "total_found": total_found,
    }


# ---------------------------------------------------------------------------
# yt-dlp metadata and downloads
# ---------------------------------------------------------------------------

def _format_size(fmt: dict[str, Any]) -> int | None:
    for key in ("filesize", "filesize_approx"):
        size = _coerce_int(fmt.get(key))
        if size and size > 0:
            return size
    return None


def _video_rank(fmt: dict[str, Any]) -> tuple[float, float, int]:
    return (
        _coerce_float(fmt.get("tbr")) or 0.0,
        _coerce_float(fmt.get("quality")) or 0.0,
        _format_size(fmt) or 0,
    )


def _estimate_size(fmt: dict[str, Any] | None, duration: float | None) -> int | None:
    """Use a real byte count when available, otherwise bitrate × duration."""
    if not fmt:
        return None
    known = _format_size(fmt)
    if known:
        return known
    bitrate = _coerce_float(fmt.get("tbr"))
    if not bitrate:
        bitrate = (_coerce_float(fmt.get("vbr")) or 0.0) + (_coerce_float(fmt.get("abr")) or 0.0)
    if bitrate and duration and duration > 0:
        return int(bitrate * 1000 / 8 * duration)
    return None


def build_format_options(info: dict[str, Any], duration: float | None = None) -> list[dict[str, Any]]:
    formats = [fmt for fmt in (info.get("formats") or []) if isinstance(fmt, dict)]
    video_formats = [
        fmt for fmt in formats
        if fmt.get("vcodec") not in (None, "none")
        and (_coerce_int(fmt.get("height")) or 0) >= 144
    ]
    audio_formats = [
        fmt for fmt in formats
        if fmt.get("acodec") not in (None, "none")
        and fmt.get("vcodec") in (None, "none")
    ]

    audio_best = max(audio_formats, key=_video_rank, default=None)
    audio_size = _estimate_size(audio_best, duration)

    by_height: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for fmt in video_formats:
        height = _coerce_int(fmt.get("height"))
        if height:
            by_height[height].append(fmt)

    best_height = max(by_height, default=None)
    best_video = max(video_formats, key=_video_rank, default=None)
    best_size = _estimate_size(best_video, duration)
    if best_video and best_video.get("acodec") in (None, "none") and audio_size:
        best_size = (best_size or 0) + audio_size

    options: list[dict[str, Any]] = [{
        "key": "best",
        "label": "Best quality",
        "detail": f"Up to {best_height}p" if best_height else "Highest available",
        "kind": "video",
        "size_bytes": best_size,
        "recommended": True,
    }]

    for height in sorted(by_height, reverse=True):
        candidate = max(by_height[height], key=_video_rank)
        size = _estimate_size(candidate, duration)
        if candidate.get("acodec") in (None, "none") and audio_size:
            size = (size or 0) + audio_size
        options.append({
            "key": str(height),
            "label": f"{height}p",
            "detail": "MP4 video",
            "kind": "video",
            "size_bytes": size,
            "recommended": False,
        })

    if audio_best:
        options.append({
            "key": "audio",
            "label": "Audio only",
            "detail": "MP3 · 192 kbps",
            "kind": "audio",
            "size_bytes": audio_size,
            "recommended": False,
        })

    return options


_cookie_warning_lock = threading.Lock()
_cookie_warning_emitted = False


def _materialize_cookies_from_env() -> None:
    """Write the cookie jar from a deployment secret when provided."""
    encoded = os.getenv("YOUTUBE_COOKIES_B64", "").strip()
    if not encoded:
        return
    try:
        text = base64.b64decode(encoded, validate=True).decode("utf-8")
        COOKIES_FILE.write_text(text, encoding="utf-8")
        with contextlib.suppress(OSError):
            os.chmod(COOKIES_FILE, 0o600)
        logger.info("Loaded YouTube cookies from YOUTUBE_COOKIES_B64")
    except Exception:
        logger.warning("Could not decode YOUTUBE_COOKIES_B64; continuing without it")


def _cookie_options() -> dict[str, str]:
    """Return yt-dlp cookie options, or an empty dict when cookies are absent."""
    global _cookie_warning_emitted

    try:
        has_cookies = COOKIES_FILE.is_file()
    except OSError:
        has_cookies = False

    # A browser you picked wins over cookies.txt.
    if COOKIES_BROWSER:
        return {"cookiesfrombrowser": (COOKIES_BROWSER,)}
    if has_cookies:
        return {"cookiefile": str(COOKIES_FILE)}

    with _cookie_warning_lock:
        if not _cookie_warning_emitted:
            logger.warning(
                "cookies.txt not found at %s. Continuing without cookies; "
                "YouTube may block requests from this server IP.",
                COOKIES_FILE,
            )
            _cookie_warning_emitted = True
    return {}


def _base_ydl_options() -> dict[str, Any]:
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
        # YouTube increasingly requires a JavaScript challenge solver before
        # it will expose media formats. yt-dlp fetches and caches the EJS
        # solver from its official GitHub component repository.
        "remote_components": {"ejs:github"},
    }
    options.update(_cookie_options())
    return options


def extract_video_info_sync(url: str) -> dict[str, Any]:
    options = _base_ydl_options()
    options.update({
        "skip_download": True,
        "format": "bv*+ba/b",
        "extractor_args": {
            "youtube": {
                # Keep mobile clients, but include web because yt-dlp skips
                # android/ios whenever a cookie file is loaded.
                "player_client": ["android", "ios", "web_embedded", "tv_samsung", "web"]
            }
        },
        "http_headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        },
    })
    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=False)

    if not isinstance(info, dict):
        raise FriendlyError("This link didn't return video information.", 422, "no_video")

    entries = info.get("entries")
    if info.get("_type") == "playlist" and entries:
        raise FriendlyError("Playlists aren't supported here. Paste a link to a single video.", 422, "playlist")
    live_status = str(info.get("live_status") or "")
    if info.get("is_live") or live_status in {"is_live", "is_upcoming"}:
        raise FriendlyError("Live or upcoming streams aren't supported yet.", 422, "live_stream")

    formats = [fmt for fmt in (info.get("formats") or []) if isinstance(fmt, dict)]
    has_playable_format = any(
        fmt.get("vcodec") not in (None, "none")
        or fmt.get("acodec") not in (None, "none")
        # Direct files (plain .mp4 links etc.) often have no codec info at all.
        or (fmt.get("url") and fmt.get("ext") not in {"mhtml", "none"} and fmt.get("protocol") != "mhtml")
        for fmt in formats
    )
    if not has_playable_format:
        if "youtube" in str(info.get("extractor_key") or "").lower():
            raise FriendlyError(LOGIN_HELP_BOT, 422, "no_formats")
        raise FriendlyError("No video was found in this link.", 422, "no_formats")

    title = str(info.get("title") or "Untitled video").strip()[:300]
    thumbnail = info.get("thumbnail")
    if not isinstance(thumbnail, str) or not thumbnail.startswith(("http://", "https://")):
        thumbnail = None
        for candidate in reversed(info.get("thumbnails") or []):
            if isinstance(candidate, dict):
                candidate_url = candidate.get("url")
                if isinstance(candidate_url, str) and candidate_url.startswith(("http://", "https://")):
                    thumbnail = candidate_url
                    break

    webpage_url = info.get("webpage_url") or url
    uploader = info.get("uploader") or info.get("channel") or info.get("creator") or ""
    extractor_name = info.get("extractor_key") or info.get("extractor") or ""
    duration = _coerce_float(info.get("duration"))
    options = build_format_options(info, duration)

    return {
        "title": title,
        "duration": duration,
        "webpage_url": str(webpage_url),
        "uploader": str(uploader)[:150],
        "extractor": str(extractor_name)[:80],
        "thumbnail_url": thumbnail,
        "formats": options,
    }


def _format_selector(quality: str) -> str:
    if quality in QUALITY_FORMAT_SELECTORS:
        return QUALITY_FORMAT_SELECTORS[quality]
    if quality.isdigit():
        height = int(quality)
        if 144 <= height <= 4320:
            # Added /best at the end to fallback gracefully
            return f"bestvideo[height<=?{height}]+bestaudio/best[height<=?{height}]/best"
    raise FriendlyError("Choose a valid quality option.", 400, "invalid_quality")


def _cleanup_job_files(job_id: str) -> None:
    for path in DOWNLOADS_DIR.glob(f"{job_id}.*"):
        if path.is_file():
            with contextlib.suppress(OSError):
                path.unlink()


def _find_finished_file(job_id: str) -> Path | None:
    candidates: list[Path] = []
    for path in DOWNLOADS_DIR.glob(f"{job_id}.*"):
        if not path.is_file():
            continue
        if path.name.endswith((".part", ".ytdl", ".temp")):
            continue
        try:
            if path.stat().st_size <= 0:
                continue
        except OSError:
            continue
        candidates.append(path)
    if not candidates:
        return None
    candidates.sort(key=lambda item: (item.stat().st_mtime, item.stat().st_size), reverse=True)
    return candidates[0]


def download_video_sync(job_id: str, quality: str) -> None:
    job = job_store.get(job_id)
    if not job or not job.video:
        return

    selector = _format_selector(quality)
    job_store.set_progress(job_id, stage="starting", percent=0.0, speed=None, eta=None)

    def progress_hook(data: dict[str, Any]) -> None:
        status = data.get("status")
        if status == "downloading":
            total = _coerce_int(data.get("total_bytes")) or _coerce_int(data.get("total_bytes_estimate"))
            downloaded = _coerce_int(data.get("downloaded_bytes")) or 0
            if not total:
                fragment_count = _coerce_int(data.get("fragment_count"))
                fragment_index = _coerce_int(data.get("fragment_index"))
                if fragment_count and fragment_index is not None:
                    total = fragment_count
                    downloaded = fragment_index
            percent = 0.0
            if total and total > 0:
                percent = min(99.9, max(0.0, downloaded / total * 100.0))
            job_store.set_progress(
                job_id,
                stage="downloading",
                percent=round(percent, 1),
                downloaded_bytes=downloaded,
                total_bytes=total,
                speed=_coerce_float(data.get("speed")),
                eta=_coerce_int(data.get("eta")),
            )
        elif status == "finished":
            job_store.set_progress(
                job_id,
                stage="processing",
                percent=100.0,
                speed=None,
                eta=None,
            )

    def postprocessor_hook(data: dict[str, Any]) -> None:
        if data.get("status") in {"started", "processing"}:
            job_store.set_progress(
                job_id,
                stage="processing",
                percent=100.0,
                speed=None,
                eta=None,
            )

    options = _base_ydl_options()
    options.update({
        "format": selector,
        "outtmpl": str(DOWNLOADS_DIR / f"{job_id}.%(ext)s"),
        "merge_output_format": "mp4",
        "overwrites": True,
        "continuedl": False,
        "progress_hooks": [progress_hook],
        "postprocessor_hooks": [postprocessor_hook],
        "windowsfilenames": True,
        "restrictfilenames": True,
        "extractor_args": {
            "youtube": {
                # Keep mobile clients, but include web because yt-dlp skips
                # android/ios whenever a cookie file is loaded.
                "player_client": ["android", "ios", "web_embedded", "tv_samsung", "web"]
            }
        },
        "http_headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        },
    })
    if quality == "audio":
        options["postprocessors"] = [{
            "key": "FFmpegExtractAudio",
            "preferredcodec": "mp3",
            "preferredquality": "192",
        }]

    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            ydl.download([job.url])

        final_file = _find_finished_file(job_id)
        if not final_file:
            raise FriendlyError(
                "The download finished, but no output file was found. Please try again.",
                500,
                "missing_output",
            )
        suffix = final_file.suffix
        download_name = sanitize_filename(
            job.video.get("title") or "video",
            fallback="video",
            max_length=95,
            extension=suffix,
        )
        # Remove any stray intermediate files left by a failed merge.
        for stray in DOWNLOADS_DIR.glob(f"{job_id}.*"):
            if stray != final_file and stray.is_file():
                with contextlib.suppress(OSError):
                    stray.unlink()
        job_store.complete(job_id, file_path=final_file, download_name=download_name)
        logger.info("Video job %s complete (%s)", job_id, final_file.name)
    except FriendlyError as exc:
        _cleanup_job_files(job_id)
        job_store.fail(job_id, exc.message)
        logger.warning("Video job %s failed: %s", job_id, exc.message)
    except Exception as exc:
        message = friendly_ytdlp_error(exc)
        _cleanup_job_files(job_id)
        job_store.fail(job_id, message)
        logger.warning("Video job %s failed: %s", job_id, message)
        logger.debug("yt-dlp error detail", exc_info=True)


# ---------------------------------------------------------------------------
# FastAPI lifecycle and app
# ---------------------------------------------------------------------------

_background_tasks: set[asyncio.Task[Any]] = set()
_cleanup_task: asyncio.Task[Any] | None = None


async def cleanup_loop() -> None:
    while True:
        await asyncio.sleep(CLEANUP_INTERVAL_SECONDS)
        try:
            await asyncio.to_thread(job_store.cleanup)
        except Exception:
            logger.exception("Background cleanup failed")


def _spawn(coro) -> asyncio.Task[Any]:
    task = asyncio.create_task(coro)
    _background_tasks.add(task)
    task.add_done_callback(_background_tasks.discard)
    return task


@asynccontextmanager
async def lifespan(_: FastAPI):
    global _http_client, _cleanup_task

    DOWNLOADS_DIR.mkdir(parents=True, exist_ok=True)
    _materialize_cookies_from_env()
    if _cookie_options():
        logger.info("Using yt-dlp cookies from %s", COOKIES_FILE)
    configure_gallery_dl()
    _http_client = httpx.AsyncClient(
        follow_redirects=False,
        timeout=httpx.Timeout(30.0, connect=10.0),
        limits=httpx.Limits(max_connections=40, max_keepalive_connections=20),
        headers={"User-Agent": USER_AGENT},
    )
    with contextlib.suppress(Exception):
        await asyncio.to_thread(job_store.cleanup)
    _cleanup_task = asyncio.create_task(cleanup_loop())

    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg:
        logger.info("Fetchly %s starting; FFmpeg found at %s", APP_VERSION, ffmpeg)
    else:
        logger.warning("FFmpeg was not found on PATH. Merged video and MP3 audio downloads will fail.")
    logger.info("Listening on http://%s:%s", HOST, PORT)
    logger.info("Files auto-delete after %s seconds", DOWNLOAD_TTL_SECONDS)

    try:
        yield
    finally:
        if _cleanup_task:
            _cleanup_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await _cleanup_task
        for task in list(_background_tasks):
            task.cancel()
        if _http_client is not None:
            await _http_client.aclose()
            _http_client = None


app = FastAPI(
    title=APP_NAME,
    version=APP_VERSION,
    description="A local video and image downloader built with yt-dlp and gallery-dl.",
    lifespan=lifespan,
)
app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")


def _basic_auth_ok(request: Request) -> bool:
    header = request.headers.get("authorization", "")
    if not header.startswith("Basic "):
        return False
    try:
        decoded = base64.b64decode(header[6:], validate=True).decode("utf-8")
    except Exception:
        return False
    username, separator, password = decoded.partition(":")
    return (
        bool(separator)
        and secrets.compare_digest(username, APP_USERNAME)
        and secrets.compare_digest(password, APP_PASSWORD)
    )


@app.middleware("http")
async def add_security_headers(request: Request, call_next):
    if APP_PASSWORD and request.url.path != "/api/health" and not _basic_auth_ok(request):
        return Response(
            status_code=401,
            content="Authentication required",
            media_type="text/plain",
            headers={"WWW-Authenticate": 'Basic realm="Fetchly", charset="UTF-8"'},
        )
    response = await call_next(request)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("X-Frame-Options", "DENY")
    response.headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
    response.headers.setdefault("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
    return response


# ---------------------------------------------------------------------------
# Request models and exception handlers
# ---------------------------------------------------------------------------

class UrlRequest(BaseModel):
    url: str = Field(min_length=1, max_length=4096)


class VideoDownloadRequest(BaseModel):
    job_id: str = Field(min_length=1, max_length=64)
    quality: str = Field(min_length=1, max_length=16)


class ImageZipRequest(BaseModel):
    job_id: str = Field(min_length=1, max_length=64)
    image_ids: list[str] = Field(min_length=1, max_length=MAX_IMAGE_COUNT)


@app.exception_handler(FriendlyError)
async def friendly_error_handler(_: Request, exc: FriendlyError) -> JSONResponse:
    return JSONResponse(
        status_code=exc.status_code,
        content={"error": exc.code, "message": exc.message, "detail": exc.detail},
    )


@app.exception_handler(RequestValidationError)
async def validation_error_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    logger.debug("Request validation failed: %s", exc.errors())
    return JSONResponse(
        status_code=422,
        content={"error": "invalid_request", "message": "Please check the URL and try again."},
    )


@app.exception_handler(HTTPException)
async def http_error_handler(_: Request, exc: HTTPException) -> JSONResponse:
    detail = exc.detail
    message = detail if isinstance(detail, str) else str(detail.get("message") or detail)
    return JSONResponse(
        status_code=exc.status_code,
        content={"error": "request_failed", "message": message},
        headers=exc.headers,
    )


@app.exception_handler(Exception)
async def unexpected_error_handler(request: Request, exc: Exception) -> JSONResponse:
    logger.exception("Unhandled error on %s", request.url.path)
    return JSONResponse(
        status_code=500,
        content={"error": "server_error", "message": "Something went wrong on our side. Please try again."},
    )


# ---------------------------------------------------------------------------
# Helpers used by routes
# ---------------------------------------------------------------------------

def get_job_or_404(job_id: str) -> Job:
    try:
        uuid.UUID(job_id)
    except (ValueError, AttributeError, TypeError):
        raise HTTPException(status_code=404, detail="That download link is invalid or has expired.") from None
    job = job_store.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="That download link has expired or no longer exists.")
    return job


def _public_image(job_id: str, image: dict[str, Any], index: int) -> dict[str, Any]:
    image_id = image["id"]
    return {
        "id": image_id,
        "alt": image.get("alt") or f"Image {index + 1}",
        "filename": image.get("filename") or f"image-{index + 1}",
        "preview_url": f"/api/images/{job_id}/{image_id}/preview",
        "width": image.get("width"),
        "height": image.get("height"),
    }


# ---------------------------------------------------------------------------
# Routes: pages and health
# ---------------------------------------------------------------------------

@app.get("/", response_class=HTMLResponse, include_in_schema=False)
async def index() -> HTMLResponse:
    return HTMLResponse((TEMPLATES_DIR / "index.html").read_text(encoding="utf-8"))


@app.get("/favicon.ico", include_in_schema=False)
async def favicon() -> FileResponse:
    return FileResponse(STATIC_DIR / "favicon.svg", media_type="image/svg+xml")


@app.get("/api/health")
async def health() -> dict[str, Any]:
    ffmpeg = shutil.which("ffmpeg")
    gallery_version = None
    if GALLERY_DL_AVAILABLE:
        with contextlib.suppress(Exception):
            from gallery_dl.version import __version__ as gallery_version_import
            gallery_version = gallery_version_import
    return {
        "status": "ok",
        "version": APP_VERSION,
        "ffmpeg": {
            "available": bool(ffmpeg),
            "path": ffmpeg,
        },
        "gallery_dl": {
            "available": GALLERY_DL_AVAILABLE,
            "version": gallery_version,
        },
        "yt_dlp": {
            "version": getattr(getattr(yt_dlp, "version", None), "__version__", None),
        },
        "limits": {
            "download_ttl_seconds": DOWNLOAD_TTL_SECONDS,
            "max_images": MAX_IMAGE_COUNT,
            "max_image_mb": round(MAX_IMAGE_DOWNLOAD_BYTES / (1024 * 1024), 1),
        },
    }


# ---------------------------------------------------------------------------
# Routes: login cookies (local use only; cookies are never logged or committed)
# ---------------------------------------------------------------------------

_COOKIE_BROWSERS = {"firefox", "chrome", "edge", "brave", "opera", "chromium", "vivaldi", "safari", "whale"}


def _require_local(request: Request) -> None:
    host = request.client.host if request.client else ""
    if host not in {"127.0.0.1", "::1", "localhost"}:
        raise FriendlyError("Cookie settings can only be changed from the computer running Fetchly.", 403, "local_only")


class CookieBrowserRequest(BaseModel):
    browser: str = Field(min_length=1, max_length=20)


class CookieFileRequest(BaseModel):
    text: str = Field(min_length=1, max_length=1_000_000)


@app.get("/api/cookies")
async def cookies_status(request: Request) -> dict[str, Any]:
    _require_local(request)
    return {"browser": COOKIES_BROWSER, "file": COOKIES_FILE.is_file()}


@app.post("/api/cookies/browser")
async def cookies_use_browser(payload: CookieBrowserRequest, request: Request) -> dict[str, Any]:
    global COOKIES_BROWSER
    _require_local(request)
    name = payload.browser.strip().lower()
    if name not in _COOKIE_BROWSERS:
        raise FriendlyError("Pick a browser from the list.", 400, "bad_browser")
    COOKIES_BROWSER = name
    configure_gallery_dl()
    return {"browser": COOKIES_BROWSER, "file": COOKIES_FILE.is_file()}


@app.post("/api/cookies/file")
async def cookies_upload_file(payload: CookieFileRequest, request: Request) -> dict[str, Any]:
    _require_local(request)
    text = payload.text.replace("\r\n", "\n")
    rows = [ln for ln in text.split("\n") if ln.strip() and (not ln.startswith("#") or ln.startswith("#HttpOnly_"))]
    if not rows or not all(len(ln.split("\t")) == 7 for ln in rows):
        raise FriendlyError(
            "That does not look like a cookies.txt file (Netscape format). Export it again from your browser.",
            400,
            "bad_cookies_file",
        )
    COOKIES_FILE.write_text(text.rstrip("\n") + "\n", encoding="utf-8")
    try:
        os.chmod(COOKIES_FILE, 0o600)
    except OSError:
        pass
    configure_gallery_dl()
    return {"browser": COOKIES_BROWSER, "file": True}


@app.delete("/api/cookies")
async def cookies_clear(request: Request) -> dict[str, Any]:
    global COOKIES_BROWSER
    _require_local(request)
    COOKIES_BROWSER = ""
    try:
        COOKIES_FILE.unlink()
    except FileNotFoundError:
        pass
    configure_gallery_dl()
    return {"browser": "", "file": False}


@app.post("/api/tools/update", dependencies=[Depends(rate_limit("tools-update", 3))])
async def tools_update() -> dict[str, Any]:
    return await asyncio.to_thread(_update_tools_sync)


# ---------------------------------------------------------------------------
# Routes: video
# ---------------------------------------------------------------------------

@app.post("/api/video/info", dependencies=[Depends(rate_limit("video-info", 12))])
async def video_info(payload: UrlRequest) -> dict[str, Any]:
    url = await ensure_public_url(payload.url)
    job = job_store.create(kind="video", url=url, state="fetching_info")

    try:
        info = await asyncio.wait_for(
            asyncio.to_thread(extract_video_info_sync, url),
            timeout=VIDEO_INFO_TIMEOUT_SECONDS,
        )
        video = {
            "title": info["title"],
            "duration": info["duration"],
            "webpage_url": info["webpage_url"],
            "uploader": info["uploader"],
            "extractor": info["extractor"],
            "has_thumbnail": bool(info["thumbnail_url"]),
            "formats": info["formats"],
        }
        job_store.update(
            job.id,
            state="ready",
            video=video,
            thumbnail_url=info["thumbnail_url"],
            expires_at=utc_now() + timedelta(seconds=DOWNLOAD_TTL_SECONDS),
        )
        return {
            "job_id": job.id,
            "state": "ready",
            "video": video,
        }
    except asyncio.TimeoutError:
        message = "That site took too long to return video details. Try again."
        job_store.fail(job.id, message)
        raise FriendlyError(message, 504, "timeout") from None
    except FriendlyError as exc:
        job_store.fail(job.id, exc.message)
        raise
    except Exception as exc:
        message = friendly_ytdlp_error(exc)
        job_store.fail(job.id, message)
        logger.info("Video info failed for %s: %s", url, message)
        raise FriendlyError(
            message, 422, "video_info_failed", detail=_strip_ansi(str(exc)).strip()[:1500]
        ) from None


@app.post("/api/video/download", dependencies=[Depends(rate_limit("video-download", 6))])
async def video_download(payload: VideoDownloadRequest) -> dict[str, Any]:
    job = get_job_or_404(payload.job_id)
    if job.kind != "video":
        raise HTTPException(status_code=400, detail="That job isn't a video download.")
    if job.state == "downloading":
        raise HTTPException(status_code=409, detail="This download is already in progress.")
    if job.state != "ready" or not job.video:
        raise HTTPException(status_code=409, detail="This video is no longer ready. Fetch it again.")

    valid_keys = {option["key"] for option in job.video.get("formats", [])}
    if payload.quality not in valid_keys:
        raise FriendlyError("Choose one of the available quality options.", 400, "invalid_quality")

    # Quality values are either "best", "audio" or a plain pixel height. The
    # selector builder validates the numeric range before anything reaches
    # yt-dlp, so this cannot inject a format string.
    _format_selector(payload.quality)

    job_store.update(
        job.id,
        state="downloading",
        error=None,
        expires_at=None,
        progress={
            "stage": "starting",
            "percent": 0.0,
            "downloaded_bytes": 0,
            "total_bytes": None,
            "speed": None,
            "eta": None,
        },
    )
    _spawn(asyncio.to_thread(download_video_sync, job.id, payload.quality))
    return {"job_id": job.id, "state": "downloading"}


@app.get("/api/video/{job_id}/thumbnail")
async def video_thumbnail(job_id: str) -> Response:
    job = get_job_or_404(job_id)
    if job.kind != "video" or not job.thumbnail_url:
        raise HTTPException(status_code=404, detail="No thumbnail is available for this video.")
    data, _, _ = await fetch_public_bytes(
        job.thumbnail_url,
        max_bytes=MAX_PREVIEW_BYTES,
        accept=IMAGE_ACCEPT,
        timeout=20.0,
    )
    _, mime = inspect_image_bytes(data)
    return Response(
        content=data,
        media_type=mime,
        headers={
            "Cache-Control": "private, max-age=900",
            "X-Content-Type-Options": "nosniff",
        },
    )


# ---------------------------------------------------------------------------
# Routes: images
# ---------------------------------------------------------------------------

@app.post("/api/images/extract", dependencies=[Depends(rate_limit("images-extract", 12))])
async def images_extract(payload: UrlRequest) -> dict[str, Any]:
    url = await ensure_public_url(payload.url)
    job = job_store.create(kind="images", url=url, state="fetching_info")

    try:
        result = await asyncio.wait_for(
            extract_images(url),
            timeout=IMAGE_EXTRACT_TIMEOUT_SECONDS,
        )
        images: list[dict[str, Any]] = []
        image_map: dict[str, dict[str, Any]] = {}
        seen_ids: set[str] = set()
        for image in result["images"][:MAX_IMAGE_COUNT]:
            raw_url = image["url"]
            image_id = hashlib.sha1(raw_url.encode("utf-8")).hexdigest()[:12]
            suffix = 1
            while image_id in seen_ids:
                image_id = hashlib.sha1(f"{raw_url}:{suffix}".encode("utf-8")).hexdigest()[:12]
                suffix += 1
            seen_ids.add(image_id)
            record = {
                "id": image_id,
                "url": raw_url,
                "filename": image.get("filename") or filename_from_url(raw_url),
                "alt": image.get("alt") or "",
                "width": image.get("width"),
                "height": image.get("height"),
            }
            images.append(record)
            image_map[image_id] = record

        job_store.update(
            job.id,
            state="ready",
            images=images,
            image_map=image_map,
            page_title=result.get("title") or urlsplit(url).hostname or "Images",
            expires_at=utc_now() + timedelta(seconds=DOWNLOAD_TTL_SECONDS),
        )
        return {
            "job_id": job.id,
            "state": "ready",
            "page_title": job.page_title,
            "extractor": result.get("extractor"),
            "total_found": result.get("total_found", len(result["images"])),
            "truncated": result.get("total_found", len(result["images"])) > MAX_IMAGE_COUNT,
            "images": [_public_image(job.id, image, index) for index, image in enumerate(images)],
        }
    except asyncio.TimeoutError:
        message = "That page took too long to scan. Try a smaller gallery or a direct link."
        job_store.fail(job.id, message)
        raise FriendlyError(message, 504, "timeout") from None
    except FriendlyError as exc:
        job_store.fail(job.id, exc.message)
        raise
    except Exception:
        logger.exception("Image extraction failed for %s", url)
        message = "We couldn't extract images from that page. Please try another link."
        job_store.fail(job.id, message)
        raise FriendlyError(message, 422, "image_extract_failed") from None


@app.get("/api/images/{job_id}/{image_id}/preview")
async def image_preview(job_id: str, image_id: str) -> Response:
    job = get_job_or_404(job_id)
    if job.kind != "images":
        raise HTTPException(status_code=404, detail="Image not found.")
    if not re.fullmatch(r"[a-f0-9]{12}", image_id):
        raise HTTPException(status_code=404, detail="Image not found.")
    image = job.image_map.get(image_id)
    if not image:
        raise HTTPException(status_code=404, detail="Image not found.")

    data, _, _ = await fetch_public_bytes(
        image["url"],
        max_bytes=MAX_PREVIEW_BYTES,
        referer=job.url,
        accept=IMAGE_ACCEPT,
        timeout=25.0,
    )
    _, mime = inspect_image_bytes(data)
    return Response(
        content=data,
        media_type=mime,
        headers={
            "Cache-Control": "private, max-age=900",
            "X-Content-Type-Options": "nosniff",
        },
    )


def _build_zip_sync(results: list[tuple[int, Path, str]], zip_path: Path) -> None:
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
        used_names: set[str] = set()
        for _, path, filename in sorted(results, key=lambda item: item[0]):
            name = filename
            stem = Path(name).stem
            suffix = Path(name).suffix
            counter = 2
            while name.lower() in used_names:
                name = f"{stem}-{counter}{suffix}"
                counter += 1
            used_names.add(name.lower())
            archive.write(path, arcname=name)


@app.post("/api/images/zip", dependencies=[Depends(rate_limit("images-zip", 5))])
async def images_zip(payload: ImageZipRequest) -> dict[str, Any]:
    source_job = get_job_or_404(payload.job_id)
    if source_job.kind != "images":
        raise HTTPException(status_code=400, detail="That job isn't an image selection.")
    if source_job.state != "ready":
        raise HTTPException(status_code=409, detail="This image selection has expired. Scan the page again.")

    requested_ids = list(dict.fromkeys(payload.image_ids))
    if len(requested_ids) > MAX_IMAGE_COUNT:
        raise FriendlyError(f"Select up to {MAX_IMAGE_COUNT} images at a time.", 400, "too_many_images")

    selected: list[dict[str, Any]] = []
    for image_id in requested_ids:
        image = source_job.image_map.get(image_id)
        if not image:
            raise FriendlyError("One or more selected images are no longer available. Scan the page again.", 409)
        selected.append(image)

    zip_job = job_store.create(kind="image_zip", url=source_job.url, state="processing")
    job_store.update(
        zip_job.id,
        page_title=source_job.page_title,
        zip_source_job=source_job.id,
        progress={
            "stage": "downloading_images",
            "percent": 0.0,
            "downloaded_bytes": 0,
            "total_bytes": None,
            "speed": None,
            "eta": None,
        },
    )

    temp_dir = DOWNLOADS_DIR / f".{zip_job.id}.parts"
    zip_path = DOWNLOADS_DIR / f"{zip_job.id}.zip"
    temp_dir.mkdir(parents=True, exist_ok=False)

    semaphore = asyncio.Semaphore(6)
    total_lock = asyncio.Lock()
    total_bytes = 0

    async def download_one(index: int, image: dict[str, Any]) -> tuple[int, Path, str] | None:
        nonlocal total_bytes
        async with semaphore:
            try:
                data, _, _ = await fetch_public_bytes(
                    image["url"],
                    max_bytes=MAX_IMAGE_DOWNLOAD_BYTES,
                    referer=source_job.url,
                    accept=IMAGE_ACCEPT,
                    timeout=35.0,
                )
                extension, _ = inspect_image_bytes(data)
                async with total_lock:
                    if total_bytes + len(data) > MAX_ZIP_TOTAL_BYTES:
                        logger.info("Skipping %s: ZIP size cap reached", image.get("url"))
                        return None
                    total_bytes += len(data)
                source_name = image.get("filename") or "image"
                stem = sanitize_filename(source_name, fallback=f"image-{index + 1}", max_length=80)
                if stem.lower().endswith(extension.lower()):
                    stem = stem[: -len(extension)]
                destination = temp_dir / f"{uuid.uuid4().hex}{extension}"
                await asyncio.to_thread(destination.write_bytes, data)
                final_name = sanitize_filename(
                    stem,
                    fallback=f"image-{index + 1}",
                    max_length=90,
                    extension=extension,
                )
                return index, destination, final_name
            except Exception as exc:
                logger.info("Could not include image %s: %s", image.get("url"), exc)
                return None

    try:
        results = await asyncio.gather(*[
            download_one(index, image) for index, image in enumerate(selected)
        ])
        successful = [item for item in results if item is not None]
        if not successful:
            raise MediaFetchError("None of the selected images could be downloaded.", 502)

        job_store.set_progress(
            zip_job.id,
            stage="packaging",
            percent=98.0,
            downloaded_bytes=total_bytes,
            total_bytes=total_bytes,
        )
        await asyncio.to_thread(_build_zip_sync, successful, zip_path)
        title = sanitize_filename(
            Path(source_job.page_title or "images").stem,
            fallback="images",
            max_length=90,
            extension=".zip",
        )
        file_size = zip_path.stat().st_size
        job_store.complete(zip_job.id, file_path=zip_path, download_name=title)
        return {
            "job_id": zip_job.id,
            "state": "complete",
            "download_url": f"/api/download/{zip_job.id}",
            "file_name": title,
            "size_bytes": file_size,
            "count": len(successful),
            "skipped": len(selected) - len(successful),
        }
    except asyncio.CancelledError:
        job_store.fail(zip_job.id, "The ZIP request was canceled.")
        raise
    except FriendlyError as exc:
        job_store.fail(zip_job.id, exc.message)
        raise
    except Exception:
        logger.exception("ZIP creation failed for job %s", zip_job.id)
        message = "We couldn't build the ZIP. Please try again with fewer images."
        job_store.fail(zip_job.id, message)
        raise FriendlyError(message, 500, "zip_failed") from None
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)
        if zip_job.state != "complete":
            with contextlib.suppress(OSError):
                zip_path.unlink(missing_ok=True)


# ---------------------------------------------------------------------------
# Routes: jobs and downloads
# ---------------------------------------------------------------------------

@app.get("/api/jobs/{job_id}")
async def job_status(job_id: str) -> dict[str, Any]:
    get_job_or_404(job_id)
    status = job_store.status(job_id)
    if not status:
        raise HTTPException(status_code=404, detail="That download link has expired.")
    return status


@app.get("/api/jobs/{job_id}/events")
async def job_events(job_id: str, request: Request) -> StreamingResponse:
    get_job_or_404(job_id)

    async def event_stream():
        last_payload: str | None = None
        last_sent = time.monotonic()
        while True:
            if await request.is_disconnected():
                break
            status = job_store.status(job_id)
            if status is None:
                payload = json.dumps({
                    "job_id": job_id,
                    "state": "error",
                    "error": "This job has expired.",
                    "progress": {},
                })
                yield f"data: {payload}\n\n"
                break
            payload = json.dumps(status, separators=(",", ":"))
            if payload != last_payload:
                yield f"data: {payload}\n\n"
                last_payload = payload
                last_sent = time.monotonic()
            if status["state"] in TERMINAL_STATES:
                break
            if time.monotonic() - last_sent > 12:
                yield ": keep-alive\n\n"
                last_sent = time.monotonic()
            await asyncio.sleep(0.35)

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@app.get("/api/download/{job_id}")
async def download_file(job_id: str) -> FileResponse:
    job = get_job_or_404(job_id)
    if job.state != "complete" or not job.file_path:
        raise HTTPException(status_code=409, detail="This file isn't ready yet.")
    path = job.file_path.resolve()
    try:
        path.relative_to(DOWNLOADS_DIR.resolve())
    except ValueError:
        raise HTTPException(status_code=404, detail="File not found.") from None
    if not path.is_file():
        raise HTTPException(status_code=404, detail="This file has expired or was removed.")
    media_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    return FileResponse(
        path,
        media_type=media_type,
        filename=job.download_name or path.name,
        headers={"Cache-Control": "private, no-store"},
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host=HOST, port=PORT, reload=False)