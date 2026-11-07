package dev.redflag.app.data

import android.content.Context
import dev.redflag.core.model.RedFlagJson
import dev.redflag.core.model.RiskReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Sites RedFlag blocked (verdict danger/critical). When Chrome shows one of them again — e.g. the user returns
 * to a tab that is still open — the stored verdict is shown at once, without re-analysis. Kept on-device only;
 * exact host, 7 days. Only danger/critical verdicts are stored: a "caution" or a check that could not finish
 * ("couldn't verify") must be analysed again next time, not replayed (fix 2026-11-04: a slow site stayed stuck).
 */
class BlockedSites(context: Context) {
    @Serializable
    data class Entry(val untilMs: Long, val report: RiskReport)

    private val file = java.io.File(context.filesDir, "blocked_sites.json")
    private val ser = MapSerializer(String.serializer(), Entry.serializer())
    private val map: MutableMap<String, Entry> = runCatching {
        RedFlagJson.decodeFromString(ser, file.readText()).toMutableMap()
    }.getOrDefault(mutableMapOf())

    @Synchronized
    fun get(host: String?, nowMs: Long = System.currentTimeMillis()): RiskReport? {
        val k = key(host) ?: return null
        val e = map[k] ?: return null
        // expired, or stored by an older build that also kept caution / unfinished checks
        if (e.untilMs < nowMs || !blockable(e.report)) { map.remove(k); save(); return null }
        return e.report
    }

    @Synchronized
    fun add(host: String?, report: RiskReport, nowMs: Long = System.currentTimeMillis()) {
        if (!blockable(report)) return
        val k = key(host) ?: return
        val keep = map[k]?.report?.takeIf { it.score > report.score } ?: report // never downgrade a stored verdict
        map[k] = Entry(nowMs + TTL_MS, keep)
        if (map.size > MAX) map.entries.sortedBy { it.value.untilMs }.take(map.size - MAX).forEach { map.remove(it.key) }
        save()
    }

    /** Seed from recent reports (danger/critical) so tabs left open before this feature are covered too. */
    @Synchronized
    fun importFrom(reports: List<RiskReport>, nowMs: Long = System.currentTimeMillis()) {
        var changed = false
        for (r in reports) {
            if (!blockable(r)) continue
            val created = runCatching { java.time.Instant.parse(r.createdAt).toEpochMilli() }.getOrDefault(nowMs)
            if (created + TTL_MS < nowMs) continue
            val k = key(hostOf(r.url)) ?: continue
            val cur = map[k]
            if (cur == null || r.score > cur.report.score) { // keep the strictest verdict (e.g. Lite 90 over Cloud 80)
                map[k] = Entry(maxOf(created + TTL_MS, cur?.untilMs ?: 0), r); changed = true
            }
        }
        if (changed) save()
    }

    /** danger / critical only (an unfinished check is clamped to caution, so it never qualifies). */
    fun blockable(r: RiskReport): Boolean = r.level == "danger" || r.level == "critical"

    private fun save() = runCatching { file.writeText(RedFlagJson.encodeToString(ser, map)) }

    companion object {
        const val TTL_MS = 7L * 24 * 3600 * 1000
        const val MAX = 200
        fun key(host: String?): String? = host?.lowercase()?.trimEnd('.')?.removePrefix("www.")?.takeIf { it.isNotEmpty() }
        fun hostOf(url: String): String? =
            runCatching { java.net.URI(if ("://" in url) url else "https://$url").host }.getOrNull()
    }
}
