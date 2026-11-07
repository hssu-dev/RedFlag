"""Safe Browsing behind one interface (twin of android/core SafeBrowsingV5.kt).

mode (SAFE_BROWSING_MODE): auto (default) = live when a key is configured, else stub | live | stub.
live: Google Safe Browsing v5 hashes.search — only 4-byte SHA-256 prefixes of the URL's
      host-suffix/path-prefix expressions are sent; full hashes are compared here. The endpoint answers
      in protobuf only (JSON output is rejected with "Unsupported Output Format").
stub: contracts/gate_lists.json safe_browsing_stub (Google's official test pages + demo hosts).
In DEMO_MODE the demo-domain stub hits are added on top of live (they can only ADD hits).
Result: {"result": hit|no_match|unknown, "provider": ..., "threats": [...]}; failures are "unknown".
"""
from __future__ import annotations

import base64
import hashlib
import ipaddress
import re
import time
from urllib.parse import quote, urlsplit

import httpx

from .. import contracts, psl
from ..config import get_settings

ENDPOINT = "https://safebrowsing.googleapis.com/v5/hashes:search"
THREAT_NAMES = {1: "MALWARE", 2: "SOCIAL_ENGINEERING", 3: "UNWANTED_SOFTWARE", 4: "POTENTIALLY_HARMFUL_APPLICATION"}
_cache: dict[str, tuple[float, dict[str, str]]] = {}  # prefix b64 -> (expiry, {full_hash_b64: threat})


# ---------------------------------------------------------------- stub

def _stub_form(url: str) -> str | None:
    host = psl.normalize_host(url)
    if not host:
        return None
    s = url if "://" in url else "https://" + url
    parts = urlsplit(s)
    tail = parts.path or "/"
    if parts.query:
        tail += "?" + parts.query
    return host + tail


def check_stub(url: str, domains_only: bool = False) -> dict:
    stub = contracts.gate_lists()["safe_browsing_stub"]
    norm = _stub_form(url)
    if norm is None:
        return {"result": "unknown", "provider": "stub", "threats": []}
    reg = psl.registrable_domain(psl.normalize_host(url))
    hit = (not domains_only and any(norm.startswith(p) for p in stub["url_prefixes"])) or \
        (reg is not None and reg in stub["domains"])
    return {"result": "hit" if hit else "no_match", "provider": "stub",
            "threats": ["SOCIAL_ENGINEERING"] if hit else []}


# ---------------------------------------------------------------- v5 expressions / hashing

def _is_ip(host: str) -> bool:
    try:
        ipaddress.ip_address(host.strip("[]"))
        return True
    except ValueError:
        return bool(re.fullmatch(r"\d+", host))


def canonical_path(p: str) -> str:
    raw = p or "/"
    out: list[str] = []
    for seg in raw.split("/")[1:]:
        if seg in ("", "."):
            continue
        if seg == "..":
            if out:
                out.pop()
            continue
        out.append(seg)
    trailing = raw.endswith("/") or raw.endswith("/.") or raw.endswith("/..")
    joined = "/" + "/".join(out)
    return joined + "/" if trailing and joined != "/" else joined


def expressions(url: str) -> list[str]:
    host = (psl.normalize_host(url) or "").strip(".")
    host = re.sub(r"\.{2,}", ".", host)
    s = url if "://" in url else "https://" + url
    parts = urlsplit(s)
    hosts = [host]
    if not _is_ip(host):
        labels = host.split(".")
        for i in range(max(1, len(labels) - 5), len(labels) - 1):
            hosts.append(".".join(labels[i:]))
    path = canonical_path(parts.path)
    paths = []
    if parts.query:
        paths.append(f"{path}?{parts.query}")
    paths += [path, "/"]
    comps = [c for c in path.strip("/").split("/") if c]
    dirs = comps if path.endswith("/") else comps[:-1]
    acc = "/"
    for c in dirs[:3]:
        acc += c + "/"
        paths.append(acc)
    seen, out = set(), []
    for h in dict.fromkeys(hosts):
        for p in dict.fromkeys(paths):
            e = h + p
            if e not in seen:
                seen.add(e)
                out.append(e)
    return out


# ---------------------------------------------------------------- protobuf (SearchHashesResponse)

