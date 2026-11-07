package dev.redflag.app.link

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.redflag.app.analysis.AnalysisController
import dev.redflag.app.analysis.AnalysisSession
import dev.redflag.app.context.ContextNotificationListener
import dev.redflag.app.ui.components.ContextActions
import dev.redflag.app.graph
import dev.redflag.app.settings.Mode
import dev.redflag.app.ui.screens.AnalysisActions
import dev.redflag.app.ui.screens.AnalysisScreen
import dev.redflag.app.ui.theme.RedFlagTheme
import dev.redflag.core.gate.GateResult
import dev.redflag.core.report.isVerifiedSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry 1: we hold ROLE_BROWSER, so http(s) links from SMS/messengers land here first.
 * Known-good -> straight to Chrome with no UI (translucent, finishes immediately).
 * Unknown -> waiting screen + Lite; Safe Browsing hit -> block screen at once.
 */
class LinkHandlerActivity : ComponentActivity() {
    private var controller: AnalysisController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.dataString
        if (url.isNullOrBlank()) { finish(); return }
        val g = graph
        lifecycleScope.launch {
            val useCache = !g.settings.alwaysReanalyze
            var gate = withContext(Dispatchers.Default) { g.gate.evaluate(url, useSafeCache = useCache) }
            var target = url
            if (gate is GateResult.Unknown && gate.reason == "shortener") {
                val final = ShortLinkResolver(g.http).resolve(url)
                if (final != null) {
                    target = final
                    gate = withContext(Dispatchers.Default) { g.gate.evaluate(final, useSafeCache = useCache) }
                }
            }
            when (gate) {
                is GateResult.Pass, is GateResult.Ignore -> { ChromeLauncher.open(this@LinkHandlerActivity, target); finish() }
                is GateResult.Block, is GateResult.Unknown -> showAnalysis(target, gate, originalUrl = url)
            }
        }
    }

    private fun showAnalysis(url: String, gate: GateResult, originalUrl: String = url) {
        enableEdgeToEdge()
        val g = graph
        val host = AnalysisSession.displayHost(g, url, gate)
        val root = FrameLayout(this)
        // Context analyzer: the app that opened the link (VIEW referrer; category only, no content)
        val linkHosts = AnalysisSession.linkHosts(originalUrl, url, host)
        val referrerPkg = referrer?.takeIf { it.scheme == "android-app" }?.host
        val source = AnalysisSession.source(this, referrerPkg, linkHosts)
        val c = AnalysisController(
            g, lifecycleScope, url, host, source = source, linkHosts = linkHosts,
            notificationAccess = { ContextNotificationListener.hasAccess(this) },
            verdictMotionMs = { AnalysisSession.FULL_SCREEN_VERDICT_MS },
        ).also { controller = it }
        val actions = AnalysisActions(
            onGoBack = { c.currentReport?.let { g.blockedSites.add(host, it) }; finish() },
            onOpenAnyway = { ChromeLauncher.open(this, url); finish() },
            onDeepAnalysis = { c.requestCloud() },
            cloudAvailable = g.settings.mode != Mode.LITE,
            onContinue = { ChromeLauncher.open(this, url); finish() },
            onSkip = { c.cancel(); ChromeLauncher.open(this, url); finish() },
            context = ContextActions(
                onAllow = c::contextAllow, onSkip = c::contextSkip, onNeverAsk = c::contextNeverAsk,
                onOpenAccessSettings = { runCatching { startActivity(ContextNotificationListener.settingsIntent(this)) } },
                onDirectInput = c::openDirectInput, onSubmit = c::submitAnswer,
            ),
        )
        root.addView(ComposeView(this).apply {
            setContent {
                RedFlagTheme {
                    val ui by c.state.collectAsStateWithLifecycle()
                    AnalysisScreen(ui, actions)
                }
            }
        })
        setContentView(root)
        var autoContinue = false
        var handedOff = false
        c.start(gate, viewHost = root) { ui ->
            val lite = ui.lite ?: return@start
            // ③ the hidden browser was kept out (CDN check / block / cloaking): open it in Chrome UNDER RedFlag's
            // cover, which reads Chrome's screen — the page is still checked before the user can touch it
            if (!handedOff && dev.redflag.app.lite.Unverified.chromeCanHelp(lite) && dev.redflag.app.Protection.isAccessibilityOn(this)) {
                handedOff = true
                c.cancel()
                ChromeLauncher.openUnderCover(this, url, host, lite.trace.lastOrNull { it.step == "extract" }?.detail ?: "unknown")
                finish()
                return@start
            }
            if (lite.isVerifiedSafe && !autoContinue) {
                autoContinue = true
                lifecycleScope.launch {
                    delay(SAFE_FLASH_MS)
                    c.awaitContextSettled() // a context question may still change the verdict
                    // once a context card was shown the user decides; never jump away from it
                    if (!c.state.value.contextPrompted && c.currentReport?.isVerifiedSafe == true) {
                        ChromeLauncher.open(this@LinkHandlerActivity, url); finish()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        controller?.contextAccessChanged() // back from the notification-access settings
    }

    override fun onDestroy() {
        controller?.cancel()
        super.onDestroy()
    }

    companion object { const val SAFE_FLASH_MS = 900L }
}
