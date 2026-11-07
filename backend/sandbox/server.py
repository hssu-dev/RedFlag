"""RedFlag sandbox browser: POST /browse {url} -> observation JSON.

Runs ONLY inside its Docker container (Playwright official image, non-root pwuser).
* Chromium with a MOBILE viewport + mobile Chrome-on-Android UA (same conditions as the
  phone so the Lite-vs-Cloud cloaking comparison is fair). navigator.webdriver is NOT
  patched: Playwright exposes webdriver=true, which is exactly what cloaking kits look for.
* Follows redirects and records the chain (HTTP redirects + later main-frame navigations).
* Never clicks, types or submits. Downloads are refused and recorded.
* Requests to private/loopback/link-local addresses are blocked (SSRF guard), except hosts
  listed in SANDBOX_ALLOW_HOSTS (compose: the local demo-pages server).
* Evaluates contracts/extract_page.js VERBATIM (mounted read-only at /contracts).

Stdlib HTTP server + Playwright sync API, one fresh browser per request.
"""
from __future__ import annotations

import base64
import ipaddress
import json
import os
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import Error as PWError
from playwright.sync_api import sync_playwright

import psl  # copied from backend/redflag_backend/psl.py (stdlib only)

CONTRACTS = Path(os.environ.get("REDFLAG_CONTRACTS_DIR", "/contracts"))
EXTRACT_JS = (CONTRACTS / "extract_page.js").read_text(encoding="utf-8")
PORT = int(os.environ.get("SANDBOX_PORT", "8090"))
ALLOW_HOSTS = {h.strip().lower() for h in os.environ.get("SANDBOX_ALLOW_HOSTS", "").split(",") if h.strip()}
MAX_CONCURRENT = int(os.environ.get("SANDBOX_MAX_CONCURRENT", "2"))
DEFAULT_TIMEOUT_MS = int(os.environ.get("SANDBOX_TIMEOUT_MS", "20000"))
SCREENSHOT_MAX_W = 768

# Galaxy S-class phone in CSS pixels; screenshot is taken at CSS scale (412 px wide <= 768).
VIEWPORT = {"width": 412, "height": 915}
DEVICE_SCALE = 3
UA_TEMPLATE = ("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
               "Chrome/{major}.0.0.0 Mobile Safari/537.36")

_slots = threading.BoundedSemaphore(MAX_CONCURRENT)
_dns_cache: dict[str, bool] = {}


def _log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


def host_allowed(host: str | None) -> bool:
    if not host:
        return False
    host = host.strip("[]").lower()
    if host in ALLOW_HOSTS:
        return True
    if host in _dns_cache:
        return _dns_cache[host]
    ok = True
    try:
        addrs = {ai[4][0] for ai in socket.getaddrinfo(host, None)}
    except OSError:
        addrs = set()  # unresolvable: let Chromium fail naturally
    for a in addrs:
        ip = ipaddress.ip_address(a.split("%")[0])
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved or ip.is_multicast \
                or ip.is_unspecified:
            ok = False
    _dns_cache[host] = ok
    return ok


def _domains(urls: list[str], own: str | None, limit: int) -> list[str]:
    out: list[str] = []
    for u in urls:
        d = psl.registrable_domain_of_url(u)
        if d and d != own and d not in out:
            out.append(d)
        if len(out) >= limit:
            break
    return out


