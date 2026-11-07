"""User-provided context in the advanced (cloud) analysis (docs/plans/manual_url_check.md §5).

Only what the USER handed over is ever received: their statements (U1-U4 chips + free text), the message they
pasted/shared with the link, the source-app category and the device's context-judgment enums. Raw chats the
device read from notifications are never sent. Nothing here is persisted: the page bundle cached by URL never
contains user context, and trace lines carry counts/enums only.

Flow: page stage (cached, context-free) -> ONE Opus request judging the situation (submit_context is the only
tool) -> merge with the device judgment on the risk side -> the existing v4 context rules (additive only).
"""
from __future__ import annotations

import asyncio
import json
from typing import Any, Callable

from . import lite_followups as lf

PASTED_SENDER = "(message pasted by the user)"
PASTED_APP = "pasted by the user"
MAX_STATEMENTS = 4
MAX_PASTED = 1
MAX_CHIPS = 5
CONSISTENCY_RANK = ["consistent", "unclear", "mismatch"]          # higher index = riskier
SENDER_RANK = ["known_contact", "unclear", "unknown_sender", "impersonated_org"]


def _limits() -> dict:
    return lf.catalog()["limits"]


def _clean_text(s: Any, limit: int) -> str:
    if not isinstance(s, str):
        return ""
    return lf.mask(s)[:limit]  # Python slices code points (Kotlin: TextNorm.takeCodePoints)


def _enum(v: Any, allowed: list[str]) -> str | None:
    return v if isinstance(v, str) and v in allowed else None


def sanitize(uc: dict | None) -> dict | None:
    """Masks, caps and enum-checks a user_context (the device already did; this is defence in depth)."""
    if not isinstance(uc, dict):
        return None
    cat = lf.catalog()
    lim = _limits()
    src = uc.get("source_category")
    statements = []
    for st in uc.get("statements") or []:
        q = cat["questions"].get(st.get("question_id")) if isinstance(st, dict) else None
        if q is None:
            continue
        valid = [c["id"] for c in q.get("chips", [])]
        chips = []
        for cid in st.get("chip_ids") or []:
            if cid in valid and cid not in chips:
                chips.append(cid)
        chips = chips[:MAX_CHIPS]
        text = _clean_text(st.get("text"), lim["statement_chars"])
        if not chips and not text.strip():
            continue
        statements.append({"question_id": st["question_id"], "chip_ids": chips, "text": text})
        if len(statements) == MAX_STATEMENTS:
            break
    pasted = []
    for m in uc.get("pasted_messages") or []:
        text = _clean_text(m.get("text") if isinstance(m, dict) else None, lim["message_chars"])
        if text.strip():
            pasted.append({"text": text})
        if len(pasted) == MAX_PASTED:
            break
    dj = uc.get("device_judgment")
    device = None
    if isinstance(dj, dict):
        device = {"scenario": _enum(dj.get("scenario"), lf.SCENARIOS),
                  "consistency": _enum(dj.get("consistency"), lf.CONSISTENCY),
                  "sender_risk": _enum(dj.get("sender_risk"), lf.SENDER),
                  "guided": dj.get("guided") is True}
    if not statements and not pasted and device is None:
        return None
    return {"source_category": src if src in cat["categories"] else "unknown",
            "statements": statements, "pasted_messages": pasted, "device_judgment": device}


def conversation_of(uc: dict) -> list[dict]:
    return [{"sender": PASTED_SENDER, "text": m["text"], "has_link": True} for m in uc.get("pasted_messages") or []]


def judge_message(uc: dict, page_domain: str | None, level: str, features: dict) -> str:
    """Same blocks as the device's Turn 3 (lite_followups.turn3_message) — one vocabulary for both judges."""
    return lf.turn3_message(uc["source_category"], page_domain, level, features, PASTED_APP,
                            conversation_of(uc) or None, uc["statements"])


def _riskier(a: str | None, b: str | None, rank: list[str]) -> str | None:
    ra = rank.index(a) if a in rank else -1
    rb = rank.index(b) if b in rank else -1
    if ra < 0 and rb < 0:
        return None
    return a if ra >= rb else b


