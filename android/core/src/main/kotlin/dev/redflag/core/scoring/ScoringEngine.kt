@file:OptIn(ExperimentalSerializationApi::class)

package dev.redflag.core.scoring

import dev.redflag.core.Brand
import dev.redflag.core.ContractBundle
import dev.redflag.core.model.BrandInfo
import dev.redflag.core.model.Features
import dev.redflag.core.model.I18n
import dev.redflag.core.model.RedFlag
import dev.redflag.core.util.TextNorm
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// ---------------------------------------------------------------- input (scoring_rules.json doc INPUT)

@Serializable
data class FormField(
    val type: String? = null,
    val label: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val name: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val autocomplete: String? = null,
)

@Serializable
data class FormInput(
    @SerialName("action_domain") val actionDomain: String? = null,
    @SerialName("same_site") val sameSite: Boolean? = null,
    val fields: List<FormField> = emptyList(),
    /** If present, used as-is instead of classifying `fields`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("sensitive_fields") val sensitiveFields: List<String>? = null,
)

@Serializable
data class ScoringSignals(
    /** hit | no_match | unknown */
    @SerialName("safe_browsing") val safeBrowsing: String = "unknown",
    @SerialName("domain_age_days") val domainAgeDays: Int? = null,
    val punycode: Boolean = false,
    val homoglyph: Boolean = false,
    val forms: List<FormInput> = emptyList(),
    /** Page text hidden from humans (rule engine only: hidden_text_patterns). */
    @SerialName("hidden_text") val hiddenText: String? = null,
    /** v4: URLs the page sends data to (form actions, fetch/XHR/beacon, submit-script literals). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("data_endpoints") val dataEndpoints: List<String> = emptyList(),
    /** The site's host/CDN served its own phishing warning (e.g. "cloudflare_phishing"); observed by code. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("provider_warning") val providerWarning: String? = null,
)

/** v4: how the user got the link, as judged by Gemma (Turn 3) + the user's own answers. Additive only. */
@Serializable
data class ContextInput(
    /** parcel_smishing | used_trade_escrow | gov_voice_phishing | investment_scam | account_threat | romance_or_job | none */
    val scenario: String? = null,
    /** consistent | mismatch | unclear */
    val consistency: String? = null,
    /** unknown_sender | impersonated_org | known_contact | unclear */
    @SerialName("sender_risk") val senderRisk: String? = null,
    /** A call or message told the user to open the page. */
    val guided: Boolean = false,
    val evidence: List<String> = emptyList(),
)

@Serializable
data class ScoringInput(
    /** lite | cloud (selects gemma+rule / opus+rule as the model source label) */
    val mode: String = "lite",
    @SerialName("registrable_domain") val registrableDomain: String?,
    /** null when the model step timed out / failed. */
    val features: Features?,
    val signals: ScoringSignals,
    @SerialName("cloaking_suspected") val cloakingSuspected: Boolean = false,
    /** Model output was schema-invalid and fixed by FeatureRepair -> at most caution (decision D2). */
    @SerialName("features_repaired") val featuresRepaired: Boolean = false,
    /** Report status is timeout/error -> never safe (decision D3). */
    @SerialName("analysis_incomplete") val analysisIncomplete: Boolean = false,
    /** FeatureRepair had to fill a core field (claimed_brand / brand_matches_domain / requested_actions). */
    @SerialName("core_fields_repaired") val coreFieldsRepaired: Boolean = false,
    /** v4: Gemma's destination relation (same_org | known_service | unrelated | unclear), null if not asked. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("form_destination") val formDestination: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("form_destination_domain") val formDestinationDomain: String? = null,
    /** v4: context judgment, null when no context was given. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val context: ContextInput? = null,
)

data class ScoreResult(
    val score: Int,
    val level: String,
    val escalate: Boolean,
    /** Fired rules: floors first, then weight desc, then rule order. */
    val redFlags: List<RedFlag>,
    val headline: I18n,
    val brand: BrandInfo,
    /** Computed facts (debugging / tests). */
    val facts: Map<String, Any?> = emptyMap(),
) {
    val fired: List<String> get() = redFlags.map { it.ruleId }
}

/**
 * Generic interpreter of contracts/scoring_rules.json (twin of backend/redflag_backend/scoring.py).
 * Nothing about individual rules is hard-coded: facts are built as documented in `facts`, rules are
 * evaluated with the expression language in `doc`.
 */
class ScoringEngine(private val bundle: ContractBundle) {
    private companion object {
        val SENSITIVE_ACTIONS = setOf("enter_password", "enter_card", "enter_secret_code")
    }

    private val rules = bundle.scoringRules
    private val levelOrder = rules.levels.map { it.level }

    // ------------------------------------------------------------ facts

    private fun categoriesOf(form: FormInput): Set<String> {
        form.sensitiveFields?.let { return it.toSet() }
        val found = LinkedHashSet<String>()
        for (f in form.fields) {
            val ftype = (f.type ?: "").lowercase()
            val hay = TextNorm.normalize(listOf(f.type, f.label, f.name, f.autocomplete).joinToString(" ") { it ?: "" })
            for ((cat, spec) in rules.sensitiveFields.categories) {
                if (ftype in spec.types || spec.phrases.any { TextNorm.containsPhrase(hay, it) }) found += cat
            }
        }
        return found
    }

    /** report.signals.forms[].sensitive_fields — stable order password, card, secret (then any others). */
    fun sensitiveCategories(form: FormInput): List<String> {
        val cats = categoriesOf(form)
        val order = listOf("password", "card", "secret") + rules.sensitiveFields.categories.keys
        return order.distinct().filter { it in cats }
    }

    private val hiddenPatterns: List<Regex> by lazy { rules.hiddenTextPatterns.patterns.map { Regex(it) } }

    /** Deterministic backup for addresses_ai_analyzer (scoring_rules.json hidden_text_patterns). */
    fun hiddenAiInstruction(hiddenText: String?): Boolean {
        val t = TextNorm.normalizeForPatterns(hiddenText)
        return t.isNotEmpty() && hiddenPatterns.any { it.containsMatchIn(t) }
    }

    fun matchBrands(claimed: String?): List<Brand> {
        val c = TextNorm.normalize(claimed)
        if (c.isEmpty()) return emptyList()
        return bundle.brands.brands.filter { b ->
            b.aliases.any { alias ->
                val a = TextNorm.normalize(alias)
                a.isNotEmpty() && (a == c || (b.match == "token" && " $c ".contains(" $a ")))
            }
        }
    }

    private fun isProcessor(domain: String): Boolean {
        val d = domain.lowercase().trimEnd('.')
        return rules.knownPaymentProcessors.any { d == it || d.endsWith(".$it") }
    }

    fun buildFacts(input: ScoringInput): Map<String, Any?> {
        val feats = input.features
        val sig = input.signals
        val reg = input.registrableDomain?.lowercase()?.ifEmpty { null }

        val claimed = feats?.claimedBrand?.takeIf { it.isNotBlank() }
        val matched = matchBrands(claimed)
        val official = matched.isNotEmpty() && reg != null && matched.any { b -> b.domains.any { it.lowercase() == reg } }

        var offending: String? = null
        for (form in sig.forms) {
            val action = form.actionDomain
            if (form.sameSite == false && !action.isNullOrEmpty() && !isProcessor(action) && categoriesOf(form).isNotEmpty()) {
                offending = action
                break
            }
        }
        val evidence0 = feats?.evidence?.firstOrNull()?.takeIf { it.isNotBlank() }
        val actions = feats?.requestedActions.orEmpty()
        val sensitiveRequest = (actions.any { it in SENSITIVE_ACTIONS }) ||
            sig.forms.any { categoriesOf(it).isNotEmpty() }
        val personalRequest = sensitiveRequest || actions.any { it == "enter_personal_id" || it == "enter_contact_info" }
        val paymentRequest = actions.any { it == "pay_fee" || it == "enter_card" } || feats?.pageType == "payment"
        val dd = rules.dataDestinations
        val botHost = sig.dataEndpoints.firstNotNullOfOrNull { DataEndpoints.match(it, dd.messengerBot) }
        val svcHost = sig.dataEndpoints.firstNotNullOfOrNull { DataEndpoints.match(it, dd.formService) }
        val ctx = input.context
        return linkedMapOf(
            "safe_browsing_hit" to (sig.safeBrowsing == "hit"),
            "provider_warning" to sig.providerWarning?.takeIf { it.isNotBlank() },
            "domain_age_days" to sig.domainAgeDays,
            "registrable_domain" to reg,
            "claimed_brand" to claimed,
            "brand_known" to matched.isNotEmpty(),
            "brand_official" to official,
            "official_domains" to (matched.firstOrNull()?.domains?.toList() ?: emptyList<String>()),
            "model_brand_matches_domain" to feats?.brandMatchesDomain,
            "sensitive_form_cross_site" to (offending != null),
            "form_action_domain" to offending,
            "punycode_or_homoglyph" to sig.homoglyph,
            "requested_actions" to (feats?.requestedActions ?: emptyList<String>()),
            "urgency_or_threat" to (feats?.urgencyOrThreat == true),
            "too_good_to_be_true" to (feats?.tooGoodToBeTrue == true),
            "addresses_ai_analyzer" to (feats?.addressesAiAnalyzer == true),
            "cloaking_suspected" to input.cloakingSuspected,
            "sensitive_request" to sensitiveRequest,
            "features_repaired" to input.featuresRepaired,
            "analysis_incomplete" to input.analysisIncomplete,
            "core_fields_repaired" to input.coreFieldsRepaired,
            "hidden_ai_instruction" to hiddenAiInstruction(sig.hiddenText),
            "evidence0" to evidence0,
            "data_endpoints" to sig.dataEndpoints,
            "exfil_messenger_bot" to (botHost != null),
            "exfil_form_service" to (svcHost != null),
            "exfil_host" to (botHost ?: svcHost),
            "personal_request" to personalRequest,
            "form_destination" to input.formDestination?.takeIf { it.isNotBlank() },
            "form_destination_domain" to input.formDestinationDomain?.takeIf { it.isNotBlank() },
            "payment_request" to paymentRequest,
            "context_scenario" to ctx?.scenario?.takeIf { it.isNotBlank() },
            "context_consistency" to ctx?.consistency?.takeIf { it.isNotBlank() },
            "context_sender_risk" to ctx?.senderRisk?.takeIf { it.isNotBlank() },
            "context_guided" to (ctx?.guided == true),
            "context_evidence0" to ctx?.evidence?.firstOrNull()?.takeIf { it.isNotBlank() },
        )
    }

    // ------------------------------------------------------------ score

    fun score(input: ScoringInput): ScoreResult {
        val facts = buildFacts(input)
        val modelSource = if (input.mode == "cloud") "opus+rule" else "gemma+rule"

        var total = 0
        val floors = ArrayList<Int>()
        val clamps = ArrayList<IntRange>()
        data class Fired(val rank: Int, val weight: Int, val order: Int, val flag: RedFlag)
        val fired = ArrayList<Fired>()
        rules.rules.forEachIndexed { order, rule ->
            if (!Expressions.evaluate(rule.whenExpr, facts)) return@forEachIndexed
            val (rank, weight) = when (rule.kind) {
                "floor" -> 0 to (rule.value ?: 0).also { floors += it }
                "add" -> 1 to (rule.weight ?: 0).also { total += it }
                "clamp" -> 2 to 0.also { clamps += (rule.min ?: rules.score.min)..(rule.max ?: rules.score.max) }
                else -> throw IllegalArgumentException("unknown rule kind: ${rule.kind}")
            }
            val flag = RedFlag(
                ruleId = rule.id,
                severity = rule.severity,
                source = if (rule.source == "model") modelSource else "signal+rule",
                weight = weight,
                title = Templates.fillI18n(rule.title, facts),
                evidence = Templates.fill(rule.evidence, facts, strict = true),
            )
            fired += Fired(rank, weight, order, flag)
        }
        fired.sortWith(compareBy<Fired>({ it.rank }, { -it.weight }, { it.order }))

        var s = total.coerceIn(rules.score.min, rules.score.max)
        for (c in clamps) s = s.coerceIn(c) // repaired output -> at most caution; unfinished analysis -> never safe
        for (f in floors) s = maxOf(s, f) // floors (Safe Browsing) beat clamps

        var level = rules.levels.first().level
        for (entry in rules.levels) if (s >= entry.min) level = entry.level
        val escalate = s >= rules.escalation.min && s <= rules.escalation.max

        val firedIds = fired.map { it.flag.ruleId }.toSet()
        var headline: I18n? = null
        if (levelOrder.indexOf(level) >= levelOrder.indexOf(rules.headlineMinLevel)) {
            for (rid in rules.headlinePriority) {
                val h = rules.headlineByRule[rid]
                if (rid in firedIds && h != null) {
                    headline = Templates.fillI18n(h, facts)
                    break
                }
            }
        }
        if (headline == null) headline = rules.headlineByLevel.getValue(level)

        @Suppress("UNCHECKED_CAST")
        val brand = BrandInfo(
            claimed = facts["claimed_brand"] as String?,
            officialDomains = facts["official_domains"] as List<String>,
            matches = if (facts["brand_known"] == true) facts["brand_official"] as Boolean else null,
        )
        return ScoreResult(s, level, escalate, fired.map { it.flag }, headline, brand, facts)
    }
}

/** scoring_rules.json data_destinations matching (twin of scoring.py parse_endpoint / _endpoint_match). */
object DataEndpoints {
    private val URL_RE = Regex("^[hH][tT][tT][pP][sS]?://([^/?#]*)([^?#]*)")
    private val PORT_RE = Regex(":[0-9]*$")

    /** (host, path) of an http(s) URL; null otherwise. */
    fun parse(url: String): Pair<String, String>? {
        val m = URL_RE.find(url.trim()) ?: return null
        val host = m.groupValues[1].substringAfterLast('@').replace(PORT_RE, "").lowercase().trimEnd('.')
        if (host.isEmpty()) return null
        return host to m.groupValues[2].ifEmpty { "/" }
    }

    fun hostMatches(host: String, entryHost: String) = host == entryHost || host.endsWith(".$entryHost")

    /** Host of [url] if it matches one of [entries], else null. */
    fun match(url: String, entries: List<dev.redflag.core.EndpointEntry>): String? {
        val (host, path) = parse(url) ?: return null
        return host.takeIf { entries.any { e -> hostMatches(host, e.host) && path.startsWith(e.pathPrefix) } }
    }
}

/** scoring_rules.json expression language. */
object Expressions {
    fun evaluate(expr: JsonElement, facts: Map<String, Any?>): Boolean {
        val obj = expr as? JsonObject ?: throw IllegalArgumentException("unknown expression: $expr")
        obj["fact"]?.let { return facts[it.jsonPrimitive.content] == true }
        obj["eq"]?.let { val (name, v) = pair(it); return factEquals(facts[name], v) }
        obj["lt"]?.let {
            val (name, v) = pair(it)
            val f = facts[name]
            val n = (v as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull ?: return false
            return f is Number && f.toDouble() < n
        }
        obj["has"]?.let {
            val (name, v) = pair(it)
            val f = facts[name] as? List<*> ?: return false
            return f.any { item -> factEquals(item, v) }
        }
        obj["not_null"]?.let { return facts[it.jsonPrimitive.content] != null }
        obj["all"]?.let { return it.jsonArray.all { e -> evaluate(e, facts) } }
        obj["any"]?.let { return it.jsonArray.any { e -> evaluate(e, facts) } }
        obj["not"]?.let { return !evaluate(it, facts) }
        throw IllegalArgumentException("unknown expression: $expr")
    }

    private fun pair(e: JsonElement): Pair<String, JsonElement> {
        val a = e as JsonArray
        return a[0].jsonPrimitive.content to a[1]
    }

    /** Python `==` between a fact value and a JSON literal. */
    fun factEquals(fact: Any?, v: JsonElement): Boolean = when {
        v is JsonNull -> fact == null
        v is JsonPrimitive && v.isString -> fact is String && fact == v.content
        v is JsonPrimitive && v.booleanOrNull != null -> when (fact) {
            is Boolean -> fact == v.booleanOrNull
            is Number -> fact.toDouble() == (if (v.booleanOrNull == true) 1.0 else 0.0)
            else -> false
        }
        v is JsonPrimitive -> v.doubleOrNull?.let { d ->
            when (fact) {
                is Number -> fact.toDouble() == d
                is Boolean -> (if (fact) 1.0 else 0.0) == d
                else -> false
            }
        } ?: false
        v is JsonArray -> fact is List<*> && fact.size == v.size && fact.indices.all { factEquals(fact[it], v[it]) }
        else -> false
    }
}

/** `{name}` placeholders (scoring_rules.json doc TEMPLATES). */
object Templates {
    private fun fmt(v: Any?): String? = when (v) {
        null -> null
        is List<*> -> if (v.isEmpty()) null else v.joinToString(", ") { it.toString() }
        is Boolean -> if (v) "true" else "false"
        else -> v.toString().takeIf { it.isNotBlank() }
    }

    /** strict = true -> null if any placeholder is null/empty; otherwise "?" is substituted. */
    fun fill(template: String?, facts: Map<String, Any?>, strict: Boolean): String? {
        if (template == null) return null
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val j = template.indexOf('{', i)
            if (j == -1) { out.append(template, i, template.length); break }
            val k = template.indexOf('}', j)
            if (k == -1) { out.append(template, i, template.length); break }
            out.append(template, i, j)
            val v = fmt(facts[template.substring(j + 1, k)])
            if (v == null) {
                if (strict) return null
                out.append('?')
            } else {
                out.append(v)
            }
            i = k + 1
        }
        return out.toString()
    }

    fun fillI18n(t: I18n, facts: Map<String, Any?>): I18n =
        I18n(en = fill(t.en, facts, false)!!, ko = fill(t.ko, facts, false)!!)
}
