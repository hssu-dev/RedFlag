"""eval/page_state/results.jsonl -> REPORT.md (Gemma vs code signatures vs combined)."""
import json, statistics, sys
from collections import Counter, defaultdict
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
R = [json.loads(l) for l in open(ROOT / "eval/page_state" / (sys.argv[1] if len(sys.argv) > 1 else "results.jsonl"), encoding="utf-8")]
STATES = ["loaded", "challenge", "blocked", "host_warning", "unavailable"]

def combined(r):
    # what the app does: code first; Gemma only when code says "loaded" but the page is ambiguous
    return r["gemma"] if r.get("ambiguous") and r["gemma"] in ("challenge", "blocked", "unavailable", "host_warning") else r["code"]

cases = {json.loads(l)["id"]: json.loads(l) for l in open(ROOT / "eval/page_state/cases.jsonl", encoding="utf-8")}
import importlib.util, sys
_spec = importlib.util.spec_from_file_location("rps", ROOT / "eval/page_state/run_page_state.py")
_m = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(_m)
def _empty(p):
    return not (p["title"] or "").strip() and not (p["text"] or "").strip() and p["form_count"] == 0
EXCLUDED = [r for r in R if _empty(cases[r["id"]]["page"])]
R = [r for r in R if not _empty(cases[r["id"]]["page"])]  # empty observations: the app stops at "empty_page" before any model
for r in R:
    p = cases[r["id"]]["page"]
    r["code"] = _m.code_state(p)  # current signatures (same as the app's PageStates.code)
    r["ambiguous"] = r["code"] == "loaded" and p["form_count"] == 0 and ((p["http_status"] or 200) >= 400 or len((p["text"] or "").strip()) < 300)

def acc(key, rows):
    return sum(r[key] == r["label"] for r in rows), len(rows)

lines = ["# Page state: can Gemma tell the site's own page from a CDN check / block / host warning / dead site?", "",
         "Mac, same LiteRT-LM runtime + gemma-4-E4B as the phone, greedy, thinking off, separate short conversation",
         "(contracts/gemma_page_state.txt). Cases: eval/real sandbox observations (real) + hand-written vendor pages (synthetic).",
         f"Excluded: {len(EXCLUDED)} empty observations (no title, text or inputs): the app already stops there ('empty_page', never safe), and Gemma called all of them 'unavailable'.", ""]
for src in ("real", "synthetic", "all"):
    rows = [r for r in R if src == "all" or r["source"] == src]
    g = acc("gemma", rows); c = acc("code", rows)
    cb = (sum(combined(r) == r["label"] for r in rows), len(rows))
    lines.append(f"- **{src}** ({len(rows)}): Gemma {g[0]}/{g[1]} ({100*g[0]/g[1]:.0f}%) · code {c[0]}/{c[1]} ({100*c[0]/c[1]:.0f}%) · code+Gemma(ambiguous only) {cb[0]}/{cb[1]} ({100*cb[0]/cb[1]:.0f}%)")
ms = [r["ms"] for r in R]
lines += ["", f"Latency per Gemma call (Mac GPU): median {statistics.median(ms):.0f} ms, p90 {sorted(ms)[int(len(ms)*0.9)]} ms.",
          f"Gemma output unparseable: {sum(r['gemma'] is None for r in R)}/{len(R)}. Ambiguous cases that would reach Gemma on the phone: {sum(r['ambiguous'] for r in R)}/{len(R)}.", "",
          "## Per label (Gemma / code)", "", "| label | n | Gemma | code |", "|---|---|---|---|"]
for s in STATES:
    rows = [r for r in R if r["label"] == s]
    if rows:
        lines.append(f"| {s} | {len(rows)} | {acc('gemma', rows)[0]} | {acc('code', rows)[0]} |")
lines += ["", "## Gemma confusion (label -> answer)", ""]
conf = Counter((r["label"], r["gemma"]) for r in R if r["gemma"] != r["label"])
for (l, g), n in conf.most_common():
    lines.append(f"- {l} -> {g}: {n}")
lines += ["", "## Every miss", "", "| id | source | label | Gemma | code |", "|---|---|---|---|---|"]
for r in R:
    if r["gemma"] != r["label"] or r["code"] != r["label"]:
        lines.append(f"| {r['id'][:48]} | {r['source']} | {r['label']} | {r['gemma']} | {r['code']} |")
(ROOT / "eval/page_state" / (sys.argv[2] if len(sys.argv) > 2 else "REPORT.md")).write_text("\n".join(lines) + "\n", encoding="utf-8")
print("\n".join(lines))
