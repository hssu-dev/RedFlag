#!/usr/bin/env python3
"""
RedFlag Lite feasibility test: can Gemma 4 E4B extract phishing features from a
structured page observation and return valid JSON?

Works with any OpenAI-compatible local server (LM Studio, mlx_lm.server).
Standard library only.

  python3 run_eval.py --model <model-id>                       # free-form JSON (worst case, like on-device)
  python3 run_eval.py --model <model-id> --schema              # constrained via response_format json_schema
  python3 run_eval.py --model <model-id> --think               # Gemma 4 thinking mode on
  python3 run_eval.py --model <model-id> --runs 3 --temp 0.7   # stability check
  python3 run_eval.py --model <model-id> --only dbs_phish injection_m365

Default base URL is LM Studio (http://localhost:1234/v1).
For mlx_lm.server use --base-url http://localhost:8080/v1
"""
import argparse
import json
import random
import re
import statistics
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent

KEYS = [
    "evidence", "claimed_brand", "brand_matches_domain", "page_type",
    "requested_actions", "urgency_or_threat", "too_good_to_be_true",
    "addresses_ai_analyzer", "verdict", "confidence",
]
ENUMS = {
    "brand_matches_domain": ["yes", "no", "unclear", "no_brand"],
    "page_type": ["login", "payment", "shop", "download", "support", "info", "other"],
    "verdict": ["safe", "suspicious", "malicious"],
    "confidence": ["low", "medium", "high"],
}
ACTIONS = [
    "enter_password", "enter_card", "enter_personal_id", "enter_secret_code",
    "enter_contact_info", "pay_fee", "call_phone", "install_app",
    "connect_wallet", "none",
]
BOOLS = ["urgency_or_threat", "too_good_to_be_true", "addresses_ai_analyzer"]


def _prop(key):
    if key == "evidence":
        return {"type": "array", "items": {"type": "string"}, "maxItems": 3}
    if key == "claimed_brand":
        return {"type": ["string", "null"]}
    if key == "requested_actions":
        return {"type": "array", "items": {"type": "string", "enum": ACTIONS}, "minItems": 1}
    if key in BOOLS:
        return {"type": "boolean"}
    return {"type": "string", "enum": ENUMS[key]}


# Properties are listed in KEYS order so constrained decoders emit evidence first.
JSON_SCHEMA = {
    "type": "object",
    "properties": {k: _prop(k) for k in KEYS},
    "required": KEYS,
    "additionalProperties": False,
}


def build_user_message(case):
    signals = json.dumps(case["signals"], ensure_ascii=False, indent=1)
    page = json.dumps(case["page"], ensure_ascii=False, indent=1)
    return f"<signals>\n{signals}\n</signals>\n<untrusted_page>\n{page}\n</untrusted_page>"


_LITERT_ENGINE = None


def litert_engine(args):
    """One engine per process: loading/compiling the model is a one-off cost, like app start."""
    global _LITERT_ENGINE
    if _LITERT_ENGINE is None:
        import litert_lm
        backend = litert_lm.Backend.GPU() if args.device == "gpu" else litert_lm.Backend.CPU()
        if args.cache_dir:  # the runtime refuses a cache dir that does not exist yet
            Path(args.cache_dir).mkdir(parents=True, exist_ok=True)
        t0 = time.perf_counter()
        _LITERT_ENGINE = litert_lm.Engine(args.model, backend=backend, enable_benchmark=True,
                                          cache_dir=args.cache_dir)
        print(f"litert engine ready ({args.device}) in {time.perf_counter() - t0:.1f}s\n")
    return _LITERT_ENGINE


