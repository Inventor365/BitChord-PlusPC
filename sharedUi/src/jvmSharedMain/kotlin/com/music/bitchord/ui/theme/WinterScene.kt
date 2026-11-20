package com.music.bitchord.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.imageResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The aurora as a runtime shader where the platform has one (Android 13+); null otherwise, and
 * the pre-rendered frames are crossfaded instead. One instance; [brush] updates it in place.
 */
expect class AuroraShader() {
    val supported: Boolean
    fun brush(width: Float, height: Float, timeSec: Float, day: Boolean): Brush?
}

/**
 * The aurora, shaded once a frame into one layer: the scene draws it, and the tab bar and mini
 * player draw the same layer behind their glass ([winterBarGlow]) — sampled, not shaded again.
 */
val LocalAuroraLayer = staticCompositionLocalOf<GraphicsLayer?> { null }

/** The clock the Winter scene runs on, for the bars too; null outside Winter. */
val LocalWinterTime = staticCompositionLocalOf<State<Float>?> { null }

/**
 * Remembers the one aurora layer the window shares; see [LocalAuroraLayer]. Offscreen, so it is a
 * real texture: shaded at its own (quarter) size only when it changes, then scaled up by the
 * scene and sampled by the bars. Left as a plain display list, it replayed the shader at full
 * screen size every frame, and again for each bar — measured, nearly all of Winter's frame cost.
 */
@Composable
fun rememberAuroraLayer(): GraphicsLayer = rememberGraphicsLayer().apply {
    compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
}

/**
 * The seconds the scene animates by, advanced about thirty times a second while [running] and
 * frozen otherwise. Read only in draw lambdas, so a tick redraws the scene's own layers and
 * recomposes nothing.
 */
@Composable
fun rememberWinterTime(running: Boolean): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = 0L
        var origin = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (origin == 0L) origin = now - (time.floatValue * 1e9f).toLong()
                // A slow sky needs no more than ~30 fps, which also lets a 120 Hz screen rest.
                if (now - last >= FRAME_GAP_NS) {
                    time.floatValue = (now - origin) / 1e9f
                    last = now
                }
            }
        }
    }
    return time
}

private const val FRAME_GAP_NS = 33_000_000L

/** The scene's painted plates for one mode; see scripts/winter/render_backplates.py. */
@Immutable
class WinterArt(val sky: ImageBitmap, val land: ImageBitmap)

@Composable
fun rememberWinterArt(night: Boolean): WinterArt {
    val sky = imageResource(if (night) Res.drawable.winter_night_sky else Res.drawable.winter_day_sky)
    val land = imageResource(if (night) Res.drawable.winter_night_land else Res.drawable.winter_day_land)
    return remember(sky, land) { WinterArt(sky, land) }
}

/** Draws [art] across the top of the scope at the plates' own aspect (the theme previews use it). */
fun DrawScope.drawWinterArt(art: WinterArt) {
    val h = size.width * art.sky.height / art.sky.width
    drawImage(art.sky, dstSize = IntSize(size.width.toInt(), h.toInt()), srcSize = IntSize(art.sky.width, art.sky.height))
    drawImage(art.land, dstSize = IntSize(size.width.toInt(), h.toInt()), srcSize = IntSize(art.land.width, art.land.height))
}

/**
 * Winter's sky over the top of every page, under the content: a film still rather than a
 * drawing. Back to front —
 *
 * 1. the sky plate (Milky Way, thousands of faint stars, airglow);
 * 2. the bright stars, twinkling, in varied size, brightness and colour ([effects].stars);
 * 3. a shooting star every 8–20 s, never two at once (stars, while moving);
 * 4. the aurora — the shader on Android 13+, pre-rendered frames below — vivid at night, faint by
 *    day ([effects].aurora);
 * 5. the sun, by day: its place from the time ([sun], 0 sunrise .. 1 sunset), with bloom;
 * 6. the landscape plate: glacier peaks, ice field, haze, two emperor penguins far out;
 * 7. fine soft snow drifting in front ([effects].snow, while moving).
 *
 * Each moving part is its own canvas in its own layer, so a tick redraws only it. Anything
 * switched off isn't drawn at all; with [ThemeEffects.moving] false, what's shown holds still.
 */
