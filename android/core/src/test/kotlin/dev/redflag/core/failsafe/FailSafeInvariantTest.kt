package dev.redflag.core.failsafe

import dev.redflag.core.Contracts
import dev.redflag.core.gemma.FeatureRepair
import dev.redflag.core.gemma.GemmaSalvage
import dev.redflag.core.gemma.GemmaOutputParser
import dev.redflag.core.model.Features
import dev.redflag.core.report.ReportBuilder
import dev.redflag.core.report.isVerifiedSafe
import dev.redflag.core.scoring.FormField
import dev.redflag.core.scoring.FormInput
import dev.redflag.core.scoring.ScoringEngine
import dev.redflag.core.scoring.ScoringInput
import dev.redflag.core.scoring.ScoringSignals
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decision D3 (core side): no failure path may produce SAFE.
 *  - unfinished analysis (timeout / error: model missing, load failure, inference failure, cancellation)
 *    -> analysis_incomplete clamp -> never "safe", never isVerifiedSafe
 *  - parser failure -> no features at all (cannot be repaired into a verdict)
 *  - repaired output (D2) -> exactly caution unless a Safe Browsing floor makes it critical
 * Exhaustive over a grid of benign-looking and risky inputs, so the property does not depend on one example.
 */
class FailSafeInvariantTest {
    private val bundle = Contracts.load()
    private val engine = ScoringEngine(bundle)

    private fun f(brand: String?, bmd: String, actions: List<String>, verdict: String = "safe") = Features(
        evidence = emptyList(), claimedBrand = brand, brandMatchesDomain = bmd, pageType = "other",
        requestedActions = actions, urgencyOrThreat = false, tooGoodToBeTrue = false, addressesAiAnalyzer = false,
        verdict = verdict, confidence = "high",
    )

    private val featureGrid: List<Features?> = listOf(
        null,
        f(null, "no_brand", listOf("none")),
        f("Hanaroom Bakery", "yes", listOf("enter_contact_info")),
        f("DBS", "yes", listOf("enter_password")),
        f("DBS", "no", listOf("enter_password"), "malicious"),
    )
    private val pw = FormInput("x-collect.top", false, listOf(FormField("password", "Password", null, null)), null)
    private val signalGrid = listOf(
        ScoringSignals("no_match", 4000),
        ScoringSignals("unknown", null),
        ScoringSignals("no_match", 3),
        ScoringSignals("no_match", 3, forms = listOf(pw)),
        ScoringSignals("hit", null),
    )
    private val domains = listOf("plain-site.com", "dbs.com.sg", "dbs-alert.top")

    private fun inputs(): List<ScoringInput> = buildList {
        for (feats in featureGrid) for (sig in signalGrid) for (d in domains) add(ScoringInput("lite", d, feats, sig))
    }

    @Test
    fun controlSomeCompleteInputsAreSafe() {
        // sanity: the grid does contain inputs that are legitimately safe when the analysis completed
        assertTrue(inputs().any { engine.score(it).level == "safe" })
    }

    @Test
    fun unfinishedAnalysisIsNeverSafe() {
        var n = 0
        for (inp in inputs()) for (status in listOf(ReportBuilder.STATUS_TIMEOUT, ReportBuilder.STATUS_ERROR)) {
            // the caller "forgets" the flag on purpose: ReportBuilder must enforce it from the status
            val rep = ReportBuilder.build(engine, inp.copy(analysisIncomplete = false), url = "https://${inp.registrableDomain}/", status = status)
            assertNotEquals("safe", rep.level, "status=$status input=$inp")
            assertFalse(rep.isVerifiedSafe)
            assertTrue(rep.score >= 30)
            if (inp.signals.safeBrowsing != "hit") assertTrue(rep.redFlags.any { it.ruleId == "analysis_incomplete" })
            n++
        }
        assertEquals(inputs().size * 2, n)
    }

