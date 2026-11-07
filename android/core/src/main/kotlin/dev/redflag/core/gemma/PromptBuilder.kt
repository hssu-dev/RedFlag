package dev.redflag.core.gemma

import dev.redflag.core.gate.NormalizedUrl
import dev.redflag.core.gate.UrlNormalizer
import dev.redflag.core.ContractBundle
import dev.redflag.core.Contracts
import dev.redflag.core.model.GemmaField
import dev.redflag.core.model.GemmaForm
import dev.redflag.core.model.GemmaInput
import dev.redflag.core.model.GemmaPage
import dev.redflag.core.model.GemmaSignals
import dev.redflag.core.model.GemmaTls
import dev.redflag.core.model.PageObservation
import dev.redflag.core.model.RedFlagJson
import dev.redflag.core.scoring.FormField
import dev.redflag.core.scoring.FormInput
import dev.redflag.core.util.TextNorm
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Deterministic signals gathered outside the page (redirects, RDAP, crt.sh, Safe Browsing). */
data class ObservationSignals(
    /** URL the user opened (before redirects). */
    val url: String,
    val redirectCount: Int = 0,
    val domainAgeDays: Int? = null,
    val tlsIssuer: String? = null,
    val certAgeDays: Int? = null,
    /** hit | no_match | unknown */
    val safeBrowsing: String = "unknown",
)

object PromptBuilder {
    const val MAX_VISIBLE = 2500
    const val MAX_HIDDEN = 800
    const val MAX_EXTERNAL_DOMAINS = 10

    /** Page text of an existing GemmaInput through sanitizeForPrompt (benchmark cases built outside buildObservation). */
    fun sanitizeInput(input: GemmaInput): GemmaInput = input.copy(
        page = input.page.copy(
            title = TextNorm.sanitizeForPrompt(input.page.title),
            visibleText = TextNorm.sanitizeForPrompt(input.page.visibleText),
            hiddenText = TextNorm.sanitizeForPrompt(input.page.hiddenText),
        ),
    )

    /** run_eval.build_user_message(), byte-for-byte. */
    fun buildUserMessage(input: GemmaInput): String {
        val signals = pyDumps(RedFlagJson.encodeToJsonElement(GemmaSignals.serializer(), input.signals))
        val page = pyDumps(RedFlagJson.encodeToJsonElement(GemmaPage.serializer(), input.page))
        return "<signals>\n$signals\n</signals>\n<untrusted_page>\n$page\n</untrusted_page>"
    }

    // ------------------------------------------------------------ observation -> GemmaInput

    /** eTLD+1 of a URL's host (IP literal host returned as-is), null if not an http(s) URL. */
    fun domainOf(url: String, bundle: ContractBundle = Contracts.load()): String? =
        when (val n = UrlNormalizer.normalize(url, bundle)) {
            is NormalizedUrl -> n.registrableDomain ?: n.host.takeIf { n.isIp }
            else -> null
        }

    /** Registrable domain of the page itself (from page.final_url, falling back to signals.url). */
    fun pageDomain(page: PageObservation, signals: ObservationSignals, bundle: ContractBundle = Contracts.load()): String? =
        domainOf(page.finalUrl.ifEmpty { signals.url }, bundle)

    /**
     * Forms for the rule engine (keeps name/autocomplete for sensitive-field detection).
     * action_domain = eTLD+1 of the form action host; same_site = action_domain == page domain
     * (false when the action is not a http(s) URL).
     */
    fun scoringForms(page: PageObservation, pageDomain: String?, bundle: ContractBundle = Contracts.load()): List<FormInput> =
        page.forms.map { f ->
            val action = domainOf(f.action.ifEmpty { page.finalUrl }, bundle)
            FormInput(
                actionDomain = action,
                sameSite = action != null && action == pageDomain,
                fields = f.fields.map { FormField(type = it.type, label = it.label, name = it.name, autocomplete = it.autocomplete) },
            )
        }

