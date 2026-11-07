package dev.redflag.app.chat

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import dev.redflag.app.analysis.AnalysisController
import dev.redflag.app.analysis.AnalysisSession
import dev.redflag.app.check.LinkLabels
import dev.redflag.app.context.ContextNotificationListener
import dev.redflag.app.graph
import dev.redflag.app.link.ChromeLauncher
import dev.redflag.app.link.ShortLinkResolver
import dev.redflag.app.settings.Mode
import dev.redflag.app.ui.components.ContextActions
import dev.redflag.app.ui.screens.AnalysisActions
import dev.redflag.core.followup.FollowupContracts
import dev.redflag.core.gate.GateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One bubble of the chat home. Everything here lives in memory only: nothing typed or pasted is stored. */
sealed interface ChatItem {
    val id: Long

    /** What the user sent (shown as typed; gone when the screen closes). */
    data class User(override val id: Long, val text: String) : ChatItem
    data class NoLink(override val id: Long) : ChatItem
    data class Pick(override val id: Long, val links: List<String>, val message: String?, val caller: String?) : ChatItem
    /** Gate / short link being resolved. */
    data class Resolving(override val id: Long, val url: String) : ChatItem
    /** A site the gate already knows (brand / popular): open it, or check it anyway. */
    data class Known(override val id: Long, val url: String, val gate: GateResult.Pass, val chain: String?, val message: String?, val caller: String?) : ChatItem
    /** A check: a live AnalysisController (inspect mode). */
    data class Analysis(override val id: Long, val url: String, val controller: AnalysisController, val actions: AnalysisActions, val chain: String?) : ChatItem
    /** The user's own words were added to [controller]'s check: shows the re-judgment. */
    data class Rejudge(override val id: Long, val controller: AnalysisController) : ChatItem
    data class Cancelled(override val id: Long, val host: String) : ChatItem
}

/**
 * The chat home's conversation (docs/superpowers/specs/2026-11-05-chat-home-design.md). One check = one
 * [AnalysisController] in inspect mode, exactly like CheckActivity: Chrome is opened only from a button, the
 * advanced analysis only from its button (requestCloud), and nothing the user typed is persisted.
 */
class ChatSession(private val activity: ComponentActivity, private val viewHost: ViewGroup) {
    private val g = activity.graph
    private val scope = activity.lifecycleScope
    val items = mutableStateListOf<ChatItem>()
    /** The check opened full screen ([자세히]). */
    val detail = mutableStateOf<ChatItem.Analysis?>(null)
    private var seq = 0L
    private fun nextId() = ++seq

    /** The newest check-type reply, if it is an analysis: a link-less message goes to it as the user's statement. */
    private val currentAnalysis: ChatItem.Analysis?
        get() = items.lastOrNull { it is ChatItem.Analysis || it is ChatItem.Known || it is ChatItem.Resolving || it is ChatItem.Pick } as? ChatItem.Analysis

    fun send(text: String, caller: String? = null) {
        val route = ChatRouter.route(text, g.bundle, hasCheck = currentAnalysis != null)
        if (route == ChatRouter.Route.Empty) return
        items += ChatItem.User(nextId(), text.trim().take(SHOWN_CHARS))
        when (route) {
            is ChatRouter.Route.Check -> check(route.url, route.message, listOf(route.url), caller)
            is ChatRouter.Route.Pick -> items += ChatItem.Pick(nextId(), route.links, route.message, caller)
            is ChatRouter.Route.Statement -> {
                val a = currentAnalysis ?: return
                a.controller.submitAnswer(FollowupContracts.catalog.freeInput.defaultQuestion, emptyList(), route.text)
                items += ChatItem.Rejudge(nextId(), a.controller)
            }
            ChatRouter.Route.NoLink -> items += ChatItem.NoLink(nextId())
            ChatRouter.Route.Empty -> Unit
        }
    }

    fun pick(p: ChatItem.Pick, url: String) = check(url, p.message, p.links, p.caller)

    fun checkAnyway(k: ChatItem.Known) = analyse(replace = k.id, url = k.url, gate = k.gate, originalUrl = k.url, chain = k.chain, message = k.message, links = listOf(k.url), caller = k.caller)

