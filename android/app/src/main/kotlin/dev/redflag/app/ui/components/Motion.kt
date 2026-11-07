package dev.redflag.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.redflag.app.R

/*
 * Motion + brand drawing shared by the analysis and home screens. Everything is drawn with Canvas so it
 * works the same inside the Chrome cover overlay (no window-level animations there).
 */

/** Shield outline of the app icon (108-unit viewport: x 32..76, y 27.5..81), scaled into a w x h box. */
fun shieldPath(w: Float, h: Float): Path {
    val sx = w / 44f
    val sy = h / 53.5f
    fun x(v: Float) = (v - 32f) * sx
    fun y(v: Float) = (v - 27.5f) * sy
    return Path().apply {
        moveTo(x(54f), y(27.5f))
        lineTo(x(76f), y(35f))
        lineTo(x(76f), y(52.5f))
        cubicTo(x(76f), y(66f), x(67f), y(75.5f), x(54f), y(81f))
        cubicTo(x(41f), y(75.5f), x(32f), y(66f), x(32f), y(52.5f))
        lineTo(x(32f), y(35f))
        close()
    }
}

/** The white pennant of the mark, in the same box as [shieldPath]. */
private fun DrawScope.drawFlag(w: Float, h: Float, color: Color) {
    val sx = w / 44f
    val sy = h / 53.5f
    fun x(v: Float) = (v - 32f) * sx
    fun y(v: Float) = (v - 27.5f) * sy
    drawRoundRect(color, topLeft = Offset(x(45f), y(40.5f)), size = Size(3.6f * sx, 29.8f * sy),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.8f * sx))
    drawPath(Path().apply { moveTo(x(48.6f), y(41f)); lineTo(x(67f), y(47.6f)); lineTo(x(48.6f), y(54.4f)); close() }, color)
}

/** App mark (the launcher icon's two layers) for in-app headers. */
@Composable
fun BrandMark(size: Dp = 48.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.28f)), contentAlignment = Alignment.Center) {
        // launchers show the inner 72 of the 108-unit adaptive canvas: scale 1.5x to match the home-screen icon
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
    }
}

/**
 * Waiting animation: the mark being scanned. A sweep of light orbits the shield, two rings ripple out,
 * and a scan line runs down the shield face.
 */
@Composable
fun ScanShield(color: Color, modifier: Modifier = Modifier, size: Dp = 148.dp) {
    val t = rememberInfiniteTransition(label = "scan")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "angle")
    val ripple by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "ripple")
    val scan by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scanline")
    Canvas(modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2
        // ripples
        for (k in 0..1) {
            val p = (ripple + k * 0.5f) % 1f
            drawCircle(color.copy(alpha = 0.22f * (1f - p)), radius = r * (0.48f + 0.5f * p), center = c, style = Stroke(2.dp.toPx()))
        }
        // orbiting sweep
        rotate(angle, c) {
            drawArc(
                Brush.sweepGradient(0f to Color.Transparent, 0.75f to color.copy(alpha = 0.05f), 1f to color.copy(alpha = 0.85f), center = c),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(c.x - r * 0.78f, c.y - r * 0.78f), size = Size(r * 1.56f, r * 1.56f),
                style = Stroke(3.dp.toPx()),
            )
        }
        // shield + scan line
        val w = r * 0.86f
        val h = w * 53.5f / 44f
        translate(c.x - w / 2, c.y - h / 2) {
            val shield = shieldPath(w, h)
            drawPath(shield, Brush.linearGradient(listOf(lerp(color, Color.White, 0.25f), color), Offset(0f, 0f), Offset(w, h)))
            clipPath(shield) {
                val y = h * scan
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.45f), 1f to Color.Transparent, startY = y - h * 0.12f, endY = y + h * 0.12f),
                    topLeft = Offset(0f, y - h * 0.12f), size = Size(w, h * 0.24f),
                )
            }
            drawFlag(w, h, Color.White)
        }
    }
}

/**
 * Slow breathing glow in a level color (behind the score gauge on a warning). Breathes [BREATHS] times, then holds:
 * the app is CPU-drawn (manifest), and an endless glow redrew the whole screen every frame (~1 core, 2026-11-05).
 */
@Composable
fun BreathingGlow(color: Color, modifier: Modifier = Modifier, size: Dp = 260.dp) {
    val a = remember { androidx.compose.animation.core.Animatable(0.10f) }
    LaunchedEffect(Unit) {
        repeat(BREATHS) {
            a.animateTo(0.30f, tween(1600, easing = FastOutSlowInEasing))
            a.animateTo(0.10f, tween(1600, easing = FastOutSlowInEasing))
        }
        a.animateTo(0.22f, tween(800, easing = FastOutSlowInEasing))
    }
    Canvas(modifier.size(size)) {
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a.value), Color.Transparent), center = center, radius = this.size.minDimension / 2))
    }
}

private const val BREATHS = 3

/** Level for a (possibly mid-animation) score: same thresholds as scoring_rules.json levels. */
fun levelForScore(score: Float): String = when {
    score >= 85f -> "critical"
    score >= 60f -> "danger"
    score >= 30f -> "caution"
    else -> "safe"
}

/** Fades + slides its content in once, [index] * [staggerMs] after first composition. */
@Composable
fun Staggered(index: Int, staggerMs: Int = 90, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(320, delayMillis = index * staggerMs)) +
            slideInVertically(tween(380, delayMillis = index * staggerMs, easing = FastOutSlowInEasing)) { it / 3 },
    ) { content() }
}
