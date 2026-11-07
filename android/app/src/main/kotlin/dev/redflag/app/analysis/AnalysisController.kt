package dev.redflag.app.analysis

import android.view.ViewGroup
import dev.redflag.app.AppGraph
import dev.redflag.app.cloud.CloudEvent
import dev.redflag.app.data.PrefsSafeVerdictCache
import dev.redflag.app.lite.LiteObservation
import dev.redflag.app.lite.StepId
import dev.redflag.app.lite.StepStatus
import dev.redflag.app.settings.Mode
import dev.redflag.core.gate.GateResult
import dev.redflag.core.report.isVerifiedSafe
import dev.redflag.core.model.RiskReport
import dev.redflag.core.model.TraceStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import android.util.Log
import dev.redflag.app.context.NotificationBuffer
import dev.redflag.app.lite.LiteResult
import dev.redflag.core.followup.ContextDecision
import dev.redflag.core.followup.ContextJudge
import dev.redflag.core.followup.ContextPlan
import dev.redflag.core.followup.ContextPolicy
import dev.redflag.core.followup.ContextSources
import dev.redflag.core.followup.ConvMessage
import dev.redflag.core.followup.FollowupContracts
import dev.redflag.core.followup.UserAnswer
import dev.redflag.core.followup.CloudContext

data class StepUi(val id: StepId, val status: StepStatus, val detail: String?)

sealed interface CloudUi {
    data object Idle : CloudUi
    /** [waitingForContext]: the user tapped it while a context card was open; it starts once that is answered or skipped. */
    data class Running(val traces: List<TraceStep>, val trigger: String, val waitingForContext: Boolean = false) : CloudUi
    data class Done(val report: RiskReport, val traces: List<TraceStep>) : CloudUi
    data class Failed(val message: String, val traces: List<TraceStep>) : CloudUi
}

enum class Phase { RUNNING, RESULT }

/** Context analyzer state (docs/plans/context_analysis.md). Every step can be skipped. */
sealed interface ContextUi {
    /** Nothing pending: not applicable, excluded, or Gemma/code saw no need. */
    data object Idle : ContextUi
    /** Turn 2 (does context matter?) is running. */
    data object Checking : ContextUi
    /** "May RedFlag check the context?" */
    data class Consent(val plan: ContextPlan, val needsAccess: Boolean) : ContextUi
    /** One fixed question + quick chips + free text. */
    data class Ask(val questionId: String, val userInitiated: Boolean) : ContextUi
    /** Turn 3 (context judgment) is running. */
    data object Judging : ContextUi
    /** Finished; [summary] null = skipped or nothing to add. */
    data class Done(val summary: ContextSummary?, val note: String? = null) : ContextUi

    val settled: Boolean get() = this is Idle || this is Done
}

data class ContextSummary(
    val scenario: String?,
    val evidence: List<String>,
    /** points the context added (0 if it changed nothing) */
    val added: Int,
    val usedConversation: Boolean,
    val usedStatement: Boolean,
    /** the conversation was the message pasted with the link (in-app check) */
    val usedPasted: Boolean = false,
)

/** In-app link check: the message pasted/shared with the link (context the user handed over). */
/**
 * What the advanced analysis would send besides the page (user-provided context, docs/plans/manual_url_check.md §5).
 * [stale]: the user added context after the last cloud verdict (offer "analyse again with it").
 */
data class CloudContextUi(val statements: Int, val pasted: Int, val previews: List<String>, val stale: Boolean = false) {
    val any: Boolean get() = statements > 0 || pasted > 0
}

data class PastedUi(
    /** masked as it goes to Gemma and, on request, to the cloud */
    val preview: String,
    /** false once the user tapped [remove] */
    val active: Boolean = true,
)

