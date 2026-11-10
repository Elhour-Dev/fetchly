"""Fetchy entry point.

Run locally:      python main.py
Run with uvicorn: uvicorn main:app
The code lives in the `fetchy` package.
"""

from fetchy.config import HOST, PORT
from fetchy.web.app import app

__all__ = ["app"]

if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host=HOST, port=PORT, reload=False)
