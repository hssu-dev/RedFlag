package dev.redflag.core.report

import dev.redflag.core.Contracts
import dev.redflag.core.TestSupport
import dev.redflag.core.gate.HashedKnownGoodSet
import dev.redflag.core.model.Features
import dev.redflag.core.model.RedFlagJson
import dev.redflag.core.model.RiskReport
import dev.redflag.core.model.TraceStep
import dev.redflag.core.scoring.ScoringEngine
import dev.redflag.core.scoring.ScoringInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportTest {
    private val bundle = Contracts.load()

    @Test
    fun fixturesRoundTrip() {
        val files = TestSupport.repoFile("contracts/fixtures").listFiles { f -> f.name.endsWith(".json") }!!.sorted()
        assertEquals(4, files.size)
        for (f in files) {
            val text = f.readText()
            val r1 = RedFlagJson.decodeFromString(RiskReport.serializer(), text)
            val encoded = RedFlagJson.encodeToString(RiskReport.serializer(), r1)
            val r2 = RedFlagJson.decodeFromString(RiskReport.serializer(), encoded)
            assertEquals(r1, r2, f.name)
            // the encoding is the same JSON tree as the fixture (nothing dropped, nothing added)
            assertTrue(TestSupport.jsonTreeEquals(Json.parseToJsonElement(text), Json.parseToJsonElement(encoded)), "tree differs: ${f.name}\n$encoded")
            assertSchema(Json.parseToJsonElement(encoded), f.name)
        }
    }

    @Test
    fun builtReportsHaveAllSchemaKeys() {
        val engine = ScoringEngine(bundle)
        val vectors = TestSupport.contractJson("scoring_vectors.json")["vectors"]!!.jsonArray
        for (v in vectors) {
            val input = RedFlagJson.decodeFromJsonElement(ScoringInput.serializer(), v.jsonObject["input"]!!)
            val report = ReportBuilder.build(
                engine, input, url = "https://${input.registrableDomain ?: "x.test"}/",
                tlsIssuer = "Let's Encrypt", certAgeDays = 3, redirectCount = 0,
                trace = listOf(TraceStep(0, "gate", "unknown domain")), latencyMs = 1234,
                createdAt = Instant.parse("2026-11-05T01:15:11.123Z"),
            )
            assertEquals("2026-11-05T01:15:11Z", report.createdAt)
            val tree = Json.parseToJsonElement(RedFlagJson.encodeToString(RiskReport.serializer(), report))
            assertSchema(tree, v.jsonObject["id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun liteFeaturesOmitCloudKeysCloudKeepsThem() {
        val lite = Features(emptyList(), null, "no_brand", "info", listOf("none"), false, false, false, "safe", "high")
        val liteJson = RedFlagJson.encodeToJsonElement(Features.serializer(), lite).jsonObject
        assertEquals(Features.LITE_KEYS, liteJson.keys.toList())
        val cloud = lite.copy(formPostsCrossSite = true, downloadsAttempted = false, officialDomainFound = null)
        val cloudJson = RedFlagJson.encodeToJsonElement(Features.serializer(), cloud).jsonObject
        assertEquals(Features.LITE_KEYS + Features.CLOUD_ONLY_KEYS, cloudJson.keys.toList())
        assertEquals(JsonNull, cloudJson["official_domain_found"])
        assertEquals(cloud, RedFlagJson.decodeFromJsonElement(Features.serializer(), cloudJson))
        assertEquals(lite, RedFlagJson.decodeFromJsonElement(Features.serializer(), liteJson))
    }

    @Test
    fun bundledTrancoSetLoads() {
        val set = HashedKnownGoodSet.fromClasspath()
        assertTrue(set.size > 90_000, "size=${set.size}")
        assertTrue(set.contains("google.com"))
        assertTrue(set.contains("naver.com"))
        assertFalse(set.contains("dbs-secure-verify.top"))
        assertFalse(set.contains("github.io")) // public suffix (PSL private section) -> never known-good
        val meta = Json.parseToJsonElement(Contracts.classpathText("known_good/tranco_meta.json")!!).jsonObject
        assertEquals(set.size.toString(), meta["domains_written"]!!.jsonPrimitive.content)
    }

    // ---- minimal JSON-Schema check: required keys, additionalProperties=false, enums/const, nested $defs

    private val schema = TestSupport.contractJson("risk_report.schema.json")
    private val liteSchema = TestSupport.contractJson("lite_features.schema.json")
    private val cloudSchema = TestSupport.contractJson("cloud_features.schema.json")

    private fun assertSchema(report: JsonElement, label: String) {
        val errs = ArrayList<String>()
        check(report, schema, "$", errs)
        val features = report.jsonObject["features"]
        if (features != null && features !is JsonNull) {
            val e1 = ArrayList<String>().also { check(features, liteSchema, "$.features", it) }
            val e2 = ArrayList<String>().also { check(features, cloudSchema, "$.features", it) }
            if (e1.isNotEmpty() && e2.isNotEmpty()) errs += "features matches neither lite ($e1) nor cloud ($e2)"
        }
        assertTrue(errs.isEmpty(), "$label: ${errs.joinToString("; ")}")
    }

    private fun resolve(s: JsonObject): JsonObject {
        val ref = (s["\$ref"] as? JsonPrimitive)?.content ?: return s
        if (!ref.startsWith("#/\$defs/")) return s
        return schema["\$defs"]!!.jsonObject[ref.removePrefix("#/\$defs/")]!!.jsonObject
    }

    private fun check(v: JsonElement, s0: JsonObject, path: String, errs: MutableList<String>) {
        val s = resolve(s0)
        if ("anyOf" in s) return // features: checked separately
        s["const"]?.let { if (v != it) errs += "$path const" }
        s["enum"]?.let { e -> if (e.jsonArray.none { it == v }) errs += "$path enum ($v)" }
        val types = when (val t = s["type"]) {
            null -> null
            is JsonArray -> t.map { it.jsonPrimitive.content }
            else -> listOf(t.jsonPrimitive.content)
        }
        if (types != null) {
            val ok = types.any { t ->
                when (t) {
                    "null" -> v is JsonNull
                    "object" -> v is JsonObject
                    "array" -> v is JsonArray
                    "string" -> v is JsonPrimitive && v.isString
                    "boolean" -> v is JsonPrimitive && !v.isString && v !is JsonNull && (v.content == "true" || v.content == "false")
                    "integer" -> v is JsonPrimitive && !v.isString && v !is JsonNull && v.content.toLongOrNull() != null
                    else -> true
                }
            }
            if (!ok) { errs += "$path type $types ($v)"; return }
        }
        if (v is JsonObject) {
            val props = s["properties"]?.jsonObject ?: JsonObject(emptyMap())
            s["required"]?.jsonArray?.forEach { k -> if (k.jsonPrimitive.content !in v) errs += "$path missing ${k.jsonPrimitive.content}" }
            if ((s["additionalProperties"] as? JsonPrimitive)?.content == "false") {
                v.keys.filter { it !in props }.forEach { errs += "$path extra key $it" }
            }
            for ((k, child) in v) props[k]?.let { check(child, it.jsonObject, "$path.$k", errs) }
        }
        if (v is JsonArray) s["items"]?.let { items -> v.forEachIndexed { i, c -> check(c, items.jsonObject, "$path[$i]", errs) } }
    }
}
