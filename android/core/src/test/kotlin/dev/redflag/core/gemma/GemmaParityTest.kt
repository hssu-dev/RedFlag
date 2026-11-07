package dev.redflag.core.gemma

import dev.redflag.core.Contracts
import dev.redflag.core.TestSupport
import dev.redflag.core.model.GemmaInput
import dev.redflag.core.model.ObservedField
import dev.redflag.core.model.ObservedForm
import dev.redflag.core.model.PageObservation
import dev.redflag.core.model.RedFlagJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GemmaParityTest {

    /** Every raw model output from last night's eval must parse exactly like run_eval.py did. */
    @Test
    fun parserParityOnRecordedRawOutputs() {
        val files = TestSupport.repoFile("redflag-gemma-test/results").listFiles { f -> f.name.endsWith(".jsonl") }!!.sorted()
        assertTrue(files.isNotEmpty())
        var n = 0
        var parsed = 0
        var valid = 0
        var withSchemaErrors = 0
        val failures = ArrayList<String>()
        for (f in files) {
            f.readLines().forEachIndexed { lineNo, line ->
                if (line.isBlank()) return@forEachIndexed
                val rec = Json.parseToJsonElement(line).jsonObject
                val raw = rec["raw"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                n++
                val r = GemmaOutputParser.parse(raw)
                val where = "${f.name}:${lineNo + 1} (${rec["id"]?.jsonPrimitive?.content})"
                val wantErr = rec["parse_error"]?.jsonPrimitive?.contentOrNull
                if (wantErr != r.error) failures += "$where parse_error expected=$wantErr got=${r.error}"
                val wantClean = rec["clean"]!!.jsonPrimitive.boolean
                if (wantClean != r.clean) failures += "$where clean expected=$wantClean got=${r.clean}"
                val wantOut = rec["output"]?.takeUnless { it is JsonNull }
                if (!TestSupport.jsonTreeEquals(wantOut, r.obj)) failures += "$where output differs:\n  want=$wantOut\n  got =${r.obj}"
                rec["schema_errors"]?.let { se ->
                    val want = se.jsonArray.map { it.jsonPrimitive.content }
                    if (want != r.schemaErrors) failures += "$where schema_errors expected=$want got=${r.schemaErrors}"
                    if (want.isNotEmpty()) withSchemaErrors++
                }
                if (r.obj != null) parsed++
                if (r.features != null) valid++
                if (r.obj != null && r.schemaErrors.isEmpty() && r.features == null) failures += "$where valid but Features decode failed"
            }
        }
        println("parser parity: $n raw records in ${files.size} files; parsed=$parsed valid->Features=$valid schema_errors=$withSchemaErrors; mismatches=${failures.size}")
        assertTrue(failures.isEmpty(), failures.take(30).joinToString("\n"))
        assertTrue(n >= 300, "expected the full set of recorded outputs, got $n")
    }

    @Test
    fun parserEdgeCases() {
        assertEquals("no_json", GemmaOutputParser.parse("I can't help").error)
        assertEquals("truncated", GemmaOutputParser.parse("{\"evidence\": [").error)
        assertEquals("bad_json(Expecting ',' delimiter)", GemmaOutputParser.parse("{\"a\": 1 \"b\": 2}").error)
        assertEquals("bad_json(Expecting ':' delimiter)", GemmaOutputParser.parse("{\"a\" 1}").error)
        assertEquals("bad_json(Expecting property name enclosed in double quotes)", GemmaOutputParser.parse("{\"a\": 1,}").error)
        assertEquals("bad_json(Expecting value)", GemmaOutputParser.parse("{\"a\": [1,]}").error)
        assertEquals("bad_json(Extra data)", GemmaOutputParser.parse("{\"a\": 1} x {}").error)
        assertEquals("bad_json(Invalid \\escape)", GemmaOutputParser.parse("{\"a\": \"\\q\"}").error)
        assertEquals("bad_json(Invalid control character at)", GemmaOutputParser.parse("{\"a\": \"x\ny\"}").error)
        // "[{}]" -> the slice is "{}" (starts at the first '{'), so not_object is unreachable, exactly as in Python
        assertEquals(JsonObject(emptyMap()), GemmaOutputParser.parse("[{}]").obj)
        val good = """<|channel>thought ... {bogus}<channel|>```json
            {"evidence": ["x"], "claimed_brand": null, "brand_matches_domain": "no_brand", "page_type": "info",
             "requested_actions": ["none"], "urgency_or_threat": false, "too_good_to_be_true": false,
             "addresses_ai_analyzer": false, "verdict": "safe", "confidence": "high"}
            ```"""
        val r = GemmaOutputParser.parse(good)
        assertNull(r.error)
        assertEquals(false, r.clean)
        assertEquals(emptyList<String>(), r.schemaErrors)
        assertNotNull(r.features)
        val swapped = GemmaOutputParser.parse("""{"claimed_brand": null, "evidence": [], "brand_matches_domain": "x"}""")
        assertTrue("order" in swapped.schemaErrors && "enum:brand_matches_domain" in swapped.schemaErrors)
        assertNull(swapped.features)
    }

    /** PromptBuilder.buildUserMessage == run_eval.build_user_message (golden generated by tools/gen_prompt_golden.py). */
    @Test
    fun promptBuilderMatchesPythonGolden() {
        val lines = javaClass.classLoader.getResource("golden/user_messages.jsonl")!!.readText().lines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 18)
        for (line in lines) {
            val rec = Json.parseToJsonElement(line).jsonObject
            val input = RedFlagJson.decodeFromJsonElement(GemmaInput.serializer(), rec["input"]!!)
            val want = rec["message"]!!.jsonPrimitive.content
            val got = PromptBuilder.buildUserMessage(input)
            assertEquals(want, got, "prompt mismatch for ${rec["id"]}")
        }
        println("prompt golden: ${lines.size}/${lines.size} byte-identical")
    }

    @Test
    fun buildObservationFromExtractorOutput() {
        val bundle = Contracts.load()
        val page = PageObservation(
            finalUrl = "https://dbs-secure-verify.top/login?ref=sms",
            title = "DBS digibank | Secure Login",
            lang = "en",
            visibleText = "가".repeat(3000),
            hiddenText = "😀".repeat(900),
            forms = listOf(
                ObservedForm("https://api-collect.click/p", "post", listOf(ObservedField("text", "User ID", "uid"), ObservedField("password", "Password", "pw"))),
                ObservedForm("https://login.dbs-secure-verify.top/x", "post", listOf(ObservedField("text", "OTP"))),
                ObservedForm("javascript:void(0)", "post", listOf(ObservedField("text", "Name"))),
            ),
            linkUrls = listOf("https://www.dbs.com.sg/a", "https://dbs.com.sg/b", "https://dbs-secure-verify.top/c", "https://x.github.io/"),
        )
        val g = PromptBuilder.buildObservation(page, ObservationSignals(url = "https://bit.ly/x", redirectCount = 1, domainAgeDays = 4, tlsIssuer = "Let's Encrypt", certAgeDays = 3, safeBrowsing = "no_match"), bundle)
        assertEquals("dbs-secure-verify.top", g.signals.registrableDomain)
        assertEquals("https://bit.ly/x", g.signals.url)
        assertEquals(listOf("api-collect.click" to false, "dbs-secure-verify.top" to true, null to false), g.signals.forms.map { it.actionDomain to it.sameSite })
        assertEquals(listOf("dbs.com.sg", "x.github.io"), g.signals.externalLinkDomains)
        assertEquals(2500, g.page.visibleText.length)
        assertEquals(800, g.page.hiddenText.codePointCount(0, g.page.hiddenText.length))
        assertTrue(PromptBuilder.buildUserMessage(g).startsWith("<signals>\n{\n \"url\": \"https://bit.ly/x\",\n"))
    }
}
