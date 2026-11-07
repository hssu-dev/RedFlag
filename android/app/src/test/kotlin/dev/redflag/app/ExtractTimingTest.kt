package dev.redflag.app

import dev.redflag.app.lite.ExtractTiming
import dev.redflag.app.lite.LiteEngine
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Page read = once the DOM is ready, however slow the site (user decision 2026-11-04). */
class ExtractTimingTest {
    @Test
    fun domReadyNeedsAParsedHttpDocument() {
        // evaluateJavascript hands back a JSON string literal
        assertTrue(ExtractTiming.domReady("\"interactive|https://www.llm-community.com/post/1312\""))
        assertTrue(ExtractTiming.domReady("\"complete|http://192.168.0.24:8081/usedtrade/\""))
        assertFalse(ExtractTiming.domReady("\"loading|https://www.llm-community.com/post/1312\""))
        assertFalse(ExtractTiming.domReady("\"complete|about:blank\"")) // before the first commit
        assertFalse(ExtractTiming.domReady("\"complete|data:text/html,chromewebdata\""))
        assertFalse(ExtractTiming.domReady("null"))
        assertFalse(ExtractTiming.domReady(null))
        assertEquals("interactive", ExtractTiming.readyState("\"interactive|https://a.example/\""))
    }

    @Test
    fun emptyPagesAreReadAgainWhileLoadingThenGiveUp() {
        assertTrue(ExtractTiming.retryEmpty("interactive", 10)) // still loading: client-side app may render later
        assertTrue(ExtractTiming.retryEmpty("complete", 0))
        assertTrue(ExtractTiming.retryEmpty("complete", 1))
        assertFalse(ExtractTiming.retryEmpty("complete", 2)) // loaded and still empty: "empty_page" (never "safe")
    }

    @Test
    fun budgetsWaitForSlowSites() {
        // measured 2026-11-04: llm-community 5.7 s to first byte, snmnews 14 s for the HTML
        assertTrue(LiteEngine.EXTRACT_MS >= 20_000)
        // the late-verdict window must cover a slow page read plus Gemma
        assertTrue(LiteEngine.LATE_LIMIT_MS >= LiteEngine.EXTRACT_MS + 20_000)
        assertTrue(LiteEngine.TIMEOUT_MS >= LiteEngine.LATE_LIMIT_MS)
    }
}

class ProviderWarningTest {
    @Test
    fun cloudflareSuspectedPhishingPageIsRecognised() {
        // measured 2026-11-04 on a DHL phish: HTTP 403, title "Suspected Phishing | Cloudflare"
        kotlin.test.assertEquals("cloudflare_phishing", dev.redflag.app.lite.ProviderWarning.detect("Suspected Phishing | Cloudflare", ""))
        kotlin.test.assertEquals("cloudflare_phishing", dev.redflag.app.lite.ProviderWarning.detect("", "Warning: Suspected Phishing Site Ahead! ... Cloudflare Ray ID"))
        // a plain Cloudflare challenge or block is NOT a phishing verdict
        kotlin.test.assertEquals(null, dev.redflag.app.lite.ProviderWarning.detect("Just a moment...", "Checking your browser · Cloudflare"))
        kotlin.test.assertEquals(null, dev.redflag.app.lite.ProviderWarning.detect("Attention Required! | Cloudflare", "Sorry, you have been blocked"))
        // a page merely talking about phishing is not the interstitial
        kotlin.test.assertEquals(null, dev.redflag.app.lite.ProviderWarning.detect("How to spot phishing", "Suspected phishing emails"))
    }
}
