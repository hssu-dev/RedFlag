package dev.redflag.app.lite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.redflag.core.model.PageObservation
import dev.redflag.core.model.RedFlagJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

data class Extraction(
    val observation: PageObservation?,
    val redirectCount: Int,
    val downloadAttempted: Boolean,
    val screenshot: File?,
    val error: String?,
    val elapsedMs: Long,
    /** main-frame HTTP status when it was an error (>= 400), else null */
    val httpStatus: Int? = null,
)

/**
 * Renders the page in an isolated WebView that the user never interacts with, then runs the
 * shared contracts/extract_page.js. Isolation:
 *  - separate WebView Profile "redflag-sandbox" (own cookies/storage, wiped after each run) when
 *    the device WebView supports MULTI_PROFILE; otherwise cookies are cleared and storage disabled
 *  - no addJavascriptInterface (no JS bridge), downloads dropped, file/content access off,
 *    no popups, non-http(s) navigations blocked
 * The WebView reports Chrome's UA (the "; wv" marker removed) so cloakers serve it what they
 * serve the user's Chrome — that's the point of Lite: same device, same IP, same view.
 */
class OffscreenExtractor(private val context: Context) : PageExtractor {

    private val script: String by lazy { context.assets.open("extract_page.js").bufferedReader().use { it.readText() } }

