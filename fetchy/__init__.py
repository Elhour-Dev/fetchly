"""Fetchy - a small, self-hosted video downloader.

The backend keeps all state in memory. It is made for a single-user local
deployment: jobs are UUID-addressed, generated files expire after an hour, and
every URL is checked against private/link-local address ranges before the
server makes a request.
"""
