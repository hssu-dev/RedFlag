package dev.redflag.app.lite

import dev.redflag.core.followup.PageStateTurn
import android.util.Log
import android.view.ViewGroup
import dev.redflag.app.demo.DemoHosts
import dev.redflag.core.ContractBundle
import dev.redflag.core.scoring.ScoringEngine
import dev.redflag.core.gate.GateResult
import dev.redflag.core.gate.NormalizedUrl
import dev.redflag.core.gemma.GemmaPipeline
import dev.redflag.core.gemma.ObservationSignals
import dev.redflag.core.gemma.PromptBuilder
import dev.redflag.core.model.EngineInfo
import dev.redflag.core.model.Features
import dev.redflag.core.model.PageObservation
import dev.redflag.core.model.RiskReport
import dev.redflag.core.model.TraceStep
import dev.redflag.core.report.ReportBuilder
import dev.redflag.core.scoring.FormInput
import dev.redflag.core.scoring.ScoringInput
import dev.redflag.core.scoring.ScoringSignals
import dev.redflag.core.scoring.ContextInput
import dev.redflag.core.followup.Destinations
import dev.redflag.core.followup.FollowupContracts
import dev.redflag.core.model.DataEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class StepId { GATE, SAFE_BROWSING, EXTRACT, SIGNALS, GEMMA, SCORE }
enum class StepStatus { PENDING, RUNNING, DONE, SKIPPED, FAILED }

fun interface ProgressSink { fun onStep(step: StepId, status: StepStatus, detail: String?) }

/** What the phone saw, sent to Cloud so the backend can detect cloaking (no page text leaves). */
data class LiteObservation(val claimedBrand: String?, val hasCredentialForm: Boolean, val title: String?, val liteScore: Int?)

data class LiteResult(
    val report: RiskReport,
    val observation: LiteObservation,
    val screenshotPath: String?,
    val rawModelText: String?,
    /** Turn 1 answer (null if the model step failed) and facts the context step needs. */
    val features: Features? = null,
    val pageDomain: String? = null,
    val domainAgeDays: Int? = null,
    val safeBrowsingHit: Boolean = false,
    /** Same inputs + a context judgment -> a new report with the SAME id (context only adds; v4 rules). */
    val rescore: ((ContextInput, TraceStep) -> RiskReport)? = null,
)

/**
 * Lite verdict: the page is read once its DOM is ready (however slow the site), then Gemma (a few s).
 * Gemma is a witness: it only fills the 10 fields;
 * the shared rule engine (contracts/scoring_rules.json) scores.
 */
/** Seams for the Lite pipeline (real implementations in AppGraph; fakes in the fail-safe tests). */
interface PageExtractor {
    suspend fun extract(
        url: String, host: ViewGroup?, timeoutMs: Long = 5_000, shotName: String? = null,
        /** how long to wait for a CDN browser check to pass by itself (short when Chrome's screen can be read) */
        challengeWaitMs: Long = ExtractTiming.CHALLENGE_WAIT_MS,
    ): Extraction
}

interface DomainSignalSource {
    /** Must not throw for "no data"; a lookup FAILURE is reported via DomainSignals.lookupFailed. */
    suspend fun collect(registrableDomain: String): DomainSignals
}

class LiteDeps(
    val bundle: ContractBundle,
    val scoring: ScoringEngine,
    val demoHosts: () -> DemoHosts?,
    val extractor: PageExtractor,
    val signals: DomainSignalSource,
    val gemma: () -> GemmaEngine,
    val safeBrowsingSource: String = "stub",
)

class LiteEngine(private val d: LiteDeps) {
    private val extractor get() = d.extractor

