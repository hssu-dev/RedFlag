"""DEMO_MODE=1 only: fake demo domains -> local demo-pages + signal overrides.

contracts/demo_hosts.json is read ONLY when get_settings().demo_mode is True, i.e. when the
DEMO_MODE env var is exactly "1". With DEMO_MODE unset every function here is a no-op
(returns None / the input unchanged) and the file is never opened. The fake domains are
never contacted: browsing goes to base_urls[DEMO_BASE] + path instead.
"""
from __future__ import annotations

import json
from urllib.parse import urlsplit, urlunsplit

from . import contracts, psl
from .config import get_settings


def _hosts_file() -> dict:
    # Deliberately not contracts.load(): no lru_cache shared with production paths.
    return json.loads((contracts.contracts_dir() / "demo_hosts.json").read_text(encoding="utf-8"))


def entry_for_url(url: str | None) -> dict | None:
    """Demo entry for this URL's host or registrable domain; None unless DEMO_MODE=1."""
    if not get_settings().demo_mode or not url:
        return None
    host = psl.normalize_host(url)
    if not host:
        return None
    hosts = _hosts_file()["hosts"]
    reg = psl.registrable_domain(host)
    entry = hosts.get(host) or (hosts.get(reg) if reg else None)
    if entry is None:
        return None
    return {**entry, "host": host if host in hosts else reg}


def base_url() -> str | None:
    s = get_settings()
    if not s.demo_mode:
        return None
    return _hosts_file()["base_urls"][s.demo_base].rstrip("/")


def fetch_url(url: str) -> str | None:
    """The local URL the sandbox should load instead of a fake demo domain (or None)."""
    entry = entry_for_url(url)
    if entry is None:
        return None
    return base_url() + entry["path"]


def signal_overrides(domain_or_url: str) -> dict | None:
    """RDAP/cert replacement for a demo domain (or None)."""
    url = domain_or_url if "://" in domain_or_url else f"https://{domain_or_url}/"
    entry = entry_for_url(url)
    return dict(entry["signal_overrides"]) if entry else None


def unmap(url: str | None, original_url: str, fetched_url: str) -> str | None:
    """Map a URL observed on the local demo server back onto the fake domain."""
    if not url or not get_settings().demo_mode:
        return url
    base = base_url()
    if base is None:
        return url
    b = urlsplit(base)
    u = urlsplit(url)
    if (u.scheme, u.netloc) != (b.scheme, b.netloc):
        return url
    if url.rstrip("/") == fetched_url.rstrip("/"):
        return original_url
    o = urlsplit(original_url)
    return urlunsplit((o.scheme, o.netloc, u.path, u.query, u.fragment))
