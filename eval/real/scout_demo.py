"""Find live phishing pages that Google Safe Browsing does NOT list yet, for a demo recording.

Same safety rules as collect.py: the host downloads only the OpenPhish feed text; every page is opened ONLY inside
the backend sandbox container (no clicks, typing, submission or downloads); requests are spaced >= 2 s apart.
Safe Browsing is asked with the backend's own client (v5 hash-prefix lookup, same API key/app identity as the phone).

    backend/.venv/bin/python eval/real/scout_demo.py --want 6 --max-tries 30
Writes eval/real/raw/demo_candidates.json (raw/ is gitignored) and prints defanged rows.
"""
import argparse
import asyncio
import json
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import collect  # noqa: E402  (reuses its feed loader, sandbox client, page filters)
from redflag_backend.tools.safe_browsing import safe_browsing  # noqa: E402

DOWNLOADS = re.compile(r"\.(vbs|exe|msi|apk|zip|rar|js|bat|scr|iso|dmg)(\?|$|%)", re.I)


def defang(u: str) -> str:
    return u.replace("http", "hxxp", 1).replace(".", "[.]")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--want", type=int, default=6)
    ap.add_argument("--max-tries", type=int, default=30)
    ap.add_argument("--seed", type=int, default=int(time.time()) // 3600)
    ap.add_argument("--need-form", action="store_true", help="keep only pages with at least one input field")
    ap.add_argument("--from-list", help="re-check the URLs of a defanged list (eval/real/sb_missed_phishing.txt) instead of the feed")
    args = ap.parse_args()

    log: dict = {}
    if args.from_list:
        rows = [l.split(" | ") for l in Path(args.from_list).read_text().splitlines() if l.strip() and not l.startswith("#")]
        urls = [r[1].strip().replace("hxxp", "http", 1).replace("[.]", ".") for r in rows if len(r) > 1]
        cands = [{"url": u, "candidate_domain": r[0].strip()} for u, r in zip(urls, rows)]
        print(f"list: {len(cands)} urls from {args.from_list}", flush=True)
    else:
        urls = collect.load_openphish(log, refresh=True)
        print(f"feed: {len(urls)} urls ({log.get('openphish', {}).get('fetched_at', '?')})", flush=True)
        cands = collect.malicious_candidates(urls, args.seed)
    cands = [c for c in cands if not DOWNLOADS.search(c["url"])]
    pacer = collect.Pacer(2.0)
    kept, tried = [], 0
    for c in cands:
        if len(kept) >= args.want or tried >= args.max_tries:
            break
        tried += 1
        # Safe Browsing first (a lookup, no page load): only pages it does NOT list are of interest
        sb = asyncio.run(safe_browsing(c["url"]))
        if sb.get("result") != "no_match":
            print(f"  skip {c['candidate_domain']}: safe browsing {sb.get('result')}", flush=True)
            continue
        pacer.wait()
        raw = collect.sandbox_browse(c["url"], 25_000)
        if not raw.get("ok"):
            print(f"  skip {c['candidate_domain']}: {collect.classify_error(raw.get('error', ''))}", flush=True)
            continue
        raw.pop("screenshot_jpeg_b64", None)
        obs = collect.normalize_obs(raw, c["url"], c["url"])
        if obs.get("downloads_attempted"):
            print(f"  skip {c['candidate_domain']}: download attempted", flush=True)
            continue
        reason, _ = collect.classify_page(obs)
        status = obs.get("status")
        forms = obs.get("forms") or []
        fields = [f for fm in forms for f in (fm.get("fields") or [])]
        has_pw = any((f.get("type") or "").lower() == "password" for f in fields)
        text = re.sub(r"\s+", " ", obs.get("visible_text") or "")
        if reason or (isinstance(status, int) and status >= 400) or len(text) < collect.MIN_VISIBLE:
            print(f"  skip {c['candidate_domain']}: {reason or status or 'empty'}", flush=True)
            continue
        if args.need_form and not fields:
            print(f"  skip {c['candidate_domain']}: no input field", flush=True)
            continue
        row = {
            "domain": c["candidate_domain"], "url": c["url"], "final_url": obs.get("final_url"),
            "title": (obs.get("title") or "")[:80], "inputs": len(fields), "password": has_pw,
            "safe_browsing": sb.get("result"), "checked_at": collect.now_iso(),
        }
        kept.append(row)
        print(f"  KEEP {row['domain']} | {defang(row['url'])[:90]} | {row['title']} | inputs={row['inputs']} pw={has_pw}", flush=True)
    out = collect.RAW / "demo_candidates.json"
    out.write_text(json.dumps(kept, ensure_ascii=False, indent=1))
    print(f"kept {len(kept)} of {tried} tried -> {out}")


if __name__ == "__main__":
    main()
