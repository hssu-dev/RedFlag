"""M3 verification: screenshot every demo page in a mobile viewport and run contracts/extract_page.js.
Runs INSIDE mcr.microsoft.com/playwright/python (see tools/m3_shots.sh). The cloaking page is
captured twice: webdriver=true (stock Playwright, like the Cloud sandbox) and webdriver=false
(AutomationControlled disabled, like the phone's WebView)."""
import json, sys
from playwright.sync_api import sync_playwright

BASE = sys.argv[1]
OUT = "/out"
JS = open("/contracts/extract_page.js").read()
PAGES = ["dbs", "m365", "shop", "parcel", "bakery", "cj", "pilates", "gift"]
UA = ("Mozilla/5.0 (Linux; Android 16; SM-S948B) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/141.0.0.0 Mobile Safari/537.36")
summary = {}
with sync_playwright() as p:
    for mode, args in (("webdriver", []), ("human", ["--disable-blink-features=AutomationControlled"])):
        browser = p.chromium.launch(args=args)
        ctx = browser.new_context(viewport={"width": 412, "height": 915}, device_scale_factor=2,
                                  is_mobile=True, has_touch=True, user_agent=UA, locale="en-SG")
        for name in PAGES if mode == "webdriver" else ["parcel", "m365"]:
            page = ctx.new_page()
            page.goto(f"{BASE}/{name}/", wait_until="load")
            page.wait_for_timeout(400)
            page.screenshot(path=f"{OUT}/{name}-{mode}.png", full_page=False)
            obs = json.loads(page.evaluate(JS))
            obs["webdriver"] = page.evaluate("navigator.webdriver")
            summary[f"{name}-{mode}"] = obs
            page.close()
        browser.close()
json.dump(summary, open(f"{OUT}/extract_summary.json", "w"), ensure_ascii=False, indent=1)
for k, v in summary.items():
    print(f"{k:18} webdriver={v['webdriver']!s:5} title={v['title'][:40]!r:44} forms={[ (f['action'], [x['type']+':'+x['label'] for x in f['fields']]) for f in v['forms']]} hidden={v['hidden_text'][:90]!r}")
