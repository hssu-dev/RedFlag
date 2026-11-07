"""RDAP domain age: IANA bootstrap (data.iana.org/rdap/dns.json) -> registry RDAP -> registration date.

DEMO_MODE=1: demo domains return contracts/demo_hosts.json signal_overrides instead (the fake
domains have no registration and are never queried).
"""
from __future__ import annotations

import time
from datetime import datetime, timezone

import httpx

from .. import demo, psl
from ..config import get_settings

BOOTSTRAP_URL = "https://data.iana.org/rdap/dns.json"
_BOOTSTRAP: dict = {"at": 0.0, "map": None}
_BOOTSTRAP_TTL = 24 * 3600


async def _bootstrap(client: httpx.AsyncClient) -> dict[str, str]:
    if _BOOTSTRAP["map"] is not None and time.time() - _BOOTSTRAP["at"] < _BOOTSTRAP_TTL:
        return _BOOTSTRAP["map"]
    r = await client.get(BOOTSTRAP_URL)
    r.raise_for_status()
    mapping: dict[str, str] = {}
    for tlds, urls in r.json()["services"]:
        base = next((u for u in urls if u.startswith("https://")), urls[0])
        for tld in tlds:
            mapping[tld.lower()] = base
    _BOOTSTRAP.update(at=time.time(), map=mapping)
    return mapping


def _parse_date(s: str) -> datetime | None:
    try:
        d = datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None
    return d if d.tzinfo else d.replace(tzinfo=timezone.utc)


def parse_rdap(doc: dict, now: datetime | None = None) -> dict:
    now = now or datetime.now(timezone.utc)
    reg = None
    for ev in doc.get("events") or []:
        if ev.get("eventAction") == "registration" and ev.get("eventDate"):
            reg = _parse_date(ev["eventDate"])
            break
    registrar = None
    for ent in doc.get("entities") or []:
        if "registrar" in (ent.get("roles") or []):
            for item in (ent.get("vcardArray") or [None, []])[1]:
                if item and item[0] == "fn":
                    registrar = item[3]
            break
    return {
        "registration_date": reg.isoformat() if reg else None,
        "domain_age_days": max(0, (now - reg).days) if reg else None,
        "registrar": registrar,
    }


async def rdap(domain: str) -> dict:
    domain = (psl.registrable_domain(domain) or domain).lower()
    over = demo.signal_overrides(domain)
    if over is not None:
        return {"domain": domain, "domain_age_days": over.get("domain_age_days"), "registration_date": None,
                "registrar": None, "source": "demo_override"}
    out = {"domain": domain, "domain_age_days": None, "registration_date": None, "registrar": None, "source": "rdap"}
    if psl.is_ip(domain) or "." not in domain:
        return {**out, "error": "not a registrable domain"}
    try:
        async with httpx.AsyncClient(timeout=get_settings().rdap_timeout_s, follow_redirects=True,
                                     headers={"Accept": "application/rdap+json"}) as client:
            base = (await _bootstrap(client)).get(domain.rsplit(".", 1)[-1])
            if not base:
                return {**out, "error": "TLD has no RDAP service in the IANA bootstrap"}
            r = await client.get(base.rstrip("/") + "/domain/" + domain)
            if r.status_code == 404:
                return {**out, "error": "not found in registry RDAP"}
            r.raise_for_status()
            return {**out, **parse_rdap(r.json()), "rdap_server": base}
    except Exception as e:
        return {**out, "error": f"{type(e).__name__}: {str(e)[:120]}"}
