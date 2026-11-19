package com.music.bitchord.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Draws a theme's hero behind the top of every page: its gradient, its glow and its motif,
 * faded into the page's background by the time the content starts. All vectors and
 * gradients — no bitmaps — and the paths are built once per size, so a frame of it is a few
 * dozen GPU draws. Nothing in it animates: a still page stays still.
 *
 * One instance per window; [draw] is called from the window's own background pass.
 */
class ThemeBackdropPainter {
    private var builtFor: Pair<ThemeId, Size>? = null
    private val palmLeft = Path()
    private val palmRight = Path()
    private val crestOuter = Path()
    private val crestInner = Path()
    private val chevrons = Path()

    fun draw(scope: DrawScope, tokens: ThemeTokens, background: Color) = with(scope) {
        drawRect(background)
        if (tokens.backdropGradient.isEmpty()) return@with
        val hero = heroHeight()
        // The sky: the theme's gradient, landing on the page colour at the hero's foot.
        drawRect(
            Brush.verticalGradient(tokens.backdropGradient + background, startY = 0f, endY = hero),
            size = Size(size.width, hero),
        )
        tokens.heroGlow?.let { glow ->
            val center = Offset(size.width * 0.82f, hero * 0.18f)
            val radius = size.width * 0.85f
            drawCircle(
                Brush.radialGradient(listOf(glow.copy(alpha = 0.20f), glow.copy(alpha = 0.05f), Color.Transparent), center, radius),
                radius = radius,
                center = center,
            )
        }
        val key = tokens.id to size
        if (builtFor != key) {
            build(tokens, hero)
            builtFor = key
        }
        when (tokens.motif) {
            ThemeMotif.SUNSET -> drawSunset(tokens, hero)
            ThemeMotif.CREST -> drawCrest(tokens, hero)
            ThemeMotif.NONE -> Unit
        }
        // Into the page: the motif is gone before the first row of content.
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                1f to background,
                startY = hero * 0.62f,
                endY = hero,
            ),
            topLeft = Offset(0f, hero * 0.62f),
            size = Size(size.width, hero * 0.38f + 1f),
        )
    }

    private fun DrawScope.heroHeight(): Float = min(HERO_HEIGHT.toPx(), size.height * 0.5f)

    private fun DrawScope.build(tokens: ThemeTokens, hero: Float) {
        palmLeft.reset(); palmRight.reset(); crestOuter.reset(); crestInner.reset(); chevrons.reset()
        when (tokens.motif) {
            ThemeMotif.SUNSET -> {
                val horizon = hero * HORIZON
                palm(palmLeft, baseX = size.width * 0.07f, baseY = horizon, height = hero * 0.46f, lean = 1f)
                palm(palmRight, baseX = size.width * 0.95f, baseY = horizon, height = hero * 0.36f, lean = -1f)
            }
            ThemeMotif.CREST -> {
                val cx = size.width / 2f
                val cy = hero * 0.40f
                chamferedPlate(crestOuter, cx, cy, size.width * 0.66f, hero * 0.56f, cut = 0.22f)
                chamferedPlate(crestInner, cx, cy, size.width * 0.66f - 22.dp.toPx(), hero * 0.56f - 22.dp.toPx(), cut = 0.22f)
                // Three engraved chevrons pointing down the plate.
                for (i in 0 until 3) {
                    val top = cy - hero * 0.10f + i * hero * 0.07f
                    val half = size.width * (0.17f - i * 0.035f)
                    chevrons.moveTo(cx - half, top)
                    chevrons.lineTo(cx, top + half * 0.42f)
                    chevrons.lineTo(cx + half, top)
                }
            }
            ThemeMotif.NONE -> Unit
        }
    }

    private fun DrawScope.drawSunset(tokens: ThemeTokens, hero: Float) {
        val horizon = hero * HORIZON
        val r = min(size.width * 0.27f, hero * 0.36f)
        val center = Offset(size.width * 0.70f, horizon)
        // The sun, banded across its lower half as the synthwave sun is.
        clipRect(bottom = horizon) {
            drawCircle(
                Brush.verticalGradient(
                    listOf(ViceColors.Sun, tokens.accent2, tokens.accent),
                    startY = horizon - r,
                    endY = horizon,
                ),
                radius = r,
                center = center,
                alpha = 0.62f,
            )
            val band = tokens.backdropGradient.getOrElse(1) { tokens.ink }
            for (i in 0 until 5) {
                val gap = r * (0.035f + i * 0.018f)
                val y = horizon - r * (0.48f - i * 0.11f)
                drawRect(band, topLeft = Offset(center.x - r, y), size = Size(r * 2f, gap))
            }
        }
        // The horizon glow the sun sits on.
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, tokens.accent.copy(alpha = 0.22f), Color.Transparent),
                startY = horizon - 18.dp.toPx(),
                endY = horizon + 18.dp.toPx(),
            ),
            topLeft = Offset(0f, horizon - 18.dp.toPx()),
            size = Size(size.width, 36.dp.toPx()),
        )
        // The grid, receding to a point on the horizon.
        val floor = hero
        val stroke = 1.dp.toPx()
        val vanish = Offset(size.width * 0.5f, horizon)
        for (i in 1..7) {
            val t = i / 7f
            val y = horizon + (floor - horizon) * t * t
            drawLine(tokens.accent3.copy(alpha = 0.16f * (1f - t * 0.5f)), Offset(0f, y), Offset(size.width, y), stroke)
        }
        for (i in -6..6) {
            val x = vanish.x + i * size.width * 0.16f
            drawLine(tokens.accent.copy(alpha = 0.12f), vanish, Offset(x + (x - vanish.x) * 1.4f, floor), stroke)
        }
        // Two palms against the dusk.
        drawPath(palmLeft, ViceColors.Night.copy(alpha = 0.92f))
        drawPath(palmRight, ViceColors.Night.copy(alpha = 0.85f))
    }

    private fun DrawScope.drawCrest(tokens: ThemeTokens, hero: Float) {
        val cy = hero * 0.40f
        // Brushed metal: fine horizontal hairlines over the hero.
        val hair = 4.dp.toPx()
        var y = 0f
        while (y < hero) {
            drawLine(Color.White.copy(alpha = 0.018f), Offset(0f, y), Offset(size.width, y), 1f)
            y += hair
        }
        // The heart of the plate glows green.
        drawCircle(
            Brush.radialGradient(
                listOf(tokens.accent.copy(alpha = 0.18f), tokens.accent.copy(alpha = 0.04f), Color.Transparent),
                Offset(size.width / 2f, cy),
                size.width * 0.42f,
            ),
            radius = size.width * 0.42f,
            center = Offset(size.width / 2f, cy),
        )
        drawPath(crestOuter, tokens.accent3.copy(alpha = 0.13f), style = Stroke(1.5.dp.toPx()))
        drawPath(crestInner, tokens.accent2.copy(alpha = 0.09f), style = Stroke(1.dp.toPx()))
        drawPath(chevrons, tokens.accent2.copy(alpha = 0.10f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Square))
        // A riveted band across the foot of the plate.
        val bandY = hero * 0.80f
        val step = 26.dp.toPx()
        var x = step / 2f
        while (x < size.width) {
            drawCircle(tokens.accent3.copy(alpha = 0.12f), radius = 2.2.dp.toPx(), center = Offset(x, bandY))
            drawCircle(Color.White.copy(alpha = 0.10f), radius = 0.8.dp.toPx(), center = Offset(x - 0.6.dp.toPx(), bandY - 0.6.dp.toPx()))
            x += step
        }
    }

    private companion object {
        /** How tall the hero is: the page title and the first shelf's heading. */
        val HERO_HEIGHT = 420.dp

        /** Where the Vice horizon sits, as a share of the hero. */
        const val HORIZON = 0.56f
    }
}

