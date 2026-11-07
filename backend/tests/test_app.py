"""API + pipeline: SSE event order, schema-valid reports, cache, budget, fallbacks, cloaking.

Sandbox/RDAP/crt.sh/Safe Browsing are replaced with canned results; Anthropic is the fake.
"""
import asyncio
import json
import socket
import threading
import time

import httpx
import pytest
import uvicorn

from fakes import FakeClient, VALID_FEATURES, msg, submit, thinking, web_search
from redflag_backend import pipeline, report
from redflag_backend.app import create_app

URL = "https://dbs-secure-verify.top/login?ref=sms"

OBS = {
    "ok": True, "requested_url": URL, "final_url": URL, "registrable_domain": "dbs-secure-verify.top",
    "status": 200, "redirect_chain": [URL], "redirect_count": 0, "title": "DBS digibank | Login", "lang": "en",
    "visible_text": "Log in to DBS digibank. Your account will be suspended. © DBS Bank Ltd. All rights reserved.",
    "hidden_text": "", "forms": [{"action": "https://api-collect.click/c", "method": "post",
                                  "action_domain": "api-collect.click", "same_site": False,
                                  "fields": [{"type": "text", "label": "User ID"}, {"type": "password", "label": "PIN"}]}],
    "external_link_domains": ["dbs.com.sg"], "external_script_domains": [], "meta": {}, "counts": {},
    "tls": None, "screenshot_jpeg_b64": "/9j/AA==", "downloads_attempted": False, "navigation_error": None,
    "timing": {}, "demo_fetch": None,
}


@pytest.fixture
def tools(monkeypatch):
    state = {"obs": dict(OBS), "sb": "no_match", "browse_calls": 0}

    async def browse(url):
        state["browse_calls"] += 1
        return dict(state["obs"])

    async def sb(url):
        return {"result": state["sb"], "provider": "stub", "threats": []}

    async def rd(domain):
        return {"domain": domain, "domain_age_days": 4, "registration_date": None, "registrar": None, "source": "rdap"}

    async def ce(domain):
        return {"domain": domain, "tls_issuer": "Let's Encrypt", "cert_age_days": 3, "first_seen_days": 3, "source": "crt.sh"}

    monkeypatch.setattr(pipeline.browse_tool, "sandbox_browse", browse)
    monkeypatch.setattr(pipeline.sb_tool, "safe_browsing", sb)
    monkeypatch.setattr(pipeline.rdap_tool, "rdap", rd)
    monkeypatch.setattr(pipeline.cert_tool, "cert", ce)
    return state


def parse_sse(text: str) -> list[tuple[str, dict]]:
    events = []
    for frame in text.split("\n\n"):
        lines = [l for l in frame.splitlines() if l and not l.startswith(":")]
        if not lines:
            continue
        name = next(l[7:] for l in lines if l.startswith("event: "))
        data = json.loads("".join(l[6:] for l in lines if l.startswith("data: ")))
        events.append((name, data))
    return events


async def post(app, body):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as c:
        r = await c.post("/v1/analyze", json=body)
        assert r.status_code == 200
        assert r.headers["content-type"].startswith("text/event-stream")
        return parse_sse(r.text)


def happy_script():
    return [msg([thinking(), *web_search("DBS Bank official website")], "pause_turn"),
            msg([submit()], "tool_use")]


