"""Keeps references to background tasks so they are not garbage-collected."""

from __future__ import annotations

import asyncio
from typing import Any

_background_tasks: set[asyncio.Task[Any]] = set()


def spawn(coro) -> asyncio.Task[Any]:
    task = asyncio.create_task(coro)
    _background_tasks.add(task)
    task.add_done_callback(_background_tasks.discard)
    return task


def cancel_background_tasks() -> None:
    for task in list(_background_tasks):
        task.cancel()
