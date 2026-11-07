#!/usr/bin/env python3
"""M6b scoring: real-page Gemma results -> rule engine -> eval/real/REAL_REPORT.md.

  python3 eval/real/score_real.py                       # report (Gemma sections 'not run yet' if no results)
  python3 eval/real/score_real.py --tune                # + weight-tuning proposal (tune on A, report on B)

Per record: re-parse `raw` with run_eval.extract_json, validate(); features = the parsed object
if schema-valid, else None (the rule engine then sees signals only, as the app would).
Rule input: registrable_domain, domain_age_days, safe_browsing ('unknown' for all real cases),
forms_full (with name/autocomplete), punycode/homoglyph from the final URL host.

Tuning protocol (master prompt M6): weights of `add` rules are searched ONLY on split A
(coordinate descent, small steps), before/after are reported on split B. contracts/scoring_rules.json
is NEVER edited; the proposal goes to eval/real/tuning_proposal.json.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import rf_eval_common as C  # noqa: E402

SPLITS = ("A", "B", "all")
RULES = C.scoring.contracts.scoring_rules()
NOT_RUN = "not run yet"


def assign_splits_if_missing(cases: list[dict]) -> None:
    """Same algorithm as collect.assign_splits: per label, sort by sha256(id), first half -> A."""
    if all("split" in c for c in cases):
        return
    for label in ("malicious", "benign"):
        group = sorted((c for c in cases if c["label"] == label),
                       key=lambda c: hashlib.sha256(c["id"].encode()).hexdigest())
        half = (len(group) + 1) // 2
        for i, c in enumerate(group):
            c.setdefault("split", "A" if i < half else "B")


# ------------------------------------------------------------------ fast re-scoring for tuning

def add_weights(rules: dict) -> dict[str, int]:
    return {r["id"]: r["weight"] for r in rules["rules"] if r["kind"] == "add"}


def fast_score(fired: list[str], weights: dict[str, int], floors: dict[str, int]) -> tuple[int, str]:
    """Same arithmetic as scoring.score(): sum add weights, clamp, apply floors, map to level.
    Rule FIRING does not depend on weights, so fired ids from one scoring.score() call are reused."""
    total = sum(weights[r] for r in fired if r in weights)
    s = max(RULES["score"]["min"], min(RULES["score"]["max"], total))
    for r in fired:
        if r in floors:
            s = max(s, floors[r])
    level = RULES["levels"][0]["level"]
    for e in RULES["levels"]:
        if s >= e["min"]:
            level = e["level"]
    return s, level


FLOORS = {r["id"]: r["value"] for r in RULES["rules"] if r["kind"] == "floor"}


def rates(rows: list[dict], weights: dict[str, int]) -> dict:
    mal = [r for r in rows if r["label"] == "malicious"]
    ben = [r for r in rows if r["label"] == "benign"]
    lv = {id(r): fast_score(r["fired"], weights, FLOORS)[1] for r in rows}

    def frac(group, floor):
        return sum(C.at_least(lv[id(r)], floor) for r in group) / len(group) if group else 0.0
    return {"n_mal": len(mal), "n_ben": len(ben),
            "det_caution": frac(mal, "caution"), "det_danger": frac(mal, "danger"),
            "fp_caution": frac(ben, "caution"), "fp_danger": frac(ben, "danger")}


def objective(m: dict, base: dict[str, int], w: dict[str, int]) -> float:
    change = sum(abs(w[k] - base[k]) for k in base)
    return (m["det_caution"] + m["det_danger"] - 2 * m["fp_caution"] - 4 * m["fp_danger"]
            - 0.002 * change)


def tune(rows_a: list[dict], base: dict[str, int], steps=(-15, -10, -5, 5, 10, 15),
         bounds=(0, 60), max_passes=6) -> tuple[dict[str, int], list[str]]:
    fired_any = {r for row in rows_a for r in row["fired"]}
    w = dict(base)
    best = objective(rates(rows_a, w), base, w)
    trace = []
    for p in range(max_passes):
        improved = False
        for rid in sorted(base):
            if rid not in fired_any:
                continue  # weight has no effect on A: leave it
            for d in steps:
                cand = dict(w)
                cand[rid] = min(bounds[1], max(bounds[0], w[rid] + d))
                if cand[rid] == w[rid]:
                    continue
                j = objective(rates(rows_a, cand), base, cand)
                if j > best + 1e-9:
                    trace.append(f"pass {p + 1}: {rid} {w[rid]} -> {cand[rid]} (J {best:.3f} -> {j:.3f})")
                    w, best, improved = cand, j, True
        if not improved:
            break
    return w, trace


# ------------------------------------------------------------------ evaluation

def evaluate(cases: list[dict], results: list[dict] | None) -> list[dict]:
    """One row per (case, run). Without results: one signals-only row per case."""
    by_id = {c["id"]: c for c in cases}
    rows = []
    if results is None:
        for c in cases:
            base = C.rule_score(c, None)
            rows.append({"id": c["id"], "label": c["label"], "split": c["split"], "rec": None,
                         "feat_used": False, "fired": base.fired, "score": base.score, "level": base.level,
                         "sig_fired": base.fired, "sig_level": base.level})
        return rows
    for r in results:
        c = by_id.get(r.get("id"))
        if c is None:
            continue
        rec = C.rescore_record(r, c)
        if C.REPAIR:
            feats, repaired, incomplete = C.app_features(rec)
            if not C.FAILSAFE:
                repaired = incomplete = False
            res = C.rule_score(c, feats, repaired, incomplete)
        else:
            feats = C.valid_features(rec) if "error" not in rec else None
            res = C.rule_score(c, feats)
        base = C.rule_score(c, None)
        rows.append({"id": c["id"], "label": c["label"], "split": c["split"], "rec": rec,
                     "feat_used": feats is not None, "fired": res.fired, "score": res.score, "level": res.level,
                     "sig_fired": base.fired, "sig_level": base.level})
    return rows


def split_rows(rows, split):
    return rows if split == "all" else [r for r in rows if r["split"] == split]


def fmt_rate(a: int, b: int) -> str:
    return f"{a}/{b} ({a / b:.0%})" if b else "-"


def metrics(rows: list[dict], have_gemma: bool) -> dict:
    mal = [r for r in rows if r["label"] == "malicious"]
    ben = [r for r in rows if r["label"] == "benign"]
    out = {"n": f"{len(mal)} mal / {len(ben)} ben"}

    def cnt(group, key, floor):
        return sum(C.at_least(r[key], floor) for r in group)
    out["sig_det_c"] = fmt_rate(cnt(mal, "sig_level", "caution"), len(mal))
    out["sig_det_d"] = fmt_rate(cnt(mal, "sig_level", "danger"), len(mal))
    out["sig_fp_c"] = fmt_rate(cnt(ben, "sig_level", "caution"), len(ben))
    out["sig_fp_d"] = fmt_rate(cnt(ben, "sig_level", "danger"), len(ben))
    if not have_gemma:
        for k in ("det_c", "det_d", "fp_c", "fp_d", "parse", "valid", "g_caught", "g_strict", "g_fa", "g_sus",
                  "lat", "feat_used"):
            out[k] = NOT_RUN
        return out
    out["det_c"] = fmt_rate(cnt(mal, "level", "caution"), len(mal))
    out["det_d"] = fmt_rate(cnt(mal, "level", "danger"), len(mal))
    out["fp_c"] = fmt_rate(cnt(ben, "level", "caution"), len(ben))
    out["fp_d"] = fmt_rate(cnt(ben, "level", "danger"), len(ben))
    recs = [r["rec"] for r in rows]
    ok = [x for x in recs if "error" not in x]
    parsed = [x for x in ok if x.get("output") is not None]
    valid = [x for x in parsed if not x.get("schema_errors")]
    out["parse"] = fmt_rate(len(parsed), len(recs))
    out["valid"] = fmt_rate(len(valid), len(recs))
    out["feat_used"] = fmt_rate(sum(r["feat_used"] for r in rows), len(rows))
    pm = [x for x in parsed if x["label"] == "malicious"]
    pb = [x for x in parsed if x["label"] == "benign"]
    out["g_caught"] = fmt_rate(sum(x["output"].get("verdict") in ("malicious", "suspicious") for x in pm), len(pm))
    out["g_strict"] = fmt_rate(sum(x["output"].get("verdict") == "malicious" for x in pm), len(pm))
    out["g_fa"] = fmt_rate(sum(x["output"].get("verdict") == "malicious" for x in pb), len(pb))
    out["g_sus"] = fmt_rate(sum(x["output"].get("verdict") == "suspicious" for x in pb), len(pb))
    lat = C.latency_stats([x["latency"] for x in ok])
    out["lat"] = (f"{lat['mean']:.2f} / {lat['median']:.2f} / {lat['p95']:.2f}" if lat["n"] else "-")
    return out


METRIC_ROWS = [
    ("cases", "n"),
    ("Gemma JSON parse rate", "parse"),
    ("Gemma schema-valid rate", "valid"),
    ("features used by rules (valid)", "feat_used"),
    ("**malicious detected, rules ≥ caution**", "det_c"),
    ("malicious detected, rules ≥ danger", "det_d"),
    ("**benign false positive, rules ≥ caution**", "fp_c"),
    ("benign false positive, rules ≥ danger", "fp_d"),
    ("Gemma verdict: malicious caught (malicious or suspicious)", "g_caught"),
    ("Gemma verdict: malicious strict", "g_strict"),
    ("Gemma verdict: benign → malicious", "g_fa"),
    ("Gemma verdict: benign → suspicious", "g_sus"),
    ("latency s (mean / median / p95)", "lat"),
    ("signals only: malicious ≥ caution", "sig_det_c"),
    ("signals only: malicious ≥ danger", "sig_det_d"),
    ("signals only: benign ≥ caution", "sig_fp_c"),
    ("signals only: benign ≥ danger", "sig_fp_d"),
]


def metrics_table(rows, have_gemma) -> str:
    ms = {s: metrics(split_rows(rows, s), have_gemma) for s in SPLITS}
    out = ["| metric | split A | split B | all |", "|---|---|---|---|"]
    for name, key in METRIC_ROWS:
        out.append(f"| {name} | " + " | ".join(ms[s][key] for s in SPLITS) + " |")
    return "\n".join(out)


def confusion_table(rows) -> str:
    verdicts = ["malicious", "suspicious", "safe", "bad verdict", "parse fail / error"]
    out = []
    for label in ("malicious", "benign"):
        grp = [r for r in rows if r["label"] == label]
        cnt = Counter()
        for r in grp:
            obj = (r["rec"] or {}).get("output")
            v = obj.get("verdict") if obj is not None else "parse fail / error"
            cnt[(v if v in verdicts else "bad verdict", r["level"])] += 1
        out.append(f"\n**label = {label}** (rows: Gemma verdict, columns: rule level)\n")
        out.append("| Gemma \\ rules | " + " | ".join(C.LEVELS) + " |")
        out.append("|---|" + "---|" * len(C.LEVELS))
        for v in verdicts:
            out.append(f"| {v} | " + " | ".join(str(cnt[(v, lv)]) for lv in C.LEVELS) + " |")
    return "\n".join(out)


def firing_table(rows, key="fired") -> str:
    ids = [r["id"] for r in RULES["rules"]]
    mal = [r for r in rows if r["label"] == "malicious"]
    ben = [r for r in rows if r["label"] == "benign"]
    out = ["| rule | weight | fired on malicious | fired on benign |", "|---|---|---|---|"]
    for rule in RULES["rules"]:
        rid = rule["id"]
        w = rule.get("weight", rule.get("value"))
        out.append(f"| {rid} | {w}{' (floor)' if rule['kind'] == 'floor' else ''} | "
                   f"{fmt_rate(sum(rid in r[key] for r in mal), len(mal))} | "
                   f"{fmt_rate(sum(rid in r[key] for r in ben), len(ben))} |")
    assert set(ids) >= {x for r in rows for x in r[key]}
    return "\n".join(out)


def dataset_section(cases: list[dict], log: dict | None) -> str:
    lines = []
    if log:
        op = log.get("openphish", {})
        tr = log.get("tranco", {})
        lines.append(f"- Collected {log.get('started_at')} → {log.get('finished_at')} (UTC) by `eval/real/collect.py`, "
                     f"seed {log.get('seed')}, sandbox: {log.get('sandbox')}.")
        lines.append(f"- Malicious source: OpenPhish community feed ({op.get('url')}), downloaded "
                     f"{op.get('downloaded_at')}, Last-Modified {op.get('last_modified')}; "
                     f"{op.get('urls_in_feed')} URLs, {op.get('distinct_registrable_domains')} distinct registrable "
                     f"domains" + (f"; ERROR {op['error']}" if op.get("error") else "") + ".")
        lines.append(f"- Benign source: Tranco list {tr.get('list_id')} (created {tr.get('created_on')}), ranks "
                     f"{tr.get('rank_range')}, uniform random with the same seed.")
        lines.append("\n| label | attempted | kept | excluded | excluded by reason |\n|---|---|---|---|---|")
        for label, v in log.get("labels", {}).items():
            reasons = ", ".join(f"{k} {n}" for k, n in v.get("excluded_by_reason", {}).items()) or "-"
            lines.append(f"| {label} | {v['attempted']} | {v['kept']} | {v['excluded_total']} | {reasons} |")
        for label, v in log.get("labels", {}).items():
            if v.get("http_error_breakdown"):
                b = "; ".join(f"{k} ×{n}" for k, n in v["http_error_breakdown"].items())
                lines.append(f"\n`http_error` detail ({label}): {b}")
    sc = Counter((c["label"], c["split"]) for c in cases)
    lines.append(f"\nSplit (stratified, sha256(id)): A = {sc[('malicious', 'A')]} mal + {sc[('benign', 'A')]} ben, "
                 f"B = {sc[('malicious', 'B')]} mal + {sc[('benign', 'B')]} ben.")
    return "\n".join(lines)


def tuning_section(rows, have_gemma, proposal_path: Path, do_tune: bool) -> tuple[str, dict | None]:
    if not have_gemma:
        return (f"**{NOT_RUN}.** Tuning needs Gemma features (model rules carry most of the weight); "
                "run M6b first, then `score_real.py --tune`."), None
    if not do_tune:
        if proposal_path.exists():
            p = json.loads(proposal_path.read_text())
            return render_proposal(p), p
        return "Not run (use `--tune`).", None
    base = add_weights(RULES)
    rows_a, rows_b = split_rows(rows, "A"), split_rows(rows, "B")
    # sanity: fast re-scoring == scoring.score() with the shipped weights
    for r in rows:
        s, lv = fast_score(r["fired"], base, FLOORS)
        assert (s, lv) == (r["score"], r["level"]), (r["id"], s, lv, r["score"], r["level"])
    w, trace = tune(rows_a, base)
    changes = {k: {"from": base[k], "to": w[k]} for k in base if w[k] != base[k]}
    p = {
        "generated_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "status": "PROPOSAL ONLY - contracts/scoring_rules.json was not modified",
        "tuned_on": "split A", "reported_on": "split B",
        "objective": "det>=caution + det>=danger - 2*fp>=caution - 4*fp>=danger - 0.002*sum|dw| (rates on split A)",
        "search": "coordinate descent over add-rule weights, steps ±5/10/15, bounds [0,60]; rules not firing on A unchanged",
        "n_A": {"malicious": sum(r["label"] == "malicious" for r in rows_a),
                "benign": sum(r["label"] == "benign" for r in rows_a)},
        "changes": changes,
        "trace": trace,
        "before": {"A": rates(rows_a, base), "B": rates(rows_b, base)},
        "after": {"A": rates(rows_a, w), "B": rates(rows_b, w)},
        "caveat": "Small sample (~30+30 per split); treat as a direction, not a final weight set. Applying it needs "
                  "contracts/scoring_rules.json + scoring_vectors.json updates and both engines re-tested.",
    }
    proposal_path.write_text(json.dumps(p, indent=1) + "\n", encoding="utf-8")
    return render_proposal(p), p


def render_proposal(p: dict) -> str:
    def r(m):
        return (f"det≥caution {m['det_caution']:.0%}, det≥danger {m['det_danger']:.0%}, "
                f"FP≥caution {m['fp_caution']:.0%}, FP≥danger {m['fp_danger']:.0%}")
    ch = ", ".join(f"`{k}` {v['from']}→{v['to']}" for k, v in p["changes"].items()) or "no change improved split A"
    return "\n".join([
        f"Proposal ({p['status']}; `eval/real/tuning_proposal.json`): {ch}.",
        f"Objective: {p['objective']}; {p['search']}.",
        "",
        "| | split A (tuned on) | split B (held out) |", "|---|---|---|",
        f"| before | {r(p['before']['A'])} | {r(p['before']['B'])} |",
        f"| after | {r(p['after']['A'])} | {r(p['after']['B'])} |",
        "", f"_{p['caveat']}_",
    ])


def write_report(path: Path, cases, rows, have_gemma, log, results_path, tuning_md):
    ts = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    try:  # repo-relative in the report: no machine-specific absolute paths in committed files
        results_path = Path(results_path).resolve().relative_to(HERE.parents[1])
    except ValueError:
        pass
    gemma_note = (f"Results: `{results_path}` ({len(rows)} records)." if have_gemma else
                  f"**Gemma (M6b): {NOT_RUN}.** Every Gemma-dependent cell below says '{NOT_RUN}'. "
                  "The signals-only rows ARE real: rule engine over the collected deterministic signals with "
                  "features = null.")
    md = f"""# RedFlag — real-page evaluation (M6)

