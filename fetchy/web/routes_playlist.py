"""Routes: playlist list, queue control and "Open folder" (desktop app only)."""

from __future__ import annotations

import asyncio
import os
from typing import Any

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

from .. import config
from ..config import MIX_CAP
from ..errors import FriendlyError, friendly_ytdlp_error, strip_ansi
from ..formats import format_selector
from ..playlist import PlaylistRun, extract_playlist_entries_sync, open_run, playlists
from ..urls import ensure_public_url
from .routes_video import UrlRequest
from .security import is_local_request

router = APIRouter()

_PUBLIC_ENTRY_FIELDS = ("n", "title", "duration", "thumb", "unavailable", "have")


class PlaylistStartRequest(BaseModel):
    items: list[int] = Field(min_length=1, max_length=10000)
    quality: str = Field(min_length=1, max_length=16)


class PlaylistActionRequest(BaseModel):
    action: str = Field(pattern="^(pause|resume|cancel|retry)$")
    n: int | None = None


def _require_desktop(request: Request) -> None:
    if not config.SAVE_DIR or not is_local_request(request):
        raise FriendlyError("Playlist lists only work in the Fetchy desktop app.", 403, "desktop_only")


def _run_or_404(request: Request, playlist_id: str) -> PlaylistRun:
    _require_desktop(request)
    run = playlists.get(playlist_id)
    if not run:
        raise HTTPException(status_code=404, detail="This playlist is no longer open. Fetch it again.")
    return run


@router.post("/api/playlist/info")
async def playlist_info(payload: UrlRequest, request: Request) -> dict[str, Any]:
    _require_desktop(request)
    url = await ensure_public_url(payload.url)
    try:
        info = await asyncio.wait_for(asyncio.to_thread(extract_playlist_entries_sync, url), timeout=240)
    except asyncio.TimeoutError:
        raise FriendlyError("The playlist took too long to load. Try again.", 504, "timeout") from None
    except FriendlyError:
        raise
    except Exception as exc:
        message = (friendly_ytdlp_error(exc) if "playlist" not in str(exc).lower()
                   else "Could not read this playlist. It may be private or empty.")
        raise FriendlyError(message, 422, "playlist_failed", detail=strip_ansi(str(exc)).strip()[:1500]) from None
    run = open_run(info["title"], info["entries"])
    return {
        "id": run.id, "title": run.title, "uploader": info["uploader"], "capped": info["capped"],
        "cap": MIX_CAP, "entries": [{k: e[k] for k in _PUBLIC_ENTRY_FIELDS} for e in info["entries"].values()],
    }


@router.post("/api/playlist/{playlist_id}/start")
async def playlist_start(playlist_id: str, payload: PlaylistStartRequest, request: Request) -> dict[str, Any]:
    run = _run_or_404(request, playlist_id)
    if run.active():
        raise HTTPException(status_code=409, detail="This playlist is already downloading.")
    format_selector(payload.quality)
    await asyncio.to_thread(run.start, payload.items, payload.quality)
    return run.status()


@router.get("/api/playlist/{playlist_id}/status")
async def playlist_status(playlist_id: str, request: Request) -> dict[str, Any]:
    return _run_or_404(request, playlist_id).status()


@router.post("/api/playlist/{playlist_id}/action")
async def playlist_action(playlist_id: str, payload: PlaylistActionRequest, request: Request) -> dict[str, Any]:
    run = _run_or_404(request, playlist_id)
    await asyncio.to_thread(run.action, payload.action, payload.n)
    return run.status()


@router.post("/api/playlist/{playlist_id}/open")
async def playlist_open(playlist_id: str, request: Request) -> dict[str, Any]:
    run = _run_or_404(request, playlist_id)
    if run.folder and run.folder.is_dir() and hasattr(os, "startfile"):
        os.startfile(str(run.folder))  # noqa: S606 - our own folder inside Downloads
    return {"ok": True}
