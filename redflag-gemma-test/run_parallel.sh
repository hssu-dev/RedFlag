#!/bin/bash
# Launch LiteRT runs in parallel. Parallel CPU runs are for ACCURACY only (latency is contended).
# usage: run_parallel.sh "<device> <prompt> <cases> <tag> <mode>" ...   mode: free|schema|think|x3
cd "$(dirname "$0")"
MODEL=${MODEL:-$HOME/models/litert/gemma-4-E4B-it.litertlm}
for spec in "$@"; do
  set -- $spec; DEV=$1; PROMPT=$2; CASES=$3; TAG=$4; MODE=$5
  case $MODE in
    free)   EXTRA=(); SUF=free-t0.0 ;;
    schema) EXTRA=(--schema); SUF=schema-t0.0 ;;
    think)  EXTRA=(--think); SUF=think-t0.0 ;;
    x3)     EXTRA=(--runs 3 --temp 0.7); SUF=free-t0.7x3 ;;
  esac
  OUT=results/$TAG-$DEV-$SUF
  .venv/bin/python run_eval.py --backend litert --device "$DEV" --model "$MODEL" --cache-dir "$HOME/models/litert/cache" \
    --prompt "$PROMPT" --cases "$CASES" "${EXTRA[@]}" --out "$OUT.jsonl" > "$OUT.log" 2>&1 &
done
wait
echo "parallel batch done"
