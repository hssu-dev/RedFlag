package dev.redflag.app.lite

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class DomainSignals(
    val domainAgeDays: Int?,
    val tlsIssuer: String?,
    val certAgeDays: Int?,
    val source: String,
    /**
     * The RDAP lookup FAILED (network error, timeout, HTTP 5xx/429, exception). Distinct from "no data"
     * (404 / TLD without RDAP / no registration event), which is just unknown. A failed lookup makes the
     * Lite analysis incomplete -> never safe (decision D3).
     */
    val lookupFailed: Boolean = false,
)

/**
 * Deterministic signals for the registrable domain only (never the URL path or page content):
 *  - RDAP registration date via the IANA bootstrap registry (https://data.iana.org/rdap/dns.json)
 *  - newest certificate from Certificate Transparency (crt.sh)
 * Both are best-effort with tight timeouts; unknown -> null ("A null value means unknown").
 */
class SignalCollector(private val context: Context, private val http: OkHttpClient) : DomainSignalSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val bootstrapFile get() = File(context.filesDir, "rdap_dns_bootstrap.json")

    override suspend fun collect(registrableDomain: String): DomainSignals = collect(registrableDomain, 2_500)

    suspend fun collect(registrableDomain: String, budgetMs: Long): DomainSignals = coroutineScope {
        // OkHttp's blocking execute() ignores coroutine timeouts, so the budget is enforced by callTimeout too.
        val client = http.newBuilder().followRedirects(true).callTimeout(budgetMs, TimeUnit.MILLISECONDS).build()
        // RDAP: null result inside Result = "no data"; failure / timeout = lookup failed
        val age = async(Dispatchers.IO) { withTimeoutOrNull(budgetMs) { runCatching { rdapAgeDays(client, registrableDomain) } } }
        val cert = async(Dispatchers.IO) { withTimeoutOrNull(budgetMs) { runCatching { newestCert(client, registrableDomain) }.getOrNull() } }
        val a = age.await()
        val c = cert.await()
        if (a == null || a.isFailure) Log.w("RedFlagSignals", "RDAP lookup failed for $registrableDomain: ${a?.exceptionOrNull()?.message ?: "timeout"}")
        DomainSignals(a?.getOrNull(), c?.first, c?.second, "rdap+crt.sh", lookupFailed = a == null || a.isFailure)
    }

    private fun get(client: OkHttpClient, url: String): String? {
        val req = Request.Builder().url(url).header("Accept", "application/json, application/rdap+json").build()
        client.newCall(req).execute().use { r ->
            return when {
                r.isSuccessful -> r.body.string()
                r.code == 404 || r.code == 410 -> null // not registered / no record: "no data", not a failure
                else -> throw IOException("HTTP ${r.code} from ${r.request.url.host}") // 5xx, 429, ...: lookup failed
            }
        }
    }

    private fun bootstrap(client: OkHttpClient): JsonObject? {
        val f = bootstrapFile
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 7L * 24 * 3600 * 1000
        val text = if (fresh) f.readText() else get(client, "https://data.iana.org/rdap/dns.json")?.also { f.writeText(it) }
        return text?.let { json.parseToJsonElement(it).jsonObject }
    }

    private fun rdapAgeDays(client: OkHttpClient, domain: String): Int? {
        val tld = domain.substringAfterLast('.')
        val services = bootstrap(client)?.get("services")?.jsonArray ?: return null
        val base = services.firstNotNullOfOrNull { svc ->
            val pair = svc.jsonArray
            val tlds = pair[0].jsonArray.map { it.jsonPrimitive.content.lowercase() }
            if (tld in tlds) pair[1].jsonArray.firstOrNull()?.jsonPrimitive?.content else null
        } ?: return null
        val body = get(client, base.trimEnd('/') + "/domain/" + domain) ?: return null
        val events = json.parseToJsonElement(body).jsonObject["events"] as? JsonArray ?: return null
        val reg = events.map { it.jsonObject }.firstOrNull { it["eventAction"]?.jsonPrimitive?.content == "registration" }
            ?.get("eventDate")?.jsonPrimitive?.content ?: return null
        val at = parseInstant(reg) ?: return null
        return ChronoUnit.DAYS.between(at, Instant.now()).toInt().coerceAtLeast(0)
    }

    /** (issuer O=, age days) of the most recently issued cert for the domain. */
    private fun newestCert(client: OkHttpClient, domain: String): Pair<String?, Int?>? {
        val body = get(client, "https://crt.sh/?q=" + domain + "&output=json&exclude=expired") ?: return null
        val arr = json.parseToJsonElement(body) as? JsonArray ?: return null
        val newest = arr.map { it.jsonObject }.maxByOrNull { it["not_before"]?.jsonPrimitive?.content ?: "" } ?: return null
        val nb = newest["not_before"]?.jsonPrimitive?.content?.let { parseInstant(it) }
        val issuer = newest["issuer_name"]?.jsonPrimitive?.content?.let { iss ->
            Regex("O=\"?([^,\"]+)\"?").find(iss)?.groupValues?.get(1)?.trim()
        }
        return issuer to nb?.let { ChronoUnit.DAYS.between(it, Instant.now()).toInt().coerceAtLeast(0) }
    }

    private fun parseInstant(s: String): Instant? =
        runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(s) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s.substringBefore('.')).toInstant(ZoneOffset.UTC) }.getOrNull()
            .also { if (it == null) Log.w("RedFlagSignals", "unparsed date $s") }

    companion object {
        suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    }
}
