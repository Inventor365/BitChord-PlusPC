package com.music.bitchord.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The app's motion, in one place: how things answer a touch, how they arrive, and the glow
 * a page can wear at its top. Kept small on purpose — three gestures used everywhere read as
 * a designed app; a different flourish on every screen reads as a demo.
 */
object Motion {
    /** Out fast, settle slow: what a thing entering the screen uses. */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val REVEAL_MS = 420
    const val STAGGER_MS = 45L

    /** Past this many, items arriving together stop queueing up behind one another. */
    const val STAGGER_CAP = 8

    // ---- Navigation: one set of numbers for every page and tab change ----

    /** Arrivals: out of the gate fast, then a long settle. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Departures: gather, then go — so what leaves is gone before it competes with what arrives. */
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** A tab change: short, because the tab bar has already said where you are going. */
    const val TAB_ENTER_MS = 260

    /** A page pushed or popped: a little longer, it is a change of place. */
    const val PAGE_ENTER_MS = 320

    /** How long what is leaving takes to fade: well under the arrival, so the two never mush. */
    const val EXIT_MS = 90

    /** The arrival waits this long, so the departure has mostly cleared. */
    const val ENTER_DELAY_MS = 60

    /** How far a tab's page travels, as a share of the width: says which way, sweeps nothing. */
    const val TAB_SHIFT = 0.08f

    /** How far a pushed page travels in. */
    const val PAGE_SHIFT = 0.12f

    /** With Reduce motion on: a plain, quick crossfade and no travel. */
    const val REDUCED_MS = 120
}

/**
 * Gives under the finger and springs back.
 *
 * Watches the press without consuming it, so it sits on top of whatever click, long-press or
 * drag handling the element already has and changes none of it.
 */
fun Modifier.pressScale(pressed: Float = 0.96f): Modifier = composed {
    var down by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (down) pressed else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "press-scale",
    )
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                down = true
                waitForUpOrCancellation()
                down = false
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

/**
 * Fades and rises into place the first time it is composed, a beat after the item before it.
 * [order] is the item's place in its group; a long list stops staggering after
 * [Motion.STAGGER_CAP] so the last row is not still arriving a second later.
 */
fun Modifier.revealOnAppear(order: Int = 0, rise: Dp = 16.dp): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(order.coerceIn(0, Motion.STAGGER_CAP) * Motion.STAGGER_MS)
        progress.animateTo(1f, tween(Motion.REVEAL_MS, easing = Motion.Emphasized))
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * rise.toPx()
    }
}

/**
 * A soft wash of [color] hanging from the top edge and gone by [depth] — the light a page's
 * artwork would throw on the wall behind it.
 */
fun Modifier.topGlow(color: Color, depth: Dp = 320.dp, strength: Float = 0.34f): Modifier = drawBehind {
    val reach = depth.toPx()
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = strength), color.copy(alpha = strength * 0.4f), Color.Transparent),
            center = Offset(size.width / 2f, 0f),
            radius = reach,
        ),
        size = androidx.compose.ui.geometry.Size(size.width, reach.coerceAtMost(size.height)),
    )
}

/** A colour that is the same every time for the same [seed] — a playlist's own, without reading its artwork. */
fun accentFor(seed: String): Color = ACCENTS[Math.floorMod(seed.hashCode(), ACCENTS.size)]

private val ACCENTS = listOf(
    Color(0xFF7C9CFF), Color(0xFF5FD4A6), Color(0xFFFFB86B),
    Color(0xFFFF8FB1), Color(0xFFB79CFF), Color(0xFF6FD3F2),
)
