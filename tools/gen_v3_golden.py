"""Golden files for the v3 Gemma output pipeline parity (Python reference -> Kotlin tests):
salvage + canonical keys + missing keys over EVERY raw model output we have, sanitize over every page text,
evidence enforcement, follow-up prompt text."""
import glob, json, sys
sys.path.insert(0, "backend")
from redflag_backend import gemma_repair as G
from redflag_backend.prompt_text import sanitize_page_text

out_dir = "android/core/src/test/resources/golden"
raws = []
for p in sorted(glob.glob("redflag-gemma-test/results/*.jsonl")) + ["eval/real/results-gemma.jsonl"]:
    for line in open(p, encoding="utf-8"):
        r = json.loads(line)
        if isinstance(r.get("raw"), str):
            raws.append(r["raw"])
raws += ['{"evidence": ["a \\"q\\""], "addresses_ai_ai_analyzer": true}', 'no json here', '{"verdict": "safe"', '"claimed_brand_brand": "X", "urgency_orthreat": false']
with open(f"{out_dir}/v3_salvage.jsonl", "w", encoding="utf-8") as g:
    for raw in raws:
        obj, notes = G.salvage(raw)
        it = G.interpret(raw, None, [])
        fu = G.followup_for(raw)
        g.write(json.dumps({"raw": raw, "obj": obj, "notes": notes, "missing": G.missing_keys(obj),
                            "interp": it, "followup": fu[0] if fu else None}, ensure_ascii=False) + "\n")
texts = []
# eval/real/cases_real.jsonl is local-only (gitignored, live phishing URLs): run eval/real/collect.py first to regenerate.
for p in ["redflag-gemma-test/cases.jsonl", "redflag-gemma-test/cases_holdout.jsonl", "eval/real/cases_real.jsonl"]:
    for line in open(p, encoding="utf-8"):
        pg = json.loads(line)["page"]
        texts += [pg.get("title", ""), pg.get("visible_text", ""), pg.get("hidden_text", "")]
# Keep live phishing domains out of the committed golden: every malicious registrable domain from the
# local cases_real.jsonl becomes a same-length "xxx.test" (same length, so truncation is unchanged).
mal = {json.loads(l)["signals"]["registrable_domain"] for l in open("eval/real/cases_real.jsonl", encoding="utf-8")
       if json.loads(l)["label"] == "malicious"}
for d in sorted(mal, key=len, reverse=True):
    if len(d) > 5:
        texts = [t.replace(d, "x" * (len(d) - 5) + ".test") for t in texts]
texts += ['He said "hi"\nand\tleft\\x', " line sep\u0085nel\x00null\x7fdel", "  many    spaces  ", "", " nbsp　ideo"]
keys = ["addresses_ai_ai_analyzer", "too_good_to_be_be_true", "too_good_to_to_be_true", "urgency_orthreat", "too_goodtobeatrue",
        "too_goodtobe_true", "claimed_brand_brand", "brand_match_domain", "verdicts", "price", "evidence", "Confidence", "page", "type"]
evid = [["one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen", "a", "b", "c"], ["  spaced   out  "], []]
with open(f"{out_dir}/v3_misc.json", "w", encoding="utf-8") as g:
    json.dump({
        "sanitize": [[t, sanitize_page_text(t)] for t in texts],
        "canonical": [[k, G.canonical_key(k)] for k in keys],
        "evidence": [[e, G.enforce_evidence({"evidence": e})["evidence"]] for e in evid],
        "followup": [[m, G.followup_prompt(m)] for m in (["verdict"], ["too_good_to_be_true", "claimed_brand", "verdict"], G.KEYS[1:])],
        "merge": [[{"a": 1, "verdict": "safe"}, {"verdict": "malicious", "confidence": "low", "x": 2}, ["confidence", "x"],
                   G.merge({"a": 1, "verdict": "safe"}, {"verdict": "malicious", "confidence": "low", "x": 2}, ["confidence", "x"])]],
    }, g, ensure_ascii=False, indent=1)
print(len(raws), "raws,", len(texts), "texts")
