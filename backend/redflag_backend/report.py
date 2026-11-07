"""Signals -> ScoringInput -> scoring.score(mode="cloud") -> RiskReport (schema-validated)."""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
from functools import lru_cache

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

from . import contracts, psl, scoring

SCHEMAS = ("risk_report.schema.json", "lite_features.schema.json", "cloud_features.schema.json")


@lru_cache(maxsize=1)
def _validator() -> Draft202012Validator:
    reg = Registry()
    for name in SCHEMAS:
        s = contracts.load(name)
        res = Resource.from_contents(s)
        reg = reg.with_resource(s["$id"], res).with_resource(name, res)
    return Draft202012Validator(contracts.load("risk_report.schema.json"), registry=reg,
                                format_checker=FormatChecker())


class ReportInvalid(ValueError):
    pass


def validate_report(report: dict) -> None:
    errors = sorted(_validator().iter_errors(report), key=lambda e: list(e.path))
    if errors:
        e = errors[0]
        raise ReportInvalid(f"RiskReport invalid at {'/'.join(map(str, e.path)) or '<root>'}: {e.message}")


def _days_since_epoch_s(ts) -> int | None:
    try:
        return max(0, (datetime.now(timezone.utc) - datetime.fromtimestamp(float(ts), timezone.utc)).days)
    except (TypeError, ValueError, OverflowError):
        return None


def build_signals(url: str, obs: dict | None, rdap: dict | None, cert: dict | None, sb: dict | None) -> dict:
    """Deterministic signals (report.signals shape + 'scoring_forms' for the rule engine)."""
    obs = obs if obs and obs.get("ok") else None
    final_url = (obs or {}).get("final_url") or url
    host = psl.normalize_host(final_url)
    tls_issuer = (cert or {}).get("tls_issuer")
    cert_age = (cert or {}).get("cert_age_days")
    tls = (obs or {}).get("tls") or {}
    if tls_issuer is None and tls.get("issuer"):  # crt.sh down: use the cert the sandbox saw
        tls_issuer = tls["issuer"]
        cert_age = _days_since_epoch_s(tls.get("valid_from"))
    forms = (obs or {}).get("forms") or []
    return {
        "domain_age_days": (rdap or {}).get("domain_age_days"),
        "tls_issuer": tls_issuer,
        "cert_age_days": cert_age,
        "safe_browsing": (sb or {}).get("result", "unknown"),
        "redirect_count": int((obs or {}).get("redirect_count") or 0),
        "punycode": psl.is_punycode(host),
        "homoglyph": psl.is_homoglyph(host),
        "forms": [{"action_domain": f.get("action_domain"), "same_site": bool(f.get("same_site")),
                   "sensitive_fields": scoring.sensitive_categories(f)} for f in forms],
        "external_link_domains": list((obs or {}).get("external_link_domains") or [])[:10],
        "scoring_forms": [{"action_domain": f.get("action_domain"), "same_site": bool(f.get("same_site")),
                           "fields": f.get("fields") or []} for f in forms],
        # rule-engine only (hidden_ai_instruction patterns); not part of report.signals
        "scoring_hidden_text": (obs or {}).get("hidden_text") or "",
        # rule-engine only (v4 data_destinations); not part of report.signals
        "scoring_data_endpoints": [e["url"] for e in (obs or {}).get("data_endpoints") or []
                                   if isinstance(e, dict) and isinstance(e.get("url"), str)],
    }


def scoring_input(registrable_domain: str | None, signals: dict, features: dict | None,
                  cloaking_suspected: bool, analysis_incomplete: bool = False, context: dict | None = None) -> dict:
    """analysis_incomplete (status timeout/error) -> the analysis_incomplete clamp: never safe (decision D3).
    context: merged user-context judgment (cloud_context.merge), v4 context rules; None = no context."""
    inp = {
        "mode": "cloud",
        "registrable_domain": registrable_domain,
        "features": features,
        "signals": {
            "safe_browsing": signals["safe_browsing"],
            "domain_age_days": signals["domain_age_days"],
            "punycode": signals["punycode"],
            "homoglyph": signals["homoglyph"],
            "forms": signals["scoring_forms"],
            "hidden_text": signals.get("scoring_hidden_text") or "",
            "data_endpoints": signals.get("scoring_data_endpoints") or [],
        },
        "cloaking_suspected": cloaking_suspected,
        "analysis_incomplete": analysis_incomplete,
    }
    if context is not None:
        inp["context"] = context
    return inp


def assemble(*, url: str, final_url: str, status: str, signals: dict, features: dict | None,
             cloaking: dict, trace: list[dict], model: str, latency_ms: int,
             registrable_domain: str | None, context: dict | None = None) -> tuple[dict, scoring.ScoreResult]:
    result = scoring.score(scoring_input(registrable_domain, signals, features, cloaking["suspected"],
                                         analysis_incomplete=status != "complete", context=context))
    report = {
        "schema_version": "1",
        "id": str(uuid.uuid4()),
        "mode": "cloud",
        "status": status,
        "url": url,
        "final_url": final_url,
        "registrable_domain": registrable_domain,
        "score": result.score,
        "level": result.level,
        "escalate": result.escalate,
        "headline": result.headline,
        "red_flags": [f.to_dict() for f in result.red_flags],
        "brand": result.brand,
        "signals": {k: v for k, v in signals.items() if k not in ("scoring_forms", "scoring_hidden_text", "scoring_data_endpoints")},
        "features": features,
        "cloaking": cloaking,
        "trace": trace,
        "engine": {"model": model, "runtime": "anthropic-messages-api", "think": True},
        "latency_ms": max(0, int(latency_ms)),
        "created_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    validate_report(report)
    return report, result
