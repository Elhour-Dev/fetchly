"""Routes: the page, favicon, health and the "Update tools" button."""

from __future__ import annotations

import asyncio
import shutil
from typing import Any

from fastapi import APIRouter, Depends
from fastapi.responses import FileResponse, HTMLResponse

from .. import config, engine
from ..config import APP_VERSION
from ..ratelimit import rate_limit
from ..updater import update_tools_sync

router = APIRouter()


@router.get("/", response_class=HTMLResponse, include_in_schema=False)
async def index() -> HTMLResponse:
    return HTMLResponse((config.TEMPLATES_DIR / "index.html").read_text(encoding="utf-8"))


@router.get("/favicon.ico", include_in_schema=False)
async def favicon() -> FileResponse:
    return FileResponse(config.STATIC_DIR / "favicon.svg", media_type="image/svg+xml")


@router.get("/api/health")
async def health() -> dict[str, Any]:
    ffmpeg = shutil.which("ffmpeg")
    return {
        "status": "ok",
        "version": APP_VERSION,
        "playlists": bool(config.SAVE_DIR),
        "ffmpeg": {"available": bool(ffmpeg), "path": ffmpeg},
        "yt_dlp": {"version": engine.tool_version()},
        "limits": {"download_ttl_seconds": config.DOWNLOAD_TTL_SECONDS},
    }


@router.post("/api/tools/update", dependencies=[Depends(rate_limit("tools-update", 3))])
async def tools_update() -> dict[str, Any]:
    return await asyncio.to_thread(update_tools_sync)
