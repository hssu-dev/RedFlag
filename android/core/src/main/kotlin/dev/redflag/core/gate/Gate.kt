package dev.redflag.core.gate

import dev.redflag.core.ContractBundle

sealed interface GateResult {
    val reason: String

    /** Known-good. reason: brand | tranco | cache */
    data class Pass(
        val normalized: NormalizedUrl,
        override val reason: String,
        val safeBrowsing: SafeBrowsingResult = SafeBrowsingResult.UNKNOWN,
    ) : GateResult

    /** Safe Browsing hit: block immediately (critical). */
    data class Block(
        val normalized: NormalizedUrl,
        override val reason: String = SAFE_BROWSING,
    ) : GateResult

    /** Needs analysis. reason: unknown_domain | shortener | ugc | ip_host | no_registrable_domain */
    data class Unknown(
        val normalized: NormalizedUrl,
        override val reason: String,
        val safeBrowsing: SafeBrowsingResult = SafeBrowsingResult.UNKNOWN,
    ) : GateResult

    /** Not a web URL. reason: not_a_url | unsupported_scheme */
    data class Ignore(override val reason: String) : GateResult

    companion object {
        const val BRAND = "brand"
        const val TRANCO = "tranco"
        const val CACHE = "cache"
        const val SAFE_BROWSING = "safe_browsing"
        const val UNKNOWN_DOMAIN = "unknown_domain"
        const val SHORTENER = "shortener"
        const val UGC = "ugc"
        const val IP_HOST = "ip_host"
        const val NO_REGISTRABLE_DOMAIN = "no_registrable_domain"
    }
}

/** The normalized URL of any non-Ignore result. */
val GateResult.normalizedOrNull: NormalizedUrl?
    get() = when (this) {
        is GateResult.Pass -> normalized
        is GateResult.Block -> normalized
        is GateResult.Unknown -> normalized
        is GateResult.Ignore -> null
    }

/**
 * On-device gate (gate_lists.json doc): normalize -> Safe Browsing -> shortener/UGC exceptions ->
 * IP / no-registrable-domain -> known-good (brand, Tranco, safe-verdict cache) -> unknown_domain.
 * Synchronous; the SafeBrowsingClient may block (call off the main thread with a live client).
 */
class Gate(
    private val bundle: ContractBundle,
    private val knownGood: KnownGoodSet,
    private val cache: SafeVerdictCache,
    private val safeBrowsing: SafeBrowsingClient = StubSafeBrowsing(bundle.gateLists.safeBrowsingStub),
) {
    private val shorteners = bundle.gateLists.shorteners.map { it.lowercase() }.toSet()
    private val brandDomains = bundle.brands.allDomains

    /** [useSafeCache] false: the in-app link check wants a fresh look even at a site cached as safe. */
    fun evaluate(raw: String, nowMs: Long = System.currentTimeMillis(), useSafeCache: Boolean = true): GateResult {
        val n = when (val o = UrlNormalizer.normalize(raw, bundle)) {
            is NormalizeIgnore -> return GateResult.Ignore(o.reason)
            is NormalizedUrl -> o
        }
        return evaluate(n, nowMs, useSafeCache)
    }

    fun evaluate(n: NormalizedUrl, nowMs: Long = System.currentTimeMillis(), useSafeCache: Boolean = true): GateResult {
        val sb = safeBrowsing.check(n)
        if (sb == SafeBrowsingResult.HIT) return GateResult.Block(n)

        if (isShortener(n.host)) return GateResult.Unknown(n, GateResult.SHORTENER, sb)
        if (isUgc(n)) return GateResult.Unknown(n, GateResult.UGC, sb)
        if (n.isIp) return GateResult.Unknown(n, GateResult.IP_HOST, sb)
        val reg = n.registrableDomain ?: return GateResult.Unknown(n, GateResult.NO_REGISTRABLE_DOMAIN, sb)

        if (reg in brandDomains) return GateResult.Pass(n, GateResult.BRAND, sb)
        if (knownGood.contains(reg)) return GateResult.Pass(n, GateResult.TRANCO, sb)
        if (useSafeCache && cache.isSafe(reg, nowMs)) return GateResult.Pass(n, GateResult.CACHE, sb)
        return GateResult.Unknown(n, GateResult.UNKNOWN_DOMAIN, sb)
    }

    fun isShortener(host: String): Boolean = host in shorteners

    fun isUgc(n: NormalizedUrl): Boolean = bundle.gateLists.ugc.any { rule ->
        val h = rule.host.lowercase()
        val hostOk = when (rule.match) {
            "suffix" -> n.host == h || n.host.endsWith(".$h")
            else -> n.host == h
        }
        hostOk && rule.paths.any { p -> p == "/" || n.path.startsWith(p) }
    }
}