_Generated {ts} by `eval/real/score_real.py`. Numbers come only from files in `eval/real/`; nothing is estimated._

## Data (M6a)

{dataset_section(cases, log)}

Gemma input = the exact `redflag-gemma-test/cases.jsonl` shape (`signals` + `page`); `safe_browsing` is
`"unknown"` for every real case (no key). Exclusions: sandbox/navigation errors, HTTP ≥ 400, < 40 visible
characters, parking / suspended / takedown / not-found / bot-challenge pages, malicious URLs that redirect to a
Tranco top-100k site, duplicate final domains (see `collect_log.json` for every excluded URL and reason).

## Run (M6b)

Runtime = the M0 choice from last night's REPORT: LiteRT-LM Python 0.19.0, GPU, Gemma 4 E4B
(`models/gemma-4-E4B-it.litertlm`), free-form JSON, thinking off, `system_prompt_v2.txt`, temperature 0.
Command: `eval/real/run_m6b.sh`. {gemma_note}

Scoring: `backend/redflag_backend/scoring.py` over `contracts/scoring_rules.json` (shipped weights). Gemma
features are used only when the output parses AND is schema-valid; otherwise the case is scored on signals only
(what the app does). Levels: safe < caution (≥30) < danger (≥60) < critical (≥85).

## Results

