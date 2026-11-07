"""Golden file for FeatureRepair parity: every parsed-but-schema-invalid Gemma output we have
(last night's redflag-gemma-test/results + eval/real/results-gemma.jsonl) -> Python repair()."""
import glob, json, sys
sys.path.insert(0, "backend")
from redflag_backend.gemma_repair import repair
out = []
for p in sorted(glob.glob("redflag-gemma-test/results/*.jsonl")) + ["eval/real/results-gemma.jsonl"]:
    for line in open(p, encoding="utf-8"):
        r = json.loads(line)
        if isinstance(r.get("output"), dict) and r.get("schema_errors"):
            f, rep = repair(r["output"])
            out.append({"src": p, "id": r["id"], "output": r["output"], "features": f, "repairs": rep})
with open("android/core/src/test/resources/golden/repair.jsonl", "w", encoding="utf-8") as g:
    for o in out:
        g.write(json.dumps(o, ensure_ascii=False) + "\n")
print(len(out), "repair cases")
