# Third-party data and models

RedFlag's own code is licensed under the Apache License 2.0 (`LICENSE`). The repository also
bundles or depends on the following third-party material, which keeps its own terms.

## Bundled in this repository

| What | Where | Source | License / terms |
|---|---|---|---|
| Public Suffix List (`VERSION: 2026-10-31_14-17-28_UTC`) | `contracts/public_suffix_list.dat` | https://publicsuffix.org | Mozilla Public License 2.0 (header kept in the file) |
| Tranco top-100k, **as 64-bit SHA-256 prefixes only** (no domain names are stored) | `android/core/src/main/resources/known_good/tranco_top100k.bin`, `tranco_meta.json` (list id + date) | https://tranco-list.eu, built by `tools/build_tranco.sh` | Free for research and commercial use. Please cite: V. Le Pochat et al., "Tranco: A Research-Oriented Top Sites Ranking Hardened Against Manipulation", NDSS 2019 |

Gradle and Python dependencies are pulled at build time under their own licenses
(`android/gradle/libs.versions.toml`, `backend/pyproject.toml`, `backend/uv.lock`).

## Not bundled (you download them yourself)

| What | Used by | Source | Terms |
|---|---|---|---|
| Gemma 4 E4B, LiteRT-LM build (`gemma-4-E4B-it.litertlm`, 3.66 GB) | on-device Lite analysis | Hugging Face `litert-community/gemma-4-E4B-it-litert-lm` | Gemma Terms of Use (https://ai.google.dev/gemma/terms). Not redistributed here (`models/` is gitignored) |
| OpenPhish public feed | `eval/real/collect.py` (evaluation only) | https://openphish.com/feed.txt | OpenPhish terms. Collected URLs are **not** committed |
| Google Safe Browsing v5 | gate (optional, needs your own API key) | https://developers.google.com/safe-browsing | Google APIs Terms of Service |
| Anthropic API (Claude Opus 5.5) | Cloud analysis (optional, needs your own API key) | https://www.anthropic.com | Anthropic commercial terms |

## Trademarks

Brand names in detection data (`contracts/brand_domains.json`, test vectors, evaluation results)
are used only to recognise impersonation of those brands. RedFlag is not affiliated with them.
