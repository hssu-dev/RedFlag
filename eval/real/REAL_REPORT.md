# RedFlag — real-page evaluation (M6) + scoring v2 비교

## scoring v2 전후 비교 (2026-11-04 사용자 결정 D1·D2·D3)

같은 Gemma 결과(`results-gemma.jsonl`, 맥 GPU, 120건)를 세 가지 규칙으로 다시 채점했다. 모델은 다시 돌리지 않았다. 세 설정 모두 FeatureRepair 적용(앱과 동일).

- **v1**: 이전 규칙 (`brand_mismatch_known_brand` 40, `secret_code_requested` 15, 캡 없음)
- **v2 가중치만**: D1 — 신규 `brand_mismatch_sensitive_request` +20, `secret_code_requested` 25
- **v2 전체**: D1 + D2(보정된 출력은 최대 caution, safe 불가) + D3(모델 답이 없으면 분석 미완료 → safe 불가). **현재 앱 동작**

| 지표 (A / B / 전체) | v1 (이전 규칙, 보정만) | v2 가중치만 (D1) | v2 전체 (D1+D2+D3) = 현재 앱 |
|---|---|---|---|
| malicious detected, rules ≥ caution | 18/30 (60%) / 17/30 (57%) / 35/60 (58%) | 18/30 (60%) / 17/30 (57%) / 35/60 (58%) | 21/30 (70%) / 17/30 (57%) / 38/60 (63%) |
| malicious detected, rules ≥ danger | 2/30 (7%) / 0/30 (0%) / 2/60 (3%) | 11/30 (37%) / 5/30 (17%) / 16/60 (27%) | 10/30 (33%) / 4/30 (13%) / 14/60 (23%) |
| benign false positive, rules ≥ caution | 0/30 (0%) / 0/30 (0%) / 0/60 (0%) | 0/30 (0%) / 0/30 (0%) / 0/60 (0%) | 10/30 (33%) / 17/30 (57%) / 27/60 (45%) |
| benign false positive, rules ≥ danger | 0/30 (0%) / 0/30 (0%) / 0/60 (0%) | 0/30 (0%) / 0/30 (0%) / 0/60 (0%) | 0/30 (0%) / 0/30 (0%) / 0/60 (0%) |

### v2 전체에서 정상 페이지가 caution 이상이 된 이유

| 라벨 | 모델 답 상태 | safe | caution | danger | critical |
|---|---|---|---|---|---|
| malicious | valid | 22 | 19 | 12 | 2 |
| malicious | repaired | 0 | 5 | 0 | 0 |
| malicious | incomplete | 0 | 0 | 0 | 0 |
| benign | valid | 33 | 0 | 0 | 0 |
| benign | repaired | 0 | 19 | 0 | 0 |
| benign | incomplete | 0 | 8 | 0 | 0 |

- **D1(가중치)**: 정상 오탐 0% 유지, 악성 ≥danger 3% → 27% (A 37%, B 17%). 이번 가중치는 사용자가 정한 값이라 A/B 분할 튜닝은 하지 않았다(양쪽 절반 모두 보고).
- **D2·D3(fail-safe)**: 정상 오탐(≥caution)이 0% → **45% (27/60)**. 원인은 전부 모델 답 상태다. Gemma가 실제 페이지에서 불리언 키를 자주 빠뜨려 보정(repaired)된 19건, JSON 파싱 실패(incomplete) 8건이 모두 caution으로 올라갔다. ≥danger 오탐은 여전히 0%.
- D2 캡 때문에 보정된 악성 페이지는 danger 이상이 될 수 없어, 악성 ≥danger는 27% → 23%로 내려갔다. ≥caution은 58% → 63%로 올랐다.
- Auto 모드에서는 caution(30–59)이 Cloud 에스컬레이션 구간이라, v2 전체에서는 정상 페이지의 약 45%가 Opus 호출을 일으킨다(비용·지연).
- 실제 페이지 데이터는 RDAP *실패*와 *정보 없음*을 구분할 수 없어(수집 시 null로만 저장) RDAP 실패 → 미완료(D3)는 이 평가에 반영되지 않았다. 앱에서는 반영된다.

---

## 상세 (v2 전체 = 현재 앱 동작)

