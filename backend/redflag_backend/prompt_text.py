"""Page text normalization before it goes into the Gemma prompt (twin of core TextNorm.sanitizeForPrompt).

Control characters and line breaks -> space, ASCII double quote -> apostrophe, backslash -> slash,
whitespace runs -> one space. The model copies evidence verbatim from this text, so it never has to
escape anything inside a JSON string. Evidence grounding is checked against the same sanitized text."""
from __future__ import annotations

import re

_WS = re.compile(r"\s+")


def sanitize_page_text(s: str | None) -> str:
    if not s:
        return ""
    out = []
    for ch in s:
        o = ord(ch)
        if o < 32 or o == 127 or ch in "  \u0085":
            out.append(" ")
        elif ch == '"':
            out.append("'")
        elif ch == "\\":
            out.append("/")
        else:
            out.append(ch)
    return _WS.sub(" ", "".join(out)).strip()
