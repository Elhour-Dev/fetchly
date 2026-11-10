"""Web version only: download a whole playlist and hand it over as one ZIP.

(The desktop app uses fetchy/playlist.py instead, which saves into a folder.)
"""

from __future__ import annotations

import shutil
import zipfile
from pathlib import Path
from typing import Any

from . import config, engine
from .errors import FriendlyError
from .ydl_options import base_options

_PARTIAL_SUFFIXES = (".part", ".ytdl", ".temp")


def extract_playlist_info_sync(url: str) -> dict[str, Any]:
    """Quick look at a playlist: title and number of videos (no per-video details)."""
    options = base_options()
    options.update({"noplaylist": False, "extract_flat": "in_playlist", "skip_download": True})
    with engine.yt_dlp.YoutubeDL(options) as ydl:
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


def work_dir(job_id: str) -> Path:
    """Folder the playlist videos are downloaded into before zipping."""
    return config.DOWNLOADS_DIR / f"{job_id}_pl"


def playlist_options(job_id: str) -> dict[str, Any]:
    """Options that turn a single-video download into a whole-playlist one."""
    return {
        "outtmpl": str(work_dir(job_id) / "%(playlist_index)03d - %(title).80s.%(ext)s"),
        "noplaylist": False,
        "ignoreerrors": True,
    }


def zip_playlist(job_id: str) -> Path:
    """Pack the downloaded videos into <job_id>.zip and remove the work folder."""
    folder = work_dir(job_id)
    files = sorted(f for f in folder.glob("*") if f.is_file() and not f.name.endswith(_PARTIAL_SUFFIXES))
    if not files:
        raise FriendlyError("No videos could be downloaded from this playlist.", 422, "no_videos")
    zip_path = config.DOWNLOADS_DIR / f"{job_id}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_STORED) as zf:
        for f in files:
            zf.write(f, f.name)
    shutil.rmtree(folder, ignore_errors=True)
    return zip_path


def remove_work_dir(job_id: str) -> None:
    shutil.rmtree(work_dir(job_id), ignore_errors=True)
