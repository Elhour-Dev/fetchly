"""Holds the loaded yt-dlp module, so it can be swapped after an update.

Always use `engine.yt_dlp` (not `import yt_dlp`) so code sees the new version.
"""

from __future__ import annotations

import importlib
import sys

from .config import TOOLS_DIR

# Desktop app: use the newest downloaded yt-dlp if present.
if TOOLS_DIR and (TOOLS_DIR / "current").is_dir():
    sys.path.insert(0, str(TOOLS_DIR / "current"))

import yt_dlp  # noqa: E402  (must come after the path change above)


def tool_version() -> str | None:
    return getattr(getattr(yt_dlp, "version", None), "__version__", None)


def reload_tools() -> None:
    """Load the freshly installed yt-dlp without a restart."""
    global yt_dlp
    for name in list(sys.modules):
        if name.split(".")[0] == "yt_dlp":
            del sys.modules[name]
    importlib.invalidate_caches()
    yt_dlp = importlib.import_module("yt_dlp")