data class AnalysisUi(
    val url: String,
    val host: String,
    val phase: Phase = Phase.RUNNING,
    val steps: List<StepUi> = StepId.entries.map { StepUi(it, StepStatus.PENDING, null) },
    val lite: RiskReport? = null,
    val liteObservation: LiteObservation? = null,
    val cloud: CloudUi = CloudUi.Idle,
    val engineLabel: String = "",
    /** Re-showing a stored verdict (the user came back to a site RedFlag already blocked). */
    val revisit: Boolean = false,
    /** Where the link came from (VIEW referrer, or the notification that carried it). */
    val source: ContextSources.Source = ContextSources.Source("unknown", null, null),
    val context: ContextUi = ContextUi.Idle,
    /** The user typed context before the verdict was in: applied when it is. */
    val contextPending: Boolean = false,
    /** A context card was shown to the user (then no automatic "safe -> continue"). */
    val contextPrompted: Boolean = false,
    /** In-app link check (never jumps to Chrome on its own; docs/plans/manual_url_check.md §3). */
    val inspect: Boolean = false,
    val pasted: PastedUi? = null,
    val cloudContext: CloudContextUi? = null,
) {
    /** The stricter of the Lite and Cloud verdicts drives the screen. */
    val displayLevel: String
        get() {
            val c = (cloud as? CloudUi.Done)?.report?.level
            val l = lite?.level ?: "safe"
            return if (c != null && LEVELS.indexOf(c) > LEVELS.indexOf(l)) c else l
        }
    val displayScore: Int
        get() = maxOf(lite?.score ?: 0, (cloud as? CloudUi.Done)?.report?.score ?: 0)
    val unverified: Boolean get() = lite != null && lite.status != "complete" && cloud !is CloudUi.Done
    val cloudReport: RiskReport? get() = (cloud as? CloudUi.Done)?.report

    companion object { val LEVELS = listOf("safe", "caution", "danger", "critical") }
}

/**
 * Drives one analysis: Lite (blocking, ≤ 8 s) then optional Cloud (background, never blocks).
 * Shared by LinkHandlerActivity (links from other apps) and the accessibility overlay (Chrome).
 */