    /** Distinct eTLD+1 of link_urls that differ from the page domain, first-seen order, max 10. */
    fun externalLinkDomains(page: PageObservation, pageDomain: String?, bundle: ContractBundle = Contracts.load()): List<String> {
        val out = LinkedHashSet<String>()
        for (u in page.linkUrls) {
            if (out.size >= MAX_EXTERNAL_DOMAINS) break
            val d = domainOf(u, bundle) ?: continue
            if (d != pageDomain) out += d
        }
        return out.toList()
    }

    /** PageObservation + deterministic signals -> exactly the cases.jsonl shape. */
    fun buildObservation(
        page: PageObservation,
        signals: ObservationSignals,
        bundle: ContractBundle = Contracts.load(),
    ): GemmaInput {
        val finalUrl = page.finalUrl.ifEmpty { signals.url }
        val pageDomain = pageDomain(page, signals, bundle)
        val forms = scoringForms(page, pageDomain, bundle).map { f ->
            GemmaForm(f.actionDomain, f.sameSite == true, f.fields.map { GemmaField(it.type ?: "text", it.label ?: "") })
        }
        return GemmaInput(
            signals = GemmaSignals(
                url = signals.url,
                finalUrl = finalUrl,
                registrableDomain = pageDomain,
                redirectCount = signals.redirectCount,
                domainAgeDays = signals.domainAgeDays,
                tls = GemmaTls(signals.tlsIssuer, signals.certAgeDays),
                safeBrowsing = signals.safeBrowsing,
                forms = forms,
                externalLinkDomains = externalLinkDomains(page, pageDomain, bundle),
            ),
            page = GemmaPage(
                lang = page.lang,
                title = TextNorm.sanitizeForPrompt(page.title),
                visibleText = TextNorm.takeCodePoints(TextNorm.sanitizeForPrompt(page.visibleText), MAX_VISIBLE),
                hiddenText = TextNorm.takeCodePoints(TextNorm.sanitizeForPrompt(page.hiddenText), MAX_HIDDEN),
            ),
        )
    }

    // ------------------------------------------------------------ Python json.dumps(obj, ensure_ascii=False, indent=1)

    fun pyDumps(e: JsonElement, indent: Int = 1): String = StringBuilder().also { write(it, e, indent, 0) }.toString()

    private fun write(sb: StringBuilder, e: JsonElement, indent: Int, level: Int) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> if (e.isString) writeString(sb, e.content) else sb.append(pyNumber(e.content))
            is JsonArray -> {
                if (e.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                e.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    newline(sb, indent, level + 1)
                    write(sb, v, indent, level + 1)
                }
                newline(sb, indent, level)
                sb.append(']')
            }
            is JsonObject -> {
                if (e.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, v) in e) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent, level + 1)
                    writeString(sb, k)
                    sb.append(": ")
                    write(sb, v, indent, level + 1)
                }
                newline(sb, indent, level)
                sb.append('}')
            }
        }
    }

    private fun newline(sb: StringBuilder, indent: Int, level: Int) {
        sb.append('\n')
        repeat(indent * level) { sb.append(' ') }
    }

    private fun pyNumber(s: String): String = when (s) {
        "true", "false" -> s
        else -> if (s.contains('.') || s.contains('e') || s.contains('E')) {
            val d = s.toDouble()
            when {
                d.isNaN() -> "NaN"
                d == Double.POSITIVE_INFINITY -> "Infinity"
                d == Double.NEGATIVE_INFINITY -> "-Infinity"
                else -> pyFloatRepr(d)
            }
        } else s
    }

    /** Python float repr (shortest round-trip), e.g. 1.0, 0.1, 1e+16, 1.5e-07. */
    private fun pyFloatRepr(d: Double): String {
        val bd = java.math.BigDecimal(d.toString()).stripTrailingZeros()
        val digits = bd.unscaledValue().abs().toString()
        val exp = digits.length - 1 - bd.scale() // decimal exponent of the leading digit
        val sign = if (d < 0) "-" else ""
        return if (exp < -4 || exp >= 16) {
            val mant = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            sign + mant + "e" + (if (exp < 0) "-" else "+") + String.format("%02d", kotlin.math.abs(exp))
        } else {
            val plain = bd.abs().toPlainString()
            sign + if (plain.contains('.')) plain else "$plain.0"
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
