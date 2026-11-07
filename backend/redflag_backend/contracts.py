"""Loads the shared contract files (single source of truth: repo-root contracts/)."""
from __future__ import annotations

import json
import os
from functools import lru_cache
from pathlib import Path


def contracts_dir() -> Path:
    env = os.environ.get("REDFLAG_CONTRACTS_DIR")
    if env:
        return Path(env)
    here = Path(__file__).resolve()
    for parent in here.parents:  # works from the repo checkout and from /app in Docker
        cand = parent / "contracts"
        if (cand / "scoring_rules.json").exists():
            return cand
    raise FileNotFoundError("contracts/ not found; set REDFLAG_CONTRACTS_DIR")


@lru_cache(maxsize=None)
def load(name: str):
    return json.loads((contracts_dir() / name).read_text(encoding="utf-8"))


def scoring_rules() -> dict:
    return load("scoring_rules.json")


def brand_domains() -> dict:
    return load("brand_domains.json")


def gate_lists() -> dict:
    return load("gate_lists.json")
