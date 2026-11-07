package dev.redflag.app.check

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.redflag.app.R
import dev.redflag.app.analysis.AnalysisController
import dev.redflag.app.analysis.AnalysisSession
import dev.redflag.app.context.ContextNotificationListener
import dev.redflag.app.graph
import dev.redflag.app.link.ChromeLauncher
import dev.redflag.app.link.ShortLinkResolver
import dev.redflag.app.settings.Mode
import dev.redflag.app.ui.components.ContextActions
import dev.redflag.app.ui.components.HostChip
import dev.redflag.app.ui.screens.AnalysisActions
import dev.redflag.app.ui.screens.AnalysisScreen
import dev.redflag.app.ui.theme.LocalRedFlagColors
import dev.redflag.app.ui.theme.RedFlagTheme
import dev.redflag.app.ui.theme.RfType
import dev.redflag.app.ui.theme.TokensDim
import dev.redflag.core.gate.GateResult
import dev.redflag.core.gate.UrlExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-app link check (docs/plans/manual_url_check.md §3): the link is checked BEFORE it is opened.
 * Never opens Chrome on its own; a known site is shown as such, with "check anyway".
 * Not exported: the home card and history start it; shares / text selection arrive via ShareEntryActivity,
 * which forwards nothing but the shared text (internal extras cannot be injected from outside).
 */
class CheckActivity : ComponentActivity() {
    private sealed interface Stage {
        data object Loading : Stage
        data class Pick(val urls: List<String>) : Stage
        data object NoLink : Stage
        data class Known(val url: String, val gate: GateResult.Pass, val chain: String?) : Stage
        data class Analysis(val controller: AnalysisController, val actions: AnalysisActions) : Stage
    }

    private val stage = mutableStateOf<Stage>(Stage.Loading)
    private var controller: AnalysisController? = null
    private lateinit var root: FrameLayout
    private var message: String? = null
    private var statement: String? = null
    private var links: List<String> = emptyList()
    private var callerPkg: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        root = FrameLayout(this)
        root.addView(ComposeView(this).apply { setContent { RedFlagTheme { CheckContent() } } })
        setContentView(root)

