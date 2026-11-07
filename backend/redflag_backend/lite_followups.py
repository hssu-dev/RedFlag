"""Gemma follow-up turns after Turn 1 (twin of android/core dev.redflag.core.followup.Followups.kt).

  D  destination relation (docs/plans/form_destination_observation.md)
  T2 does context matter? (tool calls; docs/plans/context_analysis.md)
  T3 context judgment
Each turn is its own short conversation without page text. Used by the Mac eval (eval/followups/) so what is
measured there is what the phone runs; both twins pass contracts/followup_vectors.json.
"""
from __future__ import annotations

import json
import re
from functools import lru_cache
from pathlib import Path

from . import contracts, psl
from .prompt_text import sanitize_page_text

CONTRACTS = Path(__file__).resolve().parents[2] / "contracts"


def _read(name: str) -> str:
    return (CONTRACTS / name).read_text(encoding="utf-8")


@lru_cache(maxsize=None)
def catalog() -> dict:
    return json.loads(_read("context_catalog.json"))


@lru_cache(maxsize=None)
def tools() -> list[dict]:
    return json.loads(_read("context_tools.json"))["tools"]


def prompt(name: str) -> str:
    """gemma_destination.txt | gemma_context_need.txt | gemma_context.txt, strip()ped like the Kotlin loader."""
    return _read(name).strip()


def untrusted_text(s: str | None) -> str:
    return sanitize_page_text(s).replace("<", "‹").replace(">", "›")


def _dumps(obj) -> str:
    return json.dumps(obj, ensure_ascii=False, indent=1)


def _block(tag: str, obj) -> str:
    return f"<{tag}>\n{_dumps(obj)}\n</{tag}>"


def _collapse(s: str) -> str:
    return " ".join(s.split())


# ---------------------------------------------------------------- page state (eval/page_state, gemma_page_state.txt)

PAGE_STATES = ["loaded", "challenge", "blocked", "host_warning", "unavailable"]


def page_state_message(http_status: int | None, title: str | None, text: str | None, form_count: int,
                       has_password_field: bool) -> str:
    return _block("page", {"http_status": http_status,
                           "title": untrusted_text((title or "").strip()[:120]),
                           "text": untrusted_text(_collapse(text or "")[:600]),
                           "form_count": form_count, "has_password_field": has_password_field})


def parse_page_state(text: str) -> str | None:
    o = json_object_in(text)
    v = _str(o, "page_state") if o is not None else None
    v = v.strip().lower() if v is not None else None
    return v if v in PAGE_STATES else None


def json_object_in(text: str) -> dict | None:
    a, b = text.find("{"), text.rfind("}")
    if a < 0 or b <= a:
        return None
    try:
        o = json.loads(text[a:b + 1])
    except ValueError:
        return None
    return o if isinstance(o, dict) else None


def _str(o: dict, k: str) -> str | None:
    v = o.get(k)
    return v if isinstance(v, str) else None


# ---------------------------------------------------------------- D: destination relation

RELATIONS = ["same_org", "known_service", "unrelated", "unclear"]
_VIA_ORDER = ["form", "script", "xhr", "fetch", "beacon"]
MAX_CANDIDATES = 5


def _host_matches(host: str, entry: str) -> bool:
    return host == entry or host.endswith("." + entry)


def destination_candidates(endpoints: list[dict], page_domain: str | None, personal_request: bool,
                           domain_of=psl.registrable_domain_of_url) -> list[dict]:
    if not personal_request:
        return []
    from .scoring import _endpoint_match
    rules = contracts.scoring_rules()
    dd = rules.get("data_destinations", {})
    def listed(d, lst): return any(_host_matches(d, x) for x in lst)
    order = sorted(enumerate(endpoints), key=lambda t: (_VIA_ORDER.index(t[1].get("via")) if t[1].get("via") in _VIA_ORDER else len(_VIA_ORDER), t[0]))
    out: dict[str, dict] = {}
    for _, e in order:
        if len(out) >= MAX_CANDIDATES:
            break
        url = e.get("url") or ""
        if _endpoint_match(url, dd.get("messenger_bot", [])) or _endpoint_match(url, dd.get("form_service", [])):
            continue
        d = domain_of(url)
        if not d:
            continue
        d = d.lower()
        if d == (page_domain or "").lower() or listed(d, rules["known_payment_processors"]) or listed(d, dd.get("known_data_services", [])):
            continue
        out.setdefault(d, {"domain": d, "via": e.get("via") or ""})
    return list(out.values())


def destination_message(page_domain: str | None, features: dict, candidates: list[dict]) -> str:
    cb = features.get("claimed_brand")
    return _block("analysis", {
        "page_domain": page_domain,
        "claimed_brand": untrusted_text(cb) if cb is not None else None,
        "page_type": features.get("page_type"),
        "requested_actions": list(features.get("requested_actions") or []),
        "destinations": [{"domain": c["domain"], "via": c["via"]} for c in candidates],
    })


