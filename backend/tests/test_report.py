"""RiskReport assembly is validated against contracts/risk_report.schema.json before sending."""
import pytest

from conftest import load
from redflag_backend import report


def signals():
    return report.build_signals("https://xn--pple-43d.com/login", None,
                                {"domain_age_days": 2}, {"tls_issuer": None, "cert_age_days": None}, None)


def test_signals_without_observation_are_schema_shaped():
    s = signals()
    assert s["safe_browsing"] == "unknown" and s["punycode"] is True and s["homoglyph"] is True
    assert s["forms"] == [] and s["redirect_count"] == 0


def test_assemble_validates_and_scores_homoglyph():
    rep, res = report.assemble(url="https://xn--pple-43d.com/login", final_url="https://xn--pple-43d.com/login",
                               status="timeout", signals=signals(), features=None,
                               cloaking={"suspected": False, "detail": None},
                               trace=[{"t_ms": 0, "step": "start", "detail": "x"}], model="claude-opus-5-5",
                               latency_ms=12, registrable_domain="xn--pple-43d.com")
    # D3: status timeout -> analysis_incomplete clamp (never safe); 45 is already >= caution
    assert res.fired == ["new_domain", "punycode_or_homoglyph", "analysis_incomplete"] and rep["score"] == 45


def test_tls_falls_back_to_sandbox_certificate():
    obs = {"ok": True, "final_url": "https://a.top/", "forms": [], "tls": {"issuer": "R11", "valid_from": 0}}
    s = report.build_signals("https://a.top/", obs, None, {"tls_issuer": None, "cert_age_days": None}, None)
    assert s["tls_issuer"] == "R11" and isinstance(s["cert_age_days"], int)


@pytest.mark.parametrize("level", ["safe", "caution", "danger", "critical"])
def test_fixtures_pass_validator(level):
    report.validate_report(load(f"fixtures/{level}.json"))


@pytest.mark.parametrize("mutate", [
    lambda r: r.pop("cloaking"),
    lambda r: r.update(score=101),
    lambda r: r.update(status="done"),
    lambda r: r["signals"].update(safe_browsing="maybe"),
    lambda r: r.update(features={"claimed_brand": "x"}),
])
def test_invalid_reports_are_rejected(mutate):
    r = load("fixtures/critical.json")
    mutate(r)
    with pytest.raises(report.ReportInvalid):
        report.validate_report(r)


def test_incomplete_cloud_analysis_is_never_safe():
    """D3 (backend): a timeout/error report with no features and no risky signals is caution, not safe."""
    s = signals()
    s.update(domain_age_days=4000, punycode=False, homoglyph=False)
    for status in ("timeout", "error"):
        rep, res = report.assemble(url="https://quiet.example/", final_url="https://quiet.example/", status=status,
                                   signals=s, features=None, cloaking={"suspected": False, "detail": None},
                                   trace=[{"t_ms": 0, "step": "start", "detail": "x"}], model="claude-opus-5-5",
                                   latency_ms=5, registrable_domain="quiet.example")
        assert rep["level"] != "safe" and rep["score"] == 30 and res.fired == ["analysis_incomplete"], status
