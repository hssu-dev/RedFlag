"""Cloaking = the phone (Lite) and the sandbox (Cloud) saw different pages.

Computed by the backend, never by the model. Inputs:
  lite_observation {claimed_brand, has_credential_form, title}  (what the user's device saw)
  sandbox observation (tools/sandbox_browse.normalize) + Opus features (may be None)

suspected = credential_gap OR brand_differs
  credential_gap: device saw a password/card/secret form, the sandbox saw no such form.
  brand_differs : device claimed brand B (normalized) and the sandbox page is about a
                  different brand (Opus claimed_brand != B and not the same brand_domains
                  entry), or B appears nowhere in the sandbox title/text and Opus saw no brand.
Title difference alone is NOT enough (titles are often dynamic); it is reported as
supporting detail when one of the two conditions holds.
"""
from __future__ import annotations

from . import scoring
from .textnorm import normalize


def _same_brand(a: str, b: str) -> bool:
    na, nb = normalize(a), normalize(b)
    if not na or not nb:
        return False
    if na == nb or f" {na} " in f" {nb} " or f" {nb} " in f" {na} ":
        return True
    ids_a = {x["id"] for x in scoring.match_brands(a)}
    ids_b = {x["id"] for x in scoring.match_brands(b)}
    return bool(ids_a & ids_b)


def _brand_visible(brand: str, text: str) -> bool:
    hay = f" {normalize(text)} "
    names = [brand] + [al for b in scoring.match_brands(brand) for al in b["aliases"]]
    return any((n := normalize(x)) and f" {n} " in hay for x in names)


def _title_similarity(a: str, b: str) -> float:
    ta, tb = set(normalize(a).split()), set(normalize(b).split())
    if not ta or not tb:
        return 1.0
    return len(ta & tb) / len(ta | tb)


def compare(lite: dict | None, obs: dict | None, features: dict | None) -> dict:
    if not lite:
        return {"suspected": False, "detail": None}
    if not obs or not obs.get("ok"):
        return {"suspected": False, "detail": "not compared: the sandbox could not load the page"}

    reasons: list[str] = []
    support: list[str] = []

    sandbox_sensitive = sorted({c for f in obs.get("forms") or [] for c in scoring.sensitive_categories(f)})
    credential_gap = bool(lite.get("has_credential_form")) and not sandbox_sensitive
    if credential_gap:
        reasons.append("the device saw a password/card/code form, the sandbox saw none")

    brand_differs = False
    lite_brand = (lite.get("claimed_brand") or "").strip()
    if lite_brand:
        opus_brand = ((features or {}).get("claimed_brand") or "").strip()
        page_text = f"{obs.get('title', '')} {obs.get('visible_text', '')}"
        if opus_brand:
            if not _same_brand(lite_brand, opus_brand):
                brand_differs = True
                reasons.append(f"the device page claimed '{lite_brand}', the sandbox page presents '{opus_brand}'")
        elif not _brand_visible(lite_brand, page_text):
            brand_differs = True
            reasons.append(f"the device page claimed '{lite_brand}', the sandbox page shows no '{lite_brand}'")

    lite_title, sb_title = (lite.get("title") or "").strip(), (obs.get("title") or "").strip()
    if lite_title and sb_title and _title_similarity(lite_title, sb_title) < 0.34:
        support.append(f"titles differ: device '{lite_title[:80]}' vs sandbox '{sb_title[:80]}'")

    suspected = credential_gap or brand_differs
    if not suspected:
        return {"suspected": False, "detail": None}
    detail = "; ".join(reasons + support)
    return {"suspected": True, "detail": detail[0].upper() + detail[1:]}
