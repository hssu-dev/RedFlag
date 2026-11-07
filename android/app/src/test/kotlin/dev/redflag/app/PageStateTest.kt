package dev.redflag.app

import dev.redflag.app.lite.PageState
import dev.redflag.app.lite.PageStates
import dev.redflag.app.lite.Unverified
import dev.redflag.core.model.RiskReport
import dev.redflag.core.model.RedFlagJson
import dev.redflag.core.model.TraceStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Code signatures on the 264 measured pages (eval/page_state/cases.jsonl): same answers as the Mac baseline. */
class PageStateTest {
    @Test
    fun codeSignaturesOnMeasuredPages() {
        val lines = File("../../eval/page_state/cases.jsonl").readLines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 260)
        val misses = ArrayList<String>()
        for (l in lines) {
            val c = Json.parseToJsonElement(l).jsonObject
            val p = c["page"]!!.jsonObject
            val st = p["http_status"].takeIf { it !is JsonNull }?.jsonPrimitive?.int
            val got = PageStates.code(st, p["title"]!!.jsonPrimitive.content, p["text"]!!.jsonPrimitive.content, p["form_count"]!!.jsonPrimitive.int)
            if (got.wire != c["label"]!!.jsonPrimitive.content) misses += "${c["id"]!!.jsonPrimitive.content}: ${c["label"]} -> ${got.wire}"
        }
        // the one known miss: a scam page served with HTTP 404 and short text looks "unavailable" to code
        assertEquals(listOf("mal_099: \"loaded\" -> unavailable"), misses)
    }

    @Test
    fun cloudflareWarningsAndCheckWaiting() {
        assertEquals("cloudflare_malware", PageStates.providerWarning("Suspected Malware | Cloudflare", ""))
        assertEquals(PageState.CHALLENGE, PageStates.code(403, "Just a moment...", "Performing security verification", 0))
        assertEquals(PageState.BLOCKED, PageStates.code(403, "Just a moment...", "Verify you are human by completing the action below.", 0))
        assertEquals(PageState.LOADED, PageStates.code(503, "Site under maintenance", "sign in below", 1)) // inputs: always judged
    }

    @Test
    fun unverifiedReasonFromTrace() {
        val base = RedFlagJson.decodeFromString(RiskReport.serializer(), File("../../contracts/fixtures/caution.json").readText())
        fun with(detail: String) = base.copy(status = "error", trace = listOf(TraceStep(10, "extract", detail)))
        assertEquals(Unverified.Reason.UNREACHABLE, Unverified.reason(with("load_error:-2")))
        assertEquals(Unverified.Reason.UNREACHABLE, Unverified.reason(with("page_state:unavailable")))
        assertEquals(Unverified.Reason.CHALLENGE, Unverified.reason(with("page_state:challenge")))
        assertEquals(Unverified.Reason.BLOCKED, Unverified.reason(with("http_403")))
        assertTrue(Unverified.chromeCanHelp(with("page_state:blocked")))
        assertTrue(!Unverified.chromeCanHelp(with("page_state:unavailable")))
        assertEquals(null, Unverified.reason(base.copy(status = "complete")))
    }
}
