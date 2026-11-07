package dev.redflag.app.data

import android.content.Context
import dev.redflag.core.gate.SafeVerdictCache

/** "Safe" verdict cache with TTL (gate step 4). Only registrable domains are stored, never URLs. */
class PrefsSafeVerdictCache(context: Context) : SafeVerdictCache {
    private val prefs = context.getSharedPreferences("redflag_safe_cache", Context.MODE_PRIVATE)

    override fun isSafe(registrableDomain: String, nowMs: Long): Boolean {
        val until = prefs.getLong(registrableDomain, 0L)
        return until > nowMs
    }

    override fun putSafe(registrableDomain: String, ttlMs: Long, nowMs: Long) {
        prefs.edit().putLong(registrableDomain, nowMs + ttlMs).apply()
    }

    fun clear() = prefs.edit().clear().apply()

    /** A later step (context) found a reason for caution: forget the cached "safe". */
    fun remove(registrableDomain: String) = prefs.edit().remove(registrableDomain).apply()

    companion object { const val TTL_MS = 12L * 60 * 60 * 1000 }
}
