"""SQLite state: analysis cache (TTL), daily Opus budget, persistent count of REAL Anthropic requests.

One small file (CACHE_PATH, default backend/data/redflag.sqlite3, gitignored). Every real
Messages API request is recorded in `api_requests` BEFORE it is sent, so the count includes
requests that later fail or time out. The SDK client runs with max_retries=0, so one row =
one HTTP request.
"""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

from . import psl

_SCHEMA = """
CREATE TABLE IF NOT EXISTS cache (
  key TEXT PRIMARY KEY,
  created_at REAL NOT NULL,
  payload TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS api_requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts REAL NOT NULL,
  day TEXT NOT NULL,
  model TEXT NOT NULL,
  purpose TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS api_requests_day ON api_requests(day);
"""


def cache_key(url: str) -> str:
    """Final-URL cache key: normalized host + path + query (no fragment, no scheme case)."""
    from urllib.parse import urlsplit

    s = url if "://" in url else "https://" + url
    parts = urlsplit(s)
    host = psl.normalize_host(s) or parts.netloc.lower()
    path = parts.path or "/"
    return f"{parts.scheme.lower()}://{host}{path}" + (f"?{parts.query}" if parts.query else "")


def _today() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


class Store:
    def __init__(self, path: Path, ttl_s: int):
        self.path = Path(path)
        self.ttl_s = ttl_s
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        with self._conn() as c:
            c.executescript(_SCHEMA)

    def _conn(self) -> sqlite3.Connection:
        return sqlite3.connect(self.path, timeout=10)

    # ---------------------------------------------------------------- cache
    def get(self, url: str) -> tuple[dict, float] | None:
        """(payload, age_s) if a fresh entry exists for this URL."""
        with self._lock, self._conn() as c:
            row = c.execute("SELECT created_at, payload FROM cache WHERE key=?", (cache_key(url),)).fetchone()
        if not row:
            return None
        age = time.time() - row[0]
        if age > self.ttl_s:
            return None
        return json.loads(row[1]), age

    def put(self, urls: list[str], payload: dict) -> None:
        now, blob = time.time(), json.dumps(payload, ensure_ascii=False)
        with self._lock, self._conn() as c:
            for u in {cache_key(u) for u in urls if u}:
                c.execute("INSERT OR REPLACE INTO cache(key, created_at, payload) VALUES(?,?,?)", (u, now, blob))

    def purge_expired(self) -> int:
        with self._lock, self._conn() as c:
            return c.execute("DELETE FROM cache WHERE created_at < ?", (time.time() - self.ttl_s,)).rowcount

    # ---------------------------------------------------------------- Opus budget / request counter
    def record_api_request(self, model: str, purpose: str = "analyze") -> int:
        with self._lock, self._conn() as c:
            c.execute("INSERT INTO api_requests(ts, day, model, purpose) VALUES(?,?,?,?)",
                      (time.time(), _today(), model, purpose))
            return c.execute("SELECT COUNT(*) FROM api_requests").fetchone()[0]

    def api_requests_today(self) -> int:
        with self._lock, self._conn() as c:
            return c.execute("SELECT COUNT(*) FROM api_requests WHERE day=?", (_today(),)).fetchone()[0]

    def api_requests_total(self) -> int:
        with self._lock, self._conn() as c:
            return c.execute("SELECT COUNT(*) FROM api_requests").fetchone()[0]
