from redflag_backend.config import get_settings
"""Deterministic tools: RDAP, crt.sh, Safe Browsing stub/live (network mocked)."""
from datetime import datetime, timezone

import httpx
import pytest

from redflag_backend.tools import cert, rdap, safe_browsing

NOW = datetime(2026, 11, 4, tzinfo=timezone.utc)


def mock_async_client(monkeypatch, module, handler):
    real = httpx.AsyncClient

    def factory(*a, **k):
        k["transport"] = httpx.MockTransport(handler)
        return real(*a, **k)

    monkeypatch.setattr(module.httpx, "AsyncClient", factory)


def test_parse_rdap():
    doc = {"events": [{"eventAction": "last changed", "eventDate": "2026-11-01T00:00:00Z"},
                      {"eventAction": "registration", "eventDate": "2026-10-31T10:00:00Z"}],
           "entities": [{"roles": ["registrar"], "vcardArray": ["vcard", [["version", {}, "text", "4.0"],
                                                                         ["fn", {}, "text", "NameCheap, Inc."]]]}]}
    r = rdap.parse_rdap(doc, NOW)
    assert r["domain_age_days"] == 3 and r["registrar"] == "NameCheap, Inc."


async def test_rdap_bootstrap_flow(env, monkeypatch):
    rdap._BOOTSTRAP.update(at=0.0, map=None)

    def handler(req: httpx.Request):
        if req.url.host == "data.iana.org":
            return httpx.Response(200, json={"services": [[["top"], ["https://rdap.nic.top/"]]]})
        assert str(req.url) == "https://rdap.nic.top/domain/evil.top"
        return httpx.Response(200, json={"events": [{"eventAction": "registration", "eventDate": "2026-10-30T00:00:00Z"}]})

    mock_async_client(monkeypatch, rdap, handler)
    r = await rdap.rdap("www.evil.top")
    assert r["domain"] == "evil.top" and isinstance(r["domain_age_days"], int) and r["source"] == "rdap"


async def test_rdap_unknown_tld(env, monkeypatch):
    rdap._BOOTSTRAP.update(at=0.0, map=None)
    mock_async_client(monkeypatch, rdap, lambda req: httpx.Response(200, json={"services": []}))
    r = await rdap.rdap("evil.zz")
    assert r["domain_age_days"] is None and "bootstrap" in r["error"]


def test_parse_crtsh_newest_and_earliest():
    rows = [{"issuer_name": "C=US, O=Let's Encrypt, CN=R10", "not_before": "2026-11-01T00:00:00"},
            {"issuer_name": "C=GB, O=Sectigo Limited, CN=X", "not_before": "2026-09-01T00:00:00"}]
    r = cert.parse_crtsh(rows, NOW)
    assert r == {"tls_issuer": "Let's Encrypt", "cert_age_days": 3, "first_seen_days": 64, "cert_count": 2}
    assert cert.parse_crtsh([], NOW)["tls_issuer"] is None


async def test_crtsh_timeout_yields_nulls(env, monkeypatch):
    def handler(req):
        raise httpx.ReadTimeout("slow", request=req)

    mock_async_client(monkeypatch, cert, handler)
    c = await cert.cert("evil.top")
    assert c["tls_issuer"] is None and c["cert_age_days"] is None and "ReadTimeout" in c["error"]


async def test_crtsh_5xx_yields_nulls(env, monkeypatch):
    mock_async_client(monkeypatch, cert, lambda req: httpx.Response(502, text="Bad Gateway"))
    c = await cert.cert("evil.top")
    assert c["tls_issuer"] is None and "HTTPStatusError" in c["error"]


@pytest.mark.parametrize("url,expected", [
    ("https://testsafebrowsing.appspot.com/s/phishing.html", "hit"),
    ("http://TESTSAFEBROWSING.appspot.com/s/malware.html", "hit"),
    ("https://testsafebrowsing.appspot.com/", "no_match"),
    ("https://www.free-gift-claim-sg.top/claim", "hit"),
    ("https://example.com/", "no_match"),
])
async def test_safe_browsing_stub(env, url, expected):
    assert (await safe_browsing.safe_browsing(url))["result"] == expected


def _pb_varint(v):
    out = bytearray()
    while True:
        if v < 0x80:
            out.append(v)
            return bytes(out)
        out.append((v & 0x7F) | 0x80)
        v >>= 7


def _pb_ld(field, body):
    return _pb_varint((field << 3) | 2) + _pb_varint(len(body)) + body


def _pb_response(*hashes):
    """SearchHashesResponse protobuf, same layout as the live API (verified 2026-11-04)."""
    body = b"".join(_pb_ld(1, _pb_ld(1, h) + _pb_ld(2, _pb_varint(1 << 3) + _pb_varint(t))) for h, t in hashes)
    return body + _pb_ld(2, _pb_varint(1 << 3) + _pb_varint(300))


