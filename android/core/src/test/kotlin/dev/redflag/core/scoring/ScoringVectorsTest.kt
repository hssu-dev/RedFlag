package dev.redflag.core.scoring

import dev.redflag.core.Contracts
import dev.redflag.core.TestSupport
import dev.redflag.core.model.RedFlagJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScoringVectorsTest {
    private val engine = ScoringEngine(Contracts.load())

    @Test
    fun allScoringVectorsPass() {
        val vectors = TestSupport.contractJson("scoring_vectors.json")["vectors"]!!.jsonArray
        val failures = ArrayList<String>()
        var passed = 0
        for (v in vectors) {
            v as JsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val input = RedFlagJson.decodeFromJsonElement(ScoringInput.serializer(), v["input"]!!)
            val e = v["expect"]!!.jsonObject
            val r = engine.score(input)
            val errs = ArrayList<String>()
            fun check(what: String, want: Any?, got: Any?) { if (want != got) errs += "$what expected=$want got=$got" }
            check("score", e["score"]!!.jsonPrimitive.int, r.score)
            check("level", e["level"]!!.jsonPrimitive.content, r.level)
            check("escalate", e["escalate"]!!.jsonPrimitive.boolean, r.escalate)
            check("fired", e["fired"]!!.jsonArray.map { it.jsonPrimitive.content }, r.fired)
            check("brand_matches", (e["brand_matches"] as? JsonNull)?.let { null } ?: e["brand_matches"]?.jsonPrimitive?.booleanOrNull, r.brand.matches)
            e["headline_en"]?.let { check("headline_en", it.jsonPrimitive.content, r.headline.en) }
            e["flags"]?.jsonArray?.forEach { fl ->
                fl as JsonObject
                val rid = fl["rule_id"]!!.jsonPrimitive.content
                val got = r.redFlags.firstOrNull { it.ruleId == rid }
                if (got == null) { errs += "flag $rid missing"; return@forEach }
                fun opt(k: String, actual: String?) { fl[k]?.let { check("$rid.$k", it.jsonPrimitive.contentOrNull, actual) } }
                opt("source", got.source); opt("severity", got.severity); opt("evidence", got.evidence)
                opt("title_en", got.title.en); opt("title_ko", got.title.ko)
            }
            if (errs.isEmpty()) passed++ else failures += "$id: ${errs.joinToString("; ")}"
        }
        println("scoring vectors: $passed/${vectors.size} pass")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        assertEquals(93, vectors.size) // v4: +21 destination/context vectors, +2 provider_phishing_warning
    }

    @Test
    fun sensitiveCategoriesStableOrder() {
        val form = FormInput(
            actionDomain = "x.top", sameSite = false,
            fields = listOf(
                FormField("text", "휴대폰 인증번호"), FormField("text", "Card number"), FormField("password", "PIN"),
                FormField("text", "Shipping address"), FormField("text", "", name = "cc-number", autocomplete = "cc-csc"),
            ),
        )
        assertEquals(listOf("password", "card", "secret"), engine.sensitiveCategories(form))
        assertEquals(emptyList<String>(), engine.sensitiveCategories(FormInput(fields = listOf(FormField("text", "Shipping address")))))
        assertEquals(listOf("card"), engine.sensitiveCategories(FormInput(sensitiveFields = listOf("card"))))
    }

    @Test
    fun unknownExpressionThrows() {
        assertFailsWith<IllegalArgumentException> {
            Expressions.evaluate(RedFlagJson.parseToJsonElement("""{"bogus": 1}"""), emptyMap())
        }
    }
}
