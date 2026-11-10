"""Small helpers: time, safe number parsing and safe file names."""

from __future__ import annotations

import re
import unicodedata
from datetime import datetime, timezone
from typing import Any


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime | None) -> str | None:
    return dt.isoformat() if dt else None


def coerce_int(value: Any) -> int | None:
    try:
        if value is None or value == "":
            return None
        return int(value)
    except (TypeError, ValueError, OverflowError):
        return None


def coerce_float(value: Any) -> float | None:
    try:
        if value is None or value == "":
            return None
        result = float(value)
        return result if result == result else None  # discard NaN
    except (TypeError, ValueError, OverflowError):
        return None


_WINDOWS_RESERVED_NAMES = {
    "CON", "PRN", "AUX", "NUL",
    *(f"COM{i}" for i in range(1, 10)),
    *(f"LPT{i}" for i in range(1, 10)),
}
_INVALID_FILENAME_RE = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
_SPACE_RE = re.compile(r"\s+")


def sanitize_filename(
    value: str | None,
    *,
    fallback: str = "download",
    max_length: int = 100,
    extension: str | None = None,
) -> str:
    """Make a user-facing filename safe on every OS.

    Path separators are replaced, reserved names are prefixed, leading dots and
    spaces are removed, and the final name is length-bounded. The extension is
    always chosen by the server, never trusted from a remote URL.
    """
    clean = unicodedata.normalize("NFKC", value or "")
    clean = _INVALID_FILENAME_RE.sub(" ", clean)
    clean = _SPACE_RE.sub(" ", clean).strip(" .")
    if extension:
        ext = extension if extension.startswith(".") else f".{extension}"
        if clean.lower().endswith(ext.lower()):
            clean = clean[: -len(ext)]
        clean = clean.strip(" .")
    if not clean:
        clean = fallback
    if clean.upper() in _WINDOWS_RESERVED_NAMES:
        clean = f"{fallback}-{clean}"
    ext = extension or ""
    max_stem = max(1, max_length - len(ext))
    clean = clean[:max_stem].rstrip(" .")
    if not clean:
        clean = fallback
    return f"{clean}{ext}"