def merge(device: dict | None, cloud: dict | None, statements: list[dict], conversation: list[dict]) -> dict:
    """Risk side of the two judgments (context rules only add, so this can never lower a score)."""
    d = device or {}
    c = cloud or {}
    scen = next((s for s in (c.get("scenario"), d.get("scenario")) if s not in (None, "none")), None)
    if scen is None:
        scen = c.get("scenario") or d.get("scenario")
    cloud_input = lf.to_context_input(cloud, statements, conversation)
    return {"scenario": scen,
            "consistency": _riskier(c.get("consistency"), d.get("consistency"), CONSISTENCY_RANK),
            "sender_risk": _riskier(c.get("sender_risk"), d.get("sender_risk"), SENDER_RANK),
            "guided": bool(d.get("guided") is True or cloud_input["guided"]),
            "evidence": list(c.get("evidence") or [])}


def submit_context_tool() -> dict:
    return {
        "name": "submit_context",
        "description": "Submit your judgment of the situation the user got the link in. Call exactly once.",
        "input_schema": {
            "type": "object",
            "properties": {
                "scenario": {"type": "string", "enum": lf.SCENARIOS},
                "consistency": {"type": "string", "enum": lf.CONSISTENCY},
                "sender_risk": {"type": "string", "enum": lf.SENDER},
                "evidence": {"type": "array", "items": {"type": "string"}, "maxItems": 2},
            },
            "required": ["scenario", "consistency", "sender_risk", "evidence"],
            "additionalProperties": False,
        },
    }


def summary(uc: dict) -> str:
    """Trace-safe description: counts and enums only, never the text."""
    return (f"user context: source={uc['source_category']} statements={len(uc['statements'])} "
            f"pasted={len(uc['pasted_messages'])} device_judgment={'yes' if uc['device_judgment'] else 'no'}")


async def judge(*, client: Any, settings: Any, store: Any, emit: Callable[[str, str], None], uc: dict,
                page_domain: str | None, level: str, features: dict, timeout_s: float = 30.0) -> dict | None:
    """One Opus request (submit_context is the only tool). None on any failure (device judgment is used)."""
    if store.api_requests_today() >= settings.daily_opus_cap:
        emit("context", f"cloud judgment skipped: daily Opus cap reached ({settings.daily_opus_cap})")
        return None
    message = judge_message(uc, page_domain, level, features)
    store.record_api_request(settings.model, purpose="context")
    emit("context", f"cloud judgment: request sent ({settings.model})")
    try:
        # Same request shape as the page agent (adaptive thinking, auto tool choice): a forced tool_choice is
        # rejected together with thinking. The prompt asks for exactly one submit_context call.
        resp = await asyncio.wait_for(client.beta.messages.create(
            model=settings.model, max_tokens=4000, system=lf.prompt("opus_context.txt"),
            messages=[{"role": "user", "content": [{"type": "text", "text": message}]}],
            tools=[submit_context_tool()], tool_choice={"type": "auto"}, thinking={"type": "adaptive"},
            output_config={"effort": "low"}, timeout=timeout_s), timeout=timeout_s)
    except Exception as e:  # timeout, API error: the device judgment alone is used
        code = getattr(e, "status_code", None)
        emit("context", f"cloud judgment unavailable ({type(e).__name__}{f' {code}' if code else ''}: "
                        f"{str(e)[:140]}), using device judgment")
        return None
    content = resp.get("content") if isinstance(resp, dict) else getattr(resp, "content", None)
    for b in content or []:
        btype = b.get("type") if isinstance(b, dict) else getattr(b, "type", None)
        name = b.get("name") if isinstance(b, dict) else getattr(b, "name", None)
        if btype == "tool_use" and name == "submit_context":
            inp = b.get("input") if isinstance(b, dict) else getattr(b, "input", None)
            j = lf.parse_judgment(json.dumps(inp if isinstance(inp, dict) else {}, ensure_ascii=False),
                                  lf.evidence_sources(conversation_of(uc), uc["statements"]))
            if j is not None:
                return j
    emit("context", "cloud judgment had no valid submit_context, using device judgment")
    return None
