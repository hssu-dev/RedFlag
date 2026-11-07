package dev.redflag.core.followup

import dev.redflag.core.Contracts
import dev.redflag.core.TestSupport
import dev.redflag.core.gemma.PromptBuilder
import dev.redflag.core.model.DataEndpoint
import dev.redflag.core.model.Features
import dev.redflag.core.model.RedFlagJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** contracts/followup_vectors.json (twin: backend/tests/test_lite_followups.py). */
class FollowupVectorsTest {
    private val v = TestSupport.contractJson("followup_vectors.json")
    private val bundle = Contracts.load()

    private fun JsonElement.s(): String? = (this as? JsonNull)?.let { null } ?: jsonPrimitive.contentOrNull
    private fun JsonObject.str(k: String): String? = this[k]?.s()
    private fun strs(e: JsonElement?): List<String> = (e as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
    private fun features(e: JsonElement?): Features? =
        if (e == null || e is JsonNull) null else RedFlagJson.decodeFromJsonElement(Features.serializer(), e)
    private fun cands(e: JsonElement) = e.jsonArray.map { Destinations.Candidate(it.jsonObject.str("domain")!!, it.jsonObject.str("via")!!) }
    private fun conv(e: JsonElement?) = if (e == null || e is JsonNull) null else e.jsonArray.map {
        val o = it.jsonObject
        ConvMessage(o.str("sender")!!, o.str("text")!!, o["has_link"]!!.jsonPrimitive.boolean)
    }
    private fun answers(e: JsonElement?) = (e as? JsonArray).orEmpty().map {
        val o = it.jsonObject
        UserAnswer(o.str("question_id")!!, strs(o["chip_ids"]), o.str("text") ?: "")
    }

    @Test
    fun destinationCandidates() {
        for (c in v["destination_candidates"]!!.jsonArray) {
            c as JsonObject
            val eps = c["endpoints"]!!.jsonArray.map { DataEndpoint(it.jsonObject.str("url")!!, it.jsonObject.str("via")!!) }
            val got = Destinations.candidates(eps, c.str("page_domain"), c["personal_request"]!!.jsonPrimitive.boolean, bundle.scoringRules) {
                PromptBuilder.domainOf(it, bundle)
            }
            assertEquals(cands(c["expect"]!!), got, c.str("id"))
        }
    }

    @Test
    fun destinationParse() {
        for (c in v["destination_parse"]!!.jsonArray) {
            c as JsonObject
            val e = c["expect"]
            val want = if (e is JsonObject) Destinations.Answer(e.str("relation")!!, e.str("domain")!!) else null
            assertEquals(want, Destinations.parseAnswer(c.str("text")!!, cands(c["candidates"]!!)), c.str("id"))
        }
    }

    @Test
    fun mask() {
        for (c in v["mask"]!!.jsonArray) assertEquals(c.jsonObject.str("expect"), ContextText.mask(c.jsonObject.str("in")!!))
    }

    @Test
    fun decide() {
        for (c in v["decide"]!!.jsonArray) {
            c as JsonObject
            val got = ContextPolicy.decide(
                ContextPolicy.Input(
                    score = c["score"]!!.jsonPrimitive.int, complete = c["complete"]!!.jsonPrimitive.boolean,
                    safeBrowsingHit = c["safe_browsing_hit"]!!.jsonPrimitive.boolean, features = features(c["features"]),
                    sourceCategory = c.str("source")!!, neverAsk = c["never_ask"]?.jsonPrimitive?.boolean ?: false,
                    alreadyAsked = c["already_asked"]?.jsonPrimitive?.boolean ?: false,
                ),
            )
            assertEquals(c.str("expect"), got.name.lowercase(), c.str("id"))
        }
    }

    @Test
    fun parseAndResolve() {
        for (c in v["resolve"]!!.jsonArray) {
            c as JsonObject
            val id = c.str("id")
            val calls = c["calls"]!!.jsonArray.map { ContextPolicy.ToolCallIn(it.jsonObject.str("name")!!, it.jsonObject["args"]!!.jsonObject) }
            val p = ContextPolicy.parseToolCalls(calls)
            val ep = c["expect_parsed"]!!.jsonObject
            assertEquals(
                ContextPolicy.Parsed(ep["auto"]!!.jsonPrimitive.boolean, ep.str("question"), strs(ep["reasons"]), ep["none"]!!.jsonPrimitive.boolean, ep["recognized"]!!.jsonPrimitive.int),
                p, id,
            )
            val decision = ContextDecision.valueOf(c.str("decision")!!.uppercase())
            val plan = ContextPolicy.resolve(decision, p, c.str("source")!!, features(c["features"]))
            val e = c["expect_plan"]
            val want = if (e is JsonObject) ContextPlan(e["auto"]!!.jsonPrimitive.boolean, e.str("question"), strs(e["reasons"]), e.str("origin")!!) else null
            assertEquals(want, plan, id)
        }
    }

    @Test
    fun toolCallsFromText() {
        for (c in v["tool_calls_from_text"]!!.jsonArray) {
            c as JsonObject
            val want = c["expect"]!!.jsonArray.map { ContextPolicy.ToolCallIn(it.jsonObject.str("name")!!, it.jsonObject["args"]!!.jsonObject) }
            assertEquals(want, ContextPolicy.toolCallsFromText(c.str("text")!!))
        }
    }

    @Test
    fun judgment() {
        for (c in v["judgment"]!!.jsonArray) {
            c as JsonObject
            val e = c["expect"]
            val want = if (e is JsonObject) ContextJudgment(e.str("scenario"), e.str("consistency"), e.str("sender_risk"), strs(e["evidence"])) else null
            assertEquals(want, ContextJudge.parse(c.str("text")!!, strs(c["sources"])), c.str("id"))
        }
    }

    @Test
    fun contextInput() {
        for (c in v["context_input"]!!.jsonArray) {
            c as JsonObject
            val j = (c["judgment"] as? JsonObject)?.let { ContextJudgment(it.str("scenario"), it.str("consistency"), it.str("sender_risk"), strs(it["evidence"])) }
            val got = ContextJudge.toInput(j, answers(c["answers"]), conv(c["conversation"]))
            val e = c["expect"]!!.jsonObject
            assertEquals(e.str("scenario"), got.scenario, c.str("id"))
            assertEquals(e.str("consistency"), got.consistency, c.str("id"))
            assertEquals(e.str("sender_risk"), got.senderRisk, c.str("id"))
            assertEquals(e["guided"]!!.jsonPrimitive.boolean, got.guided, c.str("id"))
            assertEquals(strs(e["evidence"]), got.evidence, c.str("id"))
        }
    }

    @Test
    fun promptBytesMatchPythonTwin() {
        var n = 0
        for (c in v["messages"]!!.jsonArray) {
            c as JsonObject
            val f = features(c["features"])!!
            val got = when (c.str("kind")) {
                "destination" -> Destinations.userMessage(c.str("page_domain"), f, cands(c["candidates"]!!))
                "turn2" -> {
                    val decision = ContextDecision.valueOf(c.str("decision")!!.uppercase())
                    ContextPolicy.turn2Message(
                        ContextPolicy.Input(0, true, false, f, c.str("source")!!), decision, c.str("page_domain"),
                        c["domain_age_days"]?.jsonPrimitive?.intOrNull, c.str("level")!!,
                    )
                }
                else -> {
                    val cv = conv(c["conversation"])
                    val an = answers(c["answers"])
                    assertEquals(strs(c["expect_sources"]), ContextJudge.evidenceSources(cv, an))
                    ContextJudge.turn3Message(c.str("source")!!, c.str("page_domain"), c.str("level")!!, f, c.str("app_name"), cv, an)
                }
            }
            assertEquals(c.str("expect"), got)
            n++
        }
        assertTrue(n >= 5)
    }

    @Test
    fun catalogAndToolsLoad() {
        val cat = FollowupContracts.catalog
        assertEquals(setOf("U1", "U2", "U3", "U4"), cat.questions.keys)
        assertEquals(listOf("request_auto_context", "request_user_context", "no_context_needed"), FollowupContracts.tools.map { it.str("name") })
        assertTrue(FollowupContracts.contextPrompt.startsWith("You are RedFlag-Lite's context judge"))
        assertEquals("messenger", ContextSources.classify("com.kakao.talk").category)
        assertEquals("unknown", ContextSources.classify("com.example.x").category)
        assertEquals("unknown", ContextSources.classify(null).category)
    }
}
