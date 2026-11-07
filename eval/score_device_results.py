#!/usr/bin/env python3
"""Score Android DebugBenchmarkActivity output (or any run_eval-like jsonl) against a cases file.

Device record (one per line):
  {id, run, label, latency (s, float), completion_tokens, prompt_tokens, thought, clean,
   parse_error, output, raw, schema_errors, backend, device}

Everything is RE-DERIVED from `raw` with the frozen redflag-gemma-test/run_eval.py functions
(extract_json, validate, check, grounding), so the phone and the Mac are scored by the same
code. Prints:
  1. run_eval.summarize()-style block (parsed, clean, valid, field accuracy, malicious caught,
     benign false alarm, evidence verbatim, latency mean/median/max)
  2. the summarize_results.py table row (same stats() function) for side-by-side comparison
  3. rule-engine levels (backend/redflag_backend/scoring.py) per case and per label:
     Gemma features + signals, and signals only.

  python3 eval/score_device_results.py eval/device/raw/bench.jsonl
  python3 eval/score_device_results.py bench.jsonl --cases redflag-gemma-test/cases_holdout.jsonl
"""
from __future__ import annotations

import argparse
import statistics
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import rf_eval_common as C  # noqa: E402


def table_row(name: str, records: list[dict]) -> str:
    sr = C.summarize_results
    s = sr.stats(records, None)
    cols = (["run", "parsed", "clean", "valid"] + [sr.SHORT[k] for k in sr.FIELDS] +
            ["mal caught (strict)", "benign FA (susp)", "evidence", "lat mean/max s", "out tok", "thought"])
    row = [name, s["parsed"], s["clean"], s["valid"]] + [s[k] for k in sr.FIELDS] + \
          [s["caught"], s["false_alarm"], s["evidence"], s["lat"], s["tok"], s["thought"]]
    return "| " + " | ".join(cols) + " |\n|" + "---|" * len(cols) + "\n| " + " | ".join(row) + " |"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("results", nargs="+", help="device benchmark jsonl file(s)")
    ap.add_argument("--cases", default=str(C.ROOT / "redflag-gemma-test" / "cases.jsonl"))
    ap.add_argument("--no-cases-ok", action="store_true", help="score records whose id is not in --cases")
    args = ap.parse_args()

    cases = {c["id"]: c for c in C.load_jsonl(args.cases)}
    for path in args.results:
        recs_in = C.load_jsonl(path)
        missing = sorted({r.get("id") for r in recs_in} - set(cases))
        if missing and not args.no_cases_ok:
            print(f"WARNING: {len(missing)} record id(s) not in {args.cases}: {missing[:8]} (skipped)")
        recs_in = [r for r in recs_in if r.get("id") in cases or args.no_cases_ok]
        records = [C.rescore_record(r, cases.get(r.get("id"))) for r in recs_in]
        label_mismatch = [r["id"] for r in recs_in if r.get("id") in cases and r.get("label")
                          and r["label"] != cases[r["id"]]["label"]]
        devs = Counter(f"{r.get('backend', '?')}@{r.get('device', '?')}" for r in recs_in)

        print("=" * 72)
        print(f"{path}\n  cases={args.cases}  records={len(records)}  backend@device={dict(devs)}")
        disagree = [r["id"] for r in records if "error" not in r and not r["_reparse_agrees"]]
        if disagree:
            print(f"  NOTE: recorded output/clean differs from run_eval re-parse for {len(disagree)}: {disagree[:8]}")
        if label_mismatch:
            print(f"  NOTE: record label differs from cases label for {label_mismatch[:8]} (cases label used)")
        print("-" * 72)
        C.print_run_eval_summary(records)
        print("\nsummarize_results.py row:")
        print(table_row(Path(path).stem, records))

        # ---- rule engine
        print("\nrule engine (backend scoring.py; features only if parsed AND schema-valid)")
        print(f"  {'id':<24} {'label':<9} {'gemma':<10} {'score':>5} {'level':<8} {'sig-only':<8} fired")
        by_label: dict[str, list[tuple[str, str]]] = {}
        for r in records:
            case = cases.get(r["id"])
            if case is None or "error" in r:
                continue
            feats = C.valid_features(r)
            res = C.rule_score(case, feats)
            base = C.rule_score(case, None)
            by_label.setdefault(case["label"], []).append((res.level, base.level))
            verdict = (r.get("output") or {}).get("verdict", "-") if r.get("output") else "PARSEFAIL"
            print(f"  {r['id']:<24} {case['label']:<9} {str(verdict):<10} {res.score:>5} {res.level:<8} "
                  f"{base.level:<8} {','.join(res.fired) or '-'}")
        for label, rows in sorted(by_label.items()):
            n = len(rows)
            caut = sum(C.at_least(lv, "caution") for lv, _ in rows)
            dang = sum(C.at_least(lv, "danger") for lv, _ in rows)
            s_caut = sum(C.at_least(b, "caution") for _, b in rows)
            dist = Counter(lv for lv, _ in rows)
            what = "detected" if label == "malicious" else "flagged (false positive)"
            print(f"  {label:<9} {what}: >=caution {C.pct(caut, n)}  >=danger {C.pct(dang, n)}"
                  f"  | signals-only >=caution {C.pct(s_caut, n)}  | levels {dict(dist)}")
        ok = [r for r in records if "error" not in r]
        if ok:
            lat = C.latency_stats([r["latency"] for r in ok])
            print(f"  latency (s) mean {lat['mean']:.2f}  median {lat['median']:.2f}  p95 {lat['p95']:.2f}"
                  f"  max {lat['max']:.2f}")
            pt = [r["prompt_tokens"] for r in ok if r.get("prompt_tokens")]
            ct = [r["completion_tokens"] for r in ok if r.get("completion_tokens")]
            if pt and ct:
                print(f"  tokens prompt mean {statistics.mean(pt):.0f}  completion mean {statistics.mean(ct):.0f}")


if __name__ == "__main__":
    main()
