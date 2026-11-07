import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts"
sys.path.insert(0, str(ROOT / "backend"))


def load(name):
    return json.loads((CONTRACTS / name).read_text(encoding="utf-8"))


# ---------------------------------------------------------------- M4 backend fixtures
import os
import tempfile

# Never touch backend/data or a real key from tests (app module creates a Store on import).
os.environ["CACHE_PATH"] = str(Path(tempfile.mkdtemp(prefix="redflag-test-")) / "import.sqlite3")
os.environ.pop("ANTHROPIC_API_KEY", None)
os.environ.pop("DEMO_MODE", None)

import pytest  # noqa: E402


@pytest.fixture
def env(monkeypatch, tmp_path):
    """Clean, fast settings for backend tests."""
    for k in ("DEMO_MODE", "ANTHROPIC_API_KEY", "SAFE_BROWSING_MODE", "SAFE_BROWSING_API_KEY", "GOOGLE_CLOUD_API_KEY",
              "REDFLAG_MAX_ROUNDS", "REDFLAG_WALL_CLOCK_S"):
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("CACHE_PATH", str(tmp_path / "t.sqlite3"))
    monkeypatch.setenv("SANDBOX_URL", "http://sandbox.invalid:1")
    return monkeypatch


@pytest.fixture
def store(env, tmp_path):
    from redflag_backend.store import Store

    return Store(tmp_path / "store.sqlite3", ttl_s=6 * 3600)