async def test_sse_order_and_schema_valid_report(env, store, tools):
    fake = FakeClient(happy_script())
    app = create_app(client_factory=lambda: fake, store=store)
    events = await post(app, {"url": URL, "trigger": "user", "locale": "ko"})
    names = [n for n, _ in events]
    assert names[-1] == "report" and set(names[:-1]) == {"trace"}
    steps = [d["step"] for n, d in events if n == "trace"]
    assert steps[0] == "start" and steps[1] == "cache"
    assert {"sandbox_browse", "rdap", "cert", "safe_browsing", "opus", "web_search", "submit_report"} <= set(steps)
    assert steps[-1] == "scoring"
    t = [d["t_ms"] for n, d in events if n == "trace"]
    assert t == sorted(t)
    rep = events[-1][1]
    report.validate_report(rep)
    assert rep["mode"] == "cloud" and rep["status"] == "complete"
    # scoring_rules v2: DBS impersonation that asks for a password also fires brand_mismatch_sensitive_request
    # (40 + 25 + 20 + 20 + 10 = 115 -> 100)
    assert rep["score"] == 100 and rep["level"] == "critical"
    assert [f["rule_id"] for f in rep["red_flags"]] == ["brand_mismatch_known_brand", "new_domain",
                                                         "brand_mismatch_sensitive_request",
                                                         "sensitive_form_cross_site", "urgency_or_threat"]
    assert all(f["source"] in ("opus+rule", "signal+rule") for f in rep["red_flags"])
    assert rep["brand"] == {"claimed": "DBS", "official_domains": ["dbs.com.sg", "dbs.com", "posb.com.sg"], "matches": False}
    assert rep["signals"]["forms"] == [{"action_domain": "api-collect.click", "same_site": False, "sensitive_fields": ["password", "secret"]}]
    assert rep["features"] == VALID_FEATURES
    assert rep["trace"] == [d for n, d in events if n == "trace"]
    assert rep["engine"]["model"] == "claude-opus-5-5"
    assert store.api_requests_total() == 2


async def test_cache_hit_skips_sandbox_and_opus(env, store, tools):
    fake = FakeClient(happy_script())
    app = create_app(client_factory=lambda: fake, store=store)
    first = await post(app, {"url": URL})
    second = await post(app, {"url": URL + "#frag"})
    assert len(fake.calls) == 2 and tools["browse_calls"] == 1
    cache_steps = [d["detail"] for n, d in second if n == "trace" and d["step"] == "cache"]
    assert cache_steps and cache_steps[0].startswith("hit")
    r1, r2 = first[-1][1], second[-1][1]
    assert (r1["score"], r1["level"]) == (r2["score"], r2["level"]) and r1["id"] != r2["id"]
    report.validate_report(r2)


async def test_cache_expired_after_ttl(env, store, tools):
    store.ttl_s = 0
    fake = FakeClient(happy_script() + happy_script())
    app = create_app(client_factory=lambda: fake, store=store)
    await post(app, {"url": URL})
    time.sleep(0.01)
    await post(app, {"url": URL})
    assert tools["browse_calls"] == 2 and len(fake.calls) == 4


async def test_budget_exceeded_scores_signals_only(env, store, tools):
    env.setenv("REDFLAG_DAILY_OPUS_CAP", "0")
    fake = FakeClient([])
    app = create_app(client_factory=lambda: fake, store=store)
    events = await post(app, {"url": URL})
    rep = events[-1][1]
    report.validate_report(rep)
    assert rep["status"] == "error" and rep["features"] is None and fake.calls == []
    # D3: status error -> analysis_incomplete clamp (never safe); 45 is already >= caution so the score is unchanged
    assert [f["rule_id"] for f in rep["red_flags"]] == ["new_domain", "sensitive_form_cross_site", "analysis_incomplete"]
    assert any(d["step"] == "budget" for n, d in events if n == "trace")
    assert store.get(URL) is None  # non-complete results are not cached


async def test_timeout_fallback_report(env, store, tools):
    env.setenv("REDFLAG_WALL_CLOCK_S", "2.2")
    fake = FakeClient([msg([submit()], "tool_use")], delay=4)
    app = create_app(client_factory=lambda: fake, store=store)
    events = await post(app, {"url": URL})
    rep = events[-1][1]
    report.validate_report(rep)
    assert rep["status"] == "timeout" and rep["features"] is None and rep["score"] == 45
    assert any(d["step"] == "timeout" for n, d in events if n == "trace")


async def test_safe_browsing_hit_skips_opus(env, store, tools):
    tools["sb"] = "hit"
    fake = FakeClient([])
    app = create_app(client_factory=lambda: fake, store=store)
    rep = (await post(app, {"url": URL}))[-1][1]
    report.validate_report(rep)
    assert rep["score"] >= 95 and rep["red_flags"][0]["rule_id"] == "safe_browsing_hit" and fake.calls == []


