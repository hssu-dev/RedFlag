package dev.redflag.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.redflag.app.R
import dev.redflag.app.accessibility.PillStyle
import dev.redflag.app.analysis.AnalysisUi
import dev.redflag.app.lite.StepStatus
import dev.redflag.app.ui.theme.LocalRedFlagColors
import dev.redflag.app.ui.theme.RfType

/**
 * The Chrome cover while it is small (docs/superpowers/specs/2026-11-05-check-pill-design.md §2):
 * "◌ 검사 중 · host" with a thin progress line, turning green / amber / grey as the verdict comes in.
 * Tapping it opens the full analysis screen.
 */
@Composable
fun CheckPill(ui: AnalysisUi, style: PillStyle, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalRedFlagColors.current
    val accent by animateColorAsState(
        when (style) {
            PillStyle.CHECKING -> MaterialTheme.colorScheme.primary
            PillStyle.SAFE -> c.safe.fg
            PillStyle.CAUTION, PillStyle.QUESTION -> c.caution.fg
            PillStyle.UNVERIFIED -> c.unknown.fg
        },
        tween(300), label = "pillAccent",
    )
    // pops in from slightly smaller, like a notification chip
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else 0.85f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "pillIn")
    val a11y = stringResource(R.string.pill_a11y)
    val shape = RoundedCornerShape(50)
    Column(
        modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(6.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, if (style == PillStyle.CHECKING) MaterialTheme.colorScheme.outline.copy(alpha = 0.5f) else accent.copy(alpha = 0.45f), shape)
            .clickable(onClick = onTap)
            .semantics { contentDescription = a11y }
            .animateContentSize(tween(260, easing = FastOutSlowInEasing))
            .widthIn(max = 320.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 14.dp, top = 7.dp, bottom = if (style == PillStyle.CHECKING) 4.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(style == PillStyle.CHECKING, transitionSpec = { scaleIn(tween(220)) + fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "pillIcon") { checking ->
                    when {
                        checking -> CircularProgressIndicator(Modifier.size(14.dp), color = accent, strokeWidth = 2.dp)
                        style == PillStyle.SAFE -> Icon(Icons.Rounded.Check, null, tint = accent, modifier = Modifier.size(16.dp))
                        else -> Box(Modifier.size(9.dp).clip(CircleShape).background(accent))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("RedFlag", style = RfType.label.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(6.dp))
            Text(
                when (style) {
                    PillStyle.CHECKING -> stringResource(R.string.pill_checking) + " · " + ui.host
                    PillStyle.SAFE -> stringResource(R.string.pill_safe)
                    PillStyle.CAUTION -> stringResource(R.string.pill_caution)
                    PillStyle.UNVERIFIED -> stringResource(R.string.pill_unverified)
                    PillStyle.QUESTION -> stringResource(R.string.pill_question)
                },
                style = RfType.label.copy(fontSize = 13.sp), color = if (style == PillStyle.CHECKING) MaterialTheme.colorScheme.onSurfaceVariant else accent,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (style == PillStyle.CHECKING) {
            val done = ui.steps.count { it.status == StepStatus.DONE || it.status == StepStatus.SKIPPED || it.status == StepStatus.FAILED }
            val fraction by animateFloatAsState(done / ui.steps.size.coerceAtLeast(1).toFloat(), tween(500, easing = FastOutSlowInEasing), label = "pillProgress")
            LinearProgressIndicator(
                progress = { fraction.coerceAtLeast(0.06f) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 6.dp).height(2.dp).clip(RoundedCornerShape(1.dp)),
                color = accent, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), drawStopIndicator = {},
            )
        }
    }
}
