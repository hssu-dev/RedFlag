package dev.redflag.core.gate

import dev.redflag.core.Contracts
import dev.redflag.core.TestSupport
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrlVectorsTest {
    private val bundle = Contracts.load()
    private val doc = TestSupport.contractJson("url_vectors.json")
    private val fixture = InMemoryKnownGoodSet(doc["known_good_fixture"]!!.jsonArray.map { it.jsonPrimitive.content })

    private fun decisionOf(r: GateResult) = when (r) {
        is GateResult.Pass -> "pass"
        is GateResult.Block -> "block"
        is GateResult.Unknown -> "unknown"
        is GateResult.Ignore -> "ignore"
    }

    @Test
    fun allUrlVectorsPass() {
        val gate = Gate(bundle, fixture, InMemorySafeVerdictCache(), StubSafeBrowsing(bundle.gateLists.safeBrowsingStub))
        val vectors = doc["vectors"]!!.jsonArray
        val failures = ArrayList<String>()
        for (v in vectors) {
            v as JsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val url = v["url"]!!.jsonPrimitive.content
            val e = v["expect"]!!.jsonObject
            val r = gate.evaluate(url, nowMs = 0)
            val n = r.normalizedOrNull
            val got = linkedMapOf<String, Any?>(
                "host" to n?.host, "registrable_domain" to n?.registrableDomain,
                "decision" to decisionOf(r), "reason" to r.reason,
            )
            if ("punycode" in e) got["punycode"] = n?.punycode
            if ("homoglyph" in e) got["homoglyph"] = n?.homoglyph
            for ((k, gv) in got) {
                val ev = e[k]
                val want: Any? = when {
                    ev == null || ev is JsonNull -> null
                    k == "punycode" || k == "homoglyph" -> ev.jsonPrimitive.boolean
                    else -> ev.jsonPrimitive.contentOrNull
                }
                if (want != gv) failures += "$id ($url): $k expected=$want got=$gv"
            }
        }
        println("url vectors: ${vectors.size - failures.map { it.substringBefore(' ') }.distinct().size}/${vectors.size} pass")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        assertEquals(67, vectors.size)
    }

    @Test
    fun cacheAndNormalizerBasics() {
        val cache = InMemorySafeVerdictCache()
        val gate = Gate(bundle, InMemoryKnownGoodSet(emptyList()), cache, StubSafeBrowsing(bundle.gateLists.safeBrowsingStub))
        assertEquals("unknown_domain", gate.evaluate("https://hanaroombakery.sg/", 1000).reason)
        cache.putSafe("hanaroombakery.sg", ttlMs = 500, nowMs = 1000)
        assertEquals("cache", gate.evaluate("https://hanaroombakery.sg/", 1200).reason)
        assertEquals("unknown_domain", gate.evaluate("https://hanaroombakery.sg/", 1500).reason)

        val n = UrlNormalizer.normalize("HTTPS://User:pw@WWW.Example.COM.:443/Path/x?q=1#frag") as NormalizedUrl
        assertEquals("https://www.example.com/Path/x?q=1", n.url)
        assertNull(n.port)
        assertEquals("/Path/x", n.path)
        val p = UrlNormalizer.normalize("example.com:8080") as NormalizedUrl
        assertEquals(8080, p.port)
        assertEquals("https://example.com:8080/", p.url)
        assertEquals(NormalizeIgnore("not_a_url"), UrlNormalizer.normalize("https://exa mple.com/"))
        assertEquals(NormalizeIgnore("unsupported_scheme"), UrlNormalizer.normalize("mailto:a@b.com"))
        assertFalse((UrlNormalizer.normalize("https://xn--mnchen-3ya.de/") as NormalizedUrl).homoglyph)
    }

    @Test
    fun publicSuffixListRules() {
        val psl = bundle.psl
        assertEquals("example.co.uk", psl.registrableDomain("www.example.co.uk"))
        assertNull(psl.registrableDomain("co.uk"))
        // wildcard *.ck + exception !www.ck
        assertNull(psl.registrableDomain("foo.ck"))
        assertEquals("bar.foo.ck", psl.registrableDomain("a.bar.foo.ck"))
        assertEquals("www.ck", psl.registrableDomain("www.ck"))
        assertEquals("city.kobe.jp", psl.registrableDomain("x.city.kobe.jp"))
        assertNull(psl.registrableDomain("localhost"))
        // unicode rule stored as punycode: 公司.cn
        assertEquals("xn--abc.xn--55qx5d.cn", psl.registrableDomain("www.xn--abc.xn--55qx5d.cn"))
        // private section
        assertEquals("foo.github.io", psl.registrableDomain("a.foo.github.io"))
    }
}