    suspend fun analyze(
        originalUrl: String,
        gate: GateResult,
        host: ViewGroup?,
        progress: ProgressSink,
        timeoutMs: Long = TIMEOUT_MS,
        /**
         * Late verdict: if given, a pipeline still running at [timeoutMs] keeps going in [lateScope]
         * (up to [LATE_LIMIT_MS]); the timeout report is returned now and [onLate] gets the real one.
         */
        lateScope: CoroutineScope? = null,
        onLate: ((LiteResult) -> Unit)? = null,
        /**
         * Reads the page Chrome is showing (accessibility). Used when the hidden WebView did not get the site's
         * own page (CDN check that did not pass, block, cloaking, unreachable) — or first, with [screenOnly].
         */
        screenReader: PageExtractor? = null,
        screenOnly: Boolean = false,
        /** With [screenOnly]: why the hidden WebView already failed on this link (hand-off from the link handler). */
        hiddenFailure: String? = null,
    ): LiteResult {
        val t0 = System.currentTimeMillis()
        val trace = ArrayList<TraceStep>()
        fun mark(step: StepId, status: StepStatus, detail: String?) {
            if (status != StepStatus.RUNNING) trace += TraceStep(System.currentTimeMillis() - t0, step.name.lowercase(), detail ?: status.name.lowercase())
            progress.onStep(step, status, detail)
        }
        val n: NormalizedUrl = when (gate) {
            is GateResult.Unknown -> gate.normalized
            is GateResult.Pass -> gate.normalized
            is GateResult.Block -> gate.normalized
            is GateResult.Ignore -> error("ignored URLs are never analyzed")
        }
        mark(StepId.GATE, StepStatus.DONE, gateDetail(gate))

        val sbWire = if (gate is GateResult.Block) "hit" else when (gate) {
            is GateResult.Unknown -> gate.safeBrowsing.wire
            is GateResult.Pass -> gate.safeBrowsing.wire
            else -> "unknown"
        }
        mark(StepId.SAFE_BROWSING, StepStatus.DONE, when (sbWire) {
            "hit" -> "listed · ${d.safeBrowsingSource}"
            "no_match" -> "not listed · ${d.safeBrowsingSource}"
            else -> "unavailable · ${d.safeBrowsingSource}"
        })

        if (gate is GateResult.Block) { // Safe Browsing hit: immediate critical, no page load at all
            listOf(StepId.EXTRACT, StepId.SIGNALS, StepId.GEMMA).forEach { mark(it, StepStatus.SKIPPED, "skipped") }
            val report = score(originalUrl, n, null, sbWire, null, emptyList(), null, trace, t0, d.gemma().info, complete = true)
            mark(StepId.SCORE, StepStatus.DONE, "${report.score}")
            return LiteResult(report, LiteObservation(null, false, null, report.score), null, null, safeBrowsingHit = true)
        }

        val demo = d.demoHosts()?.match(originalUrl) ?: d.demoHosts()?.match(n.url)
        val fetchUrl = demo?.localUrl ?: n.url
        val reportUrl = demo?.fakeUrl ?: originalUrl
        val registrable = demo?.fakeHost ?: n.registrableDomain

        var page: PageObservation? = null
        var domain: DomainSignals? = null
        var redirects = 0
        var shot: String? = null
        var features: Features? = null
        var raw: String? = null
        var extractFailed = false
        var modelFailed = false      // engine/parse failure: no usable model answer
        var repaired = false         // FeatureRepair invented a value (D2 v3: floor 30 if core / dirty signals)
        var coreRepaired = false     // ... and it was claimed_brand / brand_matches_domain / requested_actions
        var signalsFailed = false    // RDAP lookup failed (network/timeout/5xx): analysis incomplete (D3)
        var endpointUrls: List<String> = emptyList() // v4: where page data goes (code rules)
        var formDest: Destinations.Answer? = null      // v4: Gemma's destination relation (adds only)

        var pipelineError: String? = null
        // Android WebView's own Safe Browsing (same on-device protection that makes Chrome warn) refused the page
        var webSbThreat: String? = null
        // the site's host/CDN served its own phishing warning to us (e.g. Cloudflare "Suspected Phishing")
        var providerWarning: String? = null
        // the site REFUSED the hidden WebView (same phone, same network) yet shows Chrome a page with inputs
        var cloaked = false
        val work: suspend () -> Boolean = {
            try { coroutineScope {
                mark(StepId.EXTRACT, StepStatus.RUNNING, null)
                mark(StepId.SIGNALS, StepStatus.RUNNING, null)
                val ex = async {
                    if (screenOnly && screenReader != null) screenReader.extract(fetchUrl, host, timeoutMs = SCREEN_MS)
                    else extractor.extract(fetchUrl, host, timeoutMs = EXTRACT_MS, shotName = null /* no page screenshot: nothing reads it, and drawing + JPEG-encoding a full page blocked the UI thread */,
                        challengeWaitMs = if (screenReader != null) SCREEN_CHALLENGE_WAIT_MS else ExtractTiming.CHALLENGE_WAIT_MS)
                }
                val sig = async {
                    val o = demo?.host?.signalOverrides
                    if (o != null) DomainSignals(o.domainAgeDays, o.tlsIssuer, o.certAgeDays, "demo_override")
                    else registrable?.takeIf { !n.isIp }?.let { d.signals.collect(it) } ?: DomainSignals(null, null, null, "none")
                }
                domain = sig.await().also {
                    signalsFailed = it.lookupFailed
                    mark(StepId.SIGNALS, if (it.lookupFailed) StepStatus.FAILED else StepStatus.DONE, describeSignals(it))
                }
                var extraction = ex.await()
                if (extraction.observation == null && extraction.error?.startsWith("load_error") == true) {
                    // one retry with the other scheme: Chrome's url_bar hides it, and its "Not secure" label does
                    // not reliably mean http (an https page with an insecure form gets it too)
                    val other = when {
                        fetchUrl.startsWith("https://") -> "http://" + fetchUrl.removePrefix("https://")
                        fetchUrl.startsWith("http://") -> "https://" + fetchUrl.removePrefix("http://")
                        else -> null
                    }
                    if (other != null) {
                        mark(StepId.EXTRACT, StepStatus.RUNNING, "retry ${other.substringBefore("://")}")
                        extraction = extractor.extract(other, host, timeoutMs = EXTRACT_MS, shotName = null)
                    }
                }
                var fromScreen = screenOnly && screenReader != null
                // ③ the hidden WebView did not get the site's own page: read what Chrome shows the user instead
                suspend fun readScreen(why: String): Boolean {
                    if (fromScreen || screenReader == null) return false
                    mark(StepId.EXTRACT, StepStatus.RUNNING, "reading Chrome's screen ($why)")
                    val s = screenReader.extract(fetchUrl, host, timeoutMs = SCREEN_MS)
                    trace += TraceStep(System.currentTimeMillis() - t0, "screen_read", s.observation?.let { "ok · ${it.visibleText.length} chars · ${it.counts.inputs} inputs" } ?: (s.error ?: "nothing"))
                    // a page, or a warning that is itself the verdict (the host's phishing page, Chrome's "Dangerous site")
                    val verdictWarning = s.error?.let { it.startsWith(OffscreenExtractor.PROVIDER_WARNING) || it.startsWith(OffscreenExtractor.SB_HIT) } == true
                    if (s.observation == null && !verdictWarning) return false
                    extraction = s.copy(redirectCount = extraction.redirectCount)
                    fromScreen = true
                    return true
                }
                fun isRefusal(e: String?) = e == "${OffscreenExtractor.PAGE_STATE}:${PageState.BLOCKED.wire}" || e == "http_401" || e == "http_403"
                val refused = isRefusal(extraction.error) || (fromScreen && isRefusal(hiddenFailure))
                if (fromScreen && hiddenFailure != null) trace += TraceStep(System.currentTimeMillis() - t0, "hidden_browser", hiddenFailure)
                if ((screenFallback(extraction) && readScreen(extraction.error ?: "no page")) || fromScreen) {
                    cloaked = refused && extraction.observation?.forms?.any { it.fields.isNotEmpty() } == true
                    if (cloaked) trace += TraceStep(System.currentTimeMillis() - t0, "cloaking", "hidden browser refused, Chrome shows a form: different page for scanners")
                }

                redirects = extraction.redirectCount
                shot = extraction.screenshot?.absolutePath
                page = extraction.observation?.let { obs -> if (demo != null) obs.copy(finalUrl = reportUrl) else obs }
                if (page == null && extraction.error?.startsWith(OffscreenExtractor.SB_HIT) == true) {
                    webSbThreat = extraction.error!!.removePrefix(OffscreenExtractor.SB_HIT).trimStart(':')
                    mark(StepId.SAFE_BROWSING, StepStatus.DONE, "listed · Android Safe Browsing (${webSbThreat})")
                    mark(StepId.EXTRACT, StepStatus.SKIPPED, "blocked by Safe Browsing")
                    return@coroutineScope false
                }
                if (page == null && extraction.error?.startsWith(OffscreenExtractor.PROVIDER_WARNING) == true) {
                    providerWarning = extraction.error!!.removePrefix(OffscreenExtractor.PROVIDER_WARNING).trimStart(':')
                    mark(StepId.EXTRACT, StepStatus.DONE, "host warning page · $providerWarning")
                    return@coroutineScope false
                }
                if (page == null) {
                    mark(StepId.EXTRACT, StepStatus.FAILED, extraction.error)
                    extractFailed = true
                    return@coroutineScope false
                }
                // second opinion from Gemma only when code signatures are unsure (error status / almost no content)
                val codeState = PageStates.code(extraction.httpStatus, page!!.title, page!!.visibleText, page!!.forms.size)
                if (GEMMA_PAGE_STATE && PageStates.ambiguous(extraction.httpStatus, page!!.visibleText, page!!.forms.size, codeState)) {
                    val p = page!!
                    val out = runCatching {
                        d.gemma().chat(FollowupContracts.pageStatePrompt,
                            PageStateTurn.message(extraction.httpStatus, p.title, p.visibleText, p.forms.size,
                                p.forms.any { f -> f.fields.any { it.type == "password" } }), maxTokens = 90)
                    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
                    val said = out?.let { PageStateTurn.parse(it.text) }?.let { PageState.of(it) }
                    trace += TraceStep(System.currentTimeMillis() - t0, "page_state", "gemma=${said?.wire ?: "none"} code=${codeState.wire} http=${extraction.httpStatus ?: "-"} · ${out?.latencyMs ?: "-"} ms")
                    if (said == PageState.CHALLENGE || said == PageState.BLOCKED || said == PageState.UNAVAILABLE || said == PageState.HOST_WARNING) {
                        // Gemma may only make RedFlag look again or say "couldn't verify" (never adds/removes points)
                        if (readScreen("gemma: ${said.wire}")) {
                            page = extraction.observation
                        } else {
                            mark(StepId.EXTRACT, StepStatus.FAILED, "${OffscreenExtractor.PAGE_STATE}:${if (said == PageState.HOST_WARNING) PageState.BLOCKED.wire else said.wire}")
                            extractFailed = true
                            return@coroutineScope false
                        }
                    }
                }
                if (page == null) { extractFailed = true; return@coroutineScope false }
                mark(StepId.EXTRACT, StepStatus.DONE, (if (fromScreen) "read from Chrome's screen · " else "") + describePage(page!!))

                mark(StepId.GEMMA, StepStatus.RUNNING, null)
                val input = PromptBuilder.buildObservation(
                    page!!,
                    ObservationSignals(reportUrl, redirects, domain?.domainAgeDays, domain?.tlsIssuer, domain?.certAgeDays, sbWire),
                    d.bundle,
                )
                val user = PromptBuilder.buildUserMessage(input)
                var plan: GemmaPipeline.FollowUpPlan? = null
                val out = d.gemma().generate(d.bundle.systemPrompt, user, hostHint = reportUrl) { first ->
                    // at most ONE follow-up, only for keys still missing after salvage (never a same-input retry)
                    GemmaPipeline.followUpFor(d.bundle, first).also { plan = it }?.prompt
                }
                raw = out.text
                val interp = GemmaPipeline.interpret(out.text, out.followupText, plan?.missing ?: emptyList())
                features = interp.features
                repaired = interp.repaired
                coreRepaired = interp.coreRepaired
                if (interp.salvageNotes.isNotEmpty()) trace += TraceStep(System.currentTimeMillis() - t0, "gemma_salvage", interp.salvageNotes.joinToString(", "))
                if (interp.asked.isNotEmpty()) trace += TraceStep(
                    System.currentTimeMillis() - t0, "gemma_followup",
                    "asked ${interp.asked.joinToString(",")} · ${out.followupLatencyMs ?: 0} ms · still missing ${interp.missingAfter.size}",
                )
                if (interp.repairs.isNotEmpty()) trace += TraceStep(System.currentTimeMillis() - t0, "gemma_repair", interp.repairs.joinToString(", "))
                if (features == null) {
                    modelFailed = true
                    Log.w(TAG, "gemma output unusable (no keys recovered)")
                }
                val eps = page!!.dataEndpoints.map { it.copy(url = mapDemo(it.url, demo)) }
                endpointUrls = eps.map { it.url }
                features?.let { f -> formDest = askDestination(f, eps, registrable, page!!, trace, t0) }
                mark(
                    StepId.GEMMA,
                    if (features != null) StepStatus.DONE else StepStatus.FAILED,
                    features?.let { f -> "brand=${f.claimedBrand ?: "none"} · ${out.totalLatencyMs} ms · ${out.backend}" } ?: "unusable answer",
                )
                true
            } } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                // engine/WebView failures become an "error" report (couldn't verify), never a crash or a "safe"
                Log.w(TAG, "lite pipeline failed", t)
                pipelineError = t.message ?: t::class.java.simpleName
                if (features == null) mark(StepId.GEMMA, StepStatus.FAILED, pipelineError)
                false
            }
        }

        var lateJob: Deferred<Boolean?>? = null
        val finished: Boolean = if (lateScope != null && onLate != null) {
            val job = lateScope.async { withTimeoutOrNull(LATE_LIMIT_MS) { work() } }
            val inTime = withTimeoutOrNull(timeoutMs) { job.await() }
            if (inTime == null && job.isActive) lateJob = job
            inTime ?: false
        } else {
            withTimeoutOrNull(timeoutMs) { work() } ?: false
        }

        if (providerWarning != null) { // floor 95 (provider_phishing_warning), like Safe Browsing
            mark(StepId.GEMMA, StepStatus.SKIPPED, "skipped")
            val report = score(reportUrl, n.copy(registrableDomain = registrable), null, sbWire, domain, emptyList(), null, trace, t0, d.gemma().info,
                complete = true, providerWarning = providerWarning)
            mark(StepId.SCORE, StepStatus.DONE, "${report.score}")
            return LiteResult(report, LiteObservation(null, false, null, report.score), null, null)
        }
        if (webSbThreat != null) { // same verdict as a gate Safe Browsing hit: immediate critical
            mark(StepId.GEMMA, StepStatus.SKIPPED, "skipped")
            val report = score(reportUrl, n.copy(registrableDomain = registrable), null, "hit", domain, emptyList(), null, trace, t0, d.gemma().info, complete = true)
            mark(StepId.SCORE, StepStatus.DONE, "${report.score}")
            return LiteResult(report, LiteObservation(null, false, null, report.score), null, null, safeBrowsingHit = true)
        }

        fun buildResult(finished: Boolean): LiteResult {
        val pageDomain = registrable
        val forms = page?.let { PromptBuilder.scoringForms(it, pageDomain, d.bundle) } ?: emptyList()
        val external = page?.let { PromptBuilder.externalLinkDomains(it, pageDomain, d.bundle) }
        val report = score(
            reportUrl, n.copy(registrableDomain = registrable), features, sbWire, domain, forms, external,
            trace, t0, d.gemma().info, complete = finished && features != null, redirects = redirects,
            status = when {
                // fail-safe (D3): "complete" only if every step finished, including the RDAP lookup
                finished && features != null && !signalsFailed -> "complete"
                extractFailed || modelFailed || signalsFailed || pipelineError != null -> "error"
                else -> "timeout"
            },
            repaired = repaired,
            coreRepaired = coreRepaired,
            hiddenText = page?.hiddenText,
            dataEndpoints = endpointUrls,
            formDestination = formDest,
            cloaking = cloaked,
        )
        mark(StepId.SCORE, StepStatus.DONE, "${report.score} · ${report.level}")
        val obs = LiteObservation(
            claimedBrand = features?.claimedBrand,
            hasCredentialForm = report.signals.forms.any { it.sensitiveFields.isNotEmpty() },
            title = page?.title,
            liteScore = report.score,
        )
        val complete = report.status == "complete"
        val rescore: ((ContextInput, TraceStep) -> RiskReport)? = if (!complete) null else { ctx, step ->
            score(
                reportUrl, n.copy(registrableDomain = registrable), features, sbWire, domain, forms, external,
                report.trace + step, t0, d.gemma().info, complete = true, redirects = redirects, status = "complete",
                repaired = repaired, coreRepaired = coreRepaired, hiddenText = page?.hiddenText,
                dataEndpoints = endpointUrls, formDestination = formDest, context = ctx, reportId = report.id, cloaking = cloaked,
            )
        }
        return LiteResult(
            report, obs, shot, raw, features = features, pageDomain = registrable, domainAgeDays = domain?.domainAgeDays,
            safeBrowsingHit = sbWire == "hit", rescore = rescore,
        )
        }

        val first = buildResult(finished)
        lateJob?.invokeOnCompletion { cause ->
            if (cause != null) return@invokeOnCompletion
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            val ok = lateJob.getCompleted() == true
            if (ok) lateScope?.launch {
                Log.i(TAG, "late verdict after ${System.currentTimeMillis() - t0} ms")
                onLate?.invoke(buildResult(true))
            }
        }
        return first
    }

    private fun score(
        url: String, n: NormalizedUrl, features: Features?, sb: String, domain: DomainSignals?, forms: List<FormInput>,
        external: List<String>?, trace: List<TraceStep>, t0: Long, engine: EngineInfo, complete: Boolean, redirects: Int = 0,
        status: String = if (complete) "complete" else "timeout",
        repaired: Boolean = false,
        coreRepaired: Boolean = false,
        hiddenText: String? = null,
        dataEndpoints: List<String> = emptyList(),
        formDestination: Destinations.Answer? = null,
        context: ContextInput? = null,
        reportId: String? = null,
        providerWarning: String? = null,
        cloaking: Boolean = false,
    ): RiskReport {
        val input = ScoringInput(
            mode = "lite",
            registrableDomain = n.registrableDomain,
            features = features,
            signals = ScoringSignals(sb, domain?.domainAgeDays, n.punycode, n.homoglyph, forms, hiddenText = hiddenText, dataEndpoints = dataEndpoints, providerWarning = providerWarning),
            cloakingSuspected = cloaking,
            featuresRepaired = repaired,
            coreFieldsRepaired = coreRepaired,
            analysisIncomplete = status != "complete",
            formDestination = formDestination?.relation,
            formDestinationDomain = formDestination?.domain,
            context = context,
        )
        return ReportBuilder.build(
            d.scoring, input, url = url, finalUrl = url,
            status = status,
            tlsIssuer = domain?.tlsIssuer, certAgeDays = domain?.certAgeDays, redirectCount = redirects,
            externalLinkDomains = external, trace = trace.toList(), engineInfo = engine,
            latencyMs = System.currentTimeMillis() - t0,
            id = reportId ?: java.util.UUID.randomUUID().toString(),
        )
    }

    /**
     * Turn D (docs/plans/form_destination_observation.md): only when the page asks for personal data and sends it
     * to a cross-site destination the code does not already know. Own short conversation, no page text.
     * Any failure = no answer = nothing added.
     */
    private suspend fun askDestination(
        f: Features, eps: List<DataEndpoint>, pageDomain: String?, page: PageObservation, trace: MutableList<TraceStep>, t0: Long,
    ): Destinations.Answer? {
        val forms = PromptBuilder.scoringForms(page, pageDomain, d.bundle)
        val personal = d.scoring.buildFacts(
            ScoringInput(registrableDomain = pageDomain, features = f, signals = ScoringSignals(forms = forms)),
        )["personal_request"] == true
        // eps already holds the form actions (extract_page.js data_endpoints, via=form), demo-mapped
        val cands = Destinations.candidates(eps, pageDomain, personal, d.bundle.scoringRules) { PromptBuilder.domainOf(it, d.bundle) }
        if (cands.isEmpty()) return null
        return try {
            val out = d.gemma().chat(FollowupContracts.destinationPrompt, Destinations.userMessage(pageDomain, f, cands))
            Destinations.parseAnswer(out.text, cands).also { a ->
                trace += TraceStep(System.currentTimeMillis() - t0, "gemma_destination",
                    "${cands.joinToString(",") { it.domain }} → ${a?.let { "${it.relation} (${it.domain})" } ?: "no answer"} · ${out.latencyMs} ms")
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "destination turn failed: ${t.message}")
            trace += TraceStep(System.currentTimeMillis() - t0, "gemma_destination", "failed: ${t.message}")
            null
        }
    }

    /** Demo pages are served locally: map their own endpoints back onto the fake host. */
    private fun mapDemo(u: String, demo: DemoHosts.Match?): String {
        if (demo == null) return u
        val origin = Regex("^https?://[^/?#]+").find(demo.localUrl)?.value ?: return u
        return if (u.startsWith(origin)) "https://${demo.fakeHost}" + u.removePrefix(origin) else u
    }

    private fun gateDetail(gate: GateResult) = when (gate) {
        is GateResult.Unknown -> when (gate.reason) {
            "shortener" -> "short link"
            "ugc" -> "user-generated content host"
            "ip_host" -> "IP address host"
            else -> "unfamiliar domain"
        }
        is GateResult.Block -> "Safe Browsing hit"
        is GateResult.Pass -> "known site"
        is GateResult.Ignore -> gate.reason
    }

    private fun describeSignals(s: DomainSignals) = buildList {
        if (s.lookupFailed) add("registry lookup failed")
        s.domainAgeDays?.let { add("registered $it days ago") }
        s.tlsIssuer?.let { add(it) }
        if (isEmpty()) add("no registry data")
    }.joinToString(" · ")

    private fun describePage(p: PageObservation): String {
        val hidden = if (p.hiddenText.isNotBlank()) "hidden text found" else "no hidden text"
        return "${p.forms.size} form${if (p.forms.size == 1) "" else "s"} · $hidden"
    }

    companion object {
        private const val TAG = "RedFlagLite"
        /**
         * No user-facing time limit any more: the waiting screen offers "skip check and open" instead.
         * This is only a ceiling against a hung runtime (the report then says "couldn't verify").
         */
        const val TIMEOUT_MS = 90_000L
        /**
         * Ceiling for a page that never becomes ready. The page is read as soon as its DOM is ready (slow servers
         * and slow networks are waited for; user decision 2026-11-04), and the user can always skip the check.
         */
        const val EXTRACT_MS = 30_000L
        /** How long a timed-out Lite run may keep going to deliver a late verdict (covers a slow page read + Gemma). */
        const val LATE_LIMIT_MS = TIMEOUT_MS
        /** Reading Chrome's screen: Chrome may still be loading or passing a CDN check itself. */
        const val SCREEN_MS = 15_000L
        /** Under the Chrome cover a CDN check is left to Chrome itself (it is passing it right there). */
        const val SCREEN_CHALLENGE_WAIT_MS = 3_000L
        /** Gemma page-state second opinion (eval/page_state/REPORT.md). */
        const val GEMMA_PAGE_STATE = false // measured: no gain, misjudges fake-CAPTCHA phishing (eval/page_state/REPORT.md)

        /** The hidden WebView did not get the site's own page and Chrome's screen may show it. */
        fun screenFallback(e: Extraction): Boolean {
            if (e.observation != null) return false
            val err = e.error ?: return true
            return err.startsWith(OffscreenExtractor.PAGE_STATE) || err.startsWith("http_") || err.startsWith("load_error") ||
                err in setOf("timeout", "extract_failed", "empty_page")
        }
    }
}
