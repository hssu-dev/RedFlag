package dev.redflag.core.gemma

import dev.redflag.core.model.Features
import dev.redflag.core.model.RedFlagJson
import dev.redflag.core.util.TextNorm
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * @property obj the parsed JSON object (null on parse failure)
 * @property error run_eval.py parse_error: no_json | truncated | bad_json(<python msg>) | not_object, else null
 * @property clean the answer was pure JSON with nothing around it
 * @property schemaErrors run_eval.py validate() output (empty when obj is null)
 * @property features decoded Features, only when obj != null and schemaErrors is empty
 */
data class ParseResult(
    val obj: JsonObject?,
    val error: String?,
    val clean: Boolean,
    val schemaErrors: List<String>,
    val features: Features?,
) {
    val ok: Boolean get() = features != null
}

/** Exact port of redflag-gemma-test/run_eval.py extract_json() + validate(). */
object GemmaOutputParser {
    val KEYS = Features.LITE_KEYS
    val ENUMS: Map<String, List<String>> = linkedMapOf(
        "brand_matches_domain" to listOf("yes", "no", "unclear", "no_brand"),
        "page_type" to listOf("login", "payment", "shop", "download", "support", "info", "other"),
        "verdict" to listOf("safe", "suspicious", "malicious"),
        "confidence" to listOf("low", "medium", "high"),
    )
    val ACTIONS = listOf(
        "enter_password", "enter_card", "enter_personal_id", "enter_secret_code",
        "enter_contact_info", "pay_fee", "call_phone", "install_app", "connect_wallet", "none",
    )
    val BOOLS = listOf("urgency_or_threat", "too_good_to_be_true", "addresses_ai_analyzer")

    private val FENCE = Regex("```(?:json)?")

    fun parse(raw: String): ParseResult {
        val (obj, error, clean) = extractJson(raw)
        if (obj == null) return ParseResult(null, error, clean, emptyList(), null)
        val errs = validate(obj)
        val features = if (errs.isEmpty()) {
            runCatching { RedFlagJson.decodeFromJsonElement(Features.serializer(), obj) }.getOrNull()
        } else null
        return ParseResult(obj, null, clean, errs, features)
    }

    /** Returns (obj, error, clean). */
    fun extractJson(raw: String): Triple<JsonObject?, String?, Boolean> {
        var text = raw
        val ch = text.lastIndexOf("<channel|>")
        if (ch >= 0) text = text.substring(ch + "<channel|>".length)
        val stripped = TextNorm.pyStrip(text)
        val clean = stripped.startsWith("{") && stripped.endsWith("}")
        text = FENCE.replace(text, "")
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1) return Triple(null, "no_json", clean)
        if (end <= start) return Triple(null, "truncated", clean)
        val obj = try {
            PyJson.loads(text.substring(start, end + 1))
        } catch (e: PyJson.DecodeError) {
            return Triple(null, "bad_json(${e.msg})", clean)
        }
        if (obj !is JsonObject) return Triple(null, "not_object", clean)
        return Triple(obj, null, clean)
    }

    private fun isStr(e: JsonElement?) = e is JsonPrimitive && e.isString
    private fun isBool(e: JsonElement?) = e is JsonPrimitive && e !is JsonNull && !e.isString &&
        (e.content == "true" || e.content == "false")

    fun validate(obj: JsonObject): List<String> {
        val errs = ArrayList<String>()
        for (k in KEYS) if (k !in obj) errs += "missing:$k"
        for ((k, allowed) in ENUMS) {
            val v = obj[k] ?: continue
            if (!(isStr(v) && (v as JsonPrimitive).content in allowed)) errs += "enum:$k"
        }
        for (k in BOOLS) {
            val v = obj[k] ?: continue
            if (!isBool(v)) errs += "type:$k"
        }
        val actions = obj["requested_actions"]
        if (actions !is JsonArray || actions.isEmpty() || actions.any { !(isStr(it) && (it as JsonPrimitive).content in ACTIONS) }) {
            errs += "enum:requested_actions"
        }
        val brand = obj["claimed_brand"]
        if (brand != null && brand !is JsonNull && !isStr(brand)) errs += "type:claimed_brand"
        val evidence = obj["evidence"]
        if (evidence !is JsonArray || evidence.size > 3) errs += "evidence"
        if (obj.keys.filter { it in KEYS } != KEYS.filter { it in obj }) errs += "order"
        return errs
    }
}
