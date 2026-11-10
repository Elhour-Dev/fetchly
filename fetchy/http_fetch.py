"""Fetch small public files (thumbnails) with checked redirects and a size cap."""

from __future__ import annotations

from io import BytesIO
from urllib.parse import urljoin

import httpx
from PIL import Image, UnidentifiedImageError

from .config import IMAGE_ACCEPT, MAX_REDIRECTS, USER_AGENT, logger
from .errors import FriendlyError, MediaFetchError
from .urls import ensure_public_url, normalize_url
from .utils import coerce_int

IMAGE_FORMAT_MAP: dict[str, tuple[str, str]] = {
    "JPEG": ("image/jpeg", ".jpg"),
    "MPO": ("image/jpeg", ".jpg"),
    "PNG": ("image/png", ".png"),
    "WEBP": ("image/webp", ".webp"),
    "GIF": ("image/gif", ".gif"),
    "AVIF": ("image/avif", ".avif"),
    "BMP": ("image/bmp", ".bmp"),
}

_client: httpx.AsyncClient | None = None


def open_client() -> None:
    global _client
    _client = httpx.AsyncClient(
        follow_redirects=False,
        timeout=httpx.Timeout(30.0, connect=10.0),
        limits=httpx.Limits(max_connections=40, max_keepalive_connections=20),
        headers={"User-Agent": USER_AGENT},
    )


async def close_client() -> None:
    global _client
    if _client is not None:
        await _client.aclose()
        _client = None


def get_client() -> httpx.AsyncClient:
    if _client is None:
        raise FriendlyError("The server is still starting. Try again in a second.", 503)
    return _client


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


def _too_big(max_bytes: int) -> MediaFetchError:
    return MediaFetchError(f"That file is larger than the {max_bytes // (1024 * 1024)} MB safety limit.", 413)


async def fetch_public_bytes(
    url: str,
    *,
    max_bytes: int,
    referer: str | None = None,
    accept: str = IMAGE_ACCEPT,
    timeout: float = 25.0,
) -> tuple[bytes, str, str]:
    """Fetch a public URL with manual redirects and a hard size limit."""
    client = get_client()
    current = normalize_url(url)
    headers = {"User-Agent": USER_AGENT, "Accept": accept, "Accept-Language": "en-US,en;q=0.8"}
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
                    raise MediaFetchError(_http_error_message(response.status_code), 502)

                length = coerce_int(response.headers.get("content-length"))
                if length is not None and length > max_bytes:
                    raise _too_big(max_bytes)

                body = bytearray()
                async for chunk in response.aiter_bytes(chunk_size=64 * 1024):
                    if len(body) + len(chunk) > max_bytes:
                        raise _too_big(max_bytes)
                    body.extend(chunk)
                if not body:
                    raise MediaFetchError("The remote server returned an empty response.", 502)
                return bytes(body), current, response.headers.get("content-type", "").lower()
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
