"""One Cloud analysis: deterministic tools -> Opus agent -> cloaking -> rule engine -> RiskReport.

The deterministic tools (sandbox_browse, rdap, cert, safe_browsing) always run in the backend
first, concurrently: their outputs are the signals the rule engine scores, so they must not
depend on the model. Opus then receives that observation and may call the same tools again
(other pages / domains) plus web_search, and returns features via submit_report.
"""
from __future__ import annotations

import asyncio
import json
import time
from typing import Any, Callable

from . import agent, cloaking, cloud_context, psl, report, scoring
from .config import Settings
from .store import Store
from .tools import cert as cert_tool
from .tools import rdap as rdap_tool
from .tools import safe_browsing as sb_tool
from .tools import sandbox_browse as browse_tool


class BadRequest(ValueError):
    pass


class Tracer:
    def __init__(self, sink: Callable[[dict], None] | None = None):
        self.t0 = time.monotonic()
        self.steps: list[dict] = []
        self.sink = sink

    def ms(self) -> int:
        return int((time.monotonic() - self.t0) * 1000)

    def __call__(self, step: str, detail: str) -> None:
        ev = {"t_ms": self.ms(), "step": step, "detail": str(detail)[:300]}
        self.steps.append(ev)
        if self.sink:
            self.sink(ev)


def _browse_detail(obs: dict) -> str:
    if not obs.get("ok"):
        return f"failed: {obs.get('error')}"
    sens = sum(1 for f in obs["forms"] if scoring.sensitive_categories(f))
    return (f"{obs['final_url'][:100]} | title '{obs['title'][:50]}' | {len(obs['forms'])} form(s), "
            f"{sens} sensitive | hidden text {len(obs['hidden_text'])} chars | {obs['redirect_count']} redirect(s)"
            + (" | download attempted" if obs["downloads_attempted"] else ""))


def _rdap_detail(r: dict) -> str:
    src = " (demo override)" if r.get("source") == "demo_override" else ""
    if r.get("domain_age_days") is None:
        return f"{r['domain']}: age unknown{src}" + (f" ({r['error'][:80]})" if r.get("error") else "")
    return f"{r['domain']}: registered {r['domain_age_days']} days ago{src}"


def _cert_detail(c: dict) -> str:
    src = " (demo override)" if c.get("source") == "demo_override" else ""
    if c.get("tls_issuer") is None:
        return f"{c['domain']}: no CT data{src}" + (f" ({c['error'][:80]})" if c.get("error") else "")
    return f"{c['domain']}: {c['tls_issuer']}, newest cert {c['cert_age_days']} days old{src}"


def _sb_detail(s: dict) -> str:
    return f"{s['result']} ({s['provider']})"


def _strip_obs(obs: dict) -> dict:
    return {k: v for k, v in obs.items() if k != "screenshot_jpeg_b64"}


