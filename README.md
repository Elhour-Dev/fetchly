# Fetchly

A polished, self-hosted video and image downloader built with **FastAPI**, **yt-dlp**, **gallery-dl**, and **Tailwind CSS**.

Fetchly is designed as a local, single-user utility. Paste a public video URL to preview its title, thumbnail, duration, and available qualities, then follow a live download with speed and ETA. Or paste a page URL to collect its images into a selectable grid and download the selection as a ZIP.

> Use Fetchly only for content you have the right to download. Respect each site's terms and applicable copyright law.

---

## Features

### Video
- Metadata-only lookup with `yt-dlp` (`extract_info(..., download=False)`) — no download until you confirm.
- Preview card with thumbnail, title, duration, uploader, and source.
- Grouped quality options (best, 2160p/1440p/1080p/720p/480p/360p, audio-only) with byte estimates.
- Correct yt-dlp selectors:
  - Best: `bestvideo*+bestaudio/best`
  - Resolution: `bestvideo[height<=?720]+bestaudio/best[height<=?720]`
  - Audio: `bestaudio/best` + MP3 conversion (192 kbps)
- Live progress over **Server-Sent Events**: percentage, downloaded bytes, speed, ETA, and merge/post-processing stage.
- Files saved with UUID names in `downloads/`, with friendly titles in the browser download.

