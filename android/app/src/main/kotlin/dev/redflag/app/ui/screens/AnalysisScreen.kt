package dev.redflag.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.redflag.app.R
import dev.redflag.app.analysis.AnalysisUi
import dev.redflag.app.analysis.CloudUi
import dev.redflag.app.analysis.ContextUi
import dev.redflag.app.analysis.Phase
import dev.redflag.app.lite.StepStatus
import dev.redflag.app.ui.components.BreathingGlow
import dev.redflag.app.ui.components.CloudPanel
import dev.redflag.app.ui.components.ContextActions
import dev.redflag.app.ui.components.ContextPrompt
import dev.redflag.app.ui.components.ContextResult
import dev.redflag.app.ui.components.HostChip
import dev.redflag.app.ui.components.LevelPill
import dev.redflag.app.ui.components.OnDeviceBadge
import dev.redflag.app.ui.components.RedFlagList
import dev.redflag.app.ui.components.ScanShield
import dev.redflag.app.ui.components.ScoreGaugeLive
import dev.redflag.app.ui.components.Staggered
import dev.redflag.app.ui.components.StepList
import dev.redflag.app.ui.components.isKorean
import dev.redflag.app.ui.components.stepLabel
import dev.redflag.app.ui.theme.LocalRedFlagColors
import dev.redflag.app.ui.theme.RfType
import dev.redflag.app.ui.theme.TokensDim
import kotlinx.coroutines.delay

class AnalysisActions(
    val onGoBack: () -> Unit,
    val onOpenAnyway: () -> Unit,
    val onDeepAnalysis: () -> Unit,
    val onContinue: () -> Unit,
    /** While still checking: stop the check and open the page (the page is NOT marked safe). */
    val onSkip: () -> Unit = {},
    /** Context analyzer (consent / question / direct input). */
    val context: ContextActions = ContextActions(),
    /** false in "on-device only" mode: no advanced-analysis buttons at all. */
    val cloudAvailable: Boolean = true,
    /** In-app link check (docs/plans/manual_url_check.md §3): close instead of "go back", never auto-continue. */
    val inspect: Boolean = false,
    /** Shown under the host: "bit.ly/3xY → safepay-escrow.shop/pay" when a short link was resolved. */
    val chain: String? = null,
    /** In-app check: the other links found in the same text. */
    val otherLinks: List<String> = emptyList(),
    val onPickLink: (String) -> Unit = {},
    /** In-app check of a stored verdict: analyse it again from scratch. */
    val onRecheck: (() -> Unit)? = null,
)

/** Waiting → (safe | caution | block | unverified). Used full-screen by the activity and the Chrome cover. */
@Composable
fun AnalysisScreen(
    ui: AnalysisUi, actions: AnalysisActions, modifier: Modifier = Modifier,
    /** Chrome cover only: back to the small pill (never offered on a danger screen). */
    onCollapse: (() -> Unit)? = null,
    /** Chrome cover only: why it opened by itself (the user tapped an input field before the verdict). */
    notice: String? = null,
) {
    val key = when {
        ui.phase == Phase.RUNNING -> "running"
        ui.unverified -> "unverified"
        else -> ui.displayLevel
    }
    val colors = LocalRedFlagColors.current

    // The score animation lives ABOVE the screen switch: a re-score (context step: 25 -> 95) counts up from
    // the old number while the screen crossfades to the new level, instead of restarting from 0.
    val target = if (ui.phase == Phase.RESULT) ui.displayScore.toFloat() else 0f
    val score = remember { Animatable(0f) }
    // A stored verdict (revisit of a blocked site, or a history entry) sweeps up like a fresh one: the motion is
    // what makes "this site is dangerous" register, so it is never skipped (fix 2026-11-04: it used to snap).
    LaunchedEffect(target) {
        score.animateTo(target, tween(if (score.value == 0f) TokensDim.motion_gaugeSweepMs else 1300, easing = FastOutSlowInEasing))
    }

    // A verdict is also felt: a firm "reject" buzz for danger/critical, a light confirm for safe.
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(key) {
        when (key) {
            "danger", "critical" -> haptic.performHapticFeedback(HapticFeedbackType.Reject)
            "safe" -> haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }

    val tint by animateColorAsState(
        when (key) {
            "running", "safe" -> MaterialTheme.colorScheme.background
            "unverified" -> colors.unknown.container
            else -> colors.level(key).container
        },
        tween(600), label = "tint",
    )
    Box(
        modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(tint, MaterialTheme.colorScheme.background), startY = 0f, endY = 1700f),
        ),
    ) {
        AnimatedContent(
            targetState = key,
            transitionSpec = {
                (fadeIn(tween(TokensDim.motion_screenCrossfadeMs)) + scaleIn(tween(TokensDim.motion_screenCrossfadeMs), initialScale = 0.97f)) togetherWith
                    fadeOut(tween(TokensDim.motion_screenCrossfadeMs / 2))
            },
            label = "analysis",
        ) { k ->
            Column(
                Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (onCollapse != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onCollapse) { Text(stringResource(R.string.cover_collapse), style = RfType.label) }
                    }
                }
                HostChip(ui.host)
                notice?.let {
                    Text(it, style = RfType.label, color = colors.caution.fg, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(colors.caution.container).padding(horizontal = 12.dp, vertical = 8.dp))
                }
                actions.chain?.let {
                    Text(it, style = RfType.mono.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(24.dp))
                AnimatedVisibility(
                    visible = ui.context is ContextUi.Consent || ui.context is ContextUi.Ask || ui.context is ContextUi.Checking || ui.context is ContextUi.Judging,
                    enter = fadeIn(tween(260)) + expandVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)),
                    exit = fadeOut(tween(180)) + shrinkVertically(tween(260)),
                ) {
                    Column {
                        ContextPrompt(ui, actions.context)
                        Spacer(Modifier.height(20.dp))
                    }
                }
                when (k) {
                    "running" -> Waiting(ui, actions)
                    "unverified" -> Unverified(ui, actions)
                    "safe" -> SafeResult(ui, actions, score.value)
                    "caution" -> Caution(ui, actions, score.value)
                    else -> Block(ui, actions, score.value)
                }
                Spacer(Modifier.height(12.dp))
                ContextResult(ui, actions.context)
                if (actions.inspect && ui.phase == Phase.RESULT) InspectFooter(actions)
                Spacer(Modifier.height(12.dp))
                OnDeviceBadge(ui.engineLabel)
            }
        }
    }
}

