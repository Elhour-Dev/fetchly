"""URL checks: only public http(s) links (a basic SSRF guard)."""

from __future__ import annotations

import asyncio
import ipaddress
import re
import socket
from functools import lru_cache
from urllib.parse import urlsplit, urlunsplit

from .errors import FriendlyError

_BLOCKED_HOST_SUFFIXES = (
    ".localhost", ".local", ".internal", ".lan", ".home", ".test", ".invalid",
)
_BLOCKED_HOSTS = {"localhost", "localhost.localdomain", "ip6-localhost"}


def _invalid(message: str = "That doesn't look like a valid URL.", code: str = "invalid_url") -> FriendlyError:
    return FriendlyError(message, 400, code)


def normalize_url(raw: str) -> str:
    """Validate and canonicalize an HTTP(S) URL.

    This rejects non-web schemes, credentials, odd ports and whitespace.
    Host/IP reachability is checked separately in a thread.
    """
    if not isinstance(raw, str):
        raise _invalid("Please paste a valid URL.")
    value = raw.strip()
    if not value:
        raise _invalid("Paste a link to get started.")
    if len(value) > 4096:
        raise _invalid("That URL is too long.")
    if any(ord(char) < 32 for char in value) or " " in value or "\\" in value:
        raise _invalid()

    try:
        parts = urlsplit(value)
        scheme = parts.scheme.lower()
    except ValueError:
        raise _invalid() from None

    if scheme not in {"http", "https"}:
        raise _invalid("Only http:// and https:// links are supported.")
    if not parts.netloc:
        raise _invalid("That URL is missing a domain name.")
    if parts.username or parts.password:
        raise _invalid("Links with embedded usernames or passwords aren't supported.")

    try:
        host = parts.hostname
        port = parts.port
    except ValueError:
        raise _invalid("That URL has an invalid port.") from None

    if not host:
        raise _invalid("That URL is missing a domain name.")
    host = host.lower().rstrip(".")
    if host in _BLOCKED_HOSTS or host.endswith(_BLOCKED_HOST_SUFFIXES):
        raise _invalid("Local and private network addresses aren't allowed.", "blocked_url")
    if port not in (None, 80, 443):
        raise _invalid("Only standard web ports (80 and 443) are supported.")

    host_repr = f"[{host}]" if ":" in host else host
    netloc = f"{host_repr}:{port}" if port else host_repr
    path = parts.path or "/"
    return urlunsplit((scheme, netloc, path, parts.query, ""))


# Sites Fetchy cannot download from. The app says so at once instead of trying.
UNSUPPORTED_INSTAGRAM_STORY = "Not supported: Instagram stories can't be downloaded with Fetchy."
UNSUPPORTED_SNAPCHAT = "Not supported: Snapchat links can't be downloaded with Fetchy."


def unsupported_reason(url: str) -> str | None:
    """The "Not supported" message for an Instagram story or a Snapchat link, else None."""
    try:
        parts = urlsplit(url.strip())
        host = (parts.hostname or "").lower().rstrip(".")
    except ValueError:
        return None
    if host == "snapchat.com" or host.endswith(".snapchat.com"):
        return UNSUPPORTED_SNAPCHAT
    if host == "instagram.com" or host.endswith(".instagram.com"):
        # /stories/<user>/... and story highlights (/stories/highlights/..., /s/...)
        if re.match(r"^/(stories|s)/", parts.path or "", re.I):
            return UNSUPPORTED_INSTAGRAM_STORY
    return None


def unsupported_link(url: str) -> None:
    reason = unsupported_reason(url)
    if reason:
        raise FriendlyError(reason, 422, "unsupported_link")


@lru_cache(maxsize=1024)
def host_is_public(host: str) -> bool:
    """Resolve a host and require every address to be globally routable.

    A cached DNS answer is fine for this local tool; the HTTP client still
    resolves on its own, which is why this is only a basic SSRF guard.
    """
    try:
        addresses = [ipaddress.ip_address(host)]
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
            raise FriendlyError("Local, private, and reserved network addresses aren't allowed.", 400, "blocked_url")
    return True


async def ensure_public_url(url: str) -> str:
    normalized = normalize_url(url)
    host = urlsplit(normalized).hostname
    if not host:
        raise FriendlyError("That URL is missing a domain name.", 400, "invalid_url")
    try:
        await asyncio.to_thread(host_is_public, host)
    except FriendlyError:
        raise
    except Exception:
        raise FriendlyError("We couldn't verify that domain.", 400, "dns_failed") from None
    return normalized
