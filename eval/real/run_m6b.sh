#!/usr/bin/env bash
# M6b: Mac Gemma over the real-page cases with the M0 runtime/settings, then score + report.
#
#   eval/real/run_m6b.sh            # refuses while an Android emulator is running
#   FORCE=1 eval/real/run_m6b.sh    # skip the emulator check (do not: master prompt §4)
#   SCORE_ONLY=1 eval/real/run_m6b.sh   # re-score an existing results file, no model load
#
# Runtime = last night's M0 choice: LiteRT-LM Python (litert_lm_api 0.19.0 in repo-root .venv),
# GPU, Gemma 4 E4B, free-form JSON, thinking off, system_prompt_v2.txt, temperature 0, 800 max tokens.
# run_eval.py is a frozen baseline and calls check(obj, case["expected"]); the real cases have no
# `expected`, so a derived copy with "expected": {} is written to eval/real/raw/ (gitignored) and
# passed as --cases. Gemma input is unaffected (build_user_message uses only signals + page).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
PY="$ROOT/.venv/bin/python"
CASES="${CASES:-eval/real/cases_real.jsonl}"
OUT="${OUT:-eval/real/results-gemma.jsonl}"
DERIVED="eval/real/raw/cases_real_for_run_eval.jsonl"
MODEL="${MODEL:-models/gemma-4-E4B-it.litertlm}"
CACHE="${CACHE:-$HOME/models/litert/cache}"

[ -s "$CASES" ] || { echo "missing $CASES (run eval/real/collect.py first)"; exit 1; }

if [ -z "${SCORE_ONLY:-}" ]; then
  if [ -z "${FORCE:-}" ]; then
    if pgrep -f "qemu-system" >/dev/null 2>&1 || \
       (command -v adb >/dev/null 2>&1 && adb devices 2>/dev/null | grep -q '^emulator-'); then
      echo "An Android emulator is running. Stop it first (master prompt §4: never emulator + Mac Gemma together)."
      exit 2
    fi
  fi
  [ -f "$MODEL" ] || { echo "model not found: $MODEL"; exit 1; }
  mkdir -p eval/real/raw
  "$PY" - "$CASES" "$DERIVED" <<'EOF'
import json, sys
src, dst = sys.argv[1], sys.argv[2]
n = 0
with open(src, encoding="utf-8") as f, open(dst, "w", encoding="utf-8") as g:
    for line in f:
        if line.strip():
            c = json.loads(line)
            c.setdefault("expected", {})
            g.write(json.dumps(c, ensure_ascii=False) + "\n")
            n += 1
print(f"{n} cases -> {dst} (expected={{}} added for run_eval.check)")
EOF
  "$PY" redflag-gemma-test/run_eval.py --backend litert --device gpu \
    --model "$MODEL" --cache-dir "$CACHE" \
    --prompt redflag-gemma-test/system_prompt_v2.txt \
    --cases "$DERIVED" --out "$OUT" 2>&1 | tee eval/real/raw/run_m6b.log
fi

"$PY" eval/real/score_real.py --cases "$CASES" --results "$OUT" --tune
echo "report -> eval/real/REAL_REPORT.md   proposal -> eval/real/tuning_proposal.json"
