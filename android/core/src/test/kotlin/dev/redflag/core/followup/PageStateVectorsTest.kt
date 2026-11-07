package dev.redflag.core.followup

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

/** The phone sends Gemma exactly the bytes measured on the Mac (eval/page_state). */
class PageStateVectorsTest {
    private val doc = TestSupport.contractJson("followup_vectors.json")["page_state"]!!.jsonObject

    @Test
    fun messagesAreByteExact() {
        for (v in doc["messages"]!!.jsonArray) {
            v as JsonObject
            val i = v["in"]!!.jsonObject
            val status = i["http_status"].takeIf { it !is JsonNull }?.jsonPrimitive?.int
            val got = PageStateTurn.message(status, i["title"]!!.jsonPrimitive.content, i["text"]!!.jsonPrimitive.content,
                i["form_count"]!!.jsonPrimitive.int, i["has_password_field"]!!.jsonPrimitive.boolean)
            assertEquals(v["expect"]!!.jsonPrimitive.content, got, v["id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun parse() {
        for (v in doc["parse"]!!.jsonArray) {
            v as JsonObject
            val want = v["expect"].takeIf { it !is JsonNull }?.jsonPrimitive?.content
            assertEquals(want, PageStateTurn.parse(v["text"]!!.jsonPrimitive.content), v["text"]!!.jsonPrimitive.content)
        }
    }
}
