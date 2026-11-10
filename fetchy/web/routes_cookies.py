"""Routes: login cookies (local use only; cookie values are never logged)."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Request
from pydantic import BaseModel, Field

from .. import cookies
from ..errors import FriendlyError
from .security import is_local_request

router = APIRouter()


class CookieBrowserRequest(BaseModel):
    browser: str = Field(min_length=1, max_length=20)


class CookieFileRequest(BaseModel):
    text: str = Field(min_length=1, max_length=1_000_000)


def _require_local(request: Request) -> None:
    if not is_local_request(request):
        raise FriendlyError("Cookie settings can only be changed from the computer running Fetchy.", 403, "local_only")


@router.get("/api/cookies")
async def cookies_status(request: Request) -> dict[str, Any]:
    _require_local(request)
    return cookies.status()


@router.post("/api/cookies/browser")
async def cookies_use_browser(payload: CookieBrowserRequest, request: Request) -> dict[str, Any]:
    _require_local(request)
    cookies.set_browser(payload.browser)
    return cookies.status()


@router.post("/api/cookies/file")
async def cookies_upload_file(payload: CookieFileRequest, request: Request) -> dict[str, Any]:
    _require_local(request)
    cookies.save_file(payload.text)
    return {"browser": cookies.browser(), "file": True}


@router.delete("/api/cookies")
async def cookies_clear(request: Request) -> dict[str, Any]:
    _require_local(request)
    cookies.clear()
    return {"browser": "", "file": False}
