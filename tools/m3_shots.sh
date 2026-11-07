#!/bin/bash
# M3 evidence: serve demo-pages with nginx and screenshot each page in the Playwright image
# (webdriver=true like the Cloud sandbox, and webdriver=false like the phone WebView for parcel/m365).
set -euo pipefail
cd "$(dirname "$0")/.."
docker network create rf-m3 >/dev/null 2>&1 || true
docker run -d --rm --name rf-m3-demo --network rf-m3 -v "$PWD/demo-pages:/usr/share/nginx/html:ro" nginx:stable-alpine >/dev/null
trap 'docker rm -f rf-m3-demo >/dev/null; docker network rm rf-m3 >/dev/null' EXIT
mkdir -p shots/m3
docker run --rm --network rf-m3 -v "$PWD/tools/m3_shots.py:/m3.py:ro" -v "$PWD/contracts:/contracts:ro" -v "$PWD/shots/m3:/out" \
  mcr.microsoft.com/playwright/python:v1.64.0-noble bash -c "pip install -q playwright==1.64.0 && python /m3.py http://rf-m3-demo"