@Composable
fun WinterScene(
    night: Boolean,
    effects: ThemeEffects,
    time: State<Float>,
    auroraLayer: GraphicsLayer?,
    shader: AuroraShader,
    snowScale: Float,
    sun: Float?,
    /** The page colour the scene's foot melts into. */
    background: Color,
    modifier: Modifier = Modifier,
) {
    val art = rememberWinterArt(night)
    val density = LocalDensity.current
    // At the plates' own aspect, so the landscape's fade-out always finishes inside the scene: a
    // fixed height cropped it part-way and left a hard edge across the page.
    Box(modifier.fillMaxWidth().aspectRatio(PLATE_ASPECT)) {
        Image(art.sky, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)

        if (night && effects.stars) {
            val stars = remember { LiveStars() }
            val shooting = remember { ShootingStars() }
            Canvas(Modifier.fillMaxSize().graphicsLayer()) {
                stars.ensure(size.width, size.height * SKY_SHARE)
                val t = time.value
                stars.draw(this, t, twinkle = effects.moving)
                if (effects.moving) shooting.draw(this, t)
            }
        }

        if (effects.aurora) {
            if (shader.supported && auroraLayer != null) {
                val shaded = remember { floatArrayOf(-1f, -1f) }
                Canvas(Modifier.fillMaxSize().graphicsLayer()) {
                    // A quarter of the screen's resolution, re-shaded ~15 times a second: the
                    // aurora is soft gradients and drifts, so it looks the same at a fraction of
                    // the GPU's work. The bars sample this same layer.
                    val w = (size.width / AURORA_SCALE).toInt().coerceAtLeast(1)
                    val h = (size.height / AURORA_SCALE).toInt().coerceAtLeast(1)
                    val step = (time.value * AURORA_FPS).toInt().toFloat()
                    val mode = if (night) 0f else 1f
                    if (step != shaded[0] || mode != shaded[1] || auroraLayer.size.width != w) {
                        val brush = shader.brush(w.toFloat(), h.toFloat(), step / AURORA_FPS + AURORA_EPOCH, day = !night)
                        if (brush != null) auroraLayer.record(density, layoutDirection, IntSize(w, h)) { drawRect(brush) }
                        shaded[0] = step
                        shaded[1] = mode
                    }
                    scale(AURORA_SCALE, AURORA_SCALE, pivot = Offset.Zero) { drawLayer(auroraLayer) }
                }
            } else {
                AuroraFrames(night, time, auroraLayer)
            }
        }

        if (!night && sun != null) {
            Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawSun(sun) }
        }

        if (!night) {
            // A deeper blue toward the zenith, as a real sky has: the white page titles sit on it
            // with real contrast, where the pale sky alone washed them out.
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color(0xFF0E4A8A).copy(alpha = 0.34f),
                        0.42f to Color(0xFF1A5E9E).copy(alpha = 0.16f),
                        0.62f to Color.Transparent,
                    ),
                )
            }
        }

        Image(art.land, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)

        // The foot melts into the page colour: no edge where the scene ends.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(0.78f to Color.Transparent, 1f to background))
        }

        if (effects.snow && effects.moving && snowScale > 0f) {
            val flakes = remember(snowScale) { Snow(snowScale) }
            Canvas(Modifier.fillMaxSize().graphicsLayer()) { flakes.draw(this, time.value, night) }
        }
    }
}

/** The plates' width over height (1080 x 1380). */
private const val PLATE_ASPECT = 1080f / 1380f

/** The sky's share of the scene's height: where the stars and shooting stars may be. */
private const val SKY_SHARE = 0.62f
private const val AURORA_SCALE = 4f
private const val AURORA_FPS = 15f

/** Starts the aurora's clock part-way through its pattern, on a good-looking curtain. */
private const val AURORA_EPOCH = 37f

