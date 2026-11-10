"""Basic Auth for public deploys, and the "only from this computer" check."""

from __future__ import annotations

import base64
import secrets

from fastapi import Request

from ..config import APP_PASSWORD, APP_USERNAME

_LOCAL_HOSTS = {"127.0.0.1", "::1", "localhost"}


def basic_auth_ok(request: Request) -> bool:
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


def is_local_request(request: Request) -> bool:
    """True only for a direct request from this computer.

    Behind a reverse proxy (the Docker deploy trusts X-Forwarded-For) the client
    address can be faked, so any proxied request counts as remote.
    """
    if "x-forwarded-for" in request.headers or "forwarded" in request.headers:
        return False
    host = request.client.host if request.client else ""
    return host in _LOCAL_HOSTS
