package com.music.bitchord.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A theme's effects as they stand right now — resolved by the app from the listener's switches,
 * the device's tier and the moment (see WinterEffectsPolicy). Two separate questions:
 *
 * - **Is it there?** The five flags. Off means gone: no aurora, no live stars, no snow, a plain
 *   tinted bar, no frost. That's what each switch in Settings controls, visibly.
 * - **May it move?** [moving]. False when the app is saving power, animations are off system-wide
 *   or nobody is looking: whatever is shown holds still (a frozen aurora, steady stars; snow and
 *   shooting stars, which only make sense moving, are left out).
 */
@Immutable
data class ThemeEffects(
    /** The aurora is in the sky (and glows through the bars, with [glass]). */
    val aurora: Boolean = false,
    /** Fine snow drifts in front of the scene. */
    val snow: Boolean = false,
    /** The bright stars twinkle and the odd one shoots. */
    val stars: Boolean = false,
    /** Bars are frosted glass that blurs what's behind them; off, a plain translucent tint. */
    val glass: Boolean = false,
    /** Frost at the edges of the header and cards. */
    val frost: Boolean = false,
    /** Anything shown may move. */
    val moving: Boolean = false,
) {
    /** Something is on screen that moves: the clock has to tick. */
    val anyAnimated: Boolean get() = moving && (aurora || snow || stars)

    companion object {
        val None = ThemeEffects()
    }
}

/** A page title's colour over the theme's hero (see [ThemeTokens.heroTitle]), or [default]. */
@Composable
fun heroTitleColor(default: Color): Color = LocalThemeTokens.current.heroTitle ?: default

/** The line under a page title, over the hero, or [default]. */
@Composable
fun heroSubtitleColor(default: Color): Color = LocalThemeTokens.current.heroSubtitle ?: default

/**
 * The soft glow behind hero text ([ThemeTokens.heroShadow]): wide for titles, tighter for the
 * smaller lines ([soft]). [fallback] when the theme has none.
 */
@Composable
fun heroTextShadow(soft: Boolean = false, fallback: androidx.compose.ui.graphics.Shadow? = null): androidx.compose.ui.graphics.Shadow? =
    LocalThemeTokens.current.heroShadow?.let {
        androidx.compose.ui.graphics.Shadow(it, Offset(0f, if (soft) 1f else 2f), if (soft) 12f else 22f)
    } ?: fallback

/** The effects in force; outside the app's theme, none. */
val LocalThemeEffects = staticCompositionLocalOf { ThemeEffects.None }

/**
 * Finishes [shape] the way the active theme finishes its surfaces: brushed-steel light across the
 * top under Doom, frost at the corners under Winter (with frost on). Never an outline. Drawn over
 * the content, with the path built once per size. Under Sonara and Vice, nothing.
 */
@Composable
fun Modifier.themeEdge(shape: Shape): Modifier {
    val tokens = LocalThemeTokens.current
    val effects = LocalThemeEffects.current
    val edge = tokens.edge
    if (edge == ThemeEdge.NONE || (edge == ThemeEdge.FROST && !effects.frost)) return this
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawWithContent {
            drawContent()
            when (edge) {
                ThemeEdge.FORGED -> {
                    // Brushed steel catching the light: brightest along the top, gone by the
                    // middle. A fill, not a line, so nothing outlines the surface.
                    drawOutline(
                        outline,
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.07f),
                            0.45f to Color.White.copy(alpha = 0.012f),
                            1f to Color.Transparent,
                        ),
                    )
                }
                ThemeEdge.FROST -> frostCorners(tokens)
                ThemeEdge.NONE -> Unit
            }
        }
    }
}

/**
 * Frost creeping in from the two top corners: a pale bloom and a few six-armed crystals, small
 * and faint, so a card reads as cold glass rather than as decorated.
 */
fun DrawScope.frostCorners(tokens: ThemeTokens, top: Float = 0f) {
    val reach = min(64.dp.toPx(), min(size.width, size.height) * 0.6f)
    for (corner in listOf(Offset(0f, top), Offset(size.width, top))) {
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), corner, reach),
            radius = reach,
            center = corner,
        )
    }
    val crystals = listOf(
        Triple(0.06f, 0.10f, 7f), Triple(0.13f, 0.05f, 4.5f), Triple(0.04f, 0.24f, 4f),
        Triple(0.94f, 0.08f, 6f), Triple(0.87f, 0.04f, 4f),
    )
    val color = tokens.accent3.copy(alpha = 0.30f)
    for ((fx, fy, rDp) in crystals) {
        // Within the corner's reach: fy is a share of it, so crystals stay at the top on tall cards.
        val c = Offset(size.width * fx, top + reach * fy * 2.5f)
        crystal(c, rDp.dp.toPx(), color)
    }
}

/** A six-armed ice crystal with a pair of barbs on each arm. */
fun DrawScope.crystal(center: Offset, radius: Float, color: Color) {
    val w = (radius / 7f).coerceAtLeast(0.6f)
    for (k in 0 until 6) {
        val a = k * Math.PI / 3
        val tip = center + Offset((cos(a) * radius).toFloat(), (sin(a) * radius).toFloat())
        drawLine(color, center, tip, w, StrokeCap.Round)
        val mid = center + Offset((cos(a) * radius * 0.55f).toFloat(), (sin(a) * radius * 0.55f).toFloat())
        for (side in listOf(-1, 1)) {
            val b = a + side * Math.PI / 4
            drawLine(color, mid, mid + Offset((cos(b) * radius * 0.3f).toFloat(), (sin(b) * radius * 0.3f).toFloat()), w * 0.8f, StrokeCap.Round)
        }
    }
}