    /**
     * @param host optional attached ViewGroup (activity/overlay root) — an attached WebView lays
     *  out like a real tab; it's added behind the opaque UI so the user never sees it.
     */
    override suspend fun extract(url: String, host: ViewGroup?, timeoutMs: Long, shotName: String?, challengeWaitMs: Long): Extraction =
        withContext(Dispatchers.Main) {
            val started = System.currentTimeMillis()
            suspendCancellableCoroutine { cont ->
                val main = Handler(Looper.getMainLooper())
                var redirects = -1
                var downloadAttempted = false
                var finished = false
                var mainFrameError: String? = null
                var httpStatus: Int? = null
                var challengeDeadline = 0L // a CDN browser check was seen: wait for it to pass by itself until then
                val web = createWebView()

                fun cleanup() {
                    runCatching { (web.parent as? ViewGroup)?.removeView(web) }
                    runCatching { web.stopLoading(); web.destroy() }
                    wipeProfile()
                }

                fun finish(obs: PageObservation?, err: String?) {
                    if (finished) return
                    finished = true
                    main.removeCallbacksAndMessages(null)
                    val shot = if (obs != null && shotName != null) runCatching { screenshot(web, shotName) }.getOrNull() else null
                    cleanup()
                    if (cont.isActive) cont.resume(
                        Extraction(obs, redirects.coerceAtLeast(0), downloadAttempted, shot, err, System.currentTimeMillis() - started, httpStatus)
                    )
                }

                // Let the site load at its own pace; judge once its DOM is ready (manual_url_check follow-up,
                // user decision 2026-11-04). No "couldn't verify" just because a server or network is slow.
                var scheduled = false   // an extraction is pending or running for the current document
                var emptyRetries = 0

                fun runExtraction(reason: String) {
                    if (finished) return
                    web.evaluateJavascript(script) { raw ->
                        if (finished) return@evaluateJavascript
                        val obs = runCatching {
                            // evaluateJavascript returns a JSON literal; our script returns a JSON *string*.
                            val inner = (Json.parseToJsonElement(raw) as JsonPrimitive).content
                            RedFlagJson.decodeFromString(PageObservation.serializer(), inner)
                        }.onFailure { Log.w(TAG, "extract parse failed ($reason): ${it.message}") }.getOrNull()
                        val empty = obs != null && obs.visibleText.isBlank() && obs.forms.isEmpty() && obs.title.isBlank()
                        val warning = obs?.let { PageStates.providerWarning(it.title, it.visibleText) }
                        val state = obs?.let { PageStates.code(httpStatus, it.title, it.visibleText, it.forms.size) }
                        val now = System.currentTimeMillis()
                        when {
                            // the host/CDN's own phishing interstitial (HTTP 403): that IS a verdict, not a failure
                            warning != null -> {
                                Log.i(TAG, "host warning page: $warning")
                                finish(null, "$PROVIDER_WARNING:$warning")
                            }
                            // A main-frame network/TLS error means we extracted the WebView's own error page, not the
                            // site: never let that become an observation (it would look like an empty, "safe" page).
                            mainFrameError?.startsWith("http_") == false -> finish(null, mainFrameError)
                            obs == null -> finish(null, "extract_failed")
                            // a CDN browser check ("Just a moment..."): the WebView is a real Chromium, so it usually
                            // passes by itself and navigates to the site (onPageStarted restarts the DOM wait)
                            state == PageState.CHALLENGE -> {
                                if (challengeDeadline == 0L) {
                                    challengeDeadline = now + challengeWaitMs
                                    Log.i(TAG, "CDN browser check at ${now - started} ms: waiting up to $challengeWaitMs ms")
                                }
                                if (now < challengeDeadline) main.postDelayed({ runExtraction("challenge_wait") }, ExtractTiming.CHALLENGE_RECHECK_MS)
                                else finish(null, "$PAGE_STATE:${PageState.CHALLENGE.wire}")
                            }
                            state == PageState.BLOCKED || state == PageState.UNAVAILABLE -> {
                                Log.i(TAG, "page state ${state.wire} (http=$httpStatus) at ${now - started} ms")
                                finish(null, "$PAGE_STATE:${state.wire}")
                            }
                            // still rendering (client-side app): look again; never let emptiness look "safe"
                            empty -> web.evaluateJavascript(ExtractTiming.READY_PROBE) { probe ->
                                if (finished) return@evaluateJavascript
                                if (ExtractTiming.retryEmpty(ExtractTiming.readyState(probe), emptyRetries)) {
                                    emptyRetries++
                                    main.postDelayed({ runExtraction("render_retry") }, ExtractTiming.RENDER_RETRY_MS)
                                } else finish(null, "empty_page")
                            }
                            else -> {
                                Log.i(TAG, "extracted ($reason) at ${System.currentTimeMillis() - started} ms, progress=${web.progress}")
                                finish(obs, null)
                            }
                        }
                    }
                }

                fun schedule(reason: String) {
                    if (finished || scheduled) return
                    scheduled = true
                    // settle: let client-side rendering (and cloaking scripts) run
                    main.postAtTime({ runExtraction(reason) }, SETTLE, android.os.SystemClock.uptimeMillis() + ExtractTiming.SETTLE_MS)
                }

                var generation = 0 // one polling chain per document
                fun poll(gen: Int = generation) {
                    if (finished || scheduled || gen != generation) return
                    web.evaluateJavascript(ExtractTiming.READY_PROBE) { probe ->
                        if (finished || scheduled || gen != generation) return@evaluateJavascript
                        if (ExtractTiming.domReady(probe)) schedule("dom_ready")
                        else main.postAtTime({ poll(gen) }, POLL, android.os.SystemClock.uptimeMillis() + ExtractTiming.POLL_MS)
                    }
                }

                web.setDownloadListener { _, _, _, _, _ -> downloadAttempted = true } // never downloads
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val scheme = request.url.scheme?.lowercase()
                        if (scheme != "http" && scheme != "https") return true // intent://, market://, file:// ...
                        return false
                    }

                    override fun onPageStarted(view: WebView, startedUrl: String?, favicon: Bitmap?) {
                        redirects++
                        // a new navigation (e.g. a JS/meta redirect) replaces the document: wait for ITS DOM
                        main.removeCallbacksAndMessages(SETTLE)
                        main.removeCallbacksAndMessages(POLL)
                        scheduled = false
                        generation++
                        poll()
                    }

                    override fun onPageFinished(view: WebView, finishedUrl: String?) {
                        schedule("finished") // DOM ready was not seen first (or polling had not started)
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) mainFrameError = "load_error:${error.errorCode}"
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame && response.statusCode >= 400) {
                            mainFrameError = "http_${response.statusCode}"
                            httpStatus = response.statusCode // the body is still read: scam pages are served with 404 too
                        }
                    }

                    override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: android.webkit.SafeBrowsingResponse) {
                        // never show / proceed past the interstitial; a main-frame hit IS the verdict (Safe Browsing)
                        callback.backToSafety(false)
                        if (request.isForMainFrame) {
                            Log.i(TAG, "WebView Safe Browsing hit (${threatName(threatType)}) at ${System.currentTimeMillis() - started} ms")
                            finish(null, "$SB_HIT:${threatName(threatType)}")
                        }
                    }

                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        // only the page's own host counts as a main-frame failure (a bad third-party asset doesn't)
                        if (Uri.parse(error.url).host == Uri.parse(view.url ?: url).host) mainFrameError = "ssl_error:${error.primaryError}"
                        handler.cancel() // never proceed past a bad certificate
                    }
                }

                if (host != null) {
                    web.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    host.addView(web, 0)
                } else {
                    val dm = context.resources.displayMetrics
                    web.measure(
                        View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY),
                    )
                    web.layout(0, 0, dm.widthPixels, dm.heightPixels)
                }

                // only a ceiling for a page that never becomes ready (the user can always skip the check)
                main.postDelayed({
                    Log.i(TAG, "not ready after $timeoutMs ms: progress=${web.progress} error=$mainFrameError")
                    finish(null, mainFrameError ?: "timeout")
                }, timeoutMs)
                cont.invokeOnCancellation { main.post { if (!finished) { finished = true; main.removeCallbacksAndMessages(null); cleanup() } } }
                web.loadUrl(url)
                poll()
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val web = WebView(context)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().getOrCreateProfile(PROFILE)
            WebViewCompat.setProfile(web, PROFILE)
        } else {
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        }
        web.settings.apply {
            javaScriptEnabled = true // needed to see what the user would see (and to run the extractor)
            domStorageEnabled = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            safeBrowsingEnabled = true // the device's Safe Browsing (as in Chrome); a hit becomes the verdict
            userAgentString = chromeLikeUa(WebSettings.getDefaultUserAgent(context))
        }
        web.setLayerType(View.LAYER_TYPE_SOFTWARE, null) // lets us draw() it into a bitmap
        web.isFocusable = false
        web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        return web
    }

    private fun wipeProfile() {
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                val p = ProfileStore.getInstance().getProfile(PROFILE) ?: return
                p.cookieManager.removeAllCookies(null)
                p.webStorage.deleteAllData()
            } else {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
            }
        }
    }

    private fun screenshot(web: WebView, name: String): File? {
        val w = web.width.takeIf { it > 0 } ?: return null
        val h = web.height.takeIf { it > 0 } ?: return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        web.draw(Canvas(bmp))
        val scale = minOf(1f, 768f / w)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true) else bmp
        val dir = File(context.cacheDir, "shots").apply { mkdirs() }
        val f = File(dir, "$name.jpg")
        FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return f
    }

    companion object {
        private const val TAG = "RedFlagExtract"
        private const val PROFILE = "redflag-sandbox"
        /** Extraction.error prefix: Android WebView Safe Browsing refused the main frame. */
        const val SB_HIT = "safe_browsing_hit"
        /** Extraction.error prefix: the site's host/CDN served its own phishing warning page. */
        const val PROVIDER_WARNING = "provider_warning"
        /** Extraction.error prefix: not the site's page (PageState wire: challenge | blocked | unavailable). */
        const val PAGE_STATE = "page_state"

        fun threatName(t: Int): String = when (t) {
            WebViewClient.SAFE_BROWSING_THREAT_PHISHING -> "phishing"
            WebViewClient.SAFE_BROWSING_THREAT_MALWARE -> "malware"
            WebViewClient.SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE -> "unwanted_software"
            WebViewClient.SAFE_BROWSING_THREAT_BILLING -> "billing"
            else -> "threat_$t"
        }

        private val SETTLE = Any()
        private val POLL = Any()

        /** "...; wv) ... Version/4.0 Chrome/..." -> what Chrome itself sends. */
        fun chromeLikeUa(webViewUa: String): String =
            webViewUa.replace("; wv)", ")").replace(Regex("Version/\\d+(\\.\\d+)* "), "")

        fun hostOf(url: String): String? = runCatching { Uri.parse(url).host }.getOrNull()
    }
}

