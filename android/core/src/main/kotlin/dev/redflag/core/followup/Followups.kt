package dev.redflag.core.followup

import dev.redflag.core.Contracts
import dev.redflag.core.ScoringRules
import dev.redflag.core.gemma.PromptBuilder
import dev.redflag.core.model.DataEndpoint
import dev.redflag.core.model.Features
import dev.redflag.core.model.I18n
import dev.redflag.core.model.RedFlagJson
import dev.redflag.core.scoring.ContextInput
import dev.redflag.core.scoring.DataEndpoints
import dev.redflag.core.util.TextNorm
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/*
 * Gemma follow-up turns after the (unchanged, validated) Turn 1 page reading:
 *   D  destination relation  (docs/plans/form_destination_observation.md)
 *   T2 does context matter?   (tool calls; docs/plans/context_analysis.md)
 *   T3 context judgment
 * Each runs in its OWN short conversation that never contains page text: inputs are code-computed facts and
 * Turn 1's enum answers (+ the user's own context in T3). Twin: backend/redflag_backend/lite_followups.py;
 * both pass contracts/followup_vectors.json. Everything here can only ADD to the score.
 */

// ---------------------------------------------------------------- contracts

@Serializable
data class SourceEntry(val `package`: String, val category: String, val name: I18n)

@Serializable
data class Chip(val id: String, val label: I18n, val guided: Boolean = false)

@Serializable
data class Question(val text: I18n, val chips: List<Chip>)

@Serializable
data class ConsentText(
    val title: I18n, val body: I18n, val allow: I18n, val skip: I18n, val never: I18n,
    @SerialName("needs_access") val needsAccess: I18n,
    @SerialName("open_settings") val openSettings: I18n,
)

@Serializable
data class FreeInputText(val title: I18n, val hint: I18n, val send: I18n, val skip: I18n, @SerialName("default_question") val defaultQuestion: String)

@Serializable
data class CatalogLimits(
    @SerialName("messages_before") val messagesBefore: Int,
    @SerialName("message_chars") val messageChars: Int,
    @SerialName("statement_chars") val statementChars: Int,
    @SerialName("notification_ttl_minutes") val notificationTtlMinutes: Int,
    @SerialName("buffer_size") val bufferSize: Int,
)

@Serializable
data class ContextCatalog(
    val version: Int,
    val categories: Map<String, I18n>,
    val sources: List<SourceEntry>,
    @SerialName("auto_context_categories") val autoContextCategories: List<String>,
    val reasons: Map<String, I18n>,
    val consent: ConsentText,
    val questions: Map<String, Question>,
    @SerialName("free_input") val freeInput: FreeInputText,
    val scenarios: Map<String, I18n>,
    val limits: CatalogLimits,
)

object FollowupContracts {
    private fun req(path: String) = Contracts.classpathText(path) ?: error("missing contract resource: $path")
    val destinationPrompt: String by lazy { TextNorm.pyStrip(req("contracts/gemma_destination.txt")) }
    val contextNeedPrompt: String by lazy { TextNorm.pyStrip(req("contracts/gemma_context_need.txt")) }
    val contextPrompt: String by lazy { TextNorm.pyStrip(req("contracts/gemma_context.txt")) }
    val pageStatePrompt: String by lazy { TextNorm.pyStrip(req("contracts/gemma_page_state.txt")) }
    /** OpenAPI function objects {name, description, parameters} (contracts/context_tools.json). */
    val tools: List<JsonObject> by lazy {
        RedFlagJson.parseToJsonElement(req("contracts/context_tools.json")).jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
    }
    val catalog: ContextCatalog by lazy { RedFlagJson.decodeFromString(ContextCatalog.serializer(), req("contracts/context_catalog.json")) }
}

/** Text from a page, a chat or the user, made safe to sit inside a prompt block (no tag break-out, no quotes). */
fun untrustedText(s: String?): String = TextNorm.sanitizeForPrompt(s).replace('<', '‹').replace('>', '›')

private fun block(tag: String, e: JsonElement) = "<$tag>\n${PromptBuilder.pyDumps(e)}\n</$tag>"

private fun strList(v: List<String>) = buildJsonArray { v.forEach { add(JsonPrimitive(it)) } }

