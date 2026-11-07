package dev.redflag.core.gate

import dev.redflag.core.ContractBundle
import dev.redflag.core.Contracts
import java.net.IDN
import java.text.Normalizer

sealed interface NormalizeOutcome

/** A http(s) URL in canonical form. */
data class NormalizedUrl(
    /** scheme://host[:port]path[?query] — lowercase scheme/host, punycode host, no userinfo, default port and fragment dropped. */
    val url: String,
    /** "http" or "https" */
    val scheme: String,
    /** ASCII host: lowercase, punycode, no trailing dot. IPv6 kept in brackets. */
    val host: String,
    /** Explicit non-default port, else null. */
    val port: Int?,
    /** Path ("/" when empty), case preserved. */
    val path: String,
    /** eTLD+1 (PSL incl. private section); null for IPs, public suffixes and single labels. */
    val registrableDomain: String?,
    val isIp: Boolean,
    /** Some host label is an IDN (xn--). */
    val punycode: Boolean,
    /** Some IDN label is a Latin look-alike (gate_lists.json confusables). */
    val homoglyph: Boolean,
    /** "?query" or "" */
    val query: String = "",
) : NormalizeOutcome {
    /** Normalized form without scheme (Safe Browsing stub prefix matching). */
    val withoutScheme: String get() = url.substringAfter("://")
}

/** Not something the gate looks at. reason: not_a_url | unsupported_scheme */
data class NormalizeIgnore(val reason: String) : NormalizeOutcome

object UrlNormalizer {
    const val NOT_A_URL = "not_a_url"
    const val UNSUPPORTED_SCHEME = "unsupported_scheme"

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")
    private val FORBIDDEN_HOST_CHARS = " #%/:<>?@[\\]^|\"".toSet()

    fun normalize(raw: String): NormalizeOutcome = normalize(raw, Contracts.load())

