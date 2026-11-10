"""Test setup: keep all app data in a temp folder, never in the project."""

import os
import sys
import tempfile
from pathlib import Path

_DATA = Path(tempfile.mkdtemp(prefix="fetchy-test-"))
os.environ["FETCHLY_DATA_DIR"] = str(_DATA)
os.environ.pop("FETCHLY_PASSWORD", None)
os.environ.pop("FETCHLY_SAVE_DIR", None)
os.environ.pop("YOUTUBE_COOKIES_B64", None)

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
