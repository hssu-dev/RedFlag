"""normalize() shared with android/core (see scoring_rules.json sensitive_fields.doc)."""
from __future__ import annotations

import unicodedata


def normalize(text: str | None) -> str:
    if not text:
        return ""
    text = unicodedata.normalize("NFKC", text).lower()
    out = [ch if ch.isalpha() or ch.isdigit() else " " for ch in text]
    return " ".join("".join(out).split())


def contains_phrase(haystack_norm: str, phrase: str) -> bool:
    """Whole-token containment of normalize(phrase) in an already-normalized haystack."""
    p = normalize(phrase)
    if not p:
        return False
    return f" {p} " in f" {haystack_norm} "