class AgentTools:
    """Client tools exposed to Opus (bounded; DEMO_MODE mapping applies inside the tools)."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.browses = 0

    async def sandbox_browse(self, inp: dict):
        url = str(inp.get("url", ""))
        if psl.normalize_host(url) is None or not url.lower().startswith(("http://", "https://")):
            return [{"type": "text", "text": "error: url must be an absolute http(s) URL"}], f"rejected {url[:80]}"
        if self.browses >= self.settings.max_extra_browses:
            return [{"type": "text", "text": "error: sandbox_browse limit reached"}], "limit reached"
        self.browses += 1
        obs = await browse_tool.sandbox_browse(url)
        blocks = [{"type": "text", "text": "<untrusted_page>\n" + json.dumps(browse_tool.for_model(obs), ensure_ascii=False)
                   + "\n</untrusted_page>"}]
        if obs.get("screenshot_jpeg_b64"):
            blocks.append({"type": "image", "source": {"type": "base64", "media_type": "image/jpeg",
                                                       "data": obs["screenshot_jpeg_b64"]}})
        return blocks, _browse_detail(obs)

    async def rdap(self, inp: dict):
        r = await rdap_tool.rdap(str(inp.get("domain", "")))
        return [{"type": "text", "text": json.dumps(r, ensure_ascii=False)}], _rdap_detail(r)

    async def cert(self, inp: dict):
        c = await cert_tool.cert(str(inp.get("domain", "")))
        return [{"type": "text", "text": json.dumps(c, ensure_ascii=False)}], _cert_detail(c)

    async def safe_browsing(self, inp: dict):
        s = await sb_tool.safe_browsing(str(inp.get("url", "")))
        return [{"type": "text", "text": json.dumps(s, ensure_ascii=False)}], _sb_detail(s)

    def as_dict(self) -> dict:
        return {"sandbox_browse": self.sandbox_browse, "rdap": self.rdap, "cert": self.cert,
                "safe_browsing": self.safe_browsing}


async def _deterministic(url: str, emit: Tracer) -> dict:
    """sandbox_browse + rdap + cert + safe_browsing concurrently; re-check if redirected cross-site."""
    reg = psl.registrable_domain_of_url(url)
    tasks = {
        "sandbox_browse": asyncio.create_task(browse_tool.sandbox_browse(url)),
        "safe_browsing": asyncio.create_task(sb_tool.safe_browsing(url)),
    }
    if reg:
        tasks["rdap"] = asyncio.create_task(rdap_tool.rdap(reg))
        tasks["cert"] = asyncio.create_task(cert_tool.cert(reg))
    fmt = {"sandbox_browse": _browse_detail, "safe_browsing": _sb_detail, "rdap": _rdap_detail, "cert": _cert_detail}
    out: dict[str, Any] = {"rdap": None, "cert": None}
    pending = {t: name for name, t in tasks.items()}
    while pending:
        done, _ = await asyncio.wait(pending, return_when=asyncio.FIRST_COMPLETED)
        for t in done:
            name = pending.pop(t)
            out[name] = t.result()
            emit(name, fmt[name](out[name]))

    obs = out["sandbox_browse"]
    final_reg = obs.get("registrable_domain") if obs.get("ok") else None
    if final_reg and final_reg != reg:  # redirected to another site: signals describe the final site
        emit("redirect", f"final site {final_reg} differs from {reg}; re-checking")
        r2, c2, s2 = await asyncio.gather(rdap_tool.rdap(final_reg), cert_tool.cert(final_reg),
                                          sb_tool.safe_browsing(obs["final_url"]))
        emit("rdap", _rdap_detail(r2))
        emit("cert", _cert_detail(c2))
        emit("safe_browsing", _sb_detail(s2))
        if out["safe_browsing"]["result"] != "hit":
            out["safe_browsing"] = s2
        out["rdap"], out["cert"] = r2, c2
    return out


def _signals_for_model(sig: dict, reg: str | None) -> dict:
    return {
        "registrable_domain": reg,
        "domain_age_days": sig["domain_age_days"],
        "tls": {"issuer": sig["tls_issuer"], "cert_age_days": sig["cert_age_days"]},
        "safe_browsing": sig["safe_browsing"],
        "redirect_count": sig["redirect_count"],
        "punycode": sig["punycode"],
        "homoglyph": sig["homoglyph"],
        "forms": [{"action_domain": f["action_domain"], "same_site": f["same_site"],
                   "sensitive_fields": f["sensitive_fields"]} for f in sig["forms"]],
    }


async def analyze(*, url: str, lite_observation: dict | None, settings: Settings, store: Store,
                  client_factory: Callable[[], Any], emit: Tracer, user_context: dict | None = None) -> dict:
    url = url.strip()
    if psl.normalize_host(url) is None:  # also rejects javascript:, chrome:, file:, plain text
        raise BadRequest("url must be an http(s) URL")
    if not url.lower().startswith(("http://", "https://")):
        url = "https://" + url  # schemeless 'host/path' (same rule as the gate)
    emit("start", f"cloud analysis of {url[:120]}" + (" [DEMO_MODE]" if settings.demo_mode else ""))

    cached = store.get(url)
    if cached:
        bundle, age = cached
        emit("cache", f"hit ({int(age // 60)} min old): reusing observation + Opus features")
    else:
        emit("cache", "miss")
        det = await _deterministic(url, emit)
        obs = det["sandbox_browse"]
        sig = report.build_signals(url, obs, det["rdap"], det["cert"], det["safe_browsing"])
        final_url = obs.get("final_url") if obs.get("ok") else url
        reg = psl.registrable_domain_of_url(final_url)
        bundle = {"obs": _strip_obs(obs), "signals": sig, "features": None, "status": "complete",
                  "model": settings.model, "final_url": final_url, "registrable_domain": reg, "detail": ""}

        if sig["safe_browsing"] == "hit":
            emit("opus", "skipped: Safe Browsing hit already decides the level (floor 95)")
        elif not obs.get("ok"):
            bundle["status"] = "error"
            emit("opus", "skipped: the sandbox could not load the page")
        elif client_factory is None:
            bundle["status"] = "error"
            emit("opus", "skipped: ANTHROPIC_API_KEY is not configured")
        else:
            page = browse_tool.for_model(obs)
            first = agent.build_initial_message(url, _signals_for_model(sig, reg), page, obs.get("screenshot_jpeg_b64"))
            res = await agent.run_agent(client=client_factory(), settings=settings, store=store, emit=emit,
                                        url=url, first_message=first, tools=AgentTools(settings).as_dict())
            bundle.update(status=res.status, model=res.model, detail=res.detail)
            if res.features is not None:
                feats = dict(res.features)
                # Sandbox facts beat model claims for the two observed booleans.
                observed_cross = any((not f["same_site"]) and f["action_domain"] for f in obs["forms"])
                for key, val in (("form_posts_cross_site", observed_cross),
                                 ("downloads_attempted", bool(obs["downloads_attempted"]))):
                    if feats.get(key) != val:
                        emit("opus", f"{key}: model said {feats.get(key)}, sandbox observed {val} (using observed)")
                        feats[key] = val
                bundle["features"] = feats
            else:
                emit(res.status, f"{res.detail}; scoring deterministic signals only")
        if bundle["status"] == "complete":
            store.put([url, bundle["final_url"]], bundle)

    obs, sig, feats = bundle["obs"], bundle["signals"], bundle["features"]
    cl = cloaking.compare(lite_observation, obs, feats)
    if lite_observation:
        emit("cloaking", ("SUSPECTED: " + cl["detail"]) if cl["suspected"] else
             (cl["detail"] or "device and sandbox observations agree"))
    preview = scoring.score(report.scoring_input(bundle["registrable_domain"], sig, feats, cl["suspected"],
                                                 analysis_incomplete=bundle["status"] != "complete"))
    # User-provided context (manual_url_check.md §5): judged per request, AFTER the cached page stage, never stored.
    context = None
    uc = cloud_context.sanitize(user_context)
    if uc is not None:
        emit("context", cloud_context.summary(uc))
        cloud_j = None
        if feats is not None and client_factory is not None and (uc["statements"] or uc["pasted_messages"]):
            cloud_j = await cloud_context.judge(client=client_factory(), settings=settings, store=store, emit=emit, uc=uc,
                                                page_domain=bundle["registrable_domain"], level=preview.level, features=feats)
        elif feats is None:
            emit("context", "page stage has no features: using the device judgment only")
        context = cloud_context.merge(uc["device_judgment"], cloud_j, uc["statements"], cloud_context.conversation_of(uc))
        emit("context", f"merged: scenario={context['scenario']} consistency={context['consistency']} "
                        f"sender={context['sender_risk']} guided={context['guided']} "
                        f"(cloud={'yes' if cloud_j else 'no'}, device={'yes' if uc['device_judgment'] else 'no'})")
        preview = scoring.score(report.scoring_input(bundle["registrable_domain"], sig, feats, cl["suspected"],
                                                     analysis_incomplete=bundle["status"] != "complete", context=context))
    emit("scoring", f"score {preview.score} -> {preview.level} ({', '.join(preview.fired) or 'no rules fired'})")
    rep, _ = report.assemble(url=url, final_url=bundle["final_url"], status=bundle["status"], signals=sig,
                             features=feats, cloaking=cl, trace=list(emit.steps), model=bundle["model"],
                             latency_ms=emit.ms(), registrable_domain=bundle["registrable_domain"], context=context)
    return rep