/**
 * Below Android 13: four pre-rendered aurora frames, crossfaded every few seconds and drifted
 * slowly sideways, so the sky still moves without a runtime shader. Recorded into [layer] too, so
 * the bars can sample it the same way.
 */
@Composable
private fun AuroraFrames(night: Boolean, time: State<Float>, layer: GraphicsLayer?) {
    val frames = listOf(
        imageResource(Res.drawable.winter_aurora_0),
        imageResource(Res.drawable.winter_aurora_1),
        imageResource(Res.drawable.winter_aurora_2),
        imageResource(Res.drawable.winter_aurora_3),
    )
    val density = LocalDensity.current
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = if (night) 1f else 0.32f }) {
        val t = time.value / FRAME_HOLD_S
        val i = t.toInt()
        val blend = t - i
        val a = frames[i % frames.size]
        val b = frames[(i + 1) % frames.size]
        val drift = (sin(time.value * 0.02f) * size.width * 0.04f).toInt()
        val paint: DrawScope.() -> Unit = {
            drawImage(a, dstOffset = IntOffset(drift, 0), dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = 1f - blend)
            drawImage(b, dstOffset = IntOffset(drift, 0), dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = blend)
        }
        if (layer != null) {
            layer.record(density, layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) { paint() }
            drawLayer(layer)
        } else {
            paint()
        }
    }
}

private const val FRAME_HOLD_S = 7f

/**
 * The bright stars: positions, sizes, brightness, colour temperature and twinkle phase in
 * pre-allocated arrays, filled once per size. The plate behind already carries the faint ones.
 */
private class LiveStars {
    private var w = -1f
    private val x = FloatArray(COUNT)
    private val y = FloatArray(COUNT)
    private val r = FloatArray(COUNT)
    private val b = FloatArray(COUNT)
    private val phase = FloatArray(COUNT)
    private val speed = FloatArray(COUNT)
    private val tint = IntArray(COUNT)

    fun ensure(width: Float, skyHeight: Float) {
        if (width == w) return
        w = width
        val rnd = java.util.Random(4103)
        for (i in 0 until COUNT) {
            x[i] = rnd.nextFloat() * width
            y[i] = rnd.nextFloat() * skyHeight
            // The sky's prominent stars (the plate behind holds only the faint ones): most
            // modest, a few large and bright, as the sky has them.
            val m = rnd.nextFloat()
            b[i] = 0.55f + 0.45f * m
            r[i] = 0.75f + 1.35f * m * m
            phase[i] = rnd.nextFloat() * 6.283f
            speed[i] = 0.8f + rnd.nextFloat() * 2.4f
            tint[i] = rnd.nextInt(TINTS.size)
        }
    }

    fun draw(scope: DrawScope, t: Float, twinkle: Boolean) = with(scope) {
        val dp = density
        for (i in 0 until COUNT) {
            val tw = if (twinkle) 0.72f + 0.28f * sin(t * speed[i] + phase[i]) else 1f
            val c = TINTS[tint[i]]
            val radius = r[i] * dp
            drawCircle(c.copy(alpha = (b[i] * tw).coerceIn(0f, 1f)), radius, Offset(x[i], y[i]))
            if (b[i] > 0.78f) drawCircle(c.copy(alpha = 0.16f * tw), radius * 3.4f, Offset(x[i], y[i]))
        }
    }

    companion object {
        const val COUNT = 95
        val TINTS = arrayOf(Color(0xFFAFC6FF), Color(0xFFD8E3FF), Color(0xFFF7F8FF), Color(0xFFFFF0D9), Color(0xFFFFD3A3))
    }
}

/**
 * Shooting stars on a fixed, seeded schedule: one every 8–20 s, never two at once, each a thin
 * bright streak with a fading tail across the top third at its own angle. A pure function of time.
 */
