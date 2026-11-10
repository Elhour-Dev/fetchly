"""Playlists (desktop app only): videos are saved straight into a folder."""

from __future__ import annotations

import contextlib
import glob
import random
import threading
import time
import uuid
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlsplit

from fastapi import HTTPException

from . import config, engine
from .config import AUDIO_KEYS, MIX_CAP, PLAYLIST_PARALLEL, logger
from .errors import FriendlyError, friendly_ytdlp_error, strip_ansi
from .formats import audio_postprocessor, format_selector
from .utils import coerce_float, coerce_int, sanitize_filename
from .ydl_options import base_options, youtube_args

ACTIVE_STATES = {"queued", "starting", "downloading", "processing"}
_UNAVAILABLE_TITLES = {"[private video]": "Private video", "[deleted video]": "Deleted video"}
_SKIP_REASONS = (
    ("private video", "Private video"),
    ("members-only", "Members only"),
    ("has been removed", "Removed video"),
    ("deleted", "Deleted video"),
    ("video unavailable", "Not available"),
    ("not available", "Not available"),
    ("geo", "Blocked in your region"),
)

_archive_lock = threading.Lock()
playlists: dict[str, PlaylistRun] = {}


def skip_reason(raw: str) -> str | None:
    low = raw.lower()
    return next((reason for needle, reason in _SKIP_REASONS if needle in low), None)


# --- Download archive: one "site id" line per finished video ----------------

def archive_read() -> set[str]:
    try:
        return set(config.ARCHIVE_FILE.read_text(encoding="utf-8").splitlines())
    except OSError:
        return set()


def archive_add(key: str) -> None:
    if not key:
        return
    with _archive_lock, contextlib.suppress(OSError):
        if key not in archive_read():
            with config.ARCHIVE_FILE.open("a", encoding="utf-8") as fh:
                fh.write(key + "\n")


# --- Reading the list --------------------------------------------------------

def _entry_thumbnail(entry: dict[str, Any], site: str, vid: str) -> str | None:
    if site == "youtube" and vid:
        return f"https://i.ytimg.com/vi/{vid}/mqdefault.jpg"
    for cand in reversed(entry.get("thumbnails") or []):
        if isinstance(cand, dict) and str(cand.get("url") or "").startswith(("http://", "https://")):
            return cand["url"]
    return None


def extract_playlist_entries_sync(url: str) -> dict[str, Any]:
    """Fast (flat) look at a playlist: one small entry per video, no per-video requests."""
    is_mix = (parse_qs(urlsplit(url).query).get("list") or [""])[0].startswith("RD")
    options = base_options()
    options.update({"noplaylist": False, "extract_flat": "in_playlist", "skip_download": True})
    if is_mix:
        options["playlistend"] = MIX_CAP
    with engine.yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=False)
    if not isinstance(info, dict):
        info = {}
    raw = [e for e in (info.get("entries") or []) if isinstance(e, dict)]
    is_mix = is_mix or str(info.get("id") or "").startswith("RD")
    if is_mix:
        raw = raw[:MIX_CAP]
    have = archive_read()
    entries: dict[int, dict[str, Any]] = {}
    for n, e in enumerate(raw, 1):
        link = e.get("url") or e.get("webpage_url")
        if not isinstance(link, str) or not link.startswith(("http://", "https://")):
            continue
        vid = str(e.get("id") or "")
        site = str(e.get("ie_key") or "").lower()
        title = str(e.get("title") or f"Video {n}").strip()[:300]
        key = f"{site} {vid}" if site and vid else ""
        entries[n] = {
            "n": n, "url": link, "key": key, "title": title,
            "duration": coerce_float(e.get("duration")), "thumb": _entry_thumbnail(e, site, vid),
            "unavailable": _UNAVAILABLE_TITLES.get(title.lower()),
            "have": bool(key) and key in have,
        }
    if not entries:
        raise FriendlyError("No videos were found in this playlist.", 422, "no_videos")
    return {
        "title": str(info.get("title") or "Playlist").strip()[:200],
        "uploader": str(info.get("uploader") or info.get("channel") or "")[:150],
        "capped": is_mix,
        "entries": entries,
    }


# --- The download queue ----------------------------------------------------

