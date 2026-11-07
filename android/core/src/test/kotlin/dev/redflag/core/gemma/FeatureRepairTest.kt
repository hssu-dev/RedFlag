package dev.redflag.core.gemma

import dev.redflag.core.model.Features
import dev.redflag.core.model.RedFlagJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Kotlin FeatureRepair must equal backend/redflag_backend/gemma_repair.py on every real invalid output. */
class FeatureRepairTest {
    @Test
    fun parityWithPython() {
        val lines = javaClass.getResource("/golden/repair.jsonl")!!.readText().lines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 10, "expected real repair cases, got ${lines.size}")
        for (line in lines) {
            val o = RedFlagJson.parseToJsonElement(line).jsonObject
            val got = FeatureRepair.repair(o["output"]!!.jsonObject)
            val want = RedFlagJson.decodeFromJsonElement(Features.serializer(), o["features"]!!)
            val id = o["id"]!!.jsonPrimitive.content
            assertEquals(want, got.features, id)
            assertEquals(o["repairs"]!!.jsonArray.map { it.jsonPrimitive.content }, got.repairs, id)
            assertTrue(GemmaOutputParser.validate(RedFlagJson.encodeToJsonElement(Features.serializer(), got.features).jsonObject).isEmpty(), id)
        }
        println("repair parity: ${lines.size} cases")
    }
}