private class ShootingStars {
    private val start = FloatArray(EVENTS)
    private val sx = FloatArray(EVENTS)
    private val sy = FloatArray(EVENTS)
    private val angle = FloatArray(EVENTS)
    private val length = FloatArray(EVENTS)
    private val duration = FloatArray(EVENTS)
    private val period: Float

    init {
        val rnd = java.util.Random(77)
        var at = 4f
        for (i in 0 until EVENTS) {
            start[i] = at
            sx[i] = 0.08f + rnd.nextFloat() * 0.84f
            sy[i] = 0.03f + rnd.nextFloat() * 0.22f
            val tilt = (18f + rnd.nextFloat() * 22f) * (PI.toFloat() / 180f)
            angle[i] = if (rnd.nextBoolean()) tilt else PI.toFloat() - tilt
            length[i] = 0.18f + rnd.nextFloat() * 0.16f
            duration[i] = 0.7f + rnd.nextFloat() * 0.45f
            at += 8f + rnd.nextFloat() * 12f
        }
        period = at
    }

    fun draw(scope: DrawScope, t: Float) = with(scope) {
        val local = t % period
        for (i in 0 until EVENTS) {
            val p = (local - start[i]) / duration[i]
            if (p < 0f || p > 1f) continue
            val dir = Offset(cos(angle[i]), sin(angle[i]))
            val len = length[i] * size.width
            val origin = Offset(sx[i] * size.width, sy[i] * size.height)
            val eased = 1f - (1f - p) * (1f - p)
            val head = origin + dir * (len * eased)
            val tail = head - dir * (len * 0.45f * (1f - p * 0.5f))
            val fade = sin(p * PI.toFloat())
            drawLine(
                Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.9f * fade)), tail, head),
                tail,
                head,
                strokeWidth = 1.3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(Color.White.copy(alpha = 0.9f * fade), 1.4.dp.toPx(), head)
            drawCircle(Color(0xFFCFE6FF).copy(alpha = 0.18f * fade), 4.5.dp.toPx(), head)
            return@with
        }
    }

    companion object {
        const val EVENTS = 64
    }
}

/**
 * Fine snow in front of the scene: sparse, small and soft — near flakes larger and more blurred,
 * far ones faint points. Each flake's place is a function of time; nothing moves in memory.
 */
private class Snow(scale: Float) {
    private val count = (MAX * scale).toInt()
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val r = FloatArray(count)
    private val fall = FloatArray(count)
    private val sway = FloatArray(count)
    private val depth = FloatArray(count)

    init {
        val rnd = java.util.Random(97)
        for (i in 0 until count) {
            depth[i] = rnd.nextFloat()
            x[i] = rnd.nextFloat()
            y[i] = rnd.nextFloat()
            r[i] = 0.5f + depth[i] * depth[i] * 1.5f
            fall[i] = 0.025f + depth[i] * 0.05f + rnd.nextFloat() * 0.01f
            sway[i] = rnd.nextFloat() * 6.283f
        }
    }

    fun draw(scope: DrawScope, t: Float, night: Boolean) = with(scope) {
        val base = if (night) Color(0xFFEAF4FF) else Color.White
        for (i in 0 until count) {
            val py = ((y[i] + fall[i] * t) % 1f) * size.height
            val px = x[i] * size.width + sin(t * (0.35f + depth[i] * 0.3f) + sway[i]) * (4f + depth[i] * 8f) * density
            val fade = (1f - py / size.height).coerceIn(0f, 1f)
            val radius = r[i] * density
            val a = (0.25f + 0.45f * depth[i]) * fade
            drawCircle(base.copy(alpha = a), radius, Offset(px, py))
            // A soft halo: near flakes look out of focus.
            drawCircle(base.copy(alpha = a * 0.22f), radius * (1.8f + depth[i] * 1.6f), Offset(px, py))
        }
    }

    companion object {
        const val MAX = 70
    }
}

/**
 * The sun by day, at [progress] along its arc (0 sunrise at the left, 1 sunset at the right):
 * the sky brightened around it, a soft lens bloom, a small disc, and toward either end of the day
 * a warm glow low over the horizon. Behind the landscape, so it rises and sets behind the peaks.
 */
