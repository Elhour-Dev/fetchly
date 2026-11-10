"""Quality and subtitle choices shown to the user, and the yt-dlp options for them."""

from __future__ import annotations

from collections import defaultdict
from typing import Any

from . import engine
from .config import AUDIO_KEYS, QUALITY_FORMAT_SELECTORS, SUB_CODE_RE
from .errors import FriendlyError
from .utils import coerce_float, coerce_int


def _format_size(fmt: dict[str, Any]) -> int | None:
    for key in ("filesize", "filesize_approx"):
        size = coerce_int(fmt.get(key))
        if size and size > 0:
            return size
    return None


def _rank(fmt: dict[str, Any]) -> tuple[float, float, int]:
    return (
        coerce_float(fmt.get("tbr")) or 0.0,
        coerce_float(fmt.get("quality")) or 0.0,
        _format_size(fmt) or 0,
    )


def _estimate_size(fmt: dict[str, Any] | None, duration: float | None) -> int | None:
    """Use a real byte count when available, otherwise bitrate x duration."""
    if not fmt:
        return None
    known = _format_size(fmt)
    if known:
        return known
    bitrate = coerce_float(fmt.get("tbr"))
    if not bitrate:
        bitrate = (coerce_float(fmt.get("vbr")) or 0.0) + (coerce_float(fmt.get("abr")) or 0.0)
    if bitrate and duration and duration > 0:
        return int(bitrate * 1000 / 8 * duration)
    return None


def _video_only(fmt: dict[str, Any]) -> bool:
    return fmt.get("acodec") in (None, "none")


def build_format_options(info: dict[str, Any], duration: float | None = None) -> list[dict[str, Any]]:
    formats = [fmt for fmt in (info.get("formats") or []) if isinstance(fmt, dict)]
    video_formats = [
        fmt for fmt in formats
        if fmt.get("vcodec") not in (None, "none") and (coerce_int(fmt.get("height")) or 0) >= 144
    ]
    audio_formats = [
        fmt for fmt in formats
        if fmt.get("acodec") not in (None, "none") and fmt.get("vcodec") in (None, "none")
    ]

    audio_best = max(audio_formats, key=_rank, default=None)
    audio_size = _estimate_size(audio_best, duration)

    by_height: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for fmt in video_formats:
        height = coerce_int(fmt.get("height"))
        if height:
            by_height[height].append(fmt)

    best_height = max(by_height, default=None)
    best_video = max(video_formats, key=_rank, default=None)
    best_size = _estimate_size(best_video, duration)
    if best_video and _video_only(best_video) and audio_size:
        best_size = (best_size or 0) + audio_size

    options: list[dict[str, Any]] = [{
        "key": "best",
        "label": "Best quality",
        "detail": f"Up to {best_height}p" if best_height else "Highest available",
        "kind": "video",
        "size_bytes": best_size,
        "recommended": True,
    }]

    for height in sorted(by_height, reverse=True):
        candidate = max(by_height[height], key=_rank)
        size = _estimate_size(candidate, duration)
        if _video_only(candidate) and audio_size:
            size = (size or 0) + audio_size
        options.append({
            "key": str(height),
            "label": f"{height}p",
            "detail": "MP4 video",
            "kind": "video",
            "size_bytes": size,
            "recommended": False,
        })

    if audio_best:
        options.append({
            "key": "audio",
            "label": "Audio only (MP3)",
            "detail": "MP3 · 192 kbps · cover art & tags",
            "kind": "audio",
            "size_bytes": audio_size,
            "recommended": False,
        })
        options.append({
            "key": "audio-m4a",
            "label": "Audio only (M4A)",
            "detail": "M4A · original AAC quality · tags",
            "kind": "audio",
            "size_bytes": audio_size,
            "recommended": False,
        })

    return options


def _track_name(tracks: Any) -> str | None:
    return next((t.get("name") for t in tracks if isinstance(t, dict) and t.get("name")), None)


def build_subtitle_options(info: dict[str, Any]) -> list[dict[str, Any]]:
    """Subtitle languages that can be embedded: all uploaded ones, plus the automatic captions of the video's own language."""
    found: list[dict[str, Any]] = []
    for code, tracks in (info.get("subtitles") or {}).items():
        if code == "live_chat" or not SUB_CODE_RE.match(str(code)) or not tracks:
            continue
        found.append({"code": str(code), "name": str(_track_name(tracks) or code)[:60], "auto": False})
    have = {item["code"] for item in found}
    for code, tracks in (info.get("automatic_captions") or {}).items():
        base = str(code).removesuffix("-orig")
        if str(code).endswith("-orig") and SUB_CODE_RE.match(str(code)) and base not in have:
            found.append({"code": str(code), "name": f"{_track_name(tracks) or base} (auto)"[:60], "auto": True})
    return found[:60]


def format_selector(quality: str) -> str:
    """yt-dlp format string for a quality key. Rejects anything unknown."""
    if quality in QUALITY_FORMAT_SELECTORS:
        return QUALITY_FORMAT_SELECTORS[quality]
    if quality.isdigit():
        height = int(quality)
        if 144 <= height <= 4320:
            return f"bestvideo[height<=?{height}]+bestaudio/best[height<=?{height}]/best"
    raise FriendlyError("Choose a valid quality option.", 400, "invalid_quality")


def audio_postprocessor(codec: str) -> dict[str, Any]:
    """Plain audio extraction (no cover art)."""
    return {"key": "FFmpegExtractAudio", "preferredcodec": codec, **({"preferredquality": "192"} if codec == "mp3" else {})}


def _has_mutagen() -> bool:
    try:
        import mutagen  # noqa: F401
        return True
    except ImportError:
        return False


def extra_attempts(quality: str, subs: str | None, subs_auto: bool) -> list[dict[str, Any]]:
    """yt-dlp options for audio extraction (with cover art and tags) and for embedded subtitles.

    The richest set comes first; the last one is the plain fallback, used when embedding fails."""
    def parsed(argv: list[str]) -> dict[str, Any]:
        opts = engine.yt_dlp.parse_options(argv).ydl_opts
        keep = ("postprocessors", "writethumbnail", "writesubtitles", "writeautomaticsub", "subtitleslangs", "subtitlesformat")
        return {k: opts[k] for k in keep if k in opts}

    if quality in AUDIO_KEYS:
        codec = AUDIO_KEYS[quality]
        argv = ["-x", "--audio-format", codec, "--embed-metadata"]
        # Cover art: always for MP3; for M4A yt-dlp needs the optional "mutagen" package.
        if codec == "mp3" or _has_mutagen():
            argv += ["--embed-thumbnail", "--convert-thumbnails", "jpg"]
        if codec == "mp3":
            argv += ["--audio-quality", "192K"]
        return [parsed(argv), {"postprocessors": [audio_postprocessor(codec)]}]
    if subs:
        argv = ["--embed-subs", "--sub-langs", subs, "--convert-subs", "srt"] + (["--write-auto-subs"] if subs_auto else [])
        return [parsed(argv), {}]
    return [{}]
