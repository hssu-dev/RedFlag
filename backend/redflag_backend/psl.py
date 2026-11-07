"""Host normalization + registrable domain (eTLD+1) from contracts/public_suffix_list.dat.

Same semantics as android/core Gate (see contracts/url_vectors.json doc):
  * host: lowercase, NFKC, IDN -> punycode (per label), no port/userinfo/trailing dot,
    IPv6 kept in brackets.
  * registrable_domain: PSL algorithm over BOTH the ICANN and the PRIVATE section
    (wildcards and exception rules included; Unicode rules converted to punycode).
    None for IP literals, bare public suffixes and single labels (localhost).

Stdlib only: this module is also copied into the sandbox image (backend/sandbox).
"""
from __future__ import annotations

import ipaddress
import os
import re
import unicodedata
from functools import lru_cache
from pathlib import Path
from urllib.parse import urlsplit

_DOTS = str.maketrans({"。": ".", "．": ".", "｡": "."})
_SCHEME_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.\-]*:")


def _psl_path() -> Path:
    env = os.environ.get("REDFLAG_PSL_PATH")
    if env:
        return Path(env)
    from . import contracts  # local import: the sandbox sets REDFLAG_PSL_PATH instead

    return contracts.contracts_dir() / "public_suffix_list.dat"


def label_to_ascii(label: str) -> str:
    if not label or label.isascii():
        return label
    return "xn--" + label.encode("punycode").decode("ascii")


def label_to_unicode(label: str) -> str:
    if label.startswith("xn--"):
        try:
            return label[4:].encode("ascii").decode("punycode")
        except Exception:  # malformed punycode: keep as-is
            return label
    return label


def host_to_ascii(host: str) -> str:
    host = unicodedata.normalize("NFKC", host).translate(_DOTS).lower().rstrip(".")
    return ".".join(label_to_ascii(lbl) for lbl in host.split("."))


@lru_cache(maxsize=1)
def _rules() -> tuple[frozenset, frozenset, frozenset]:
    exact, wild, exc = set(), set(), set()
    for raw in _psl_path().read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("//"):
            continue
        rule = line.split()[0]
        if rule.startswith("!"):
            exc.add(host_to_ascii(rule[1:]))
        elif rule.startswith("*."):
            wild.add(host_to_ascii(rule[2:]))
        else:
            exact.add(host_to_ascii(rule))
    return frozenset(exact), frozenset(wild), frozenset(exc)


def is_ip(host: str | None) -> bool:
    if not host:
        return False
    h = host.strip("[]")
    try:
        ipaddress.ip_address(h)
        return True
    except ValueError:
        pass
    # Legacy numeric forms (http://3232235521/, 0x7f.1) are IPs to browsers.
    return bool(re.fullmatch(r"(0x[0-9a-f]+|\d+)(\.(0x[0-9a-f]+|\d+)){0,3}", h))


def public_suffix(host: str) -> str:
    labels = host.split(".")
    exact, wild, exc = _rules()
    n = len(labels)
    for i in range(n):  # i = index of the candidate suffix's first label; longest first
        cand = ".".join(labels[i:])
        if cand in exc:  # exception rule: suffix is the rule minus its leftmost label
            return ".".join(labels[i + 1:])
        if cand in exact:
            return cand
        if i + 1 < n and ".".join(labels[i + 1:]) in wild:
            return cand
    return labels[-1]  # default rule "*"


def registrable_domain(host: str | None) -> str | None:
    if not host:
        return None
    host = host_to_ascii(host)
    if not host or is_ip(host) or "." not in host:
        return None
    labels = host.split(".")
    if any(not lbl for lbl in labels):
        return None
    suffix = public_suffix(host)
    s_len = len(suffix.split("."))
    if len(labels) <= s_len:
        return None
    return ".".join(labels[-(s_len + 1):])


def normalize_host(url: str | None) -> str | None:
    """URL (or schemeless 'host/path') -> normalized ASCII host, None if not an http(s) URL."""
    if not url:
        return None
    s = url.strip()
    if not s:
        return None
    if not _SCHEME_RE.match(s) or re.match(r"^[^/:]+\.[^/:]+:\d+", s):
        if " " in s or "." not in s:
            return None
        s = "https://" + s
    try:
        parts = urlsplit(s)
    except ValueError:
        return None
    if parts.scheme.lower() not in ("http", "https"):
        return None
    netloc = parts.netloc.rsplit("@", 1)[-1]
    if netloc.startswith("["):
        end = netloc.find("]")
        return netloc[: end + 1].lower() if end != -1 else None
    host = netloc.rsplit(":", 1)[0] if ":" in netloc else netloc
    host = host_to_ascii(host)
    return host or None


def registrable_domain_of_url(url: str | None) -> str | None:
    return registrable_domain(normalize_host(url))


# ---------------------------------------------------------------- punycode / homoglyph

def _confusables() -> dict:
    from . import contracts

    return contracts.gate_lists()["confusables"]["map"]


def is_punycode(host: str | None) -> bool:
    return bool(host) and any(lbl.startswith("xn--") for lbl in host.split("."))


def is_homoglyph(host: str | None, confusables: dict | None = None) -> bool:
    """gate_lists.json confusables.doc: a non-ASCII label that maps to pure [a-z0-9-],
    or a label mixing Latin [a-z] with Cyrillic/Greek letters."""
    if not host:
        return False
    cmap = confusables if confusables is not None else _confusables()
    for lbl in host.split("."):
        u = label_to_unicode(lbl)
        if u.isascii():
            continue
        mapped = "".join(cmap.get(ch, ch) for ch in u)
        if re.fullmatch(r"[a-z0-9\-]+", mapped):
            return True
        has_latin = any("a" <= ch <= "z" for ch in u)
        has_cg = any(0x0400 <= ord(ch) <= 0x04FF or 0x0370 <= ord(ch) <= 0x03FF for ch in u)
        if has_latin and has_cg:
            return True
    return False
