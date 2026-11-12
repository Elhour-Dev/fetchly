import json
import os
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from starlette.requests import Request

import main
from fetchy import config, engine, formats, jobs, playlist, urls, utils, video
from fetchy.errors import FriendlyError, friendly_ytdlp_error
from fetchy.web.security import is_local_request

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
    with pytest.raises(FriendlyError):
        urls.normalize_url(url)


def test_normalize_url_keeps_query_and_drops_fragment():
    assert urls.normalize_url(" https://Example.com/watch?v=1#t=3 ") == "https://example.com/watch?v=1"


def test_private_ip_is_blocked():
    with pytest.raises(FriendlyError):
        urls.host_is_public("10.0.0.1")
    with pytest.raises(FriendlyError):
        urls.host_is_public("127.0.0.1")


# --- Names and errors -------------------------------------------------------

def test_sanitize_filename():
    assert utils.sanitize_filename('a/b:c*?.mp4', extension=".mp4") == "a b c.mp4"
    assert utils.sanitize_filename("CON", extension=".mp4") == "download-CON.mp4"
    assert utils.sanitize_filename("", fallback="video") == "video"
    assert len(utils.sanitize_filename("x" * 500, extension=".mp4", max_length=95)) == 95


def test_friendly_errors():
    assert "HTTP 404" in friendly_ytdlp_error(Exception("ERROR: HTTP Error 404: Not Found"))
    assert '"not found"' in friendly_ytdlp_error(Exception("HTTP Error 404"))
    assert "private" in friendly_ytdlp_error(Exception("Private video")).lower()
    assert friendly_ytdlp_error(Exception("")).startswith("Couldn't download")


# --- Qualities ----------------------------------------------------------------

def test_format_selector():
    assert formats.format_selector("best") == "bestvideo*+bestaudio/best"
    assert "height<=?720" in formats.format_selector("720")
    for bad in ("99", "99999", "worst", "720;rm"):
        with pytest.raises(FriendlyError):
            formats.format_selector(bad)


def test_audio_attempts_have_plain_fallback():
    rich, plain = formats.extra_attempts("audio-m4a", None, False)
    assert plain["postprocessors"][0]["preferredcodec"] == "m4a"
    assert any(pp["key"] == "FFmpegExtractAudio" for pp in rich["postprocessors"])
    assert formats.extra_attempts("720", None, False) == [{}]


def test_build_format_options():
    info = {"formats": [
        {"format_id": "a", "vcodec": "none", "acodec": "mp4a", "filesize": 100},
        {"format_id": "v", "vcodec": "avc1", "acodec": "none", "height": 720, "filesize": 1000},
    ]}
    keys = [o["key"] for o in formats.build_format_options(info)]
    assert keys == ["best", "720", "audio", "audio-m4a"]


# --- Jobs and cleanup -------------------------------------------------------

def test_cleanup_keeps_files_of_running_jobs(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "DOWNLOADS_DIR", tmp_path)
    store = jobs.JobStore()
    running = store.create(kind="video", url="https://example.com/", state="downloading")
    old = time.time() - config.DOWNLOAD_TTL_SECONDS - 60
    keep = tmp_path / f"{running.id}.f137.mp4.part"
    stale = tmp_path / "11111111-1111-1111-1111-111111111111.mp4"
    for path in (keep, stale):
        path.write_bytes(b"x")
        os.utime(path, (old, old))
    store.cleanup()
    assert keep.exists()
    assert not stale.exists()


def test_download_video_sync_completes(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "DOWNLOADS_DIR", tmp_path)
    job = jobs.job_store.create(kind="video", url="https://example.com/v", state="downloading")
    jobs.job_store.update(job.id, video={"title": "My: video", "formats": []})

    class FakeYDL:
        def __init__(self, opts):
            self.opts = opts

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def extract_info(self, url, download):
            Path(self.opts["outtmpl"].replace("%(ext)s", "mp4")).write_bytes(b"video")
            return {"title": "Title from the download"}

    monkeypatch.setattr(engine.yt_dlp, "YoutubeDL", FakeYDL)
    video.download_video_sync(job.id, "best")
    done = jobs.job_store.get(job.id)
    assert done.state == "complete"
    assert done.download_name == "My video.mp4"

    # Details were not read before the download (best-only fallback): the file is named after the download.
    job = jobs.job_store.create(kind="video", url="https://example.com/v", state="downloading")
    jobs.job_store.update(job.id, video={"title": "", "formats": list(formats.BEST_ONLY_FORMATS), "no_info": True})
    video.download_video_sync(job.id, "best")
    assert jobs.job_store.get(job.id).download_name == "Title from the download.mp4"


