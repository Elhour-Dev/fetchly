"""Routes: single video info, download, progress events and the finished file."""

from __future__ import annotations

import asyncio
import json
import mimetypes
import time
import uuid
from datetime import timedelta
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse, Response, StreamingResponse
from pydantic import BaseModel, Field

from .. import config
from ..config import AUDIO_KEYS, IMAGE_ACCEPT, IMAGE_EXTENSIONS, logger
from ..errors import FriendlyError, friendly_ytdlp_error, strip_ansi
from ..formats import format_selector
from ..http_fetch import fetch_public_bytes, inspect_image_bytes
from ..jobs import TERMINAL_STATES, Job, job_store
from ..playlist_zip import extract_playlist_info_sync
from ..ratelimit import rate_limit
from ..urls import ensure_public_url
from ..utils import utc_now
from ..video import download_video_sync, extract_video_info_sync
from .tasks import spawn

router = APIRouter()


class UrlRequest(BaseModel):
    url: str = Field(min_length=1, max_length=4096)
    playlist: bool = False   # web version: whole playlist as one ZIP


class VideoDownloadRequest(BaseModel):
    job_id: str = Field(min_length=1, max_length=64)
    quality: str = Field(min_length=1, max_length=16)
    subs: str | None = Field(default=None, max_length=32)   # subtitle language to embed (video only)


def get_job_or_404(job_id: str) -> Job:
    try:
        uuid.UUID(job_id)
    except (ValueError, AttributeError, TypeError):
        raise HTTPException(status_code=404, detail="That download link is invalid or has expired.") from None
    job = job_store.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="That download link has expired or no longer exists.")
    return job


@router.post("/api/video/info", dependencies=[Depends(rate_limit("video-info", 12))])
async def video_info(payload: UrlRequest) -> dict[str, Any]:
    url = await ensure_public_url(payload.url)
    if Path(urlsplit(url).path).suffix.lower() in IMAGE_EXTENSIONS:
        raise FriendlyError("Fetchy downloads videos only.", 422, "videos_only")
    job = job_store.create(kind="video", url=url, state="fetching_info")

    try:
        info = await asyncio.wait_for(
            asyncio.to_thread(extract_playlist_info_sync if payload.playlist else extract_video_info_sync, url),
            timeout=config.VIDEO_INFO_TIMEOUT_SECONDS,
        )
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
        raise FriendlyError(message, 422, "video_info_failed", detail=strip_ansi(str(exc)).strip()[:1500]) from None

    video = {
        "title": info["title"],
        "duration": info["duration"],
        "webpage_url": info["webpage_url"],
        "uploader": info["uploader"],
        "extractor": info["extractor"],
        "has_thumbnail": bool(info["thumbnail_url"]),
        "is_playlist": bool(info.get("is_playlist")),
        "formats": info["formats"],
        "subtitles": info.get("subtitles", []),
    }
    job_store.update(
        job.id,
        state="ready",
        video=video,
        thumbnail_url=info["thumbnail_url"],
        expires_at=utc_now() + timedelta(seconds=config.DOWNLOAD_TTL_SECONDS),
    )
    return {"job_id": job.id, "state": "ready", "video": video}


@router.post("/api/video/download", dependencies=[Depends(rate_limit("video-download", 6))])
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
    # Only known keys or a plain pixel height pass, so no format string can be injected.
    format_selector(payload.quality)

    subs = (payload.subs or "").strip() or None
    sub_options = {item["code"]: item for item in job.video.get("subtitles", [])}
    if subs and (subs not in sub_options or payload.quality in AUDIO_KEYS):
        subs = None   # unknown language, or audio-only: nothing to embed
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
    subs_auto = bool(subs and sub_options[subs]["auto"])
    spawn(asyncio.to_thread(download_video_sync, job.id, payload.quality, subs, subs_auto))
    return {"job_id": job.id, "state": "downloading"}


@router.get("/api/video/{job_id}/thumbnail")
async def video_thumbnail(job_id: str) -> Response:
    job = get_job_or_404(job_id)
    if job.kind != "video" or not job.thumbnail_url:
        raise HTTPException(status_code=404, detail="No thumbnail is available for this video.")
    data, _, _ = await fetch_public_bytes(
        job.thumbnail_url, max_bytes=config.MAX_PREVIEW_BYTES, accept=IMAGE_ACCEPT, timeout=20.0,
    )
    _, mime = inspect_image_bytes(data)
    return Response(
        content=data,
        media_type=mime,
        headers={"Cache-Control": "private, max-age=900", "X-Content-Type-Options": "nosniff"},
    )


@router.get("/api/jobs/{job_id}")
async def job_status(job_id: str) -> dict[str, Any]:
    get_job_or_404(job_id)
    status = job_store.status(job_id)
    if not status:
        raise HTTPException(status_code=404, detail="That download link has expired.")
    return status


@router.get("/api/jobs/{job_id}/events")
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
                payload = json.dumps({"job_id": job_id, "state": "error", "error": "This job has expired.", "progress": {}})
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
        headers={"Cache-Control": "no-cache, no-transform", "Connection": "keep-alive", "X-Accel-Buffering": "no"},
    )


@router.get("/api/download/{job_id}")
async def download_file(job_id: str) -> FileResponse:
    job = get_job_or_404(job_id)
    if job.state != "complete" or not job.file_path:
        raise HTTPException(status_code=409, detail="This file isn't ready yet.")
    path = job.file_path.resolve()
    try:
        path.relative_to(config.DOWNLOADS_DIR.resolve())
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
