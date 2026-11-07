"""sandbox_browse: HTTP call to the sandbox container (backend/sandbox), then normalization.

Normalization happens here (not only in the sandbox) because in DEMO_MODE the sandbox loads
http://demo-pages/<path>; URLs are mapped back onto the fake domain before registrable
domains / same_site are computed, so demo observations look exactly like real ones.
"""
from __future__ import annotations

import httpx

from .. import demo, psl
from ..config import get_settings


def _domains(urls: list[str], own: str | None, limit: int) -> list[str]:
    out: list[str] = []
    for u in urls:
        d = psl.registrable_domain_of_url(u)
        if d and d != own and d not in out:
            out.append(d)
        if len(out) >= limit:
            break
    return out


def normalize(raw: dict, requested_url: str, fetched_url: str) -> dict:
    """Sandbox JSON -> observation (demo URLs unmapped, PSL fields recomputed)."""
    if not raw.get("ok"):
        return {"ok": False, "requested_url": requested_url, "error": raw.get("error", "sandbox error"),
                "downloads_attempted": bool(raw.get("downloads_attempted"))}
    # Chromium's own error page (DNS failure, refused, TLS...) is not the site: the page did not load, so the
    # analysis cannot be complete (decision D3: no failure path yields SAFE).
    if str(raw.get("final_url") or "").startswith("chrome-error://"):
        return {"ok": False, "requested_url": requested_url,
                "error": f"page did not load: {raw.get('navigation_error') or 'browser error page'}",
                "downloads_attempted": bool(raw.get("downloads_attempted"))}

    def um(u):
        return demo.unmap(u, requested_url, fetched_url)

    final_url = um(raw.get("final_url")) or requested_url
    chain = [um(u) for u in raw.get("redirect_chain") or []]
    if fetched_url != requested_url:  # demo: the first hop is the fake URL itself
        chain = [requested_url] + [u for u in chain if u != requested_url]
        dedup: list[str] = []
        for u in chain:
            if not dedup or dedup[-1] != u:
                dedup.append(u)
        chain = dedup
    own = psl.registrable_domain_of_url(final_url)
    forms = []
    for f in raw.get("forms") or []:
        action = um(f.get("action"))
        ad = psl.registrable_domain_of_url(action)
        forms.append({"action": action, "method": f.get("method"), "action_domain": ad,
                      "same_site": ad is None or ad == own, "fields": f.get("fields") or []})
    links = [um(u) for u in raw.get("link_urls") or []]
    scripts = [um(u) for u in raw.get("script_urls") or []]
    # v4: where page data can go (form actions, submit-script URL literals, fetch/XHR/beacon while loading)
    endpoints = [{"url": um(e.get("url")), "via": e.get("via")}
                 for e in (raw.get("data_endpoints") or [])[:40] if isinstance(e, dict) and e.get("url")]
    return {
        "ok": True,
        "requested_url": requested_url,
        "final_url": final_url,
        "registrable_domain": own,
        "status": raw.get("status"),
        "redirect_chain": chain,
        "redirect_count": max(0, len(chain) - 1),
        "title": raw.get("title") or "",
        "lang": raw.get("lang") or "",
        "visible_text": raw.get("visible_text") or "",
        "hidden_text": raw.get("hidden_text") or "",
        "forms": forms,
        "external_link_domains": _domains(links, own, 20),
        "external_script_domains": _domains(scripts, own, 20),
        "data_endpoints": endpoints,
        "meta": raw.get("meta") or {},
        "counts": raw.get("counts") or {},
        "tls": raw.get("tls"),
        "screenshot_jpeg_b64": raw.get("screenshot_jpeg_b64"),
        "downloads_attempted": bool(raw.get("downloads_attempted")),
        "navigation_error": raw.get("navigation_error"),
        "timing": raw.get("timing") or {},
        "demo_fetch": fetched_url if fetched_url != requested_url else None,
    }


async def sandbox_browse(url: str) -> dict:
    s = get_settings()
    fetched = demo.fetch_url(url) or url
    try:
        async with httpx.AsyncClient(timeout=s.sandbox_timeout_s + 5) as client:
            r = await client.post(s.sandbox_url.rstrip("/") + "/browse",
                                  json={"url": fetched, "timeout_ms": int(s.sandbox_timeout_s * 1000)})
            r.raise_for_status()
            raw = r.json()
    except Exception as e:
        raw = {"ok": False, "error": f"sandbox unreachable: {type(e).__name__}"}
    return normalize(raw, url, fetched)


def for_model(obs: dict) -> dict:
    """Observation as given to Opus (no screenshot bytes; those go in an image block)."""
    if not obs.get("ok"):
        return {"ok": False, "error": obs.get("error"), "downloads_attempted": obs.get("downloads_attempted", False)}
    keep = ("final_url", "registrable_domain", "status", "redirect_chain", "title", "lang", "visible_text",
            "hidden_text", "forms", "external_link_domains", "external_script_domains", "meta", "counts",
            "downloads_attempted", "navigation_error")
    return {k: obs.get(k) for k in keep}
