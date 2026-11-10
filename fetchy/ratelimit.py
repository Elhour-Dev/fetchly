"""Simple in-memory sliding-window rate limits per client address."""

from __future__ import annotations

import threading
import time
from collections import defaultdict, deque

from fastapi import HTTPException, Request


class SlidingWindowLimiter:
    def __init__(self, limit: int, window_seconds: int) -> None:
        self.limit = limit
        self.window = window_seconds
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def hit(self, key: str) -> int | None:
        """Return retry-after seconds when limited, otherwise None."""
        now = time.monotonic()
        cutoff = now - self.window
        with self._lock:
            bucket = self._hits[key]
            while bucket and bucket[0] <= cutoff:
                bucket.popleft()
            if len(bucket) >= self.limit:
                return max(1, int(self.window - (now - bucket[0])) + 1)
            bucket.append(now)
            if len(self._hits) > 4096:
                for stale_key in [k for k, v in self._hits.items() if not v]:
                    self._hits.pop(stale_key, None)
            return None


_LIMITERS: dict[str, SlidingWindowLimiter] = {}
_LIMITERS_LOCK = threading.Lock()


def rate_limit(scope: str, limit: int, window_seconds: int = 60):
    """FastAPI dependency: at most `limit` calls per client in the window."""
    with _LIMITERS_LOCK:
        limiter = _LIMITERS.setdefault(scope, SlidingWindowLimiter(limit, window_seconds))

    async def dependency(request: Request) -> None:
        client = request.client.host if request.client else "unknown"
        retry_after = limiter.hit(client)
        if retry_after is not None:
            raise HTTPException(
                status_code=429,
                detail="Too many requests. Please wait a moment and try again.",
                headers={"Retry-After": str(retry_after)},
            )

    return dependency
