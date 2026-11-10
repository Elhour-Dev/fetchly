"""In-memory download jobs and cleanup of old files."""

from __future__ import annotations

import shutil
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

from . import config
from .config import logger
from .utils import iso, utc_now

TERMINAL_STATES = {"complete", "error"}
_BUSY_STATES = {"downloading", "working", "fetching_info", "processing"}


def _empty_progress() -> dict[str, Any]:
    return {
        "stage": "idle",
        "percent": 0.0,
        "downloaded_bytes": 0,
        "total_bytes": None,
        "speed": None,
        "eta": None,
    }


@dataclass
class Job:
    id: str
    kind: str
    url: str
    state: str = "working"
    created_at: datetime = field(default_factory=utc_now)
    updated_at: datetime = field(default_factory=utc_now)
    completed_at: datetime | None = None
    expires_at: datetime | None = None
    error: str | None = None
    file_path: Path | None = None
    download_name: str | None = None
    progress: dict[str, Any] = field(default_factory=_empty_progress)
    video: dict[str, Any] | None = None
    thumbnail_url: str | None = None

    def public_status(self) -> dict[str, Any]:
        return {
            "job_id": self.id,
            "kind": self.kind,
            "state": self.state,
            "progress": dict(self.progress),
            "error": self.error,
            "download_url": f"/api/download/{self.id}" if self.state == "complete" and self.file_path else None,
            "file_name": self.download_name,
            "expires_at": iso(self.expires_at),
        }


class JobStore:
    def __init__(self) -> None:
        self._jobs: dict[str, Job] = {}
        self._lock = threading.RLock()

    def create(self, *, kind: str, url: str, state: str = "working") -> Job:
        job = Job(id=str(uuid.uuid4()), kind=kind, url=url, state=state)
        with self._lock:
            self._jobs[job.id] = job
        return job

    def get(self, job_id: str) -> Job | None:
        with self._lock:
            return self._jobs.get(job_id)

    def update(self, job_id: str, **fields: Any) -> Job | None:
        with self._lock:
            job = self._jobs.get(job_id)
            if not job:
                return None
            for key, value in fields.items():
                setattr(job, key, value)
            job.updated_at = utc_now()
            return job

    def set_progress(self, job_id: str, **values: Any) -> None:
        with self._lock:
            job = self._jobs.get(job_id)
            if not job:
                return
            job.progress.update(values)
            job.updated_at = utc_now()

    def complete(self, job_id: str, *, file_path: Path, download_name: str) -> Job | None:
        now = utc_now()
        with self._lock:
            job = self._jobs.get(job_id)
            if not job:
                return None
            job.state = "complete"
            job.file_path = file_path
            job.download_name = download_name
            job.error = None
            job.completed_at = now
            job.expires_at = now + timedelta(seconds=config.DOWNLOAD_TTL_SECONDS)
            job.progress.update({"stage": "complete", "percent": 100.0, "speed": None, "eta": None})
            job.updated_at = now
            return job

    def fail(self, job_id: str, message: str) -> Job | None:
        now = utc_now()
        with self._lock:
            job = self._jobs.get(job_id)
            if not job:
                return None
            job.state = "error"
            job.error = message
            job.completed_at = now
            job.expires_at = now + timedelta(seconds=config.DOWNLOAD_TTL_SECONDS)
            job.updated_at = now
            return job

    def status(self, job_id: str) -> dict[str, Any] | None:
        with self._lock:
            job = self._jobs.get(job_id)
            return job.public_status() if job else None

    def cleanup(self) -> int:
        """Remove expired jobs and on-disk files older than the TTL."""
        now = utc_now()
        removed_jobs = 0
        with self._lock:
            for job_id, job in list(self._jobs.items()):
                if job.state in _BUSY_STATES:
                    continue
                expiry = job.expires_at
                if expiry is None:
                    expiry = (job.completed_at or job.created_at) + timedelta(seconds=config.DOWNLOAD_TTL_SECONDS)
                    job.expires_at = expiry
                if expiry <= now:
                    self._jobs.pop(job_id, None)
                    removed_jobs += 1
            active_ids = {job.id for job in self._jobs.values() if job.state not in TERMINAL_STATES}

        removed_files = _remove_old_files(config.DOWNLOADS_DIR, active_ids)
        if removed_jobs or removed_files:
            logger.info("Cleanup removed %s jobs and %s files", removed_jobs, removed_files)
        return removed_files


def _remove_old_files(folder: Path, active_ids: set[str]) -> int:
    removed = 0
    cutoff = time.time() - config.DOWNLOAD_TTL_SECONDS
    if not folder.exists():
        return 0
    for entry in folder.iterdir():
        if entry.name == ".gitkeep":
            continue
        # Files of a running job ("<id>.f137.mp4.part", "<id>.mp4"...) are never removed.
        if entry.name.split(".", 1)[0] in active_ids:
            continue
        try:
            if entry.stat().st_mtime >= cutoff:
                continue
            if entry.is_dir():
                shutil.rmtree(entry, ignore_errors=True)
            else:
                entry.unlink(missing_ok=True)
            removed += 1
        except OSError:
            logger.debug("Could not clean up %s", entry, exc_info=True)
    return removed


job_store = JobStore()
