"""Shared helpers for the eval scorers (stdlib only; imports the FROZEN run_eval.py and the
backend rule engine read-only).

* run_eval: extract_json / validate / check / grounding (redflag-gemma-test/run_eval.py)
* summarize_results: stats() (redflag-gemma-test/summarize_results.py)
* rule engine: backend/redflag_backend/scoring.py over contracts/scoring_rules.json
"""
from __future__ import annotations

import json
import math
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "redflag-gemma-test"))
sys.path.insert(0, str(ROOT / "backend"))

import run_eval  # noqa: E402
import summarize_results  # noqa: E402
from redflag_backend import psl, scoring  # noqa: E402

LEVELS = scoring.LEVEL_ORDER  # safe < caution < danger < critical


def load_jsonl(path) -> list[dict]:
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def rescore_record(rec: dict, case: dict | None) -> dict:
    """Re-parse rec['raw'] with run_eval and recompute schema_errors/checks/grounding.

    Returns a run_eval-shaped record (the shape summarize_results.stats() reads). Records
    with 'error' are passed through. Missing 'expected' -> {} (no field checks)."""
    if "error" in rec:
        return dict(rec)
    raw = rec.get("raw") or ""
    obj, perr, clean = run_eval.extract_json(raw)
    out = {k: rec.get(k) for k in ("id", "run", "label")}
    if case is not None:
        out["label"] = case["label"]
    out.update(latency=float(rec["latency"]), completion_tokens=rec.get("completion_tokens"),
               thought=rec.get("thought"), clean=clean, parse_error=perr, output=obj, raw=raw)
    if rec.get("prompt_tokens"):
        out["prompt_tokens"] = rec["prompt_tokens"]
    if obj is not None:
        out["schema_errors"] = run_eval.validate(obj)
        out["checks"] = run_eval.check(obj, (case or {}).get("expected") or {})
        out["grounding"] = run_eval.grounding(obj, case) if case is not None else None
    # bookkeeping: did the recorded parse agree with our re-parse?
    out["_reparse_agrees"] = (rec.get("output") == obj) and (bool(rec.get("clean")) == clean
                                                            if "clean" in rec else True)
    return out


def scoring_input(case: dict, features: dict | None) -> dict:
    """Lite ScoringInput (contracts/scoring_rules.json doc) from a case + Gemma features.

    Forms: case['forms_full'] (with name/autocomplete) when present, else signals.forms.
    punycode/homoglyph: computed from the final URL host with the backend PSL helpers."""
    sig = case["signals"]
    host = psl.normalize_host(sig.get("final_url") or sig.get("url"))
    return {
        "mode": "lite",
        "registrable_domain": sig.get("registrable_domain"),
        "features": features,
        "signals": {
            "safe_browsing": sig.get("safe_browsing") or "unknown",
            "domain_age_days": sig.get("domain_age_days"),
            "punycode": psl.is_punycode(host),
            "homoglyph": psl.is_homoglyph(host),
            "forms": case.get("forms_full") or sig.get("forms") or [],
        },
        "cloaking_suspected": False,
    }


REPAIR = False  # set by --repair: mirror the app's FeatureRepair (parsed-but-invalid -> conservative repair)


def valid_features(rec: dict) -> dict | None:
    """Gemma features the app would use: parsed AND schema-valid (or repaired when REPAIR), else None (signals only)."""
    obj = rec.get("output")
    if obj is None:
        return None
    if rec.get("schema_errors"):
        if not REPAIR:
            return None
        from redflag_backend.gemma_repair import repair
        return repair(obj)[0]
    return obj


FAILSAFE = True  # v2 app: repaired -> features_repaired (D2 cap), no usable model answer -> analysis_incomplete (D3)