def browse(url: str, timeout_ms: int) -> dict:
    t0 = time.monotonic()
    host = urlsplit(url).hostname
    if urlsplit(url).scheme not in ("http", "https"):
        return {"ok": False, "error": "unsupported scheme"}
    if not host_allowed(host):
        return {"ok": False, "error": "blocked: private or loopback address"}

    downloads: list[str] = []
    navigations: list[str] = []
    blocked: list[str] = []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        try:
            major = browser.version.split(".")[0]
            context = browser.new_context(
                viewport=VIEWPORT, screen=VIEWPORT, device_scale_factor=DEVICE_SCALE,
                is_mobile=True, has_touch=True, user_agent=UA_TEMPLATE.format(major=major),
                locale="en-US", timezone_id="Asia/Singapore", accept_downloads=False,
                ignore_https_errors=True, service_workers="block",
            )
            context.set_default_timeout(min(10000, timeout_ms))

            def guard(route):
                h = urlsplit(route.request.url).hostname
                if route.request.url.startswith(("data:", "blob:")) or host_allowed(h):
                    route.continue_()
                else:
                    blocked.append(route.request.url[:200])
                    route.abort("blockedbyclient")

            context.route("**/*", guard)
            page = context.new_page()
            page.on("download", lambda d: downloads.append(d.suggested_filename or d.url))
            page.on("framenavigated", lambda f: navigations.append(f.url) if f == page.main_frame else None)
            # Never let the page open dialogs that block automation; dismiss = no input given.
            page.on("dialog", lambda d: d.dismiss())

            t_goto = time.monotonic()
            resp, nav_error = None, None
            try:
                resp = page.goto(url, wait_until="load", timeout=min(15000, timeout_ms))
            except PWError as e:
                msg = str(e)
                if "Download is starting" in msg:
                    downloads.append(url)
                else:
                    nav_error = msg.splitlines()[0][:200]
            goto_ms = int((time.monotonic() - t_goto) * 1000)
            try:
                page.wait_for_load_state("networkidle", timeout=2000)
            except PWError:
                pass
            page.wait_for_timeout(700)  # settle: late JS redirects / DOM swaps (cloaking kits)

            raw, t_ex = None, time.monotonic()
            for _ in range(3):
                try:
                    raw = page.evaluate(EXTRACT_JS)
                    break
                except PWError as e:  # navigation mid-evaluate: wait and retry
                    if "Execution context was destroyed" not in str(e) and "navigat" not in str(e).lower():
                        raise
                    try:
                        page.wait_for_load_state("load", timeout=5000)
                    except PWError:
                        pass
            extract_ms = int((time.monotonic() - t_ex) * 1000)
            if raw is None:
                return {"ok": False, "error": nav_error or "extraction failed", "downloads_attempted": bool(downloads)}
            data = json.loads(raw)

            chain: list[str] = []
            if resp is not None:
                r = resp.request
                while r is not None:
                    chain.insert(0, r.url)
                    r = r.redirected_from
            for u in navigations:
                if u not in chain and u != "about:blank":
                    chain.append(u)
            final_url = page.url
            if final_url not in chain:
                chain.append(final_url)

            tls = None
            if resp is not None and final_url.startswith("https:"):
                try:
                    sd = resp.security_details()
                    if sd:
                        tls = {"issuer": sd.get("issuer"), "valid_from": sd.get("validFrom"),
                               "protocol": sd.get("protocol"), "subject": sd.get("subjectName")}
                except PWError:
                    pass

            shot = page.screenshot(type="jpeg", quality=60, scale="css", full_page=False)
            own = psl.registrable_domain_of_url(final_url)
            forms = []
            for f in data.get("forms", []):
                action_domain = psl.registrable_domain_of_url(f.get("action"))
                forms.append({**f, "action_domain": action_domain,
                              "same_site": action_domain is None or action_domain == own})
            return {
                "ok": True,
                "requested_url": url,
                "final_url": final_url,
                "status": resp.status if resp is not None else None,
                "redirect_chain": chain,
                "redirect_count": max(0, len(chain) - 1),
                "title": data.get("title", ""),
                "lang": data.get("lang", ""),
                "visible_text": data.get("visible_text", ""),
                "hidden_text": data.get("hidden_text", ""),
                "forms": forms,
                "link_urls": data.get("link_urls", []),
                "script_urls": data.get("script_urls", []),
                "data_endpoints": data.get("data_endpoints", []),
                "external_link_domains": _domains(data.get("link_urls", []), own, 20),
                "external_script_domains": _domains(data.get("script_urls", []), own, 20),
                "meta": data.get("meta", {}),
                "counts": data.get("counts", {}),
                "tls": tls,
                "screenshot_jpeg_b64": base64.b64encode(shot).decode("ascii"),
                "screenshot_width": VIEWPORT["width"],
                "downloads_attempted": bool(downloads),
                "downloads": downloads[:5],
                "blocked_requests": blocked[:10],
                "navigation_error": nav_error,
                "user_agent": UA_TEMPLATE.format(major=major),
                "viewport": VIEWPORT,
                "timing": {"goto_ms": goto_ms, "extract_ms": extract_ms,
                           "total_ms": int((time.monotonic() - t0) * 1000)},
            }
        finally:
            browser.close()


class Handler(BaseHTTPRequestHandler):
    server_version = "redflag-sandbox/1"

    def _send(self, code: int, obj: dict):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        _log(self.address_string(), fmt % args)

    def do_GET(self):
        if self.path == "/healthz":
            return self._send(200, {"ok": True, "screenshot_max_w": SCREENSHOT_MAX_W})
        self._send(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if self.path != "/browse":
            return self._send(404, {"ok": False, "error": "not found"})
        try:
            n = int(self.headers.get("Content-Length", "0"))
            req = json.loads(self.rfile.read(min(n, 65536)) or b"{}")
            url = str(req["url"])
            timeout_ms = max(3000, min(int(req.get("timeout_ms", DEFAULT_TIMEOUT_MS)), 45000))
        except Exception:
            return self._send(400, {"ok": False, "error": "body must be JSON {url, timeout_ms?}"})
        if not _slots.acquire(timeout=30):
            return self._send(503, {"ok": False, "error": "sandbox busy"})
        try:
            _log("browse", url[:200])
            result = browse(url, timeout_ms)
        except Exception as e:  # never leak a stack trace to the caller
            _log("error", type(e).__name__, str(e)[:300])
            result = {"ok": False, "error": f"{type(e).__name__}: {str(e).splitlines()[0][:200]}"}
        finally:
            _slots.release()
        self._send(200, result)


if __name__ == "__main__":
    _log(f"sandbox listening on :{PORT}, allow_hosts={sorted(ALLOW_HOSTS)}")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
