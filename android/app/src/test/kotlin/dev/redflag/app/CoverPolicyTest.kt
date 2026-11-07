package dev.redflag.app

import dev.redflag.app.accessibility.CoverMode
import dev.redflag.app.accessibility.CoverPolicy
import dev.redflag.app.accessibility.FullReason
import dev.redflag.app.accessibility.InputFocus
import dev.redflag.app.accessibility.PillStyle
import dev.redflag.app.analysis.AnalysisUi
import dev.redflag.app.analysis.ContextUi
import dev.redflag.app.analysis.Phase
import dev.redflag.core.Contracts
import dev.redflag.core.model.Features
import dev.redflag.core.report.ReportBuilder
import dev.redflag.core.scoring.ScoringEngine
import dev.redflag.core.scoring.ScoringInput
import dev.redflag.core.scoring.ScoringSignals
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** docs/superpowers/specs/2026-11-05-check-pill-design.md §2–§3: when the Chrome cover is a pill and when it is full screen. */
class CoverPolicyTest {
    private val engine = ScoringEngine(Contracts.load())
    private val plain = Features(
        evidence = emptyList(), claimedBrand = null, brandMatchesDomain = "no_brand", pageType = "other",
        requestedActions = listOf("none"), urgencyOrThreat = false, tooGoodToBeTrue = false, addressesAiAnalyzer = false,
        verdict = "safe", confidence = "high",
    )
    private val input = ScoringInput("lite", "plain-site.com", plain, ScoringSignals("no_match", 4000))
    private val safe = ReportBuilder.build(engine, input, url = "https://plain-site.com/", status = ReportBuilder.STATUS_COMPLETE)
    private val timeout = ReportBuilder.build(engine, input, url = "https://plain-site.com/", status = ReportBuilder.STATUS_TIMEOUT)
    private val caution = safe.copy(level = "caution", score = 40)
    private val danger = safe.copy(level = "danger", score = 70)
    private val critical = safe.copy(level = "critical", score = 95)

    private val running = AnalysisUi(url = "https://plain-site.com/", host = "plain-site.com")
    private fun result(r: dev.redflag.core.model.RiskReport, ctx: ContextUi = ContextUi.Idle) = running.copy(phase = Phase.RESULT, lite = r, context = ctx)

    private fun input(ui: AnalysisUi, knownBad: Boolean = false, setting: Boolean = false, guard: Boolean = false, expanded: Boolean = false) =
        CoverPolicy.Input.of(ui, knownBad, setting, guard, expanded)

    private fun mode(ui: AnalysisUi, knownBad: Boolean = false, setting: Boolean = false, guard: Boolean = false, expanded: Boolean = false) =
        CoverPolicy.mode(input(ui, knownBad, setting, guard, expanded))

    @Test fun checkingIsAPill() = assertEquals(CoverMode.Pill(PillStyle.CHECKING), mode(running))

    @Test fun verifiedSafeIsAGreenPill() = assertEquals(CoverMode.Pill(PillStyle.SAFE), mode(result(safe)))

    @Test fun cautionStaysAPill() = assertEquals(CoverMode.Pill(PillStyle.CAUTION), mode(result(caution)))

    @Test fun dangerAndCriticalGoFullScreenAndCannotBeCollapsed() {
        for (r in listOf(danger, critical)) {
            assertEquals(CoverMode.Full(FullReason.DANGER, collapsible = false), mode(result(r)))
            // even after the user had collapsed / expanded it, or tripped the guard
            assertEquals(CoverMode.Full(FullReason.DANGER, collapsible = false), mode(result(r), guard = true, expanded = true))
        }
    }

    @Test fun knownBadAndRevisitAreFullFromTheStart() {
        assertEquals(CoverMode.Full(FullReason.KNOWN_BAD, false), mode(running, knownBad = true))
        assertEquals(CoverMode.Full(FullReason.KNOWN_BAD, false), mode(result(danger).copy(revisit = true)))
    }

    @Test fun aContextQuestionIsAPillThatDoesNotExpandByItself() {
        val ask = ContextUi.Ask("Q1", userInitiated = false)
        assertEquals(CoverMode.Pill(PillStyle.QUESTION), mode(result(caution, ask)))
        assertEquals(CoverMode.Pill(PillStyle.QUESTION), mode(result(safe, ask)))
    }

    @Test fun contextStepStillWorkingShowsChecking() {
        assertEquals(CoverMode.Pill(PillStyle.CHECKING), mode(result(safe, ContextUi.Checking)))
        assertEquals(CoverMode.Pill(PillStyle.CHECKING), mode(result(caution, ContextUi.Judging)))
    }

    /** D3: an unfinished / failed check is never a green pill. */
    @Test fun unfinishedCheckIsNeverGreen() {
        val m = mode(result(timeout))
        assertEquals(CoverMode.Pill(PillStyle.UNVERIFIED), m)
        assertNotEquals(CoverMode.Pill(PillStyle.SAFE), mode(result(timeout.copy(level = "safe"))))
        assertFalse(input(result(timeout.copy(level = "safe"))).verifiedSafe)
    }

    @Test fun tappingThePillExpandsAndCanCollapse() {
        assertEquals(CoverMode.Full(FullReason.USER, collapsible = true), mode(running, expanded = true))
        assertEquals(CoverMode.Full(FullReason.USER, collapsible = true), mode(result(caution), expanded = true))
    }

    @Test fun settingKeepsTheOldFullScreenCover() {
        assertEquals(CoverMode.Full(FullReason.SETTING, false), mode(running, setting = true))
        assertEquals(CoverMode.Full(FullReason.SETTING, false), mode(result(caution), setting = true))
    }

    @Test fun inputGuardWhileCheckingTripsOnAnyField() {
        val i = input(running)
        assertTrue(CoverPolicy.guardTrips(InputFocus.TEXT, i))
        assertTrue(CoverPolicy.guardTrips(InputFocus.PASSWORD, i))
        assertFalse(CoverPolicy.guardTrips(InputFocus.NONE, i))
        assertEquals(CoverMode.Full(FullReason.INPUT_GUARD, collapsible = true), mode(running, guard = true))
    }

    @Test fun inputGuardAfterANotSafeVerdictTripsOnPasswordsOnly() {
        for (ui in listOf(result(caution), result(timeout), result(caution, ContextUi.Ask("Q1", false)))) {
            val i = input(ui)
            assertFalse(CoverPolicy.guardTrips(InputFocus.TEXT, i), "$ui")
            assertTrue(CoverPolicy.guardTrips(InputFocus.PASSWORD, i), "$ui")
        }
    }

    @Test fun inputGuardNeverTripsOnAVerifiedSafePageAndIsReleasedBySafe() {
        val i = input(result(safe))
        assertFalse(CoverPolicy.guardTrips(InputFocus.PASSWORD, i))
        // tripped while checking, then the verdict came in safe: back to the (green) pill
        assertEquals(CoverMode.Pill(PillStyle.SAFE), mode(result(safe), guard = true))
    }
}
