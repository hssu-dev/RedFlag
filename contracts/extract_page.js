/*
 * RedFlag page extractor — SHARED CONTRACT.
 * Run as-is by the Android OffscreenExtractor (WebView.evaluateJavascript) and by the
 * backend sandbox (Playwright page.evaluate). Same script => Lite and Cloud observations
 * are comparable (cloaking detection depends on that).
 *
 * It is a single expression that evaluates to a JSON *string* (WebView returns strings
 * reliably). It never clicks, submits, or navigates.
 *
 * Output:
 * { final_url, title, lang, visible_text, hidden_text,
 *   forms: [{ action, method, fields: [{type, label, name, autocomplete}] }],
 *   link_urls: [absolute http(s) hrefs, max 200], script_urls: [absolute src, max 100],
 *   data_endpoints: [{ url, via: form|script|fetch|xhr|beacon }, max 40],
 *   meta: { description, og_site_name }, counts: { forms, inputs, iframes } }
 * data_endpoints (v4): where page data can go. Form actions (read now, so a JS-changed action counts),
 * URL literals in inline scripts that use a submit API, and fetch/XHR/beacon requests made while loading
 * (Resource Timing). Only scheme://host/path is kept (query/fragment dropped: they can carry tokens).
 * Registrable domains / same_site are computed by the caller with the PSL, not here.
 */
