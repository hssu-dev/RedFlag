"""contracts/followup_vectors.json against the Python twin (Kotlin twin: FollowupVectorsTest)."""
import json
from pathlib import Path

from redflag_backend import lite_followups as L

V = json.loads((Path(__file__).resolve().parents[2] / "contracts" / "followup_vectors.json").read_text(encoding="utf-8"))


def test_destination_candidates():
    for c in V["destination_candidates"]:
        assert L.destination_candidates(c["endpoints"], c["page_domain"], c["personal_request"]) == c["expect"], c["id"]


def test_destination_parse():
    for c in V["destination_parse"]:
        assert L.parse_destination(c["text"], c["candidates"]) == c["expect"], c["id"]


def test_mask():
    for c in V["mask"]:
        assert L.mask(c["in"]) == c["expect"]


def test_decide():
    for c in V["decide"]:
        got = L.decide(c["score"], c["complete"], c["safe_browsing_hit"], c["features"], c["source"],
                       c.get("never_ask", False), c.get("already_asked", False))
        assert got == c["expect"], c["id"]


def test_parse_and_resolve():
    for c in V["resolve"]:
        p = L.parse_tool_calls(c["calls"])
        assert p == c["expect_parsed"], c["id"]
        assert L.resolve(c["decision"], p, c["source"], c["features"]) == c["expect_plan"], c["id"]


def test_tool_calls_from_text():
    for c in V["tool_calls_from_text"]:
        assert L.tool_calls_from_text(c["text"]) == c["expect"]


def test_judgment_and_input():
    for c in V["judgment"]:
        assert L.parse_judgment(c["text"], c["sources"]) == c["expect"], c["id"]
    for c in V["context_input"]:
        assert L.to_context_input(c["judgment"], c["answers"], c["conversation"]) == c["expect"], c["id"]


def test_prompt_bytes():
    for c in V["messages"]:
        if c["kind"] == "destination":
            got = L.destination_message(c["page_domain"], c["features"], c["candidates"])
        elif c["kind"] == "turn2":
            got = L.turn2_message(c["features"], c["decision"], c["source"], c["page_domain"], c["domain_age_days"], c["level"])
        else:
            got = L.turn3_message(c["source"], c["page_domain"], c["level"], c["features"], c["app_name"], c["conversation"], c["answers"])
            assert L.evidence_sources(c["conversation"], c["answers"]) == c["expect_sources"]
        assert got == c["expect"]


def test_contract_files():
    assert [t["name"] for t in L.tools()] == ["request_auto_context", "request_user_context", "no_context_needed"]
    assert set(L.catalog()["questions"]) == {"U1", "U2", "U3", "U4"}
    for name in ("gemma_destination.txt", "gemma_context_need.txt", "gemma_context.txt"):
        assert L.prompt(name)


def test_page_state_twin_vectors():
    from conftest import load
    d = load("followup_vectors.json")["page_state"]
    for v in d["messages"]:
        i = v["in"]
        assert L.page_state_message(i["http_status"], i["title"], i["text"], i["form_count"], i["has_password_field"]) == v["expect"], v["id"]
    for v in d["parse"]:
        assert L.parse_page_state(v["text"]) == v["expect"], v["text"]
