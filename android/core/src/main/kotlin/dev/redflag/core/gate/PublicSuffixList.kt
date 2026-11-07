package dev.redflag.core.gate

import java.net.IDN

/**
 * publicsuffix.org list (ICANN + PRIVATE sections), algorithm per https://github.com/publicsuffix/list/wiki/Format:
 * exception rules (`!`) win, otherwise the matching rule with the most labels, `*` is the implicit default.
 * Unicode rules are stored as punycode (java.net.IDN) so lookups take ASCII hosts.
 */
class PublicSuffixList private constructor(
    private val exact: Set<String>,
    /** "*.ck" stored as "ck". */
    private val wildcard: Set<String>,
    /** "!www.ck" stored as "www.ck". */
    private val exception: Set<String>,
) {
    val ruleCount: Int get() = exact.size + wildcard.size + exception.size

    /** eTLD+1 of an ASCII (punycode) host, or null for IPs-as-text, public suffixes and single labels. */
    fun registrableDomain(asciiHost: String): String? {
        val labels = labels(asciiHost) ?: return null
        val n = publicSuffixLabelCount(labels)
        if (labels.size <= n) return null
        return labels.subList(labels.size - n - 1, labels.size).joinToString(".")
    }

    /** The public suffix of the host (e.g. "co.kr", "github.io"), or null for malformed hosts. */
    fun publicSuffix(asciiHost: String): String? {
        val labels = labels(asciiHost) ?: return null
        val n = publicSuffixLabelCount(labels)
        if (labels.size < n) return null
        return labels.subList(labels.size - n, labels.size).joinToString(".")
    }

    /** True when [label] (ASCII, lowercase) is a top-level rule of the list ("com", "kr", "shop"), not just the implicit "*". */
    fun isListedTld(label: String): Boolean = label in exact || label in wildcard

    fun isPublicSuffix(asciiHost: String): Boolean {
        val labels = labels(asciiHost) ?: return false
        return publicSuffixLabelCount(labels) >= labels.size
    }

    private fun labels(host: String): List<String>? {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return null
        val labels = h.split('.')
        if (labels.any { it.isEmpty() }) return null
        return labels
    }

    private fun publicSuffixLabelCount(labels: List<String>): Int {
        val size = labels.size
        // exception rules take priority: public suffix = rule minus its leftmost label
        for (i in 0 until size) {
            val suffix = labels.subList(i, size).joinToString(".")
            if (suffix in exception) return size - i - 1
        }
        var best = 1 // implicit "*"
        for (i in 0 until size) {
            val suffix = labels.subList(i, size).joinToString(".")
            val len = size - i
            if (suffix in exact && len > best) best = len
            if (i > 0 && suffix in wildcard && len + 1 > best) best = len + 1
        }
        return best
    }

    companion object {
        fun parse(text: String): PublicSuffixList {
            val exact = HashSet<String>()
            val wildcard = HashSet<String>()
            val exception = HashSet<String>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("//")) continue
                val rule = line.split(' ', '\t').first().lowercase()
                when {
                    rule.startsWith("!") -> exception += toAscii(rule.substring(1)) ?: continue
                    rule.startsWith("*.") -> wildcard += toAscii(rule.substring(2)) ?: continue
                    else -> exact += toAscii(rule) ?: continue
                }
            }
            return PublicSuffixList(exact, wildcard, exception)
        }

        private fun toAscii(rule: String): String? =
            if (rule.all { it.code < 0x80 }) rule
            else runCatching { IDN.toASCII(rule, IDN.ALLOW_UNASSIGNED).lowercase() }.getOrNull()
    }
}
