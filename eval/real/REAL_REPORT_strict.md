# RedFlag — real-page evaluation (M6)

_Generated 2026-11-03T23:42:16+00:00 by `eval/real/score_real.py`. Numbers come only from files in `eval/real/`; nothing is estimated._

## Data (M6a)

- Collected 2026-11-03T18:59:38+00:00 → 2026-11-03T19:23:20+00:00 (UTC) by `eval/real/collect.py`, seed 20261104, sandbox: backend docker compose service 'sandbox' via docker compose exec (no published port).
- Malicious source: OpenPhish community feed (https://openphish.com/feed.txt), downloaded 2026-11-03T18:59:41+00:00, Last-Modified None; 300 URLs, 215 distinct registrable domains.
- Benign source: Tranco list GQ38K (created 2026-11-02T22:00:01.847762), ranks [200000, 1000000], uniform random with the same seed.

| label | attempted | kept | excluded | excluded by reason |
|---|---|---|---|---|
| malicious | 174 | 60 | 114 | blocked_private_ip 2, bot_challenge 1, duplicate_final_domain 1, empty_page 27, extraction_failed 1, http_error 77, redirected_to_known_good 2, suspended_or_takedown 2, timeout 1 |
| benign | 82 | 60 | 22 | empty_page 11, http_error 11 |

Split (stratified, sha256(id)): A = 30 mal + 30 ben, B = 30 mal + 30 ben.

Gemma input = the exact `redflag-gemma-test/cases.jsonl` shape (`signals` + `page`); `safe_browsing` is
`"unknown"` for every real case (no key). Exclusions: sandbox/navigation errors, HTTP ≥ 400, < 40 visible
characters, parking / suspended / takedown / not-found / bot-challenge pages, malicious URLs that redirect to a
Tranco top-100k site, duplicate final domains (see `collect_log.json` for every excluded URL and reason).

## Run (M6b)

Runtime = the M0 choice from last night's REPORT: LiteRT-LM Python 0.19.0, GPU, Gemma 4 E4B
(`models/gemma-4-E4B-it.litertlm`), free-form JSON, thinking off, `system_prompt_v2.txt`, temperature 0.
Command: `eval/real/run_m6b.sh`. Results: `eval/real/results-gemma.jsonl` (120 records).

Scoring: `backend/redflag_backend/scoring.py` over `contracts/scoring_rules.json` (shipped weights). Gemma
features are used only when the output parses AND is schema-valid; otherwise the case is scored on signals only
(what the app does). Levels: safe < caution (≥30) < danger (≥60) < critical (≥85).

## Results

| metric | split A | split B | all |
|---|---|---|---|
| cases | 30 mal / 30 ben | 30 mal / 30 ben | 60 mal / 60 ben |
| Gemma JSON parse rate | 57/60 (95%) | 55/60 (92%) | 112/120 (93%) |
| Gemma schema-valid rate | 46/60 (77%) | 42/60 (70%) | 88/120 (73%) |
| features used by rules (valid) | 46/60 (77%) | 42/60 (70%) | 88/120 (73%) |
| **malicious detected, rules ≥ caution** | 17/30 (57%) | 16/30 (53%) | 33/60 (55%) |
| malicious detected, rules ≥ danger | 2/30 (7%) | 0/30 (0%) | 2/60 (3%) |
| **benign false positive, rules ≥ caution** | 0/30 (0%) | 0/30 (0%) | 0/60 (0%) |
| benign false positive, rules ≥ danger | 0/30 (0%) | 0/30 (0%) | 0/60 (0%) |
| Gemma verdict: malicious caught (malicious or suspicious) | 25/30 (83%) | 27/30 (90%) | 52/60 (87%) |
| Gemma verdict: malicious strict | 16/30 (53%) | 8/30 (27%) | 24/60 (40%) |
| Gemma verdict: benign → malicious | 0/27 (0%) | 0/25 (0%) | 0/52 (0%) |
| Gemma verdict: benign → suspicious | 6/27 (22%) | 4/25 (16%) | 10/52 (19%) |
| latency s (mean / median / p95) | 3.14 / 3.12 / 3.90 | 3.11 / 3.07 / 3.96 | 3.13 / 3.10 / 3.96 |
| signals only: malicious ≥ caution | 0/30 (0%) | 0/30 (0%) | 0/60 (0%) |
| signals only: malicious ≥ danger | 0/30 (0%) | 0/30 (0%) | 0/60 (0%) |
| signals only: benign ≥ caution | 0/30 (0%) | 0/30 (0%) | 0/60 (0%) |
| signals only: benign ≥ danger | 0/30 (0%) | 0/30 (0%) | 0/60 (0%) |

### Gemma's own verdict vs rule level

**label = malicious** (rows: Gemma verdict, columns: rule level)

| Gemma \ rules | safe | caution | danger | critical |
|---|---|---|---|---|
| malicious | 7 | 15 | 2 | 0 |
| suspicious | 12 | 16 | 0 | 0 |
| safe | 8 | 0 | 0 | 0 |
| bad verdict | 0 | 0 | 0 | 0 |
| parse fail / error | 0 | 0 | 0 | 0 |

**label = benign** (rows: Gemma verdict, columns: rule level)

| Gemma \ rules | safe | caution | danger | critical |
|---|---|---|---|---|
| malicious | 0 | 0 | 0 | 0 |
| suspicious | 10 | 0 | 0 | 0 |
| safe | 42 | 0 | 0 | 0 |
| bad verdict | 0 | 0 | 0 | 0 |
| parse fail / error | 8 | 0 | 0 | 0 |

### Rule firing counts (Gemma features + signals, all splits)
| rule | weight | fired on malicious | fired on benign |
|---|---|---|---|
| safe_browsing_hit | 95 (floor) | 0/60 (0%) | 0/60 (0%) |
| brand_mismatch_known_brand | 40 | 26/60 (43%) | 0/60 (0%) |
| brand_mismatch_model_only | 25 | 19/60 (32%) | 3/60 (5%) |
| new_domain | 25 | 2/60 (3%) | 0/60 (0%) |
| sensitive_form_cross_site | 20 | 1/60 (2%) | 0/60 (0%) |
| punycode_or_homoglyph | 20 | 0/60 (0%) | 0/60 (0%) |
| addresses_ai_analyzer | 30 | 0/60 (0%) | 0/60 (0%) |
| secret_code_requested | 15 | 3/60 (5%) | 0/60 (0%) |
| too_good_to_be_true | 15 | 0/60 (0%) | 0/60 (0%) |
| urgency_or_threat | 10 | 11/60 (18%) | 0/60 (0%) |
| call_phone_with_urgency | 15 | 0/60 (0%) | 0/60 (0%) |
| cloaking_suspected | 35 | 0/60 (0%) | 0/60 (0%) |

### Rule firing counts (signals only, all splits)
| rule | weight | fired on malicious | fired on benign |
|---|---|---|---|
| safe_browsing_hit | 95 (floor) | 0/60 (0%) | 0/60 (0%) |
| brand_mismatch_known_brand | 40 | 0/60 (0%) | 0/60 (0%) |
| brand_mismatch_model_only | 25 | 0/60 (0%) | 0/60 (0%) |
| new_domain | 25 | 2/60 (3%) | 0/60 (0%) |
| sensitive_form_cross_site | 20 | 1/60 (2%) | 0/60 (0%) |
| punycode_or_homoglyph | 20 | 0/60 (0%) | 0/60 (0%) |
| addresses_ai_analyzer | 30 | 0/60 (0%) | 0/60 (0%) |
| secret_code_requested | 15 | 0/60 (0%) | 0/60 (0%) |
| too_good_to_be_true | 15 | 0/60 (0%) | 0/60 (0%) |
| urgency_or_threat | 10 | 0/60 (0%) | 0/60 (0%) |
| call_phone_with_urgency | 15 | 0/60 (0%) | 0/60 (0%) |
| cloaking_suspected | 35 | 0/60 (0%) | 0/60 (0%) |

## Weight tuning (tune on A, report on B)

Proposal (PROPOSAL ONLY - contracts/scoring_rules.json was not modified; `eval/real/tuning_proposal.json`): `brand_mismatch_known_brand` 40→60, `brand_mismatch_model_only` 25→50.
Objective: det>=caution + det>=danger - 2*fp>=caution - 4*fp>=danger - 0.002*sum|dw| (rates on split A); coordinate descent over add-rule weights, steps ±5/10/15, bounds [0,60]; rules not firing on A unchanged.

| | split A (tuned on) | split B (held out) |
|---|---|---|
| before | det≥caution 57%, det≥danger 7%, FP≥caution 0%, FP≥danger 0% | det≥caution 53%, det≥danger 0%, FP≥caution 0%, FP≥danger 0% |
| after | det≥caution 70%, det≥danger 57%, FP≥caution 7%, FP≥danger 0% | det≥caution 80%, det≥danger 53%, FP≥caution 3%, FP≥danger 0% |

_Small sample (~30+30 per split); treat as a direction, not a final weight set. Applying it needs contracts/scoring_rules.json + scoring_vectors.json updates and both engines re-tested._

## Caveats

- 60 + 60 pages from one day's feeds; OpenPhish entries are confirmed phish but kits cloak (a page kept here
  is what the sandbox saw, which may be a decoy). Benign = Tranco 200k–1M, i.e. mostly small but established sites.
- `domain_age_days` from RDAP and `tls` from crt.sh were looked up at collection time; Safe Browsing is not used.
- Splits are ~30 + 30 cases: a one-case change moves a rate by ~3 points.
