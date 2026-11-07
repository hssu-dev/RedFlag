package dev.redflag.app.lite

/**
 * What the hidden browser actually received (eval/page_state, contracts/gemma_page_state.txt):
 * the site's own page, or something standing in front of it.
 */
enum class PageState(val wire: String) {
    LOADED("loaded"), CHALLENGE("challenge"), BLOCKED("blocked"), HOST_WARNING("host_warning"), UNAVAILABLE("unavailable");

    companion object {
        fun of(wire: String?): PageState? = entries.firstOrNull { it.wire == wire }
    }
}

/** Code signatures (no model). Pure; mirrors eval/page_state/run_page_state.py code_state. */
object PageStates {
    private val SUSPECTED = Regex("suspected (phishing|malware)", RegexOption.IGNORE_CASE)

    /** The host/CDN's own phishing/malware warning page: "cloudflare_phishing" | "cloudflare_malware" | null. */
    fun providerWarning(title: String?, text: String?): String? {
        val all = "${title.orEmpty()} ${text.orEmpty()}"
        val m = SUSPECTED.find(all) ?: return null
        if (!all.contains("cloudflare", ignoreCase = true)) return null
        return "cloudflare_" + m.groupValues[1].lowercase()
    }

    fun code(httpStatus: Int?, title: String?, text: String?, formCount: Int): PageState {
        val t = title.orEmpty().trim().lowercase()
        val x = text.orEmpty().lowercase()
        val st = httpStatus ?: 200
        if (providerWarning(title, text) != null) return PageState.HOST_WARNING
        if (formCount > 0) return PageState.LOADED // a page with inputs is judged as the site's page, whatever it claims
        if ("completing the action below" in x) return PageState.BLOCKED // interactive Turnstile: never passes by itself
        // a CDN check names its provider; a page that only SAYS "verify you are human" may be a fake CAPTCHA
        // (ClickFix-style phishing, e.g. eval/real mal_011): that one must be analysed, not waited out
        if (t == "just a moment..." ||
            (CHALLENGE_TEXT.any { it in x } && (PROVIDER_MARKS.any { it in x } || st in CHALLENGE_STATUS))) return PageState.CHALLENGE
        if (BLOCK_TEXT.any { it in x } || t == "403 forbidden") return PageState.BLOCKED
        if (st >= 500 || UNAVAILABLE_TEXT.any { it in "$t $x" }) return PageState.UNAVAILABLE
        if (st >= 400 && x.length < 200) return PageState.UNAVAILABLE
        return PageState.LOADED
    }

    /** Worth a second opinion from Gemma: an error status, or almost no content and no inputs. */
    fun ambiguous(httpStatus: Int?, text: String?, formCount: Int, codeState: PageState): Boolean =
        codeState == PageState.LOADED && formCount == 0 && ((httpStatus ?: 200) >= 400 || text.orEmpty().trim().length < 300)

    private val CHALLENGE_TEXT = listOf("checking your browser", "performing security verification", "verifying your browser", "verify you are human")
    private val CHALLENGE_STATUS = setOf(403, 429, 503)
    private val PROVIDER_MARKS = listOf("cloudflare", "ray id", "ddos-guard", "vercel", "sucuri", "imperva", "akamai")
    private val BLOCK_TEXT = listOf("you have been blocked", "access denied", "request blocked", "request is blocked", "incapsula incident", "mod security")
    private val UNAVAILABLE_TEXT = listOf("site not found", "isn't live", "account suspended", "has been suspended", "web server is down")
}