class PlaylistRun:
    """One playlist: its videos and the download queue (a few worker threads)."""

    def __init__(self, title: str, entries: dict[int, dict[str, Any]]):
        self.id = str(uuid.uuid4())
        self.title = title
        self.entries = entries
        self.items: dict[int, dict[str, Any]] = {}
        self.quality = "best"
        self.folder: Path | None = None
        self.lock = threading.Lock()
        self.workers = 0
        self.first = True

    def active(self) -> bool:
        return any(i["state"] in ACTIVE_STATES for i in self.items.values())

    def start(self, numbers: list[int], quality: str) -> None:
        assert config.SAVE_DIR is not None
        self.quality = quality
        self.folder = config.SAVE_DIR / sanitize_filename(self.title, fallback="Playlist", max_length=80)
        self.folder.mkdir(parents=True, exist_ok=True)
        have = archive_read()
        with self.lock:
            self.items = {}
            for n in sorted(set(numbers)):
                entry = self.entries.get(n)
                if not entry:
                    continue
                reason = entry["unavailable"] or ("Already downloaded" if entry["key"] and entry["key"] in have else None)
                self.items[n] = {"n": n, "state": "skipped" if reason else "queued", "percent": 0.0,
                                 "speed": None, "error": reason, "stop": None}
        self._kick()

    def _kick(self) -> None:
        with self.lock:
            queued = sum(1 for i in self.items.values() if i["state"] == "queued")
            while self.workers < min(PLAYLIST_PARALLEL, queued):
                self.workers += 1
                threading.Thread(target=self._worker, daemon=True).start()

    def _worker(self) -> None:
        while True:
            with self.lock:
                item = next((i for _, i in sorted(self.items.items()) if i["state"] == "queued"), None)
                if item is None:
                    self.workers -= 1
                    return
                item.update(state="starting", stop=None, error=None, speed=None)
                # A small random pause between requests, so the site is not hammered.
                wait = 0.0 if self.first else random.uniform(1.0, 3.0)
                self.first = False
            time.sleep(wait)
            try:
                self._download(item)
            except Exception:  # never let a worker die silently
                logger.exception("Playlist worker error")
                item.update(state="failed", error="Something went wrong. Try again.")

    def _stem(self, n: int) -> str:
        return f"{n:03d} - " + sanitize_filename(self.entries[n]["title"], fallback="video", max_length=80)

    def _remove_partial(self, n: int) -> None:
        if not self.folder:
            return
        for path in self.folder.glob(glob.escape(self._stem(n)) + ".*"):
            with contextlib.suppress(OSError):
                path.unlink()

    def _options(self, n: int, hook) -> dict[str, Any]:
        assert self.folder is not None
        options = base_options()
        options.update({
            "format": format_selector(self.quality),
            "outtmpl": str(self.folder / (self._stem(n).replace("%", "%%") + ".%(ext)s")),
            "noplaylist": True,
            "merge_output_format": "mp4",
            "continuedl": True,
            "noprogress": True,
            "progress_hooks": [hook],
            "windowsfilenames": True,
            "extractor_args": youtube_args(),
        })
        if self.quality in AUDIO_KEYS:
            options["postprocessors"] = [audio_postprocessor(AUDIO_KEYS[self.quality])]
        return options

    def _download(self, item: dict[str, Any]) -> None:
        n = item["n"]
        entry = self.entries[n]

        def check() -> None:
            if item["stop"]:
                raise engine.yt_dlp.utils.DownloadCancelled(item["stop"])

        def hook(data: dict[str, Any]) -> None:
            check()
            if data.get("status") == "downloading":
                total = coerce_int(data.get("total_bytes")) or coerce_int(data.get("total_bytes_estimate"))
                done = coerce_int(data.get("downloaded_bytes")) or 0
                percent = min(99.9, done / total * 100.0) if total else item["percent"]
                item.update(state="downloading", percent=round(percent, 1), speed=coerce_float(data.get("speed")))
            elif data.get("status") == "finished":
                item.update(state="processing", speed=None)

        outcome = "done"
        try:
            check()
            with engine.yt_dlp.YoutubeDL(self._options(n, hook)) as ydl:
                info = ydl.extract_info(entry["url"], download=True)
            archive_add(entry["key"])
            if isinstance(info, dict) and info.get("extractor_key") and info.get("id"):
                archive_add(f"{str(info['extractor_key']).lower()} {info['id']}")
            item.update(state="done", percent=100.0, error=None)
        except engine.yt_dlp.utils.DownloadCancelled:
            outcome = item["stop"] or "cancel"
        except Exception as exc:
            raw = strip_ansi(str(exc))
            reason = skip_reason(raw)
            item.update(state="skipped" if reason else "failed", error=reason or friendly_ytdlp_error(exc))
            logger.info("Playlist item %s: %s", n, raw[:200])
        if outcome == "pause":
            item.update(state="paused")
        elif outcome == "cancel":
            self._remove_partial(n)
            item.update(state="cancelled", percent=0.0)
        item.update(stop=None, speed=None)

    def action(self, name: str, n: int | None) -> None:
        with self.lock:
            if n is not None and n not in self.items:
                raise HTTPException(status_code=404, detail="That video is not in the queue.")
            for item in ([self.items[n]] if n is not None else list(self.items.values())):
                state = item["state"]
                busy = state in {"starting", "downloading"}
                if name == "pause":
                    if state == "queued":
                        item["state"] = "paused"
                    elif busy:
                        item["stop"] = "pause"
                elif name == "resume" and state == "paused":
                    item["state"] = "queued"
                elif name == "cancel":
                    if state in {"queued", "paused"}:
                        self._remove_partial(item["n"])
                        item.update(state="cancelled", percent=0.0)
                    elif busy:
                        item["stop"] = "cancel"
                elif name == "retry" and state in {"failed", "cancelled"}:
                    item.update(state="queued", error=None)
        self._kick()

    def status(self) -> dict[str, Any]:
        with self.lock:
            items = [{k: i[k] for k in ("n", "state", "percent", "speed", "error")} for _, i in sorted(self.items.items())]
        return {"id": self.id, "folder": str(self.folder or ""), "running": self.active(), "items": items}


def open_run(title: str, entries: dict[int, dict[str, Any]]) -> PlaylistRun:
    """Register a new playlist and forget old ones that are not downloading."""
    for old_id in [k for k, v in playlists.items() if not v.active()]:
        playlists.pop(old_id, None)
    run = PlaylistRun(title, entries)
    playlists[run.id] = run
    return run
