package dev.redflag.core.followup

import dev.redflag.core.util.TextNorm
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `user_context` of POST /v1/analyze (docs/plans/manual_url_check.md §5): ONLY what the user handed over
 * (statements, the pasted/shared message), the source-app category and the device's judgment enums.
 * Raw chats read from notifications are never part of it. Numbers are masked, lengths and counts capped.
 * Twin of backend cloud_context.sanitize (contracts/followup_vectors.json "cloud_context").
 * Built only inside AnalysisController.requestCloud(), i.e. after the user tapped advanced analysis.
 */
object CloudContext {
    const val MAX_STATEMENTS = 4
    const val MAX_PASTED = 1
    const val MAX_CHIPS = 5

    data class DeviceJudgment(val scenario: String?, val consistency: String?, val senderRisk: String?, val guided: Boolean)

    /** null when there is nothing to send. */
    fun build(
        sourceCategory: String,
        statements: List<UserAnswer>,
        pasted: List<String>,
        device: DeviceJudgment?,
        catalog: ContextCatalog = FollowupContracts.catalog,
    ): JsonObject? {
        val lim = catalog.limits
        val st = statements.mapNotNull { a ->
            val q = catalog.questions[a.questionId] ?: return@mapNotNull null
            val valid = q.chips.map { it.id }
            val chips = a.chipIds.filter { it in valid }.distinct().take(MAX_CHIPS)
            val text = TextNorm.takeCodePoints(ContextText.mask(a.text), lim.statementChars)
            if (chips.isEmpty() && text.isBlank()) null else Triple(a.questionId, chips, text)
        }.take(MAX_STATEMENTS)
        val ps = pasted.map { TextNorm.takeCodePoints(ContextText.mask(it), lim.messageChars) }.filter { it.isNotBlank() }.take(MAX_PASTED)
        val dj = device?.let {
            buildJsonObject {
                put("scenario", it.scenario?.takeIf { v -> v in ContextJudge.SCENARIOS })
                put("consistency", it.consistency?.takeIf { v -> v in ContextJudge.CONSISTENCY })
                put("sender_risk", it.senderRisk?.takeIf { v -> v in ContextJudge.SENDER })
                put("guided", it.guided)
            }
        }
        if (st.isEmpty() && ps.isEmpty() && dj == null) return null
        return buildJsonObject {
            put("source_category", if (sourceCategory in catalog.categories) sourceCategory else "unknown")
            put("statements", buildJsonArray {
                st.forEach { (q, chips, text) ->
                    add(buildJsonObject {
                        put("question_id", q)
                        put("chip_ids", JsonArray(chips.map { JsonPrimitive(it) }))
                        put("text", text)
                    })
                }
            })
            put("pasted_messages", JsonArray(ps.map { buildJsonObject { put("text", it) } }))
            put("device_judgment", dj ?: JsonNull)
        }
    }

    /**
     * What the "what will be sent" sheet shows: each statement as "chip labels · text" (the chips go as ids,
     * shown here by their labels) and each pasted message exactly as sent (masked).
     */
    fun previewTexts(ctx: JsonObject?, korean: Boolean = true, catalog: ContextCatalog = FollowupContracts.catalog): List<String> {
        if (ctx == null) return emptyList()
        val out = ArrayList<String>()
        (ctx["statements"] as? JsonArray)?.forEach { s ->
            s as JsonObject
            val q = catalog.questions[(s["question_id"] as? JsonPrimitive)?.content]
            val chips = (s["chip_ids"] as? JsonArray).orEmpty().mapNotNull { id ->
                q?.chips?.firstOrNull { it.id == (id as JsonPrimitive).content }?.label?.let { if (korean) it.ko else it.en }
            }
            val text = (s["text"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            (chips + listOfNotNull(text)).joinToString(" · ").takeIf { it.isNotEmpty() }?.let(out::add)
        }
        (ctx["pasted_messages"] as? JsonArray)?.forEach { m -> ((m as JsonObject)["text"] as? JsonPrimitive)?.content?.let(out::add) }
        return out
    }
}
