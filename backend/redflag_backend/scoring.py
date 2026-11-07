"""Python interpreter for contracts/scoring_rules.json.

"LLM is a witness, the score is code": Gemma/Opus only supply features; this module
(and its Kotlin twin in android/core) turns features + deterministic signals into a
score. Both must pass contracts/scoring_vectors.json.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from . import contracts
from .textnorm import contains_phrase, normalize

LEVEL_ORDER = ["safe", "caution", "danger", "critical"]


@dataclass
class RedFlag:
    rule_id: str
    severity: str
    source: str
    weight: int
    title: dict
    evidence: str | None

    def to_dict(self) -> dict:
        return {"rule_id": self.rule_id, "severity": self.severity, "source": self.source,
                "weight": self.weight, "title": self.title, "evidence": self.evidence}


@dataclass
class ScoreResult:
    score: int
    level: str
    escalate: bool
    red_flags: list[RedFlag]
    headline: dict
    brand: dict
    facts: dict = field(repr=False, default_factory=dict)

    @property
    def fired(self) -> list[str]:
        return [f.rule_id for f in self.red_flags]


# ---------------------------------------------------------------- facts

def _sensitive_categories(form: dict, rules: dict) -> set[str]:
    if isinstance(form.get("sensitive_fields"), list):
        return set(form["sensitive_fields"])
    cats = rules["sensitive_fields"]["categories"]
    found: set[str] = set()
    for f in form.get("fields") or []:
        ftype = (f.get("type") or "").lower()
        hay = normalize(" ".join(str(f.get(k) or "") for k in ("type", "label", "name", "autocomplete")))
        for cat, spec in cats.items():
            if ftype in spec["types"] or any(contains_phrase(hay, p) for p in spec["phrases"]):
                found.add(cat)
    return found


def sensitive_categories(form: dict) -> list[str]:
    """Public helper: report.signals.forms[].sensitive_fields (stable order)."""
    rules = contracts.scoring_rules()
    cats = _sensitive_categories(form, rules)
    return [c for c in ("password", "card", "secret") if c in cats]


def _is_processor(domain: str, processors: list[str]) -> bool:
    d = domain.lower().rstrip(".")
    return any(d == p or d.endswith("." + p) for p in processors)


def match_brands(claimed: str | None, brands: list[dict] | None = None) -> list[dict]:
    if brands is None:
        brands = contracts.brand_domains()["brands"]
    c = normalize(claimed)
    if not c:
        return []
    out = []
    for b in brands:
        for alias in b["aliases"]:
            a = normalize(alias)
            if not a:
                continue
            if a == c or (b.get("match", "token") == "token" and f" {a} " in f" {c} "):
                out.append(b)
                break
    return out


def build_facts(inp: dict) -> dict:
    rules = contracts.scoring_rules()
    feats = inp.get("features") or {}
    sig = inp.get("signals") or {}
    reg = (inp.get("registrable_domain") or "").lower() or None

    claimed = feats.get("claimed_brand")
    if isinstance(claimed, str) and not claimed.strip():
        claimed = None
    matched = match_brands(claimed)
    official = bool(matched) and reg is not None and any(reg in [d.lower() for d in b["domains"]] for b in matched)

    processors = rules["known_payment_processors"]
    offending = None
    for form in sig.get("forms") or []:
        action = form.get("action_domain")
        if form.get("same_site") is False and action and not _is_processor(action, processors) \
                and _sensitive_categories(form, rules):
            offending = action
            break

    sensitive_actions = {"enter_password", "enter_card", "enter_secret_code"}
    req_actions = feats.get("requested_actions") if isinstance(feats.get("requested_actions"), list) else []
    sensitive_request = bool(sensitive_actions & set(req_actions)) or any(
        _sensitive_categories(form, rules) for form in sig.get("forms") or [])

    age = sig.get("domain_age_days")
    evidence = feats.get("evidence") if isinstance(feats.get("evidence"), list) else []
    actions = feats.get("requested_actions") if isinstance(feats.get("requested_actions"), list) else []
    personal_request = sensitive_request or bool({"enter_personal_id", "enter_contact_info"} & set(actions))
    payment_request = bool({"pay_fee", "enter_card"} & set(actions)) or feats.get("page_type") == "payment"
    endpoints = [u for u in (sig.get("data_endpoints") or []) if isinstance(u, str)]
    dd = rules.get("data_destinations", {})
    bot_host = next((h for h in (_endpoint_match(u, dd.get("messenger_bot", [])) for u in endpoints) if h), None)
    svc_host = next((h for h in (_endpoint_match(u, dd.get("form_service", [])) for u in endpoints) if h), None)
    ctx = inp.get("context") if isinstance(inp.get("context"), dict) else {}
    ctx_ev = ctx.get("evidence") if isinstance(ctx.get("evidence"), list) else []
    return {
        "safe_browsing_hit": sig.get("safe_browsing") == "hit",
        "provider_warning": sig.get("provider_warning") if isinstance(sig.get("provider_warning"), str) and sig.get("provider_warning").strip() else None,
        "domain_age_days": age if isinstance(age, int) and not isinstance(age, bool) else None,
        "registrable_domain": reg,
        "claimed_brand": claimed,
        "brand_known": bool(matched),
        "brand_official": official,
        "official_domains": list(matched[0]["domains"]) if matched else [],
        "model_brand_matches_domain": feats.get("brand_matches_domain"),
        "sensitive_form_cross_site": offending is not None,
        "form_action_domain": offending,
        "punycode_or_homoglyph": sig.get("homoglyph") is True,
        "requested_actions": actions,
        "urgency_or_threat": feats.get("urgency_or_threat") is True,
        "too_good_to_be_true": feats.get("too_good_to_be_true") is True,
        "addresses_ai_analyzer": feats.get("addresses_ai_analyzer") is True,
        "cloaking_suspected": inp.get("cloaking_suspected") is True,
        "sensitive_request": sensitive_request,
        "features_repaired": inp.get("features_repaired") is True,
        "analysis_incomplete": inp.get("analysis_incomplete") is True,
        "core_fields_repaired": inp.get("core_fields_repaired") is True,
        "hidden_ai_instruction": hidden_ai_instruction(sig.get("hidden_text")),
        "evidence0": evidence[0] if evidence and isinstance(evidence[0], str) and evidence[0].strip() else None,
        "data_endpoints": endpoints,
        "exfil_messenger_bot": bot_host is not None,
        "exfil_form_service": svc_host is not None,
        "exfil_host": bot_host or svc_host,
        "personal_request": personal_request,
        "form_destination": _str_or_none(inp.get("form_destination")),
        "form_destination_domain": _str_or_none(inp.get("form_destination_domain")),
        "payment_request": payment_request,
        "context_scenario": _str_or_none(ctx.get("scenario")),
        "context_consistency": _str_or_none(ctx.get("consistency")),
        "context_sender_risk": _str_or_none(ctx.get("sender_risk")),
        "context_guided": ctx.get("guided") is True,
        "context_evidence0": ctx_ev[0] if ctx_ev and isinstance(ctx_ev[0], str) and ctx_ev[0].strip() else None,
    }


def _str_or_none(v) -> str | None:
    return v if isinstance(v, str) and v.strip() else None


def parse_endpoint(url: str) -> tuple[str, str] | None:
    """scoring_rules.json data_destinations.doc: ^https?://AUTHORITY PATH -> (host, path); None if not http(s)."""
    import re
    m = re.match(r"(?i)^https?://([^/?#]*)([^?#]*)", url.strip())
    if not m:
        return None
    host = m.group(1).rsplit("@", 1)[-1]
    host = re.sub(r":\d*$", "", host).lower().rstrip(".")
    if not host:
        return None
    return host, (m.group(2) or "/")


