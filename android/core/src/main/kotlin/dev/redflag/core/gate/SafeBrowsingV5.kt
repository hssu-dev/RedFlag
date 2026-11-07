package dev.redflag.core.gate

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Google Safe Browsing v5 `hashes.search` (https://developers.google.com/safe-browsing/reference).
 * Privacy: only 4-byte SHA-256 prefixes of the URL's host-suffix/path-prefix expressions leave the
 * device; full hashes returned by Google are compared locally, so Google never sees the URL.
 * Responses are protobuf (verified against the live API: JSON output is rejected with
 * "Unsupported Output Format"). Never throws: network / quota / auth failures are UNKNOWN (an unknown result never makes a page safe).
 *
 * @param androidPackage / androidCertSha1 sent as X-Android-Package / X-Android-Cert so an API key
 *  restricted to the Android app (package + signing SHA-1) is accepted.
 */
class SafeBrowsingV5(
    private val apiKey: String,
    private val androidPackage: String? = null,
    private val androidCertSha1: String? = null,
    private val timeoutMs: Int = 2_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val transport: (url: String, headers: Map<String, String>, timeoutMs: Int) -> Pair<Int, ByteArray> = ::httpGet,
) : SafeBrowsingClient {

    /** full-hash cache from responses: prefix(base64) -> (expiry ms, threat full hashes) */
    private val cache = ConcurrentHashMap<String, Pair<Long, Set<String>>>()

    @Volatile var lastError: String? = null
        private set

    override fun check(url: NormalizedUrl): SafeBrowsingResult {
        if (url.isIp && url.host.startsWith("[")) return SafeBrowsingResult.UNKNOWN // IPv6 literals: not handled
        val exprs = expressions(url)
        val full = exprs.associateWith { sha256(it) }
        val fullB64 = full.values.map { b64(it) }.toSet()
        val now = clock()
        val prefixes = full.values.map { b64(it.copyOf(4)) }.distinct()

        val missing = prefixes.filter { p -> cache[p]?.first?.let { it > now } != true }
        if (missing.isNotEmpty()) {
            val q = missing.joinToString("&") { "hashPrefixes=" + URLEncoder.encode(it, "UTF-8") }
            val headers = buildMap {
                androidPackage?.let { put("X-Android-Package", it) }
                androidCertSha1?.let { put("X-Android-Cert", it) }
            }
            val (code, body) = try {
                transport("$ENDPOINT?key=${URLEncoder.encode(apiKey, "UTF-8")}&$q", headers, timeoutMs)
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
                return SafeBrowsingResult.UNKNOWN
            }
            if (code != 200) {
                lastError = "HTTP $code"
                return SafeBrowsingResult.UNKNOWN
            }
            val parsed = try { parse(body) } catch (e: Exception) {
                lastError = "bad response: ${e.message}"
                return SafeBrowsingResult.UNKNOWN
            }
            val expiry = now + parsed.first
            // cache every asked prefix (an empty answer is also cacheable, per the API docs)
            for (p in missing) {
                val hashesForP = parsed.second.filter { h -> b64(Base64.getDecoder().decode(h).copyOf(4)) == p }.toSet()
                cache[p] = expiry to hashesForP
            }
            lastError = null
        }
        val threats = prefixes.flatMap { cache[it]?.second ?: emptySet() }.toSet()
        return if (threats.any { it in fullB64 }) SafeBrowsingResult.HIT else SafeBrowsingResult.NO_MATCH
    }

    companion object {
        const val ENDPOINT = "https://safebrowsing.googleapis.com/v5/hashes:search"
        /** Threat types that block. Unknown/other types are ignored (the API asks clients to tolerate them). */

        fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

        /** v5 ThreatType enum values that block (1 MALWARE, 2 SOCIAL_ENGINEERING, 3 UNWANTED_SOFTWARE, 4 POTENTIALLY_HARMFUL_APPLICATION). */
        val BLOCKING_TYPE_IDS = setOf(1L, 2L, 3L, 4L)

        /**
         * SearchHashesResponse (application/x-protobuf; the v5 endpoint does not serve JSON):
         *   1 repeated FullHash { 1 bytes full_hash; 2 repeated FullHashDetail { 1 enum threat_type; ... } }
         *   2 Duration cache_duration { 1 int64 seconds; 2 int32 nanos }
         * Returns (cache duration ms, base64 full hashes with a blocking threat type). Unknown fields are skipped.
         */
        fun parse(body: ByteArray): Pair<Long, List<String>> {
            var durMs = 300_000L
            val hashes = ArrayList<String>()
            Proto(body).forEach { field, wire, value ->
                when {
                    field == 1 && wire == 2 -> {
                        var full: ByteArray? = null
                        var blocking = false
                        Proto(value as ByteArray).forEach { f2, w2, v2 ->
                            if (f2 == 1 && w2 == 2) full = v2 as ByteArray
                            if (f2 == 2 && w2 == 2) Proto(v2 as ByteArray).forEach { f3, w3, v3 ->
                                if (f3 == 1 && w3 == 0 && (v3 as Long) in BLOCKING_TYPE_IDS) blocking = true
                            }
                        }
                        val fh = full
                        if (blocking && fh != null && fh.size == 32) hashes += b64(fh)
                    }
                    field == 2 && wire == 2 -> Proto(value as ByteArray).forEach { f2, w2, v2 ->
                        if (f2 == 1 && w2 == 0) durMs = (v2 as Long) * 1000
                    }
                }
            }
            return durMs to hashes
        }

        /** Minimal protobuf reader: varint (wire 0), length-delimited (2), fixed64 (1), fixed32 (5). */
        private class Proto(private val b: ByteArray) {
            private var i = 0
            private fun varint(): Long {
                var shift = 0
                var r = 0L
                while (true) {
                    val x = b[i++].toInt() and 0xff
                    r = r or ((x and 0x7f).toLong() shl shift)
                    if (x < 0x80) return r
                    shift += 7
                    require(shift < 64) { "varint too long" }
                }
            }
            fun forEach(fn: (field: Int, wire: Int, value: Any) -> Unit) {
                while (i < b.size) {
                    val key = varint()
                    val field = (key ushr 3).toInt()
                    when (val wire = (key and 7).toInt()) {
                        0 -> fn(field, wire, varint())
                        2 -> { val len = varint().toInt(); require(len >= 0 && i + len <= b.size) { "bad length" }; fn(field, wire, b.copyOfRange(i, i + len)); i += len }
                        1 -> i += 8
                        5 -> i += 4
                        else -> throw IllegalArgumentException("wire type $wire")
                    }
                }
            }
        }

        /**
         * Host-suffix / path-prefix expressions (Safe Browsing URL processing): up to 5 hosts (exact host +
         * up to 4 suffixes built from the last 5 labels, never the bare TLD; IPs exact only) x up to 6 paths
         * (exact path+query, exact path, "/", then successive directory prefixes, max 4 incl. "/").
         */
        fun expressions(url: NormalizedUrl): List<String> {
            val host = url.host.trim('.').replace(Regex("\\.{2,}"), ".")
            val hosts = LinkedHashSet<String>()
            hosts += host
            if (!url.isIp) {
                val labels = host.split('.')
                val start = maxOf(1, labels.size - 5)
                for (i in start until labels.size - 1) hosts += labels.subList(i, labels.size).joinToString(".")
            }
            val path = canonicalPath(url.path)
            val query = url.query
            val paths = LinkedHashSet<String>()
            if (query.isNotEmpty()) paths += path + (if (query.startsWith("?")) query else "?$query")
            paths += path
            paths += "/"
            val comps = path.trim('/').split('/').filter { it.isNotEmpty() }
            var acc = "/"
            // directory prefixes (exclude the last component if the path does not end with '/')
            val dirs = if (path.endsWith("/")) comps else comps.dropLast(1)
            for (c in dirs.take(3)) {
                acc += "$c/"
                paths += acc
            }
            return hosts.flatMap { h -> paths.map { p -> h + p } }.distinct()
        }

        /** resolve "/./" and "/../", collapse "//", keep a leading "/". */
        fun canonicalPath(p: String): String {
            val raw = if (p.isEmpty()) "/" else p
            val out = ArrayList<String>()
            val segs = raw.split('/')
            for (s in segs.drop(1)) when (s) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out += s
            }
            val trailing = raw.endsWith("/") || raw.endsWith("/.") || raw.endsWith("/..")
            val joined = "/" + out.joinToString("/")
            return if (trailing && joined != "/") "$joined/" else joined
        }

        private fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): Pair<Int, ByteArray> {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            return try {
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                code to (stream?.use { it.readBytes() } ?: ByteArray(0))
            } finally {
                c.disconnect()
            }
        }
    }
}

/**
 * Real Safe Browsing is authoritative; `extraHits` (demo domains from the stub, debug only) can only ADD hits.
 * Unlike CompositeSafeBrowsing, a live failure stays UNKNOWN even if the stub says "no match".
 */
class AuthoritativeSafeBrowsing(
    private val primary: SafeBrowsingClient,
    private val extraHits: SafeBrowsingClient? = null,
) : SafeBrowsingClient {
    override fun check(url: NormalizedUrl): SafeBrowsingResult {
        if (extraHits?.check(url) == SafeBrowsingResult.HIT) return SafeBrowsingResult.HIT
        return primary.check(url)
    }
}
