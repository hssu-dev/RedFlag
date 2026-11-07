package dev.redflag.core.report

import dev.redflag.core.model.Cloaking
import dev.redflag.core.model.EngineInfo
import dev.redflag.core.model.Features
import dev.redflag.core.model.FormSignal
import dev.redflag.core.model.ReportSignals
import dev.redflag.core.model.RiskReport
import dev.redflag.core.model.TraceStep
import dev.redflag.core.scoring.ScoreResult
import dev.redflag.core.scoring.ScoringEngine
import dev.redflag.core.scoring.ScoringInput
import dev.redflag.core.scoring.ScoringSignals
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Assembles a RiskReport (contracts/risk_report.schema.json). Score fields always come from the rule engine. */
object ReportBuilder {
    const val STATUS_COMPLETE = "complete"
    const val STATUS_TIMEOUT = "timeout"
    const val STATUS_ERROR = "error"

    val LITE_ENGINE = EngineInfo(model = "gemma-4-e4b", runtime = "litert-lm", think = false)

    /** ISO-8601 UTC, second precision ("2026-11-05T01:15:11Z"). */
    fun isoUtc(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))

    /** report.signals from the scoring signals + the deterministic extras; forms get sensitive_fields. */
    fun reportSignals(
        engine: ScoringEngine,
        signals: ScoringSignals,
        tlsIssuer: String? = null,
        certAgeDays: Int? = null,
        redirectCount: Int = 0,
        externalLinkDomains: List<String>? = null,
    ): ReportSignals = ReportSignals(
        domainAgeDays = signals.domainAgeDays,
        tlsIssuer = tlsIssuer,
        certAgeDays = certAgeDays,
        safeBrowsing = signals.safeBrowsing,
        redirectCount = redirectCount,
        punycode = signals.punycode,
        homoglyph = signals.homoglyph,
        forms = signals.forms.map { f ->
            FormSignal(actionDomain = f.actionDomain, sameSite = f.sameSite ?: true, sensitiveFields = engine.sensitiveCategories(f))
        },
        externalLinkDomains = externalLinkDomains,
    )

    /** Low-level: everything supplied by the caller. */
    fun build(
        mode: String,
        status: String,
        url: String,
        finalUrl: String,
        registrableDomain: String?,
        score: ScoreResult,
        signals: ReportSignals,
        features: Features?,
        cloaking: Cloaking = Cloaking(false, null),
        trace: List<TraceStep> = emptyList(),
        engine: EngineInfo = LITE_ENGINE,
        latencyMs: Long = 0,
        id: String = UUID.randomUUID().toString(),
        createdAt: Instant = Instant.now(),
    ): RiskReport = RiskReport(
        schemaVersion = "1",
        id = id,
        mode = mode,
        status = status,
        url = url,
        finalUrl = finalUrl,
        registrableDomain = registrableDomain,
        score = score.score,
        level = score.level,
        escalate = score.escalate,
        headline = score.headline,
        redFlags = score.redFlags,
        brand = score.brand,
        signals = signals,
        features = features,
        cloaking = cloaking,
        trace = trace,
        engine = engine,
        latencyMs = latencyMs.coerceAtLeast(0),
        createdAt = isoUtc(createdAt),
    )

    /** Convenience: scores `input` with `engine` and assembles the report. */
    fun build(
        engine: ScoringEngine,
        input: ScoringInput,
        url: String,
        finalUrl: String = url,
        status: String = if (input.features != null) STATUS_COMPLETE else STATUS_TIMEOUT,
        tlsIssuer: String? = null,
        certAgeDays: Int? = null,
        redirectCount: Int = 0,
        externalLinkDomains: List<String>? = null,
        cloakingDetail: String? = null,
        trace: List<TraceStep> = emptyList(),
        engineInfo: EngineInfo = LITE_ENGINE,
        latencyMs: Long = 0,
        id: String = UUID.randomUUID().toString(),
        createdAt: Instant = Instant.now(),
    ): RiskReport {
        // Fail-safe (decision D3): whatever the caller passed, an unfinished analysis is scored with the
        // analysis_incomplete clamp, so a timeout/error report can never come out "safe".
        val effective = if (status != STATUS_COMPLETE) input.copy(analysisIncomplete = true) else input
        return buildScored(engine, effective, url, finalUrl, status, tlsIssuer, certAgeDays, redirectCount,
            externalLinkDomains, cloakingDetail, trace, engineInfo, latencyMs, id, createdAt)
    }

    private fun buildScored(
        engine: ScoringEngine, input: ScoringInput, url: String, finalUrl: String, status: String,
        tlsIssuer: String?, certAgeDays: Int?, redirectCount: Int, externalLinkDomains: List<String>?,
        cloakingDetail: String?, trace: List<TraceStep>, engineInfo: EngineInfo, latencyMs: Long, id: String,
        createdAt: Instant,
    ): RiskReport = build(
        mode = input.mode,
        status = status,
        url = url,
        finalUrl = finalUrl,
        registrableDomain = input.registrableDomain,
        score = engine.score(input),
        signals = reportSignals(engine, input.signals, tlsIssuer, certAgeDays, redirectCount, externalLinkDomains),
        features = input.features,
        cloaking = Cloaking(input.cloakingSuspected, cloakingDetail),
        trace = trace,
        engine = engineInfo,
        latencyMs = latencyMs,
        id = id,
        createdAt = createdAt,
    )
}

/**
 * The ONLY condition under which RedFlag may treat a page as safe (pass it to Chrome, cache it,
 * allow-list it): the analysis finished AND the rule engine said safe.
 */
val RiskReport.isVerifiedSafe: Boolean get() = status == ReportBuilder.STATUS_COMPLETE && level == "safe"