class AnalysisController(
    private val g: AppGraph,
    private val scope: CoroutineScope,
    url: String,
    host: String,
    source: ContextSources.Source = ContextSources.Source("unknown", null, null),
    /** Hosts the link was shown with in the chat (original link host first; e.g. a shortener). */
    private val linkHosts: List<String> = listOf(host),
    /** True when notification access is granted (context cards need it for automatic context). */
    private val notificationAccess: () -> Boolean = { false },
    /** In-app link check: user-started, so the "once per site" context limit does not apply. */
    private val inspect: Boolean = false,
    /** Text pasted/shared with the link (in-app check). Becomes one untrusted conversation message. */
    pastedMessage: String? = null,
    /** "Add a situation" typed on the home card: the user's own words (user_statement). */
    statement: String? = null,
    /**
     * How long the verdict's own animation runs on the screen showing it (gauge sweep + crossfade), 0 when nothing
     * big moves (Chrome pill). The context turn waits that long after a not-safe verdict: Gemma on the GPU and the
     * sweep in the same second made the sweep stall up to 200 ms a frame (perfetto, 2026-11-05).
     */
    private val verdictMotionMs: () -> Long = { 0L },
) {
    private val pastedWindow: String? = pastedMessage?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { dev.redflag.core.gate.UrlExtractor.messageWindow(it, url, FollowupContracts.catalog.limits.messageChars) }
    private val _state = MutableStateFlow(AnalysisUi(
        url = url, host = host, engineLabel = g.gemma.backendLabel, source = source, inspect = inspect,
        pasted = pastedWindow?.let { PastedUi(dev.redflag.core.followup.ContextText.mask(it)) },
    ))
    val state: StateFlow<AnalysisUi> = _state
    private var cloudJob: Job? = null

    private var liteJob: Job? = null

    /** Show a verdict RedFlag already made for this site, without re-analysing it. */
    fun showStored(report: RiskReport) {
        _state.update { it.copy(phase = Phase.RESULT, lite = report, revisit = true) }
    }

    /** The verdict currently on screen (stricter of Lite and Cloud). */
    val currentReport: RiskReport?
        get() = _state.value.let { s -> s.cloudReport?.takeIf { (it.score) > (s.lite?.score ?: 0) } ?: s.lite }

    /** Called after Lite and again after a context re-score. */
    private var onUpdate: (AnalysisUi) -> Unit = {}

    fun start(
        gate: GateResult, viewHost: ViewGroup?,
        /** Chrome cover only: reads the page Chrome shows when the hidden WebView cannot get it (③). */
        screenReader: dev.redflag.app.lite.PageExtractor? = null,
        screenOnly: Boolean = false,
        hiddenFailure: String? = null,
        onLiteDone: (AnalysisUi) -> Unit = {},
    ) {
        onUpdate = onLiteDone
        liteJob = scope.launch {
            val res = g.lite.analyze(
                _state.value.url, gate, viewHost,
                progress = { step, status, detail ->
                    _state.update { s -> s.copy(steps = s.steps.map { if (it.id == step) StepUi(step, status, detail ?: it.detail) else it }) }
                },
                lateScope = scope,
                screenReader = screenReader, screenOnly = screenOnly, hiddenFailure = hiddenFailure,
                onLate = { late -> // the real verdict arrived after the 8 s "couldn't verify" screen
                    applyLite(late)
                    startContext(late)
                    if (late.report.isVerifiedSafe) cacheSafeWhenSettled()
                    onLiteDone(_state.value)
                },
            )
            applyLite(res)
            startContext(res) // before onLiteDone: "safe -> continue" waits for the context step
            if (res.report.isVerifiedSafe) cacheSafeWhenSettled() // after startContext: waits for its card
            val report = res.report
            // No automatic cloud analysis, in any mode: report.escalate only makes the screen RECOMMEND it.
            onLiteDone(_state.value)
        }
    }

    private fun applyLite(res: dev.redflag.app.lite.LiteResult) {
        val report = res.report
        g.reports.add(report)
        _state.update { it.copy(phase = Phase.RESULT, lite = report, liteObservation = res.observation, engineLabel = g.gemma.backendLabel) }
    }

    /**
     * "Safe" is cached (12 h: the gate then skips the page) only once the context step is over: never while a
     * context card is open, never if the user left or skipped a card, and after an answer only if still safe.
     */
    private var safeCacheJob: Job? = null
    private fun cacheSafeWhenSettled() {
        safeCacheJob?.cancel()
        safeCacheJob = scope.launch {
            awaitContextSettled()
            val s = _state.value
            val r = s.lite ?: return@launch
            val answered = (s.context as? ContextUi.Done)?.summary != null
            if (r.isVerifiedSafe && (!s.contextPrompted || answered)) {
                r.registrableDomain?.let { g.safeVerdictCache.putSafe(it, PrefsSafeVerdictCache.TTL_MS, System.currentTimeMillis()) }
            }
        }
    }

    /**
     * The ONLY way page data reaches the cloud: the user tapped "advanced analysis" (AnalysisActions.onDeepAnalysis).
     * Never called by the app itself (user decision 2026-11-04; guarded by NoAutomaticCloudTest).
     */
    fun requestCloud() {
        if (g.settings.mode == Mode.LITE) return
        if (cloudJob?.isActive == true) return
        if (_state.value.cloud is CloudUi.Done && _state.value.cloudContext?.stale != true) return // again only with new context
        val trigger = "user"
        _state.update { it.copy(cloud = CloudUi.Running(emptyList(), trigger, waitingForContext = !it.context.settled)) }
        cloudJob = scope.launch {
            // a context card is open: the user is answering it right here, so send their answer too
            awaitContextSettled()
            val s = _state.value
            val url = s.lite?.url ?: s.url
            // user-provided context goes along ONLY on this user-requested path (user decision 2026-11-04)
            val userContext = cloudContext()
            sentCloudContext = userContext?.toString()
            _state.update { it.copy(cloud = CloudUi.Running(emptyList(), trigger), cloudContext = it.cloudContext?.copy(stale = false)) }
            val traces = ArrayList<TraceStep>()
            g.cloud.analyze(url, s.liteObservation, trigger, userContext).collect { ev ->
                when (ev) {
                    is CloudEvent.Trace -> { traces += ev.step; _state.update { it.copy(cloud = CloudUi.Running(traces.toList(), trigger)) } }
                    is CloudEvent.Report -> { g.reports.add(ev.report); _state.update { it.copy(cloud = CloudUi.Done(ev.report, traces.toList())) } }
                    is CloudEvent.Failed -> _state.update { it.copy(cloud = CloudUi.Failed(ev.message, traces.toList())) }
                }
            }
            if (_state.value.cloud is CloudUi.Running) _state.update { it.copy(cloud = CloudUi.Failed("stream ended", traces.toList())) }
        }
    }

    // ------------------------------------------------------------ user context for the cloud

    private var deviceJudgment: CloudContext.DeviceJudgment? = null
    private var sentCloudContext: String? = null

    /**
     * user_context for the advanced analysis: the user's own answers/statements, the pasted message (if not removed),
     * the source category and the device judgment enums. Chats read from notifications are NOT included.
     */
    private fun cloudContext(): kotlinx.serialization.json.JsonObject? = CloudContext.build(
        _state.value.source.category, answers.toList(),
        listOfNotNull(pastedWindow?.takeIf { _state.value.pasted?.active == true }),
        deviceJudgment,
    )

    /** Keeps the "sent along with it" line and the re-run offer in step with the context the user has given. */
    private fun refreshCloudContext() {
        val c = cloudContext()
        val statements = (c?.get("statements") as? kotlinx.serialization.json.JsonArray)?.size ?: 0
        val pasted = (c?.get("pasted_messages") as? kotlinx.serialization.json.JsonArray)?.size ?: 0
        _state.update { s ->
            val stale = s.cloud is CloudUi.Done && c != null && c.toString() != sentCloudContext && (statements + pasted) > 0
            s.copy(cloudContext = CloudContextUi(statements, pasted, CloudContext.previewTexts(c, java.util.Locale.getDefault().language == "ko"), stale))
        }
    }

    /** Stops everything this analysis started (Lite incl. a pending late verdict, Cloud, context turns). */
    fun cancel() { liteJob?.cancel(); cloudJob?.cancel(); contextJob?.cancel() }

    // ------------------------------------------------------------ context analyzer

    private var contextJob: Job? = null
    private var lastLite: LiteResult? = null
    private var plan: ContextPlan? = null
    private var conversation: List<ConvMessage>? = null
    private var conversationApp: String? = null
    private val answers = ArrayList<UserAnswer>().apply {
        statement?.trim()?.takeIf { it.isNotEmpty() }?.let {
            add(UserAnswer(FollowupContracts.catalog.freeInput.defaultQuestion, emptyList(), it.take(FollowupContracts.catalog.limits.statementChars)))
        }
    }

    /** The pasted message as Turn 3 sees it (null once removed, or when there was none). */
    private fun pastedConversation(): List<ConvMessage>? =
        pastedWindow?.takeIf { _state.value.pasted?.active == true }?.let { listOf(ConvMessage(PASTED_SENDER, it, hasLink = true)) }

    /** The user handed over context themselves (pasted message or typed statement). */
    private fun hasUserContext() = pastedConversation() != null || answers.isNotEmpty()

    init { refreshCloudContext() }

    /** [remove] on the "pasted message in use" chip: not used for the (next) judgment, never sent to the cloud. */
    fun removePasted() {
        _state.update { s -> s.copy(pasted = s.pasted?.copy(active = false)) }
        if (conversationApp == PASTED_APP) { conversation = null; conversationApp = null }
        refreshCloudContext()
    }
    private var contextT0 = 0L

    /** Suspends until no context step is waiting or running. */
    suspend fun awaitContextSettled() { state.first { it.context.settled } }

    private fun setContext(c: ContextUi) = _state.update {
        it.copy(context = c, contextPrompted = it.contextPrompted || c is ContextUi.Consent || c is ContextUi.Ask)
    }

    private fun startContext(res: LiteResult) {
        lastLite = res
        if (!g.settings.contextEnabled) return
        val s = _state.value
        val userCtx = hasUserContext()
        val input = ContextPolicy.Input(
            score = res.report.score, complete = res.report.status == "complete", safeBrowsingHit = res.safeBrowsingHit,
            features = res.features, sourceCategory = s.source.category,
            // the user handed context over: "don't ask again" only blocks question cards (below)
            neverAsk = g.settings.contextNeverAsk && !userCtx,
            alreadyAsked = !inspect && askedHosts.contains(s.host),
        )
        val decision = ContextPolicy.decide(input)
        if (s.contextPending && answers.isNotEmpty()) { _state.update { it.copy(contextPending = false) }; judge(); return }
        if (decision == ContextDecision.EXCLUDE) {
            if (userCtx && inspect) setContext(ContextUi.Done(null, note = if (res.report.score >= 85 || res.safeBrowsingHit) "already_high" else "excluded"))
            return
        }
        askedHosts += s.host
        pastedConversation()?.let { conversation = it; conversationApp = PASTED_APP }
        // typed a situation on the home card: the user already explained, judge right away
        if (answers.isNotEmpty()) { judge(); return }
        setContext(ContextUi.Checking)
        contextT0 = System.currentTimeMillis()
        contextJob = scope.launch {
            // let the verdict land first (a safe verdict barely moves, and its "continue" should not wait)
            if (res.report.level != "safe") verdictMotionMs().takeIf { it > 0 }?.let { delay(it) }
            val out = try {
                g.gemma.chat(
                    FollowupContracts.contextNeedPrompt,
                    ContextPolicy.turn2Message(input, decision, res.pageDomain, res.domainAgeDays, res.report.level),
                    FollowupContracts.tools,
                )
            } catch (c: CancellationException) { throw c } catch (t: Throwable) { Log.w(TAG, "context turn 2 failed: ${t.message}"); null }
            val calls = out?.toolCalls?.ifEmpty { null } ?: out?.text?.let { ContextPolicy.toolCallsFromText(it) }.orEmpty()
            val parsed = out?.let { ContextPolicy.parseToolCalls(calls) }
            val p = ContextPolicy.resolve(decision, parsed, s.source.category, res.features)
            Log.i(TAG, "context: decision=$decision calls=${calls.map { it.name + it.args }} plan=$p (${out?.latencyMs} ms)")
            plan = p
            if (conversationApp == PASTED_APP) { // the message is already here: only a question can still help
                val q = p?.question?.takeIf { !g.settings.contextNeverAsk }
                if (q != null) setContext(ContextUi.Ask(q, userInitiated = false)) else judge()
                return@launch
            }
            when {
                p == null -> setContext(ContextUi.Idle)
                p.auto -> setContext(ContextUi.Consent(p, !notificationAccess()))
                p.question != null -> setContext(ContextUi.Ask(p.question!!, userInitiated = false))
                else -> setContext(ContextUi.Idle)
            }
        }
    }

    /** "Allow": read the link's message + a few before it (memory only), then the question if one was asked for. */
    fun contextAllow() {
        val c = _state.value.context as? ContextUi.Consent ?: return
        if (!notificationAccess()) { setContext(c.copy(needsAccess = true)); return }
        val found = NotificationBuffer.findLink(linkHosts)
        conversation = found?.messages
        conversationApp = found?.pkg?.let { ContextSources.classify(it).name?.en }
        val q = c.plan.question
        when {
            q != null -> setContext(ContextUi.Ask(q, userInitiated = false))
            found != null -> judge()
            else -> setContext(ContextUi.Done(null, note = "no_conversation"))
        }
    }

    /** Re-check after the user came back from the notification-access settings. */
    fun contextAccessChanged() {
        val c = _state.value.context as? ContextUi.Consent ?: return
        setContext(c.copy(needsAccess = !notificationAccess()))
    }

    fun contextSkip() {
        when (val c = _state.value.context) {
            is ContextUi.Consent -> c.plan.question?.let { setContext(ContextUi.Ask(it, userInitiated = false)) } ?: setContext(ContextUi.Done(null))
            is ContextUi.Ask -> if (conversation != null || answers.isNotEmpty()) judge() else setContext(ContextUi.Done(null))
            else -> Unit
        }
    }

    fun contextNeverAsk() {
        g.settings.contextNeverAsk = true
        setContext(ContextUi.Done(null))
    }

    /** [상황 직접 입력]: always available, also while still checking. */
    fun openDirectInput() {
        if (_state.value.context is ContextUi.Checking || _state.value.context is ContextUi.Judging) contextJob?.cancel()
        setContext(ContextUi.Ask(FollowupContracts.catalog.freeInput.defaultQuestion, userInitiated = true))
    }

    fun submitAnswer(questionId: String, chipIds: List<String>, text: String) {
        val limit = FollowupContracts.catalog.limits.statementChars
        answers += UserAnswer(questionId, chipIds, text.take(limit))
        refreshCloudContext()
        if (lastLite == null || _state.value.phase == Phase.RUNNING) {
            _state.update { it.copy(contextPending = true) } // applied when the verdict is in
            setContext(ContextUi.Idle)
            return
        }
        judge()
    }

    private fun judge() {
        val res = lastLite ?: return
        val f = res.features
        val rescore = res.rescore
        if (f == null || rescore == null) { setContext(ContextUi.Done(null, note = "unavailable")); return }
        setContext(ContextUi.Judging)
        val s = _state.value
        contextJob = scope.launch {
            val msg = ContextJudge.turn3Message(s.source.category, res.pageDomain, res.report.level, f, conversationApp ?: s.source.name?.en, conversation, answers)
            val out = try { g.gemma.chat(FollowupContracts.contextPrompt, msg) }
                catch (c: CancellationException) { throw c } catch (t: Throwable) { Log.w(TAG, "context turn 3 failed: ${t.message}"); null }
            val j = out?.let { ContextJudge.parse(it.text, ContextJudge.evidenceSources(conversation, answers)) }
            val ctx = ContextJudge.toInput(j, answers, conversation)
            if (j != null) deviceJudgment = CloudContext.DeviceJudgment(ctx.scenario, ctx.consistency, ctx.senderRisk, ctx.guided)
            val step = TraceStep(
                (System.currentTimeMillis() - contextT0).coerceAtLeast(0), "context",
                "source=${s.source.category} conv=${conversation?.size ?: 0} answers=${answers.size} · scenario=${ctx.scenario} " +
                    "consistency=${ctx.consistency} sender=${ctx.senderRisk} guided=${ctx.guided} · ${out?.latencyMs ?: "-"} ms",
            )
            val before = _state.value.lite
            val next = rescore(ctx, step)
            // v4 context rules only add; never let a context answer lower a verdict
            val applied = before == null || next.score >= before.score
            if (applied) {
                g.reports.add(next)
                if (!next.isVerifiedSafe) next.registrableDomain?.let { g.safeVerdictCache.remove(it) }
                else cacheSafeWhenSettled()
                _state.update { it.copy(lite = next) }
            }
            val added = if (applied && before != null) next.score - before.score else 0
            setContext(ContextUi.Done(ContextSummary(ctx.scenario, ctx.evidence, added, conversation != null, answers.isNotEmpty(), conversationApp == PASTED_APP)))
            refreshCloudContext()
            onUpdate(_state.value)
        }
    }

    companion object {
        /** Sender / app label of a pasted message inside <untrusted_conversation>. */
        const val PASTED_SENDER = "(message pasted by the user)"
        const val PASTED_APP = "pasted by the user"
        private const val TAG = "RedFlagContext"
        /** Context is asked at most once per site per process (plan §0). */
        private val askedHosts = java.util.Collections.synchronizedSet(HashSet<String>())
    }
}
