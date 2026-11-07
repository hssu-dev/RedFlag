"""PSL (private section included) + host normalization against contracts/url_vectors.json."""
import pytest

from conftest import load
from redflag_backend import psl

VECTORS = load("url_vectors.json")["vectors"]
WITH_HOST = [v for v in VECTORS if "host" in v["expect"]]


@pytest.mark.parametrize("vec", WITH_HOST, ids=[v["id"] for v in WITH_HOST])
def test_url_vector_host_and_registrable_domain(vec):
    e = vec["expect"]
    host = psl.normalize_host(vec["url"])
    assert host == e["host"]
    if "registrable_domain" in e:
        assert psl.registrable_domain(e["host"]) == e["registrable_domain"]
        assert psl.registrable_domain_of_url(vec["url"]) == e["registrable_domain"]
    if "punycode" in e:
        assert psl.is_punycode(host) == e["punycode"]
    if "homoglyph" in e:
        assert psl.is_homoglyph(host) == e["homoglyph"]


def test_vector_coverage():
    assert len(WITH_HOST) >= 50


@pytest.mark.parametrize("host,expected", [
    ("a.b.example.ck", "b.example.ck"),       # *.ck wildcard: example.ck is a suffix
    ("www.ck", "www.ck"),                     # !www.ck exception
    ("x.city.kawasaki.jp", "city.kawasaki.jp"),
    ("a.b.kawasaki.jp", "a.b.kawasaki.jp"),
    ("foo.bar.github.io", "bar.github.io"),   # private section
    ("x.y.s3.amazonaws.com", "y.s3.amazonaws.com"),
    ("example.co.uk", "example.co.uk"),
    ("co.uk", None),
    ("localhost", None),
    ("127.0.0.1", None),
    ("[::1]", None),
    ("shop.テスト.jp", "xn--zckzah.jp"),
])
def test_psl_rules(host, expected):
    assert psl.registrable_domain(host) == expected


@pytest.mark.parametrize("url", ["chrome://newtab/", "about:blank", "javascript:alert(1)", "", None, "how to cook rice"])
def test_non_http_urls_have_no_host(url):
    assert psl.normalize_host(url) is None