    fun normalize(raw: String, bundle: ContractBundle): NormalizeOutcome {
        // WHATWG: strip leading/trailing C0 control+space, remove all ASCII tab/newline
        val input = raw.trim { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
        if (input.isEmpty()) return NormalizeIgnore(NOT_A_URL)

        val scheme: String
        val rest: String
        val m = SCHEME.find(input)
        val candidate = m?.groupValues?.get(1)?.lowercase()
        val afterColon = m?.let { input.substring(it.range.last + 1) }
        val looksLikeHostPort = candidate != null && afterColon != null &&
            !afterColon.startsWith("//") && candidate != "http" && candidate != "https" &&
            (afterColon.firstOrNull()?.isDigit() == true || candidate.contains('.'))
        if (candidate != null && afterColon != null && !looksLikeHostPort) {
            if (candidate != "http" && candidate != "https") return NormalizeIgnore(UNSUPPORTED_SCHEME)
            scheme = candidate
            rest = afterColon.trimStart('/', '\\')
        } else {
            // no scheme: "m.naver.com/news" -> https://, only if it has a dot and no spaces
            if (!input.contains('.') || input.any { Character.isWhitespace(it) || Character.isSpaceChar(it) }) {
                return NormalizeIgnore(NOT_A_URL)
            }
            scheme = "https"
            rest = input.trimStart('/', '\\')
        }

        // authority = up to the first / \ ? #
        val authEnd = rest.indexOfFirst { it == '/' || it == '\\' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        var authority = rest.substring(0, authEnd)
        val tail = rest.substring(authEnd)
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)

        // host / port
        val hostRaw: String
        val portRaw: String?
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return NormalizeIgnore(NOT_A_URL)
            hostRaw = authority.substring(0, close + 1)
            val after = authority.substring(close + 1)
            portRaw = when {
                after.isEmpty() -> null
                after.startsWith(":") -> after.substring(1)
                else -> return NormalizeIgnore(NOT_A_URL)
            }
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) {
                hostRaw = authority.substring(0, colon)
                portRaw = authority.substring(colon + 1)
            } else {
                hostRaw = authority
                portRaw = null
            }
        }
        var port: Int? = null
        if (!portRaw.isNullOrEmpty()) {
            if (!portRaw.all { it in '0'..'9' }) return NormalizeIgnore(NOT_A_URL)
            port = portRaw.trimStart('0').ifEmpty { "0" }.let { if (it.length > 5) return NormalizeIgnore(NOT_A_URL) else it.toInt() }
            if (port > 65535) return NormalizeIgnore(NOT_A_URL)
        }
        if ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) port = null

        val hostInfo = normalizeHost(hostRaw, bundle) ?: return NormalizeIgnore(NOT_A_URL)

        // path / query (fragment dropped)
        val noFrag = tail.substringBefore('#')
        val qIdx = noFrag.indexOf('?')
        var path = if (qIdx >= 0) noFrag.substring(0, qIdx) else noFrag
        val query = if (qIdx >= 0) noFrag.substring(qIdx) else ""
        path = path.replace('\\', '/')
        if (path.isEmpty()) path = "/"

        val host = hostInfo.host
        val reg = if (hostInfo.isIp) null else bundle.psl.registrableDomain(host)
        val url = buildString {
            append(scheme).append("://").append(host)
            if (port != null) append(':').append(port)
            append(path).append(query)
        }
        return NormalizedUrl(
            url = url, scheme = scheme, host = host, port = port, path = path,
            registrableDomain = reg, isIp = hostInfo.isIp, punycode = hostInfo.punycode,
            homoglyph = hostInfo.homoglyph, query = query,
        )
    }

    private class HostInfo(val host: String, val isIp: Boolean, val punycode: Boolean, val homoglyph: Boolean)

    private fun normalizeHost(hostRaw: String, bundle: ContractBundle): HostInfo? {
        if (hostRaw.isEmpty()) return null
        if (hostRaw.startsWith("[")) {
            val inner = hostRaw.substring(1, hostRaw.length - 1).lowercase()
            if (inner.isEmpty() || !inner.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' } || !inner.contains(':')) return null
            return HostInfo("[$inner]", isIp = true, punycode = false, homoglyph = false)
        }
        val decoded = percentDecode(hostRaw) ?: return null
        var h = Normalizer.normalize(decoded, Normalizer.Form.NFKC).lowercase()
        if (h.any { it in FORBIDDEN_HOST_CHARS || it < ' ' || it == '\u007f' }) return null
        if (h.any { it.code >= 0x80 }) {
            h = try {
                IDN.toASCII(h, IDN.ALLOW_UNASSIGNED).lowercase()
            } catch (e: IllegalArgumentException) {
                return null
            }
        }
        h = h.trimEnd('.')
        if (h.isEmpty()) return null
        val labels = h.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return null
        if (endsInNumber(labels)) {
            if (!validIpv4(labels)) return null
            return HostInfo(h, isIp = true, punycode = false, homoglyph = false)
        }
        val puny = labels.any { it.startsWith("xn--") }
        return HostInfo(h, isIp = false, punycode = puny, homoglyph = puny && isHomoglyph(labels, bundle.gateLists.confusables.map))
    }

    /** gate_lists.json confusables.doc */
    internal fun isHomoglyph(asciiLabels: List<String>, map: Map<String, String>): Boolean {
        val cps = HashMap<Int, String>()
        for ((k, v) in map) if (k.codePointCount(0, k.length) == 1) cps[k.codePointAt(0)] = v
        for (label in asciiLabels) {
            if (!label.startsWith("xn--")) continue
            val uni = runCatching { IDN.toUnicode(label, IDN.ALLOW_UNASSIGNED) }.getOrNull() ?: continue
            if (uni.all { it.code < 0x80 }) continue
            val mapped = StringBuilder()
            var hasLatin = false
            var hasCyrGreek = false
            var i = 0
            while (i < uni.length) {
                val cp = uni.codePointAt(i)
                mapped.append(cps[cp] ?: String(Character.toChars(cp)))
                if (cp in 'a'.code..'z'.code) hasLatin = true
                if (cp in 0x0400..0x04FF || cp in 0x0370..0x03FF) hasCyrGreek = true
                i += Character.charCount(cp)
            }
            if (mapped.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return true
            if (hasLatin && hasCyrGreek) return true
        }
        return false
    }

    private fun percentDecode(s: String): String? {
        if (!s.contains('%')) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val v = if (hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) hex.toInt(16) else null
                if (v != null) { out.write(v); i += 3; continue }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
        }.getOrNull()
    }

    // ---- WHATWG IPv4 ("ends in a number" + number parser), host text itself is kept as given

    private fun endsInNumber(labels: List<String>): Boolean {
        val last = labels.last()
        if (last.isNotEmpty() && last.all { it in '0'..'9' }) return true
        return parseIpv4Number(last) != null
    }

    private fun parseIpv4Number(s: String): Long? {
        if (s.isEmpty()) return null
        var t = s
        var radix = 10
        if (t.length >= 2 && (t.startsWith("0x") || t.startsWith("0X"))) { t = t.substring(2); radix = 16 }
        else if (t.length >= 2 && t.startsWith("0")) { t = t.substring(1); radix = 8 }
        if (t.isEmpty()) return 0
        val ok = when (radix) {
            16 -> t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            8 -> t.all { it in '0'..'7' }
            else -> t.all { it in '0'..'9' }
        }
        if (!ok) return null
        return t.toBigInteger(radix).let { if (it.bitLength() > 40) Long.MAX_VALUE else it.toLong() }
    }

    private fun validIpv4(labels: List<String>): Boolean {
        if (labels.size > 4) return false
        val nums = labels.map { parseIpv4Number(it) ?: return false }
        if (nums.dropLast(1).any { it > 255 }) return false
        return nums.last() < (1L shl (8 * (5 - nums.size)))
    }
}
