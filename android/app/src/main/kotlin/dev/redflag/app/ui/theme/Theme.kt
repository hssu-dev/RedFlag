package dev.redflag.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.node.invalidateDraw

@Immutable
data class LevelColors(val fg: Color, val container: Color)

@Immutable
data class RedFlagColors(
    val safe: LevelColors,
    val caution: LevelColors,
    val danger: LevelColors,
    val critical: LevelColors,
    val unknown: LevelColors,
    val cloud: Color,
    val dark: Boolean,
) {
    fun level(level: String?): LevelColors = when (level) {
        "safe" -> safe
        "caution" -> caution
        "danger" -> danger
        "critical" -> critical
        else -> unknown
    }
}

private fun levelColors(dark: Boolean) = RedFlagColors(
    safe = LevelColors(if (dark) TokensLevel.safeDark else TokensLevel.safeLight, if (dark) TokensLevel.safeContainerDark else TokensLevel.safeContainerLight),
    caution = LevelColors(if (dark) TokensLevel.cautionDark else TokensLevel.cautionLight, if (dark) TokensLevel.cautionContainerDark else TokensLevel.cautionContainerLight),
    danger = LevelColors(if (dark) TokensLevel.dangerDark else TokensLevel.dangerLight, if (dark) TokensLevel.dangerContainerDark else TokensLevel.dangerContainerLight),
    critical = LevelColors(if (dark) TokensLevel.criticalDark else TokensLevel.criticalLight, if (dark) TokensLevel.criticalContainerDark else TokensLevel.criticalContainerLight),
    unknown = LevelColors(if (dark) TokensLevel.unknownDark else TokensLevel.unknownLight, if (dark) TokensLevel.unknownContainerDark else TokensLevel.unknownContainerLight),
    cloud = if (dark) TokensLevel.cloudDark else TokensLevel.cloudLight,
    dark = dark,
)

val LocalRedFlagColors = staticCompositionLocalOf { levelColors(false) }

private fun style(t: TypeToken, family: FontFamily = FontFamily.Default) = TextStyle(
    fontFamily = family,
    fontSize = t.size.sp,
    lineHeight = t.lineHeight.sp,
    fontWeight = FontWeight(t.weight),
    letterSpacing = t.letterSpacing.sp,
)

object RfType {
    val score = style(TokensType.displayScore)
    val headline = style(TokensType.headline)
    val title = style(TokensType.title)
    val body = style(TokensType.body)
    val label = style(TokensType.label)
    val mono = style(TokensType.mono, FontFamily.Monospace)
}

@Composable
fun RedFlagTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (darkTheme) darkColorScheme(
        primary = TokensDark.primary, onPrimary = TokensDark.onPrimary,
        primaryContainer = TokensDark.primaryContainer, onPrimaryContainer = TokensDark.onPrimaryContainer,
        secondary = TokensDark.secondary, onSecondary = TokensDark.onSecondary,
        background = TokensDark.background, onBackground = TokensDark.onBackground,
        surface = TokensDark.surface, onSurface = TokensDark.onSurface,
        surfaceVariant = TokensDark.surfaceVariant, onSurfaceVariant = TokensDark.onSurfaceVariant,
        outline = TokensDark.outline, scrim = TokensDark.scrim,
    ) else lightColorScheme(
        primary = TokensLight.primary, onPrimary = TokensLight.onPrimary,
        primaryContainer = TokensLight.primaryContainer, onPrimaryContainer = TokensLight.onPrimaryContainer,
        secondary = TokensLight.secondary, onSecondary = TokensLight.onSecondary,
        background = TokensLight.background, onBackground = TokensLight.onBackground,
        surface = TokensLight.surface, onSurface = TokensLight.onSurface,
        surfaceVariant = TokensLight.surfaceVariant, onSurfaceVariant = TokensLight.onSurfaceVariant,
        outline = TokensLight.outline, scrim = TokensLight.scrim,
    )
    val typography = Typography(
        displayLarge = RfType.score, headlineMedium = RfType.headline, titleMedium = RfType.title,
        bodyLarge = RfType.body, bodyMedium = RfType.body.copy(fontSize = 14.sp, lineHeight = 20.sp), labelLarge = RfType.label,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalRedFlagColors provides levelColors(darkTheme)) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            // The app is CPU-drawn (manifest hardwareAccelerated=false). Ripples start a RenderNodeAnimator, which
            // crashes there ("Cannot start this animator on a detached view", S26 2026-11-05): no ripples, a plain
            // pressed highlight instead.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalRippleConfiguration provides null,
                androidx.compose.foundation.LocalIndication provides PressHighlight,
                content = content,
            )
        }
    }
}

/** Tap feedback without ripples: a translucent overlay while pressed (works on a software canvas). */
object PressHighlight : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode =
        PressHighlightNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7

    private class PressHighlightNode(private val source: androidx.compose.foundation.interaction.InteractionSource) :
        androidx.compose.ui.Modifier.Node(), androidx.compose.ui.node.DrawModifierNode {
        private var pressed = false
        override fun onAttach() {
            coroutineScope.launch {
                val presses = ArrayList<androidx.compose.foundation.interaction.PressInteraction.Press>()
                source.interactions.collect { i ->
                    when (i) {
                        is androidx.compose.foundation.interaction.PressInteraction.Press -> presses += i
                        is androidx.compose.foundation.interaction.PressInteraction.Release -> presses -= i.press
                        is androidx.compose.foundation.interaction.PressInteraction.Cancel -> presses -= i.press
                    }
                    val now = presses.isNotEmpty()
                    if (now != pressed) { pressed = now; this@PressHighlightNode.invalidateDraw() }
                }
            }
        }
        override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
            drawContent()
            if (pressed) drawRect(Color.Gray.copy(alpha = 0.18f))
        }
    }
}