    private fun replace(id: Long, with: ChatItem) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = with else items += with
    }

    /** Gate (fresh: the safe-verdict cache is ignored) -> short link -> known site or a full analysis. */
    private fun check(url: String, message: String?, links: List<String>, caller: String?) {
        val placeholder = ChatItem.Resolving(nextId(), url)
        items += placeholder
        scope.launch {
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
                is GateResult.Ignore -> replace(placeholder.id, ChatItem.NoLink(placeholder.id))
                is GateResult.Pass -> replace(placeholder.id, ChatItem.Known(placeholder.id, target, gt, chain, message, caller))
                else -> analyse(placeholder.id, target, gt, url, chain, message, links, caller)
            }
        }
    }

    private fun analyse(replace: Long, url: String, gate: GateResult, originalUrl: String, chain: String?, message: String?, links: List<String>, caller: String?) {
        val host = AnalysisSession.displayHost(g, url, gate)
        val linkHosts = AnalysisSession.linkHosts(originalUrl, url, host)
        val source = AnalysisSession.source(activity, caller, linkHosts)
        val c = AnalysisController(
            g, scope, url, host, source = source, linkHosts = linkHosts,
            notificationAccess = { ContextNotificationListener.hasAccess(activity) },
            inspect = true, pastedMessage = message,
            // the card crossfades; the full screen ([자세히]) sweeps the gauge
            verdictMotionMs = { if (detail.value?.controller != null) AnalysisSession.FULL_SCREEN_VERDICT_MS else AnalysisSession.CHAT_VERDICT_MS },
        )
        lateinit var item: ChatItem.Analysis
        val actions = AnalysisActions(
            onGoBack = { detail.value = null }, // [닫기] on the full screen: back to the chat
            onOpenAnyway = { ChromeLauncher.open(activity, url) },
            onDeepAnalysis = { c.requestCloud() },
            cloudAvailable = g.settings.mode != Mode.LITE,
            onContinue = { ChromeLauncher.open(activity, url) },
            onSkip = { c.cancel(); detail.value = null; replace(item.id, ChatItem.Cancelled(item.id, host)) },
            context = ContextActions(
                onAllow = c::contextAllow, onSkip = c::contextSkip, onNeverAsk = c::contextNeverAsk,
                onOpenAccessSettings = { runCatching { activity.startActivity(ContextNotificationListener.settingsIntent(activity)) } },
                onDirectInput = c::openDirectInput, onSubmit = c::submitAnswer, onRemovePasted = c::removePasted,
            ),
            inspect = true, chain = chain,
            otherLinks = links.filter { it != originalUrl },
            onPickLink = { check(it, message, links, caller) },
        )
        item = ChatItem.Analysis(replace, url, c, actions, chain)
        replace(replace, item)
        // a dangerous verdict is remembered like a closed check (a later tap on that link warns at once)
        c.start(gate, viewHost = viewHost) { ui ->
            val r = ui.lite ?: return@start
            if (r.level == "danger" || r.level == "critical") g.blockedSites.add(host, r)
        }
    }

    /** Notification access may have changed in Settings (a context card asked for it). */
    fun onResume() = items.filterIsInstance<ChatItem.Analysis>().lastOrNull()?.controller?.contextAccessChanged()

    fun close() = items.filterIsInstance<ChatItem.Analysis>().forEach { it.controller.cancel() }

    companion object {
        /** what the user's bubble shows of a long paste */
        const val SHOWN_CHARS = 600
    }
}

/**
 * Text shared to RedFlag ("Share -> RedFlag", text selection), handed from ShareEntryActivity to the chat home
 * inside the app process. MainActivity is exported, so it takes nothing from intent extras: another app cannot
 * plant a statement or pretend to be a messenger (docs/superpowers/specs/2026-11-05-chat-home-design.md §4).
 */
object SharedInbox {
    data class Shared(val text: String, val caller: String?)

    @Volatile private var pending: Shared? = null

    fun put(text: String, caller: String?) { pending = Shared(text, caller) }

    fun take(): Shared? = pending.also { pending = null }
}
