package dev.redflag.core

import dev.redflag.core.gate.PublicSuffixList
import dev.redflag.core.model.I18n
import dev.redflag.core.model.RedFlagJson
import dev.redflag.core.util.TextNorm
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---------------------------------------------------------------- scoring_rules.json

@Serializable
data class ScoreBounds(val min: Int, val max: Int)

@Serializable
data class LevelThreshold(val level: String, val min: Int)

@Serializable
data class SensitiveCategory(val types: List<String> = emptyList(), val phrases: List<String> = emptyList())

@Serializable
data class SensitiveFieldsSpec(val categories: Map<String, SensitiveCategory>)

@Serializable
data class ScoringRule(
    val id: String,
    /** "floor" | "add" | "clamp". */
    val kind: String,
    val weight: Int? = null,
    val value: Int? = null,
    /** clamp bounds (kind == "clamp"). */
    val min: Int? = null,
    val max: Int? = null,
    val severity: String,
    /** "model" or "signal". */
    val source: String,
    /** Boolean expression over facts (see ScoringRules.doc). */
    @SerialName("when") val whenExpr: JsonElement,
    val title: I18n,
    val evidence: String? = null,
)

@Serializable
data class ScoringRules(
    val version: Int,
    val doc: List<String> = emptyList(),
    val score: ScoreBounds,
    val levels: List<LevelThreshold>,
    val escalation: ScoreBounds,
    @SerialName("known_payment_processors") val knownPaymentProcessors: List<String>,
    @SerialName("sensitive_fields") val sensitiveFields: SensitiveFieldsSpec,
    val facts: Map<String, String> = emptyMap(),
    val rules: List<ScoringRule>,
    @SerialName("headline_priority") val headlinePriority: List<String>,
    @SerialName("headline_min_level") val headlineMinLevel: String,
    @SerialName("headline_by_rule") val headlineByRule: Map<String, I18n>,
    @SerialName("headline_by_level") val headlineByLevel: Map<String, I18n>,
    @SerialName("hidden_text_patterns") val hiddenTextPatterns: HiddenTextPatterns = HiddenTextPatterns(),
    @SerialName("data_destinations") val dataDestinations: DataDestinations = DataDestinations(),
)

@Serializable
data class EndpointEntry(val host: String, @SerialName("path_prefix") val pathPrefix: String = "")

/** scoring_rules.json data_destinations (v4): where page data is sent. */
@Serializable
data class DataDestinations(
    val doc: String = "",
    @SerialName("messenger_bot") val messengerBot: List<EndpointEntry> = emptyList(),
    @SerialName("form_service") val formService: List<EndpointEntry> = emptyList(),
    @SerialName("known_data_services") val knownDataServices: List<String> = emptyList(),
)

@Serializable
data class HiddenTextPatterns(val doc: String = "", val patterns: List<String> = emptyList())

// ---------------------------------------------------------------- brand_domains.json

@Serializable
data class Brand(
    val id: String,
    val name: String,
    val region: String? = null,
    /** "token" (whole-token containment) or "exact". */
    val match: String = "token",
    val aliases: List<String>,
    val domains: List<String>,
)

@Serializable
data class BrandDomains(val version: Int, val brands: List<Brand>) {
    /** Lowercased official registrable domains of every brand (gate known-good list). */
    val allDomains: Set<String> by lazy { brands.flatMap { b -> b.domains.map { it.lowercase() } }.toSet() }
}

// ---------------------------------------------------------------- gate_lists.json

@Serializable
data class UgcRule(val host: String, val match: String, val paths: List<String>)

@Serializable
data class SafeBrowsingStubLists(
    @SerialName("url_prefixes") val urlPrefixes: List<String>,
    val domains: List<String>,
)

@Serializable
data class Confusables(val map: Map<String, String>)

@Serializable
data class GateLists(
    val version: Int,
    val shorteners: List<String>,
    val ugc: List<UgcRule>,
    @SerialName("safe_browsing_stub") val safeBrowsingStub: SafeBrowsingStubLists,
    val confusables: Confusables,
)

// ---------------------------------------------------------------- bundle

/** Everything in contracts/ (+ the Lite system prompt), parsed once. */
class ContractBundle(
    val scoringRules: ScoringRules,
    val brands: BrandDomains,
    val gateLists: GateLists,
    val psl: PublicSuffixList,
    /** contracts/demo_hosts.json verbatim; null when not packaged. DEBUG/DEMO only. */
    val demoHostsJson: String?,
    /** redflag-gemma-test/system_prompt_v2.txt, Python-strip()ped exactly like run_eval.py does. */
    val systemPrompt: String,
    /** contracts/gemma_followup.txt (strip()ped): the "missing keys only" follow-up question, {keys} placeholder. */
    val followupTemplate: String = "",
)

object Contracts {
    @Volatile private var cached: ContractBundle? = null

    /** Loads from the classpath (resources contracts/... and prompts/...). Cached after the first call. */
    fun load(): ContractBundle = cached ?: synchronized(this) {
        cached ?: loadFrom(::classpathText).also { cached = it }
    }

    /** Load with a custom reader (path relative to the resource root, e.g. "contracts/gate_lists.json"). */
    fun loadFrom(read: (String) -> String?): ContractBundle {
        fun req(path: String) = read(path) ?: error("missing contract resource: $path")
        val json = RedFlagJson
        return ContractBundle(
            scoringRules = json.decodeFromString(ScoringRules.serializer(), req("contracts/scoring_rules.json")),
            brands = json.decodeFromString(BrandDomains.serializer(), req("contracts/brand_domains.json")),
            gateLists = json.decodeFromString(GateLists.serializer(), req("contracts/gate_lists.json")),
            psl = PublicSuffixList.parse(req("contracts/public_suffix_list.dat")),
            demoHostsJson = read("contracts/demo_hosts.json"),
            systemPrompt = TextNorm.pyStrip(req("prompts/system_prompt_v2.txt")),
            followupTemplate = TextNorm.pyStrip(req("contracts/gemma_followup.txt")),
        )
    }

    fun classpathText(path: String): String? =
        Contracts::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }

    fun classpathBytes(path: String): ByteArray? =
        Contracts::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
}