    @Test
    fun repairedOutput_v3_lowerBoundOnlyAndOnlyWhenCoreMissingOrSignalsDirty() {
        // D2 as corrected by the user: repaired output is never capped from above; it gets the 30 floor
        // only if a core field was repaired or a deterministic signal is not clean.
        for (inp in inputs()) {
            if (inp.features == null) continue
            val plain = engine.score(inp)
            val coreMissing = engine.score(inp.copy(featuresRepaired = true, coreFieldsRepaired = true))
            assertNotEquals("safe", coreMissing.level, "core repaired must never be safe: $inp")
            assertEquals(maxOf(plain.score, 30), coreMissing.score, "lower bound only: $inp")
            val coreIntact = engine.score(inp.copy(featuresRepaired = true))
            val sig = inp.signals
            val dirty = sig.safeBrowsing == "hit" || (sig.domainAgeDays ?: Int.MAX_VALUE) < 30 || sig.homoglyph ||
                engine.buildFacts(inp)["sensitive_form_cross_site"] == true
            if (dirty) assertEquals(maxOf(plain.score, 30), coreIntact.score, "dirty signals -> floor: $inp")
            else assertEquals(plain.score, coreIntact.score, "core intact + clean signals -> unchanged: $inp")
        }
    }

    @Test
    fun parserFailuresYieldNoFeatures() {
        val garbage = listOf(
            "", "   ", "I think this page is safe.", "{", "{\"verdict\": \"safe\"", "[1, 2, 3]",
            "```json\n{\"verdict\": \"safe\", }\n```", "<channel|>", "{\"evidence\": [], \"verdict\": \"safe\"",
        )
        for (g in garbage) {
            val p = GemmaOutputParser.parse(g)
            assertNull(p.features, "features from: $g")
            assertNull(p.obj, "object from: $g") // nothing for FeatureRepair to work on -> no model verdict
            // what the app does next: no features + not complete -> never safe
            val rep = ReportBuilder.build(engine, ScoringInput("lite", "plain-site.com", null, ScoringSignals("no_match", 4000)),
                url = "https://plain-site.com/", status = ReportBuilder.STATUS_ERROR)
            assertFalse(rep.isVerifiedSafe)
        }
    }

    @Test
    fun repairOfAnAnswerMissingCoreFieldsIsNotSafe() {
        // a "verdict: safe" answer that lacks claimed_brand / brand_matches_domain / requested_actions:
        // the defaults are invented, so the page cannot be called safe (v3 D2 floor)
        val p = GemmaOutputParser.parse("""{"evidence": [], "page_type": "info", "urgency_or_threat": false, "too_good_to_be_true": false, "addresses_ai_analyzer": false, "verdict": "safe", "confidence": "high"}""")
        assertNull(p.features)
        val fixed = FeatureRepair.repair(p.obj!!)
        assertTrue(GemmaSalvage.isCoreRepair(fixed.repairs))
        val r = engine.score(ScoringInput("lite", "plain-site.com", fixed.features, ScoringSignals("no_match", 4000),
            featuresRepaired = true, coreFieldsRepaired = true))
        assertEquals("caution", r.level)
    }

    @Test
    fun repairOfOnlyMissingFlagsWithCleanSignalsMayBeSafe() {
        // the dominant real-page case (a boolean flag missing): core intact + clean signals -> safe allowed (v3)
        val p = GemmaOutputParser.parse("""{"evidence": [], "claimed_brand": null, "brand_matches_domain": "no_brand", "page_type": "info", "requested_actions": ["none"], "verdict": "safe", "confidence": "high"}""")
        val fixed = FeatureRepair.repair(p.obj!!)
        assertFalse(GemmaSalvage.isCoreRepair(fixed.repairs))
        val r = engine.score(ScoringInput("lite", "plain-site.com", fixed.features, ScoringSignals("no_match", 4000), featuresRepaired = true))
        assertEquals("safe", r.level)
    }

    @Test
    fun onlyCompleteSafeIsVerifiedSafe() {
        val safeInput = ScoringInput("lite", "plain-site.com", f(null, "no_brand", listOf("none")), ScoringSignals("no_match", 4000))
        assertTrue(ReportBuilder.build(engine, safeInput, url = "https://plain-site.com/", status = ReportBuilder.STATUS_COMPLETE).isVerifiedSafe)
        assertFalse(ReportBuilder.build(engine, safeInput, url = "https://plain-site.com/", status = ReportBuilder.STATUS_TIMEOUT).isVerifiedSafe)
    }
}
