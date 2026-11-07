@file:OptIn(ExperimentalSerializationApi::class)

package dev.redflag.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * Shared Json instance. explicitNulls + encodeDefaults so every schema-required nullable key is
 * written (`"registrable_domain": null`); truly optional keys use @EncodeDefault(NEVER).
 */
val RedFlagJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
}

// ---------------------------------------------------------------- RiskReport (contracts/risk_report.schema.json)

@Serializable
data class I18n(val en: String, val ko: String)

@Serializable
data class RedFlag(
    @SerialName("rule_id") val ruleId: String,
    /** low | medium | high | critical */
    val severity: String,
    /** rule | gemma+rule | opus+rule | signal+rule */
    val source: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val weight: Int? = null,
    val title: I18n,
    val evidence: String?,
)

@Serializable
data class BrandInfo(
    val claimed: String?,
    @SerialName("official_domains") val officialDomains: List<String>,
    /** null when the brand is not in brand_domains.json (model opinion only). */
    val matches: Boolean?,
)

@Serializable
data class FormSignal(
    @SerialName("action_domain") val actionDomain: String?,
    @SerialName("same_site") val sameSite: Boolean,
    /** subset of password | card | secret, stable order. */
    @SerialName("sensitive_fields") val sensitiveFields: List<String>,
)

@Serializable
data class ReportSignals(
    @SerialName("domain_age_days") val domainAgeDays: Int?,
    @SerialName("tls_issuer") val tlsIssuer: String?,
    @SerialName("cert_age_days") val certAgeDays: Int?,
    /** hit | no_match | unknown */
    @SerialName("safe_browsing") val safeBrowsing: String,
    @SerialName("redirect_count") val redirectCount: Int,
    val punycode: Boolean,
    val homoglyph: Boolean,
    val forms: List<FormSignal>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("external_link_domains") val externalLinkDomains: List<String>? = null,
)

@Serializable
data class Cloaking(val suspected: Boolean = false, val detail: String? = null)

@Serializable
data class TraceStep(@SerialName("t_ms") val tMs: Long, val step: String, val detail: String)

@Serializable
data class EngineInfo(val model: String, val runtime: String, val think: Boolean)

@Serializable
data class RiskReport(
    @SerialName("schema_version") val schemaVersion: String = "1",
    val id: String,
    /** lite | cloud */
    val mode: String,
    /** complete | timeout | error */
    val status: String,
    val url: String,
    @SerialName("final_url") val finalUrl: String,
    @SerialName("registrable_domain") val registrableDomain: String?,
    val score: Int,
    /** safe | caution | danger | critical */
    val level: String,
    val escalate: Boolean,
    val headline: I18n,
    @SerialName("red_flags") val redFlags: List<RedFlag>,
    val brand: BrandInfo,
    val signals: ReportSignals,
    val features: Features?,
    val cloaking: Cloaking,
    val trace: List<TraceStep>,
    val engine: EngineInfo,
    @SerialName("latency_ms") val latencyMs: Long,
    /** ISO-8601 UTC, e.g. 2026-11-05T01:15:11Z */
    @SerialName("created_at") val createdAt: String,
)

// ---------------------------------------------------------------- Features (lite_features / cloud_features)

/**
 * The 10 Lite fields (+ the 3 Cloud-only ones). When both form_posts_cross_site and
 * downloads_attempted are null the object is Lite and the three Cloud keys are omitted on encode;
 * otherwise all three are written (official_domain_found may be null).
 */
@KeepGeneratedSerializer
@Serializable(with = FeaturesSerializer::class)
data class Features(
    val evidence: List<String>,
    @SerialName("claimed_brand") val claimedBrand: String?,
    /** yes | no | unclear | no_brand */
    @SerialName("brand_matches_domain") val brandMatchesDomain: String,
    /** login | payment | shop | download | support | info | other */
    @SerialName("page_type") val pageType: String,
    @SerialName("requested_actions") val requestedActions: List<String>,
    @SerialName("urgency_or_threat") val urgencyOrThreat: Boolean,
    @SerialName("too_good_to_be_true") val tooGoodToBeTrue: Boolean,
    @SerialName("addresses_ai_analyzer") val addressesAiAnalyzer: Boolean,
    /** safe | suspicious | malicious */
    val verdict: String,
    /** low | medium | high */
    val confidence: String,
    @SerialName("form_posts_cross_site") val formPostsCrossSite: Boolean? = null,
    @SerialName("downloads_attempted") val downloadsAttempted: Boolean? = null,
    @SerialName("official_domain_found") val officialDomainFound: String? = null,
) {
    val isCloud: Boolean get() = formPostsCrossSite != null || downloadsAttempted != null

    companion object {
        val LITE_KEYS = listOf(
            "evidence", "claimed_brand", "brand_matches_domain", "page_type", "requested_actions",
            "urgency_or_threat", "too_good_to_be_true", "addresses_ai_analyzer", "verdict", "confidence",
        )
        val CLOUD_ONLY_KEYS = listOf("form_posts_cross_site", "downloads_attempted", "official_domain_found")
    }
}