(function () {
  var MAX_VISIBLE = 4000, MAX_HIDDEN = 1500, MAX_FIELD_LABEL = 80;

  function clean(s) { return (s || "").replace(/\s+/g, " ").trim(); }

  function isHidden(el) {
    for (var e = el; e && e.nodeType === 1; e = e.parentElement) {
      var cs = window.getComputedStyle(e);
      if (cs.display === "none" || cs.visibility === "hidden" || cs.visibility === "collapse") return true;
      if (parseFloat(cs.opacity) === 0) return true;
      if (cs.clipPath && cs.clipPath.indexOf("inset(50%") === 0) return true;
      if (cs.clip && cs.clip.replace(/\s/g, "") === "rect(0px,0px,0px,0px)") return true;
      if (e.hasAttribute("hidden")) return true;
    }
    var cs0 = window.getComputedStyle(el);
    if (parseFloat(cs0.fontSize) < 2) return true;
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return true;
    var docW = Math.max(document.documentElement.scrollWidth, window.innerWidth);
    var docH = Math.max(document.documentElement.scrollHeight, window.innerHeight);
    var sx = window.scrollX, sy = window.scrollY;
    if (r.right + sx < 0 || r.bottom + sy < 0 || r.left + sx > docW || r.top + sy > docH) return true;
    if (cs0.color && cs0.color === cs0.backgroundColor && cs0.color !== "rgba(0, 0, 0, 0)") return true;
    return false;
  }

  var visible = [], hidden = [];
  var skip = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, SVG: 1, HEAD: 1, TITLE: 1 };
  var walker = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_COMMENT, null);
  var node, vLen = 0, hLen = 0;
  while ((node = walker.nextNode())) {
    var text = clean(node.nodeValue);
    if (!text) continue;
    if (node.nodeType === 8) { // comments: invisible to humans, readable by AI scanners
      if (text.length >= 20 && hLen < MAX_HIDDEN) { hidden.push(text); hLen += text.length + 1; }
      continue;
    }
    var parent = node.parentElement;
    if (!parent || skip[parent.tagName.toUpperCase()]) continue;
    if (isHidden(parent)) {
      if (hLen < MAX_HIDDEN) { hidden.push(text); hLen += text.length + 1; }
    } else if (vLen < MAX_VISIBLE) {
      visible.push(text); vLen += text.length + 1;
    }
  }
  // Input placeholders/button values are visible text a human reads.
  Array.prototype.forEach.call(document.querySelectorAll("input[type=submit],button"), function (b) {
    var t = clean(b.value || b.innerText);
    if (t && vLen < MAX_VISIBLE && !isHidden(b) && visible.indexOf(t) === -1) { visible.push(t); vLen += t.length + 1; }
  });

  function labelFor(input) {
    var t = "";
    if (input.id) {
      var l = document.querySelector('label[for="' + (window.CSS && CSS.escape ? CSS.escape(input.id) : input.id) + '"]');
      if (l) t = clean(l.innerText);
    }
    if (!t) { var p = input.closest("label"); if (p) t = clean(p.innerText); }
    if (!t) t = clean(input.getAttribute("aria-label"));
    if (!t) t = clean(input.getAttribute("placeholder"));
    if (!t && input.getAttribute("aria-labelledby")) {
      var ref = document.getElementById(input.getAttribute("aria-labelledby"));
      if (ref) t = clean(ref.innerText);
    }
    return t.slice(0, MAX_FIELD_LABEL);
  }

  var forms = [];
  var formEls = Array.prototype.slice.call(document.forms);
  // Inputs outside any <form> that still collect secrets (JS-submitted) form a pseudo-form.
  var orphan = Array.prototype.filter.call(document.querySelectorAll("input,textarea,select"), function (i) { return !i.form; });
  formEls.forEach(function (f) { forms.push({ el: f, inputs: Array.prototype.slice.call(f.elements) }); });
  if (orphan.length) forms.push({ el: null, inputs: orphan });
  var outForms = forms.slice(0, 10).map(function (f) {
    var action = f.el ? (f.el.action || location.href) : location.href;
    var fields = [];
    f.inputs.forEach(function (i) {
      var tag = i.tagName.toLowerCase();
      if (tag === "button" || tag === "fieldset" || tag === "output" || tag === "object") return;
      var type = tag === "input" ? (i.getAttribute("type") || "text").toLowerCase() : tag;
      if (type === "hidden" || type === "submit" || type === "button" || type === "reset" || type === "image") return;
      fields.push({ type: type, label: labelFor(i), name: clean(i.getAttribute("name")).slice(0, 40), autocomplete: clean(i.getAttribute("autocomplete")).slice(0, 40) });
    });
    return { action: action, method: f.el ? (f.el.method || "get").toLowerCase() : "js", fields: fields.slice(0, 20) };
  }).filter(function (f) { return f.fields.length > 0; });

  function uniqAbs(list, max) {
    var seen = {}, out = [];
    for (var k = 0; k < list.length && out.length < max; k++) {
      var u = list[k];
      if (!u || !/^https?:/i.test(u) || seen[u]) continue;
      seen[u] = 1; out.push(u);
    }
    return out;
  }
  var links = uniqAbs(Array.prototype.map.call(document.querySelectorAll("a[href]"), function (a) { return a.href; }), 200);
  var scripts = uniqAbs(Array.prototype.map.call(document.querySelectorAll("script[src]"), function (s) { return s.src; }), 100);
  var MAX_ENDPOINTS = 40, MAX_SCRIPT_SCAN = 60000;
  var endpoints = [], seenEp = {};
  function addEp(u, via) {
    if (endpoints.length >= MAX_ENDPOINTS || !u) return;
    var abs;
    try { abs = new URL(u, location.href); } catch (e) { return; }
    if (abs.protocol !== "http:" && abs.protocol !== "https:") return;
    var s = (abs.protocol + "//" + abs.host + abs.pathname).slice(0, 160);
    if (seenEp[s]) return;
    seenEp[s] = 1;
    endpoints.push({ url: s, via: via });
  }
  outForms.forEach(function (f) { if (f.method !== "js") addEp(f.action, "form"); });
  var SUBMIT_API = /fetch\s*\(|XMLHttpRequest|sendBeacon|\$\.(post|ajax)|axios|\.submit\s*\(|\.action\s*=|new\s+WebSocket/;
  var URL_LIT = /https?:\/\/[^\s"'`<>\\)]+/g;
  var scanned = 0;
  Array.prototype.forEach.call(document.querySelectorAll("script:not([src])"), function (sc) {
    if (scanned >= MAX_SCRIPT_SCAN) return;
    var t = (sc.textContent || "").slice(0, MAX_SCRIPT_SCAN - scanned);
    scanned += t.length;
    if (!SUBMIT_API.test(t)) return;
    var m;
    URL_LIT.lastIndex = 0;
    while ((m = URL_LIT.exec(t)) && endpoints.length < MAX_ENDPOINTS) addEp(m[0], "script");
  });
  try {
    Array.prototype.forEach.call(performance.getEntriesByType("resource") || [], function (e) {
      var t = e.initiatorType;
      if (t === "fetch" || t === "xmlhttprequest" || t === "beacon") addEp(e.name, t === "xmlhttprequest" ? "xhr" : t);
    });
  } catch (e) { /* Resource Timing unavailable */ }

  function meta(sel) { var m = document.querySelector(sel); return m ? clean(m.getAttribute("content")).slice(0, 200) : null; }

  return JSON.stringify({
    final_url: location.href,
    title: clean(document.title).slice(0, 200),
    lang: (document.documentElement.getAttribute("lang") || "").slice(0, 10),
    visible_text: visible.join(" ").slice(0, MAX_VISIBLE),
    hidden_text: hidden.join(" ").slice(0, MAX_HIDDEN),
    forms: outForms,
    link_urls: links,
    script_urls: scripts,
    data_endpoints: endpoints,
    meta: { description: meta('meta[name="description"]'), og_site_name: meta('meta[property="og:site_name"]') },
    counts: { forms: document.forms.length, inputs: document.querySelectorAll("input,textarea,select").length, iframes: document.querySelectorAll("iframe").length }
  });
})()
