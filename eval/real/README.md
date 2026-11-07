# eval/real — real-page evaluation (M6)

| file | committed | what |
|---|---|---|
| `collect.py` | yes | M6a: OpenPhish + Tranco → sandbox → `cases_real.jsonl` |
| `cases_real.jsonl` | **no** (gitignored: live phishing URLs) | real cases, `redflag-gemma-test/cases.jsonl` shape + side fields |
| `collect_log.json` | **no** (gitignored: live phishing URLs) | feed timestamps, Tranco list id, attempted/kept/excluded counts per reason, every excluded URL |
| `run_m6b.sh` | yes | M6b: Mac Gemma (M0 runtime) over the cases, then `score_real.py --tune` |
| `score_real.py` | yes | rule-engine scoring, per-split metrics, tuning proposal, `REAL_REPORT.md` |
| `results-gemma.jsonl` | yes (small) | run_eval.py output for the real cases (after M6b) |
| `REAL_REPORT.md`, `tuning_proposal.json` | yes | generated |
| `raw/` | **no** (gitignored) | feed text, Tranco CSV, per-page sandbox JSON (`obs/`), screenshots (`screens/`), logs |

Files marked **no** stay on the machine that ran `collect.py`. The public repo keeps the scripts and the
generated reports only, so it never hosts live phishing URLs. Rerunning `collect.py` produces a new set
(the OpenPhish feed changes hourly), so the reported numbers are reproducible in method, not byte-for-byte.

Malicious case IDs are anonymous (`mal_001`…), not `mal_<domain>`: `eval/anon_ids.py` keeps the mapping in
`raw/id_map.json` (local only), and `collect.py` / `eval/page_state/build_cases.py` allocate IDs through it. The
page-state builder also replaces each phishing page's own domain in its text with a same-length `x…x.test`.

## Safety

Real pages are opened **only** inside the backend `sandbox` container (Playwright, non-root, read-only FS,
no published port). `collect.py` reaches it with `docker compose exec -T sandbox python -c <POST client>`
(the container calls its own `127.0.0.1:8090/browse`). The host only downloads the feed text lists and does
RDAP / crt.sh lookups of domain names. No clicks, typing, form submission or downloads (the sandbox refuses
them). Requests are spaced ≥ 2 s apart (global), at most 2 concurrent by default (`--workers` ≤ 3).

## M6a — collect

```bash
cd backend && docker compose up -d --build sandbox && cd ..
backend/.venv/bin/python eval/real/collect.py              # 60 malicious + 60 benign (defaults)
```

* malicious: `https://openphish.com/feed.txt` → one URL per registrable domain (first in feed order),
  domains shuffled with `--seed` (20261104); benign: Tranco `GQ38K` (same list as the Android known-good
  set) ranks 200,000–1,000,000, shuffled with the same seed, `https://<domain>/`.
* candidates are tried in that order until 60 per label are **kept** (max `--max-attempts` 200 per label).
* excluded (counted per reason in `collect_log.json`): sandbox errors (`dead_dns`, `timeout`,
  `dead_connect_or_tls`, `blocked_private_ip`, `extraction_failed`), `http_error` (status ≥ 400; incl.
  Cloudflare "Suspected Phishing/Malware" interstitials, broken down in `http_error_breakdown`),
  `empty_page` (< 40 visible chars), `parked`, `suspended_or_takedown`, `not_found`,
  `server_default_page`, `bot_challenge`, `download_attempted`, `redirected_to_known_good` (malicious URL
  that now lands on a Tranco top-100k site), `duplicate_final_domain`.
* signals: RDAP age + crt.sh cert of the **final** registrable domain (backend `rdap`/`cert` tools; sandbox
  TLS as fallback, like the backend), `safe_browsing = "unknown"`, forms/links from the shared extractor.
* side fields after `page`: `forms_full` (fields with `name`/`autocomplete`, for the rule engine), `meta`
  (source, Tranco rank, HTTP status, punycode/homoglyph, redirect chain, fetch time), `split`.
* `split`: per label, cases sorted by sha256(id); first half → `A` (tuning), rest → `B` (reporting).

## M6b — Gemma + rules (after the emulator is stopped)

```bash
eval/real/run_m6b.sh                      # Gemma E4B LiteRT GPU, free-form, no thinking, prompt v2
# re-score only (no model):
SCORE_ONLY=1 eval/real/run_m6b.sh
```

`run_eval.py` is frozen and needs `case["expected"]`; `run_m6b.sh` passes a derived copy with
`"expected": {}` (`raw/cases_real_for_run_eval.jsonl`). Gemma input is identical.

Without results, `python3 eval/real/score_real.py` still writes `REAL_REPORT.md` with the Gemma cells marked
"not run yet" and the (real) signals-only rule baseline.
