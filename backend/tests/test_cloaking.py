"""Backend cloaking comparison: device (lite_observation) vs sandbox (+ Opus features)."""
from redflag_backend.cloaking import compare


def obs(title="", text="", forms=None, ok=True):
    return {"ok": ok, "title": title, "visible_text": text, "forms": forms or []}


PW_FORM = {"action_domain": "x.top", "same_site": False, "fields": [{"type": "password", "label": "Password"}]}
CARD_FORM = {"action_domain": "x.top", "same_site": True, "fields": [{"type": "text", "label": "Card number"}]}
NEWS_FORM = {"action_domain": "x.top", "same_site": True, "fields": [{"type": "email", "label": "Newsletter"}]}
LITE = {"claimed_brand": "SingPost", "has_credential_form": True, "title": "SingPost | Redelivery"}


def test_harmless_sandbox_page_is_cloaking():
    r = compare(LITE, obs("Delivery tips blog", "Five tips for parcels", [NEWS_FORM]), {"claimed_brand": None})
    assert r["suspected"] is True
    assert "form" in r["detail"] and "SingPost" in r["detail"] and "titles differ" in r["detail"]


def test_same_page_is_not_cloaking():
    r = compare(LITE, obs("SingPost | Redelivery", "SingPost parcel on hold", [CARD_FORM]), {"claimed_brand": "SingPost"})
    assert r == {"suspected": False, "detail": None}


def test_brand_differs_after_normalize():
    r = compare({"claimed_brand": "DBS", "has_credential_form": False, "title": "x"},
                obs("Login", "Welcome", [PW_FORM]), {"claimed_brand": "Microsoft"})
    assert r["suspected"] is True and "Microsoft" in r["detail"]


def test_brand_alias_and_case_are_same_brand():
    r = compare({"claimed_brand": "dbs bank", "has_credential_form": True, "title": "DBS"},
                obs("DBS iBanking", "Log in", [PW_FORM]), {"claimed_brand": "DBS"})
    assert r["suspected"] is False
    r2 = compare({"claimed_brand": "POSB", "has_credential_form": True, "title": None},
                 obs("x", "y", [PW_FORM]), {"claimed_brand": "DBS digibank"})  # same brand_domains entry
    assert r2["suspected"] is False


def test_credential_form_only_on_device():
    r = compare({"claimed_brand": None, "has_credential_form": True, "title": None}, obs("A", "B", [NEWS_FORM]), None)
    assert r["suspected"] is True and r["detail"].startswith("The device saw")


def test_sandbox_has_form_device_did_not_is_not_cloaking():
    r = compare({"claimed_brand": None, "has_credential_form": False, "title": None}, obs("A", "B", [PW_FORM]), None)
    assert r["suspected"] is False


def test_title_difference_alone_is_not_enough():
    r = compare({"claimed_brand": None, "has_credential_form": False, "title": "Totally different"},
                obs("Something else", "text"), None)
    assert r["suspected"] is False


def test_brand_visible_in_text_when_opus_has_no_brand():
    r = compare({"claimed_brand": "SingPost", "has_credential_form": False, "title": None},
                obs("Track", "Track your SingPost parcel"), None)
    assert r["suspected"] is False


def test_no_lite_observation_or_failed_sandbox():
    assert compare(None, obs(), None) == {"suspected": False, "detail": None}
    r = compare(LITE, obs(ok=False), None)
    assert r["suspected"] is False and "could not load" in r["detail"]
