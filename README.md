# Fetchy

A polished, self-hosted video downloader built with **FastAPI**, **yt-dlp**, and **Tailwind CSS**.

Fetchy is designed as a local, single-user utility. Paste a public video URL to preview its title, thumbnail, duration, and available qualities, then follow a live download with speed and ETA.

> Use Fetchy only for content you have the right to download. Respect each site's terms and applicable copyright law.

---

## Features

### Video
- Metadata-only lookup with `yt-dlp` (`extract_info(..., download=False)`) — no download until you confirm.
- Preview card with thumbnail, title, duration, uploader, and source.
- Grouped quality options (best, 2160p/1440p/1080p/720p/480p/360p, audio-only) with byte estimates.
- Correct yt-dlp selectors:
  - Best: `bestvideo*+bestaudio/best`
  - Resolution: `bestvideo[height<=?720]+bestaudio/best[height<=?720]`
  - Audio: `bestaudio/best` + MP3 conversion (192 kbps), or M4A (original AAC). Title and artist tags are added, plus cover art for MP3 (and for M4A when the optional `mutagen` package is installed).
- Quick quality buttons (Best, 1080p, 720p, 480p, Audio) that remember your last choice.
- Optional embedded subtitles: pick a language (`subs` in `POST /api/video/download`) and it is muxed into the MP4.
- Download history on your device (web and desktop), with "Fetch again", and a "done" notification when you are in another window.
- Live progress over **Server-Sent Events**: percentage, downloaded bytes, speed, ETA, and merge/post-processing stage.
- Files saved with UUID names in `downloads/`, with friendly titles in the browser download.

### UX and safety
- Clean responsive layout (down to 360 px), light/dark theme, toasts, skeleton loaders, inline URL validation, paste/clear buttons, keyboard navigation, visible focus rings, `aria-live` announcements, and `prefers-reduced-motion` support.
- All actions are AJAX/fetch based — no page reloads.
- Basic SSRF guard: only HTTP(S), no credentials, standard web ports, and every resolved address must be globally routable. Redirects are re-validated on each hop.
- Friendly JSON errors; tracebacks stay in the server log.
- In-memory sliding-window rate limits on lookup/download endpoints.
- Automatic cleanup removes generated files after **1 hour** (`DOWNLOAD_TTL_SECONDS`).
- Hard limits on response size.

---

## Download for Windows