def call_litert(args, system, user):
    """LiteRT-LM Python API (same runtime and .litertlm file as Android).

    Thinking is switched on through ThinkingConfig instead of a literal <|think|>
    prefix, and the reasoning arrives on the separate "thought" channel.
    Latency covers conversation creation + prefill + decode for one page.
    """
    import litert_lm as L
    engine = litert_engine(args)
    if args.temp == 0:
        sampler = L.SamplerConfig(top_k=1, temperature=0.0)
    else:  # Gemma defaults besides temperature; fresh seed so repeated runs can differ
        sampler = L.SamplerConfig(top_k=64, top_p=0.95, temperature=args.temp,
                                  seed=random.randrange(1, 2**31))
    constrained = None
    response_format = None
    if args.schema:
        constrained = L.ConstrainedDecodingConfig(
            enable=True, provider=L.LiteRtLmConstraintProviderType.LL_GUIDANCE)
        response_format = L.ResponseFormat.json(JSON_SCHEMA)
    t0 = time.perf_counter()
    with engine.create_conversation(
        messages=[L.Message.system(system)],
        sampler_config=sampler,
        constrained_decoding_config=constrained,
        thinking_config=L.ThinkingConfig(enable_thinking=args.think),
        max_output_tokens=args.max_tokens,
    ) as conv:
        resp = conv.send_message(user, response_format=response_format).to_json()
        latency = time.perf_counter() - t0
        bench = conv.get_benchmark_info()
    parts = resp.get("content") or []
    if isinstance(parts, str):
        content = parts
    else:
        content = "".join(p.get("text", "") for p in parts if p.get("type") == "text")
    reasoning = (resp.get("channels") or {}).get("thought") or ""
    usage = {"completion_tokens": bench.last_decode_token_count,
             "prompt_tokens": bench.last_prefill_token_count}
    return content, latency, usage, bool(reasoning), reasoning


