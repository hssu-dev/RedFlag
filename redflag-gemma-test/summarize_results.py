#!/usr/bin/env python3
"""Aggregate results/*.jsonl from run_eval.py into one markdown table.

Metric definitions mirror run_eval.summarize(). The phone-latency column is a
PROJECTION, not a measurement: measured prefill/decode token counts divided by
the published LiteRT-LM S26 Ultra GPU throughput for the model (model cards).

  python3 summarize_results.py results/litert-e4b-*.jsonl
"""
import json
import statistics
import sys
from pathlib import Path

FIELDS = ["claimed_brand", "brand_matches_domain", "requested_actions", "urgency_or_threat",
          "too_good_to_be_true", "addresses_ai_analyzer", "verdict"]
SHORT = {"claimed_brand": "brand", "brand_matches_domain": "brand=dom", "requested_actions": "actions",
         "urgency_or_threat": "urgency", "too_good_to_be_true": "tgtbt", "addresses_ai_analyzer": "ai_inj",
         "verdict": "verdict"}
# LiteRT-LM model cards, Samsung S26 Ultra, GPU, no speculative decoding (tokens/s).
# third value: decode tok/s WITH speculative decoding, "Free form" row of the same card.
PHONE = {"e4b": (1293, 22.1, 36.7), "e2b": (3808, 52.1, 66.5)}


def load(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def frac(a, b):
    return f"{a}/{b}" if b else "-"


def stats(records, phone=None):
    ok = [r for r in records if "error" not in r]
    parsed = [r for r in ok if r.get("output") is not None]
    valid = [r for r in parsed if not r.get("schema_errors")]
    out = {"n": len(records), "errors": len(records) - len(ok),
           "parsed": frac(len(parsed), len(ok)), "clean": frac(sum(r["clean"] for r in ok), len(ok)),
           "valid": frac(len(valid), len(ok))}
    for k in FIELDS:
        v = [r["checks"][k] for r in parsed if k in r.get("checks", {})]
        out[k] = frac(sum(v), len(v))
    mal = [r for r in parsed if r["label"] == "malicious"]
    ben = [r for r in parsed if r["label"] == "benign"]
    out["caught"] = (f"{sum(r['output'].get('verdict') in ('malicious', 'suspicious') for r in mal)}/{len(mal)}"
                     f" ({sum(r['output'].get('verdict') == 'malicious' for r in mal)})")
    out["false_alarm"] = (f"{sum(r['output'].get('verdict') == 'malicious' for r in ben)}/{len(ben)}"
                          f" ({sum(r['output'].get('verdict') == 'suspicious' for r in ben)})")
    g = [r["grounding"] for r in parsed if r.get("grounding") is not None]
    out["evidence"] = f"{statistics.mean(g):.0%}" if g else "-"
    lat = [r["latency"] for r in ok]
    out["lat"] = f"{statistics.mean(lat):.2f} / {max(lat):.2f}" if lat else "-"
    toks = [r["completion_tokens"] for r in ok if r.get("completion_tokens")]
    out["tok"] = f"{statistics.mean(toks):.0f}" if toks else "-"
    out["thought"] = frac(sum(bool(r.get("thought")) for r in ok), len(ok))
    if phone:
        pre, dec, spec = phone
        rows = [r for r in ok if r.get("prompt_tokens") and r.get("completion_tokens")]
        est = [r["prompt_tokens"] / pre + r["completion_tokens"] / dec for r in rows]
        est_spec = [r["prompt_tokens"] / pre + r["completion_tokens"] / spec for r in rows]
        out["phone"] = f"{statistics.mean(est):.1f} / {max(est):.1f}" if est else "-"
        out["phone_spec"] = f"{statistics.mean(est_spec):.1f} / {max(est_spec):.1f}" if est else "-"
    else:
        out["phone"] = out["phone_spec"] = "-"
    return out


def main():
    paths = sys.argv[1:]
    cols = (["run", "parsed", "clean", "valid"] + [SHORT[k] for k in FIELDS] +
            ["mal caught (strict)", "benign FA (susp)", "evidence", "lat mean/max s", "out tok", "thought",
             "S26 est. s (mean/max)", "S26+spec est. s"])
    print("| " + " | ".join(cols) + " |")
    print("|" + "---|" * len(cols))
    for p in paths:
        name = Path(p).stem
        phone = next((v for k, v in PHONE.items() if k in name and "litert" in name), None)
        s = stats(load(p), phone)
        row = [name, s["parsed"], s["clean"], s["valid"]] + [s[k] for k in FIELDS] + \
              [s["caught"], s["false_alarm"], s["evidence"], s["lat"], s["tok"], s["thought"], s["phone"], s["phone_spec"]]
        print("| " + " | ".join(row) + " |")


if __name__ == "__main__":
    main()
