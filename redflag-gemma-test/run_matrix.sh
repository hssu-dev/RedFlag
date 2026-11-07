#!/bin/bash
# usage: run_matrix.sh <device> <prompt> <cases> <tag>   (LiteRT E4B; see REPORT.md)
set -u
cd "$(dirname "$0")"
DEV=$1; PROMPT=$2; CASES=$3; TAG=$4
MODEL=${MODEL:-$HOME/models/litert/gemma-4-E4B-it.litertlm}
R=".venv/bin/python run_eval.py --backend litert --device $DEV --model $MODEL --cache-dir $HOME/models/litert/cache --prompt $PROMPT --cases $CASES"
P=results/$TAG-$DEV
mkdir -p results
$R --out $P-free-t0.0.jsonl                > $P-free-t0.0.log 2>&1
$R --schema --out $P-schema-t0.0.jsonl     > $P-schema-t0.0.log 2>&1
$R --think --out $P-think-t0.0.jsonl       > $P-think-t0.0.log 2>&1
$R --runs 3 --temp 0.7 --out $P-free-t0.7x3.jsonl > $P-free-t0.7x3.log 2>&1
echo "matrix done: $TAG $DEV"