### Images
- Site-specific galleries through [gallery-dl](https://github.com/mikf/gallery-dl)'s Python API (`extractor.find` + a capped `DataJob` in no-download mode).
- Safe generic-page fallback for everything else: supports `<img>`, `srcset`, lazy-load attributes (`data-src`, `data-original`, `data-lazy-src`, `data-srcset`), `<picture>`, Open Graph/Twitter metadata, JSON-LD, and linked image files.
- Responsive 2/3/4-column thumbnail grid with checkboxes, select all/clear, live selection count, and "Download selected as ZIP".
- ZIP images are downloaded server-side, verified with Pillow, and packaged under sanitized filenames.

### UX and safety
- Clean responsive layout (down to 360 px), light/dark theme, animated tab indicator, toasts, skeleton loaders, inline URL validation, paste/clear buttons, keyboard navigation, visible focus rings, `aria-live` announcements, and `prefers-reduced-motion` support.
- All actions are AJAX/fetch based — no page reloads.
- Basic SSRF guard: only HTTP(S), no credentials, standard web ports, and every resolved address must be globally routable. Redirects are re-validated on each hop.
- Friendly JSON errors; tracebacks stay in the server log.
- In-memory sliding-window rate limits on lookup/download endpoints.
- Automatic cleanup removes generated files after **1 hour** (`DOWNLOAD_TTL_SECONDS`).
- Hard limits on image count, response size, and ZIP size.

---

## Why gallery-dl?

**Chosen library: gallery-dl.**

gallery-dl is mature and actively maintained, with roughly a thousand site-specific extractors and a stable Python API. Its `DataJob` can collect direct image URLs without downloading anything, which is exactly what a selection UI needs. It is also available as a normal PyPI package and does not require shelling out to a CLI.

Its built-in *generic* extractor is intentionally very broad and often picks up logos, sprites, and other UI assets. For unrecognized pages Fetchly therefore uses a small, hardened HTML/BeautifulSoup fallback that extracts only likely content images, honors lazy-loading attributes, and re-validates every image URL before it is proxied or zipped. Site-specific gallery extraction still goes through gallery-dl first. This gives the best of both worlds: gallery-dl's depth for supported sites and predictable results for arbitrary pages.

---

## Requirements

- **Python 3.10+**
- **FFmpeg** installed and available on `PATH` (required for merging separate video/audio streams and for MP3 conversion)
- Internet access to the video/image sites you use

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

If `ffmpeg -version` works in your terminal, Fetchly will find it.

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

Open <http://127.0.0.1:8000>.

The API docs are available at <http://127.0.0.1:8000/docs>.

### First check

1. Install FFmpeg and confirm `ffmpeg -version` works.
2. Start Fetchly. Open the app. If FFmpeg is missing, a warning appears above the video form and `/api/health` reports `"available": false`.
3. Paste a public video link and fetch its details.
4. Choose a quality and download. The file appears in `downloads/` with a UUID filename.

---

## Project structure

```text
.
├── main.py              # FastAPI app, job store, SSE, yt-dlp/gallery-dl logic, safety
├── requirements.txt
├── README.md
├── downloads/           # generated files (auto-cleaned; .gitkeep only in git)
├── templates/
│   └── index.html       # Tailwind UI
└── static/
    ├── app.js           # vanilla JS: tabs, fetch, SSE, grid, toasts, theme
    └── favicon.svg
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
| `POST` | `/api/images/extract` | Scan a page and return images |
| `GET` | `/api/images/{job_id}/{image_id}/preview` | Proxy an image preview |
| `POST` | `/api/images/zip` | Download selected images and package a ZIP |
| `GET` | `/api/download/{job_id}` | Serve a completed file |
| `GET` | `/api/health` | FFmpeg, gallery-dl, yt-dlp status |

Example:

```bash
curl -X POST http://127.0.0.1:8000/api/video/info \
  -H "Content-Type: application/json" \
  -d '{"url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"}'
```

---

## Configuration

Environment variables (all optional):

| Variable | Default | Description |
| --- | --- | --- |
| `HOST` | `127.0.0.1` | Bind address |
| `PORT` | `8000` | Port |
| `DOWNLOAD_TTL_SECONDS` | `3600` | File and job lifetime |
| `CLEANUP_INTERVAL_SECONDS` | `600` | Cleanup sweep interval |
| `MAX_IMAGE_COUNT` | `100` | Maximum images returned/zip-able |
| `MAX_IMAGE_DOWNLOAD_BYTES` | `26214400` (25 MB) | Per-image ZIP download cap |
| `MAX_PAGE_BYTES` | `8388608` (8 MB) | Page HTML cap |
| `MAX_PREVIEW_BYTES` | `20971520` (20 MB) | Preview proxy cap |
| `MAX_ZIP_TOTAL_BYTES` | `314572800` (300 MB) | Total ZIP download cap |
| `VIDEO_INFO_TIMEOUT_SECONDS` | `90` | Metadata lookup timeout |
| `LOG_LEVEL` | `INFO` | Python log level |

The server binds to localhost by default. If you expose it on a LAN, put it behind a reverse proxy and add authentication — the built-in limits are meant for a trusted local user, not the public internet.

---

## How it works

1. **Validation** — URLs are normalized, checked for HTTP(S), credentials, standard ports, and global-only DNS resolution.
2. **Video lookup** — `yt-dlp` extracts metadata with `download=False`. Formats are grouped and size estimates use real byte counts where available, otherwise `tbr × duration`.
3. **Download** — a UUID job is created, yt-dlp downloads to `downloads/{uuid}.%(ext)s`, progress hooks update the server-side job, and SSE streams snapshots to the browser.
4. **Images** — gallery-dl handles recognized galleries; otherwise the HTML fallback builds a ranked candidate list. A server-side proxy serves previews without exposing your browser to remote hotlink/referrer problems.
5. **ZIP** — selected image URLs are revalidated, downloaded with concurrency, sniffed with Pillow, written to a temporary directory, then packaged into `downloads/{uuid}.zip`.
6. **Cleanup** — a background task deletes completed jobs and files after the TTL, and sweeps orphaned files older than one hour.

---

## Troubleshooting

### "FFmpeg wasn't found on PATH"

- Run `ffmpeg -version` in the same terminal that starts Fetchly.
- If it works elsewhere, close and reopen the terminal (or IDE) so it receives the updated `PATH`.
- On Windows, confirm the FFmpeg `bin` folder (the one containing `ffmpeg.exe`) is on `PATH`.
- Restart the Fetchly server after fixing `PATH`.

### A video fails with a sign-in or bot check

Some sites (notably YouTube) may ask for a login/bot verification depending on your network. Fetchly intentionally does not ship cookies or credentials. Try a different public link, or configure cookies for yt-dlp locally if you understand the account/security implications. Raw stack traces are never sent to the browser — check the server console for details.

### Images previews are blank

The origin may block hotlinking or require a session. The grid shows "Preview unavailable", but the URL may still be included in the ZIP attempt. Errors are reported per image. Some login-gated galleries cannot be downloaded without cookies.

### "No images were found"

Many modern sites render images with JavaScript after page load. Fetchly reads server HTML (plus common metadata and lazy-load attributes), not a headless browser. Try a direct image URL, the page's gallery view, or a different site.

### Download link expires

Generated files and job IDs are deleted after one hour by default. Set `DOWNLOAD_TTL_SECONDS` if you need a longer window.

### YouTube 4K/1080p merges fail

That is almost always FFmpeg missing from `PATH`. The audio-only and progressive formats may still work, but separate high-quality video/audio streams need FFmpeg to merge.

---

## Notes and limitations

- Single-process, in-memory job store. Restarting the server clears pending jobs; files on disk are swept by the cleanup task.
- Public URLs only. Private, local, link-local, and reserved addresses are rejected. The guard is intentionally conservative but is not a substitute for network-level egress controls.
- Built-in rate limits are per client IP and in-memory; they reset when the process restarts.
- gallery-dl site coverage varies and some sites change frequently. Keep `gallery-dl` and `yt-dlp` up to date with `pip install -U gallery-dl yt-dlp`.
- This project uses the Tailwind CDN for a zero-build frontend. For an internet-facing deployment, compile Tailwind locally and add a proper CSP.
- No account system, cloud storage, or persistent database by design.

---

## License

This project is provided for personal, lawful use. You are responsible for complying with the terms of service of any site you access and for the copyright status of any content you download.
