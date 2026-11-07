"""eval/followups/results.jsonl -> eval/followups/REPORT.md (Turn 2 friction/coverage, Turn D)."""
import json
import statistics
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
rows = [json.loads(l) for l in open(HERE / "results.jsonl")]
# 2026-11-04 user decision (lite_followups.resolve): when the code said "fine", a Gemma request needs a concrete reason.
# Re-applied to the stored Gemma answers (no new inference); the raw plan stays in results.jsonl as plan_raw.
for r in rows:
    for t in r["turn2"].values():
        p = t.get("plan")
        t["plan_raw"] = p
        if p and t["decision"] == "fine" and not [x for x in (t.get("parsed") or {}).get("reasons", []) if x != "other"]:
            t["plan"] = None


def pct(a, b):
    return f"{a}/{b} ({100 * a / b:.0f}%)" if b else "0/0"


out = ["# v4 follow-up turns on eval/real (Mac, LiteRT-LM GPU, Gemma 4 E4B, greedy)", "",
       "Turn 1 answers: `eval/real/results-gemma.jsonl` (same prompt/model as the phone). Follow-up prompts, parsing and",
       "decisions: `backend/redflag_backend/lite_followups.py` (byte-identical twin of the phone code, `contracts/followup_vectors.json`).",
       "Script: `eval/followups/run_followups.py`, raw: `results.jsonl`.", ""]
for src in ("unknown", "sms"):
    out += [f"## Turn 2 — source_app = {src}", ""]
    out += ["| | benign (60) | phishing (60) |", "|---|---|---|"]
    by = {lab: [r for r in rows if r["label"] == lab] for lab in ("benign", "malicious")}
    def line(name, f):
        out.append(f"| {name} | " + " | ".join(pct(sum(1 for r in by[l] if f(r["turn2"][src])), len(by[l])) for l in ("benign", "malicious")) + " |")
    line("① code: exclude", lambda t: t["decision"] == "exclude")
    line("① code: needed", lambda t: t["decision"] == "needed")
    line("① code: fine → Gemma asked", lambda t: t["decision"] == "fine")
    line("context card before the 'other'-only filter", lambda t: t.get("plan_raw") is not None)
    line("**context card shown**", lambda t: (t.get("plan") or None) is not None)
    line("… by Gemma request", lambda t: (t.get("plan") or {}).get("origin") == "gemma")
    line("… by code default (Gemma said none/failed)", lambda t: (t.get("plan") or {}).get("origin") == "code_default")
    line("… consent card (auto context)", lambda t: (t.get("plan") or {}).get("auto") is True)
    line("… question card", lambda t: (t.get("plan") or {}).get("question") is not None)
    line("fine → Gemma: no_context_needed", lambda t: t["decision"] == "fine" and (t.get("parsed") or {}).get("none"))
    asked = [r["turn2"][src] for r in rows if r["turn2"][src]["decision"] != "exclude"]
    valid = sum(1 for t in asked if (t.get("parsed") or {}).get("recognized", 0) > 0)
    native = sum(1 for t in asked if t.get("calls") and not t.get("text"))
    lat = [t["latency"] for t in asked if "latency" in t]
    qs = Counter((t.get("plan") or {}).get("question") for t in asked if (t.get("plan") or {}).get("question"))
    rs = Counter(x for t in asked for x in (t.get("plan") or {}).get("reasons", []))
    out += ["", f"- Gemma calls: {len(asked)}; recognized tool call {pct(valid, len(asked))}; native tool-call (no text) {pct(native, len(asked))}",
            f"- latency (Mac GPU): mean {statistics.mean(lat):.2f} s, median {statistics.median(lat):.2f} s, max {max(lat):.2f} s" if lat else "- latency: n/a",
            f"- questions: {dict(qs)}", f"- reasons: {dict(rs.most_common())}", ""]
    for split in ("A", "B"):
        sb = [r for r in rows if r.get("split") == split]
        b = [r for r in sb if r["label"] == "benign"]; m = [r for r in sb if r["label"] == "malicious"]
        out.append(f"- split {split}: card shown benign {pct(sum(1 for r in b if r['turn2'][src].get('plan')), len(b))}, "
                   f"phishing {pct(sum(1 for r in m if r['turn2'][src].get('plan')), len(m))}")
    out.append("")
    # what benign pages get a card
    fr = [r for r in rows if r["label"] == "benign" and r["turn2"][src].get("plan")]
    if fr:
        out += [f"Benign pages with a card ({src}):", ""]
        for r in fr[:25]:
            p = r["turn2"][src]["plan"]
            out.append(f"- `{r['id']}` score {r['score']} ({r['turn2'][src]['decision']}) → {'consent' if p['auto'] else ''}{' ' + p['question'] if p['question'] else ''} {p['reasons']} [{p['origin']}]")
        out.append("")
out += ["## Turn D — destination relation", ""]
d = [r for r in rows if r.get("dest")]
out.append(f"Cases with a cross-site personal-data destination (form actions only offline): {len(d)}/120")
for r in d:
    x = r["dest"]
    out.append(f"- `{r['id']}` ({r['label']}) {[c['domain'] for c in x['candidates']]} → {x['answer']} · {x['latency']} s · score {r['score']}→{x['score_after']} ({r['level']}→{x['level_after']})")
out += ["", "Limit: script/XHR endpoints were not collected for these 120 pages (they are mostly offline now), so Turn D",
        "and the messenger-bot / form-service code rules are only exercised by the demo pages and the vectors.", ""]
(HERE / "REPORT.md").write_text("\n".join(out) + "\n", encoding="utf-8")
print("\n".join(out))
