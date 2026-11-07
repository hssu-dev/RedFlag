"""Certificate transparency via crt.sh JSON: issuer + age of the newest cert, first-seen age.

crt.sh is often slow or 5xx: any error/timeout yields nulls (never blocks the analysis).
DEMO_MODE=1: demo domains return signal_overrides (tls_issuer, cert_age_days) instead.
"""
from __future__ import annotations

import re
from datetime import datetime, timezone

import httpx

from .. import demo, psl
from ..config import get_settings

CRT_SH = "https://crt.sh/"


def _issuer_org(issuer_name: str | None) -> str | None:
    if not issuer_name:
        return None
    m = re.search(r"(?:^|,\s*)O=(\"[^\"]+\"|[^,]+)", issuer_name)
    if m:
        return m.group(1).strip().strip('"')
    m = re.search(r"CN=([^,]+)", issuer_name)
    return m.group(1).strip() if m else issuer_name[:80]


def _dt(s: str | None) -> datetime | None:
    if not s:
        return None
    try:
        d = datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None
    return d if d.tzinfo else d.replace(tzinfo=timezone.utc)


def parse_crtsh(rows: list[dict], now: datetime | None = None) -> dict:
    now = now or datetime.now(timezone.utc)
    dated = [(d, r) for r in rows if (d := _dt(r.get("not_before")))]
    if not dated:
        return {"tls_issuer": None, "cert_age_days": None, "first_seen_days": None, "cert_count": len(rows)}
    newest_d, newest = max(dated, key=lambda t: t[0])
    earliest_d, _ = min(dated, key=lambda t: t[0])
    return {
        "tls_issuer": _issuer_org(newest.get("issuer_name")),
        "cert_age_days": max(0, (now - newest_d).days),
        "first_seen_days": max(0, (now - earliest_d).days),
        "cert_count": len(rows),
    }


async def cert(domain: str) -> dict:
    domain = (psl.registrable_domain(domain) or domain).lower()
    over = demo.signal_overrides(domain)
    if over is not None:
        return {"domain": domain, "tls_issuer": over.get("tls_issuer"), "cert_age_days": over.get("cert_age_days"),
                "first_seen_days": over.get("cert_age_days"), "source": "demo_override"}
    out = {"domain": domain, "tls_issuer": None, "cert_age_days": None, "first_seen_days": None, "source": "crt.sh"}
    try:
        async with httpx.AsyncClient(timeout=get_settings().cert_timeout_s) as client:
            r = await client.get(CRT_SH, params={"q": domain, "output": "json", "exclude": "expired"})
            r.raise_for_status()
            rows = r.json() if r.content.strip() else []
    except Exception as e:
        return {**out, "error": f"{type(e).__name__}: {str(e)[:120]}"}
    return {**out, **parse_crtsh(rows if isinstance(rows, list) else [])}
