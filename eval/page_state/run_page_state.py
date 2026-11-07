"""Mac measurement: can Gemma tell 'the site's own page' from a CDN check / block / host warning / dead site?

Same LiteRT-LM runtime + model as the phone, greedy, thinking off, separate short conversation (no Turn 1).
Compares Gemma with a code-signature baseline (what the app can do without a model).
usage: .venv/bin/python eval/page_state/run_page_state.py models/gemma-4-E4B-it.litertlm
"""
import json, re, sys, time
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend"))
from redflag_backend import lite_followups as LF  # noqa: E402
import litert_lm as L  # noqa: E402

STATES = ["loaded", "challenge", "blocked", "host_warning", "unavailable"]
PROMPT = (ROOT / "contracts/gemma_page_state.txt").read_text(encoding="utf-8").strip()


def code_state(p):
    t, x, st = (p["title"] or "").lower(), (p["text"] or "").lower(), p["http_status"] or 0
    if re.search(r"suspected (phishing|malware)", t + " " + x) and "cloudflare" in t + " " + x: return "host_warning"
    if p["form_count"] > 0: return "loaded"
    if "completing the action below" in x: return "blocked"
    if t == "just a moment..." or (any(k in x for k in ("checking your browser", "performing security verification", "verifying your browser", "verify you are human"))
                                   and (any(k in x for k in ("cloudflare", "ray id", "ddos-guard", "vercel", "sucuri", "imperva", "akamai")) or st in (403, 429, 503))):
        return "challenge"
    if any(k in x for k in ("you have been blocked", "access denied", "request blocked", "request is blocked", "incapsula incident", "mod security")) or t == "403 forbidden": return "blocked"
    if st >= 500 or any(k in t + " " + x for k in ("site not found", "isn't live", "account suspended", "has been suspended", "web server is down")): return "unavailable"
    if st >= 400 and len(x) < 200: return "unavailable"
    return "loaded"


def message(p):
    return "<page>\n" + json.dumps({"http_status": p["http_status"], "title": LF.untrusted_text(p["title"]),
                                   "text": LF.untrusted_text(p["text"]), "form_count": p["form_count"],
                                   "has_password_field": p["has_password_field"]}, ensure_ascii=False, indent=1) + "\n</page>"


def gemma(eng, p):
    t0 = time.perf_counter()
    with eng.create_conversation(messages=[L.Message.system(PROMPT)],
                                 sampler_config=L.SamplerConfig(top_k=1, temperature=0.0),
                                 thinking_config=L.ThinkingConfig(enable_thinking=False), max_output_tokens=90) as conv:
        resp = conv.send_message(message(p)).to_json()
    dt = time.perf_counter() - t0
    parts = resp.get("content") or []
    text = parts if isinstance(parts, str) else "".join(q.get("text", "") for q in parts if isinstance(q, dict))
    o = LF.json_object_in(text) or {}
    st = o.get("page_state")
    return (st if st in STATES else None), o.get("provider"), text, dt


def main():
    model = sys.argv[1]
    cases = [json.loads(l) for l in open(ROOT / "eval/page_state/cases.jsonl", encoding="utf-8")]
    eng = L.Engine(model, backend=L.Backend.GPU())
    out = open(ROOT / ("eval/page_state/" + (sys.argv[2] if len(sys.argv) > 2 else "results.jsonl")), "w", encoding="utf-8")
    for i, c in enumerate(cases):
        g, prov, raw, dt = gemma(eng, c["page"])
        r = {"id": c["id"], "source": c["source"], "label": c["label"], "gemma": g, "provider": prov,
             "code": code_state(c["page"]), "ms": round(dt * 1000), "raw": raw[:300]}
        out.write(json.dumps(r, ensure_ascii=False) + "\n"); out.flush()
        if i % 20 == 0: print(i, r["label"], r["gemma"], r["code"], r["ms"], flush=True)
    out.close()


if __name__ == "__main__":
    main()
