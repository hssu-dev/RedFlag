package dev.redflag.app.lite

import dev.redflag.core.model.RiskReport

/** Why a check could not finish (from the report's "extract" trace step). Drives ④ wording and the ③ hand-off. */
object Unverified {
    enum class Reason { UNREACHABLE, BLOCKED, CHALLENGE, OTHER }

    fun reason(r: RiskReport?): Reason? {
        if (r == null || r.status == "complete") return null
        val d = r.trace.lastOrNull { it.step == "extract" }?.detail ?: return Reason.OTHER
        return when {
            d.startsWith("${OffscreenExtractor.PAGE_STATE}:${PageState.UNAVAILABLE.wire}") || d.startsWith("load_error") -> Reason.UNREACHABLE
            d.startsWith("${OffscreenExtractor.PAGE_STATE}:${PageState.CHALLENGE.wire}") -> Reason.CHALLENGE
            d.startsWith("${OffscreenExtractor.PAGE_STATE}:${PageState.BLOCKED.wire}") || d.startsWith("http_") -> Reason.BLOCKED
            else -> Reason.OTHER
        }
    }

    /** The page exists but RedFlag's hidden browser was kept out: Chrome's own screen can still be read. */
    fun chromeCanHelp(r: RiskReport?): Boolean = reason(r).let { it == Reason.BLOCKED || it == Reason.CHALLENGE }
}