/** A palm silhouette: a curving trunk and six fronds, from [baseX],[baseY] up. */
private fun palm(path: Path, baseX: Float, baseY: Float, height: Float, lean: Float) {
    val topX = baseX + lean * height * 0.22f
    val topY = baseY - height
    val w = height * 0.035f
    // Trunk.
    path.moveTo(baseX - w, baseY)
    path.quadraticTo(baseX + lean * height * 0.02f - w, baseY - height * 0.55f, topX - w * 0.6f, topY)
    path.lineTo(topX + w * 0.6f, topY)
    path.quadraticTo(baseX + lean * height * 0.02f + w, baseY - height * 0.55f, baseX + w, baseY)
    path.close()
    // Fronds: drooping leaves, each a slim curved blade.
    val fronds = listOf(-160f, -125f, -95f, -60f, -25f, 10f)
    for (angle in fronds) {
        val rad = Math.toRadians(angle.toDouble())
        val len = height * if (angle < -100f || angle > -50f) 0.42f else 0.34f
        val tipX = topX + (kotlin.math.cos(rad) * len).toFloat()
        val tipY = topY + (kotlin.math.sin(rad) * len).toFloat() + len * 0.45f
        val midX = (topX + tipX) / 2f
        val midY = minOf(topY, tipY) - len * 0.22f
        path.moveTo(topX, topY)
        path.quadraticTo(midX, midY - len * 0.06f, tipX, tipY)
        path.quadraticTo(midX, midY + len * 0.10f, topX, topY + w)
        path.close()
    }
}

