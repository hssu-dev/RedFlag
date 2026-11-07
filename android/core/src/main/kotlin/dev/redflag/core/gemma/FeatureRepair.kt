package dev.redflag.core.gemma

import dev.redflag.core.model.Features
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Conservative repair of a PARSED but schema-invalid Gemma answer (contracts/README.md "Feature repair").
 * On real pages E4B sometimes drops boolean keys (M6b: 12/120 missing addresses_ai_analyzer /
 * too_good_to_be_true). Throwing the whole answer away would score the page on signals only, which
 * almost always means "safe"; repairing keeps the brand/actions evidence and only defaults what is
 * missing to the no-flag value. Python twin: backend/redflag_backend/gemma_repair.py.
 *
 * Rules: missing/non-bool flag -> false; evidence: keep strings, max 3; claimed_brand non-string -> null;
 * requested_actions: drop unknown values, empty -> ["none"]; bad enum -> neutral (unclear / other /
 * suspicious / low). Key order is irrelevant. Unparseable text is never repaired.
 */
object FeatureRepair {
    private val ACTIONS = setOf(
        "enter_password", "enter_card", "enter_personal_id", "enter_secret_code", "enter_contact_info",
        "pay_fee", "call_phone", "install_app", "connect_wallet", "none",
    )
    private val BMD = setOf("yes", "no", "unclear", "no_brand")
    private val PAGE = setOf("login", "payment", "shop", "download", "support", "info", "other")
    private val VERDICT = setOf("safe", "suspicious", "malicious")
    private val CONF = setOf("low", "medium", "high")

    data class Repaired(val features: Features, val repairs: List<String>)

    fun repair(obj: JsonObject): Repaired {
        val repairs = ArrayList<String>()
        fun str(k: String): String? = (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun enumOr(k: String, allowed: Set<String>, fallback: String): String =
            str(k)?.takeIf { it in allowed } ?: fallback.also { repairs += "$k->$fallback" }
        fun flag(k: String): Boolean =
            (obj[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false.also { repairs += "$k->false" }

        val evRaw = obj["evidence"] as? JsonArray
        val evidence = evRaw?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.take(3) ?: emptyList()
        if (evRaw == null || evidence.size != evRaw.size) repairs += "evidence"

        val brandEl: JsonElement? = obj["claimed_brand"]
        val brand = (brandEl as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (brandEl == null || (brand == null && brandEl.toString() != "null")) repairs += "claimed_brand->null"

        val actRaw = obj["requested_actions"] as? JsonArray
        var actions = actRaw?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { a -> a in ACTIONS } }?.distinct() ?: emptyList()
        if (actRaw == null || actions.size != actRaw.size) repairs += "requested_actions"
        if (actions.isEmpty()) actions = listOf("none")
        if (actions.size > 1 && "none" in actions) actions = actions - "none"

        val f = Features(
            evidence = evidence,
            claimedBrand = brand,
            brandMatchesDomain = enumOr("brand_matches_domain", BMD, if (brand == null) "no_brand" else "unclear"),
            pageType = enumOr("page_type", PAGE, "other"),
            requestedActions = actions,
            urgencyOrThreat = flag("urgency_or_threat"),
            tooGoodToBeTrue = flag("too_good_to_be_true"),
            addressesAiAnalyzer = flag("addresses_ai_analyzer"),
            verdict = enumOr("verdict", VERDICT, "suspicious"),
            confidence = enumOr("confidence", CONF, "low"),
        )
        return Repaired(f, repairs)
    }
}