@Composable
private fun Waiting(ui: AnalysisUi, a: AnalysisActions) {
    var showSkip by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(SKIP_DELAY_MS); showSkip = true }
    val done = ui.steps.count { it.status == StepStatus.DONE || it.status == StepStatus.SKIPPED || it.status == StepStatus.FAILED }
    val fraction by animateFloatAsState(done / ui.steps.size.toFloat(), tween(500, easing = FastOutSlowInEasing), label = "progress")
    val running = ui.steps.firstOrNull { it.status == StepStatus.RUNNING }
    ScanShield(MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(18.dp))
    Text(stringResource(R.string.waiting_title), style = RfType.headline, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    AnimatedContent(running?.id, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "now") { id ->
        Text(
            id?.let { (if (isKorean()) "지금: " else "Now: ") + stepLabel(it) } ?: stringResource(R.string.waiting_subtitle),
            style = RfType.body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(18.dp))
    LinearProgressIndicator(
        progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), drawStopIndicator = {},
    )
    Spacer(Modifier.height(22.dp))
    StepList(ui.steps, Modifier.fillMaxWidth())
    AnimatedVisibility(visible = showSkip, enter = fadeIn(tween(TokensDim.motion_stepEnterMs)) + expandVertically()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 28.dp)) {
            OutlinedButton(a.onSkip, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(TokensDim.shape_medium.dp)) {
                Text(stringResource(if (a.inspect) R.string.inspect_cancel else R.string.skip_check), style = RfType.title)
            }
            if (!a.inspect) Text(
                stringResource(R.string.skip_check_hint), style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** The skip button appears after a short delay so it is not hit by accident on fast checks. */
private const val SKIP_DELAY_MS = 3_000L

@Composable
private fun Headline(ui: AnalysisUi) {
    val r = ui.cloudReport?.takeIf { (it.score) >= (ui.lite?.score ?: 0) } ?: ui.lite ?: return
    AnimatedContent(if (isKorean()) r.headline.ko else r.headline.en, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) }, label = "headline") { text ->
        Text(text, style = RfType.headline, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
    }
}

private fun flagsOf(ui: AnalysisUi) =
    (ui.cloudReport?.takeIf { it.score >= (ui.lite?.score ?: 0) } ?: ui.lite)?.redFlags.orEmpty()

/** Gauge (+ glow for warnings) and the "+N from context" chip when the context step raised the score. */
@Composable
private fun GaugeBlock(ui: AnalysisUi, score: Float, glow: Boolean) {
    val c = LocalRedFlagColors.current.level(ui.displayLevel)
    Box(contentAlignment = Alignment.Center) {
        if (glow) BreathingGlow(c.fg)
        ScoreGaugeLive(score)
    }
    val added = (ui.context as? ContextUi.Done)?.summary?.added ?: 0
    AnimatedVisibility(added > 0, enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn()) {
        Row(
            Modifier.padding(top = 4.dp).clip(RoundedCornerShape(TokensDim.shape_pill.dp)).background(c.container).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Forum, null, tint = c.fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (isKorean()) "+$added · 받은 상황 반영" else "+$added · from how you got the link", style = RfType.label, color = c.fg)
        }
    }
}

