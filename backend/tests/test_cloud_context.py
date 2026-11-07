"""User-provided context in the advanced analysis (docs/plans/manual_url_check.md §5).

Vectors (cloud_context / context_merge) + pipeline behaviour: one extra Opus request, cache stays context-free,
nothing user-provided is persisted or traced, failures fall back to the device judgment, never a lower score.
"""
import json
import sqlite3

import httpx

from conftest import CONTRACTS, load
from fakes import FakeClient, VALID_FEATURES, msg, submit, tool_use
from redflag_backend import cloud_context, pipeline, report
from redflag_backend.app import create_app

from test_app import OBS, URL, post, tools  # noqa: F401  (fixture)

V = load("followup_vectors.json")

# A payment page that by itself is only "caution": no brand, no cross-site form, old domain.
PAY_URL = "https://safepay-escrow.shop/pay"
PAY_FEATURES = dict(VALID_FEATURES, evidence=["안전결제 진행"], claimed_brand=None, brand_matches_domain="unclear",
                    page_type="payment", requested_actions=["pay_fee"], urgency_or_threat=False, verdict="suspicious",
                    confidence="medium", form_posts_cross_site=False, official_domain_found=None)
SECRET_PHONE = "010-1234-5678"
PASTED = "[Web발신] 안전결제 진행해주세요 https://safepay-escrow.shop/pay 문의 " + SECRET_PHONE
STATEMENT = "중고나라 판매자가 안전결제로 하자고 링크를 보냈어요 " + SECRET_PHONE
USER_CTX = {
    "source_category": "messenger",
    "statements": [{"question_id": "U4", "chip_ids": ["yes_trade"], "text": STATEMENT}],
    "pasted_messages": [{"text": PASTED}],
    "device_judgment": {"scenario": "used_trade_escrow", "consistency": "unclear", "sender_risk": "unknown_sender", "guided": False},
}


def ctx_answer(scenario="used_trade_escrow", consistency="mismatch", sender="unknown_sender", evidence=("안전결제 진행해주세요",)):
    return msg([tool_use("submit_context", {"scenario": scenario, "consistency": consistency, "sender_risk": sender,
                                            "evidence": list(evidence)}, "toolu_ctx")], "tool_use")


def pay_page(tools):  # noqa: F811
    tools["obs"] = dict(OBS, requested_url=PAY_URL, final_url=PAY_URL, registrable_domain="safepay-escrow.shop",
                        title="안전결제", visible_text="안전결제 진행 결제하기", forms=[], redirect_chain=[PAY_URL])


def page_script():
    return [msg([submit(PAY_FEATURES)], "tool_use")]


# ------------------------------------------------------------------ vectors

def test_cloud_context_vectors():
    for v in V["cloud_context"]:
        assert cloud_context.sanitize(v["in"]) == v["expect"], v["id"]
        if v["expect"] is not None:  # idempotent: the device already sanitized
            assert cloud_context.sanitize(v["expect"]) == v["expect"], v["id"]


def test_context_merge_vectors():
    for v in V["context_merge"]:
        i = v["in"]
        assert cloud_context.merge(i["device"], i["cloud"], i["statements"], i["conversation"]) == v["expect"], v["id"]


def test_opus_and_gemma_judges_share_the_field_definitions():
    def fields(name):
        t = (CONTRACTS / name).read_text(encoding="utf-8")
        return t[t.index("FIELDS\n"):t.index("\n\nOUTPUT")]
    assert fields("opus_context.txt") == fields("gemma_context.txt")


def test_judge_message_masks_numbers_and_labels_pasted_text_untrusted():
    uc = cloud_context.sanitize(USER_CTX)
    m = cloud_context.judge_message(uc, "safepay-escrow.shop", "caution", PAY_FEATURES)
    assert SECRET_PHONE not in m and "###-####-####" in m
    assert "<untrusted_conversation>" in m and "<user_statement>" in m and cloud_context.PASTED_SENDER in m


# ------------------------------------------------------------------ pipeline

async def test_context_adds_rules_with_one_extra_request(env, store, tools, tmp_path):  # noqa: F811
    pay_page(tools)
    base_fake = FakeClient(page_script())
    base = (await post(create_app(client_factory=lambda: base_fake, store=store), {"url": PAY_URL}))[-1][1]

    from redflag_backend.store import Store
    s2 = Store(tmp_path / "s2.sqlite3", ttl_s=3600)  # fresh cache: page stage runs again with context
    fake = FakeClient(page_script() + [ctx_answer()])
    events = await post(create_app(client_factory=lambda: fake, store=s2), {"url": PAY_URL, "user_context": USER_CTX})
    rep = events[-1][1]
    report.validate_report(rep)
    assert len(fake.calls) == 2  # page agent (1 round here) + exactly one context judgment
    ctx_call = fake.calls[1]
    assert ctx_call["tool_choice"] == {"type": "auto"} and ctx_call["thinking"] == {"type": "adaptive"}
    assert [t["name"] for t in ctx_call["tools"]] == ["submit_context"]
    ids = [f["rule_id"] for f in rep["red_flags"]]
    for rid in ("context_scam_scenario", "context_purpose_mismatch", "unknown_sender_payment", "used_trade_escrow_payment"):
        assert rid in ids, ids
    assert rep["score"] > base["score"]
    assert all(f["source"] in ("opus+rule", "signal+rule") for f in rep["red_flags"])
    ev = next(f for f in rep["red_flags"] if f["rule_id"] == "context_scam_scenario")["evidence"]
    assert ev == "안전결제 진행해주세요"