1. Go to the [latest release](https://github.com/Elhour-Dev/fetchly/releases/latest) and download `Fetchy-Setup-X.Y.Z.exe` (the newest version).
2. Run it. No admin rights needed. yt-dlp and ffmpeg are already inside.
3. Windows may show "Protected your PC". Click **More info**, then **Run anyway**.
4. Videos save to your Downloads folder. Use **Update tools** to get the newest yt-dlp.

License notices (ffmpeg is GPL v3, yt-dlp is Unlicense): see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Download for Android

1. On your phone, open the [latest release](https://github.com/Elhour-Dev/fetchly/releases/latest) and download `Fetchy-X.Y.Z.apk` (the newest version).
2. Open the file. Android will ask to allow installs from your browser or Files app. Allow it for this install.
3. Share a video link to Fetchy, or paste it in the app. Videos save to your Downloads folder.
4. Login problems? The app shows a **Log in** button. If the site blocks that, use **Import cookies.txt**. Open **Help** in the app for steps.

Needs Android 10 or newer and a 64-bit (arm64) phone.

## Requirements

- **Python 3.10+**
- **FFmpeg** installed and available on `PATH` (required for merging separate video/audio streams and for MP3 conversion)
- Internet access to the video sites you use

### Install FFmpeg

#### Windows

Option A — winget:

```powershell
winget install --id Gyan.FFmpeg -e
```

Option B — manual:

1. Download a full build from <https://www.gyan.dev/ffmpeg/builds/>.
2. Extract it to a permanent location, for example `C:\ffmpeg`.
3. Add `C:\ffmpeg\bin` to your **user** `PATH`.
4. Open a **new** terminal and verify:

```powershell
ffmpeg -version
```

#### macOS

```bash
brew install ffmpeg
ffmpeg -version
```

#### Linux

Debian / Ubuntu:

```bash
sudo apt update
sudo apt install -y ffmpeg
ffmpeg -version
```

Fedora:

```bash
sudo dnf install -y ffmpeg
```

Arch:

```bash
sudo pacman -S ffmpeg
```

If `ffmpeg -version` works in your terminal, Fetchy will find it.

---

## Setup

```bash
# 1. Enter the project directory
cd fetchly

# 2. Create and activate a virtual environment
python -m venv .venv

# Windows PowerShell
.venv\Scripts\Activate.ps1

# macOS / Linux
source .venv/bin/activate

# 3. Install dependencies
python -m pip install --upgrade pip
pip install -r requirements.txt

# 4. Run
python main.py
# or, with auto-reload during development:
uvicorn main:app --reload
```

Open <http://127.0.0.1:8001>.

The API docs are available at <http://127.0.0.1:8001/docs>.

### First check

1. Install FFmpeg and confirm `ffmpeg -version` works.
2. Start Fetchy. Open the app. If FFmpeg is missing, a warning appears above the video form and `/api/health` reports `"available": false`.
3. Paste a public video link and fetch its details.
4. Choose a quality and download. The file appears in `downloads/` with a UUID filename.

---

## Project structure

```text
.
├── main.py                  # entry point (python main.py / uvicorn main:app)
├── fetchy/                  # the backend
│   ├── config.py            # settings from env vars, paths, constants
│   ├── errors.py            # safe errors + friendly yt-dlp messages
│   ├── utils.py             # time, number parsing, safe file names
│   ├── urls.py              # URL checks (SSRF guard)
│   ├── jobs.py              # in-memory jobs + file cleanup
│   ├── ratelimit.py         # per-client rate limits
│   ├── http_fetch.py        # thumbnail fetch with checked redirects
│   ├── engine.py            # the loaded yt-dlp (swapped after an update)
│   ├── updater.py           # "Update tools" (yt-dlp)
│   ├── cookies.py           # login cookies (file or browser)
│   ├── ydl_options.py       # shared yt-dlp options
│   ├── formats.py           # quality/subtitle choices
│   ├── video.py             # single video: info + download
│   ├── playlist.py          # playlist queue (desktop app)
│   ├── playlist_zip.py      # whole playlist as one ZIP (web version)
│   └── web/                 # FastAPI app, auth, routes_*.py
├── templates/index.html     # Tailwind UI
├── static/                  # app.js, playlist.js, help.js, icons
├── tests/                   # pytest tests
├── desktop/                 # Electron wrapper (Windows installer)
├── android/                 # Android app (Kotlin)
├── downloads/               # generated files (auto-cleaned)
├── requirements.txt         # app dependencies
└── requirements-dev.txt     # + pytest and ruff
```

### Tests and lint

```bash
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements-dev.txt   # macOS/Linux: .venv/bin/python
.venv/Scripts/python -m pytest
.venv/Scripts/python -m ruff check .
```

---

## API overview

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/video/info` | Fetch metadata and grouped formats |
| `POST` | `/api/video/download` | Start a download for a ready video job |
| `GET` | `/api/jobs/{job_id}` | Poll job status (SSE fallback) |
| `GET` | `/api/jobs/{job_id}/events` | SSE download progress stream |
| `GET` | `/api/video/{job_id}/thumbnail` | Proxy the video thumbnail |
| `GET` | `/api/download/{job_id}` | Serve a completed file |
| `GET` | `/api/health` | FFmpeg and yt-dlp status |

Example:

```bash
curl -X POST http://127.0.0.1:8001/api/video/info \
  -H "Content-Type: application/json" \
  -d '{"url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"}'
```

---

## Configuration

Environment variables (all optional):

| Variable | Default | Description |
| --- | --- | --- |
| `HOST` | `127.0.0.1` | Bind address |
| `PORT` | `8001` | Port |
| `DOWNLOAD_TTL_SECONDS` | `3600` | File and job lifetime |
| `CLEANUP_INTERVAL_SECONDS` | `600` | Cleanup sweep interval |
| `MAX_PREVIEW_BYTES` | `20971520` (20 MB) | Preview proxy cap |
| `VIDEO_INFO_TIMEOUT_SECONDS` | `90` | Metadata lookup timeout |
| `LOG_LEVEL` | `INFO` | Python log level |

The server binds to localhost by default. If you expose it on a LAN, put it behind a reverse proxy and add authentication — the built-in limits are meant for a trusted local user, not the public internet.

---

## How it works

1. **Validation** — URLs are normalized, checked for HTTP(S), credentials, standard ports, and global-only DNS resolution.
2. **Video lookup** — `yt-dlp` extracts metadata with `download=False`. Formats are grouped and size estimates use real byte counts where available, otherwise `tbr × duration`.
3. **Download** — a UUID job is created, yt-dlp downloads to `downloads/{uuid}.%(ext)s`, progress hooks update the server-side job, and SSE streams snapshots to the browser.
4. **Cleanup** — a background task deletes completed jobs and files after the TTL, and sweeps orphaned files older than one hour.

---

## Troubleshooting

### "FFmpeg wasn't found on PATH"

- Run `ffmpeg -version` in the same terminal that starts Fetchy.
- If it works elsewhere, close and reopen the terminal (or IDE) so it receives the updated `PATH`.
- On Windows, confirm the FFmpeg `bin` folder (the one containing `ffmpeg.exe`) is on `PATH`.
- Restart the Fetchy server after fixing `PATH`.

### A video fails with a sign-in or bot check

Some sites (notably YouTube) may ask for a login/bot verification depending on your network. Fetchy intentionally does not ship cookies or credentials. Try a different public link, or configure cookies for yt-dlp locally if you understand the account/security implications. Raw stack traces are never sent to the browser — check the server console for details.

### Download link expires

Generated files and job IDs are deleted after one hour by default. Set `DOWNLOAD_TTL_SECONDS` if you need a longer window.

### YouTube 4K/1080p merges fail

That is almost always FFmpeg missing from `PATH`. The audio-only and progressive formats may still work, but separate high-quality video/audio streams need FFmpeg to merge.

---

## Notes and limitations

- Single-process, in-memory job store. Restarting the server clears pending jobs; files on disk are swept by the cleanup task.
- Public URLs only. Private, local, link-local, and reserved addresses are rejected. The guard is intentionally conservative but is not a substitute for network-level egress controls.
- Built-in rate limits are per client IP and in-memory; they reset when the process restarts.
- Site support changes often. Keep yt-dlp up to date (use the **Update tools** button).
- This project uses the Tailwind CDN for a zero-build frontend. For an internet-facing deployment, compile Tailwind locally and add a proper CSP.
- No account system, cloud storage, or persistent database by design.

---

## License

This project is provided for personal, lawful use. You are responsible for complying with the terms of service of any site you access and for the copyright status of any content you download.

## Logins and the YouTube "not a bot" check

Some links need you to be logged in (Instagram, private posts, YouTube bot checks).
The yt-dlp docs recommend using your browser's cookies:

1. Sign in to the site in your normal browser (Chrome, Firefox, Edge...).
2. Start Fetchy with `FETCHLY_COOKIES_BROWSER=chrome` (or `firefox`, `edge`, `brave`).
   Windows PowerShell: `$env:FETCHLY_COOKIES_BROWSER="chrome"; uvicorn main:app`
3. If Chrome/Edge cookies fail to read, close the browser first or use Firefox.

Fetchy never saves your cookies or passwords. Do not commit `cookies.txt` (it is in `.gitignore`).
Use the **Update tools** button at the bottom of the page to update yt-dlp.


Fetchy downloads videos only. Image links show "Fetchy downloads videos only."