/** A plate with its four corners cut off at [cut] of the smaller side. */
private fun chamferedPlate(path: Path, cx: Float, cy: Float, width: Float, height: Float, cut: Float) {
    val c = min(width, height) * cut
    val l = cx - width / 2f
    val r = cx + width / 2f
    val t = cy - height / 2f
    val b = cy + height / 2f
    path.moveTo(l + c, t)
    path.lineTo(r - c, t)
    path.lineTo(r, t + c)
    path.lineTo(r, b - c * 1.8f)
    path.lineTo(cx, b)
    path.lineTo(l, b - c * 1.8f)
    path.lineTo(l, t + c)
    path.close()
}

/**
 * A theme's fine texture over a card: dots for Sonara, a fine grid for Vice, brushed hairlines
 * and corner rivets for Doom. Faint by design; it reads as material, not pattern.
 */
fun DrawScope.drawThemeTexture(tokens: ThemeTokens) {
    when (tokens.texture) {
        ThemeTexture.DOTS -> {
            val spacing = 14.dp.toPx()
            val dot = 0.9.dp.toPx()
            var y = spacing / 2f
            while (y < size.height) {
                var x = spacing / 2f
                while (x < size.width) {
                    drawCircle(Color.White.copy(alpha = 0.045f), dot, Offset(x, y))
                    x += spacing
                }
                y += spacing
            }
        }
        ThemeTexture.GRID -> {
            val spacing = 18.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(tokens.accent3.copy(alpha = 0.06f), Offset(x, 0f), Offset(x, size.height), 1f)
                x += spacing
            }
            var y = 0f
            while (y < size.height) {
                drawLine(tokens.accent.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), 1f)
                y += spacing
            }
        }
        ThemeTexture.RIVETS -> {
            var y = 0f
            val hair = 3.dp.toPx()
            while (y < size.height) {
                drawLine(Color.White.copy(alpha = 0.025f), Offset(0f, y), Offset(size.width, y), 1f)
                y += hair
            }
            val inset = 10.dp.toPx()
            for (corner in listOf(
                Offset(inset, inset),
                Offset(size.width - inset, inset),
                Offset(inset, size.height - inset),
                Offset(size.width - inset, size.height - inset),
            )) {
                drawCircle(tokens.accent3.copy(alpha = 0.30f), 2.6.dp.toPx(), corner)
                drawCircle(Color.White.copy(alpha = 0.22f), 1.dp.toPx(), corner - Offset(0.7.dp.toPx(), 0.7.dp.toPx()))
            }
        }
    }
}
