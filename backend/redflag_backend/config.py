"""All backend settings. Defaults live here; the ONLY way to override them is env vars.

Read through get_settings() at call time (not import time) so tests can monkeypatch env.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

_BACKEND_DIR = Path(__file__).resolve().parents[1]


def _env(name: str, default: str) -> str:
    v = os.environ.get(name)
    return default if v is None or v == "" else v


def _int(name: str, default: int) -> int:
    return int(_env(name, str(default)))


def _float(name: str, default: float) -> float:
    return float(_env(name, str(default)))


def _bool(name: str, default: bool) -> bool:
    return _env(name, "1" if default else "0").strip().lower() in ("1", "true", "yes", "on")


@dataclass(frozen=True)
class Settings:
    anthropic_api_key: str | None = field(repr=False)
    model: str
    effort: str
    max_tokens: int
    use_fallbacks: bool
    web_search_max_uses: int
    max_rounds: int
    wall_clock_s: float
    daily_opus_cap: int
    sandbox_url: str
    sandbox_timeout_s: float
    demo_mode: bool
    demo_base: str
    cache_path: Path
    cache_ttl_s: int
    safe_browsing_mode: str
    safe_browsing_api_key: str | None = field(repr=False)
    safe_browsing_android_package: str | None
    safe_browsing_android_cert: str | None
    rdap_timeout_s: float
    cert_timeout_s: float
    max_extra_browses: int


def get_settings() -> Settings:
    sb_mode = (os.environ.get("SAFE_BROWSING_MODE") or "auto").lower()
    if sb_mode not in ("auto", "stub", "live"):
        raise ValueError(f"SAFE_BROWSING_MODE must be auto|stub|live, got {sb_mode!r}")
    return Settings(
        anthropic_api_key=os.environ.get("ANTHROPIC_API_KEY") or None,
        model=_env("REDFLAG_MODEL", "claude-opus-5-5"),
        effort=_env("REDFLAG_EFFORT", "medium"),
        max_tokens=_int("REDFLAG_MAX_TOKENS", 8000),
        use_fallbacks=_bool("REDFLAG_FALLBACKS", True),
        web_search_max_uses=_int("REDFLAG_WEB_SEARCH_MAX_USES", 3),
        max_rounds=_int("REDFLAG_MAX_ROUNDS", 6),
        wall_clock_s=_float("REDFLAG_WALL_CLOCK_S", 45.0),
        daily_opus_cap=_int("REDFLAG_DAILY_OPUS_CAP", 100),
        sandbox_url=_env("SANDBOX_URL", "http://localhost:8090"),
        sandbox_timeout_s=_float("SANDBOX_TIMEOUT_S", 25.0),
        # DEMO_MODE is opt-in and only the exact value "1" enables it.
        demo_mode=os.environ.get("DEMO_MODE") == "1",
        demo_base=_env("DEMO_BASE", "docker"),  # key of demo_hosts.json base_urls
        cache_path=Path(_env("CACHE_PATH", str(_BACKEND_DIR / "data" / "redflag.sqlite3"))),
        cache_ttl_s=_int("REDFLAG_CACHE_TTL_S", 6 * 3600),
        safe_browsing_mode=sb_mode,
        safe_browsing_api_key=os.environ.get("SAFE_BROWSING_API_KEY") or os.environ.get("GOOGLE_CLOUD_API_KEY") or None,
        # An API key restricted to the Android app is accepted when these headers match (same key as the phone).
        safe_browsing_android_package=os.environ.get("SAFE_BROWSING_ANDROID_PACKAGE", "dev.redflag.app") or None,
        safe_browsing_android_cert=os.environ.get("SAFE_BROWSING_ANDROID_CERT", "AA64D8EE3EACA2FFFCCBE05579FADCA47F0272C0") or None,
        rdap_timeout_s=_float("REDFLAG_RDAP_TIMEOUT_S", 6.0),
        cert_timeout_s=_float("REDFLAG_CERT_TIMEOUT_S", 8.0),
        max_extra_browses=_int("REDFLAG_MAX_EXTRA_BROWSES", 2),
    )
