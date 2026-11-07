package dev.redflag.app.demo

import android.content.Context
import dev.redflag.core.model.Features
import dev.redflag.core.model.RedFlagJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DEMO ONLY (debug builds). contracts/demo_hosts.json maps fake demo domains to the local
 * demo-pages server. The fake domains are never contacted; the app fetches the local copy and
 * reports everything under the fake URL.
 */
class DemoHosts(private val doc: Doc, private val baseUrl: String) {

    @Serializable
    data class Overrides(
        @SerialName("domain_age_days") val domainAgeDays: Int? = null,
        @SerialName("tls_issuer") val tlsIssuer: String? = null,
        @SerialName("cert_age_days") val certAgeDays: Int? = null,
    )

    @Serializable
    data class Host(
        val page: String,
        val path: String,
        @SerialName("expected_level") val expectedLevel: String? = null,
        @SerialName("signal_overrides") val signalOverrides: Overrides = Overrides(),
        @SerialName("fake_features") val fakeFeatures: Features? = null,
    )

    @Serializable
    data class Doc(@SerialName("base_urls") val baseUrls: Map<String, String>, val hosts: Map<String, Host>)

    data class Match(val fakeHost: String, val host: Host, val localUrl: String, val fakeUrl: String)

    val hosts: Map<String, Host> get() = doc.hosts

    /** Resolve either a fake-domain URL or a local demo URL (http://10.0.2.2:8081/dbs/...) to its demo host. */
    fun match(url: String): Match? {
        val lower = url.lowercase()
        val bare = lower.substringAfter("://")
        val baseBare = baseUrl.lowercase().substringAfter("://")
        // 1) local demo URL -> host by path (scheme ignored: Chrome's address bar hides it)
        if (bare.startsWith(baseBare)) {
            val rest = bare.substring(baseBare.length)
            val entry = doc.hosts.entries.firstOrNull { rest.startsWith(it.value.path) || rest.trimEnd('/') + "/" == it.value.path }
                ?: return null
            return Match(entry.key, entry.value, baseUrl + entry.value.path, "https://${entry.key}/")
        }
        // 2) fake domain URL
        val host = Regex("^([^/:?#@]+@)?([^/:?#]+)").find(bare)?.groupValues?.get(2)?.trimEnd('.') ?: return null
        val entry = doc.hosts.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) } ?: return null
        return Match(entry.key, entry.value, baseUrl + entry.value.path, if ("://" in url) url else "https://$url")
    }

    companion object {
        fun load(context: Context, baseKey: String = "emulator"): DemoHosts? = runCatching {
            val text = context.assets.open("demo_hosts.json").bufferedReader().use { it.readText() }
            val doc = RedFlagJson.decodeFromString(Doc.serializer(), text)
            DemoHosts(doc, doc.baseUrls[baseKey] ?: doc.baseUrls.values.first())
        }.getOrNull()
    }
}
