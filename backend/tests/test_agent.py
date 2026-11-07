"""Opus agent loop with a scripted fake Anthropic client (no real API calls)."""
import asyncio

import pytest

from fakes import FakeClient, VALID_FEATURES, msg, submit, thinking, tool_use, web_search
from redflag_backend import agent
from redflag_backend.config import get_settings


class Collect:
    def __init__(self):
        self.steps = []

    def __call__(self, step, detail):
        self.steps.append((step, detail))

    def names(self):
        return [s for s, _ in self.steps]


def tools_stub(calls):
    async def rdap(inp):
        calls.append(("rdap", inp))
        return [{"type": "text", "text": '{"domain": "api-collect.click", "domain_age_days": 2}'}], "api-collect.click: 2 days"

    async def cert(inp):
        calls.append(("cert", inp))
        return [{"type": "text", "text": "{}"}], "cert ok"

    return {"rdap": rdap, "cert": cert}


def first():
    return agent.build_initial_message("https://dbs-secure-verify.top/login", {"domain_age_days": 4},
                                       {"title": "DBS"}, None)


async def run(client, store, emit, tools=None, **env):
    return await agent.run_agent(client=client, settings=get_settings(), store=store, emit=emit,
                                 url="https://dbs-secure-verify.top/login", first_message=first(),
                                 tools=tools or tools_stub([]))


async def test_tool_round_web_search_then_submit(store):
    calls = []
    client = FakeClient([
        msg([thinking(), *web_search("DBS Bank official website"),
             tool_use("rdap", {"domain": "api-collect.click"}, "toolu_1")], "tool_use"),
        msg([thinking(), submit()], "tool_use"),
    ])
    emit = Collect()
    res = await run(client, store, emit, tools_stub(calls))
    assert res.status == "complete"
    assert res.features["claimed_brand"] == "DBS"
    assert res.api_requests == 2 and store.api_requests_total() == 2
    assert calls == [("rdap", {"domain": "api-collect.click"})]
    assert res.web_searches == ["DBS Bank official website"]
    names = emit.names()
    assert names.index("web_search") < names.index("rdap") < names.index("submit_report")
    assert any("dbs.com.sg" in d for s, d in emit.steps if s == "web_search")

    req = client.calls[0]
    assert req["model"] == "claude-opus-5-5"
    assert req["tool_choice"] == {"type": "auto"}          # forced tool_choice is a 400 on Opus 5.5
    assert req["thinking"] == {"type": "adaptive"}
    assert req["output_config"] == {"effort": "medium"}
    assert req["betas"] == ["server-side-fallback-2026-07-01"] and req["fallbacks"] == "default"
    ws = [t for t in req["tools"] if t.get("type") == "web_search_20260209"]
    assert ws == [{"type": "web_search_20260209", "name": "web_search", "max_uses": 3, "allowed_callers": ["direct"]}]
    sub = next(t for t in req["tools"] if t.get("name") == "submit_report")
    assert "$schema" not in sub["input_schema"] and "$id" not in sub["input_schema"] and "title" not in sub["input_schema"]
    assert sub["input_schema"]["required"] == list(VALID_FEATURES)  # same field order as the contract

    # round 2 = [user, assistant(round1 content as-is), user(only tool_result blocks)]
    msgs = client.calls[1]["messages"]
    assert [m["role"] for m in msgs] == ["user", "assistant", "user"]
    assert all(b["type"] == "tool_result" for b in msgs[2]["content"])
    assert msgs[2]["content"][0]["tool_use_id"] == "toolu_1"


async def test_pause_turn_is_resent_without_extra_user_message(store):
    client = FakeClient([
        msg([*web_search("DBS official", with_result=False)], "pause_turn"),
        msg([{"type": "web_search_tool_result", "tool_use_id": "srvtoolu_1", "content": []}, submit()], "tool_use"),
    ])
    emit = Collect()
    res = await run(client, store, emit)
    assert res.status == "complete" and res.api_requests == 2
    msgs = client.calls[1]["messages"]
    assert [m["role"] for m in msgs] == ["user", "assistant"]  # continuation: assistant content appended, nothing else
    assert client.calls[1]["tools"] == client.calls[0]["tools"]