def parse_destination(text: str, candidates: list[dict]) -> dict | None:
    if not candidates:
        return None
    o = json_object_in(text)
    rel = None
    if o is not None:
        v = _str(o, "form_destination")
        if v is not None and v.strip().lower() in RELATIONS:
            rel = v.strip().lower()
    if rel is None:
        m = re.search(r"\b(same_org|known_service|unrelated|unclear)\b", text)
        if not m:
            return None
        rel = m.group(1)
    dom = None
    if o is not None and _str(o, "domain") is not None:
        d = _str(o, "domain").strip().lower()
        if any(c["domain"] == d for c in candidates):
            dom = d
    return {"relation": rel, "domain": dom or candidates[0]["domain"]}


# ---------------------------------------------------------------- context: sources, masking

def classify_source(package: str | None) -> dict:
    for e in catalog()["sources"]:
        if e["package"] == package:
            return {"category": e["category"], "name": e["name"], "package": package}
    return {"category": "unknown", "name": None, "package": package}


_NUMBER_RUN = re.compile(r"\+?[0-9][0-9\-. ]{5,}[0-9]")


def mask(s: str) -> str:
    def rep(m):
        v = m.group(0)
        return re.sub(r"[0-9]", "#", v) if sum(c in "0123456789" for c in v) >= 7 else v
    return _NUMBER_RUN.sub(rep, s)


# ---------------------------------------------------------------- context: decision, Turn 2

_RISKY = {"pay_fee", "enter_card", "enter_personal_id", "enter_secret_code", "install_app", "connect_wallet"}
QUESTIONS = ["U1", "U2", "U3", "U4"]
_CHAT = {"messenger", "sms"}


def decide(score: int, complete: bool, sb_hit: bool, features: dict | None, source_category: str,
           never_ask: bool = False, already_asked: bool = False) -> str:
    """① exclude | needed | fine."""
    if features is None or not complete or sb_hit or score >= 85 or never_ask or already_asked:
        return "exclude"
    if 30 <= score <= 70:
        return "needed"
    if _RISKY & set(features.get("requested_actions") or []) and source_category in _CHAT:
        return "needed"
    return "fine"


def turn2_message(features: dict, decision: str, source_category: str, page_domain: str | None,
                  domain_age_days: int | None, level: str) -> str:
    cb = features.get("claimed_brand")
    return _block("analysis", {
        "source_app": source_category,
        "code_decision": "needed" if decision == "needed" else "fine",
        "page_domain": page_domain,
        "domain_age_days": domain_age_days,
        "level": level,
        "claimed_brand": untrusted_text(cb) if cb is not None else None,
        "brand_matches_domain": features.get("brand_matches_domain"),
        "page_type": features.get("page_type"),
        "requested_actions": list(features.get("requested_actions") or []),
    })


def parse_tool_calls(calls: list[dict]) -> dict:
    """calls: [{name, args}] -> {auto, question, reasons, none, recognized}."""
    reasons_ok = catalog()["reasons"]
    auto, question, none, recognized, reasons = False, None, False, 0, []
    def add_reasons(a):
        rs = a.get("reasons")
        if isinstance(rs, list):
            for r in rs:
                if isinstance(r, str) and r.strip() in reasons_ok and r.strip() not in reasons:
                    reasons.append(r.strip())
    for c in calls:
        name, args = c.get("name"), c.get("args") if isinstance(c.get("args"), dict) else {}
        if name == "request_auto_context":
            auto = True; recognized += 1; add_reasons(args)
        elif name == "request_user_context":
            recognized += 1
            q = args.get("question_id")
            q = q.strip().upper() if isinstance(q, str) else None
            if question is None:
                question = q if q in QUESTIONS else "U1"
            add_reasons(args)
        elif name == "no_context_needed":
            none = True; recognized += 1
    return {"auto": auto, "question": question, "reasons": reasons,
            "none": none and not auto and question is None, "recognized": recognized}


def tool_calls_from_text(text: str) -> list[dict]:
    o = json_object_in(text)
    if o is None:
        return []
    name = _str(o, "name") or _str(o, "tool")
    if name is None:
        return []
    args = o.get("arguments", o.get("args", o.get("parameters")))
    return [{"name": name, "args": args if isinstance(args, dict) else {}}]


def _default_reasons(f: dict) -> list[str]:
    a = f.get("requested_actions") or []
    out = []
    if "install_app" in a:
        out.append("app_install")
    if "pay_fee" in a or "enter_card" in a or f.get("page_type") == "payment":
        out.append("unexpected_payment")
    if f.get("brand_matches_domain") in ("no", "unclear"):
        out.append("brand_unverifiable")
    return out or ["other"]


def _default_question(f: dict) -> str:
    a = f.get("requested_actions") or []
    if "install_app" in a:
        return "U3"
    if f.get("page_type") == "login" or "enter_password" in a:
        return "U2"
    return "U1"


def concrete_reasons(reasons: list[str]) -> list[str]:
    """"other" alone is too vague to show a card when the code said fine (user decision 2026-11-04)."""
    return [r for r in reasons if r != "other"]


