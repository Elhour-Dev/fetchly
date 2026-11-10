"""Single video: read its details, then download it into the downloads folder."""

from __future__ import annotations

import contextlib
from pathlib import Path
from typing import Any

from . import config, engine
from .config import logger
from .errors import LOGIN_HELP_BOT, FriendlyError, friendly_ytdlp_error
from .formats import build_format_options, build_subtitle_options, extra_attempts, format_selector
from .jobs import job_store
from .utils import coerce_float, coerce_int, sanitize_filename
from .ydl_options import single_video_options

# Thumbnails and subtitle files that yt-dlp writes next to the media file.
SIDE_FILE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".srt", ".vtt", ".ass", ".lrc", ".json"}
_PARTIAL_SUFFIXES = (".part", ".ytdl", ".temp")


def _is_playable(fmt: dict[str, Any]) -> bool:
    return (
        fmt.get("vcodec") not in (None, "none")
        or fmt.get("acodec") not in (None, "none")
        # Direct files (plain .mp4 links etc.) often have no codec info at all.
        or bool(fmt.get("url") and fmt.get("ext") not in {"mhtml", "none"} and fmt.get("protocol") != "mhtml")
    )


def _pick_thumbnail(info: dict[str, Any]) -> str | None:
    thumbnail = info.get("thumbnail")
    if isinstance(thumbnail, str) and thumbnail.startswith(("http://", "https://")):
        return thumbnail
    for candidate in reversed(info.get("thumbnails") or []):
        if isinstance(candidate, dict):
            url = candidate.get("url")
            if isinstance(url, str) and url.startswith(("http://", "https://")):
                return url
    return None


def extract_video_info_sync(url: str) -> dict[str, Any]:
    options = single_video_options()
    options.update({"skip_download": True, "format": "bv*+ba/b"})
    with engine.yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=False)

    if not isinstance(info, dict):
        raise FriendlyError("This link didn't return video information.", 422, "no_video")
    if info.get("_type") == "playlist" and info.get("entries"):
        raise FriendlyError("Playlists aren't supported here. Paste a link to a single video.", 422, "playlist")
    live_status = str(info.get("live_status") or "")
    if info.get("is_live") or live_status in {"is_live", "is_upcoming"}:
        raise FriendlyError("Live or upcoming streams aren't supported yet.", 422, "live_stream")

    formats = [fmt for fmt in (info.get("formats") or []) if isinstance(fmt, dict)]
    if not any(_is_playable(fmt) for fmt in formats):
        if "youtube" in str(info.get("extractor_key") or "").lower():
            raise FriendlyError(LOGIN_HELP_BOT, 422, "no_formats")
        raise FriendlyError("No video was found in this link.", 422, "no_formats")

    duration = coerce_float(info.get("duration"))
    uploader = info.get("uploader") or info.get("channel") or info.get("creator") or ""
    return {
        "title": str(info.get("title") or "Untitled video").strip()[:300],
        "duration": duration,
        "webpage_url": str(info.get("webpage_url") or url),
        "uploader": str(uploader)[:150],
        "extractor": str(info.get("extractor_key") or info.get("extractor") or "")[:80],
        "thumbnail_url": _pick_thumbnail(info),
        "formats": build_format_options(info, duration),
        "subtitles": build_subtitle_options(info),
    }


def _job_files(job_id: str):
    return (path for path in config.DOWNLOADS_DIR.glob(f"{job_id}.*") if path.is_file())


def cleanup_job_files(job_id: str) -> None:
    for path in _job_files(job_id):
        with contextlib.suppress(OSError):
            path.unlink()


def find_finished_file(job_id: str) -> Path | None:
    candidates: list[Path] = []
    for path in _job_files(job_id):
        if path.name.endswith(_PARTIAL_SUFFIXES) or path.suffix.lower() in SIDE_FILE_SUFFIXES:
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


def _progress_hooks(job_id: str):
    def progress_hook(data: dict[str, Any]) -> None:
        status = data.get("status")
        if status == "downloading":
            total = coerce_int(data.get("total_bytes")) or coerce_int(data.get("total_bytes_estimate"))
            downloaded = coerce_int(data.get("downloaded_bytes")) or 0
            if not total:
                fragment_count = coerce_int(data.get("fragment_count"))
                fragment_index = coerce_int(data.get("fragment_index"))
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
                speed=coerce_float(data.get("speed")),
                eta=coerce_int(data.get("eta")),
            )
        elif status == "finished":
            job_store.set_progress(job_id, stage="processing", percent=100.0, speed=None, eta=None)

    def postprocessor_hook(data: dict[str, Any]) -> None:
        if data.get("status") in {"started", "processing"}:
            job_store.set_progress(job_id, stage="processing", percent=100.0, speed=None, eta=None)

    return progress_hook, postprocessor_hook


def _run_download(job_id: str, url: str, options: dict[str, Any], attempts: list[dict[str, Any]]) -> None:
    """Try each option set in turn; the last one is the plain fallback."""
    for number, extra in enumerate(attempts):
        try:
            with engine.yt_dlp.YoutubeDL({**options, **extra}) as ydl:
                ydl.download([url])
            return
        except Exception:
            if number == len(attempts) - 1:
                raise
            # Cover art / subtitles failed: start over with the plain download.
            logger.info("Video job %s: extras failed, retrying without them", job_id)
            cleanup_job_files(job_id)
            job_store.set_progress(job_id, stage="starting", percent=0.0, speed=None, eta=None)


def download_video_sync(job_id: str, quality: str, subs: str | None = None, subs_auto: bool = False) -> None:
    job = job_store.get(job_id)
    if not job or not job.video:
        return

    selector = format_selector(quality)
    job_store.set_progress(job_id, stage="starting", percent=0.0, speed=None, eta=None)
    progress_hook, postprocessor_hook = _progress_hooks(job_id)

    options = single_video_options()
    options.update({
        "format": selector,
        "outtmpl": str(config.DOWNLOADS_DIR / f"{job_id}.%(ext)s"),
        "merge_output_format": "mp4",
        "overwrites": True,
        "continuedl": False,
        "progress_hooks": [progress_hook],
        "postprocessor_hooks": [postprocessor_hook],
        "windowsfilenames": True,
        "restrictfilenames": True,
    })

    try:
        _run_download(job_id, job.url, options, extra_attempts(quality, subs, subs_auto))
        final_file = find_finished_file(job_id)
        if not final_file:
            raise FriendlyError("The download finished, but no output file was found. Please try again.", 500, "missing_output")
        download_name = sanitize_filename(
            job.video.get("title") or "video", fallback="video", max_length=95, extension=final_file.suffix,
        )
        # Remove any stray intermediate files left by a failed merge.
        for stray in _job_files(job_id):
            if stray != final_file:
                with contextlib.suppress(OSError):
                    stray.unlink()
        job_store.complete(job_id, file_path=final_file, download_name=download_name)
        logger.info("Video job %s complete (%s)", job_id, final_file.name)
    except FriendlyError as exc:
        cleanup_job_files(job_id)
        job_store.fail(job_id, exc.message)
        logger.warning("Video job %s failed: %s", job_id, exc.message)
    except Exception as exc:
        message = friendly_ytdlp_error(exc)
        cleanup_job_files(job_id)
        job_store.fail(job_id, message)
        logger.warning("Video job %s failed: %s", job_id, message)
        logger.debug("yt-dlp error detail", exc_info=True)
