package dev.redflag.core.gate

import dev.redflag.core.ContractBundle
import dev.redflag.core.Contracts

/**
 * Finds the links in text the user pasted or shared (in-app link check, docs/plans/manual_url_check.md §2).
 * Contract: contracts/url_extract_vectors.json. Pure and offline (regex + PSL; no network).
 *
 * - http(s) URLs, `www.` and bare ASCII domains whose TLD is a listed public suffix (`3.5`, `photo.jpg` are not links)
 * - Korean written right after a link ends it (`https://a.kr/x에서` -> `https://a.kr/x`); non-ASCII hosts (IDN) only after a scheme
 * - trailing punctuation and unbalanced closing brackets are dropped
 * - defanged forms are re-armed: hxxp(s)://, [.], (.), [dot], (dot), [:]
 * - other schemes (javascript:, intent:, ftp:, file:, data:), emails, loopback / link-local / localhost are skipped
 * - at most [MAX_URLS] unique links (by normalized URL), in order of appearance
 */
object UrlExtractor {
    const val MAX_INPUT = 4000
    const val MAX_URLS = 5

    data class Extracted(
        /** Links as written (re-armed; https:// added when there was no scheme). */
        val urls: List<String>,
        /** The whole text when it says more than just the link(s) (used as pasted-message context), else null. */
        val message: String?,
        val defanged: Boolean,
    )

    private val DEFANG = listOf(
        Regex("(?i)\\bhxxps://") to "https://",
        Regex("(?i)\\bhxxp://") to "http://",
        Regex("(?i)\\[\\.]|\\(\\.\\)|\\[dot]|\\(dot\\)") to ".",
        Regex("\\[:]") to ":",
    )

    /** scheme URL: host (any non-space, IDN allowed) then path; trimmed afterwards */
    private val SCHEME_URL = Regex("(?i)(?<![A-Za-z0-9+.-])https?://[^\\s<>\"'`]+")

    /** bare domain (ASCII only), optional port and path */
    private val BARE = Regex(
        "(?<![A-Za-z0-9@./:_%-])((?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63})" +
            "(:[0-9]{1,5})?([/?#][^\\s<>\"'`]*)?",
    )

    private const val TRAILING = ".,;:!?'\"*~"

    fun extract(text: String, bundle: ContractBundle = Contracts.load()): Extracted {
        val original = text.take(MAX_INPUT)
        var armed = original
        for ((re, to) in DEFANG) armed = re.replace(armed, to)
        val defanged = armed != original

        data class Hit(val start: Int, val end: Int, val url: String)
        val hits = ArrayList<Hit>()
        val taken = BooleanArray(armed.length)

        for (m in SCHEME_URL.findAll(armed)) {
            val cut = trimScheme(m.value)
            if (cut.length <= m.value.indexOf("://") + 3) continue
            hits += Hit(m.range.first, m.range.first + cut.length, cut)
            for (i in m.range.first until m.range.first + cut.length) taken[i] = true
        }
        for (m in BARE.findAll(armed)) {
            if (taken[m.range.first]) continue
            val host = m.groupValues[1]
            val hostEnd = m.range.first + host.length
            if (hostEnd < armed.length && armed[hostEnd] == '@') continue // the user part of an email
            val tld = host.substringAfterLast('.').lowercase()
            if (!bundle.psl.isListedTld(tld)) continue
            val cut = trimPath(m.value)
            hits += Hit(m.range.first, m.range.first + cut.length, "https://$cut")
            for (i in m.range.first until m.range.first + cut.length) taken[i] = true
        }
        hits.sortBy { it.start }

        val seen = HashSet<String>()
        val urls = ArrayList<String>()
        for (h in hits) {
            val n = UrlNormalizer.normalize(h.url, bundle) as? NormalizedUrl ?: continue
            if (isLocal(n)) continue
            if (seen.add(n.url)) urls += h.url
            if (urls.size == MAX_URLS) break
        }

        // the text "says more" than its links: >= 2 letters/digits outside every link
        var rest = 0
        for (i in armed.indices) if (!taken[i] && armed[i].isLetterOrDigit()) rest++
        val message = original.trim().takeIf { rest >= 2 && urls.isNotEmpty() }
        return Extracted(urls, message, defanged)
    }

    /**
     * At most [limit] characters of [text] around [url] (a long pasted thread: keep the part with the link).
     * The link starts about a third of the way into the window.
     */
    fun messageWindow(text: String, url: String, limit: Int): String {
        if (text.length <= limit) return text
        val host = url.substringAfter("://").substringBefore('/')
        val at = text.indexOf(url, ignoreCase = true).takeIf { it >= 0 }
            ?: text.indexOf(host, ignoreCase = true).takeIf { it >= 0 && host.isNotEmpty() } ?: 0
        val start = (at - limit / 3).coerceIn(0, text.length - limit)
        return text.substring(start, start + limit)
    }

    private fun isLocal(n: NormalizedUrl): Boolean {
        val h = n.host
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h == "[::1]" || h.startsWith("[fe80:")) return true
        if (!n.isIp) return false
        val first = h.substringBefore('.').toIntOrNull() ?: return false
        val second = h.split('.').getOrNull(1)?.toIntOrNull()
        return first == 127 || first == 0 || (first == 169 && second == 254)
    }

    private fun isHangul(c: Char) = c in '가'..'힣' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'

    /** Scheme URL: the host may be IDN until it has an ASCII TLD; the path ends at the first non-ASCII character. */
    private fun trimScheme(raw: String): String {
        val schemeEnd = raw.indexOf("://") + 3
        var i = schemeEnd
        var sawAsciiTld = false
        while (i < raw.length) {
            val c = raw[i]
            if (c == '/' || c == '?' || c == '#') break
            if (c.code > 0x7E && (sawAsciiTld || !c.isLetterOrDigit())) return finish(raw.substring(0, i)) // "a.kr에서", "▶"
            if (i > schemeEnd && raw[i - 1] == '.' && (c in 'a'..'z' || c in 'A'..'Z')) sawAsciiTld = true
            i++
        }
        return finish(raw.substring(0, i) + raw.substring(i).takeWhile { it.code in 0x21..0x7E })
    }

    private fun trimPath(raw: String): String {
        val slash = raw.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (slash < 0) return finish(raw)
        return finish(raw.substring(0, slash) + raw.substring(slash).takeWhile { it.code in 0x21..0x7E })
    }

    /** Drops trailing punctuation and closing brackets that have no opener inside the link. */
    private fun finish(s: String): String {
        var t = s
        while (t.isNotEmpty()) {
            val c = t.last()
            val drop = when (c) {
                in TRAILING -> true
                ')' -> t.count { it == ')' } > t.count { it == '(' }
                ']' -> t.count { it == ']' } > t.count { it == '[' }
                '}' -> t.count { it == '}' } > t.count { it == '{' }
                '>' -> true
                else -> false
            }
            if (!drop) break
            t = t.dropLast(1)
        }
        return t
    }
}
