"""Python rule engine must pass contracts/scoring_vectors.json (same file as android/core)."""
import pytest

from conftest import load
from redflag_backend import scoring

VECTORS = load("scoring_vectors.json")["vectors"]


@pytest.mark.parametrize("vec", VECTORS, ids=[v["id"] for v in VECTORS])
def test_vector(vec):
    r = scoring.score(vec["input"])
    e = vec["expect"]
    assert r.score == e["score"]
    assert r.level == e["level"]
    assert r.escalate == e["escalate"]
    assert r.fired == e["fired"]
    assert r.brand["matches"] == e["brand_matches"]
    if "headline_en" in e:
        assert r.headline["en"] == e["headline_en"]
    for fl in e.get("flags", []):
        got = next(f for f in r.red_flags if f.rule_id == fl["rule_id"])
        for key, value in (("source", got.source), ("severity", got.severity), ("evidence", got.evidence),
                           ("title_en", got.title["en"]), ("title_ko", got.title["ko"])):
            if key in fl:
                assert value == fl[key], key


def test_appendix_b_case_requirement():
    """Appendix B: the 6 malicious cases in cases.jsonl score danger+, the 4 benign score safe."""
    import json
    from conftest import ROOT
    labels = {json.loads(l)["id"]: json.loads(l)["label"]
              for l in (ROOT / "redflag-gemma-test" / "cases.jsonl").read_text().splitlines() if l.strip()}
    seen = 0
    for vec in VECTORS:
        cid = vec["id"].removeprefix("case_")
        if cid in labels:
            seen += 1
            lvl = scoring.score(vec["input"]).level
            if labels[cid] == "malicious":
                assert lvl in ("danger", "critical"), cid
            else:
                assert lvl == "safe", cid
    assert seen == 10


def test_unknown_expression_raises():
    with pytest.raises(ValueError):
        scoring.evaluate({"bogus": 1}, {})
