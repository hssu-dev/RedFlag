"""DEMO_MODE isolation: demo hosts are only rewritten when DEMO_MODE is exactly "1"."""
import pytest

from redflag_backend import demo
from redflag_backend.tools import sandbox_browse as sb

DEMO_URL = "https://dbs-secure-verify.top/login?ref=sms"


@pytest.mark.parametrize("value", [None, "", "0", "true", "yes", "1 "])
def test_demo_hosts_not_rewritten_without_demo_mode(env, monkeypatch, value):
    if value is not None:
        env.setenv("DEMO_MODE", value)

    def boom():
        raise AssertionError("demo_hosts.json must not be read when DEMO_MODE is off")

    monkeypatch.setattr(demo, "_hosts_file", boom)
    assert demo.fetch_url(DEMO_URL) is None
    assert demo.entry_for_url(DEMO_URL) is None
    assert demo.signal_overrides("dbs-secure-verify.top") is None
    assert demo.base_url() is None
    assert demo.unmap("http://demo-pages/dbs/", DEMO_URL, "http://demo-pages/dbs/") == "http://demo-pages/dbs/"


async def test_rdap_and_cert_do_not_use_overrides_without_demo_mode(env, monkeypatch):
    from redflag_backend.tools import cert, rdap

    monkeypatch.setattr(demo, "_hosts_file", lambda: (_ for _ in ()).throw(AssertionError("read")))

    class NoNet:
        def __init__(self, *a, **k):
            pass

        async def __aenter__(self):
            raise ConnectionError("network disabled in test")

        async def __aexit__(self, *a):
            return False

    monkeypatch.setattr(rdap.httpx, "AsyncClient", NoNet)
    r = await rdap.rdap("dbs-secure-verify.top")
    c = await cert.cert("dbs-secure-verify.top")
    assert r["source"] == "rdap" and r["domain_age_days"] is None and "error" in r
    assert c["source"] == "crt.sh" and c["tls_issuer"] is None


def test_demo_mode_rewrites_and_overrides(env):
    env.setenv("DEMO_MODE", "1")
    assert demo.fetch_url(DEMO_URL) == "http://demo-pages/dbs/"
    assert demo.fetch_url("https://www.dbs-secure-verify.top/") == "http://demo-pages/dbs/"  # by registrable domain
    assert demo.fetch_url("https://example.com/") is None
    assert demo.signal_overrides("dbs-secure-verify.top")["domain_age_days"] == 4
    env.setenv("DEMO_BASE", "host")
    assert demo.fetch_url(DEMO_URL) == "http://localhost:8081/dbs/"


def test_demo_observation_is_unmapped_before_psl(env):
    env.setenv("DEMO_MODE", "1")
    raw = {"ok": True, "final_url": "http://demo-pages/dbs/", "redirect_chain": ["http://demo-pages/dbs/"],
           "title": "DBS", "visible_text": "", "hidden_text": "",
           "forms": [{"action": "https://api-collect.click/collect", "method": "post", "fields": [{"type": "password"}]},
                     {"action": "http://demo-pages/dbs/otp", "method": "post", "fields": [{"type": "text"}]}],
           "link_urls": ["http://demo-pages/dbs/help", "https://www.dbs.com.sg/"], "script_urls": []}
    o = sb.normalize(raw, DEMO_URL, "http://demo-pages/dbs/")
    assert o["final_url"] == DEMO_URL and o["registrable_domain"] == "dbs-secure-verify.top"
    assert o["redirect_chain"] == [DEMO_URL] and o["redirect_count"] == 0
    assert o["forms"][0]["action_domain"] == "api-collect.click" and o["forms"][0]["same_site"] is False
    assert o["forms"][1]["action"] == "https://dbs-secure-verify.top/dbs/otp" and o["forms"][1]["same_site"] is True
    assert o["external_link_domains"] == ["dbs.com.sg"]
