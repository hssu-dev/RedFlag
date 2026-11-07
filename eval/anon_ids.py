"""Public-repo hygiene for real phishing cases.

Case IDs of malicious real pages used to be `mal_<domain slug>` (e.g. mal_example_com), which spells
out a live phishing domain in every committed result file. They are now `mal_NNN`. The mapping
original -> anonymous lives only in eval/real/raw/id_map.json (eval/real/raw/ is gitignored), so the
collector, the page-state builder and every local file keep agreeing on the same numbers.

scrub() replaces given domains inside free text with a same-length `xxx.test` placeholder (same
length so text truncation downstream is unchanged).
"""
from __future__ import annotations

import json
import re
from pathlib import Path

MAP_PATH = Path(__file__).resolve().parent / "real" / "raw" / "id_map.json"
_ANON = re.compile(r"^mal_\d{3,}$")


def load_map() -> dict[str, str]:
    return json.loads(MAP_PATH.read_text(encoding="utf-8")) if MAP_PATH.exists() else {}


def save_map(m: dict[str, str]) -> None:
    MAP_PATH.parent.mkdir(parents=True, exist_ok=True)
    MAP_PATH.write_text(json.dumps(m, indent=1, sort_keys=True) + "\n", encoding="utf-8")


def anon_id(orig: str) -> str:
    """mal_<slug> -> mal_NNN (allocating the next number on first sight). Other IDs pass through."""
    if not orig.startswith("mal_") or _ANON.match(orig):
        return orig
    m = load_map()
    if orig not in m:
        m[orig] = f"mal_{len(m) + 1:03d}"
        save_map(m)
    return m[orig]


def placeholder(domain: str) -> str:
    return "x" * max(1, len(domain) - 5) + ".test"


def scrub(text: str, domains) -> str:
    """Replace each domain (longest first, whole-label match, case-insensitive) by placeholder()."""
    for d in sorted({d.lower() for d in domains if d and len(d) > 5}, key=len, reverse=True):
        text = re.sub(r"(?<![A-Za-z0-9-])" + re.escape(d) + r"(?![A-Za-z0-9-])", placeholder(d), text, flags=re.I)
    return text
