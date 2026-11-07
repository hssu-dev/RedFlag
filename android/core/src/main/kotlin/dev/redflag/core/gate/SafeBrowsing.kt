package dev.redflag.core.gate

import dev.redflag.core.SafeBrowsingStubLists
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

enum class SafeBrowsingResult(val wire: String) {
    HIT("hit"), NO_MATCH("no_match"), UNKNOWN("unknown");
}

/** Blocking; call off the main thread. Never throws: failures are UNKNOWN. */
fun interface SafeBrowsingClient {
    fun check(url: NormalizedUrl): SafeBrowsingResult
}

/** gate_lists.json safe_browsing_stub: prefix of the scheme-less normalized URL, or registrable domain in `domains`. */
class StubSafeBrowsing(private val lists: SafeBrowsingStubLists) : SafeBrowsingClient {
    private val domains = lists.domains.map { it.lowercase() }.toSet()

    override fun check(url: NormalizedUrl): SafeBrowsingResult {
        val bare = url.withoutScheme
        if (lists.urlPrefixes.any { bare.startsWith(it) }) return SafeBrowsingResult.HIT
        if (url.registrableDomain != null && url.registrableDomain in domains) return SafeBrowsingResult.HIT
        return SafeBrowsingResult.NO_MATCH
    }
}

/**
 * Google Safe Browsing Lookup API v4 (threatMatches:find). Disabled by default — no API key exists in
 * this build; wire it in only when one is configured. Not unit-tested against the network.
 */
class LiveSafeBrowsing(
    private val apiKey: String,
    private val clientId: String = "redflag",
    private val clientVersion: String = "0.1",
    private val timeoutMs: Int = 2500,
    private val endpoint: String = "https://safebrowsing.googleapis.com/v4/threatMatches:find",
) : SafeBrowsingClient {

    fun requestBody(url: String): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("clientId", clientId)
            put("clientVersion", clientVersion)
        }
        putJsonObject("threatInfo") {
            putJsonArray("threatTypes") {
                add("MALWARE"); add("SOCIAL_ENGINEERING"); add("UNWANTED_SOFTWARE"); add("POTENTIALLY_HARMFUL_APPLICATION")
            }
            putJsonArray("platformTypes") { add("ANY_PLATFORM") }
            putJsonArray("threatEntryTypes") { add("URL") }
            putJsonArray("threatEntries") { addJsonObject { put("url", url) } }
        }
    }

    override fun check(url: NormalizedUrl): SafeBrowsingResult {
        if (apiKey.isBlank()) return SafeBrowsingResult.UNKNOWN
        return try {
            val conn = URI("$endpoint?key=${URLEncoder.encode(apiKey, "UTF-8")}").toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(requestBody(url.url).toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return SafeBrowsingResult.UNKNOWN
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            parseResponse(body)
        } catch (e: Exception) {
            SafeBrowsingResult.UNKNOWN
        }
    }

    companion object {
        /** `{}` -> NO_MATCH, `{"matches":[...]}` -> HIT. */
        fun parseResponse(body: String): SafeBrowsingResult = runCatching {
            val obj = Json.parseToJsonElement(body.ifBlank { "{}" }).jsonObject
            val matches = obj["matches"] as? JsonArray
            if (matches != null && matches.isNotEmpty()) SafeBrowsingResult.HIT else SafeBrowsingResult.NO_MATCH
        }.getOrDefault(SafeBrowsingResult.UNKNOWN)
    }
}

/** First HIT wins; NO_MATCH if any client answered NO_MATCH; else UNKNOWN. E.g. Composite(stub, live). */
class CompositeSafeBrowsing(private val clients: List<SafeBrowsingClient>) : SafeBrowsingClient {
    override fun check(url: NormalizedUrl): SafeBrowsingResult {
        var anyNoMatch = false
        for (c in clients) {
            when (c.check(url)) {
                SafeBrowsingResult.HIT -> return SafeBrowsingResult.HIT
                SafeBrowsingResult.NO_MATCH -> anyNoMatch = true
                SafeBrowsingResult.UNKNOWN -> {}
            }
        }
        return if (anyNoMatch) SafeBrowsingResult.NO_MATCH else SafeBrowsingResult.UNKNOWN
    }
}