async def test_cloaking_detected_and_scored(env, store, tools):
    tools["obs"] = dict(OBS, title="Delivery tips", visible_text="Five tips to keep parcels safe.", forms=[])
    harmless = dict(VALID_FEATURES, claimed_brand=None, brand_matches_domain="no_brand", page_type="info",
                    requested_actions=["none"], urgency_or_threat=False, verdict="safe", confidence="medium",
                    form_posts_cross_site=False, official_domain_found=None, evidence=["Five tips"])
    fake = FakeClient([msg([submit(harmless)], "tool_use")])
    app = create_app(client_factory=lambda: fake, store=store)
    events = await post(app, {"url": "https://singpost-redelivery.info/",
                              "lite_observation": {"claimed_brand": "SingPost", "has_credential_form": True,
                                                   "title": "SingPost | Redelivery"}})
    rep = events[-1][1]
    report.validate_report(rep)
    assert rep["cloaking"]["suspected"] is True and "SingPost" in rep["cloaking"]["detail"]
    assert rep["red_flags"][0]["rule_id"] == "cloaking_suspected"
    assert rep["headline"]["en"] == "This page hides its real face from scanners"
    assert any(d["step"] == "cloaking" and d["detail"].startswith("SUSPECTED") for n, d in events if n == "trace")


async def test_observed_booleans_override_model(env, store, tools):
    wrong = dict(VALID_FEATURES, form_posts_cross_site=False, downloads_attempted=True)
    fake = FakeClient([msg([submit(wrong)], "tool_use")])
    app = create_app(client_factory=lambda: fake, store=store)
    rep = (await post(app, {"url": URL}))[-1][1]
    assert rep["features"]["form_posts_cross_site"] is True and rep["features"]["downloads_attempted"] is False


async def test_bad_url_is_error_event(env, store, tools):
    app = create_app(client_factory=lambda: FakeClient([]), store=store)
    events = await post(app, {"url": "javascript:alert(1)"})
    assert events[-1][0] == "error" and events[-1][1]["code"] == "bad_request"


async def test_no_api_key_reports_error_status(env, store, tools):
    app = create_app(client_factory=None, store=store)
    rep = (await post(app, {"url": URL}))[-1][1]
    report.validate_report(rep)
    assert rep["status"] == "error"


async def test_request_validation(env, store, tools):
    app = create_app(client_factory=None, store=store)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as c:
        assert (await c.post("/v1/analyze", json={"url": URL, "trigger": "nope"})).status_code == 422
        assert (await c.post("/v1/analyze", json={"url": URL, "locale": "fr"})).status_code == 422


async def test_healthz_exposes_request_count(env, store, tools):
    store.record_api_request("claude-opus-5-5")
    app = create_app(client_factory=None, store=store)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as c:
        h = (await c.get("/healthz")).json()
    assert h["ok"] and h["anthropic_requests_total"] == 1 and h["anthropic_requests_today"] == 1
    assert h["model"] == "claude-opus-5-5" and h["demo_mode"] is False and h["anthropic_key_configured"] is False


def test_sse_flushes_incrementally(env, store, tools):
    """Real uvicorn: trace events must arrive while Opus is still 'thinking' (1.5 s fake delay)."""
    fake = FakeClient([msg([submit()], "tool_use")], delay=1.5)
    app = create_app(client_factory=lambda: fake, store=store)
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning"))
    th = threading.Thread(target=server.run, daemon=True)
    th.start()
    try:
        for _ in range(100):
            if server.started:
                break
            time.sleep(0.05)
        arrivals = []
        t0 = time.monotonic()
        with httpx.stream("POST", f"http://127.0.0.1:{port}/v1/analyze", json={"url": URL}, timeout=20) as r:
            buf = ""
            for chunk in r.iter_text():
                buf += chunk
                while "\n\n" in buf:
                    frame, buf = buf.split("\n\n", 1)
                    if frame.startswith("event: "):
                        arrivals.append((frame.split("\n")[0][7:], time.monotonic() - t0))
        first_trace = next(t for n, t in arrivals if n == "trace")
        report_t = next(t for n, t in arrivals if n == "report")
        assert report_t - first_trace >= 1.2, arrivals
    finally:
        server.should_exit = True
        th.join(timeout=5)
