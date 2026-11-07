# v4 follow-up turns on eval/real (Mac, LiteRT-LM GPU, Gemma 4 E4B, greedy)

Turn 1 answers: `eval/real/results-gemma.jsonl` (same prompt/model as the phone). Follow-up prompts, parsing and
decisions: `backend/redflag_backend/lite_followups.py` (byte-identical twin of the phone code, `contracts/followup_vectors.json`).
Script: `eval/followups/run_followups.py`, raw: `results.jsonl`.

## Turn 2 — source_app = unknown

| | benign (60) | phishing (60) |
|---|---|---|
| ① code: exclude | 0/60 (0%) | 2/60 (3%) |
| ① code: needed | 2/60 (3%) | 34/60 (57%) |
| ① code: fine → Gemma asked | 58/60 (97%) | 24/60 (40%) |
| context card before the 'other'-only filter | 4/60 (7%) | 42/60 (70%) |
| **context card shown** | 4/60 (7%) | 42/60 (70%) |
| … by Gemma request | 4/60 (7%) | 42/60 (70%) |
| … by code default (Gemma said none/failed) | 0/60 (0%) | 0/60 (0%) |
| … consent card (auto context) | 0/60 (0%) | 0/60 (0%) |
| … question card | 4/60 (7%) | 42/60 (70%) |
| fine → Gemma: no_context_needed | 56/60 (93%) | 16/60 (27%) |

- Gemma calls: 118; recognized tool call 118/118 (100%); native tool-call (no text) 118/118 (100%)
- latency (Mac GPU): mean 1.25 s, median 1.23 s, max 1.57 s
- questions: {'U1': 45, 'U3': 1}
- reasons: {'brand_unverifiable': 46, 'account_threat': 17, 'other': 6, 'gov_or_refund': 1, 'unexpected_payment': 1, 'app_install': 1}

- split A: card shown benign 2/30 (7%), phishing 20/30 (67%)
- split B: card shown benign 2/30 (7%), phishing 22/30 (73%)

Benign pages with a card (unknown):

- `ben_kunjpublication_com` score 25 (fine) →  U1 ['brand_unverifiable'] [gemma]
- `ben_rumahawan4d_com` score 30 (needed) →  U1 ['brand_unverifiable', 'other'] [gemma]
- `ben_mars4dsarana_com` score 25 (fine) →  U1 ['brand_unverifiable'] [gemma]
- `ben_66vip33_com` score 30 (needed) →  U1 ['brand_unverifiable', 'other'] [gemma]

## Turn 2 — source_app = sms

| | benign (60) | phishing (60) |
|---|---|---|
| ① code: exclude | 0/60 (0%) | 2/60 (3%) |
| ① code: needed | 3/60 (5%) | 35/60 (58%) |
| ① code: fine → Gemma asked | 57/60 (95%) | 23/60 (38%) |
| context card before the 'other'-only filter | 20/60 (33%) | 54/60 (90%) |
| **context card shown** | 11/60 (18%) | 51/60 (85%) |
| … by Gemma request | 11/60 (18%) | 51/60 (85%) |
| … by code default (Gemma said none/failed) | 0/60 (0%) | 0/60 (0%) |
| … consent card (auto context) | 11/60 (18%) | 51/60 (85%) |
| … question card | 0/60 (0%) | 0/60 (0%) |
| fine → Gemma: no_context_needed | 40/60 (67%) | 4/60 (7%) |

- Gemma calls: 118; recognized tool call 118/118 (100%); native tool-call (no text) 118/118 (100%)
- latency (Mac GPU): mean 1.16 s, median 1.09 s, max 1.53 s
- questions: {}
- reasons: {'brand_unverifiable': 53, 'account_threat': 41, 'other': 12, 'gov_or_refund': 1, 'delivery': 1, 'app_install': 1, 'unexpected_payment': 1}

- split A: card shown benign 7/30 (23%), phishing 25/30 (83%)
- split B: card shown benign 4/30 (13%), phishing 26/30 (87%)

Benign pages with a card (sms):

- `ben_bcs_hosting_net` score 0 (fine) → consent ['account_threat', 'other'] [gemma]
- `ben_4p_misdns_net` score 25 (fine) → consent ['brand_unverifiable'] [gemma]
- `ben_ezrirx_com` score 0 (needed) → consent ['unexpected_payment', 'other'] [gemma]
- `ben_t1a_jp` score 25 (fine) → consent ['brand_unverifiable'] [gemma]
- `ben_m999w_com` score 0 (fine) → consent ['account_threat', 'other'] [gemma]
- `ben_stocks_vip_com` score 25 (fine) → consent ['brand_unverifiable'] [gemma]
- `ben_kunjpublication_com` score 25 (fine) → consent ['brand_unverifiable', 'other'] [gemma]
- `ben_rumahawan4d_com` score 30 (needed) → consent ['other'] [gemma]
- `ben_vitaport_co_kr` score 25 (fine) → consent ['brand_unverifiable', 'other'] [gemma]
- `ben_mars4dsarana_com` score 25 (fine) → consent ['account_threat', 'brand_unverifiable'] [gemma]
- `ben_66vip33_com` score 30 (needed) → consent ['other'] [gemma]

## Turn D — destination relation

Cases with a cross-site personal-data destination (form actions only offline): 1/120
- `mal_169` (malicious) ['xxxxxxxx.test'] → {'relation': 'unrelated', 'domain': 'xxxxxxxx.test'} · 0.68 s · score 90→100 (critical→critical)

Limit: script/XHR endpoints were not collected for these 120 pages (they are mostly offline now), so Turn D
and the messenger-bot / form-service code rules are only exercised by the demo pages and the vectors.