/** Readiness rules of [OffscreenExtractor] (pure, unit-tested). */
internal object ExtractTiming {
    const val POLL_MS = 250L
    /** after DOM ready: let client-side rendering (and cloaking scripts) run */
    const val SETTLE_MS = 700L
    const val RENDER_RETRY_MS = 800L
    /** a CDN browser check normally passes within ~5 s in a real Chromium */
    const val CHALLENGE_WAIT_MS = 12_000L
    const val CHALLENGE_RECHECK_MS = 1_000L
    /** an empty document is re-read while it still loads, and at most this many times once fully loaded */
    const val EMPTY_RETRIES_WHEN_COMPLETE = 2

    const val READY_PROBE = "document.readyState + '|' + location.href"

    private fun unquote(probe: String?): String? = probe?.trim()?.removeSurrounding("\"")

    fun readyState(probe: String?): String = unquote(probe)?.substringBefore('|') ?: ""

    /** DOM parsed on a real http(s) document; about:blank / not-yet-committed documents do not count. */
    fun domReady(probe: String?): Boolean {
        val p = unquote(probe) ?: return false
        val state = p.substringBefore('|')
        val href = p.substringAfter('|', "")
        return (state == "interactive" || state == "complete") && (href.startsWith("http://") || href.startsWith("https://"))
    }

    fun retryEmpty(readyState: String, retries: Int): Boolean =
        readyState != "complete" || retries < EMPTY_RETRIES_WHEN_COMPLETE
}

/** Kept for callers/tests: see [PageStates.providerWarning]. */
internal object ProviderWarning {
    fun detect(title: String?, visibleText: String?): String? = PageStates.providerWarning(title, visibleText)
}
