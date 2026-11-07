"""Python twin of android/core FeatureRepair.kt (conservative repair of schema-invalid Gemma JSON)."""
from __future__ import annotations

ACTIONS = ["enter_password", "enter_card", "enter_personal_id", "enter_secret_code", "enter_contact_info",
           "pay_fee", "call_phone", "install_app", "connect_wallet", "none"]
BMD = {"yes", "no", "unclear", "no_brand"}
PAGE = {"login", "payment", "shop", "download", "support", "info", "other"}
VERDICT = {"safe", "suspicious", "malicious"}
CONF = {"low", "medium", "high"}


def repair(obj: dict) -> tuple[dict, list[str]]:
    repairs: list[str] = []

    def enum_or(k, allowed, fallback):
        v = obj.get(k)
        if isinstance(v, str) and v in allowed:
            return v
        repairs.append(f"{k}->{fallback}")
        return fallback

    def flag(k):
        v = obj.get(k)
        if isinstance(v, bool):
            return v
        repairs.append(f"{k}->false")
        return False

    ev_raw = obj.get("evidence")
    evidence = [e for e in ev_raw if isinstance(e, str)][:3] if isinstance(ev_raw, list) else []
    if not isinstance(ev_raw, list) or len(evidence) != len(ev_raw):
        repairs.append("evidence")
    brand = obj.get("claimed_brand")
    if "claimed_brand" not in obj or (brand is not None and not isinstance(brand, str)):
        repairs.append("claimed_brand->null")
    brand = brand if isinstance(brand, str) else None
    act_raw = obj.get("requested_actions")
    actions = list(dict.fromkeys(a for a in act_raw if a in ACTIONS)) if isinstance(act_raw, list) else []
    if not isinstance(act_raw, list) or len(actions) != len(act_raw):
        repairs.append("requested_actions")
    if not actions:
        actions = ["none"]
    if len(actions) > 1 and "none" in actions:
        actions = [a for a in actions if a != "none"]
    f = {
        "evidence": evidence,
        "claimed_brand": brand,
        "brand_matches_domain": enum_or("brand_matches_domain", BMD, "no_brand" if brand is None else "unclear"),
        "page_type": enum_or("page_type", PAGE, "other"),
        "requested_actions": actions,
        "urgency_or_threat": flag("urgency_or_threat"),
        "too_good_to_be_true": flag("too_good_to_be_true"),
        "addresses_ai_analyzer": flag("addresses_ai_analyzer"),
        "verdict": enum_or("verdict", VERDICT, "suspicious"),
        "confidence": enum_or("confidence", CONF, "low"),
    }
    return f, repairs


# ---------------------------------------------------------------- v3: salvage + follow-up (twin of core GemmaSalvage.kt)
import json as _json
import re as _re
from pathlib import Path as _Path

KEYS = ["evidence", "claimed_brand", "brand_matches_domain", "page_type", "requested_actions",
        "urgency_or_threat", "too_good_to_be_true", "addresses_ai_analyzer", "verdict", "confidence"]
CORE_KEYS = ("claimed_brand", "brand_matches_domain", "requested_actions")
EVIDENCE_MAX_ITEMS = 3
EVIDENCE_MAX_WORDS = 15
_KEY_RE = _re.compile(r'"([A-Za-z_]{3,40})"\s*:')
_FENCE = _re.compile(r"```(?:json)?")