def test_v5_expressions_documented_example():
    assert set(safe_browsing.expressions("http://a.b.c/1/2.html?param=1")) == {
        "a.b.c/1/2.html?param=1", "a.b.c/1/2.html", "a.b.c/", "a.b.c/1/",
        "b.c/1/2.html?param=1", "b.c/1/2.html", "b.c/", "b.c/1/"}


async def test_safe_browsing_live_v5(env, monkeypatch):
    import hashlib
    safe_browsing._cache.clear()
    env.setenv("SAFE_BROWSING_MODE", "live")
    assert (await safe_browsing.safe_browsing("https://x.top/"))["result"] == "unknown"  # no key -> unknown
    env.setenv("GOOGLE_CLOUD_API_KEY", "k")
    target = hashlib.sha256(b"evil.example.com/login").digest()
    seen = {}

    def handler(req):
        seen.update(key=req.url.params["key"], prefixes=req.url.params.get_list("hashPrefixes"),
                    pkg=req.headers.get("x-android-package"))
        return httpx.Response(200, content=_pb_response((target, 2)), headers={"content-type": "application/x-protobuf"})

    state = {"resp": lambda: httpx.Response(200, content=_pb_response((target, 2)))}

    def routed(req):
        handler(req)
        return state["resp"]()

    mock_async_client(monkeypatch, safe_browsing, routed)
    r = await safe_browsing.safe_browsing("https://evil.example.com/login")
    assert r == {"result": "hit", "provider": "live", "threats": ["SOCIAL_ENGINEERING"]}
    assert seen["key"] == "k" and seen["pkg"] == "dev.redflag.app"
    assert seen["prefixes"] and all(len(__import__("base64").b64decode(p)) == 4 for p in seen["prefixes"])  # only 4-byte prefixes leave
    safe_browsing._cache.clear()
    state["resp"] = lambda: httpx.Response(200, content=_pb_response((bytes(32), 1)))
    assert (await safe_browsing.safe_browsing("https://evil.example.com/login"))["result"] == "no_match"
    safe_browsing._cache.clear()
    state["resp"] = lambda: httpx.Response(429, text="quota")
    assert (await safe_browsing.safe_browsing("https://evil.example.com/login"))["result"] == "unknown"


async def test_safe_browsing_auto_mode(env, monkeypatch):
    assert get_settings().safe_browsing_mode == "auto"
    assert (await safe_browsing.safe_browsing("https://testsafebrowsing.appspot.com/s/phishing.html"))["provider"] == "stub"
    env.setenv("GOOGLE_CLOUD_API_KEY", "k")
    safe_browsing._cache.clear()
    mock_async_client(monkeypatch, safe_browsing, lambda req: httpx.Response(200, content=_pb_response()))
    assert (await safe_browsing.safe_browsing("https://example.org/"))["provider"] == "live"


async def test_safe_browsing_live_real_google(env):
    """Real network call with the key from ../.env (skipped when absent; the key is never printed)."""
    import pathlib
    f = pathlib.Path(__file__).resolve().parents[2] / ".env"
    key = None
    if f.exists():
        for line in f.read_text().splitlines():
            line = line.strip().removeprefix("export ").strip()
            if line.replace(" ", "").startswith("GOOGLE_CLOUD_API_KEY="):
                key = line.split("=", 1)[1].strip().strip("\"'")
    if not key:
        pytest.skip("no GOOGLE_CLOUD_API_KEY in .env")
    safe_browsing._cache.clear()
    hit = await safe_browsing.check_live("https://testsafebrowsing.appspot.com/s/phishing.html", key,
                                         android_package="dev.redflag.app", android_cert="AA64D8EE3EACA2FFFCCBE05579FADCA47F0272C0")
    clean = await safe_browsing.check_live("https://www.google.com/", key, android_package="dev.redflag.app",
                                           android_cert="AA64D8EE3EACA2FFFCCBE05579FADCA47F0272C0")
    assert hit["result"] == "hit" and "SOCIAL_ENGINEERING" in hit["threats"], hit
    assert clean["result"] == "no_match", clean

def test_browser_error_page_is_a_load_failure():
    """DNS failure etc.: Chromium shows chrome-error://, which must not be analysed as the site (D3)."""
    from redflag_backend.tools import sandbox_browse as sb
    raw = {"ok": True, "final_url": "chrome-error://chromewebdata/", "navigation_error": "net::ERR_NAME_NOT_RESOLVED",
           "redirect_chain": [], "title": "", "visible_text": "", "hidden_text": "", "forms": [], "link_urls": [], "script_urls": []}
    o = sb.normalize(raw, "https://gone.example/", "https://gone.example/")
    assert o["ok"] is False and "ERR_NAME_NOT_RESOLVED" in o["error"]
