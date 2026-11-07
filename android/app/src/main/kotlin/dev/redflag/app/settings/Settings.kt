package dev.redflag.app.settings

import android.content.Context
import dev.redflag.app.DeviceEnv
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Advanced (cloud) analysis runs ONLY when the user asks for it, never automatically, not even when it is
 * recommended (user decision 2026-11-04). LITE hides the cloud buttons; ON_REQUEST shows them.
 */
enum class Mode { LITE, ON_REQUEST }
enum class EnginePref { AUTO, REAL, FAKE }

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("redflag_settings", Context.MODE_PRIVATE)

    // old stored values AUTO / CLOUD (automatic escalation) both become ON_REQUEST: nothing is sent automatically
    private val _mode = MutableStateFlow(if (prefs.getString("mode", null) == "LITE") Mode.LITE else Mode.ON_REQUEST)
    val modeFlow: StateFlow<Mode> = _mode
    var mode: Mode
        get() = _mode.value
        set(v) { prefs.edit().putString("mode", v.name).apply(); _mode.value = v }

    private val _backend = MutableStateFlow(prefs.getString("backend_url", null) ?: DeviceEnv.defaultBackendUrl)
    val backendFlow: StateFlow<String> = _backend
    var backendUrl: String
        get() = _backend.value
        set(v) { val t = v.trim().trimEnd('/'); prefs.edit().putString("backend_url", t).apply(); _backend.value = t }

    private val _engine = MutableStateFlow(runCatching { EnginePref.valueOf(prefs.getString("engine", null) ?: "") }.getOrDefault(EnginePref.AUTO))
    val engineFlow: StateFlow<EnginePref> = _engine
    var engine: EnginePref
        get() = _engine.value
        set(v) { prefs.edit().putString("engine", v.name).apply(); _engine.value = v }

    /** LiteRT-LM speculative decoding for the on-device model. */
    var speculativeDecoding: Boolean
        get() = prefs.getBoolean("spec_decoding", true)
        set(v) { prefs.edit().putBoolean("spec_decoding", v).apply() }

    /** Context analyzer (docs/plans/context_analysis.md): on unless the user turns it off. */
    var contextEnabled: Boolean
        get() = prefs.getBoolean("context_enabled", true)
        set(v) { prefs.edit().putBoolean("context_enabled", v).apply() }

    /**
     * Presentation / testing: analyse every visit again. Skips the 12 h "safe" cache in the gate and the replay of a
     * stored blocked verdict (the allow-list of pages the user just chose to open still applies, so no loops).
     */
    var alwaysReanalyze: Boolean
        get() = prefs.getBoolean("always_reanalyze", false)
        set(v) { prefs.edit().putBoolean("always_reanalyze", v).apply() }

    /** Chrome: cover the whole screen while checking (the old behaviour). Off = a small pill; full screen only for danger. */
    var fullCoverWhileChecking: Boolean
        get() = prefs.getBoolean("full_cover_while_checking", false)
        set(v) { prefs.edit().putBoolean("full_cover_while_checking", v).apply() }

    /** "Don't ask again" on a context card: no more context prompts (direct input stays available). */
    var contextNeverAsk: Boolean
        get() = prefs.getBoolean("context_never_ask", false)
        set(v) { prefs.edit().putBoolean("context_never_ask", v).apply() }

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) { prefs.edit().putBoolean("onboarded", v).apply() }
}
