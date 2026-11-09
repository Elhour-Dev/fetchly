# Third-party notices

The Fetchy Windows app bundles these programs. They keep their own licenses.

## FFmpeg (GPL v3)

- Program: `ffmpeg.exe`, version 9.0.2 "full build" from gyan.dev
- Copyright (c) 2000-2026 the FFmpeg developers
- License: **GNU General Public License v3 or later** (this build uses `--enable-gpl --enable-version3`)
- Full license text: https://www.gnu.org/licenses/gpl-3.0.txt
- Project and source code: https://ffmpeg.org/download.html
- Build used: https://www.gyan.dev/ffmpeg/builds/ (source and build details are linked there)

Fetchy runs `ffmpeg.exe` as a separate program. It does not link to it.
You may replace `ffmpeg.exe` in the `resources/ffmpeg` folder of the install with your own build.

## yt-dlp (The Unlicense)

- Project: https://github.com/yt-dlp/yt-dlp
- License: The Unlicense (public domain). Text: https://unlicense.org/
- The version in the app can be updated with the "Update tools" button.

## Other bundled parts

- Python 3.12 (embeddable) - PSF License - https://docs.python.org/3/license.html
- FastAPI, Starlette, Uvicorn, HTTPX, Pydantic, Pillow, and their dependencies - MIT/BSD/HPND-style licenses (each package keeps its license file in `resources/pylib`)
- Electron and Chromium - MIT and other open-source licenses - https://github.com/electron/electron

Fetchy itself is not affiliated with any of these projects.
