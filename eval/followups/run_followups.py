"""Mac measurement of the v4 follow-up turns on eval/real (120 real pages), offline.

Turn 1 answers come from eval/real/results-gemma.jsonl (same model, same prompt as the phone), interpreted
with gemma_repair.interpret (no follow-up available offline). Then, with the SAME LiteRT-LM runtime and model:
  Turn D (destination relation): only when the twin's candidate selection finds a cross-site personal-data
         destination. Offline the destinations are the cases' form action domains (script/XHR endpoints
         were not collected for these pages).
  Turn 2 (does context matter? native tool calls): for source_app = unknown (Chrome path, no notification)
         and sms (link from a text message). Friction = benign pages that get a context card.
Prompts / parsing / decisions are backend/redflag_backend/lite_followups.py (twin of the phone code).

usage: python eval/followups/run_followups.py MODEL.litertlm [--limit N] [--out eval/followups/results.jsonl]
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "eval"))
import rf_eval_common as C  # noqa: E402
from redflag_backend import gemma_repair, scoring  # noqa: E402
from redflag_backend import lite_followups as LF  # noqa: E402

import litert_lm as L  # noqa: E402
from litert_lm.interfaces import Tool  # noqa: E402


class ContractTool(Tool):
    def __init__(self, fn: dict):
        self.fn = fn

    def get_tool_description(self) -> dict:
        return {"type": "function", "function": self.fn}

    def execute(self, param):  # never executed: automatic tool calling is off
        return {}


def chat(eng, system: str, user: str, tools=None, max_tokens: int = 256):
    t0 = time.perf_counter()
    with eng.create_conversation(messages=[L.Message.system(system)], tools=tools, automatic_tool_calling=False,
                                 sampler_config=L.SamplerConfig(top_k=1, temperature=0.0),
                                 thinking_config=L.ThinkingConfig(enable_thinking=False),
                                 max_output_tokens=max_tokens) as conv:
        resp = conv.send_message(user).to_json()
    dt = time.perf_counter() - t0
    parts = resp.get("content") or []
    text = parts if isinstance(parts, str) else "".join(p.get("text", "") for p in parts if isinstance(p, dict) and p.get("type") == "text")
    calls = []
    for tc in resp.get("tool_calls") or []:
        fn = tc.get("function") or {}
        args = fn.get("arguments") or {}
        if isinstance(args, str):
            try:
                args = json.loads(args)
            except ValueError:
                args = {}
        calls.append({"name": fn.get("name"), "args": args if isinstance(args, dict) else {}})
    return text, calls, dt


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--out", default=str(ROOT / "eval/followups/results.jsonl"))
    a = ap.parse_args()
    cases = C.load_jsonl(ROOT / "eval/real/cases_real.jsonl")
    recs = {r["id"]: r for r in C.load_jsonl(ROOT / "eval/real/results-gemma.jsonl")}
    if a.limit:
        cases = cases[: a.limit]
    eng = L.Engine(a.model, backend=L.Backend.GPU())
    tools = [ContractTool(t) for t in LF.tools()]
    p_dest, p_need = LF.prompt("gemma_destination.txt"), LF.prompt("gemma_context_need.txt")
    out = open(a.out, "w")
    for i, c in enumerate(cases):
        rec = recs.get(c["id"]) or {}
        raw = rec.get("raw") or ""
        it = gemma_repair.interpret(raw, None, []) if raw else None
        feats = it["features"] if it else None
        inp = C.scoring_input(c, feats)
        inp["features_repaired"] = bool(it and it["repaired"])
        inp["core_fields_repaired"] = bool(it and it["core_repaired"])
        inp["analysis_incomplete"] = feats is None
        base = scoring.score(inp)
        row = {"id": c["id"], "label": c["label"], "split": c.get("split"), "score": base.score, "level": base.level}
        # ---- Turn D
        reg = c["signals"].get("registrable_domain")
        eps = [{"url": f"https://{f['action_domain']}/", "via": "form"} for f in (c.get("forms_full") or c["signals"].get("forms") or []) if f.get("action_domain")]
        cands = LF.destination_candidates(eps, reg, base.facts.get("personal_request") is True) if feats else []
        if cands:
            text, _, dt = chat(eng, p_dest, LF.destination_message(reg, feats, cands))
            ans = LF.parse_destination(text, cands)
            inp2 = dict(inp, form_destination=(ans or {}).get("relation"), form_destination_domain=(ans or {}).get("domain"))
            s2 = scoring.score(inp2)
            row["dest"] = {"candidates": cands, "answer": ans, "raw": text[:200], "latency": round(dt, 2), "score_after": s2.score, "level_after": s2.level}
        # ---- Turn 2
        row["turn2"] = {}
        for src in ("unknown", "sms"):
            decision = LF.decide(base.score, feats is not None, c["signals"].get("safe_browsing") == "hit", feats, src)
            if decision == "exclude":
                row["turn2"][src] = {"decision": decision}
                continue
            text, calls, dt = chat(eng, p_need, LF.turn2_message(feats, decision, src, reg, c["signals"].get("domain_age_days"), base.level), tools)
            if not calls:
                calls = LF.tool_calls_from_text(text)
            parsed = LF.parse_tool_calls(calls)
            plan = LF.resolve(decision, parsed, src, feats)
            row["turn2"][src] = {"decision": decision, "calls": calls, "text": text[:200], "parsed": parsed, "plan": plan, "latency": round(dt, 2)}
        out.write(json.dumps(row, ensure_ascii=False) + "\n")
        out.flush()
        print(i + 1, c["id"], c["label"], base.score, {k: (v.get("plan") or {}).get("origin", v["decision"]) for k, v in row["turn2"].items()},
              (row.get("dest") or {}).get("answer"), flush=True)


if __name__ == "__main__":
    main()
