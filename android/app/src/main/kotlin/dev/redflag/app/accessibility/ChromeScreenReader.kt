package dev.redflag.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.redflag.app.lite.Extraction
import dev.redflag.app.lite.PageExtractor
import dev.redflag.app.lite.PageState
import dev.redflag.app.lite.PageStates
import dev.redflag.core.model.ObservedField
import dev.redflag.core.model.ObservedForm
import dev.redflag.core.model.PageCounts
import dev.redflag.core.model.PageObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Reads the page Chrome is showing (under RedFlag's cover) through the accessibility tree: exactly what the
 * user sees, so CDN checks, blocks and scanner-only cloaking do not matter (user decision 2026-11-04, ③).
 * Used when the hidden WebView could not get the site's own page. Visible text and input fields only: hidden
 * text and where a form sends its data are not visible here (those rules simply do not fire).
 */
class ChromeScreenReader(private val service: AccessibilityService) : PageExtractor {
    /** element ids seen by the last [read] (Chrome's own pages have fixed ones, e.g. "details-button") */
    private var lastIds: Set<String> = emptySet()

    override suspend fun extract(url: String, host: ViewGroup?, timeoutMs: Long, shotName: String?, challengeWaitMs: Long): Extraction {
        val t0 = System.currentTimeMillis()
        var last: PageObservation? = null
        var lastState: PageState? = null
        // Chrome may still be loading, or itself passing a CDN check: look again until it shows the site
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            val obs = withContext(Dispatchers.Main) { runCatching { read(url) }.getOrNull() }
            if (obs != null) {
                // Chrome's own "This site can't be reached" page is not the site's page: never analyse it as one
                // (seen 2026-11-05: an unresolvable domain read as 118 chars of "page" -> judged safe)
                chromePage(lastIds, obs.visibleText)?.let { err ->
                    Log.i(TAG, "Chrome shows its own page, not the site's: $err (${System.currentTimeMillis() - t0} ms)")
                    return Extraction(null, 0, false, null, err, System.currentTimeMillis() - t0)
                }
                last = obs
                val state = PageStates.code(null, obs.title, obs.visibleText, obs.forms.size)
                lastState = state
                val enough = obs.visibleText.length >= MIN_TEXT || obs.forms.isNotEmpty()
                if (state == PageState.HOST_WARNING || (enough && state == PageState.LOADED)) {
                    Log.i(TAG, "read Chrome screen at ${System.currentTimeMillis() - t0} ms: ${obs.visibleText.length} chars, ${obs.counts.inputs} inputs, state=${state.wire}")
                    return Extraction(obs, 0, false, null, if (state == PageState.HOST_WARNING) "provider_warning:" + PageStates.providerWarning(obs.title, obs.visibleText) else null,
                        System.currentTimeMillis() - t0)
                }
            }
            delay(POLL_MS)
        }
        Log.i(TAG, "Chrome screen not readable as the site's page (state=${lastState?.wire}, ${last?.visibleText?.length ?: 0} chars)")
        val err = when (lastState) {
            PageState.CHALLENGE, PageState.BLOCKED, PageState.UNAVAILABLE -> "page_state:${lastState.wire}"
            else -> "screen_unreadable"
        }
        return Extraction(null, 0, false, null, err, System.currentTimeMillis() - t0)
    }

    /** One pass over Chrome's window: the web content subtree (below the toolbar). */
    private fun read(url: String): PageObservation? {
        val roots = runCatching { service.windows }.getOrDefault(emptyList())
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() == CHROME }
        val web = roots.firstNotNullOfOrNull { findWeb(it) } ?: return null
        val texts = ArrayList<String>()
        val fields = ArrayList<ObservedField>()
        var title = ""
        val ids = HashSet<String>()
        walk(web, 0) { n ->
            n.viewIdResourceName?.let { ids += it }
            val cls = n.className?.toString().orEmpty()
            if (n.isEditable || cls.endsWith("EditText")) {
                val label = listOfNotNull(n.hintText, n.contentDescription, n.paneTitle, n.viewIdResourceName).map { it.toString() }
                    .firstOrNull { it.isNotBlank() }.orEmpty()
                fields += ObservedField(type = if (n.isPassword) "password" else guessType(label), label = label.take(80))
            } else {
                val t = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                if (t.isNotEmpty() && t.length < 500 && texts.lastOrNull() != t) texts += t
                if (title.isEmpty() && n.isHeading && t.isNotEmpty()) title = t.take(120)
            }
        }
        lastIds = ids
        val visible = texts.joinToString(" ").take(MAX_TEXT)
        if (title.isEmpty()) title = (web.text ?: web.contentDescription)?.toString()?.take(120).orEmpty()
        // where a form sends its data is not visible on screen: no destination is claimed (action = the page itself)
        val forms = if (fields.isEmpty()) emptyList() else listOf(ObservedForm(action = url, method = "js", fields = fields.take(20)))
        return PageObservation(finalUrl = url, title = title, visibleText = visible, forms = forms,
            counts = PageCounts(forms = forms.size, inputs = fields.size))
    }

    private fun findWeb(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root, 0) { n -> if (found == null && n.className?.toString() == "android.webkit.WebView") found = n }
        return found
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, visit: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 60) return
        visit(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, visit) }
    }

    private fun guessType(label: String): String {
        val l = label.lowercase()
        return when {
            "mail" in l || "correo" in l || "이메일" in l -> "email"
            "card" in l || "tarjeta" in l || "카드" in l -> "text"
            "phone" in l || "tel" in l || "전화" in l -> "tel"
            else -> "text"
        }
    }

    companion object {
        /**
         * Chrome's network / certificate error interstitial shows its error code ("ERR_NAME_NOT_RESOLVED",
         * "NET::ERR_CERT_DATE_INVALID"). A page that merely prints such a code ends up "could not verify",
         * never safe (D3), so a phishing page gains nothing by imitating it.
         */
        fun chromeError(visibleText: String?): String? = CHROME_ERROR.find(visibleText.orEmpty())?.value

        /**
         * Chrome's own pages shown in place of the site, as an Extraction.error (null = this is the site's page):
         *  - its Safe Browsing warning ("위험한 사이트" / "Dangerous site") -> a Safe Browsing hit: the verdict itself
         *  - a network / certificate error (ERR_ code) -> could not reach the site
         *  - any other Chrome interstitial -> could not verify
         * Chrome's interstitials carry fixed element ids ("primary-button", "details-button"). A page imitating one
         * only ends up critical or "could not verify", never safe.
         */
        fun chromePage(ids: Set<String>, visibleText: String?): String? {
            val text = visibleText.orEmpty()
            val interstitial = INTERSTITIAL_IDS.any { it in ids }
            if (interstitial && SB_TITLES.any { text.contains(it, ignoreCase = true) }) return "safe_browsing_hit:chrome_warning"
            chromeError(text)?.let { code ->
                val ssl = code.startsWith("NET::ERR_CERT") || code.startsWith("ERR_CERT") || code.startsWith("ERR_SSL")
                return (if (ssl) "ssl_error:chrome_" else "load_error:chrome_") + code
            }
            return if (interstitial) "load_error:chrome_interstitial" else null
        }

        private val INTERSTITIAL_IDS = setOf("primary-button", "details-button")
        private val SB_TITLES = listOf("위험한 사이트", "사기성 사이트", "유해한 프로그램", "Dangerous site", "Deceptive site", "contains malware", "contains harmful programs")
        private val CHROME_ERROR = Regex("""\b(?:NET::)?ERR_[A-Z0-9_]{3,}\b""")
        private const val TAG = "RedFlagScreen"
        private const val CHROME = "com.android.chrome"
        private const val POLL_MS = 600L
        private const val MIN_TEXT = 60
        private const val MAX_TEXT = 4000
        const val TIMEOUT_MS = 15_000L
    }
}