/** First '{' .. last '}' of a model answer as a JSON object, or null. */
fun jsonObjectIn(text: String): JsonObject? {
    val a = text.indexOf('{')
    val b = text.lastIndexOf('}')
    if (a < 0 || b <= a) return null
    return runCatching { RedFlagJson.parseToJsonElement(text.substring(a, b + 1)) as? JsonObject }.getOrNull()
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

// ---------------------------------------------------------------- D: destination relation

object Destinations {
    val RELATIONS = listOf("same_org", "known_service", "unrelated", "unclear")
    private val VIA_ORDER = listOf("form", "script", "xhr", "fetch", "beacon")
    const val MAX_CANDIDATES = 5

    data class Candidate(val domain: String, val via: String)
    data class Answer(val relation: String, val domain: String)

    /**
     * Cross-site destinations worth asking Gemma about: only when the page asks for personal data, and never
     * for the page's own domain, known payment processors, known data services, or endpoints the code already
     * flags (messenger_bot / form_service).
     */
    fun candidates(
        endpoints: List<DataEndpoint>,
        pageDomain: String?,
        personalRequest: Boolean,
        rules: ScoringRules,
        domainOf: (String) -> String?,
    ): List<Candidate> {
        if (!personalRequest) return emptyList()
        val dd = rules.dataDestinations
        fun listed(domain: String, list: List<String>) = list.any { DataEndpoints.hostMatches(domain, it) }
        val out = LinkedHashMap<String, Candidate>()
        val sorted = endpoints.withIndex().sortedWith(compareBy({ VIA_ORDER.indexOf(it.value.via).let { i -> if (i < 0) VIA_ORDER.size else i } }, { it.index }))
        for ((_, e) in sorted) {
            if (out.size >= MAX_CANDIDATES) break
            if (DataEndpoints.match(e.url, dd.messengerBot) != null || DataEndpoints.match(e.url, dd.formService) != null) continue
            val d = domainOf(e.url)?.lowercase() ?: continue
            if (d == pageDomain?.lowercase() || listed(d, rules.knownPaymentProcessors) || listed(d, dd.knownDataServices)) continue
            out.getOrPut(d) { Candidate(d, e.via) }
        }
        return out.values.toList()
    }

    fun userMessage(pageDomain: String?, features: Features, candidates: List<Candidate>): String = block("analysis", buildJsonObject {
        put("page_domain", pageDomain)
        put("claimed_brand", features.claimedBrand?.let { untrustedText(it) })
        put("page_type", features.pageType)
        put("requested_actions", strList(features.requestedActions))
        put("destinations", buildJsonArray { candidates.forEach { c -> add(buildJsonObject { put("domain", c.domain); put("via", c.via) }) } })
    })

    /** Validated answer; null when unusable (then nothing is added). A domain not among the candidates -> the first one. */
    fun parseAnswer(text: String, candidates: List<Candidate>): Answer? {
        if (candidates.isEmpty()) return null
        val obj = jsonObjectIn(text)
        val rel = obj?.str("form_destination")?.trim()?.lowercase()?.takeIf { it in RELATIONS }
            ?: Regex("\\b(same_org|known_service|unrelated|unclear)\\b").find(text)?.groupValues?.get(1)
            ?: return null
        val dom = obj?.str("domain")?.trim()?.lowercase()?.takeIf { d -> candidates.any { it.domain == d } } ?: candidates.first().domain
        return Answer(rel, dom)
    }
}

// ---------------------------------------------------------------- context: sources, masking

object ContextSources {
    data class Source(val category: String, val name: I18n?, val packageName: String?)

    fun classify(packageName: String?, catalog: ContextCatalog = FollowupContracts.catalog): Source {
        val e = catalog.sources.firstOrNull { it.`package` == packageName }
        return if (e != null) Source(e.category, e.name, packageName) else Source("unknown", null, packageName)
    }
}

object ContextText {
    private val NUMBER_RUN = Regex("\\+?[0-9][0-9\\-. ]{5,}[0-9]")

    /** Phone / account / card / ID numbers: any digit run (with - . space) holding >= 7 digits -> digits become '#'. */
    fun mask(s: String): String = NUMBER_RUN.replace(s) { m ->
        if (m.value.count { it in '0'..'9' } >= 7) m.value.map { if (it in '0'..'9') '#' else it }.joinToString("") else m.value
    }
}

// ---------------------------------------------------------------- context: ① code decision, ② Turn 2

enum class ContextDecision { EXCLUDE, NEEDED, FINE }

data class ContextPlan(
    /** "May I analyze the context?" (source app + messages around the link) */
    val auto: Boolean,
    /** U1..U4, or null */
    val question: String?,
    val reasons: List<String>,
    /** gemma | code_default */
    val origin: String,
)

object ContextPolicy {
    private val RISKY_ACTIONS = setOf("pay_fee", "enter_card", "enter_personal_id", "enter_secret_code", "install_app", "connect_wallet")
    val QUESTIONS = listOf("U1", "U2", "U3", "U4")
    private val CHAT_SOURCES = setOf("messenger", "sms")

    data class Input(
        val score: Int,
        val complete: Boolean,
        val safeBrowsingHit: Boolean,
        val features: Features?,
        val sourceCategory: String,
        /** "don't ask again" */
        val neverAsk: Boolean = false,
        /** context already asked for this site in this session */
        val alreadyAsked: Boolean = false,
    )

    /** ① deterministic: exclude / needed / fine (fine -> ask Gemma for a second opinion). */
    fun decide(i: Input): ContextDecision {
        val f = i.features
        if (f == null || !i.complete || i.safeBrowsingHit || i.score >= 85 || i.neverAsk || i.alreadyAsked) return ContextDecision.EXCLUDE
        if (i.score in 30..70) return ContextDecision.NEEDED
        if (f.requestedActions.any { it in RISKY_ACTIONS } && i.sourceCategory in CHAT_SOURCES) return ContextDecision.NEEDED
        return ContextDecision.FINE
    }

    fun turn2Message(i: Input, decision: ContextDecision, pageDomain: String?, domainAgeDays: Int?, level: String): String {
        val f = i.features!!
        return block("analysis", buildJsonObject {
            put("source_app", i.sourceCategory)
            put("code_decision", if (decision == ContextDecision.NEEDED) "needed" else "fine")
            put("page_domain", pageDomain)
            put("domain_age_days", domainAgeDays)
            put("level", level)
            put("claimed_brand", f.claimedBrand?.let { untrustedText(it) })
            put("brand_matches_domain", f.brandMatchesDomain)
            put("page_type", f.pageType)
            put("requested_actions", strList(f.requestedActions))
        })
    }

    data class ToolCallIn(val name: String, val args: JsonObject)
    data class Parsed(val auto: Boolean, val question: String?, val reasons: List<String>, val none: Boolean, val recognized: Int)

    /** Turn 2 tool calls -> request; unknown tools / values are dropped. */
    fun parseToolCalls(calls: List<ToolCallIn>, catalog: ContextCatalog = FollowupContracts.catalog): Parsed {
        var auto = false
        var question: String? = null
        var none = false
        var recognized = 0
        val reasons = LinkedHashSet<String>()
        fun addReasons(a: JsonObject) {
            (a["reasons"] as? JsonArray)?.forEach { r -> (r as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it in catalog.reasons }?.let { reasons += it } }
        }
        for (c in calls) when (c.name) {
            "request_auto_context" -> { auto = true; recognized++; addReasons(c.args) }
            "request_user_context" -> {
                recognized++
                val q = (c.args["question_id"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase()
                question = question ?: q?.takeIf { it in QUESTIONS } ?: "U1"
                addReasons(c.args)
            }
            "no_context_needed" -> { none = true; recognized++ }
        }
        return Parsed(auto, question, reasons.toList(), none && !auto && question == null, recognized)
    }

    /** Text fallback: the model wrote a call as text (e.g. JSON {"name":..., "arguments":...}). */
    fun toolCallsFromText(text: String): List<ToolCallIn> {
        val obj = jsonObjectIn(text) ?: return emptyList()
        val name = obj.str("name") ?: obj.str("tool") ?: return emptyList()
        val args = (obj["arguments"] ?: obj["args"] ?: obj["parameters"]) as? JsonObject ?: JsonObject(emptyMap())
        return listOf(ToolCallIn(name, args))
    }

    private fun defaultReasons(f: Features): List<String> = buildList {
        val a = f.requestedActions
        if ("install_app" in a) add("app_install")
        if ("pay_fee" in a || "enter_card" in a || f.pageType == "payment") add("unexpected_payment")
        if (f.brandMatchesDomain == "no" || f.brandMatchesDomain == "unclear") add("brand_unverifiable")
        if (isEmpty()) add("other")
    }

    private fun defaultQuestion(f: Features): String = when {
        "install_app" in f.requestedActions -> "U3"
        f.pageType == "login" || "enter_password" in f.requestedActions -> "U2"
        else -> "U1"
    }

    /** Reasons that justify a context card on their own ("other" alone is too vague: user decision 2026-11-04). */
    fun concreteReasons(reasons: List<String>) = reasons.filter { it != "other" }

    /**
     * Decision table (plan §4). Gemma can only ADD a request; it cannot cancel the code's "needed".
     * When the code said "fine", a Gemma request counts only with at least one concrete reason (not just "other"):
     * measured on eval/real, SMS-origin benign pages with a card 33% -> 18%, phishing 90% -> 85%.
     */
    fun resolve(decision: ContextDecision, parsed: Parsed?, sourceCategory: String, features: Features?,
                catalog: ContextCatalog = FollowupContracts.catalog): ContextPlan? {
        if (decision == ContextDecision.EXCLUDE || features == null) return null
        val autoOk = sourceCategory in catalog.autoContextCategories || sourceCategory == "unknown"
        val vague = decision == ContextDecision.FINE && parsed != null && concreteReasons(parsed.reasons).isEmpty()
        if (parsed != null && (parsed.auto || parsed.question != null) && !vague) {
            val auto = parsed.auto && autoOk
            val q = parsed.question ?: if (parsed.auto && !autoOk) "U1" else null
            return ContextPlan(auto, q, parsed.reasons.ifEmpty { defaultReasons(features) }, "gemma")
        }
        if (decision == ContextDecision.FINE) return null
        return if (sourceCategory in CHAT_SOURCES) ContextPlan(true, null, defaultReasons(features), "code_default")
        else ContextPlan(false, defaultQuestion(features), defaultReasons(features), "code_default")
    }
}

// ---------------------------------------------------------------- context: Turn 3

data class ConvMessage(val sender: String, val text: String, val hasLink: Boolean)
data class UserAnswer(val questionId: String, val chipIds: List<String>, val text: String)

data class ContextJudgment(val scenario: String?, val consistency: String?, val senderRisk: String?, val evidence: List<String>)

object ContextJudge {
    val SCENARIOS = listOf("parcel_smishing", "used_trade_escrow", "gov_voice_phishing", "investment_scam", "account_threat", "romance_or_job", "none")
    val CONSISTENCY = listOf("consistent", "mismatch", "unclear")
    val SENDER = listOf("unknown_sender", "impersonated_org", "known_contact", "unclear")
    const val MAX_EVIDENCE_WORDS = 15

    fun conversationJson(appName: String?, messages: List<ConvMessage>, limits: CatalogLimits = FollowupContracts.catalog.limits): JsonObject = buildJsonObject {
        put("app", appName)
        put("messages", buildJsonArray {
            messages.forEach { m ->
                add(buildJsonObject {
                    put("from", TextNorm.takeCodePoints(untrustedText(ContextText.mask(m.sender)), 60))
                    put("text", TextNorm.takeCodePoints(untrustedText(ContextText.mask(m.text)), limits.messageChars))
                    put("contains_link", m.hasLink)
                })
            }
        })
    }

    fun statementText(a: UserAnswer, catalog: ContextCatalog = FollowupContracts.catalog): String {
        val q = catalog.questions[a.questionId]
        val chips = a.chipIds.mapNotNull { id -> q?.chips?.firstOrNull { it.id == id }?.label?.en }
        val free = TextNorm.takeCodePoints(untrustedText(a.text), catalog.limits.statementChars)
        return buildString {
            append("Q: ").append(q?.text?.en ?: a.questionId).append('\n')
            append("A: ").append((chips + listOfNotNull(free.ifBlank { null })).joinToString("; ").ifEmpty { "(no answer)" })
        }
    }

    fun turn3Message(
        sourceCategory: String, pageDomain: String?, level: String, features: Features,
        appName: String?, conversation: List<ConvMessage>?, answers: List<UserAnswer>,
    ): String = buildString {
        append(block("analysis", buildJsonObject {
            put("source_app", sourceCategory)
            put("page_domain", pageDomain)
            put("level", level)
            put("claimed_brand", features.claimedBrand?.let { untrustedText(it) })
            put("page_type", features.pageType)
            put("requested_actions", strList(features.requestedActions))
        }))
        if (!conversation.isNullOrEmpty()) append('\n').append(block("untrusted_conversation", conversationJson(appName, conversation)))
        if (answers.isNotEmpty()) append("\n<user_statement>\n").append(answers.joinToString("\n") { statementText(it) }).append("\n</user_statement>")
    }

    /** Enum-validated judgment; evidence kept only if copied from the conversation / statement. null if no JSON. */
    fun parse(text: String, sourceTexts: List<String>): ContextJudgment? {
        val o = jsonObjectIn(text) ?: return null
        fun pick(k: String, allowed: List<String>) = o.str(k)?.trim()?.lowercase()?.takeIf { it in allowed }
        val hay = sourceTexts.map { TextNorm.pyCollapse(it) }
        val ev = (o["evidence"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(TextNorm::pyCollapse) }
            .filter { e -> e.isNotEmpty() && e.split(' ').size <= MAX_EVIDENCE_WORDS && hay.any { h -> h.contains(e) } }
            .distinct().take(2)
        return ContextJudgment(pick("scenario", SCENARIOS), pick("consistency", CONSISTENCY), pick("sender_risk", SENDER), ev)
    }

    /** Texts evidence may be copied from: exactly what Gemma saw. */
    fun evidenceSources(conversation: List<ConvMessage>?, answers: List<UserAnswer>, limits: CatalogLimits = FollowupContracts.catalog.limits): List<String> =
        conversation.orEmpty().map { TextNorm.takeCodePoints(untrustedText(ContextText.mask(it.text)), limits.messageChars) } +
            answers.map { TextNorm.takeCodePoints(untrustedText(it.text), limits.statementChars) }

    /**
     * guided (code, not Gemma): a U3 chip says a call/message sent the user here, OR the link came in a
     * collected message from an unknown / impersonating sender.
     */
    fun toInput(j: ContextJudgment?, answers: List<UserAnswer>, conversation: List<ConvMessage>?, catalog: ContextCatalog = FollowupContracts.catalog): ContextInput {
        val chipGuided = answers.any { a -> a.chipIds.any { id -> catalog.questions[a.questionId]?.chips?.firstOrNull { it.id == id }?.guided == true } }
        val linkFromStranger = conversation.orEmpty().any { it.hasLink } && j?.senderRisk in setOf("unknown_sender", "impersonated_org")
        return ContextInput(
            scenario = j?.scenario, consistency = j?.consistency, senderRisk = j?.senderRisk,
            guided = chipGuided || linkFromStranger, evidence = j?.evidence.orEmpty(),
        )
    }
}

// ---------------------------------------------------------------- page state (eval/page_state)

/**
 * "Did we get the site's own page?" — a short separate conversation, only when code signatures are unsure.
 * The answer can only make RedFlag look again (read Chrome's screen) or say "couldn't verify"; it never adds or
 * removes points. Twin of lite_followups.page_state_message / parse_page_state.
 */
object PageStateTurn {
    val STATES = listOf("loaded", "challenge", "blocked", "host_warning", "unavailable")

    fun message(httpStatus: Int?, title: String?, text: String?, formCount: Int, hasPasswordField: Boolean): String =
        block("page", buildJsonObject {
            put("http_status", httpStatus?.let { JsonPrimitive(it) } ?: JsonNull)
            put("title", untrustedText(TextNorm.takeCodePoints(TextNorm.pyStrip(title.orEmpty()), 120)))
            put("text", untrustedText(TextNorm.takeCodePoints(TextNorm.pyCollapse(text.orEmpty()), 600)))
            put("form_count", formCount)
            put("has_password_field", hasPasswordField)
        })

    fun parse(text: String): String? =
        jsonObjectIn(text)?.str("page_state")?.trim()?.lowercase()?.takeIf { it in STATES }
}