{metrics_table(rows, have_gemma)}

### Gemma's own verdict vs rule level
{confusion_table(rows) if have_gemma else NOT_RUN}

### Rule firing counts (Gemma features + signals, all splits)
{firing_table(rows) if have_gemma else NOT_RUN}

### Rule firing counts (signals only, all splits)
{firing_table(rows, "sig_fired")}

## Weight tuning (tune on A, report on B)

{tuning_md}

## Caveats

- 60 + 60 pages from one day's feeds; OpenPhish entries are confirmed phish but kits cloak (a page kept here
  is what the sandbox saw, which may be a decoy). Benign = Tranco 200k–1M, i.e. mostly small but established sites.
- `domain_age_days` from RDAP and `tls` from crt.sh were looked up at collection time; Safe Browsing is not used.
- Splits are ~30 + 30 cases: a one-case change moves a rate by ~3 points.
"""
    path.write_text(md, encoding="utf-8")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cases", default=str(HERE / "cases_real.jsonl"))
    ap.add_argument("--results", default=str(HERE / "results-gemma.jsonl"))
    ap.add_argument("--log", default=str(HERE / "collect_log.json"))
    ap.add_argument("--report", default=str(HERE / "REAL_REPORT.md"))
    ap.add_argument("--proposal", default=str(HERE / "tuning_proposal.json"))
    ap.add_argument("--tune", action="store_true")
    ap.add_argument("--repair", action="store_true", help="score like the app: FeatureRepair + (v2) fail-safe flags")
    ap.add_argument("--no-failsafe", action="store_true", help="with --repair: skip the D2/D3 clamps (isolate the weight change)")
    args = ap.parse_args()
    C.REPAIR = args.repair
    C.FAILSAFE = not args.no_failsafe

    cases = C.load_jsonl(args.cases)
    assign_splits_if_missing(cases)
    rp = Path(args.results)
    results = C.load_jsonl(rp) if rp.exists() and rp.stat().st_size else None
    have_gemma = results is not None
    rows = evaluate(cases, results)
    lp = Path(args.log)
    log = json.loads(lp.read_text()) if lp.exists() else None

    print(f"cases {len(cases)}  results {'-' if results is None else len(results)}  rows {len(rows)}")
    print(metrics_table(rows, have_gemma))
    if have_gemma:
        print(confusion_table(rows))
        print("\n" + firing_table(rows))
    print("\nsignals only:\n" + firing_table(rows, "sig_fired"))
    tuning_md, prop = tuning_section(rows, have_gemma, Path(args.proposal), args.tune)
    print("\n" + tuning_md)
    write_report(Path(args.report), cases, rows, have_gemma, log, args.results, tuning_md)
    print(f"\nreport -> {args.report}")


if __name__ == "__main__":
    main()
