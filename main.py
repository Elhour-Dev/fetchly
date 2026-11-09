"""Fetchy - a small, self-hosted video downloader.

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
import zipfile
import uuid
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from functools import lru_cache
from io import BytesIO
from pathlib import Path
from typing import Any
from urllib.parse import urljoin, urlsplit, urlunsplit

import httpx
# Desktop app: use the newest downloaded tools (yt-dlp) if present.
TOOLS_DIR = Path(os.environ["FETCHLY_TOOLS_DIR"]) if os.getenv("FETCHLY_TOOLS_DIR") else None
if TOOLS_DIR and (TOOLS_DIR / "current").is_dir():
    sys.path.insert(0, str(TOOLS_DIR / "current"))
import yt_dlp
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

APP_NAME = "Fetchy"
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
# Nothing is copied or saved by Fetchy; the tools read the browser when needed.
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
    "then start Fetchy with FETCHLY_COOKIES_BROWSER=chrome (or firefox, edge) and try again."
)


def _strip_ansi(value: str) -> str:
    return re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", value)


def friendly_ytdlp_error(exc: Exception) -> str:
    """Translate the most common yt-dlp failures into actionable copy."""
    raw = _strip_ansi(str(exc)).replace("\r", " ").strip()
    raw = re.sub(r"^ERROR:\s*", "", raw, flags=re.I)
    low = raw.lower()

    known = (
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


# ---------------------------------------------------------------------------
# Update tools (yt-dlp)
# ---------------------------------------------------------------------------

_update_lock = threading.Lock()


def _tool_version() -> str | None:
    return getattr(getattr(yt_dlp, "version", None), "__version__", None)


def _reload_tools() -> None:
    """Load the freshly installed yt-dlp without a restart."""
    global yt_dlp
    for name in list(sys.modules):
        if name.split(".")[0] == "yt_dlp":
            del sys.modules[name]
    importlib.invalidate_caches()
    import yt_dlp as new_yt_dlp
    yt_dlp = new_yt_dlp


def _update_message(before: str | None, after: str | None, backup: bool = False) -> str:
    if before == after:
        return f"Already up to date (yt-dlp {after})"
    return f"Updated yt-dlp to version {after}." + (" Old version kept as backup." if backup else "")


def _update_tools_desktop(before: str | None) -> dict[str, Any]:
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
        raise FriendlyError("The update took too long. Check your internet and try again.", 504, "update_timeout") from None
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
        _reload_tools()
        after = _tool_version()
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
            _reload_tools()
        raise FriendlyError("The new version did not work, so the old one is back.", 500, "update_rolled_back") from None
    return {"message": _update_message(before, after, True), "yt_dlp": after}


def _update_tools_sync() -> dict[str, Any]:
    if not _update_lock.acquire(blocking=False):
        raise FriendlyError("An update is already running. Please wait.", 409, "update_busy")
    try:
        before = _tool_version()
        if TOOLS_DIR:
            return _update_tools_desktop(before)
        try:
            proc = subprocess.run(
                [sys.executable, "-m", "pip", "install", "-U", "--disable-pip-version-check", "yt-dlp"],
                capture_output=True, text=True, timeout=300,
            )
        except subprocess.TimeoutExpired:
            raise FriendlyError("The update took too long. Check your internet and try again.", 504, "update_timeout") from None
        if proc.returncode != 0:
            logger.warning("Tool update failed: %s", proc.stderr[-500:])
            raise FriendlyError(
                "Couldn't update here (no internet or no permission). Try running: pip install -U yt-dlp",
                500,
                "update_failed",
            )
        _reload_tools()
        after = _tool_version()
        return {"message": _update_message(before, after), "yt_dlp": after}
    finally:
        _update_lock.release()


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


def extract_playlist_info_sync(url: str) -> dict[str, Any]:
    """Quick look at a playlist: title and number of videos (no per-video details)."""
    options = _base_ydl_options()
    options.update({"noplaylist": False, "extract_flat": "in_playlist", "skip_download": True})
    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=False)
    entries = [e for e in (info.get("entries") or []) if e] if isinstance(info, dict) else []
    if not entries:
        raise FriendlyError("No videos were found in this playlist.", 422, "no_videos")
    count = len(entries)
    return {
        "title": (str(info.get("title") or "Playlist").strip()[:260] + f" ({count} videos)"),
        "duration": None,
        "webpage_url": str(info.get("webpage_url") or url),
        "uploader": str(info.get("uploader") or info.get("channel") or "")[:150],
        "extractor": str(info.get("extractor_key") or "")[:80],
        "thumbnail_url": None,
        "is_playlist": True,
        "formats": [
            {"key": "best", "label": "Best quality", "detail": f"All {count} videos, saved as a ZIP",
             "kind": "video", "size_bytes": None, "recommended": True},
            {"key": "audio", "label": "Audio only", "detail": f"All {count} videos as MP3, saved as a ZIP",
             "kind": "audio", "size_bytes": None, "recommended": False},
        ],
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

    is_playlist = bool(job.video.get("is_playlist"))
    pl_dir = DOWNLOADS_DIR / f"{job_id}_pl"
    options = _base_ydl_options()
    options.update({
        "format": selector,
        "outtmpl": str(pl_dir / "%(playlist_index)03d - %(title).80s.%(ext)s") if is_playlist
        else str(DOWNLOADS_DIR / f"{job_id}.%(ext)s"),
        "noplaylist": not is_playlist,
        "ignoreerrors": is_playlist,
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

        if is_playlist:
            files = sorted(f for f in pl_dir.glob("*") if f.is_file() and not f.name.endswith((".part", ".ytdl", ".temp")))
            if not files:
                raise FriendlyError("No videos could be downloaded from this playlist.", 422, "no_videos")
            job_store.set_progress(job_id, stage="processing", percent=100.0, speed=None, eta=None)
            zip_path = DOWNLOADS_DIR / f"{job_id}.zip"
            with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_STORED) as zf:
                for f in files:
                    zf.write(f, f.name)
            shutil.rmtree(pl_dir, ignore_errors=True)
            name = sanitize_filename(job.video.get("title") or "playlist", fallback="playlist", max_length=95, extension=".zip")
            job_store.complete(job_id, file_path=zip_path, download_name=name)
            logger.info("Playlist job %s complete (%s files)", job_id, len(files))
            return

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
        shutil.rmtree(pl_dir, ignore_errors=True)
        _cleanup_job_files(job_id)
        job_store.fail(job_id, exc.message)
        logger.warning("Video job %s failed: %s", job_id, exc.message)
    except Exception as exc:
        message = friendly_ytdlp_error(exc)
        shutil.rmtree(pl_dir, ignore_errors=True)
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
        logger.info("Fetchy %s starting; FFmpeg found at %s", APP_VERSION, ffmpeg)
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
    description="A local video downloader built with yt-dlp.",
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
            headers={"WWW-Authenticate": 'Basic realm="Fetchy", charset="UTF-8"'},
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
    playlist: bool = False


class VideoDownloadRequest(BaseModel):
    job_id: str = Field(min_length=1, max_length=64)
    quality: str = Field(min_length=1, max_length=16)


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
    return {
        "status": "ok",
        "version": APP_VERSION,
        "ffmpeg": {
            "available": bool(ffmpeg),
            "path": ffmpeg,
        },
        "yt_dlp": {
            "version": getattr(getattr(yt_dlp, "version", None), "__version__", None),
        },
        "limits": {
            "download_ttl_seconds": DOWNLOAD_TTL_SECONDS,
        },
    }


# ---------------------------------------------------------------------------
# Routes: login cookies (local use only; cookies are never logged or committed)
# ---------------------------------------------------------------------------

_COOKIE_BROWSERS = {"firefox", "chrome", "edge", "brave", "opera", "chromium", "vivaldi", "safari", "whale"}


def _require_local(request: Request) -> None:
    host = request.client.host if request.client else ""
    if host not in {"127.0.0.1", "::1", "localhost"}:
        raise FriendlyError("Cookie settings can only be changed from the computer running Fetchy.", 403, "local_only")


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
    if Path(urlsplit(url).path).suffix.lower() in IMAGE_EXTENSIONS:
        raise FriendlyError("Fetchy downloads videos only.", 422, "videos_only")
    job = job_store.create(kind="video", url=url, state="fetching_info")

    try:
        info = await asyncio.wait_for(
            asyncio.to_thread(extract_playlist_info_sync if payload.playlist else extract_video_info_sync, url),
            timeout=VIDEO_INFO_TIMEOUT_SECONDS,
        )
        video = {
            "title": info["title"],
            "duration": info["duration"],
            "webpage_url": info["webpage_url"],
            "uploader": info["uploader"],
            "extractor": info["extractor"],
            "has_thumbnail": bool(info["thumbnail_url"]),
            "is_playlist": bool(info.get("is_playlist")),
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