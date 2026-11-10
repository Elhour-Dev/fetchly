"""Builds the FastAPI app: startup/shutdown, auth, headers, error format, routes."""

from __future__ import annotations

import asyncio
import contextlib
import shutil
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from fastapi.staticfiles import StaticFiles

from .. import config, cookies, http_fetch
from ..config import APP_NAME, APP_PASSWORD, APP_VERSION, logger
from ..errors import FriendlyError
from ..jobs import job_store
from . import routes_cookies, routes_pages, routes_playlist, routes_video
from .tasks import cancel_background_tasks
from .security import basic_auth_ok


async def _cleanup_loop() -> None:
    while True:
        await asyncio.sleep(config.CLEANUP_INTERVAL_SECONDS)
        try:
            await asyncio.to_thread(job_store.cleanup)
        except Exception:
            logger.exception("Background cleanup failed")


@asynccontextmanager
async def lifespan(_: FastAPI):
    config.DOWNLOADS_DIR.mkdir(parents=True, exist_ok=True)
    cookies.materialize_from_env()
    cookies.ydl_options()   # logs a warning once when there are no cookies
    source = cookies.source_name()
    if source:
        logger.info("Using yt-dlp cookies from %s", source)
    http_fetch.open_client()
    with contextlib.suppress(Exception):
        await asyncio.to_thread(job_store.cleanup)
    cleanup_task = asyncio.create_task(_cleanup_loop())

    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg:
        logger.info("Fetchy %s starting; FFmpeg found at %s", APP_VERSION, ffmpeg)
    else:
        logger.warning("FFmpeg was not found on PATH. Merged video and MP3 audio downloads will fail.")
    logger.info("Listening on http://%s:%s", config.HOST, config.PORT)
    logger.info("Files auto-delete after %s seconds", config.DOWNLOAD_TTL_SECONDS)

    try:
        yield
    finally:
        cleanup_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await cleanup_task
        cancel_background_tasks()
        await http_fetch.close_client()


app = FastAPI(
    title=APP_NAME,
    version=APP_VERSION,
    description="A local video downloader built with yt-dlp.",
    lifespan=lifespan,
)
app.mount("/static", StaticFiles(directory=str(config.STATIC_DIR)), name="static")


@app.middleware("http")
async def add_security_headers(request: Request, call_next):
    if APP_PASSWORD and request.url.path != "/api/health" and not basic_auth_ok(request):
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
    detail: Any = exc.detail
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


for _module in (routes_pages, routes_cookies, routes_video, routes_playlist):
    app.include_router(_module.router)