def _lev(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def canonical_key(k: str) -> str | None:
    """Map a misspelled key (token repeat / drop, e.g. addresses_ai_ai_analyzer, urgency_orthreat) to the
    expected key; None if it is not confidently one of the 10 keys."""
    if k in KEYS:
        return k
    toks = [t for t in k.lower().split("_") if t]
    dedup = [t for i, t in enumerate(toks) if i == 0 or t != toks[i - 1]]
    cand = "_".join(dedup)
    if cand in KEYS:
        return cand
    flat = "".join(ch for ch in k.lower() if "a" <= ch <= "z")
    scored = sorted((_lev(flat, key.replace("_", "")), key) for key in KEYS)
    if scored[0][0] <= 2 and scored[1][0] > scored[0][0]:
        return scored[0][1]
    return None


def salvage(raw: str) -> tuple[dict | None, list[str]]:
    """Tolerant read of a broken answer: every `"key": <json value>` occurrence, keys canonicalized,
    first occurrence wins. Recovers early-closed arrays/objects, stray commas and misspelled keys.
    Values are taken verbatim from the model (nothing is invented)."""
    text = raw
    if "<channel|>" in text:
        text = text.rsplit("<channel|>", 1)[1]
    text = _FENCE.sub("", text)
    dec = _json.JSONDecoder()
    obj: dict = {}
    notes: list[str] = []
    for m in _KEY_RE.finditer(text):
        ck = canonical_key(m.group(1))
        if ck is None or ck in obj:
            continue
        i = m.end()
        while i < len(text) and text[i] in " \t\n\r":
            i += 1
        try:
            val, _ = dec.raw_decode(text, i)
        except ValueError:
            continue
        obj[ck] = val
        if ck != m.group(1):
            notes.append(f"key:{m.group(1)}->{ck}")
    return (obj or None), notes


def enforce_evidence(f: dict) -> dict:
    """Policy: at most 3 snippets, each at most 15 words (prefix kept, so still verbatim)."""
    ev = f.get("evidence") if isinstance(f.get("evidence"), list) else []
    f = dict(f)
    f["evidence"] = [" ".join(s.split()[:EVIDENCE_MAX_WORDS]) for s in ev if isinstance(s, str)][:EVIDENCE_MAX_ITEMS]
    return f


def missing_keys(obj: dict | None) -> list[str]:
    return [k for k in KEYS if obj is None or k not in obj]


def followup_prompt(missing: list[str], contracts_dir=None) -> str:
    from . import contracts
    d = _Path(contracts_dir) if contracts_dir else contracts.contracts_dir()
    tmpl = (d / "gemma_followup.txt").read_text(encoding="utf-8").strip()
    return tmpl.replace("{keys}", ", ".join(k for k in KEYS if k in missing))


def is_core_repair(repairs: list[str]) -> bool:
    return any(r.split("->")[0] in CORE_KEYS for r in repairs)


def finalize(obj: dict) -> tuple[dict, list[str], bool]:
    """(features, repairs, core_repaired) from a (salvaged / follow-up-merged) object."""
    from . import scoring  # noqa: F401  (keeps import graph explicit)
    f, repairs = repair(obj)
    return enforce_evidence(f), repairs, is_core_repair(repairs)


def merge(first: dict | None, followup: dict | None, asked: list[str]) -> dict:
    """Twin of GemmaSalvage.merge: only asked-for keys come from the follow-up; the first answer wins otherwise."""
    out = dict(first or {})
    for k, v in (followup or {}).items():
        if k in asked and k not in out:
            out[k] = v
    return out


def followup_for(first: str) -> tuple[str, list[str]] | None:
    """Twin of GemmaPipeline.followUpFor."""
    import importlib.util
    obj, err = _strict(first)
    if obj is not None and not _validate(obj):
        return None
    missing = missing_keys(salvage(first)[0])
    if not missing:
        return None
    return followup_prompt(missing), missing


def interpret(first: str, followup: str | None, asked: list[str]) -> dict:
    """Twin of GemmaPipeline.interpret -> dict(features, strict_ok, salvage_notes, missing_before, asked,
    missing_after, repairs, core_repaired, repaired)."""
    obj, err = _strict(first)
    if obj is not None and not _validate(obj):
        return dict(features=enforce_evidence(obj), strict_ok=True, salvage_notes=[], missing_before=[], asked=[],
                    missing_after=[], repairs=[], core_repaired=False, repaired=False)
    sal, notes = salvage(first)
    before = missing_keys(sal)
    o = merge(sal, salvage(followup)[0], asked) if (followup is not None and asked) else sal
    if not o:
        return dict(features=None, strict_ok=False, salvage_notes=notes, missing_before=before, asked=asked,
                    missing_after=list(KEYS), repairs=[], core_repaired=False, repaired=False)
    after = missing_keys(o)
    f, repairs = repair(o)
    return dict(features=enforce_evidence(f), strict_ok=False, salvage_notes=notes, missing_before=before, asked=asked,
                missing_after=after, repairs=repairs, core_repaired=is_core_repair(repairs), repaired=bool(repairs))


def _run_eval():
    """redflag-gemma-test/run_eval.py (frozen baseline) for extract_json/validate parity."""
    import importlib.util
    import sys
    if "run_eval" in sys.modules:
        return sys.modules["run_eval"]
    root = _Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location("run_eval", root / "redflag-gemma-test" / "run_eval.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    sys.modules["run_eval"] = mod
    return mod


def _strict(text: str):
    obj, err, _clean = _run_eval().extract_json(text)
    return obj, err


def _validate(obj: dict) -> list[str]:
    return _run_eval().validate(obj)
