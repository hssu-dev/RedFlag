# contracts/

Single source of truth shared by `android/core` (Kotlin) and `backend/` (Python). Both
implementations read these files at runtime/build time — never copy values into code.

| file | what |
|---|---|
| `risk_report.schema.json` | the one RiskReport shape (Lite + Cloud), JSON Schema 2020-12 |
| `lite_features.schema.json` | Gemma's 10 fixed fields (mirrors `redflag-gemma-test/run_eval.py`) |
| `cloud_features.schema.json` | Lite fields + `form_posts_cross_site`, `downloads_attempted`, `official_domain_found`; flat so it doubles as the Opus `submit_report` input_schema |
| `scoring_rules.json` | declarative rule engine (facts → boolean expressions → weights/floors → level/headline) |
| `scoring_vectors.json` | 52 ScoringInput → expected score/level/fired/headline vectors (hand-computed) |
| `url_vectors.json` | 64 gate vectors (normalization, PSL private, IDN/homoglyph, shorteners, UGC, SB stub, IP) |
| `brand_domains.json` | 38 brands, official registrable domains (only ones we are sure of) |
| `gate_lists.json` | shorteners, path/host UGC, Safe Browsing stub, confusables map |
| `public_suffix_list.dat` | publicsuffix.org PSL, VERSION 2026-10-31_14-17-28_UTC, sha256 617d1b27…5d01 (private section included) |
| `design_tokens.json` | Compose theme tokens (colors light/dark, level colors, type, motion) |
| `demo_hosts.json` | DEBUG/DEMO_MODE only: fake demo domains → local demo-pages URLs + signal overrides |
| `fixtures/*.json` | one valid RiskReport per level |

Validation: `cd backend && uv run --group dev pytest tests/test_contracts.py tests/test_scoring.py`

## Observation → Gemma input (both implementations)

`extract_page.js` output + deterministic signals are turned into exactly the
`redflag-gemma-test/cases.jsonl` shape, then `run_eval.build_user_message()` formatting
(`json.dumps(..., ensure_ascii=False, indent=1)` for `signals` and `page`):

```
signals = { url, final_url, registrable_domain, redirect_count, domain_age_days,
            tls: {issuer, cert_age_days}, safe_browsing,
            forms: [{action_domain (eTLD+1 of form action host), same_site, fields: [{type, label}]}],
            external_link_domains: [distinct eTLD+1 of link_urls != registrable_domain, max 10] }
page    = { lang, title, visible_text (first 2500 chars), hidden_text (first 800 chars) }
```
Unknown values are `null` (system_prompt_v2: "A null value means unknown").