def _varint(b: bytes, i: int) -> tuple[int, int]:
    shift = r = 0
    while True:
        x = b[i]
        i += 1
        r |= (x & 0x7F) << shift
        if x < 0x80:
            return r, i
        shift += 7
        if shift >= 64:
            raise ValueError("varint too long")


def _fields(b: bytes):
    i = 0
    while i < len(b):
        key, i = _varint(b, i)
        f, w = key >> 3, key & 7
        if w == 0:
            v, i = _varint(b, i)
            yield f, w, v
        elif w == 2:
            n, i = _varint(b, i)
            if i + n > len(b):
                raise ValueError("bad length")
            yield f, w, b[i:i + n]
            i += n
        elif w == 1:
            i += 8
        elif w == 5:
            i += 4
        else:
            raise ValueError(f"wire type {w}")


def parse_response(body: bytes) -> tuple[float, dict[str, str]]:
    """-> (cache seconds, {full_hash_b64: threat name}) for blocking threat types only."""
    dur, out = 300.0, {}
    for f, w, v in _fields(body):
        if f == 1 and w == 2:
            full, threat = None, None
            for f2, w2, v2 in _fields(v):
                if f2 == 1 and w2 == 2:
                    full = v2
                elif f2 == 2 and w2 == 2:
                    for f3, w3, v3 in _fields(v2):
                        if f3 == 1 and w3 == 0 and v3 in THREAT_NAMES and threat is None:
                            threat = THREAT_NAMES[v3]
            if full is not None and len(full) == 32 and threat:
                out[base64.b64encode(full).decode()] = threat
        elif f == 2 and w == 2:
            for f2, w2, v2 in _fields(v):
                if f2 == 1 and w2 == 0:
                    dur = float(v2)
    return dur, out


async def check_live(url: str, api_key: str, timeout: float = 5.0, android_package: str | None = None,
                     android_cert: str | None = None) -> dict:
    exprs = expressions(url)
    full = {e: hashlib.sha256(e.encode()).digest() for e in exprs}
    full_b64 = {base64.b64encode(h).decode() for h in full.values()}
    prefixes = list(dict.fromkeys(base64.b64encode(h[:4]).decode() for h in full.values()))
    now = time.time()
    missing = [p for p in prefixes if not (p in _cache and _cache[p][0] > now)]
    if missing:
        q = "&".join("hashPrefixes=" + quote(p, safe="") for p in missing)
        headers = {}
        if android_package:
            headers["X-Android-Package"] = android_package
        if android_cert:
            headers["X-Android-Cert"] = android_cert
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                # build the whole query by hand: httpx `params=` REPLACES an existing query string, which
                # silently dropped every hashPrefixes (empty answer = false "no match")
                r = await client.get(f"{ENDPOINT}?{q}&key={quote(api_key, safe='')}", headers=headers)
            if r.status_code != 200:
                return {"result": "unknown", "provider": "live", "threats": [], "error": f"HTTP {r.status_code}"}
            dur, hashes = parse_response(r.content)
        except Exception as e:  # network / parse error -> unknown, never a false hit or a false "clean"
            return {"result": "unknown", "provider": "live", "threats": [], "error": type(e).__name__}
        for p in missing:
            _cache[p] = (now + dur, {h: t for h, t in hashes.items()
                                     if base64.b64encode(base64.b64decode(h)[:4]).decode() == p})
    threats = sorted({t for p in prefixes for h, t in (_cache.get(p, (0, {}))[1]).items() if h in full_b64})
    return {"result": "hit" if threats else "no_match", "provider": "live", "threats": threats}


async def safe_browsing(url: str) -> dict:
    s = get_settings()
    mode = s.safe_browsing_mode
    if mode == "auto":
        mode = "live" if s.safe_browsing_api_key else "stub"
    if mode == "live":
        if not s.safe_browsing_api_key:
            return {"result": "unknown", "provider": "live", "threats": [], "error": "no API key"}
        if s.demo_mode and check_stub(url, domains_only=True)["result"] == "hit":
            return {"result": "hit", "provider": "stub(demo)", "threats": ["SOCIAL_ENGINEERING"]}
        return await check_live(url, s.safe_browsing_api_key, android_package=s.safe_browsing_android_package,
                                android_cert=s.safe_browsing_android_cert)
    return check_stub(url)
