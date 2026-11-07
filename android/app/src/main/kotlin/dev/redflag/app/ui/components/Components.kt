package dev.redflag.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dangerous
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.redflag.app.R
import dev.redflag.app.analysis.CloudUi
import dev.redflag.app.analysis.StepUi
import dev.redflag.app.lite.StepId
import dev.redflag.app.lite.StepStatus
import dev.redflag.app.ui.theme.LocalRedFlagColors
import dev.redflag.app.ui.theme.RfType
import dev.redflag.app.ui.theme.TokensDim
import dev.redflag.core.model.RedFlag
import dev.redflag.core.model.RiskReport
import java.util.Locale

fun levelIcon(level: String?): ImageVector = when (level) {
    "safe" -> Icons.Rounded.CheckCircle
    "caution" -> Icons.Rounded.Warning
    "danger" -> Icons.Rounded.Dangerous
    "critical" -> Icons.Rounded.GppBad
    else -> Icons.Rounded.HelpOutline
}

@Composable
fun levelLabel(level: String?): String = stringResource(
    when (level) {
        "safe" -> R.string.level_safe
        "caution" -> R.string.level_caution
        "danger" -> R.string.level_danger
        "critical" -> R.string.level_critical
        else -> R.string.level_unknown
    },
)

fun isKorean(): Boolean = Locale.getDefault().language == "ko"

/** 270° gauge that sweeps from 0 to [score] (token motion.gaugeSweepMs). */
@Composable
fun ScoreGauge(score: Int, level: String?, modifier: Modifier = Modifier, size: Dp = TokensDim.gauge_sizeDp.dp, animate: Boolean = true) {
    val colors = LocalRedFlagColors.current.level(level)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val progress = remember { Animatable(if (animate) 0f else score / 100f) }
    LaunchedEffect(score) {
        progress.animateTo(score / 100f, tween(TokensDim.motion_gaugeSweepMs, easing = FastOutSlowInEasing))
    }
    val label = levelLabel(level)
    val desc = "${stringResource(R.string.score_label)} $score, $label"
    Box(modifier.size(size).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = TokensDim.gauge_strokeDp.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, TokensDim.gauge_startAngle.toFloat(), TokensDim.gauge_sweepAngle.toFloat(), false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(colors.fg, TokensDim.gauge_startAngle.toFloat(), TokensDim.gauge_sweepAngle * progress.value, false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(progress.value * 100).toInt()}", style = RfType.score, color = MaterialTheme.colorScheme.onSurface)
            Text(label.uppercase(), style = RfType.label, color = colors.fg)
        }
    }
}

/**
 * Gauge driven by an externally animated [value] (0..100), so a re-score (e.g. 25 -> 95 after the context step)
 * counts up continuously across screen changes. Color and label follow the band the number is in.
 */
@Composable
fun ScoreGaugeLive(value: Float, modifier: Modifier = Modifier, size: Dp = TokensDim.gauge_sizeDp.dp) {
    val lvl = levelForScore(value)
    val fg by androidx.compose.animation.animateColorAsState(LocalRedFlagColors.current.level(lvl).fg, tween(280), label = "gaugeColor")
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val label = levelLabel(lvl)
    val desc = "${stringResource(R.string.score_label)} ${value.toInt()}, $label"
    Box(modifier.size(size).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = TokensDim.gauge_strokeDp.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, TokensDim.gauge_startAngle.toFloat(), TokensDim.gauge_sweepAngle.toFloat(), false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(fg, TokensDim.gauge_startAngle.toFloat(), TokensDim.gauge_sweepAngle * (value / 100f).coerceIn(0f, 1f), false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            // glowing tip
            val ang = Math.toRadians((TokensDim.gauge_startAngle + TokensDim.gauge_sweepAngle * (value / 100f).coerceIn(0f, 1f)).toDouble())
            val rr = arcSize.width / 2
            val tip = Offset(center.x + (rr * kotlin.math.cos(ang)).toFloat(), center.y + (rr * kotlin.math.sin(ang)).toFloat())
            if (value > 0.5f && value < 97f) drawCircle(fg.copy(alpha = 0.35f), radius = stroke * 0.95f, center = tip)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${value.toInt()}", style = RfType.score, color = MaterialTheme.colorScheme.onSurface)
            Text(label.uppercase(), style = RfType.label, color = fg)
        }
    }
}

@Composable
fun LevelPill(level: String?, text: String = levelLabel(level), modifier: Modifier = Modifier) {
    val c = LocalRedFlagColors.current.level(level)
    Row(
        modifier.clip(RoundedCornerShape(TokensDim.shape_pill.dp)).background(c.container).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(levelIcon(level), null, tint = c.fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = RfType.label, color = c.fg)
    }
}