def test_video_info_failure_offers_best_only(client, monkeypatch):
    async def public(url):
        return url

    def boom(url):
        raise RuntimeError("ERROR: [youtube] abc: Sign in to confirm you're not a bot")

    monkeypatch.setattr("fetchy.web.routes_video.ensure_public_url", public)
    monkeypatch.setattr("fetchy.web.routes_video.extract_video_info_sync", boom)
    r = client.post("/api/video/info", json={"url": "https://www.youtube.com/watch?v=abc"})
    assert r.status_code == 422
    body = r.json()
    assert body["error"] == "video_info_failed" and "not a bot" in body["message"]
    job = jobs.job_store.get(body["job_id"])
    assert job.state == "ready" and job.video["no_info"] is True
    assert [f["key"] for f in job.video["formats"]] == ["best"]

    started = {}
    monkeypatch.setattr("fetchy.web.routes_video.download_video_sync", lambda *a: started.update(args=a))
    assert client.post("/api/video/download", json={"job_id": job.id, "quality": "720"}).status_code == 400
    r = client.post("/api/video/download", json={"job_id": job.id, "quality": "best"})
    assert r.status_code == 200 and r.json()["state"] == "downloading"

    # The whole-playlist ZIP has no best-only fallback.
    monkeypatch.setattr("fetchy.web.routes_video.extract_playlist_info_sync", boom)
    r = client.post("/api/video/info", json={"url": "https://www.youtube.com/playlist?list=1", "playlist": True})
    assert r.status_code == 422 and "job_id" not in r.json()


# --- Local-only checks ------------------------------------------------------