def app_features(rec: dict) -> tuple[dict | None, bool, bool]:
    """(features, repaired, incomplete) exactly as the v2 app scores a model answer.
    No answer / parse failure -> no features and the analysis is incomplete (status error in the app).
    Note: eval cases cannot tell an RDAP *failure* from "no RDAP data", so a null domain age is treated as no data."""
    obj = rec.get("output")
    if "error" in rec or obj is None:
        return None, False, True
    if rec.get("schema_errors"):
        from redflag_backend.gemma_repair import repair
        return repair(obj)[0], True, False
    return obj, False, False


def rule_score(case: dict, features: dict | None, repaired: bool = False, incomplete: bool = False) -> scoring.ScoreResult:
    inp = scoring_input(case, features)
    if repaired:
        inp["features_repaired"] = True
    if incomplete:
        inp["analysis_incomplete"] = True
    return scoring.score(inp)


def at_least(level: str, floor: str) -> bool:
    return LEVELS.index(level) >= LEVELS.index(floor)


def pct(a: int, b: int) -> str:
    return f"{a}/{b} = {a / b:.0%}" if b else f"{a}/{b} = -"


def latency_stats(lat: list[float]) -> dict:
    if not lat:
        return {"n": 0}
    s = sorted(lat)
    p95 = s[max(0, math.ceil(0.95 * len(s)) - 1)]  # nearest-rank
    return {"n": len(s), "mean": statistics.mean(s), "median": statistics.median(s), "p95": p95, "max": s[-1]}


def print_run_eval_summary(records: list[dict]) -> None:
    """Same lines/definitions as run_eval.summarize() (without its args dependency)."""
    ok = [r for r in records if "error" not in r]
    parsed = [r for r in ok if r.get("output") is not None]
    valid = [r for r in parsed if not r["schema_errors"]]
    p = run_eval.pct
    print(f"requests            {len(records)}  (errors {len(records) - len(ok)})")
    if not ok:
        return
    print(f"JSON parsed         {len(parsed)}/{len(ok)} = {p(len(parsed), len(ok))}")
    print(f"  clean output      {sum(r['clean'] for r in ok)}/{len(ok)}  (pure JSON, nothing around it)")
    print(f"schema valid        {len(valid)}/{len(ok)} = {p(len(valid), len(ok))}")
    field_hits: dict[str, list[bool]] = {}
    for r in parsed:
        for k, v in r["checks"].items():
            field_hits.setdefault(k, []).append(v)
    print("field accuracy" + ("" if field_hits else "      (no 'expected' in cases: none)"))
    for k, v in field_hits.items():
        print(f"  {k:<22} {sum(v)}/{len(v)} = {p(sum(v), len(v))}")
    mal = [r for r in parsed if r["label"] == "malicious"]
    ben = [r for r in parsed if r["label"] == "benign"]
    if mal:
        caught = sum(r["output"].get("verdict") in ("malicious", "suspicious") for r in mal)
        strict = sum(r["output"].get("verdict") == "malicious" for r in mal)
        print(f"malicious caught    {caught}/{len(mal)}  (strict 'malicious' {strict}/{len(mal)})")
    if ben:
        alarm = sum(r["output"].get("verdict") == "malicious" for r in ben)
        sus = sum(r["output"].get("verdict") == "suspicious" for r in ben)
        print(f"benign false alarm  {alarm}/{len(ben)}  (marked suspicious {sus}/{len(ben)})")
    g = [r["grounding"] for r in parsed if r.get("grounding") is not None]
    if g:
        print(f"evidence verbatim   {statistics.mean(g):.0%}")
    lat = [r["latency"] for r in ok]
    print(f"latency (s)         mean {statistics.mean(lat):.2f}  median {statistics.median(lat):.2f}"
          f"  max {max(lat):.2f}")
    toks = [r["completion_tokens"] for r in ok if r.get("completion_tokens")]
    if toks:
        print(f"completion tokens   mean {statistics.mean(toks):.0f}")
    ptoks = [r["prompt_tokens"] for r in ok if r.get("prompt_tokens")]
    if ptoks:
        print(f"prompt tokens       mean {statistics.mean(ptoks):.0f}")
