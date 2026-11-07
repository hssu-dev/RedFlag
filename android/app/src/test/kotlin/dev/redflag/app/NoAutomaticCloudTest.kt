package dev.redflag.app

import android.content.Context
import dev.redflag.app.settings.Mode
import dev.redflag.app.settings.Settings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * User decision 2026-11-04: advanced (cloud) analysis runs ONLY when the user asks for it. Nothing is ever sent
 * to the cloud automatically, not even when RedFlag recommends it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoAutomaticCloudTest {
    private val main = File("src/main/kotlin").let { if (it.exists()) it else File("app/src/main/kotlin") }
    private val sources = main.walkTopDown().filter { it.extension == "kt" }.associate { it.name to it.readText() }

    @Test
    fun cloudIsReachedOnlyFromTheUsersButton() {
        assertTrue(sources.isNotEmpty())
        // the one place page data goes to the backend
        val analyzeCallers = sources.filter { (_, src) -> src.contains("cloud.analyze(") }.keys
        assertEquals(setOf("AnalysisController.kt"), analyzeCallers)
        val ctrl = sources.getValue("AnalysisController.kt")
        assertEquals(1, Regex("""g\.cloud\.analyze\(""").findAll(ctrl).count())
        // requestCloud() is only ever invoked from the advanced-analysis button
        for ((name, src) in sources) {
            for (m in Regex("""requestCloud\(""").findAll(src)) {
                val line = src.substring(src.lastIndexOf('\n', m.range.first) + 1, src.indexOf('\n', m.range.first))
                val isDefinition = line.trimStart().startsWith("fun requestCloud(")
                assertTrue(isDefinition || line.contains("onDeepAnalysis"), "$name calls requestCloud outside the user's button: $line")
            }
        }
        assertTrue(sources.values.none { it.contains("requestCloud(\"auto\")") })
    }

    /** User-provided context goes to the cloud only on the user's request path (manual_url_check.md §5). */
    @Test
    fun userContextIsSentOnlyFromRequestCloud() {
        assertEquals(setOf("AnalysisController.kt"), sources.filter { (_, s) -> s.contains("CloudContext.build(") }.keys)
        val ctrl = sources.getValue("AnalysisController.kt")
        val start = ctrl.indexOf("    fun requestCloud() {")
        val end = ctrl.indexOf("\n    }\n", start)
        assertTrue(start >= 0 && end > start)
        val body = ctrl.substring(start, end)
        val call = ctrl.indexOf("g.cloud.analyze(")
        assertTrue(call in start..end, "the only cloud call must be inside requestCloud()")
        assertTrue(body.contains("cloudContext()") && body.contains("g.cloud.analyze(url, s.liteObservation, trigger, userContext)"))
        // the context sent never includes chats read from notifications
        val build = ctrl.substring(ctrl.indexOf("private fun cloudContext()"), ctrl.indexOf("private fun refreshCloudContext()"))
        assertTrue(!build.contains("conversation"), "notification chats must not be part of the cloud context: $build")
        assertTrue(sources.getValue("CloudClient.kt").contains("put(\"user_context\", userContext)"))
    }

    @Test
    fun oldAutomaticModesMigrateToOnRequest() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val prefs = ctx.getSharedPreferences("redflag_settings", Context.MODE_PRIVATE)
        for ((stored, want) in listOf("AUTO" to Mode.ON_REQUEST, "CLOUD" to Mode.ON_REQUEST, "LITE" to Mode.LITE, null to Mode.ON_REQUEST)) {
            prefs.edit().apply { if (stored == null) remove("mode") else putString("mode", stored) }.commit()
            assertEquals(want, Settings(ctx).mode, "stored=$stored")
        }
    }
}
