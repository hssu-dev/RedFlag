package dev.redflag.core.followup

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
import kotlin.test.assertTrue

/** Kotlin twin of backend cloud_context.sanitize: same masked / capped user_context (followup_vectors.json). */
class CloudContextVectorsTest {
    private val doc = TestSupport.contractJson("followup_vectors.json")

    @Test
    fun cloudContextVectors() {
        val vectors = doc["cloud_context"]!!.jsonArray
        assertEquals(6, vectors.size)
        for (v in vectors) {
            v as JsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val i = v["in"]!!.jsonObject
            val statements = i["statements"]!!.jsonArray.map { s ->
                s as JsonObject
                UserAnswer(s["question_id"]!!.jsonPrimitive.content, s["chip_ids"]!!.jsonArray.map { it.jsonPrimitive.content }, s["text"]!!.jsonPrimitive.content)
            }
            val pasted = i["pasted_messages"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
            val dj = i["device_judgment"].takeIf { it != null && it !is JsonNull }?.jsonObject?.let {
                CloudContext.DeviceJudgment(it["scenario"]?.jsonPrimitive?.contentOrNull, it["consistency"]?.jsonPrimitive?.contentOrNull,
                    it["sender_risk"]?.jsonPrimitive?.contentOrNull, it["guided"]!!.jsonPrimitive.boolean)
            }
            val got = CloudContext.build(i["source_category"]!!.jsonPrimitive.content, statements, pasted, dj)
            val want = v["expect"].takeIf { it !is JsonNull }
            assertTrue(TestSupport.jsonTreeEquals(want ?: JsonNull, got ?: JsonNull), "$id\nwant=$want\ngot =$got")
        }
    }
}