private fun DrawScope.drawSun(progress: Float) {
    val w = size.width
    val h = size.height
    val horizon = h * 0.66f
    val centre = Offset(w * (0.04f + 0.92f * progress), horizon - sin(progress * PI.toFloat()) * h * 0.50f)
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.42f), Color.Transparent), centre, w * 0.62f),
        w * 0.62f,
        centre,
    )
    val bloom = 86.dp.toPx()
    drawCircle(
        Brush.radialGradient(
            0f to Color(0xFFFFFBEF).copy(alpha = 0.95f),
            0.18f to Color(0xFFFFF1CF).copy(alpha = 0.55f),
            1f to Color.Transparent,
            center = centre,
            radius = bloom,
        ),
        bloom,
        centre,
    )
    drawCircle(Color(0xFFFFFDF6), 9.dp.toPx(), centre)
    val warmth = maxOf(((progress - 0.72f) / 0.28f), ((0.18f - progress) / 0.18f)).coerceIn(0f, 1f)
    if (warmth > 0f) {
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.55f to Color(0xFFFFC08A).copy(alpha = 0.10f * warmth),
                1f to Color(0xFFFFA866).copy(alpha = 0.30f * warmth),
                startY = h * 0.30f,
                endY = horizon,
            ),
        )
    }
}

/**
 * Winter's touch on a glass bar. By night the live aurora glows through it — the shared aurora
 * layer, sampled (squeezed into the bar, faint), not shaded a second time — with a thin specular
 * line along the top. By day the bar is frosted ice: a bright cool-white wash over the blur, so
 * dark cards scrolling under it never turn it muddy grey, and a crisp highlight. With the glass
 * effect off, nothing: the bar is a plain tint then.
 */
@Composable
fun Modifier.winterBarGlow(shape: Shape): Modifier {
    val tokens = LocalThemeTokens.current
    if (!tokens.animated) return this
    val effects = LocalThemeEffects.current
    if (!effects.glass) return this
    val night = tokens.motif == ThemeMotif.AURORA
    val aurora = LocalAuroraLayer.current.takeIf { night && effects.aurora }
    val glow = if (aurora != null) rememberGraphicsLayer() else null
    return drawWithContent {
        val outline = shape.createOutline(size, layoutDirection, this)
        if (night) {
            if (aurora != null && glow != null && aurora.size.width > 0) {
                // A child layer that only references the aurora layer's node: the aurora shades
                // once and both bars show it live. Alpha is applied per draw (no offscreen buffer).
                glow.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.ModulateAlpha
                glow.alpha = BAR_AURORA_ALPHA
                glow.record(this, layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) {
                    clipPath(outline.asPath()) {
                        // The band where the curtains are, from 25% to 70% of the aurora's height.
                        val sx = size.width / aurora.size.width
                        val sy = size.height / (aurora.size.height * 0.45f)
                        translate(top = -aurora.size.height * 0.25f * sy) {
                            scale(sx, sy, pivot = Offset.Zero) { drawLayer(aurora) }
                        }
                    }
                }
                drawLayer(glow)
            }
        } else {
            drawOutline(
                outline,
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.66f),
                    1f to Color(0xFFE6F2FB).copy(alpha = 0.58f),
                ),
            )
        }
        drawContent()
        // The specular edge: brightest at the middle of the top, fading to the ends.
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = if (night) 0.45f else 0.95f), Color.Transparent)),
            Offset(size.height * 0.5f, 0.75f.dp.toPx()),
            Offset(size.width - size.height * 0.5f, 0.75f.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private const val BAR_AURORA_ALPHA = 0.42f

private fun androidx.compose.ui.graphics.Outline.asPath(): Path = when (this) {
    is androidx.compose.ui.graphics.Outline.Generic -> path
    is androidx.compose.ui.graphics.Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is androidx.compose.ui.graphics.Outline.Rectangle -> Path().apply { addRect(rect) }
}