@Composable
fun HostChip(host: String, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(TokensDim.shape_pill.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Language, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(host, style = RfType.mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

@Composable
fun OnDeviceBadge(engineLabel: String, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(TokensDim.shape_pill.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(TokensDim.shape_pill.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Memory, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(5.dp))
        Text(
            stringResource(R.string.on_device_badge) + " · Gemma 4 E4B" + if (engineLabel.isNotBlank()) " · $engineLabel" else "",
            style = RfType.label.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun stepLabel(id: StepId): String = stringResource(
    when (id) {
        StepId.GATE -> R.string.step_gate
        StepId.SAFE_BROWSING -> R.string.step_safe_browsing
        StepId.EXTRACT -> R.string.step_extract
        StepId.SIGNALS -> R.string.step_signals
        StepId.GEMMA -> R.string.step_gemma
        StepId.SCORE -> R.string.step_score
    },
)

/** Analysis steps appear one after another (stagger = motion.stepStaggerMs). */
@Composable
fun StepList(steps: List<StepUi>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val visible = steps.indexOfLast { it.status != StepStatus.PENDING }.coerceAtLeast(0) + 1
        steps.forEachIndexed { i, s ->
            AnimatedVisibility(
                visible = i < visible.coerceAtMost(steps.size),
                enter = fadeIn(tween(TokensDim.motion_stepEnterMs, delayMillis = i * TokensDim.motion_stepStaggerMs / 3)) +
                    slideInVertically(tween(TokensDim.motion_stepEnterMs)) { it / 2 },
            ) { StepRow(s) }
        }
    }
}

/**
 * Step details are written in English by the pipeline (they are also the report trace). On a Korean screen the
 * known phrases are shown in Korean; anything unrecognized is left as is.
 */
fun localizeStepDetail(detail: String, ko: Boolean): String {
    if (!ko) return detail
    val levels = mapOf("safe" to "안전", "caution" to "주의", "danger" to "위험", "critical" to "매우 위험")
    return detail.split(" · ").joinToString(" · ") { part ->
        val p = part.trim()
        Regex("^registered (\\d+) days ago$").find(p)?.let { return@joinToString "${it.groupValues[1]}일 전 등록" }
        Regex("^(\\d+) forms?$").find(p)?.let { return@joinToString "입력 폼 ${it.groupValues[1]}개" }
        Regex("^brand=(.+)$").find(p)?.let { m -> return@joinToString if (m.groupValues[1] == "none") "내세운 회사 없음" else "내세운 회사: ${m.groupValues[1]}" }
        Regex("^(\\d+) ms$").find(p)?.let { return@joinToString String.format(Locale.US, "%.1f초", it.groupValues[1].toInt() / 1000.0) }
        Regex("^retry (https?)$").find(p)?.let { return@joinToString "${it.groupValues[1]}로 다시 시도" }
        levels[p]?.let { return@joinToString it }
        when (p) {
            "unfamiliar domain" -> "처음 보는 도메인"
            "short link" -> "단축 링크"
            "user-generated content host" -> "사용자 콘텐츠 호스팅"
            "IP address host" -> "IP 주소로 된 주소"
            "known site" -> "알려진 사이트"
            "Safe Browsing hit" -> "세이프 브라우징 위험 목록에 있음"
            "not listed" -> "위험 목록에 없음"
            "listed" -> "위험 목록에 있음"
            "unavailable" -> "조회하지 못함"
            "no hidden text" -> "숨은 글 없음"
            "hidden text found" -> "숨은 글 있음"
            "registry lookup failed" -> "등록 정보 조회 실패"
            "no registry data" -> "등록 정보 없음"
            "skipped" -> "건너뜀"
            "unusable answer" -> "답을 쓸 수 없음"
            "gpu" -> "GPU"
            "cpu" -> "CPU"
            else -> p
        }
    }
}

@Composable
private fun StepRow(s: StepUi) {
    val colors = LocalRedFlagColors.current
    Row(verticalAlignment = Alignment.Top) {
        androidx.compose.animation.AnimatedContent(
            s.status, Modifier.size(22.dp),
            transitionSpec = {
                (androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f), initialScale = 0.3f) + fadeIn(tween(120))) togetherWith
                    androidx.compose.animation.fadeOut(tween(100))
            },
            contentAlignment = Alignment.Center, label = "stepIcon",
        ) { st ->
            when (st) {
                StepStatus.RUNNING, StepStatus.PENDING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                StepStatus.DONE -> Icon(Icons.Rounded.CheckCircle, null, tint = colors.safe.fg, modifier = Modifier.size(22.dp))
                StepStatus.SKIPPED -> Icon(Icons.Rounded.RemoveCircleOutline, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(22.dp))
                StepStatus.FAILED -> Icon(Icons.Rounded.Error, null, tint = colors.caution.fg, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.animateContentSize()) {
            Text(stepLabel(s.id), style = RfType.body, color = MaterialTheme.colorScheme.onSurface)
            if (!s.detail.isNullOrBlank() && s.status != StepStatus.RUNNING) {
                Text(localizeStepDetail(s.detail, isKorean()), style = RfType.label.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Pulsing shield ring for the waiting screen. */
@Composable
fun PulseRing(color: Color, modifier: Modifier = Modifier, size: Dp = 88.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(TokensDim.motion_pulseMs, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(color.copy(alpha = 0.18f * (1 - p)), radius = r * (0.55f + 0.45f * p))
        drawCircle(color.copy(alpha = 0.9f), radius = r * 0.42f)
    }
}

@Composable
fun RedFlagList(flags: List<RedFlag>, max: Int = 3, modifier: Modifier = Modifier) {
    val colors = LocalRedFlagColors.current
    val ko = isKorean()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        flags.take(max).forEachIndexed { i, f ->
            val lvl = when (f.severity) { "critical" -> "critical"; "high" -> "danger"; "medium" -> "caution"; else -> "caution" }
            val c = colors.level(lvl)
            Staggered(i) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(TokensDim.shape_medium.dp))
                    .background(MaterialTheme.colorScheme.surface).padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(c.container), contentAlignment = Alignment.Center) {
                    Icon(if (f.ruleId == "addresses_ai_analyzer" || f.ruleId == "cloaking_suspected") Icons.Rounded.VisibilityOff else levelIcon(lvl),
                        null, tint = c.fg, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (ko) f.title.ko else f.title.en, style = RfType.body.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    f.evidence?.takeIf { it.isNotBlank() && f.source != "signal+rule" && !it.startsWith("destination: ") }?.let {
                        Text("“$it”", style = RfType.mono.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    f.evidence?.takeIf { it.startsWith("destination: ") }?.let {
                        Text("→ " + it.removePrefix("destination: "), style = RfType.mono.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            }
        }
    }
}

/** Live Cloud agent trace + verdict. */
@Composable
fun CloudPanel(cloud: CloudUi, lite: RiskReport?, modifier: Modifier = Modifier) {
    if (cloud is CloudUi.Idle) return
    val colors = LocalRedFlagColors.current
    val ko = isKorean()
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(TokensDim.shape_large.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.padding(18.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Cloud, null, tint = colors.cloud, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when (cloud) {
                        is CloudUi.Done -> stringResource(R.string.cloud_done)
                        is CloudUi.Failed -> stringResource(R.string.cloud_title)
                        else -> stringResource(R.string.cloud_running)
                    },
                    style = RfType.title, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
                )
                if (cloud is CloudUi.Running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.cloud)
                if (cloud is CloudUi.Done) LevelPill(cloud.report.level, "${cloud.report.score} · ${levelLabel(cloud.report.level)}")
            }
            val traces = when (cloud) {
                is CloudUi.Running -> cloud.traces
                is CloudUi.Done -> cloud.traces
                is CloudUi.Failed -> cloud.traces
                CloudUi.Idle -> emptyList()
            }
            if (traces.isNotEmpty()) Spacer(Modifier.height(10.dp))
            traces.takeLast(7).forEach { t ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(String.format(Locale.US, "%5.1fs", t.tMs / 1000.0), style = RfType.mono.copy(fontSize = 11.sp), color = colors.cloud)
                    Spacer(Modifier.width(8.dp))
                    Text("${t.step}  ${t.detail}", style = RfType.mono.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (cloud is CloudUi.Failed) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.cloud_failed, cloud.message), style = RfType.body.copy(fontSize = 13.sp), color = colors.caution.fg)
            }
            if (cloud is CloudUi.Done) {
                val r = cloud.report
                Spacer(Modifier.height(10.dp))
                Text(if (ko) r.headline.ko else r.headline.en, style = RfType.body.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                if (r.cloaking.suspected) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(TokensDim.shape_small.dp)).background(colors.critical.container).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.VisibilityOff, null, tint = colors.critical.fg, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cloud_cloaking), style = RfType.body.copy(fontSize = 13.sp), color = colors.critical.fg)
                    }
                }
                if (lite != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.cloud_vs_lite, "${lite.score} ${levelLabel(lite.level)}", "${r.score} ${levelLabel(r.level)}"),
                        style = RfType.label, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