@Composable
private fun Block(ui: AnalysisUi, a: AnalysisActions, score: Float) {
    val colors = LocalRedFlagColors.current
    val c = colors.level(ui.displayLevel)
    if (ui.revisit) {
        Text(
            stringResource(if (a.inspect) R.string.inspect_stored_banner else R.string.revisit_banner), style = RfType.title, color = c.fg, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(TokensDim.shape_medium.dp)).background(c.container).padding(12.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
    GaugeBlock(ui, score, glow = true)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.block_title), style = RfType.label, color = c.fg)
    Spacer(Modifier.height(8.dp))
    Headline(ui)
    Spacer(Modifier.height(22.dp))
    Text(stringResource(R.string.block_why), style = RfType.title, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    RedFlagList(flagsOf(ui), modifier = Modifier.fillMaxWidth())
    if (flagsOf(ui).any { it.ruleId == "safe_browsing_hit" }) {
        Text(stringResource(R.string.sb_attribution), style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
    Spacer(Modifier.height(16.dp))
    CloudPanel(ui.cloud, ui.lite)
    CloudContextExtras(ui, a)
    Spacer(Modifier.height(20.dp))
    Staggered(3) {
        Button(
            a.onGoBack, Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.fg, contentColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(TokensDim.shape_medium.dp),
        ) { Text(stringResource(if (a.inspect) R.string.inspect_close else R.string.block_go_back), style = RfType.title) }
    }
    if (ui.cloud is CloudUi.Idle && a.cloudAvailable) {
        TextButton(a.onDeepAnalysis, Modifier.fillMaxWidth()) { Text(stringResource(R.string.second_opinion), color = colors.cloud) }
    }
    TextButton(a.onOpenAnyway, Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.block_open_anyway), style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Caution(ui: AnalysisUi, a: AnalysisActions, score: Float) {
    val colors = LocalRedFlagColors.current
    GaugeBlock(ui, score, glow = true)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.caution_title), style = RfType.label, color = colors.caution.fg)
    Spacer(Modifier.height(8.dp))
    Headline(ui)
    Spacer(Modifier.height(20.dp))
    RedFlagList(flagsOf(ui), modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(16.dp))
    CloudPanel(ui.cloud, ui.lite)
    CloudContextExtras(ui, a)
    Spacer(Modifier.height(20.dp))
    if (ui.cloud is CloudUi.Idle && a.cloudAvailable) {
        FilledTonalButton(a.onDeepAnalysis, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(TokensDim.shape_medium.dp)) {
            Text(stringResource(R.string.caution_deep), style = RfType.title)
        }
        Text(stringResource(R.string.caution_deep_hint), style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
    }
    OutlinedButton(a.onOpenAnyway, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(TokensDim.shape_medium.dp)) {
        Text(stringResource(R.string.caution_open), style = RfType.title)
    }
    TextButton(a.onGoBack, Modifier.fillMaxWidth()) { Text(stringResource(if (a.inspect) R.string.inspect_close else R.string.block_go_back)) }
}

@Composable
private fun Unverified(ui: AnalysisUi, a: AnalysisActions) {
    val colors = LocalRedFlagColors.current
    Icon(Icons.Rounded.HourglassBottom, null, tint = colors.unknown.fg, modifier = Modifier.size(56.dp))
    Spacer(Modifier.height(12.dp))
    LevelPill(null)
    Spacer(Modifier.height(12.dp))
    // ④ say WHY: a dead site is not the same as a site that kept RedFlag out
    val why = dev.redflag.app.lite.Unverified.reason(ui.lite)
    val (titleRes, bodyRes) = when (why) {
        dev.redflag.app.lite.Unverified.Reason.UNREACHABLE -> R.string.unverified_unreachable_title to R.string.unverified_unreachable_body
        dev.redflag.app.lite.Unverified.Reason.BLOCKED, dev.redflag.app.lite.Unverified.Reason.CHALLENGE -> R.string.unverified_blocked_title to R.string.unverified_blocked_body
        else -> R.string.unverified_title to R.string.unverified_body
    }
    Text(stringResource(titleRes), style = RfType.headline, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(bodyRes), style = RfType.body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    if (ui.lite?.redFlags?.isNotEmpty() == true) {
        Spacer(Modifier.height(16.dp))
        RedFlagList(ui.lite.redFlags, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(16.dp))
    CloudPanel(ui.cloud, ui.lite)
    CloudContextExtras(ui, a)
    Spacer(Modifier.height(20.dp))
    if (ui.cloud is CloudUi.Idle && a.cloudAvailable) {
        FilledTonalButton(a.onDeepAnalysis, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(TokensDim.shape_medium.dp)) {
            Text(stringResource(R.string.caution_deep), style = RfType.title)
        }
    }
    OutlinedButton(a.onOpenAnyway, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(TokensDim.shape_medium.dp)) {
        Text(stringResource(R.string.caution_open), style = RfType.title)
    }
    TextButton(a.onGoBack, Modifier.fillMaxWidth()) { Text(stringResource(if (a.inspect) R.string.inspect_close else R.string.block_go_back)) }
}

@Composable
private fun SafeResult(ui: AnalysisUi, a: AnalysisActions, score: Float) {
    val safeC = LocalRedFlagColors.current.safe
    GaugeBlock(ui, score, glow = false)
    Spacer(Modifier.height(14.dp))
    Staggered(1) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CheckCircle, null, tint = safeC.fg, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.safe_title), style = RfType.headline, color = MaterialTheme.colorScheme.onBackground)
        }
    }
    Spacer(Modifier.height(16.dp))
    CloudPanel(ui.cloud, ui.lite)
    CloudContextExtras(ui, a)
    Spacer(Modifier.height(20.dp))
    Staggered(2) {
        Button(
            a.onContinue, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(TokensDim.shape_medium.dp),
            colors = ButtonDefaults.buttonColors(containerColor = safeC.fg, contentColor = MaterialTheme.colorScheme.surface),
        ) {
            Text(stringResource(if (a.inspect) R.string.inspect_open_chrome else R.string.safe_continue), style = RfType.title.copy(fontWeight = FontWeight.SemiBold))
        }
    }
    if (a.inspect) TextButton(a.onGoBack, Modifier.fillMaxWidth()) { Text(stringResource(R.string.inspect_close)) }
    if (a.inspect && ui.cloud is CloudUi.Idle && a.cloudAvailable) {
        TextButton(a.onDeepAnalysis, Modifier.fillMaxWidth()) { Text(stringResource(R.string.second_opinion), color = LocalRedFlagColors.current.cloud) }
    }
    Spacer(Modifier.height(4.dp))
    Column(verticalArrangement = Arrangement.Center) {}
}

/** In-app check: re-check a stored verdict, and the other links found in the same text. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun InspectFooter(a: AnalysisActions) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        a.onRecheck?.let { recheck ->
            OutlinedButton(recheck, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(TokensDim.shape_medium.dp)) {
                Text(stringResource(R.string.inspect_recheck), style = RfType.title)
            }
        }
        if (a.otherLinks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.inspect_other_links), style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                a.otherLinks.forEach { u ->
                    androidx.compose.material3.AssistChip(onClick = { a.onPickLink(u) }, label = { Text(dev.redflag.app.check.LinkLabels.short(u), style = RfType.mono.copy(fontSize = 12.sp)) })
                }
            }
        }
    }
}

/**
 * User-provided context and the advanced analysis (docs/plans/manual_url_check.md §5.5): what goes along
 * (one line + "show what is sent"), "starts when the question is answered", and "analyse again with it".
 */
@Composable
private fun CloudContextExtras(ui: AnalysisUi, a: AnalysisActions) {
    if (!a.cloudAvailable) return
    val cc = ui.cloudContext
    val colors = LocalRedFlagColors.current
    val ko = isKorean()
    var open by remember { mutableStateOf(false) }
    val running = ui.cloud as? CloudUi.Running
    if (running?.waitingForContext == true) {
        Text(stringResource(R.string.cloud_context_waiting), style = RfType.label, color = colors.cloud,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
    if (cc == null || !cc.any) return
    val idle = ui.cloud is CloudUi.Idle
    if (idle || cc.stale) {
        Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cloud_context_disclosure, cc.statements, cc.pasted), style = RfType.label.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
                TextButton({ open = !open }) { Text(stringResource(if (open) R.string.cloud_context_hide else R.string.cloud_context_preview), style = RfType.label.copy(fontSize = 12.sp)) }
            }
            if (open) cc.previews.forEach { t ->
                Text(t, style = RfType.mono.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp))
                Spacer(Modifier.height(4.dp))
            }
            if (cc.stale) {
                FilledTonalButton(a.onDeepAnalysis, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(TokensDim.shape_medium.dp)) {
                    Text(stringResource(R.string.cloud_context_rerun), style = RfType.title)
                }
            }
        }
    }
}
