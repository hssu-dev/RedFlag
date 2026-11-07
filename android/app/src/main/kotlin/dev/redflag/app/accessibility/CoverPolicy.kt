package dev.redflag.app.accessibility

import dev.redflag.app.analysis.AnalysisUi
import dev.redflag.app.analysis.ContextUi
import dev.redflag.app.analysis.Phase
import dev.redflag.core.report.isVerifiedSafe

/** What kind of input field the user just tapped in Chrome's page. */
enum class InputFocus { NONE, TEXT, PASSWORD }

/** The small status-bar pill shown while a page is checked (docs/superpowers/specs/2026-11-05-check-pill-design.md §2). */
enum class PillStyle { CHECKING, SAFE, CAUTION, UNVERIFIED, QUESTION }

enum class FullReason {
    /** Gate Block, or a site RedFlag already judged dangerous (revisit). */
    KNOWN_BAD,
    /** The verdict is danger / critical (Lite, context re-score or cloud). */
    DANGER,
    /** Settings: "cover the whole screen while checking" (the old behaviour). */
    SETTING,
    /** The user tapped an input field before the page was verified safe. */
    INPUT_GUARD,
    /** The user tapped the pill. */
    USER,
}

sealed interface CoverMode {
    data class Pill(val style: PillStyle) : CoverMode
    /** [collapsible]: offers "show small" (never on a danger screen). */
    data class Full(val reason: FullReason, val collapsible: Boolean) : CoverMode
}

/**
 * Pure: how the Chrome cover is shown. Full screen only when there is danger, when the user asks for it, or when
 * the user is about to type into a page that is not verified safe. Never decides "safe": that stays
 * [isVerifiedSafe] (D3 — a failed or unfinished check never ends green).
 */
object CoverPolicy {

    data class Input(
        val knownBad: Boolean,
        val fullCoverSetting: Boolean,
        val result: Boolean,
        val level: String?,
        val unverified: Boolean,
        val verifiedSafe: Boolean,
        /** a context question / consent card is waiting for the user */
        val contextCard: Boolean,
        /** the context step (Gemma) is still working on a verdict that is already in */
        val contextBusy: Boolean,
        val guardTripped: Boolean,
        val userExpanded: Boolean,
    ) {
        companion object {
            fun of(ui: AnalysisUi, knownBad: Boolean, fullCoverSetting: Boolean, guardTripped: Boolean, userExpanded: Boolean): Input {
                val result = ui.phase == Phase.RESULT && ui.lite != null
                return Input(
                    knownBad = knownBad || ui.revisit,
                    fullCoverSetting = fullCoverSetting,
                    result = result,
                    level = if (result) ui.displayLevel else null,
                    unverified = ui.unverified,
                    verifiedSafe = result && ui.displayLevel == "safe" && ui.lite?.isVerifiedSafe == true,
                    contextCard = ui.context is ContextUi.Consent || ui.context is ContextUi.Ask,
                    contextBusy = ui.context is ContextUi.Checking || ui.context is ContextUi.Judging,
                    guardTripped = guardTripped,
                    userExpanded = userExpanded,
                )
            }
        }
    }

    private val DANGER_LEVELS = setOf("danger", "critical")

    fun mode(i: Input): CoverMode = when {
        i.knownBad -> CoverMode.Full(FullReason.KNOWN_BAD, collapsible = false)
        i.level in DANGER_LEVELS -> CoverMode.Full(FullReason.DANGER, collapsible = false)
        i.fullCoverSetting -> CoverMode.Full(FullReason.SETTING, collapsible = false)
        i.guardTripped && !i.verifiedSafe -> CoverMode.Full(FullReason.INPUT_GUARD, collapsible = true)
        i.userExpanded -> CoverMode.Full(FullReason.USER, collapsible = true)
        else -> CoverMode.Pill(style(i))
    }

    fun style(i: Input): PillStyle = when {
        !i.result -> PillStyle.CHECKING
        i.contextCard -> PillStyle.QUESTION
        i.contextBusy -> PillStyle.CHECKING
        i.unverified -> PillStyle.UNVERIFIED
        i.verifiedSafe -> PillStyle.SAFE
        i.level == "caution" -> PillStyle.CAUTION
        else -> PillStyle.UNVERIFIED // anything not proven safe is never shown green
    }

    /**
     * Does tapping this field expand the cover? While checking: any field. Once a not-safe verdict is in
     * (caution, unverified, a question): password fields only — 45% of ordinary sites come out "caution"
     * (eval), so guarding every search box would bring the full-screen cover back.
     */
    fun guardTrips(focus: InputFocus, i: Input): Boolean = when {
        focus == InputFocus.NONE || i.verifiedSafe -> false
        !i.result || i.contextBusy -> true
        else -> focus == InputFocus.PASSWORD
    }
}