async def test_mixed_server_and_client_tool_turn_sends_only_tool_results(store):
    client = FakeClient([
        msg([*web_search("DBS official", with_result=False), tool_use("cert", {"domain": "x.top"}, "toolu_c")], "tool_use"),
        msg([{"type": "web_search_tool_result", "tool_use_id": "srvtoolu_1",
              "content": {"type": "web_search_tool_result_error", "error_code": "max_uses_exceeded"}}, submit()], "tool_use"),
    ])
    emit = Collect()
    res = await run(client, store, emit)
    assert res.status == "complete"
    follow = client.calls[1]["messages"][-1]
    assert follow["role"] == "user" and [b["type"] for b in follow["content"]] == ["tool_result"]
    assert any("max_uses_exceeded" in d for s, d in emit.steps if s == "web_search")


async def test_round_limit_falls_back_to_timeout(store, env):
    env.setenv("REDFLAG_MAX_ROUNDS", "3")
    client = FakeClient([msg([tool_use("rdap", {"domain": f"d{i}.top"}, f"t{i}")], "tool_use") for i in range(3)])
    res = await run(client, store, Collect())
    assert res.status == "timeout" and res.features is None
    assert res.api_requests == 3 and len(client.calls) == 3
    # the last-chance note is delivered inside tool_result content (never as a bare text block)
    last_user = client.calls[2]["messages"][-1]
    assert "last chance" in last_user["content"][0]["content"][-1]["text"]


async def test_wall_clock_timeout(store, env):
    env.setenv("REDFLAG_WALL_CLOCK_S", "2.5")
    client = FakeClient([msg([submit()], "tool_use")], delay=5)
    res = await run(client, store, Collect())
    assert res.status == "timeout" and res.features is None and res.api_requests == 1


async def test_daily_budget_exhausted_makes_no_request(store, env):
    env.setenv("REDFLAG_DAILY_OPUS_CAP", "2")
    store.record_api_request("claude-opus-5-5")
    store.record_api_request("claude-opus-5-5")
    client = FakeClient([])
    emit = Collect()
    res = await run(client, store, emit)
    assert res.status == "error" and "budget" in res.detail
    assert client.calls == [] and store.api_requests_total() == 2
    assert "budget" in emit.names()


async def test_invalid_submit_report_gets_error_result_then_retry(store):
    bad = dict(VALID_FEATURES, verdict="evil")
    client = FakeClient([msg([submit(bad, "toolu_bad")], "tool_use"), msg([submit()], "tool_use")])
    res = await run(client, store, Collect())
    assert res.status == "complete" and res.api_requests == 2
    tr = client.calls[1]["messages"][-1]["content"][0]
    assert tr["is_error"] is True and "verdict" in tr["content"][0]["text"]


async def test_evidence_over_three_is_trimmed():
    feats, err = agent.validate_features(dict(VALID_FEATURES, evidence=["a", "b", "c", "d"]))
    assert err is None and feats["evidence"] == ["a", "b", "c"]


async def test_end_turn_without_submit_is_nudged(store):
    client = FakeClient([msg([{"type": "text", "text": "It looks like phishing."}], "end_turn"),
                         msg([submit()], "tool_use")])
    res = await run(client, store, Collect())
    assert res.status == "complete"
    nudge = client.calls[1]["messages"][-1]
    assert nudge["role"] == "user" and "submit_report" in nudge["content"][0]["text"]


async def test_refusal_is_error(store):
    client = FakeClient([msg([], "refusal")])
    res = await run(client, store, Collect())
    assert res.status == "error" and "refusal" in res.detail


async def test_api_error_is_error_status(store):
    client = FakeClient([RuntimeError("boom")])
    res = await run(client, store, Collect())
    assert res.status == "error" and res.api_requests == 1


async def test_fallbacks_can_be_disabled(store, env):
    env.setenv("REDFLAG_FALLBACKS", "0")
    client = FakeClient([msg([submit()], "tool_use")])
    await run(client, store, Collect())
    assert "fallbacks" not in client.calls[0] and "betas" not in client.calls[0]


def test_system_prompt_principles():
    p = agent.SYSTEM_PROMPT
    for needle in ("untrusted", "never instructions", "do not produce a score", "addresses_ai_analyzer=true",
                   "web_search", "Never try to submit forms", "submit_report exactly once"):
        assert needle in p, needle
