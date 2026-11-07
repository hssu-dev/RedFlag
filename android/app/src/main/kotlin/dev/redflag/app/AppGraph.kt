package dev.redflag.app

import android.content.Context
import dev.redflag.app.cloud.CloudClient
import dev.redflag.app.data.PrefsSafeVerdictCache
import dev.redflag.app.data.ReportStore
import dev.redflag.app.demo.DemoHosts
import dev.redflag.app.lite.FakeGemmaEngine
import dev.redflag.app.lite.GemmaEngine
import dev.redflag.app.lite.LiteDeps
import dev.redflag.app.lite.LiteEngine
import dev.redflag.app.lite.OffscreenExtractor
import dev.redflag.app.lite.LiteRtGemmaEngine
import dev.redflag.app.lite.SignalCollector
import dev.redflag.app.settings.EnginePref
import dev.redflag.app.settings.Settings
import dev.redflag.core.ContractBundle
import dev.redflag.core.Contracts
import dev.redflag.core.gate.Gate
import dev.redflag.core.gate.HashedKnownGoodSet
import dev.redflag.core.gate.KnownGoodSet
import dev.redflag.core.gate.AuthoritativeSafeBrowsing
import dev.redflag.core.gate.SafeBrowsingClient
import dev.redflag.core.gate.SafeBrowsingV5
import dev.redflag.core.gate.StubSafeBrowsing
import dev.redflag.core.scoring.ScoringEngine
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual DI: one instance per process. */
class AppGraph(val context: Context) {
    val bundle: ContractBundle by lazy { Contracts.load() }
    val settings = Settings(context)

    /** DEBUG ONLY. Always null in release (BuildConfig.DEMO_HOSTS=false, asset not packaged). */
    val demoHosts: DemoHosts? by lazy { if (BuildConfig.DEMO_HOSTS) DemoHosts.load(context, DeviceEnv.demoBaseKey) else null }

    private val knownGood: KnownGoodSet by lazy { HashedKnownGoodSet.fromClasspath() }
    val safeVerdictCache by lazy { PrefsSafeVerdictCache(context) }
    /**
     * Real Google Safe Browsing v5 (hash-prefix lookups, the URL never leaves the phone) when a key is
     * built in; the stub otherwise. Debug builds keep the demo-domain stub hits on top of the real service.
     */
    val safeBrowsing: SafeBrowsingClient by lazy {
        val key = BuildConfig.SAFE_BROWSING_API_KEY
        if (key.isBlank()) StubSafeBrowsing(bundle.gateLists.safeBrowsingStub)
        else AuthoritativeSafeBrowsing(
            SafeBrowsingV5(key, androidPackage = context.packageName, androidCertSha1 = signingSha1(context)),
            if (BuildConfig.DEMO_HOSTS) StubSafeBrowsing(bundle.gateLists.safeBrowsingStub.copy(urlPrefixes = emptyList())) else null,
        )
    }
    val safeBrowsingSource: String get() = if (BuildConfig.SAFE_BROWSING_API_KEY.isBlank()) "stub" else "Google Safe Browsing v5"
    val gate: Gate by lazy { Gate(bundle, knownGood, safeVerdictCache, safeBrowsing) }
    val scoring: ScoringEngine by lazy { ScoringEngine(bundle) }
    val reports = ReportStore(context)
    val blockedSites by lazy { dev.redflag.app.data.BlockedSites(context).also { it.importFrom(reports.recent.value) } }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    val signals by lazy { SignalCollector(context, http) }

    val realGemma by lazy { LiteRtGemmaEngine(context, speculative = { settings.speculativeDecoding }) }
    private val fakeGemma by lazy {
        FakeGemmaEngine(demoHosts, if (BuildConfig.DEMO_HOSTS) java.io.File(context.getExternalFilesDir(null), "fake_delay_ms.txt") else null)
    }

    /**
     * Release: always the real engine (if the model is missing it fails -> status=error, never "safe").
     * Debug: the fixture engine is allowed (emulator demo flows) when chosen or when the model is absent.
     */
    val gemma: GemmaEngine
        get() {
            if (!BuildConfig.DEMO_HOSTS) return realGemma
            return when (settings.engine) {
                EnginePref.REAL -> realGemma
                EnginePref.FAKE -> fakeGemma
                EnginePref.AUTO -> if (realGemma.modelAvailable()) realGemma else fakeGemma
            }
        }

    val lite by lazy {
        LiteEngine(LiteDeps(bundle, scoring, { demoHosts }, OffscreenExtractor(context), signals, { gemma }, safeBrowsingSource))
    }
    val cloud by lazy { CloudClient(this) }
}

/** SHA-1 of the app signing certificate, uppercase hex (X-Android-Cert for an Android-restricted API key). */
fun signingSha1(context: Context): String? = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
    val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
    java.security.MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString("") { "%02X".format(it) }
}.getOrNull()