        val g = graph
        statement = intent.getStringExtra(EXTRA_STATEMENT)
        callerPkg = intent.getStringExtra(EXTRA_CALLER)
        val reportId = intent.getStringExtra(EXTRA_REPORT_ID)
        val text = intent.getStringExtra(EXTRA_TEXT)
        val extracted = text?.let { UrlExtractor.extract(it, g.bundle) }
        message = extracted?.message
        links = intent.getStringArrayListExtra(EXTRA_LINKS) ?: extracted?.urls.orEmpty()
        val url = intent.getStringExtra(EXTRA_URL)
        when {
            reportId != null -> showStored(reportId)
            url != null -> check(url)
            links.isEmpty() -> stage.value = Stage.NoLink
            links.size == 1 -> check(links.first())
            else -> stage.value = Stage.Pick(links)
        }
    }

    @Composable
    private fun CheckContent() {
        val s by stage
        AnimatedContent(s, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "check") { st ->
            when (st) {
                Stage.Loading -> Centered { CircularProgressIndicator() }
                is Stage.Pick -> PickScreen(st.urls)
                Stage.NoLink -> NoLinkScreen()
                is Stage.Known -> KnownScreen(st)
                is Stage.Analysis -> {
                    val ui by st.controller.state.collectAsStateWithLifecycle()
                    AnalysisScreen(ui, st.actions)
                }
            }
        }
    }

    /** A stored verdict from the history list, with [check again]. */
    private fun showStored(id: String) {
        val g = graph
        val r = g.reports.recent.value.firstOrNull { it.id == id } ?: run { stage.value = Stage.NoLink; return }
        val host = r.registrableDomain ?: dev.redflag.app.data.BlockedSites.hostOf(r.url) ?: r.url
        val c = AnalysisController(g, lifecycleScope, r.url, host, inspect = true).also { controller = it }
        c.showStored(r)
        stage.value = Stage.Analysis(c, AnalysisActions(
            onGoBack = { finish() },
            onOpenAnyway = { ChromeLauncher.open(this, r.url); finish() },
            onDeepAnalysis = {}, cloudAvailable = false,
            onContinue = { ChromeLauncher.open(this, r.url); finish() },
            inspect = true, onRecheck = { check(r.url) },
        ))
    }

    /** Gate (fresh: the safe-verdict cache is ignored) -> short link -> known site or a full analysis. */
    private fun check(url: String, anyway: Boolean = false) {
        controller?.cancel()
        stage.value = Stage.Loading
        val g = graph
        lifecycleScope.launch {
            var gate = withContext(Dispatchers.Default) { g.gate.evaluate(url, useSafeCache = false) }
            var target = url
            var chain: String? = null
            if (gate is GateResult.Unknown && gate.reason == GateResult.SHORTENER) {
                val final = ShortLinkResolver(g.http).resolve(url)
                if (final != null && final != url) {
                    target = final
                    chain = LinkLabels.short(url) + "  →  " + LinkLabels.short(final)
                    gate = withContext(Dispatchers.Default) { g.gate.evaluate(final, useSafeCache = false) }
                }
            }
            when (val gt = gate) {
                is GateResult.Ignore -> stage.value = Stage.NoLink
                is GateResult.Pass -> if (anyway) analyse(target, gt, url, chain) else stage.value = Stage.Known(target, gt, chain)
                else -> analyse(target, gt, url, chain)
            }
        }
    }

    private fun analyse(url: String, gate: GateResult, originalUrl: String, chain: String?) {
        val g = graph
        val host = AnalysisSession.displayHost(g, url, gate)
        val linkHosts = AnalysisSession.linkHosts(originalUrl, url, host)
        val source = AnalysisSession.source(this, callerPkg, linkHosts)
        val c = AnalysisController(
            g, lifecycleScope, url, host, source = source, linkHosts = linkHosts,
            notificationAccess = { ContextNotificationListener.hasAccess(this) },
            inspect = true, pastedMessage = message, statement = statement,
            verdictMotionMs = { AnalysisSession.FULL_SCREEN_VERDICT_MS },
        ).also { controller = it }
        val actions = AnalysisActions(
            onGoBack = { c.currentReport?.let { g.blockedSites.add(host, it) }; finish() },
            onOpenAnyway = { ChromeLauncher.open(this, url); finish() },
            onDeepAnalysis = { c.requestCloud() },
            cloudAvailable = g.settings.mode != Mode.LITE,
            onContinue = { ChromeLauncher.open(this, url); finish() },
            onSkip = { c.cancel(); finish() },
            context = ContextActions(
                onAllow = c::contextAllow, onSkip = c::contextSkip, onNeverAsk = c::contextNeverAsk,
                onOpenAccessSettings = { runCatching { startActivity(ContextNotificationListener.settingsIntent(this)) } },
                onDirectInput = c::openDirectInput, onSubmit = c::submitAnswer, onRemovePasted = c::removePasted,
            ),
            inspect = true, chain = chain,
            otherLinks = links.filter { it != originalUrl },
            onPickLink = { check(it) },
        )
        stage.value = Stage.Analysis(c, actions)
        c.start(gate, viewHost = root)
    }

    override fun onResume() {
        super.onResume()
        controller?.contextAccessChanged()
    }

    override fun onDestroy() {
        controller?.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ small screens

    @Composable
    private fun Centered(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding(), contentAlignment = Alignment.Center) { content() }
    }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }

    @Composable
    private fun PickScreen(urls: List<String>) = Page {
        Text(stringResource(R.string.check_pick_title, urls.size), style = RfType.headline, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Text(stringResource(R.string.check_pick_body), style = RfType.body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        urls.forEach { u ->
            OutlinedButton({ check(u) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(TokensDim.shape_medium.dp)) {
                Text(LinkLabels.short(u), style = RfType.mono.copy(fontSize = 13.sp), maxLines = 1)
            }
        }
        TextButton({ finish() }) { Text(stringResource(R.string.inspect_close)) }
    }

    @Composable
    private fun NoLinkScreen() = Page {
        Spacer(Modifier.height(40.dp))
        Icon(Icons.Outlined.LinkOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(52.dp))
        Text(stringResource(R.string.check_no_link), style = RfType.headline, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Text(stringResource(R.string.check_no_link_body), style = RfType.body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Button({ finish() }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(TokensDim.shape_medium.dp)) { Text(stringResource(R.string.inspect_close)) }
    }

    @Composable
    private fun KnownScreen(k: Stage.Known) = Page {
        val safe = LocalRedFlagColors.current.safe
        val domain = k.gate.normalized.registrableDomain ?: k.gate.normalized.host
        val brand = graph.bundle.brands.brands.firstOrNull { b -> b.domains.any { it.equals(domain, ignoreCase = true) } }?.name
        HostChip(k.gate.normalized.host)
        k.chain?.let { Text(it, style = RfType.mono.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(20.dp))
        Icon(Icons.Rounded.Verified, null, tint = safe.fg, modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.check_known_title), style = RfType.headline, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Text(
            if (brand != null) stringResource(R.string.check_known_brand, brand, domain) else stringResource(R.string.check_known_popular, domain),
            style = RfType.body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Text(stringResource(R.string.check_known_hint), style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Button({ ChromeLauncher.open(this@CheckActivity, k.url); finish() }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(TokensDim.shape_medium.dp)) {
            Text(stringResource(R.string.inspect_open_chrome), style = RfType.title)
        }
        OutlinedButton({ check(k.url, anyway = true) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(TokensDim.shape_medium.dp)) {
            Text(stringResource(R.string.check_known_anyway), style = RfType.title)
        }
        TextButton({ finish() }) { Text(stringResource(R.string.inspect_close)) }
    }

    companion object {
        const val EXTRA_URL = "dev.redflag.check.URL"
        const val EXTRA_TEXT = "dev.redflag.check.TEXT"
        const val EXTRA_LINKS = "dev.redflag.check.LINKS"
        const val EXTRA_STATEMENT = "dev.redflag.check.STATEMENT"
        const val EXTRA_REPORT_ID = "dev.redflag.check.REPORT_ID"
        const val EXTRA_CALLER = "dev.redflag.check.CALLER"

        /** From the home card: the chosen link, the whole input (context) and the optional situation. */
        fun forInput(context: Context, url: String, text: String, links: List<String>, statement: String?) =
            Intent(context, CheckActivity::class.java)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_TEXT, text)
                .putStringArrayListExtra(EXTRA_LINKS, ArrayList(links))
                .apply { statement?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_STATEMENT, it) } }

        fun forReport(context: Context, reportId: String) =
            Intent(context, CheckActivity::class.java).putExtra(EXTRA_REPORT_ID, reportId)

        /** From ShareEntryActivity: nothing but the shared text and the sending app. */
        fun forShared(context: Context, text: String, callerPkg: String?) =
            Intent(context, CheckActivity::class.java).putExtra(EXTRA_TEXT, text)
                .apply { callerPkg?.let { putExtra(EXTRA_CALLER, it) } }
    }
}

/** Compact link labels for chips: host + path, no scheme, middle-trimmed. */
object LinkLabels {
    fun short(url: String, max: Int = 42): String {
        val t = url.substringAfter("://").trimEnd('/')
        return if (t.length <= max) t else t.take(max - 12) + "…" + t.takeLast(11)
    }
}