_Generated 2026-11-04T00:08:52+00:00 by `eval/real/score_real.py`. Numbers come only from files in `eval/real/`; nothing is estimated._

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
| features used by rules (valid) | 57/60 (95%) | 55/60 (92%) | 112/120 (93%) |
| **malicious detected, rules ≥ caution** | 21/30 (70%) | 17/30 (57%) | 38/60 (63%) |
| malicious detected, rules ≥ danger | 10/30 (33%) | 4/30 (13%) | 14/60 (23%) |
| **benign false positive, rules ≥ caution** | 10/30 (33%) | 17/30 (57%) | 27/60 (45%) |
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
| malicious | 4 | 7 | 11 | 2 |
| suspicious | 12 | 15 | 1 | 0 |
| safe | 6 | 2 | 0 | 0 |
| bad verdict | 0 | 0 | 0 | 0 |
| parse fail / error | 0 | 0 | 0 | 0 |

**label = benign** (rows: Gemma verdict, columns: rule level)

| Gemma \ rules | safe | caution | danger | critical |
|---|---|---|---|---|
| malicious | 0 | 0 | 0 | 0 |
| suspicious | 6 | 4 | 0 | 0 |
| safe | 27 | 15 | 0 | 0 |
| bad verdict | 0 | 0 | 0 | 0 |
| parse fail / error | 0 | 8 | 0 | 0 |

### Rule firing counts (Gemma features + signals, all splits)
| rule | weight | fired on malicious | fired on benign |
|---|---|---|---|
| safe_browsing_hit | 95 (floor) | 0/60 (0%) | 0/60 (0%) |
| brand_mismatch_known_brand | 40 | 28/60 (47%) | 0/60 (0%) |
| brand_mismatch_sensitive_request | 20 | 14/60 (23%) | 0/60 (0%) |
| brand_mismatch_model_only | 25 | 20/60 (33%) | 6/60 (10%) |
| new_domain | 25 | 2/60 (3%) | 0/60 (0%) |
| sensitive_form_cross_site | 20 | 1/60 (2%) | 0/60 (0%) |
| punycode_or_homoglyph | 20 | 0/60 (0%) | 0/60 (0%) |
| addresses_ai_analyzer | 30 | 0/60 (0%) | 0/60 (0%) |
| secret_code_requested | 25 | 3/60 (5%) | 0/60 (0%) |
| too_good_to_be_true | 15 | 0/60 (0%) | 1/60 (2%) |
| urgency_or_threat | 10 | 12/60 (20%) | 0/60 (0%) |
| call_phone_with_urgency | 15 | 0/60 (0%) | 0/60 (0%) |
| cloaking_suspected | 35 | 0/60 (0%) | 0/60 (0%) |
| analysis_incomplete | None | 0/60 (0%) | 8/60 (13%) |
| model_output_repaired | None | 5/60 (8%) | 19/60 (32%) |

### Rule firing counts (signals only, all splits)
| rule | weight | fired on malicious | fired on benign |
|---|---|---|---|
| safe_browsing_hit | 95 (floor) | 0/60 (0%) | 0/60 (0%) |
| brand_mismatch_known_brand | 40 | 0/60 (0%) | 0/60 (0%) |
| brand_mismatch_sensitive_request | 20 | 0/60 (0%) | 0/60 (0%) |
| brand_mismatch_model_only | 25 | 0/60 (0%) | 0/60 (0%) |
| new_domain | 25 | 2/60 (3%) | 0/60 (0%) |
| sensitive_form_cross_site | 20 | 1/60 (2%) | 0/60 (0%) |
| punycode_or_homoglyph | 20 | 0/60 (0%) | 0/60 (0%) |
| addresses_ai_analyzer | 30 | 0/60 (0%) | 0/60 (0%) |
| secret_code_requested | 25 | 0/60 (0%) | 0/60 (0%) |
| too_good_to_be_true | 15 | 0/60 (0%) | 0/60 (0%) |
| urgency_or_threat | 10 | 0/60 (0%) | 0/60 (0%) |
| call_phone_with_urgency | 15 | 0/60 (0%) | 0/60 (0%) |
| cloaking_suspected | 35 | 0/60 (0%) | 0/60 (0%) |
| analysis_incomplete | None | 0/60 (0%) | 0/60 (0%) |
| model_output_repaired | None | 0/60 (0%) | 0/60 (0%) |

## Weight tuning (tune on A, report on B)

Not run (use `--tune`).

## Caveats

- 60 + 60 pages from one day's feeds; OpenPhish entries are confirmed phish but kits cloak (a page kept here
  is what the sandbox saw, which may be a decoy). Benign = Tranco 200k–1M, i.e. mostly small but established sites.
- `domain_age_days` from RDAP and `tls` from crt.sh were looked up at collection time; Safe Browsing is not used.
- Splits are ~30 + 30 cases: a one-case change moves a rate by ~3 points.
