#!/usr/bin/env bash
# Builds android/core/src/main/resources/known_good/tranco_top100k.bin (+ tranco_meta.json).
#
# Source: Tranco (https://tranco-list.eu), the research top-sites list. Uses the documented
# "latest list" endpoints:
#   https://tranco-list.eu/top-1m-id                 -> current list id
#   https://tranco-list.eu/api/lists/id/<ID>         -> list metadata (created_on = list date)
#   https://tranco-list.eu/download/<ID>/1000000     -> CSV "rank,domain" (permanent, citable list)
# Then ./gradlew :core:buildTranco maps the top 100,000 rows through the bundled PSL
# (contracts/public_suffix_list.dat, private section included), drops public suffixes, dedupes,
# and writes sorted 64-bit SHA-256 prefixes.
#
# Usage: tools/build_tranco.sh [LIST_ID]      (default: latest)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/redflag-tranco"
mkdir -p "$WORK"

ID="${1:-$(curl -fsSL --max-time 30 https://tranco-list.eu/top-1m-id)}"
META="$(curl -fsSL --max-time 30 "https://tranco-list.eu/api/lists/id/$ID")"
DATE="$(printf '%s' "$META" | python3 -c 'import json,sys; print(json.load(sys.stdin)["created_on"][:10])')"
CSV="$WORK/tranco_$ID.csv"
if [ ! -s "$CSV" ]; then
  echo "downloading Tranco list $ID ($DATE)..."
  curl -fsSL --max-time 300 "https://tranco-list.eu/download/$ID/1000000" -o "$CSV.part"
  mv "$CSV.part" "$CSV"
fi
echo "rows in CSV: $(wc -l < "$CSV" | tr -d ' ')"

cd "$ROOT/android"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
./gradlew -q :core:buildTranco -PtrancoCsv="$CSV" -PtrancoId="$ID" -PtrancoDate="$DATE"
cat "$ROOT/android/core/src/main/resources/known_good/tranco_meta.json"
