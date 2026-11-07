#!/usr/bin/env python3
"""M6a: build eval/real/cases_real.jsonl from REAL pages (OpenPhish phish + Tranco tail benign).

SAFETY
* Pages are opened ONLY inside the backend sandbox container (Playwright, no published port).
  This script reaches it with `docker compose exec -T sandbox python -c <tiny POST client>`,
  i.e. from inside the container to its own 127.0.0.1:8090. The host never fetches a page.
* The host downloads only the feed TEXT lists (OpenPhish feed.txt, Tranco CSV) and does
  RDAP / crt.sh lookups of domain NAMES (they never touch the site itself).
* The sandbox never clicks/types/submits and refuses downloads. Requests are spaced
  (>= --delay s between request starts, globally) with --workers <= 3 concurrent.

PIPELINE
  lists -> candidates (seeded shuffle) -> sandbox browse -> exclusion filters -> RDAP + cert of
  the FINAL registrable domain -> the exact redflag-gemma-test/cases.jsonl shape -> split A/B.
  Candidates are tried in seeded order until --target cases per label are kept or
  --max-attempts candidates per label have been tried (every exclusion is counted by reason).

Run with the backend venv (it has httpx for the reused rdap/cert tools):
  cd backend && docker compose up -d --build sandbox && cd ..
  backend/.venv/bin/python eval/real/collect.py            # both labels, 60 each
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import csv
import hashlib
import json
import random
import re
import subprocess
import sys
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
RAW = HERE / "raw"
sys.path.insert(0, str(ROOT / "backend"))
sys.path.insert(0, str(ROOT / "eval"))

from anon_ids import anon_id  # noqa: E402  (mal_<domain> -> mal_NNN; map in raw/id_map.json, gitignored)

from redflag_backend import psl, report  # noqa: E402
from redflag_backend.tools import cert as cert_tool  # noqa: E402
from redflag_backend.tools import rdap as rdap_tool  # noqa: E402
from redflag_backend.tools.sandbox_browse import normalize as normalize_obs  # noqa: E402

OPENPHISH_URL = "https://openphish.com/feed.txt"
TRANCO_ID = "GQ38K"  # same list the Android known-good set was built from (tranco_meta.json)
TRANCO_CSV_URL = f"https://tranco-list.eu/download/{TRANCO_ID}/1000000"
TRANCO_META_URL = f"https://tranco-list.eu/api/lists/id/{TRANCO_ID}"
UA = "RedFlag-eval/0.1 (research; feed list download only)"

MAX_VISIBLE, MAX_HIDDEN, MAX_LINK_DOMAINS = 2500, 800, 10
MIN_VISIBLE = 40

# Client run INSIDE the sandbox container: POST its own /browse endpoint, print the JSON.
SANDBOX_CLIENT = (
    "import json,sys,urllib.request\n"
    "b=json.dumps({'url':sys.argv[1],'timeout_ms':int(sys.argv[2])}).encode()\n"
    "r=urllib.request.Request('http://127.0.0.1:8090/browse',data=b,headers={'Content-Type':'application/json'})\n"
    "sys.stdout.write(urllib.request.urlopen(r,timeout=80).read().decode())\n"
)

# ------------------------------------------------------------------ exclusion heuristics
# Applied to lowercase title / the start of short pages only, so phishing lures that SAY
# "your account has been suspended" on a full login page are not dropped.
TITLE_PATTERNS = {
    "not_found": [r"\b404\b", r"not found", r"\berror [45]\d\d\b", r"not acceptable", r"page not available", r"no such app", r"site not found",
                  r"\b403\b", r"forbidden", r"\b50[0-4]\b", r"bad gateway", r"service unavailable",
                  r"internal server error", r"error 10\d\d", r"web server is down"],
    "suspended_or_takedown": [r"account suspended", r"suspended", r"suspected phishing", r"phishing",
                              r"deceptive site", r"site blocked", r"deactivated", r"disabled",
                              r"taken down", r"removed"],
    "parked": [r"for sale", r"parked", r"domain parking", r"buy this domain", r"this domain",
               r"hugedomains", r"dan\.com", r"afternic", r"sedo", r"parkingcrew"],
    "server_default_page": [r"^index of /", r"welcome to nginx", r"apache2? .*default page", r"^it works",
                            r"default web page", r"iis windows server", r"^coming soon$",
                            r"future home of", r"^domain default page", r"^under construction"],
    "bot_challenge": [r"just a moment", r"attention required", r"verify you are human",
                      r"ddos-guard", r"security check", r"captcha"],
}
# Title hits that count whatever the page length; all other title patterns need a short page.
STRONG_TITLE = {r"\b404\b", r"not found", r"\berror [45]\d\d\b", r"no such app", r"site not found", r"account suspended",
                r"suspected phishing", r"deceptive site", r"buy this domain", r"domain parking",
                r"hugedomains", r"parkingcrew", r"^index of /", r"welcome to nginx", r"just a moment",
                r"attention required", r"ddos-guard"}
SHORT_TEXT_PATTERNS = {  # only when visible_text < 800 chars
    "suspended_or_takedown": [r"this account has been suspended", r"contact your hosting provider",
                              r"website (has been|is) suspended", r"site has been (suspended|disabled|blocked)",
                              r"suspected phishing", r"reported (as|for) phishing", r"removed for violating",
                              r"violat\w+ (our|the) terms", r"has been taken down", r"deployment (has been )?"
                              r"(disabled|paused)", r"this (site|page) has been (removed|deleted)",
                              r"is temporarily paused", r"has been banned"],
    "parked": [r"domain (is|may be) for sale", r"buy this domain", r"this domain (name )?is parked",
               r"parked (free|domain)", r"domain parking", r"this domain has been registered",
               r"inquire about this domain", r"make an offer"],
    "not_found": [r"\b404\b", r"page not found", r"site not found", r"not found on this server",
                  r"there isn'?t a github pages site here", r"no such app", r"domain not configured",
                  r"this site can'?t be reached", r"dns resolution error", r"web page not available",
                  r"the requested url was not found"],
    "server_default_page": [r"welcome to nginx", r"apache2 .*default page", r"default web ?page",
                            r"future home of something", r"index of /"],
    "bot_challenge": [r"verify you are human", r"checking your browser", r"enable javascript and cookies to continue",
                      r"complete the security check", r"press (and|&) hold", r"i'?m not a robot"],
}


def classify_page(obs: dict) -> tuple[str | None, str | None]:
    """(reason, matched pattern) for pages that are not a real live page, else (None, None)."""
    title = (obs.get("title") or "").strip().lower()
    text = re.sub(r"\s+", " ", obs.get("visible_text") or "").strip()
    low = text.lower()
    for reason in ("bot_challenge", "suspended_or_takedown", "parked", "not_found", "server_default_page"):
        for p in TITLE_PATTERNS[reason]:
            if title and re.search(p, title):
                # 'this domain'/'suspended'/'removed' in a title of a long page with a form is
                # more likely a lure than a takedown: only trust weak title hits on short pages.
                if p not in STRONG_TITLE and len(text) >= 800:
                    continue
                return reason, f"title:{p}"
    if len(text) < 800:
        for reason, pats in SHORT_TEXT_PATTERNS.items():
            for p in pats:
                if re.search(p, low):
                    return reason, f"text:{p}"
    return None, None


def classify_error(err: str) -> str:
    e = (err or "").lower()
    if "name_not_resolved" in e or "name not resolved" in e:
        return "dead_dns"
    if "timeout" in e or "timed out" in e:
        return "timeout"
    if "connection" in e or "err_address" in e or "err_empty_response" in e or "ssl" in e or "cert" in e:
        return "dead_connect_or_tls"
    if "blocked: private" in e:
        return "blocked_private_ip"
    if "busy" in e:
        return "sandbox_busy"
    return "extraction_failed"


# ------------------------------------------------------------------ lists

def http_get(url: str, timeout: float = 120) -> tuple[bytes, dict]:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(), dict(r.headers)


def load_openphish(log: dict, refresh: bool) -> list[str]:
    path = RAW / "openphish_feed.txt"
    meta_path = RAW / "openphish_feed.meta.json"
    if refresh or not path.exists():
        try:
            body, headers = http_get(OPENPHISH_URL, 60)
            path.write_bytes(body)
            meta_path.write_text(json.dumps({
                "url": OPENPHISH_URL, "downloaded_at": now_iso(),
                "last_modified": headers.get("Last-Modified"), "etag": headers.get("ETag")}, indent=1))
        except Exception as e:  # recorded, the benign half still runs
            log["openphish"] = {"url": OPENPHISH_URL, "error": f"{type(e).__name__}: {e}"}
            return []
    meta = json.loads(meta_path.read_text()) if meta_path.exists() else {}
    urls = [u.strip() for u in path.read_text(encoding="utf-8", errors="replace").splitlines() if u.strip()]
    log["openphish"] = {**meta, "urls_in_feed": len(urls)}
    return urls


def load_tranco(log: dict) -> list[tuple[int, str]]:
    path = RAW / f"tranco_{TRANCO_ID}.csv"
    if not path.exists():
        body, _ = http_get(TRANCO_CSV_URL, 300)
        path.write_bytes(body)
    try:
        meta, _ = http_get(TRANCO_META_URL, 30)
        created = json.loads(meta).get("created_on")
    except Exception as e:
        created = f"unavailable ({type(e).__name__})"
    rows = []
    with path.open(newline="", encoding="utf-8") as f:
        for row in csv.reader(f):
            if len(row) >= 2 and row[0].isdigit():
                rows.append((int(row[0]), row[1].strip().lower()))
    log["tranco"] = {"list_id": TRANCO_ID, "source": TRANCO_CSV_URL, "created_on": created, "rows": len(rows)}
    return rows


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")[:60]


def malicious_candidates(urls: list[str], seed: int) -> list[dict]:
    """One URL per registrable domain (first occurrence in feed order), domains shuffled."""
    by_domain: dict[str, str] = {}
    for u in urls:
        if not u.lower().startswith(("http://", "https://")):
            continue
        d = psl.registrable_domain_of_url(u)
        if d and d not in by_domain:
            by_domain[d] = u
    domains = sorted(by_domain)
    random.Random(seed).shuffle(domains)
    return [{"id": anon_id(f"mal_{slug(d)}"), "label": "malicious", "url": by_domain[d], "candidate_domain": d}
            for d in domains]


def benign_candidates(rows: list[tuple[int, str]], seed: int, lo: int, hi: int) -> list[dict]:
    pool = [(r, d) for r, d in rows if lo <= r <= hi]
    random.Random(seed).shuffle(pool)
    out, seen = [], set()
    for rank, d in pool:
        reg = psl.registrable_domain(d)
        if not reg or reg in seen:
            continue
        seen.add(reg)
        out.append({"id": f"ben_{slug(reg)}", "label": "benign", "url": f"https://{d}/",
                    "candidate_domain": reg, "tranco_rank": rank})
    return out


# ------------------------------------------------------------------ sandbox + lookups

class Pacer:
    """>= delay seconds between any two request starts (global, all workers)."""

    def __init__(self, delay: float):
        self.delay, self.lock, self.last = delay, threading.Lock(), 0.0

    def wait(self):
        with self.lock:
            gap = self.last + self.delay - time.monotonic()
            if gap > 0:
                time.sleep(gap)
            self.last = time.monotonic()


def sandbox_browse(url: str, timeout_ms: int) -> dict:
    try:
        p = subprocess.run(["docker", "compose", "exec", "-T", "sandbox", "python", "-c", SANDBOX_CLIENT,
                            url, str(timeout_ms)], cwd=ROOT / "backend", capture_output=True, text=True,
                           timeout=100)
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "timeout: docker exec exceeded 100 s"}
    if p.returncode != 0:
        return {"ok": False, "error": f"sandbox client failed: {(p.stderr or p.stdout).strip()[-300:]}"}
    try:
        return json.loads(p.stdout)
    except json.JSONDecodeError:
        return {"ok": False, "error": "sandbox returned non-JSON"}


async def lookups(domain: str) -> tuple[dict, dict]:
    return await asyncio.gather(rdap_tool.rdap(domain), cert_tool.cert(domain))


def build_case(cand: dict, obs: dict, sig: dict) -> dict:
    reg = obs["registrable_domain"]
    forms_full = [{"action_domain": f.get("action_domain"), "same_site": bool(f.get("same_site")),
                   "fields": [{"type": x.get("type") or "", "label": x.get("label") or "",
                               "name": x.get("name") or "", "autocomplete": x.get("autocomplete") or ""}
                              for x in f.get("fields") or []]}
                  for f in obs.get("forms") or []]
    return {
        "id": cand["id"],
        "label": cand["label"],
        "signals": {
            "url": cand["url"],
            "final_url": obs["final_url"],
            "registrable_domain": reg,
            "redirect_count": sig["redirect_count"],
            "domain_age_days": sig["domain_age_days"],
            "tls": {"issuer": sig["tls_issuer"], "cert_age_days": sig["cert_age_days"]},
            "safe_browsing": "unknown",
            "forms": [{"action_domain": f["action_domain"], "same_site": f["same_site"],
                       "fields": [{"type": x["type"], "label": x["label"]} for x in f["fields"]]}
                      for f in forms_full],
            "external_link_domains": list(obs.get("external_link_domains") or [])[:MAX_LINK_DOMAINS],
        },
        "page": {
            "lang": obs.get("lang") or "",
            "title": obs.get("title") or "",
            "visible_text": (obs.get("visible_text") or "")[:MAX_VISIBLE],
            "hidden_text": (obs.get("hidden_text") or "")[:MAX_HIDDEN],
        },
        # ---- side fields (not part of the Gemma input; run_eval.build_user_message ignores them)
        "forms_full": forms_full,
        "meta": {
            "source": "openphish" if cand["label"] == "malicious" else f"tranco:{TRANCO_ID}",
            "tranco_rank": cand.get("tranco_rank"),
            "http_status": obs.get("status"),
            "punycode": sig["punycode"],
            "homoglyph": sig["homoglyph"],
            "redirect_chain": obs.get("redirect_chain") or [],
            "fetched_at": cand.get("fetched_at"),
            "rdap_error": sig.get("rdap_error"),
            "cert_source": sig.get("cert_source"),
        },
    }


def process(cand: dict, pacer: Pacer, timeout_ms: int, known_good: set[str], dedupe: dict,
            dedupe_lock: threading.Lock) -> dict:
    pacer.wait()
    cand["fetched_at"] = now_iso()
    raw = sandbox_browse(cand["url"], timeout_ms)
    shot = raw.pop("screenshot_jpeg_b64", None)
    obs_dir, shot_dir = RAW / "obs", RAW / "screens"
    obs_dir.mkdir(parents=True, exist_ok=True)
    shot_dir.mkdir(parents=True, exist_ok=True)
    (obs_dir / f"{cand['id']}.json").write_text(json.dumps(raw, ensure_ascii=False, indent=1), encoding="utf-8")
    if shot:
        (shot_dir / f"{cand['id']}.jpg").write_bytes(base64.b64decode(shot))

    def excl(reason, detail=None):
        return {"id": cand["id"], "label": cand["label"], "url": cand["url"], "excluded": reason,
                "detail": (detail or "")[:160]}

    if not raw.get("ok"):
        return excl(classify_error(raw.get("error", "")), raw.get("error"))
    obs = normalize_obs(raw, cand["url"], cand["url"])
    if obs.get("downloads_attempted"):
        return excl("download_attempted", ",".join(raw.get("downloads") or []))
    status = obs.get("status")
    if isinstance(status, int) and status >= 400:
        return excl("http_error", f"HTTP {status}; title={obs.get('title', '')[:60]!r}")
    text = re.sub(r"\s+", " ", obs.get("visible_text") or "").strip()
    if len(text) < MIN_VISIBLE:
        return excl("empty_page", f"{len(text)} visible chars; title={obs.get('title', '')[:60]!r}")
    reason, pat = classify_page(obs)
    if reason:
        return excl(reason, f"{pat}; title={obs.get('title', '')[:60]!r}")
    reg = obs.get("registrable_domain")
    if not reg:
        return excl("no_registrable_domain", obs.get("final_url"))
    if cand["label"] == "malicious" and reg != cand["candidate_domain"] and reg in known_good:
        return excl("redirected_to_known_good", f"{cand['candidate_domain']} -> {reg}")
    with dedupe_lock:
        if reg in dedupe:
            return excl("duplicate_final_domain", f"{reg} already used by {dedupe[reg]}")
        dedupe[reg] = cand["id"]

    rd, ce = asyncio.run(lookups(reg))
    sig = report.build_signals(cand["url"], obs, rd, ce, {"result": "unknown"})
    sig["rdap_error"] = rd.get("error")
    sig["cert_source"] = "crt.sh" if ce.get("tls_issuer") else ("sandbox_tls" if sig["tls_issuer"] else None)
    return {"id": cand["id"], "label": cand["label"], "case": build_case(cand, obs, sig)}


def run_label(cands: list[dict], args, pacer: Pacer, known_good: set[str], dedupe: dict,
              dedupe_lock: threading.Lock, target: int, max_attempts: int) -> tuple[list, list, int, int]:
    """Try candidates in order until `target` kept or `max_attempts` tried.
    Returns (kept, excluded, tried, overshoot) - overshoot = kept beyond target in the last batch (discarded)."""
    kept: list[dict] = []
    excluded: list[dict] = []
    tried = 0
    idx = 0
    lock = threading.Lock()
    limit = min(len(cands), max_attempts)
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        while len(kept) < target and idx < limit:
            need = target - len(kept)
            batch = cands[idx: min(idx + max(need, args.workers), limit)]
            idx += len(batch)
            for res in pool.map(lambda c: process(c, pacer, args.timeout_ms, known_good, dedupe, dedupe_lock),
                                batch):
                tried += 1
                with lock:
                    if "case" in res:
                        kept.append(res["case"])
                        print(f"  keep {res['id']}", flush=True)
                    else:
                        excluded.append(res)
                        print(f"  drop {res['id']:<40} {res['excluded']}: {res['detail'][:80]}", flush=True)
    overshoot = max(0, len(kept) - target)
    return kept[:target], excluded, tried, overshoot


def label_log(cands_n: int, kept: list, excluded: list, tried: int, overshoot: int) -> dict:
    counts: dict[str, int] = {}
    for e in excluded:
        counts[e["excluded"]] = counts.get(e["excluded"], 0) + 1
    return {"candidates_available": cands_n, "attempted": tried, "kept": len(kept),
            "kept_overshoot_discarded": overshoot, "excluded_total": len(excluded),
            "excluded_by_reason": dict(sorted(counts.items())), "excluded": excluded}


def refilter(case: dict) -> tuple[str, str] | None:
    """Re-apply the CURRENT page classifier to an already-kept case (from raw/obs)."""
    try:
        raw = json.loads((RAW / "obs" / f"{case['id']}.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    obs = normalize_obs(raw, case["signals"]["url"], case["signals"]["url"])
    reason, pat = classify_page(obs)
    return (reason, f"post-filter {pat}; title={obs.get('title', '')[:60]!r}") if reason else None


def http_error_breakdown(log: dict) -> None:
    """Group http_error exclusions by status + page title (e.g. Cloudflare 'Suspected Phishing'
    takedown interstitials vs plain 403/404). Titles are read from raw/obs (sandbox output)."""
    for v in log.get("labels", {}).values():
        groups: dict[str, int] = {}
        for e in v.get("excluded", []):
            if e["excluded"] != "http_error":
                continue
            try:
                o = json.loads((RAW / "obs" / f"{e['id']}.json").read_text(encoding="utf-8"))
                key = f"HTTP {o.get('status')} {(o.get('title') or '').strip()[:40]!r}"
            except (OSError, ValueError):
                key = e["detail"][:50]
            groups[key] = groups.get(key, 0) + 1
        if groups:
            v["http_error_breakdown"] = dict(sorted(groups.items(), key=lambda kv: -kv[1]))


def assign_splits(cases: list[dict]) -> None:
    """Stratified deterministic 50/50: within each label sort by sha256(id), first half -> A."""
    for label in ("malicious", "benign"):
        group = sorted((c for c in cases if c["label"] == label),
                       key=lambda c: hashlib.sha256(c["id"].encode()).hexdigest())
        half = (len(group) + 1) // 2
        for i, c in enumerate(group):
            c["split"] = "A" if i < half else "B"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--labels", nargs="+", default=["malicious", "benign"], choices=["malicious", "benign"])
    ap.add_argument("--target", type=int, default=60, help="kept cases per label")
    ap.add_argument("--max-attempts", type=int, default=200, help="max candidates tried per label")
    ap.add_argument("--seed", type=int, default=20261104)
    ap.add_argument("--workers", type=int, default=2, help="concurrent sandbox requests (<= 3)")
    ap.add_argument("--delay", type=float, default=2.0, help="seconds between request starts (>= 1.5)")
    ap.add_argument("--timeout-ms", type=int, default=20000)
    ap.add_argument("--tranco-lo", type=int, default=200_000)
    ap.add_argument("--tranco-hi", type=int, default=1_000_000)
    ap.add_argument("--refresh-feed", action="store_true", help="re-download the OpenPhish feed")
    ap.add_argument("--out", default=str(HERE / "cases_real.jsonl"))
    ap.add_argument("--resume", action="store_true",
                    help="keep existing cases_real.jsonl + collect_log.json, re-apply the page classifier to kept "
                         "cases, and top up each label from not-yet-tried candidates (same seed/order)")
    ap.add_argument("--enrich-log-only", action="store_true",
                    help="only add http_error_breakdown to an existing collect_log.json (no browsing)")
    args = ap.parse_args()
    if args.enrich_log_only:
        lp = HERE / "collect_log.json"
        log = json.loads(lp.read_text(encoding="utf-8"))
        http_error_breakdown(log)
        lp.write_text(json.dumps(log, ensure_ascii=False, indent=1), encoding="utf-8")
        return
    if not 1 <= args.workers <= 3:
        sys.exit("--workers must be 1..3")
    if args.delay < 1.5:
        sys.exit("--delay must be >= 1.5 s")
    RAW.mkdir(parents=True, exist_ok=True)

    prev_cases: list[dict] = []
    if args.resume:
        log = json.loads((HERE / "collect_log.json").read_text(encoding="utf-8"))
        log.setdefault("resumed_at", []).append(now_iso())
        prev_cases = [json.loads(x) for x in Path(args.out).read_text(encoding="utf-8").splitlines() if x.strip()]
    else:
        log = {"started_at": now_iso(), "seed": args.seed, "target_per_label": args.target,
               "max_attempts_per_label": args.max_attempts, "workers": args.workers, "delay_s": args.delay,
               "sandbox": "backend docker compose service 'sandbox' via docker compose exec (no published port)",
               "psl_version": psl_version(), "labels": {}}
    rows = load_tranco(log)
    known_good = {psl.registrable_domain(d) or d for r, d in rows if r <= 100_000}
    pacer, dedupe, dedupe_lock = Pacer(args.delay), {}, threading.Lock()

    cases: list[dict] = []
    for label in ("malicious", "benign"):
        prev_kept = [c for c in prev_cases if c["label"] == label]
        prev = log["labels"].get(label, {}) if args.resume else {}
        prev_excl = list(prev.get("excluded", []))
        post = []
        for c in list(prev_kept):  # classifier may have improved since the first run
            hit = refilter(c)
            if hit:
                prev_kept.remove(c)
                post.append({"id": c["id"], "label": label, "url": c["signals"]["url"], "excluded": hit[0],
                             "detail": hit[1][:160]})
                print(f"  post-filter drop {c['id']}: {hit[0]} ({hit[1][:80]})", flush=True)
        for c in prev_kept:
            dedupe[c["signals"]["registrable_domain"]] = c["id"]
        if label not in args.labels:
            cases += prev_kept
            continue
        if label == "malicious":
            feed = load_openphish(log, args.refresh_feed and not args.resume)
            cands = malicious_candidates(feed, args.seed)
            log["openphish"]["distinct_registrable_domains"] = len(cands)
        else:
            cands = benign_candidates(rows, args.seed, args.tranco_lo, args.tranco_hi)
            log["tranco"]["rank_range"] = [args.tranco_lo, args.tranco_hi]
        print(f"{label}: {len(cands)} candidates", flush=True)
        done_ids = {c["id"] for c in prev_kept} | {e["id"] for e in prev_excl} | {e["id"] for e in post}
        prev_tried = prev.get("attempted", 0)
        todo = [c for c in cands if c["id"] not in done_ids]
        kept, excl, tried, over = run_label(todo, args, pacer, known_good, dedupe, dedupe_lock,
                                            args.target - len(prev_kept), args.max_attempts - prev_tried)
        entry = label_log(len(cands), prev_kept + kept, prev_excl + post + excl, prev_tried + tried,
                          prev.get("kept_overshoot_discarded", 0) + over)
        if post:
            entry["post_filtered"] = [e["id"] for e in post]
        log["labels"][label] = entry
        cases += prev_kept + kept

    assign_splits(cases)
    out = Path(args.out)
    with out.open("w", encoding="utf-8") as f:
        for c in cases:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
    http_error_breakdown(log)
    log["finished_at"] = now_iso()
    log["output"] = str(out.relative_to(ROOT)) if out.is_relative_to(ROOT) else str(out)
    log["split_counts"] = {f"{lab}/{s}": sum(1 for c in cases if c["label"] == lab and c["split"] == s)
                           for lab in ("malicious", "benign") for s in ("A", "B")}
    (HERE / "collect_log.json").write_text(json.dumps(log, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps({k: {kk: vv for kk, vv in v.items() if kk != "excluded"} for k, v in log["labels"].items()},
                     indent=1))


def psl_version() -> str | None:
    try:
        head = (ROOT / "contracts" / "public_suffix_list.dat").read_text(encoding="utf-8")[:2000]
        m = re.search(r"VERSION:\s*(\S+)", head)
        return m.group(1) if m else None
    except OSError:
        return None


if __name__ == "__main__":
    main()
