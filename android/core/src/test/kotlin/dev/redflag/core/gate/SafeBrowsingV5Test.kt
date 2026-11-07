package dev.redflag.core.gate

import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SafeBrowsingV5Test {
    private fun n(raw: String) = UrlNormalizer.normalize(raw) as NormalizedUrl

    @Test
    fun expressionsMatchTheDocumentedExample() {
        // https://developers.google.com/safe-browsing/v4/urls-hashing (same rules in v5)
        assertEquals(
            setOf("a.b.c/1/2.html?param=1", "a.b.c/1/2.html", "a.b.c/", "a.b.c/1/", "b.c/1/2.html?param=1", "b.c/1/2.html", "b.c/", "b.c/1/"),
            SafeBrowsingV5.expressions(n("http://a.b.c/1/2.html?param=1")).toSet(),
        )
    }

    @Test
    fun expressionsCapHostsAndPaths() {
        val e = SafeBrowsingV5.expressions(n("http://a.b.c.d.e.f.g/1/2/3/4/5.html"))
        val hosts = e.map { it.substringBefore('/') }.toSet()
        assertEquals(setOf("a.b.c.d.e.f.g", "c.d.e.f.g", "d.e.f.g", "e.f.g", "f.g"), hosts)
        assertTrue(e.size <= 30)
    }

    // --- tiny protobuf writer for fake responses (same layout the live API returned) ---
    private fun varint(v: Long): ByteArray { val o = java.io.ByteArrayOutputStream(); var x = v; while (true) { if (x < 0x80) { o.write(x.toInt()); break }; o.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; return o.toByteArray() }
    private fun ld(field: Int, body: ByteArray) = varint(((field shl 3) or 2).toLong()) + varint(body.size.toLong()) + body
    private fun vi(field: Int, v: Long) = varint((field shl 3).toLong()) + varint(v)
    private fun response(vararg hashes: Pair<ByteArray, Long>): ByteArray =
        hashes.fold(ByteArray(0)) { acc, (h, t) -> acc + ld(1, ld(1, h) + ld(2, vi(1, t))) } + ld(2, vi(1, 300))

    @Test
    fun hitOnlyWhenAReturnedFullHashMatches() {
        val url = n("https://evil.example.com/login")
        val target = SafeBrowsingV5.sha256("evil.example.com/login")
        fun client(body: ByteArray, code: Int = 200) = SafeBrowsingV5("k", transport = { _, _, _ -> code to body })
        assertEquals(SafeBrowsingResult.HIT, client(response(target to 2L)).check(url))           // SOCIAL_ENGINEERING
        assertEquals(SafeBrowsingResult.NO_MATCH, client(response(ByteArray(32) { 7 } to 1L)).check(url))
        assertEquals(SafeBrowsingResult.NO_MATCH, client(response(target to 0L)).check(url))      // unspecified type ignored
        assertEquals(SafeBrowsingResult.NO_MATCH, client(response()).check(url))
        assertEquals(SafeBrowsingResult.UNKNOWN, client("quota".toByteArray(), 429).check(url))
        assertEquals(SafeBrowsingResult.UNKNOWN, client(byteArrayOf(0x0a, 0x7f)).check(url))      // truncated protobuf
        assertEquals(SafeBrowsingResult.UNKNOWN, SafeBrowsingV5("k", transport = { _, _, _ -> throw java.io.IOException("offline") }).check(url))
    }

    @Test
    fun liveFailureIsNotTurnedIntoNoMatchByTheDemoStub() {
        val failing = SafeBrowsingClient { SafeBrowsingResult.UNKNOWN }
        val stubNoMatch = SafeBrowsingClient { SafeBrowsingResult.NO_MATCH }
        assertEquals(SafeBrowsingResult.UNKNOWN, AuthoritativeSafeBrowsing(failing, stubNoMatch).check(n("https://x.top/")))
        val stubHit = SafeBrowsingClient { SafeBrowsingResult.HIT }
        assertEquals(SafeBrowsingResult.HIT, AuthoritativeSafeBrowsing(failing, stubHit).check(n("https://x.top/")))
    }

    /** Real network call; runs only when the key is in the environment (never printed). */
    @Test
    fun liveGoogleTestUrls() {
        val key = System.getenv("GOOGLE_CLOUD_API_KEY") ?: dotEnvKey()
        assumeTrue(!key.isNullOrBlank())
        val c = SafeBrowsingV5(key!!, androidPackage = "dev.redflag.app", androidCertSha1 = System.getenv("RF_ANDROID_CERT_SHA1"))
        val phishing = c.check(n("https://testsafebrowsing.appspot.com/s/phishing.html"))
        println("live phishing test url -> $phishing (${c.lastError ?: "ok"})")
        val malware = c.check(n("http://testsafebrowsing.appspot.com/s/malware.html"))
        println("live malware test url -> $malware (${c.lastError ?: "ok"})")
        assertEquals(SafeBrowsingResult.HIT, malware, c.lastError ?: "")
        val google = c.check(n("https://www.google.com/"))
        println("live google.com -> $google (${c.lastError ?: "ok"})")
        assertEquals(SafeBrowsingResult.HIT, phishing, c.lastError ?: "")
        assertEquals(SafeBrowsingResult.NO_MATCH, google, c.lastError ?: "")
    }

    /** repo-root .env, parsed like app/build.gradle.kts does (value never printed). */
    private fun dotEnvKey(): String? {
        val root = System.getProperty("redflag.repoRoot") ?: return null
        val f = java.io.File(root, ".env").takeIf { it.exists() } ?: return null
        return f.readLines().map { it.trim().removePrefix("export ").trim() }
            .firstOrNull { it.replace(" ", "").startsWith("GOOGLE_CLOUD_API_KEY=") }
            ?.substringAfter('=')?.trim()?.trim('"', '\'')
    }
}
