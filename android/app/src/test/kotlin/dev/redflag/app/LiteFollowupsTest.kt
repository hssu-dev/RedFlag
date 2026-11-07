package dev.redflag.app

import android.view.ViewGroup
import dev.redflag.app.lite.ChatOutput
import dev.redflag.app.lite.DomainSignalSource
import dev.redflag.app.lite.DomainSignals
import dev.redflag.app.lite.Extraction
import dev.redflag.app.lite.FollowUp
import dev.redflag.app.lite.GemmaEngine
import dev.redflag.app.lite.GemmaOutput
import dev.redflag.app.lite.LiteDeps
import dev.redflag.app.lite.LiteEngine
import dev.redflag.app.lite.LiteResult
import dev.redflag.app.lite.PageExtractor
import dev.redflag.core.Contracts
import dev.redflag.core.gate.Gate
import dev.redflag.core.gate.GateResult
import dev.redflag.core.gate.InMemoryKnownGoodSet
import dev.redflag.core.gate.InMemorySafeVerdictCache
import dev.redflag.core.gate.StubSafeBrowsing
import dev.redflag.core.model.DataEndpoint
import dev.redflag.core.model.EngineInfo
import dev.redflag.core.model.Features
import dev.redflag.core.model.ObservedField
import dev.redflag.core.model.ObservedForm
import dev.redflag.core.model.PageObservation
import dev.redflag.core.model.RedFlagJson
import dev.redflag.core.model.TraceStep
import dev.redflag.core.report.isVerifiedSafe
import dev.redflag.core.scoring.ContextInput
import dev.redflag.core.scoring.ScoringEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v4 follow-ups through the REAL Lite pipeline: destination turn (D) and context re-scoring. Both only add. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiteFollowupsTest {
    private val bundle = Contracts.load()
    private val url = "https://parcel-redelivery-fee.com/"
    private val gate: GateResult = Gate(
        bundle, InMemoryKnownGoodSet(emptyList()), InMemorySafeVerdictCache(), StubSafeBrowsing(bundle.gateLists.safeBrowsingStub),
    ).evaluate(url).also { check(it is GateResult.Unknown) }

    private val payFeatures = Features(
        evidence = emptyList(), claimedBrand = null, brandMatchesDomain = "no_brand", pageType = "payment",
        requestedActions = listOf("enter_contact_info", "pay_fee"), urgencyOrThreat = false, tooGoodToBeTrue = false,
        addressesAiAnalyzer = false, verdict = "safe", confidence = "low",
    )

    private fun page(endpoints: List<DataEndpoint>) = PageObservation(
        finalUrl = url, title = "Redelivery", lang = "en", visibleText = "Enter your name and phone to reschedule.",
        forms = listOf(ObservedForm(action = endpoints.firstOrNull { it.via == "form" }?.url ?: url, method = "post",
            fields = listOf(ObservedField(type = "text", label = "Name", name = "name", autocomplete = "")))),
        dataEndpoints = endpoints,
    )

    private class FakeExtractor(private val obs: PageObservation) : PageExtractor {
        override suspend fun extract(url: String, host: ViewGroup?, timeoutMs: Long, shotName: String?, challengeWaitMs: Long) = Extraction(obs, 0, false, null, null, 5)
    }

    private class Gemma(private val features: Features, private val chatAnswer: (String) -> String?) : GemmaEngine {
        val chats = ArrayList<String>()
        override val info = EngineInfo("test", "scripted", false)
        override val backendLabel = "TEST"
        override suspend fun warmUp() = Result.success(Unit)
        override suspend fun generate(system: String, user: String, hostHint: String?, followUp: FollowUp?) =
            GemmaOutput(RedFlagJson.encodeToString(Features.serializer(), features), null, 1, null, null, "test")
        override suspend fun chat(system: String, user: String, tools: List<JsonObject>, maxTokens: Int): ChatOutput {
            chats += user
            val a = chatAnswer(user) ?: throw IllegalStateException("no answer")
            return ChatOutput(a, emptyList(), 1)
        }
    }

    private val oldDomain = object : DomainSignalSource { override suspend fun collect(registrableDomain: String) = DomainSignals(4000, "R11", 30, "test") }

    private fun run(g: Gemma, endpoints: List<DataEndpoint>): LiteResult {
        val engine = LiteEngine(LiteDeps(bundle, ScoringEngine(bundle), { null }, FakeExtractor(page(endpoints)), oldDomain, { g }))
        return runBlocking { engine.analyze(url, gate, null, { _, _, _ -> }, timeoutMs = 5_000) }
    }

    @Test
    fun unrelatedDestinationAddsPoints() {
        val g = Gemma(payFeatures) { """{"form_destination": "unrelated", "domain": "xxxxxxxx.test"}""" }
        val r = run(g, listOf(DataEndpoint("https://www.xxxxxxxx.test/send.php", "form")))
        assertEquals(1, g.chats.size)
        assertTrue(g.chats[0].contains("\"domain\": \"xxxxxxxx.test\""))
        assertFalse(g.chats[0].contains("Enter your name")) // no page text in the follow-up turn
        val f = r.report.redFlags.firstOrNull { it.ruleId == "destination_unrelated" }
        assertNotNull(f)
        assertEquals("destination: xxxxxxxx.test", f.evidence)
        assertTrue(r.report.trace.any { it.step == "gemma_destination" })
    }

    @Test
    fun knownServiceOrFailureAddsNothingAndRemovesNothing() {
        val eps = listOf(DataEndpoint("https://www.xxxxxxxx.test/send.php", "form"))
        val same = run(Gemma(payFeatures) { """{"form_destination": "known_service"}""" }, eps)
        val failed = run(Gemma(payFeatures) { null }, eps)
        assertEquals(same.report.score, failed.report.score)
        assertTrue(same.report.redFlags.none { it.ruleId == "destination_unrelated" })
    }

    @Test
    fun noPersonalDataNoQuestion() {
        val info = payFeatures.copy(pageType = "info", requestedActions = listOf("none"))
        val g = Gemma(info) { """{"form_destination": "unrelated"}""" }
        val p = page(listOf(DataEndpoint("https://www.xxxxxxxx.test/send.php", "form"))).copy(forms = emptyList())
        val engine = LiteEngine(LiteDeps(bundle, ScoringEngine(bundle), { null }, FakeExtractor(p), oldDomain, { g }))
        val r = runBlocking { engine.analyze(url, gate, null, { _, _, _ -> }, timeoutMs = 5_000) }
        assertEquals(0, g.chats.size)
        assertTrue(r.report.redFlags.none { it.ruleId == "destination_unrelated" })
    }

    @Test
    fun telegramBotCaughtByCodeWithoutAskingGemma() {
        val g = Gemma(payFeatures) { """{"form_destination": "same_org"}""" }
        val r = run(g, listOf(DataEndpoint("https://api.telegram.org/bot", "script"), DataEndpoint(url + "pay.php", "form")))
        assertEquals(0, g.chats.size) // code-known endpoint + same-site form: nothing for Gemma to judge
        assertTrue(r.report.redFlags.any { it.ruleId == "data_sent_to_messenger_bot" })
        assertFalse(r.report.isVerifiedSafe)
    }

    @Test
    fun contextRescoreKeepsIdAndOnlyAdds() {
        val r = run(Gemma(payFeatures) { null }, emptyList())
        assertTrue(r.report.isVerifiedSafe) // the page alone looks fine: this is the case context exists for
        val step = TraceStep(1, "context", "test")
        val next = r.rescore!!(ContextInput(scenario = "used_trade_escrow", consistency = "consistent", senderRisk = "unknown_sender", guided = false, evidence = listOf("안전결제")), step)
        assertEquals(r.report.id, next.id)
        assertEquals(70, next.score) // scenario 25 + used-trade payment 25 + unknown sender payment 20
        assertTrue(next.trace.last().step == "context")
        val none = r.rescore!!(ContextInput(scenario = "none", consistency = "consistent", senderRisk = "known_contact"), step)
        assertEquals(r.report.score, none.score)
    }

    @Test
    fun noRescoreForIncompleteReports() {
        val engine = LiteEngine(LiteDeps(bundle, ScoringEngine(bundle), { null }, FakeExtractor(page(emptyList())),
            object : DomainSignalSource { override suspend fun collect(registrableDomain: String) = DomainSignals(null, null, null, "x", lookupFailed = true) },
            { Gemma(payFeatures) { null } }))
        val r = runBlocking { engine.analyze(url, gate, null, { _, _, _ -> }, timeoutMs = 5_000) }
        assertEquals("error", r.report.status)
        assertNull(r.rescore)
    }
}