object FeaturesSerializer : JsonTransformingSerializer<Features>(Features.generatedSerializer()) {
    override fun transformSerialize(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        val lite = (element["form_posts_cross_site"] ?: JsonNull) is JsonNull &&
            (element["downloads_attempted"] ?: JsonNull) is JsonNull
        return if (lite) JsonObject(element.filterKeys { it !in Features.CLOUD_ONLY_KEYS }) else element
    }
}

// ---------------------------------------------------------------- page observation (contracts/extract_page.js)

@Serializable
data class ObservedField(
    val type: String = "text",
    val label: String = "",
    val name: String = "",
    val autocomplete: String = "",
)

@Serializable
data class ObservedForm(
    val action: String = "",
    /** get | post | js (pseudo-form of inputs outside any <form>) */
    val method: String = "get",
    val fields: List<ObservedField> = emptyList(),
)

@Serializable
data class PageMeta(
    val description: String? = null,
    @SerialName("og_site_name") val ogSiteName: String? = null,
)

@Serializable
data class PageCounts(val forms: Int = 0, val inputs: Int = 0, val iframes: Int = 0)

/** Exactly the JSON string extract_page.js returns. */
@Serializable
data class PageObservation(
    @SerialName("final_url") val finalUrl: String = "",
    val title: String = "",
    val lang: String = "",
    @SerialName("visible_text") val visibleText: String = "",
    @SerialName("hidden_text") val hiddenText: String = "",
    val forms: List<ObservedForm> = emptyList(),
    @SerialName("link_urls") val linkUrls: List<String> = emptyList(),
    @SerialName("script_urls") val scriptUrls: List<String> = emptyList(),
    /** v4: where page data can go (extract_page.js data_endpoints). */
    @SerialName("data_endpoints") val dataEndpoints: List<DataEndpoint> = emptyList(),
    val meta: PageMeta = PageMeta(),
    val counts: PageCounts = PageCounts(),
)

/** via: form | script | fetch | xhr | beacon */
@Serializable
data class DataEndpoint(val url: String, val via: String = "")

// ---------------------------------------------------------------- Gemma input (redflag-gemma-test/cases.jsonl shape)
// Field order here IS the prompt order (must match cases.jsonl / run_eval.build_user_message).

@Serializable
data class GemmaField(val type: String, val label: String)

@Serializable
data class GemmaForm(
    @SerialName("action_domain") val actionDomain: String?,
    @SerialName("same_site") val sameSite: Boolean,
    val fields: List<GemmaField>,
)

@Serializable
data class GemmaTls(val issuer: String?, @SerialName("cert_age_days") val certAgeDays: Int?)

@Serializable
data class GemmaSignals(
    val url: String,
    @SerialName("final_url") val finalUrl: String,
    @SerialName("registrable_domain") val registrableDomain: String?,
    @SerialName("redirect_count") val redirectCount: Int,
    @SerialName("domain_age_days") val domainAgeDays: Int?,
    val tls: GemmaTls?,
    /** hit | no_match | unknown */
    @SerialName("safe_browsing") val safeBrowsing: String,
    val forms: List<GemmaForm>,
    @SerialName("external_link_domains") val externalLinkDomains: List<String>,
)

@Serializable
data class GemmaPage(
    val lang: String,
    val title: String,
    @SerialName("visible_text") val visibleText: String,
    @SerialName("hidden_text") val hiddenText: String,
)

@Serializable
data class GemmaInput(val signals: GemmaSignals, val page: GemmaPage)