def call_model(args, system, user):
    if args.backend == "litert":
        return call_litert(args, system, user)
    body = {
        "model": args.model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "temperature": args.temp,
        "max_tokens": args.max_tokens,
        "stream": False,
    }
    if args.schema:
        body["response_format"] = {
            "type": "json_schema",
            "json_schema": {"name": "redflag_lite", "strict": True, "schema": JSON_SCHEMA},
        }
    if args.extra_body:  # runtime-specific switches, e.g. LM Studio {"reasoning_effort": "none"}
        body.update(json.loads(args.extra_body))
    req = urllib.request.Request(
        args.base_url.rstrip("/") + "/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=args.timeout) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    latency = time.perf_counter() - t0
    msg = data["choices"][0]["message"]
    content = msg.get("content") or ""
    reasoning = msg.get("reasoning_content") or msg.get("reasoning") or ""
    thought = bool(reasoning) or "<|channel>" in content
    return content, latency, data.get("usage") or {}, thought, reasoning


def extract_json(text):
    """Returns (obj, error, clean). clean = the answer was pure JSON with nothing around it."""
    if "<channel|>" in text:
        text = text.rsplit("<channel|>", 1)[1]
    stripped = text.strip()
    clean = stripped.startswith("{") and stripped.endswith("}")
    text = re.sub(r"```(?:json)?", "", text)
    start, end = text.find("{"), text.rfind("}")
    if start == -1:
        return None, "no_json", clean
    if end <= start:
        return None, "truncated", clean
    try:
        obj = json.loads(text[start:end + 1])
    except json.JSONDecodeError as e:
        return None, f"bad_json({e.msg})", clean
    if not isinstance(obj, dict):
        return None, "not_object", clean
    return obj, None, clean


def validate(obj):
    errs = [f"missing:{k}" for k in KEYS if k not in obj]
    for k, allowed in ENUMS.items():
        if k in obj and obj[k] not in allowed:
            errs.append(f"enum:{k}")
    for k in BOOLS:
        if k in obj and not isinstance(obj[k], bool):
            errs.append(f"type:{k}")
    actions = obj.get("requested_actions")
    if not isinstance(actions, list) or not actions or any(a not in ACTIONS for a in actions):
        errs.append("enum:requested_actions")
    brand = obj.get("claimed_brand")
    if brand is not None and not isinstance(brand, str):
        errs.append("type:claimed_brand")
    evidence = obj.get("evidence")
    if not isinstance(evidence, list) or len(evidence) > 3:
        errs.append("evidence")
    if list(k for k in obj if k in KEYS) != [k for k in KEYS if k in obj]:
        errs.append("order")
    return errs


def check(obj, exp):
    res = {}
    if "claimed_brand_any" in exp:
        want, got = exp["claimed_brand_any"], obj.get("claimed_brand")
        if want is None:
            res["claimed_brand"] = got is None
        else:
            res["claimed_brand"] = isinstance(got, str) and any(w.lower() in got.lower() for w in want)
    for k in ("brand_matches_domain", "verdict"):
        if k in exp:
            res[k] = obj.get(k) in exp[k]
    for k in BOOLS:
        if k in exp:
            res[k] = obj.get(k) is exp[k]
    if "actions_include" in exp:
        got = obj.get("requested_actions")
        got = set(got) if isinstance(got, list) else set()
        res["requested_actions"] = set(exp["actions_include"]) <= got
    return res


def _norm(s):
    return re.sub(r"\s+", " ", s).strip().strip("\"'“”‘’").lower()


def grounding(obj, case):
    """Share of evidence snippets that really appear verbatim in the page."""
    evidence = obj.get("evidence")
    if not isinstance(evidence, list) or not evidence:
        return None
    p = case["page"]
    hay = _norm(" ".join([p.get("title", ""), p.get("visible_text", ""), p.get("hidden_text", "")]))
    hits = sum(1 for s in evidence if isinstance(s, str) and _norm(s) and _norm(s) in hay)
    return hits / len(evidence)


def pct(a, b):
    return f"{a / b:.0%}" if b else "-"


def print_row(r):
    if "error" in r:
        print(f"{r['id']:<22} ERROR  {r['error']}")
        return
    head = f"{r['id']:<22} {r['latency']:6.2f}s"
    if r["output"] is None:
        print(f"{head}  PARSE FAIL ({r['parse_error']})")
        return
    verdict = str(r["output"].get("verdict"))
    fails = [k for k, ok in r["checks"].items() if not ok]
    errs = r["schema_errors"]
    status = "PASS" if not fails and not errs else "FAIL " + ",".join(fails + errs)
    print(f"{head}  verdict={verdict:<10} {status}")


def summarize(records, out_path, args):
    ok = [r for r in records if "error" not in r]
    parsed = [r for r in ok if r.get("output") is not None]
    valid = [r for r in parsed if not r["schema_errors"]]
    print("\n" + "=" * 64)
    print(f"requests            {len(records)}  (errors {len(records) - len(ok)})")
    if not ok:
        return
    print(f"JSON parsed         {len(parsed)}/{len(ok)} = {pct(len(parsed), len(ok))}")
    print(f"  clean output      {sum(r['clean'] for r in ok)}/{len(ok)}  (pure JSON, nothing around it)")
    print(f"schema valid        {len(valid)}/{len(ok)} = {pct(len(valid), len(ok))}")

    field_hits = {}
    for r in parsed:
        for k, v in r["checks"].items():
            field_hits.setdefault(k, []).append(v)
    print("field accuracy")
    for k, v in field_hits.items():
        print(f"  {k:<22} {sum(v)}/{len(v)} = {pct(sum(v), len(v))}")

    mal = [r for r in parsed if r["label"] == "malicious"]
    ben = [r for r in parsed if r["label"] == "benign"]
    if mal:
        caught = sum(r["output"].get("verdict") in ("malicious", "suspicious") for r in mal)
        strict = sum(r["output"].get("verdict") == "malicious" for r in mal)
        print(f"malicious caught    {caught}/{len(mal)}  (strict 'malicious' {strict}/{len(mal)})")
    if ben:
        alarm = sum(r["output"].get("verdict") == "malicious" for r in ben)
        sus = sum(r["output"].get("verdict") == "suspicious" for r in ben)
        print(f"benign false alarm  {alarm}/{len(ben)}  (marked suspicious {sus}/{len(ben)})")

    g = [r["grounding"] for r in parsed if r.get("grounding") is not None]
    if g:
        print(f"evidence verbatim   {statistics.mean(g):.0%}")
    lat = [r["latency"] for r in ok]
    print(f"latency (s)         mean {statistics.mean(lat):.2f}  median {statistics.median(lat):.2f}  max {max(lat):.2f}")
    toks = [r["completion_tokens"] for r in ok if r.get("completion_tokens")]
    if toks:
        print(f"completion tokens   mean {statistics.mean(toks):.0f}")
    if args.think:
        seen = sum(r["thought"] for r in ok)
        print(f"thinking observed   {seen}/{len(ok)}  (0 means the server did not turn thinking on)")
    print(f"\nraw results -> {out_path}")


def main():
    ap = argparse.ArgumentParser(description="RedFlag Lite / Gemma structured-output test")
    ap.add_argument("--model", required=True,
                    help="model id as shown by your local server (litert: path to the .litertlm file)")
    ap.add_argument("--backend", choices=["openai", "litert"], default="openai",
                    help="openai = OpenAI-compatible HTTP server; litert = LiteRT-LM Python API in-process")
    ap.add_argument("--device", choices=["cpu", "gpu"], default="gpu", help="litert backend only")
    ap.add_argument("--cache-dir", default=None, help="litert compiled-artifact cache directory")
    ap.add_argument("--base-url", default="http://localhost:1234/v1")
    ap.add_argument("--extra-body", default=None,
                    help='JSON merged into the request body (openai backend), e.g. \'{"reasoning_effort":"none"}\'')
    ap.add_argument("--schema", action="store_true", help="send response_format json_schema")
    ap.add_argument("--think", action="store_true", help="prepend <|think|> to the system prompt")
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--temp", type=float, default=0.0)
    ap.add_argument("--max-tokens", type=int, default=None)
    ap.add_argument("--timeout", type=float, default=180)
    ap.add_argument("--cases", default=str(HERE / "cases.jsonl"))
    ap.add_argument("--prompt", default=str(HERE / "system_prompt.txt"))
    ap.add_argument("--only", nargs="*", help="run only these case ids")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()
    if args.max_tokens is None:
        args.max_tokens = 4096 if args.think else 800

    system = Path(args.prompt).read_text(encoding="utf-8").strip()
    if args.think and args.backend == "openai":
        system = "<|think|>" + system  # litert turns thinking on via ThinkingConfig instead
    lines = Path(args.cases).read_text(encoding="utf-8").splitlines()
    cases = [json.loads(line) for line in lines if line.strip()]
    if args.only:
        cases = [c for c in cases if c["id"] in args.only]

    tag = f"{'schema' if args.schema else 'free'}{'-think' if args.think else ''}-t{args.temp}"
    out_path = Path(args.out) if args.out else HERE / f"results-{tag}.jsonl"
    backend = args.backend + (f"/{args.device}" if args.backend == "litert" else "")
    print(f"{len(cases)} cases x {args.runs} runs | mode={tag} | backend={backend} | model={args.model}\n")

    records = []
    with out_path.open("w", encoding="utf-8") as f:
        for case in cases:
            user = build_user_message(case)
            for run in range(args.runs):
                rec = {"id": case["id"], "run": run, "label": case["label"]}
                try:
                    content, latency, usage, thought, reasoning = call_model(args, system, user)
                except (urllib.error.URLError, TimeoutError, KeyError, IndexError, json.JSONDecodeError,
                        RuntimeError, ValueError) as e:
                    rec["error"] = str(e)
                else:
                    obj, perr, clean = extract_json(content)
                    rec.update(latency=round(latency, 3), completion_tokens=usage.get("completion_tokens"),
                               thought=thought, clean=clean, parse_error=perr, output=obj, raw=content)
                    if usage.get("prompt_tokens"):  # additive: needed to project on-phone prefill time
                        rec["prompt_tokens"] = usage["prompt_tokens"]
                    if reasoning:  # additive field, kept out of `raw` so parsing is unchanged
                        rec["reasoning"] = reasoning
                    if obj is not None:
                        rec["schema_errors"] = validate(obj)
                        rec["checks"] = check(obj, case["expected"])
                        rec["grounding"] = grounding(obj, case)
                records.append(rec)
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                print_row(rec)

    summarize(records, out_path, args)


if __name__ == "__main__":
    main()
