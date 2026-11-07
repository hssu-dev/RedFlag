package dev.redflag.app.analysis

import android.content.Context
import dev.redflag.app.AppGraph
import dev.redflag.app.context.ContextNotificationListener
import dev.redflag.app.context.NotificationBuffer
import dev.redflag.app.data.BlockedSites
import dev.redflag.core.followup.ContextSources
import dev.redflag.core.gate.GateResult

/** Wiring shared by the link handler (tapped links) and the in-app link check. */
object AnalysisSession {
    /** Host shown on screen (DEMO: the fake domain a local demo page stands for). */
    /** The full analysis screen's verdict motion: gauge sweep + screen crossfade (AnalysisScreen). */
    val FULL_SCREEN_VERDICT_MS: Long = (dev.redflag.app.ui.theme.TokensDim.motion_gaugeSweepMs + dev.redflag.app.ui.theme.TokensDim.motion_screenCrossfadeMs).toLong()
    /** The chat card's verdict crossfade (ChatHomeScreen.VerdictBubble). */
    const val CHAT_VERDICT_MS: Long = 300L

    fun displayHost(g: AppGraph, url: String, gate: GateResult): String = when (gate) {
        is GateResult.Unknown -> g.demoHosts?.match(url)?.fakeHost ?: gate.normalized.host
        is GateResult.Block -> gate.normalized.host
        is GateResult.Pass -> g.demoHosts?.match(url)?.fakeHost ?: gate.normalized.host
        is GateResult.Ignore -> url
    }

    /** Hosts the link was shown with (original link host first; e.g. a shortener). */
    fun linkHosts(originalUrl: String, url: String, host: String): List<String> =
        listOfNotNull(BlockedSites.hostOf(originalUrl), BlockedSites.hostOf(url), host).distinct()

    /**
     * Where the link came from: the calling app (VIEW referrer / share sender; category only), else the
     * notification that carried this link (only if notification access is granted).
     */
    fun source(context: Context, callerPkg: String?, linkHosts: List<String>): ContextSources.Source {
        val fromCaller = ContextSources.classify(callerPkg)
        if (fromCaller.category != "unknown" || !ContextNotificationListener.hasAccess(context)) return fromCaller
        return NotificationBuffer.sourceOf(linkHosts)?.let { ContextSources.classify(it) } ?: fromCaller
    }
}
