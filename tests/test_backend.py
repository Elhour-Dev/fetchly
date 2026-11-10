import json
import os
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from starlette.requests import Request

import main

ROOT = Path(__file__).resolve().parent.parent


def make_request(host: str, headers: dict[str, str] | None = None) -> Request:
    raw = [(k.lower().encode(), v.encode()) for k, v in (headers or {}).items()]
    return Request({"type": "http", "method": "GET", "path": "/", "headers": raw, "client": (host, 1234)})


# --- URLs -------------------------------------------------------------------

@pytest.mark.parametrize("url", [
    "ftp://example.com/a",
    "http://user:pw@example.com/",
    "http://localhost/",
    "http://printer.local/",
    "http://example.com:8080/",
    "",
    "http://exa mple.com/",
])
def test_normalize_url_rejects_bad_urls(url):
    with pytest.raises(main.FriendlyError):
        main.normalize_url(url)


def test_normalize_url_keeps_query_and_drops_fragment():
    assert main.normalize_url(" https://Example.com/watch?v=1#t=3 ") == "https://example.com/watch?v=1"


def test_private_ip_is_blocked():
    with pytest.raises(main.FriendlyError):
        main._host_is_public("10.0.0.1")
    with pytest.raises(main.FriendlyError):
        main._host_is_public("127.0.0.1")


# --- Names and errors -------------------------------------------------------

def test_sanitize_filename():
    assert main.sanitize_filename('a/b:c*?.mp4', extension=".mp4") == "a b c.mp4"
    assert main.sanitize_filename("CON", extension=".mp4") == "download-CON.mp4"
    assert main.sanitize_filename("", fallback="video") == "video"
    assert len(main.sanitize_filename("x" * 500, extension=".mp4", max_length=95)) == 95


def test_friendly_errors():
    assert "HTTP 404" in main.friendly_ytdlp_error(Exception("ERROR: HTTP Error 404: Not Found"))
    assert '"not found"' in main.friendly_ytdlp_error(Exception("HTTP Error 404"))
    assert "private" in main.friendly_ytdlp_error(Exception("Private video")).lower()
    assert main.friendly_ytdlp_error(Exception("")).startswith("Couldn't download")


# --- Qualities ----------------------------------------------------------------

def test_format_selector():
    assert main._format_selector("best") == "bestvideo*+bestaudio/best"
    assert "height<=?720" in main._format_selector("720")
    for bad in ("99", "99999", "worst", "720;rm"):
        with pytest.raises(main.FriendlyError):
            main._format_selector(bad)


def test_audio_attempts_have_plain_fallback():
    rich, plain = main._extra_ydl_attempts("audio-m4a", None, False)
    assert plain["postprocessors"][0]["preferredcodec"] == "m4a"
    assert any(pp["key"] == "FFmpegExtractAudio" for pp in rich["postprocessors"])
    assert main._extra_ydl_attempts("720", None, False) == [{}]


def test_build_format_options():
    info = {"formats": [
        {"format_id": "a", "vcodec": "none", "acodec": "mp4a", "filesize": 100},
        {"format_id": "v", "vcodec": "avc1", "acodec": "none", "height": 720, "filesize": 1000},
    ]}
    keys = [o["key"] for o in main.build_format_options(info)]
    assert keys == ["best", "720", "audio", "audio-m4a"]


# --- Jobs and cleanup -------------------------------------------------------

def test_cleanup_keeps_files_of_running_jobs(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DOWNLOADS_DIR", tmp_path)
    store = main.JobStore()
    running = store.create(kind="video", url="https://example.com/", state="downloading")
    old = time.time() - main.DOWNLOAD_TTL_SECONDS - 60
    keep = tmp_path / f"{running.id}.f137.mp4.part"
    stale = tmp_path / "11111111-1111-1111-1111-111111111111.mp4"
    for path in (keep, stale):
        path.write_bytes(b"x")
        os.utime(path, (old, old))
    store.cleanup()
    assert keep.exists()
    assert not stale.exists()


def test_download_video_sync_completes(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DOWNLOADS_DIR", tmp_path)
    job = main.job_store.create(kind="video", url="https://example.com/v", state="downloading")
    main.job_store.update(job.id, video={"title": "My: video", "formats": []})

    class FakeYDL:
        def __init__(self, opts):
            self.opts = opts

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def download(self, urls):
            Path(self.opts["outtmpl"].replace("%(ext)s", "mp4")).write_bytes(b"video")

    monkeypatch.setattr(main.yt_dlp, "YoutubeDL", FakeYDL)
    main.download_video_sync(job.id, "best")
    done = main.job_store.get(job.id)
    assert done.state == "complete"
    assert done.download_name == "My video.mp4"


# --- Local-only checks ------------------------------------------------------

def test_local_request_rules():
    assert main._is_local_request(make_request("127.0.0.1"))
    assert not main._is_local_request(make_request("8.8.8.8"))
    # A proxied request could fake its address, so it never counts as local.
    assert not main._is_local_request(make_request("127.0.0.1", {"X-Forwarded-For": "127.0.0.1"}))


def test_playlist_audio_m4a_extracts_audio(tmp_path, monkeypatch):
    seen = {}

    class FakeYDL:
        def __init__(self, opts):
            seen.update(opts)

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def extract_info(self, url, download):
            return {}

    monkeypatch.setattr(main.yt_dlp, "YoutubeDL", FakeYDL)
    run = main.PlaylistRun("List", {1: {"n": 1, "url": "https://example.com/1", "key": "", "title": "One",
                                        "unavailable": None}})
    run.quality = "audio-m4a"
    run.folder = tmp_path
    item = {"n": 1, "state": "starting", "percent": 0.0, "speed": None, "error": None, "stop": None}
    run._download(item)
    assert item["state"] == "done"
    assert seen["postprocessors"][0]["preferredcodec"] == "m4a"


# --- HTTP API ---------------------------------------------------------------

@pytest.fixture()
def client():
    with TestClient(main.app) as c:
        yield c


def test_health_reports_app_version(client):
    body = client.get("/api/health").json()
    package = json.loads((ROOT / "desktop" / "package.json").read_text(encoding="utf-8"))
    assert body["status"] == "ok"
    assert body["version"] == package["version"]


def test_pages_and_favicon(client):
    assert client.get("/").status_code == 200
    assert client.get("/favicon.ico").status_code == 200


def test_cookie_routes_are_local_only(client):
    # TestClient's address is "testclient", not this computer.
    assert client.get("/api/cookies").status_code == 403


def test_bad_job_ids(client):
    assert client.get("/api/jobs/not-a-uuid").status_code == 404
    assert client.get("/api/download/00000000-0000-0000-0000-000000000000").status_code == 404


def test_video_info_rejects_private_url(client):
    r = client.post("/api/video/info", json={"url": "http://127.0.0.1/x.mp4"})
    assert r.status_code == 400
    assert r.json()["error"] == "blocked_url"
