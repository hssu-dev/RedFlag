package dev.redflag.core.gate

import dev.redflag.core.Contracts
import dev.redflag.core.TestSupport
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UrlExtractVectorsTest {
    private val bundle = Contracts.load()
    private val doc = TestSupport.contractJson("url_extract_vectors.json")

    @Test
    fun extractVectors() {
        val vectors = doc["vectors"]!!.jsonArray
        val failures = ArrayList<String>()
        for (v in vectors) {
            v as JsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val e = v["expect"]!!.jsonObject
            val got = UrlExtractor.extract(v["text"]!!.jsonPrimitive.content, bundle)
            val wantUrls = e["urls"]!!.jsonArray.map { it.jsonPrimitive.content }
            val wantMsg = e["message"].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.content }
            val wantDef = e["defanged"]!!.jsonPrimitive.boolean
            if (got.urls != wantUrls) failures += "$id: urls expected=$wantUrls got=${got.urls}"
            if (got.message != wantMsg) failures += "$id: message expected=$wantMsg got=${got.message}"
            if (got.defanged != wantDef) failures += "$id: defanged expected=$wantDef got=${got.defanged}"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        assertEquals(42, vectors.size)
    }

    @Test
    fun windowVectors() {
        for (v in doc["window_vectors"]!!.jsonArray) {
            v as JsonObject
            val got = UrlExtractor.messageWindow(v["text"]!!.jsonPrimitive.content, v["url"]!!.jsonPrimitive.content, v["limit"]!!.jsonPrimitive.int)
            assertEquals(v["expect"]!!.jsonPrimitive.content, got, v["id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun everyExtractedUrlPassesTheNormalizer() {
        for (v in doc["vectors"]!!.jsonArray) {
            for (u in UrlExtractor.extract((v as JsonObject)["text"]!!.jsonPrimitive.content, bundle).urls) {
                assertTrue(UrlNormalizer.normalize(u, bundle) is NormalizedUrl, u)
            }
        }
    }

    @Test
    fun safeCacheCanBeBypassed() {
        val cache = InMemorySafeVerdictCache().apply { putSafe("cached-site.example", 60_000, 0) }
        val gate = Gate(bundle, InMemoryKnownGoodSet(emptyList()), cache, StubSafeBrowsing(bundle.gateLists.safeBrowsingStub))
        val hit = gate.evaluate("https://cached-site.example/a", nowMs = 1)
        assertTrue(hit is GateResult.Pass && hit.reason == GateResult.CACHE)
        val fresh = gate.evaluate("https://cached-site.example/a", nowMs = 1, useSafeCache = false)
        assertTrue(fresh is GateResult.Unknown && fresh.reason == GateResult.UNKNOWN_DOMAIN, "$fresh")
    }
}