def test_local_request_rules():
    assert is_local_request(make_request("127.0.0.1"))
    assert not is_local_request(make_request("8.8.8.8"))
    # A proxied request could fake its address, so it never counts as local.
    assert not is_local_request(make_request("127.0.0.1", {"X-Forwarded-For": "127.0.0.1"}))


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

    monkeypatch.setattr(engine.yt_dlp, "YoutubeDL", FakeYDL)
    run = playlist.PlaylistRun("List", {1: {"n": 1, "url": "https://example.com/1", "key": "", "title": "One",
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


# --- Cookies ------------------------------------------------------------------

def test_cookies_browser_and_file(tmp_path, monkeypatch):
    from fetchy import cookies

    monkeypatch.setattr(config, "COOKIES_FILE", tmp_path / "cookies.txt")
    monkeypatch.setattr(cookies, "_can_read", lambda name: True)
    cookies._check_cache.clear()
    with pytest.raises(FriendlyError):
        cookies.set_browser("netscape")
    with pytest.raises(FriendlyError):
        cookies.save_file("not a cookie file")

    cookies.save_file(".example.com\tTRUE\t/\tTRUE\t0\tname\tvalue\r\n")
    assert cookies.ydl_options() == {"cookiefile": str(tmp_path / "cookies.txt")}
    cookies.set_browser(" Firefox ")
    assert cookies.ydl_options() == {"cookiesfrombrowser": ("firefox",)}
    cookies.clear()
    assert cookies.status() == {"browser": "", "file": False}


def test_unreadable_browser_is_refused_and_not_stored(tmp_path, monkeypatch):
    from fetchy import cookies

    monkeypatch.setattr(config, "COOKIES_FILE", tmp_path / "cookies.txt")
    cookies.clear()
    cookies._check_cache.clear()
    monkeypatch.setattr(cookies, "_can_read", lambda name: False)
    with pytest.raises(FriendlyError) as err:
        cookies.set_browser("chrome")
    assert err.value.code == "browser_cookies_unreadable"
    assert "Chrome" in err.value.message
    assert cookies.browser() == ""


def test_browser_that_becomes_unreadable_falls_back(tmp_path, monkeypatch):
    from fetchy import cookies

    monkeypatch.setattr(config, "COOKIES_FILE", tmp_path / "cookies.txt")
    cookies.clear()
    cookies._check_cache.clear()
    monkeypatch.setattr(cookies, "_can_read", lambda name: True)
    cookies.set_browser("chrome")
    assert cookies.ydl_options() == {"cookiesfrombrowser": ("chrome",)}
    # Chrome gets locked later: downloads must not break.
    cookies._check_cache.clear()
    monkeypatch.setattr(cookies, "_can_read", lambda name: False)
    assert cookies.ydl_options() == {}
    cookies.save_file(".example.com\tTRUE\t/\tTRUE\t0\tname\tvalue\n")
    assert cookies.ydl_options() == {"cookiefile": str(tmp_path / "cookies.txt")}
    cookies.clear()


def test_single_video_options_use_youtube_clients():
    from fetchy.ydl_options import single_video_options

    a, b = single_video_options(), single_video_options()
    assert a["extractor_args"]["youtube"]["player_client"][0] == "android"
    assert a["extractor_args"] is not b["extractor_args"]   # fresh dict each time


# --- Web version: whole playlist as a ZIP -----------------------------------

def test_zip_playlist_download(tmp_path, monkeypatch):
    import zipfile

    monkeypatch.setattr(config, "DOWNLOADS_DIR", tmp_path)
    job = jobs.job_store.create(kind="video", url="https://example.com/list", state="downloading")
    jobs.job_store.update(job.id, video={"title": "My list (2 videos)", "is_playlist": True, "formats": []})
    seen = {}
    real_validate = engine.yt_dlp.YoutubeDL.validate_outtmpl

    class FakeYDL:
        validate_outtmpl = staticmethod(real_validate)   # used by yt-dlp's option parser

        def __init__(self, opts):
            seen.update(opts)
            self.opts = opts

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def extract_info(self, url, download):
            for n in (1, 2):
                path = Path(self.opts["outtmpl"].replace("%(playlist_index)03d", f"{n:03d}")
                            .replace("%(title).80s", f"v{n}").replace("%(ext)s", "mp4"))
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"x")

    monkeypatch.setattr(engine.yt_dlp, "YoutubeDL", FakeYDL)
    video.download_video_sync(job.id, "audio")
    done = jobs.job_store.get(job.id)
    assert done.state == "complete"
    assert done.download_name == "My list (2 videos).zip"
    assert seen["noplaylist"] is False and seen["ignoreerrors"] is True
    assert seen["postprocessors"][0]["key"] == "FFmpegExtractAudio"
    with zipfile.ZipFile(done.file_path) as zf:
        assert zf.namelist() == ["001 - v1.mp4", "002 - v2.mp4"]
    assert not (tmp_path / f"{job.id}_pl").exists()


def test_cleanup_keeps_running_playlist_folder(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "DOWNLOADS_DIR", tmp_path)
    store = jobs.JobStore()
    running = store.create(kind="video", url="https://example.com/", state="downloading")
    folder = tmp_path / f"{running.id}_pl"
    folder.mkdir()
    old = time.time() - config.DOWNLOAD_TTL_SECONDS - 60
    os.utime(folder, (old, old))
    store.cleanup()
    assert folder.exists()


def test_video_info_accepts_playlist_flag(client, monkeypatch):
    from fetchy import playlist_zip

    monkeypatch.setattr(urls, "host_is_public", lambda host: True)
    monkeypatch.setattr(playlist_zip, "extract_playlist_info_sync", lambda url: None)
    calls = []

    def fake(url):
        calls.append(url)
        return {"title": "L (1 videos)", "duration": None, "webpage_url": url, "uploader": "", "extractor": "",
                "thumbnail_url": None, "is_playlist": True, "formats": [{"key": "best"}]}

    import fetchy.web.routes_video as rv
    monkeypatch.setattr(rv, "extract_playlist_info_sync", fake)
    r = client.post("/api/video/info", json={"url": "https://example.com/list?list=1", "playlist": True})
    assert r.status_code == 200, r.text
    assert r.json()["video"]["is_playlist"] is True
    assert calls


def _fake_ydl(on_download):
    class FakeYDL:
        def __init__(self, opts):
            self.opts = opts

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def extract_info(self, url, download):
            on_download(self.opts)
            Path(self.opts["outtmpl"].replace("%(ext)s", "mp4")).write_bytes(b"video")
            return {"title": "t"}

    return FakeYDL


def _ready_job(state="downloading", stage="starting"):
    job = jobs.job_store.create(kind="video", url="https://example.com/v", state=state)
    jobs.job_store.update(job.id, video={"title": "v", "formats": []})
    jobs.job_store.set_progress(job.id, stage=stage)
    return job


def test_cancel_running_download_stops_only_that_one(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "DOWNLOADS_DIR", tmp_path)
    stop, keep = _ready_job(), _ready_job()

    def on_download(opts):
        if stop.id in opts["outtmpl"]:
            jobs.job_store.cancel(stop.id)   # the user presses Cancel while bytes come in
            opts["progress_hooks"][0]({"status": "downloading", "downloaded_bytes": 1, "total_bytes": 10})

    monkeypatch.setattr(engine.yt_dlp, "YoutubeDL", _fake_ydl(on_download))
    video.download_video_sync(stop.id, "best")
    video.download_video_sync(keep.id, "best")
    assert jobs.job_store.get(stop.id).state == "cancelled"
    assert not list(tmp_path.glob(f"{stop.id}*"))
    assert jobs.job_store.get(keep.id).state == "complete"


def test_cancel_queued_download_never_starts(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "DOWNLOADS_DIR", tmp_path)
    job = _ready_job(stage="queued")
    jobs.job_store.cancel(job.id)
    assert jobs.job_store.get(job.id).state == "cancelled"   # at once, no need to wait for a slot
    started = []
    monkeypatch.setattr(engine.yt_dlp, "YoutubeDL", _fake_ydl(started.append))
    video.download_video_sync(job.id, "best")
    assert not started and jobs.job_store.get(job.id).state == "cancelled"
    # A job that is not downloading cannot be cancelled (so a later download is not stopped by mistake).
    ready = _ready_job(state="ready")
    jobs.job_store.cancel(ready.id)
    assert not jobs.job_store.get(ready.id).cancel_requested


def test_download_pool_runs_a_few_at_a_time(tmp_path, monkeypatch):
    import threading

    monkeypatch.setattr(config, "DOWNLOADS_DIR", tmp_path)
    lock, running, peak = threading.Lock(), [0], [0]

    def on_download(opts):
        with lock:
            running[0] += 1
            peak[0] = max(peak[0], running[0])
        time.sleep(0.15)
        with lock:
            running[0] -= 1

    monkeypatch.setattr(engine.yt_dlp, "YoutubeDL", _fake_ydl(on_download))
    ids = [_ready_job(stage="queued").id for _ in range(config.MAX_PARALLEL_DOWNLOADS + 3)]
    futures = [video.DOWNLOAD_POOL.submit(video.download_video_sync, i, "best") for i in ids]
    for f in futures:
        f.result(timeout=10)
    assert peak[0] == config.MAX_PARALLEL_DOWNLOADS
    assert all(jobs.job_store.get(i).state == "complete" for i in ids)


def test_cancel_route(client):
    job = _ready_job(stage="queued")
    r = client.post(f"/api/video/{job.id}/cancel")
    assert r.status_code == 200 and r.json()["state"] == "cancelled"
    assert client.post("/api/video/not-a-job/cancel").status_code == 404


# --- Links Fetchy does not support ---------------------------------------------

@pytest.mark.parametrize("url, word", [
    ("https://www.instagram.com/stories/natgeo/3456789012345678901/", "Instagram stories"),
    ("https://instagram.com/stories/natgeo/", "Instagram stories"),
    ("https://www.instagram.com/stories/highlights/17900000000000000/", "Instagram stories"),
    ("https://www.instagram.com/s/aGlnaGxpZ2h0OjE3OTAw?igsh=abc", "Instagram stories"),
    ("https://www.snapchat.com/spotlight/W7_EDlXWTBiXAEEniNoMPwAAYc", "Snapchat"),
    ("https://story.snapchat.com/p/abc", "Snapchat"),
    ("https://t.snapchat.com/AbCdEf", "Snapchat"),
])
def test_unsupported_links_say_which(url, word):
    reason = urls.unsupported_reason(url)
    assert reason and reason.startswith("Not supported") and word in reason


@pytest.mark.parametrize("url", [
    "https://www.instagram.com/p/C1abc/",
    "https://www.instagram.com/reel/C1abc/",
    "https://www.youtube.com/watch?v=abc",
    "https://notsnapchat.com/x",
    "https://example.com/stories/a",
])
def test_supported_links_pass(url):
    assert urls.unsupported_reason(url) is None


def test_video_info_refuses_unsupported_link_at_once(client, monkeypatch):
    async def public(url):
        return url

    def never(url):
        raise AssertionError("must not ask yt-dlp")

    monkeypatch.setattr("fetchy.web.routes_video.ensure_public_url", public)
    monkeypatch.setattr("fetchy.web.routes_video.extract_video_info_sync", never)
    r = client.post("/api/video/info", json={"url": "https://www.snapchat.com/spotlight/abc"})
    assert r.status_code == 422
    assert r.json()["error"] == "unsupported_link"
    assert "Snapchat" in r.json()["message"]
