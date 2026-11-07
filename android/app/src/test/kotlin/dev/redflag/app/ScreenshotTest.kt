package dev.redflag.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.redflag.app.analysis.AnalysisUi
import dev.redflag.app.analysis.CloudUi
import dev.redflag.app.analysis.Phase
import dev.redflag.app.analysis.StepUi
import dev.redflag.app.lite.StepId
import dev.redflag.app.lite.StepStatus
import dev.redflag.app.ui.screens.AnalysisActions
import dev.redflag.app.ui.screens.AnalysisScreen
import dev.redflag.app.ui.theme.RedFlagTheme
import dev.redflag.core.model.RedFlagJson
import dev.redflag.core.model.RiskReport
import dev.redflag.core.model.TraceStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * JVM screenshot tests (Roborazzi + Robolectric) for every analysis state, light/dark, en/ko.
 * Record: ./gradlew :app:recordRoborazziDebug   Verify: ./gradlew :app:verifyRoborazziDebug
 * Images: app/build/outputs/roborazzi/ (copied to shots/roborazzi/ for review).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(level: String): RiskReport =
        RedFlagJson.decodeFromString(RiskReport.serializer(), File("../../contracts/fixtures/$level.json").readText())

    private val noop = AnalysisActions({}, {}, {}, {})

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { RedFlagTheme(darkTheme = dark) { Box(Modifier.fillMaxSize()) { content() } } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun running() = AnalysisUi(
        url = "https://dbs-secure-verify.top/login", host = "dbs-secure-verify.top", engineLabel = "GPU",
        steps = listOf(
            StepUi(StepId.GATE, StepStatus.DONE, "unfamiliar domain"),
            StepUi(StepId.SAFE_BROWSING, StepStatus.DONE, "not listed"),
            StepUi(StepId.EXTRACT, StepStatus.DONE, "1 form · no hidden text"),
            StepUi(StepId.SIGNALS, StepStatus.DONE, "registered 4 days ago · Let's Encrypt"),
            StepUi(StepId.GEMMA, StepStatus.RUNNING, null),
            StepUi(StepId.SCORE, StepStatus.PENDING, null),
        ),
    )

    private fun result(lite: RiskReport, cloud: CloudUi = CloudUi.Idle) =
        AnalysisUi(url = lite.url, host = lite.registrableDomain ?: "", phase = Phase.RESULT, lite = lite, cloud = cloud, engineLabel = "GPU")

    @Test fun waiting() = shot("analysis_waiting") { AnalysisScreen(running(), noop) }

    @Test fun waitingDark() = shot("analysis_waiting_dark", dark = true) { AnalysisScreen(running(), noop) }

    @Test fun blockCritical() = shot("analysis_block_critical") { AnalysisScreen(result(fixture("critical")), noop) }

    @Test fun blockCriticalDark() = shot("analysis_block_critical_dark", dark = true) { AnalysisScreen(result(fixture("critical")), noop) }

    @Test fun blockDanger() = shot("analysis_block_danger") { AnalysisScreen(result(fixture("danger")), noop) }

    @Test fun caution() = shot("analysis_caution") { AnalysisScreen(result(fixture("caution")), noop) }

    @Test fun cautionCloudRunning() = shot("analysis_caution_cloud_running") {
        AnalysisScreen(
            result(
                fixture("caution"),
                CloudUi.Running(
                    listOf(
                        TraceStep(0, "start", "cloud analysis"),
                        TraceStep(1400, "sandbox_browse", "title '숨 필라테스' | 1 form"),
                        TraceStep(2600, "opus", "round 1: request sent"),
                    ),
                    "auto",
                ),
            ),
            noop,
        )
    }

    @Test fun cautionCloudCloaking() = shot("analysis_cloud_cloaking") {
        val cloud = fixture("critical").copy(
            score = 100, cloaking = dev.redflag.core.model.Cloaking(true, "device saw a card form, sandbox saw none"),
        )
        AnalysisScreen(result(fixture("danger"), CloudUi.Done(cloud, listOf(TraceStep(9700, "cloaking", "SUSPECTED")))), noop)
    }

    @Test fun unverified() = shot("analysis_unverified") {
        AnalysisScreen(result(fixture("caution").copy(status = "timeout", features = null)), noop)
    }

    @Test fun safe() = shot("analysis_safe") { AnalysisScreen(result(fixture("safe")), noop) }

    @Test @Config(qualifiers = "ko-w411dp-h891dp-xxhdpi")
    fun blockKorean() = shot("analysis_block_critical_ko") { AnalysisScreen(result(fixture("critical")), noop) }

    @Test @Config(qualifiers = "ko-w411dp-h891dp-xxhdpi")
    fun waitingKorean() = shot("analysis_waiting_ko") { AnalysisScreen(running(), noop) }
}
