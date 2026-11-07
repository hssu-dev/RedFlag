"""M1 acceptance: schemas are valid 2020-12 schemas and fixtures/vectors conform."""
import json

import pytest
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

from conftest import CONTRACTS, load

SCHEMAS = ["risk_report.schema.json", "lite_features.schema.json", "cloud_features.schema.json"]


def registry():
    reg = Registry()
    for name in SCHEMAS:
        s = load(name)
        res = Resource.from_contents(s)
        reg = reg.with_resource(s["$id"], res).with_resource(name, res)
    return reg


def validator(name):
    return Draft202012Validator(load(name), registry=registry(), format_checker=FormatChecker())


@pytest.mark.parametrize("name", SCHEMAS)
def test_schema_is_valid(name):
    s = load(name)
    assert s["$schema"] == "https://json-schema.org/draft/2020-12/schema"
    Draft202012Validator.check_schema(s)


def test_all_json_contracts_parse():
    for p in CONTRACTS.rglob("*.json"):
        json.loads(p.read_text(encoding="utf-8"))


@pytest.mark.parametrize("level", ["safe", "caution", "danger", "critical"])
def test_fixture_matches_schema_and_level(level):
    doc = load(f"fixtures/{level}.json")
    errors = sorted(validator("risk_report.schema.json").iter_errors(doc), key=str)
    assert not errors, [e.message for e in errors]
    assert doc["level"] == level
    feats_schema = "cloud_features.schema.json" if doc["mode"] == "cloud" else "lite_features.schema.json"
    assert not list(validator(feats_schema).iter_errors(doc["features"]))


def test_fixture_rejects_bad_level():
    doc = load("fixtures/safe.json")
    doc["level"] = "fine"
    assert list(validator("risk_report.schema.json").iter_errors(doc))


def test_lite_features_schema_matches_run_eval_keys():
    """lite_features.schema.json must mirror redflag-gemma-test/run_eval.py exactly."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("run_eval", CONTRACTS.parent / "redflag-gemma-test" / "run_eval.py")
    run_eval = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(run_eval)
    s = load("lite_features.schema.json")
    assert list(s["properties"]) == run_eval.KEYS
    assert s["required"] == run_eval.KEYS
    for k, allowed in run_eval.ENUMS.items():
        assert s["properties"][k]["enum"] == allowed
    assert s["properties"]["requested_actions"]["items"]["enum"] == run_eval.ACTIONS
    cloud = load("cloud_features.schema.json")
    assert list(cloud["properties"])[:10] == run_eval.KEYS


def test_scoring_vector_features_are_valid_lite_features():
    v = validator("lite_features.schema.json")
    for vec in load("scoring_vectors.json")["vectors"]:
        f = vec["input"]["features"]
        if f is not None:
            assert not list(v.iter_errors(f)), vec["id"]


def test_vector_minimums():
    assert len(load("url_vectors.json")["vectors"]) >= 40
    assert len(load("scoring_vectors.json")["vectors"]) >= 20
    assert len(load("brand_domains.json")["brands"]) >= 25
