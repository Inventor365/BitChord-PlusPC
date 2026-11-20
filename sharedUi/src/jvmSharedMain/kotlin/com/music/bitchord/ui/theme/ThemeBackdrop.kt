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
    private var builtFor: Triple<ThemeId, ThemeMotif, Size>? = null
    private val palmLeft = Path()
    private val palmRight = Path()
    private val crestInner = Path()
    private val chevrons = Path()

    /**
     * @param effects the theme's effects in force (Winter's frost edges, here).
     * @param art Winter's painted plates, for the places that show Winter without its live scene
     *   (the theme picker's previews, the welcome screen); the window's floor passes none and
     *   draws the live WinterScene over the plain page colour instead.
     */
    fun draw(
        scope: DrawScope,
        tokens: ThemeTokens,
        background: Color,
        effects: ThemeEffects = ThemeEffects.None,
        art: WinterArt? = null,
    ) = with(scope) {
        drawRect(background)
        if (tokens.motif == ThemeMotif.AURORA || tokens.motif == ThemeMotif.GLACIER) {
            // Winter's sky is a painted plate and live layers (WinterScene), not vectors: the
            // plate's foot fades out, so the page colour carries on beneath with no seam.
            art?.let { drawWinterArt(it) }
            if (tokens.edge == ThemeEdge.FROST && effects.frost) frostCorners(tokens, top = STATUS_BAR_CLEARANCE.toPx())
            return@with
        }
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
        val key = Triple(tokens.id, tokens.motif, size)
        if (builtFor != key) {
            build(tokens, hero)
            builtFor = key
        }
        when (tokens.motif) {
            ThemeMotif.SUNSET -> drawSunset(tokens, hero)
            ThemeMotif.CREST -> drawCrest(tokens, hero)
            ThemeMotif.AURORA, ThemeMotif.GLACIER, ThemeMotif.NONE -> Unit
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
        palmLeft.reset(); palmRight.reset(); crestInner.reset(); chevrons.reset()
        when (tokens.motif) {
            ThemeMotif.SUNSET -> {
                val horizon = hero * HORIZON
                palm(palmLeft, baseX = size.width * 0.07f, baseY = horizon, height = hero * 0.46f, lean = 1f)
                palm(palmRight, baseX = size.width * 0.95f, baseY = horizon, height = hero * 0.36f, lean = -1f)
            }
            ThemeMotif.CREST -> {
                // The arcane dial: tick marks between two engraved rings, long every sixth.
                val c = Offset(size.width / 2f, hero * DIAL_Y)
                val inner = size.width * 0.30f
                val outer = size.width * 0.36f
                for (k in 0 until 72) {
                    val a = k * Math.PI * 2 / 72
                    val long = k % 6 == 0
                    val from = if (long) inner - 4.dp.toPx() else inner + (outer - inner) * 0.45f
                    chevrons.moveTo(c.x + (kotlin.math.cos(a) * from).toFloat(), c.y + (kotlin.math.sin(a) * from).toFloat())
                    chevrons.lineTo(c.x + (kotlin.math.cos(a) * outer).toFloat(), c.y + (kotlin.math.sin(a) * outer).toFloat())
                }
                // Four small diamonds on the outer ring, at the quarters.
                for (k in 0 until 4) {
                    val a = k * Math.PI / 2
                    val p = Offset(c.x + (kotlin.math.cos(a) * outer).toFloat(), c.y + (kotlin.math.sin(a) * outer).toFloat())
                    val d = 5.dp.toPx()
                    crestInner.moveTo(p.x, p.y - d); crestInner.lineTo(p.x + d * 0.6f, p.y)
                    crestInner.lineTo(p.x, p.y + d); crestInner.lineTo(p.x - d * 0.6f, p.y); crestInner.close()
                }
            }
            // Winter is painted plates, drawn above.
            ThemeMotif.AURORA, ThemeMotif.GLACIER -> Unit
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
        val c = Offset(size.width / 2f, hero * DIAL_Y)
        // Forged iron: fine horizontal hairlines over the hero.
        val hair = 4.dp.toPx()
        var y = 0f
        while (y < hero) {
            drawLine(Color.White.copy(alpha = 0.02f), Offset(0f, y), Offset(size.width, y), 1f)
            y += hair
        }
        // The arcane glow behind the header: emerald, strongest at the dial's heart.
        val glow = size.width * 0.62f
        drawCircle(
            Brush.radialGradient(
                listOf(tokens.accent.copy(alpha = 0.30f), tokens.accent.copy(alpha = 0.10f), tokens.accent.copy(alpha = 0.03f), Color.Transparent),
                c,
                glow,
            ),
            radius = glow,
            center = c,
        )
        // The dial: two engraved rings, its ticks, and brass diamonds at the quarters.
        val inner = size.width * 0.30f
        val outer = size.width * 0.36f
        drawCircle(tokens.accent.copy(alpha = 0.16f), inner, c, style = Stroke(1.dp.toPx()))
        drawCircle(tokens.accent.copy(alpha = 0.22f), outer, c, style = Stroke(1.5.dp.toPx()))
        drawCircle(tokens.accent3.copy(alpha = 0.06f), outer + 6.dp.toPx(), c, style = Stroke(1.dp.toPx()))
        drawPath(chevrons, tokens.accent.copy(alpha = 0.14f), style = Stroke(1.dp.toPx(), cap = StrokeCap.Square))
        drawPath(crestInner, tokens.accent2.copy(alpha = 0.32f))
        // An armour seam across the foot of the header: a cut, its lit lip, and rivets along it.
        val seam = hero * 0.80f
        drawLine(Color.Black.copy(alpha = 0.45f), Offset(0f, seam), Offset(size.width, seam), 1.5.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, seam + 1.5.dp.toPx()), Offset(size.width, seam + 1.5.dp.toPx()), 1.dp.toPx())
        val step = 26.dp.toPx()
        var x = step / 2f
        while (x < size.width) {
            val r = Offset(x, seam - 7.dp.toPx())
            drawCircle(Color.Black.copy(alpha = 0.40f), radius = 2.6.dp.toPx(), center = r + Offset(0f, 0.6.dp.toPx()))
            drawCircle(tokens.accent3.copy(alpha = 0.22f), radius = 2.2.dp.toPx(), center = r)
            drawCircle(Color.White.copy(alpha = 0.16f), radius = 0.8.dp.toPx(), center = r - Offset(0.6.dp.toPx(), 0.6.dp.toPx()))
            x += step
        }
    }

    private companion object {
        /** How tall the hero is: the page title and the first shelf's heading. */
        val HERO_HEIGHT = 420.dp

        /** Where the Vice horizon sits, as a share of the hero. */
        const val HORIZON = 0.56f

        /** Where Doom's dial is centred, as a share of the hero. */
        const val DIAL_Y = 0.30f

        /** Clears a typical status bar, for decoration that must not sit beside its icons. */
        val STATUS_BAR_CLEARANCE = 52.dp
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

/**
 * A theme's fine texture over a card: dots for Sonara, a fine grid for Vice, brushed hairlines
 * and corner rivets for Doom, frost for Winter (only with its Frost edges switch on, [effects]).
 * Faint by design; it reads as material, not pattern.
 */
fun DrawScope.drawThemeTexture(tokens: ThemeTokens, effects: ThemeEffects = ThemeEffects.None) {
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
        ThemeTexture.FROST -> if (effects.frost) frostCorners(tokens)
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