async def test_cache_hit_with_context_costs_one_request(env, store, tools):  # noqa: F811
    pay_page(tools)
    fake = FakeClient(page_script() + [ctx_answer()])
    app = create_app(client_factory=lambda: fake, store=store)
    await post(app, {"url": PAY_URL})                       # page stage cached (context-free)
    events = await post(app, {"url": PAY_URL, "user_context": USER_CTX})
    assert len(fake.calls) == 2 and tools["browse_calls"] == 1
    assert [d for n, d in events if n == "trace" and d["step"] == "cache"][0]["detail"].startswith("hit")
    assert "used_trade_escrow_payment" in [f["rule_id"] for f in events[-1][1]["red_flags"]]


async def test_nothing_user_provided_is_stored_or_traced(env, store, tools):  # noqa: F811
    pay_page(tools)
    fake = FakeClient(page_script() + [ctx_answer()])
    events = await post(create_app(client_factory=lambda: fake, store=store), {"url": PAY_URL, "user_context": USER_CTX})
    rep = events[-1][1]
    traces = json.dumps([d for n, d in events if n == "trace"], ensure_ascii=False)
    for secret in (SECRET_PHONE, "중고나라 판매자가", "[Web발신]"):
        assert secret not in traces, secret
    # the sqlite file (cache + budget tables) never holds user context
    con = sqlite3.connect(store.path)
    dump = "\n".join(con.iterdump())
    for secret in (SECRET_PHONE, "중고나라", "Web발신", "안전결제 진행해주세요"):
        assert secret not in dump, secret
    # the number never left the device unmasked, not even to Opus
    sent = json.dumps(fake.calls[1]["messages"], ensure_ascii=False)
    assert SECRET_PHONE not in sent and "###-####-####" in sent
    assert rep["trace"][-1]["step"] == "scoring"


async def test_context_judge_failure_uses_device_judgment_and_never_lowers(env, store, tools):  # noqa: F811
    pay_page(tools)
    fake0 = FakeClient(page_script())
    base = (await post(create_app(client_factory=lambda: fake0, store=store), {"url": PAY_URL}))[-1][1]
    fake = FakeClient([RuntimeError("overloaded")])  # page stage is a cache hit; the context call fails
    events = await post(create_app(client_factory=lambda: fake, store=store), {"url": PAY_URL, "user_context": USER_CTX})
    rep = events[-1][1]
    assert len(fake.calls) == 1
    ids = [f["rule_id"] for f in rep["red_flags"]]
    assert "used_trade_escrow_payment" in ids and "unknown_sender_payment" in ids  # device enums
    assert rep["score"] >= base["score"]
    details = [d["detail"] for n, d in events if n == "trace" and d["step"] == "context"]
    assert any("using device judgment" in d for d in details)


async def test_cloud_saying_benign_cannot_remove_device_points(env, store, tools):  # noqa: F811
    pay_page(tools)
    fake = FakeClient(page_script() + [ctx_answer("none", "consistent", "known_contact", ())])
    rep = (await post(create_app(client_factory=lambda: fake, store=store), {"url": PAY_URL, "user_context": USER_CTX}))[-1][1]
    ids = [f["rule_id"] for f in rep["red_flags"]]
    assert "used_trade_escrow_payment" in ids and "unknown_sender_payment" in ids


async def test_device_judgment_only_needs_no_opus_call(env, store, tools):  # noqa: F811
    pay_page(tools)
    fake = FakeClient(page_script())
    uc = {"source_category": "sms", "device_judgment": {"scenario": "parcel_smishing", "consistency": "mismatch",
                                                        "sender_risk": "unclear", "guided": False}}
    rep = (await post(create_app(client_factory=lambda: fake, store=store), {"url": PAY_URL, "user_context": uc}))[-1][1]
    assert len(fake.calls) == 1
    assert "context_purpose_mismatch" in [f["rule_id"] for f in rep["red_flags"]]


async def test_user_context_validation(env, store, tools):  # noqa: F811
    app = create_app(client_factory=None, store=store)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as c:
        # raw notification chats are not part of the contract: rejected outright
        bad = dict(USER_CTX, auto_messages=[{"sender": "x", "text": "y"}])
        assert (await c.post("/v1/analyze", json={"url": URL, "user_context": bad})).status_code == 422
        assert (await c.post("/v1/analyze", json={"url": URL, "user_context": dict(USER_CTX, statements=[
            {"question_id": "U1", "chip_ids": [], "text": "x" * 5000}])})).status_code == 422
        assert (await c.post("/v1/analyze", json={"url": URL, "user_context": dict(USER_CTX, statements=[
            {"question_id": "Q9", "chip_ids": [], "text": "x"}])})).status_code == 422
