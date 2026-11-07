package dev.redflag.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.redflag.app.R
import dev.redflag.app.analysis.AnalysisController
import dev.redflag.app.context.ContextNotificationListener
import dev.redflag.app.context.NotificationBuffer
import dev.redflag.app.ui.components.ContextActions
import dev.redflag.core.followup.ContextSources
import dev.redflag.app.graph
import dev.redflag.app.link.AllowList
import dev.redflag.app.link.ScreenHandoff
import dev.redflag.app.settings.Mode
import dev.redflag.app.ui.screens.AnalysisActions
import dev.redflag.app.ui.screens.AnalysisScreen
import dev.redflag.app.ui.theme.LocalRedFlagColors
import dev.redflag.app.ui.theme.RedFlagTheme
import dev.redflag.app.data.BlockedSites
import dev.redflag.core.gate.GateResult
import dev.redflag.core.model.RiskReport
import dev.redflag.core.report.isVerifiedSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.ui.graphics.TransformOrigin
import dev.redflag.app.analysis.AnalysisUi
import dev.redflag.app.analysis.Phase
import dev.redflag.app.ui.components.CheckPill

/**
 * Entry 2: watches Chrome's address bar (com.android.chrome:id/url_bar). An unfamiliar domain gets a cover until
 * Lite decides: a small pill below the toolbar, growing to a full-screen, touch-blocking overlay only for danger,
 * when the user taps it, or when the user taps an input field first (docs/superpowers/specs/2026-11-05-check-pill-design.md).
 * When the bar scrolls away we simply keep the last URL (no event = no change).
 */
class RedFlagAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var lastUrl: String? = null
    private var pendingUrl: String? = null
    private var cover: Cover? = null
    private var badge: View? = null
    private var badgeOwner: OverlayOwner? = null
    private var gateJob: Job? = null

    private data class CoverFlags(
        val knownBad: Boolean,
        /** the input guard tripped (spec §3) */
        val guard: Boolean = false,
        val guardPassword: Boolean = false,
        /** the user tapped the pill */
        val expanded: Boolean = false,
    )

    private class Cover(
        val windows: CoverWindows, val owner: OverlayOwner, val controller: AnalysisController, val host: String,
        val flags: MutableStateFlow<CoverFlags>, val mode: StateFlow<CoverMode>,
    ) {
        val jobs = ArrayList<Job>()
        var safeExit: Job? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        scope.launch(Dispatchers.Default) {
            graph.gate // warm contracts + Tranco set
            graph.gemma.warmUp().onFailure { Log.w(TAG, "gemma warm-up failed: ${it.message}") }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != CHROME) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED || event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            onPageInput(event)
            return
        }
        // Chrome came (back) to the front or switched tabs: re-check even if the address did not change,
        // so a dangerous page left open in a tab is flagged again.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) lastUrl = null
        val node = findUrlBar() ?: return
        if (node.isFocused) return // the user is typing; judge once they navigate
        val text = gateInputFromBar(node.text?.toString())
        if (text.isEmpty() || text == lastUrl || text == pendingUrl) return
        pendingUrl = text
        main.removeCallbacksAndMessages(DEBOUNCE)
        main.postAtTime({ onUrl(text) }, DEBOUNCE, android.os.SystemClock.uptimeMillis() + DEBOUNCE_MS)
    }

    private fun findUrlBar(): AccessibilityNodeInfo? {
        val roots = buildList {
            rootInActiveWindow?.let { add(it) }
            runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList()).forEach { add(it) }
        }
        for (r in roots) {
            if (r.packageName?.toString() != CHROME) continue
            r.findAccessibilityNodeInfosByViewId(URL_BAR_ID)?.firstOrNull()?.let { return it }
        }
        return null
    }

    private fun onUrl(text: String) {
        lastUrl = text
        pendingUrl = null
        gateJob?.cancel()
        gateJob = scope.launch {
            val g = graph
            val gate = withContext(Dispatchers.Default) { g.gate.evaluate(text, useSafeCache = !g.settings.alwaysReanalyze) }
            Log.i(TAG, "url_bar='$text' gate=${gate::class.simpleName}/${gate.reason}")
            when (gate) {
                // the bar no longer shows the page under check (back to search, a new tab): the user left it,
                // so its cover and check go too (they used to stay up, "checking" forever over the other page)
                is GateResult.Ignore -> cover?.let { Log.i(TAG, "left ${it.host} (bar: '$text'): cover closed"); dismissCover() }
                is GateResult.Pass -> { AllowList.visited(gate.normalized.host); dismissCover(); showBadge() }
                is GateResult.Block, is GateResult.Unknown -> {
                    AllowList.visited(if (gate is GateResult.Unknown) gate.normalized.host else (gate as GateResult.Block).normalized.host)
                    if (AllowList.isAllowed(text)) { Log.i(TAG, "allowed (user skipped / opened it on this visit): no cover"); dismissCover(); return@launch }
                    val host = if (gate is GateResult.Unknown) gate.normalized.host else (gate as GateResult.Block).normalized.host
                    val shownHost = g.demoHosts?.match(text)?.fakeHost ?: host
                    if (cover?.host == shownHost) return@launch
                    val stored = if (g.settings.alwaysReanalyze) null else g.blockedSites.get(shownHost)
                    showCover(text, gate, shownHost, stored)
                }
            }
        }
    }

    /** Back; if Chrome still shows the blocked site (e.g. a tab with no history), go to the home screen. */
    private fun leaveBlockedSite(host: String) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        main.postDelayed({
            val now = findUrlBar()?.text?.toString()?.let { BlockedSites.hostOf(gateInputFromBar(it)) }
            if (BlockedSites.key(now) == BlockedSites.key(host)) performGlobalAction(GLOBAL_ACTION_HOME)
        }, 700)
    }

    private fun showCover(url: String, gate: GateResult, host: String, stored: RiskReport? = null) {
        dismissCover()
        hideBadge() // the previous page's "checked" dot does not belong to this one
        val g = graph
        val owner = OverlayOwner()
        val windows = CoverWindows(this, wm).apply { pillY = pillTop() }
        // Chrome path: no VIEW referrer. With notification access, the app whose notification carried this
        // link is the origin (memory-only buffer; nothing is read beyond the match until the user allows).
        val linkHosts = listOfNotNull(BlockedSites.hostOf(url), host).distinct()
        val hasAccess = ContextNotificationListener.hasAccess(this)
        val source = ContextSources.classify(if (hasAccess) NotificationBuffer.sourceOf(linkHosts) else null)
        var coverFull = false // the full analysis screen sweeps its gauge; the pill only changes colour
        val controller = AnalysisController(
            g, scope, url, host, source = source, linkHosts = linkHosts,
            notificationAccess = { ContextNotificationListener.hasAccess(this) },
            verdictMotionMs = { if (coverFull) dev.redflag.app.analysis.AnalysisSession.FULL_SCREEN_VERDICT_MS else 0L },
        )
        // a link RedFlag could not read itself was handed to Chrome under this cover: read Chrome's screen directly.
        // It came from another app (LinkHandlerActivity), where the check is always full screen (spec §1).
        val handoff = if (stored == null) ScreenHandoff.take(host) else null // why the hidden browser already failed
        val flags = MutableStateFlow(CoverFlags(knownBad = gate is GateResult.Block || stored != null))
        val fullSetting = g.settings.fullCoverWhileChecking || handoff != null
        fun modeOf(ui: AnalysisUi, f: CoverFlags) = CoverPolicy.mode(CoverPolicy.Input.of(ui, f.knownBad, fullSetting, f.guard, f.expanded))
        val mode = combine(controller.state, flags, ::modeOf)
            .stateIn(scope, SharingStarted.Eagerly, modeOf(controller.state.value, flags.value))
        val actions = AnalysisActions(
            onGoBack = {
                controller.currentReport?.let { g.blockedSites.add(host, it) }
                dismissCover()
                leaveBlockedSite(host)
            },
            onOpenAnyway = { AllowList.allow(url); AllowList.allowHost(host); dismissCover() },
            onDeepAnalysis = { controller.requestCloud() },
            cloudAvailable = g.settings.mode != Mode.LITE,
            onContinue = { AllowList.allow(url); dismissCover(); showBadge() },
            onSkip = { AllowList.allow(url); AllowList.allowHost(host); dismissCover() }, // dismissCover cancels the check
            context = ContextActions(
                onAllow = controller::contextAllow, onSkip = controller::contextSkip, onNeverAsk = controller::contextNeverAsk,
                onOpenAccessSettings = { runCatching { startActivity(ContextNotificationListener.settingsIntent(this)) } },
                onDirectInput = controller::openDirectInput, onSubmit = controller::submitAnswer,
                onKeyboard = { on -> windows.setKeyboard(on) },
            ),
        )
        val guardText = getString(R.string.cover_input_guard)
        val guardPasswordText = getString(R.string.cover_input_guard_password)
        val compose = ComposeView(this).apply {
            setContent {
                RedFlagTheme {
                    val ui by controller.state.collectAsStateWithLifecycle()
                    val m by mode.collectAsStateWithLifecycle()
                    val f by flags.collectAsStateWithLifecycle()
                    when (val cm = m) {
                        is CoverMode.Pill -> CheckPill(ui, cm.style, onTap = { Log.i(TAG, "pill tapped"); flags.update { it.copy(expanded = true) } })
                        is CoverMode.Full -> {
                            // grows out of the pill: scale up from the top centre
                            val enter = remember { MutableTransitionState(false).apply { targetState = true } }
                            AnimatedVisibility(
                                enter, enter = fadeIn(tween(220)) + scaleIn(tween(320), initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 0f)),
                            ) {
                                AnalysisScreen(
                                    ui, actions,
                                    onCollapse = if (cm.collapsible) ({ flags.update { it.copy(expanded = false, guard = false) } }) else null,
                                    notice = if (cm.reason == FullReason.INPUT_GUARD) (if (f.guardPassword) guardPasswordText else guardText) else null,
                                )
                            }
                        }
                    }
                }
            }
        }
        windows.surface.addView(compose)
        owner.attach(windows.surface)
        windows.add(full = mode.value is CoverMode.Full)
        val c = Cover(windows, owner, controller, host, flags, mode)
        cover = c
        c.jobs += scope.launch { mode.collect { m -> Log.i(TAG, "cover mode: $m"); coverFull = m is CoverMode.Full; windows.setFull(coverFull) } }
        // verified safe and nothing left to ask: close the cover, leave the green "checked" dot
        c.jobs += scope.launch {
            combine(controller.state, mode) { ui, m -> ui to m }.collect { (ui, m) ->
                val safe = ui.phase == Phase.RESULT && ui.lite?.isVerifiedSafe == true && ui.displayLevel == "safe"
                val leave = safe && ui.context.settled && !(m is CoverMode.Full && ui.contextPrompted)
                c.safeExit?.cancel()
                if (!leave) return@collect
                c.safeExit = scope.launch {
                    delay(SAFE_EXIT_MS) // the green pill / safe screen registers before it goes
                    if (controller.currentReport?.isVerifiedSafe != true) return@launch
                    AllowList.allow(url)
                    // a late verdict must only close ITS cover, never one opened since for another page
                    if (cover === c) { dismissCover(); showBadge() }
                }
            }
        }
        // the user left Chrome (home, another app): close the cover instead of floating it over other apps.
        // Coming back to Chrome re-checks the page (TYPE_WINDOW_STATE_CHANGED resets lastUrl).
        c.jobs += scope.launch {
            var away = 0
            while (true) {
                delay(LEAVE_POLL_MS)
                val active = runCatching { this@RedFlagAccessibilityService.windows.firstOrNull { it.isActive } }.getOrNull()
                val pkg = runCatching { active?.root?.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString() }.getOrNull()
                // our own cover takes focus for the context text field (stays); RedFlag's app in front is leaving
                val ownCover = pkg == packageName && active?.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                away = if (pkg == null || pkg == CHROME || ownCover || pkg in TRANSIENT_WINDOWS) 0 else away + 1
                if (away >= 2) {
                    Log.i(TAG, "left Chrome ($pkg): cover for $host closed")
                    lastUrl = null
                    if (cover === c) dismissCover()
                    break
                }
            }
        }
        Log.i(TAG, "cover for $host: ${mode.value}")
        if (stored != null) { // came back to a site already blocked: show that verdict at once
            Log.i(TAG, "revisit of blocked site $host (${stored.level} ${stored.score})")
            controller.showStored(stored)
            return
        }
        controller.start(gate, viewHost = windows.stage, screenReader = ChromeScreenReader(this), screenOnly = handoff != null, hiddenFailure = handoff) { ui ->
            val lite = ui.lite ?: return@start
            if (lite.level == "danger" || lite.level == "critical") g.blockedSites.add(host, lite)
        }
    }

    /** Just below Chrome's toolbar (the address stays readable); a fixed guess if the bar is not on screen. */
    private fun pillTop(): Int {
        val r = android.graphics.Rect()
        val bar = findUrlBar()?.also { it.getBoundsInScreen(r) }
        val gap = (10 * resources.displayMetrics.density).toInt()
        return if (bar != null && r.bottom > 0) r.bottom + gap * 2 else (110 * resources.displayMetrics.density).toInt()
    }

    /**
     * Input guard (spec §3): the user tapped a field in Chrome's page while the pill is up and the page is not
     * verified safe -> the cover goes full screen before anything can be typed.
     */
    private fun onPageInput(event: AccessibilityEvent) {
        val c = cover ?: return
        if (c.mode.value !is CoverMode.Pill) return
        val src = event.source ?: return
        if (!src.isEditable) return
        // Chrome's own fields (address bar, find in page) have "com.android.chrome:id/..." ids; a web page field may
        // carry its HTML id as a bare view id ("name"), which is fine
        if (src.viewIdResourceName?.startsWith("$CHROME:id/") == true) return
        val focus = if (src.isPassword) InputFocus.PASSWORD else InputFocus.TEXT
        val f = c.flags.value
        val input = CoverPolicy.Input.of(c.controller.state.value, f.knownBad, false, f.guard, f.expanded)
        if (!CoverPolicy.guardTrips(focus, input)) return
        Log.i(TAG, "input guard: $focus field tapped on ${c.host} before a safe verdict")
        c.flags.update { it.copy(guard = true, guardPassword = focus == InputFocus.PASSWORD) }
        runCatching { src.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) }
    }

    private fun dismissCover() {
        val c = cover ?: return
        cover = null
        c.jobs.forEach { it.cancel() }
        c.safeExit?.cancel()
        c.controller.cancel()
        c.windows.remove()
        c.owner.destroy()
    }

    /**
     * "Checked" mark: a small green dot in the top-right corner, faded in and out, so it never covers the page.
     * The words stay as its accessibility label (TalkBack still says "RedFlag 확인 완료").
     */
    private fun showBadge() {
        hideBadge()
        val owner = OverlayOwner()
        val label = getString(R.string.badge_checked)
        val view = ComposeView(this).apply {
            setContent {
                RedFlagTheme {
                    val c = LocalRedFlagColors.current
                    var shown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { shown = true; delay(BADGE_MS - BADGE_FADE_MS); shown = false }
                    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(BADGE_FADE_MS.toInt()), label = "badge")
                    // ring in the surface colour + soft shadow: the dot stays visible on light and dark pages alike
                    Box(
                        Modifier.padding(10.dp).alpha(alpha).size(14.dp).shadow(2.dp, CircleShape)
                            .background(MaterialTheme.colorScheme.surface, CircleShape).padding(2.5.dp)
                            .background(c.safe.fg, CircleShape)
                            .semantics { contentDescription = label },
                    )
                }
            }
        }
        val frame = FrameLayout(this).apply { addView(view) }
        owner.attach(frame)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 180 }
        runCatching { wm.addView(frame, lp) }
        badge = frame
        badgeOwner = owner
        main.postAtTime({ hideBadge() }, BADGE_TOKEN, android.os.SystemClock.uptimeMillis() + BADGE_MS)
    }

    private fun hideBadge() {
        main.removeCallbacksAndMessages(BADGE_TOKEN) // an older timer must not remove a newer dot early
        badge?.let { runCatching { wm.removeView(it) } }
        badgeOwner?.destroy()
        badge = null
        badgeOwner = null
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        dismissCover()
        hideBadge()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    companion object {
        /**
         * Chrome may append the security state to the url_bar text, e.g. on the S26 Ultra / Chrome 156:
         * "localhost:8081/cj/. 연결이 안전하지 않음" (= "Not secure"). Take the first token and drop the
         * trailing sentence punctuation so the gate sees just the address.
         */
        fun urlFromBarText(raw: String?): String {
            val t = raw?.trim().orEmpty()
            if (t.isEmpty()) return ""
            val first = t.split(Regex("\\s+"), limit = 2)[0]
            return first.trimEnd('.', ',', ';', '·', '…')
        }

        /**
         * What to hand to the gate. Chrome hides the scheme; a security label after the address
         * ("연결이 안전하지 않음" / "Not secure") means plain http. Dot-less hosts (localhost:8081/...)
         * get an explicit scheme so the gate treats them as a URL; placeholder text ("Search or type ...")
         * has no ':' '/' '.' and is left alone (the gate ignores it).
         */
        fun gateInputFromBar(raw: String?): String {
            val token = urlFromBarText(raw)
            if (token.isEmpty() || "://" in token) return token
            val insecure = raw!!.trim().split(Regex("\\s+")).size > 1
            val urlish = token.any { it == ':' || it == '/' || it == '.' }
            // Chrome's "Not secure" label also appears on https pages (e.g. a form posting to http), so it is
            // only a hint for dot-less hosts; real domains stay scheme-less and LiteEngine retries the other
            // scheme if loading fails (seen on the S26: an https phishing page labelled "연결이 안전하지 않음").
            return when {
                !urlish -> token
                '.' !in token.substringBefore('/') -> (if (insecure) "http://" else "https://") + token
                else -> token
            }
        }

        private const val TAG = "RedFlagA11y"
        const val CHROME = "com.android.chrome"
        const val URL_BAR_ID = "com.android.chrome:id/url_bar"
        private const val DEBOUNCE_MS = 350L
        private val DEBOUNCE = Any()
        private const val SAFE_EXIT_MS = 900L
        private const val LEAVE_POLL_MS = 700L
        /** pulled down over Chrome for a moment (notification shade): not leaving */
        private val TRANSIENT_WINDOWS = setOf("com.android.systemui")
        private const val BADGE_MS = 1_800L
        private const val BADGE_FADE_MS = 250L
        private val BADGE_TOKEN = Any()
        @Volatile var instance: RedFlagAccessibilityService? = null
    }
}