def resolve(decision: str, parsed: dict | None, source_category: str, features: dict | None) -> dict | None:
    if decision == "exclude" or features is None:
        return None
    auto_ok = source_category in catalog()["auto_context_categories"] or source_category == "unknown"
    vague = decision == "fine" and parsed is not None and not concrete_reasons(parsed["reasons"])
    if parsed is not None and (parsed["auto"] or parsed["question"] is not None) and not vague:
        auto = parsed["auto"] and auto_ok
        q = parsed["question"] if parsed["question"] is not None else ("U1" if parsed["auto"] and not auto_ok else None)
        return {"auto": auto, "question": q, "reasons": parsed["reasons"] or _default_reasons(features), "origin": "gemma"}
    if decision == "fine":
        return None
    if source_category in _CHAT:
        return {"auto": True, "question": None, "reasons": _default_reasons(features), "origin": "code_default"}
    return {"auto": False, "question": _default_question(features), "reasons": _default_reasons(features), "origin": "code_default"}


# ---------------------------------------------------------------- context: Turn 3

SCENARIOS = ["parcel_smishing", "used_trade_escrow", "gov_voice_phishing", "investment_scam", "account_threat", "romance_or_job", "none"]
CONSISTENCY = ["consistent", "mismatch", "unclear"]
SENDER = ["unknown_sender", "impersonated_org", "known_contact", "unclear"]
MAX_EVIDENCE_WORDS = 15


def conversation_obj(app_name: str | None, messages: list[dict]) -> dict:
    lim = catalog()["limits"]
    return {"app": app_name, "messages": [
        {"from": untrusted_text(mask(m["sender"]))[:60],
         "text": untrusted_text(mask(m["text"]))[:lim["message_chars"]],
         "contains_link": bool(m.get("has_link"))} for m in messages]}


def statement_text(a: dict) -> str:
    cat = catalog()
    q = cat["questions"].get(a["question_id"])
    chips = []
    for cid in a.get("chip_ids") or []:
        for c in (q or {}).get("chips", []):
            if c["id"] == cid:
                chips.append(c["label"]["en"])
                break
    free = untrusted_text(a.get("text"))[:cat["limits"]["statement_chars"]]
    parts = chips + ([free] if free.strip() else [])
    return f"Q: {q['text']['en'] if q else a['question_id']}\nA: {'; '.join(parts) or '(no answer)'}"


def turn3_message(source_category: str, page_domain: str | None, level: str, features: dict,
                  app_name: str | None, conversation: list[dict] | None, answers: list[dict]) -> str:
    cb = features.get("claimed_brand")
    out = _block("analysis", {
        "source_app": source_category,
        "page_domain": page_domain,
        "level": level,
        "claimed_brand": untrusted_text(cb) if cb is not None else None,
        "page_type": features.get("page_type"),
        "requested_actions": list(features.get("requested_actions") or []),
    })
    if conversation:
        out += "\n" + _block("untrusted_conversation", conversation_obj(app_name, conversation))
    if answers:
        out += "\n<user_statement>\n" + "\n".join(statement_text(a) for a in answers) + "\n</user_statement>"
    return out


def evidence_sources(conversation: list[dict] | None, answers: list[dict]) -> list[str]:
    lim = catalog()["limits"]
    return ([untrusted_text(mask(m["text"]))[:lim["message_chars"]] for m in conversation or []]
            + [untrusted_text(a.get("text"))[:lim["statement_chars"]] for a in answers])


def parse_judgment(text: str, source_texts: list[str]) -> dict | None:
    o = json_object_in(text)
    if o is None:
        return None
    def pick(k, allowed):
        v = _str(o, k)
        v = v.strip().lower() if v is not None else None
        return v if v in allowed else None
    hay = [_collapse(t) for t in source_texts]
    ev = []
    raw = o.get("evidence") if isinstance(o.get("evidence"), list) else []
    for e in raw:
        if not isinstance(e, str):
            continue
        e = _collapse(e)
        if e and len(e.split(" ")) <= MAX_EVIDENCE_WORDS and any(e in h for h in hay) and e not in ev:
            ev.append(e)
    return {"scenario": pick("scenario", SCENARIOS), "consistency": pick("consistency", CONSISTENCY),
            "sender_risk": pick("sender_risk", SENDER), "evidence": ev[:2]}


def to_context_input(j: dict | None, answers: list[dict], conversation: list[dict] | None) -> dict:
    qs = catalog()["questions"]
    chip_guided = any(c.get("guided") for a in answers for cid in (a.get("chip_ids") or [])
                      for c in qs.get(a["question_id"], {}).get("chips", []) if c["id"] == cid)
    link_from_stranger = any(m.get("has_link") for m in conversation or []) and (j or {}).get("sender_risk") in ("unknown_sender", "impersonated_org")
    return {"scenario": (j or {}).get("scenario"), "consistency": (j or {}).get("consistency"),
            "sender_risk": (j or {}).get("sender_risk"), "guided": bool(chip_guided or link_from_stranger),
            "evidence": list((j or {}).get("evidence") or [])}