def _endpoint_match(url: str, entries: list[dict]) -> str | None:
    """Host of `url` if it matches one of the data_destinations entries, else None."""
    hp = parse_endpoint(url)
    if hp is None:
        return None
    host, path = hp
    for e in entries:
        h = e["host"]
        if (host == h or host.endswith("." + h)) and path.startswith(e.get("path_prefix", "")):
            return host
    return None


def normalize_for_patterns(text: str | None) -> str:
    """NFKC, lowercase, whitespace runs -> one space, trim (scoring_rules.json hidden_text_patterns)."""
    import unicodedata
    return " ".join(unicodedata.normalize("NFKC", text or "").lower().split())


def hidden_ai_instruction(hidden_text: str | None) -> bool:
    import re
    t = normalize_for_patterns(hidden_text)
    if not t:
        return False
    return any(re.search(p, t) for p in contracts.scoring_rules().get("hidden_text_patterns", {}).get("patterns", []))


# ---------------------------------------------------------------- expressions

def evaluate(expr: dict, facts: dict) -> bool:
    if "fact" in expr:
        return facts.get(expr["fact"]) is True
    if "eq" in expr:
        name, value = expr["eq"]
        return facts.get(name) == value
    if "lt" in expr:
        name, value = expr["lt"]
        v = facts.get(name)
        return isinstance(v, (int, float)) and not isinstance(v, bool) and v < value
    if "has" in expr:
        name, value = expr["has"]
        v = facts.get(name)
        return isinstance(v, list) and value in v
    if "not_null" in expr:
        return facts.get(expr["not_null"]) is not None
    if "all" in expr:
        return all(evaluate(e, facts) for e in expr["all"])
    if "any" in expr:
        return any(evaluate(e, facts) for e in expr["any"])
    if "not" in expr:
        return not evaluate(expr["not"], facts)
    raise ValueError(f"unknown expression: {expr}")


