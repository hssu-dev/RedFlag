package dev.redflag.core.gemma

import dev.redflag.core.Contracts
import dev.redflag.core.model.Features
import dev.redflag.core.model.RedFlagJson
import dev.redflag.core.util.TextNorm
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Kotlin twin == Python reference (backend/redflag_backend/gemma_repair.py, prompt_text.py) on all real data. */
class GemmaV3ParityTest {
    private fun res(p: String) = javaClass.getResource(p)!!.readText()

    @Test
    fun salvageMatchesPythonOnEveryRawOutput() {
        val lines = res("/golden/v3_salvage.jsonl").lines().filter { it.isNotBlank() }
        assertTrue(lines.size > 400, "expected all raw outputs, got ${lines.size}")
        val bundle = Contracts.load()
        for (line in lines) {
            val o = RedFlagJson.parseToJsonElement(line).jsonObject
            val raw = o["raw"]!!.jsonPrimitive.content
            val got = GemmaSalvage.salvage(raw)
            val want = o["obj"]!!.let { if (it is JsonNull) null else it.jsonObject }
            assertEquals(want, got.obj, raw.take(120))
            assertEquals(o["notes"]!!.jsonArray.map { it.jsonPrimitive.content }, got.notes, raw.take(120))
            assertEquals(o["missing"]!!.jsonArray.map { it.jsonPrimitive.content }, GemmaSalvage.missingKeys(got.obj))
            // full interpretation (no follow-up answer) + the follow-up question that would be asked
            val want2 = o["interp"]!!.jsonObject
            val it2 = GemmaPipeline.interpret(raw, null, emptyList())
            val wf = want2["features"]!!.let { if (it is JsonNull) null else RedFlagJson.decodeFromJsonElement(Features.serializer(), it) }
            assertEquals(wf, it2.features, raw.take(120))
            assertEquals(want2["strict_ok"]!!.jsonPrimitive.content.toBoolean(), it2.strictOk)
            assertEquals(want2["repairs"]!!.jsonArray.map { it.jsonPrimitive.content }, it2.repairs, raw.take(120))
            assertEquals(want2["core_repaired"]!!.jsonPrimitive.content.toBoolean(), it2.coreRepaired)
            assertEquals(want2["missing_after"]!!.jsonArray.map { it.jsonPrimitive.content }, it2.missingAfter)
            val wantFu = o["followup"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals(wantFu, GemmaPipeline.followUpFor(bundle, raw)?.prompt, raw.take(120))
        }
        println("salvage parity: ${lines.size} raw outputs")
    }

    @Test
    fun miscMatchesPython() {
        val m = RedFlagJson.parseToJsonElement(res("/golden/v3_misc.json")).jsonObject
        val bundle = Contracts.load()
        for (pair in m["sanitize"]!!.jsonArray) {
            val (inp, want) = pair.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(want, TextNorm.sanitizeForPrompt(inp), inp.take(80))
        }
        for (pair in m["canonical"]!!.jsonArray) {
            val k = pair.jsonArray[0].jsonPrimitive.content
            val want = pair.jsonArray[1].let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals(want, GemmaSalvage.canonicalKey(k), k)
        }
        for (pair in m["evidence"]!!.jsonArray) {
            val ev = pair.jsonArray[0].jsonArray.map { it.jsonPrimitive.content }
            val want = pair.jsonArray[1].jsonArray.map { it.jsonPrimitive.content }
            val f = Features(ev, null, "no_brand", "info", listOf("none"), false, false, false, "safe", "low")
            assertEquals(want, GemmaSalvage.enforceEvidence(f).evidence)
        }
        for (pair in m["followup"]!!.jsonArray) {
            val missing = pair.jsonArray[0].jsonArray.map { it.jsonPrimitive.content }
            assertEquals(pair.jsonArray[1].jsonPrimitive.content, GemmaSalvage.followupPrompt(bundle, missing))
        }
        for (row in m["merge"]!!.jsonArray) {
            val r = row.jsonArray
            val got = GemmaSalvage.merge(r[0].jsonObject, r[1].jsonObject, r[2].jsonArray.map { it.jsonPrimitive.content })
            assertEquals(r[3].jsonObject, got as JsonObject)
        }
    }
}