def _fmt_value(v: Any) -> str | None:
    if v is None:
        return None
    if isinstance(v, list):
        return ", ".join(str(x) for x in v) if v else None
    if isinstance(v, bool):
        return "true" if v else "false"
    s = str(v)
    return s if s.strip() else None


def fill(template: str | None, facts: dict, strict: bool) -> str | None:
    """Replace {name} placeholders. strict=True -> None if any placeholder is empty."""
    if template is None:
        return None
    out, i = [], 0
    while i < len(template):
        j = template.find("{", i)
        if j == -1:
            out.append(template[i:])
            break
        k = template.find("}", j)
        if k == -1:
            out.append(template[i:])
            break
        out.append(template[i:j])
        val = _fmt_value(facts.get(template[j + 1:k]))
        if val is None:
            if strict:
                return None
            val = "?"
        out.append(val)
        i = k + 1
    return "".join(out)


def _fill_i18n(t: dict, facts: dict) -> dict:
    return {lang: fill(text, facts, strict=False) for lang, text in t.items()}


# ---------------------------------------------------------------- score

def score(inp: dict) -> ScoreResult:
    rules = contracts.scoring_rules()
    facts = build_facts(inp)
    mode = inp.get("mode", "lite")
    model_source = "opus+rule" if mode == "cloud" else "gemma+rule"

    total = 0
    floors: list[int] = []
    clamps: list[tuple[int, int]] = []
    fired: list[tuple[int, int, int, RedFlag]] = []  # (rank: floor 0 / add 1 / clamp 2, -weight, order, flag)
    for order, rule in enumerate(rules["rules"]):
        if not evaluate(rule["when"], facts):
            continue
        kind = rule["kind"]
        if kind == "floor":
            weight = rule["value"]
            floors.append(weight)
        elif kind == "clamp":
            weight = 0
            clamps.append((rule["min"], rule["max"]))
        elif kind == "add":
            weight = rule["weight"]
            total += weight
        else:
            raise ValueError(f"unknown rule kind: {kind}")
        flag = RedFlag(
            rule_id=rule["id"],
            severity=rule["severity"],
            source=model_source if rule["source"] == "model" else "signal+rule",
            weight=weight,
            title=_fill_i18n(rule["title"], facts),
            evidence=fill(rule.get("evidence"), facts, strict=True),
        )
        fired.append(({"floor": 0, "add": 1, "clamp": 2}[kind], -weight, order, flag))
    fired.sort(key=lambda t: t[:3])

    lo, hi = rules["score"]["min"], rules["score"]["max"]
    s = max(lo, min(hi, total))
    for cmin, cmax in clamps:  # e.g. repaired model output -> at most caution; unfinished analysis -> never safe
        s = max(cmin, min(cmax, s))
    for f in floors:  # floors (Safe Browsing) beat clamps
        s = max(s, f)

    level = rules["levels"][0]["level"]
    for entry in rules["levels"]:
        if s >= entry["min"]:
            level = entry["level"]
    esc = rules["escalation"]["min"] <= s <= rules["escalation"]["max"]

    fired_ids = {t[3].rule_id for t in fired}
    headline = None
    if LEVEL_ORDER.index(level) >= LEVEL_ORDER.index(rules["headline_min_level"]):
        for rid in rules["headline_priority"]:
            if rid in fired_ids and rid in rules["headline_by_rule"]:
                headline = _fill_i18n(rules["headline_by_rule"][rid], facts)
                break
    if headline is None:
        headline = dict(rules["headline_by_level"][level])

    brand = {
        "claimed": facts["claimed_brand"],
        "official_domains": facts["official_domains"],
        "matches": facts["brand_official"] if facts["brand_known"] else None,
    }
    return ScoreResult(score=s, level=level, escalate=esc, red_flags=[t[3] for t in fired],
                       headline=headline, brand=brand, facts=facts)
