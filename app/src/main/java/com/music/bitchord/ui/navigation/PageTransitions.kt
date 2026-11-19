package com.music.bitchord.ui.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import com.music.bitchord.ui.components.Motion

/**
 * The app's page and tab transitions, built from [Motion]'s tokens so every change of place
 * moves the same way. All are interruptible: a new target mid-flight retargets from where the
 * pages are, it never queues behind the running animation. The system's animator duration
 * scale applies to them as to every Compose animation (zero makes them instant).
 *
 * What leaves fades linearly, so its alpha falls from the first frame: an easing that starts
 * slow keeps the old page nearly opaque while the new one arrives, and the two read as one
 * smeared page for a frame.
 */
object PageTransitions {

    /** No movement at all: the Library grid swapping with its own row. */
    val None: ContentTransform = EnterTransition.None togetherWith ExitTransition.None

    /**
     * A tab change: the new page eases in from the side the tab bar moved towards while the
     * old one clears quickly the other way — a fade-through with direction.
     */
    fun tab(forward: Boolean, reduced: Boolean): ContentTransform {
        if (reduced) return reducedFade()
        val dir = if (forward) 1 else -1
        return (
            slideInHorizontally(tween(Motion.TAB_ENTER_MS, easing = Motion.EmphasizedDecelerate)) { (it * Motion.TAB_SHIFT).toInt() * dir } +
                fadeIn(tween(Motion.TAB_ENTER_MS - Motion.ENTER_DELAY_MS, delayMillis = Motion.ENTER_DELAY_MS, easing = Motion.EmphasizedDecelerate))
            ) togetherWith (
            slideOutHorizontally(tween(Motion.EXIT_MS, easing = Motion.EmphasizedAccelerate)) { -(it * Motion.TAB_SHIFT / 2).toInt() * dir } +
                fadeOut(tween(Motion.EXIT_MS, easing = LinearEasing))
            )
    }

    /**
     * A page pushed ([push]) or popped: pushed pages arrive from the right and the page under
     * them steps back a little to the left; popping reverses both.
     */
    fun page(push: Boolean, reduced: Boolean): ContentTransform {
        if (reduced) return reducedFade()
        val dir = if (push) 1 else -1
        val enterSpec = tween<IntOffset>(Motion.PAGE_ENTER_MS, easing = Motion.EmphasizedDecelerate)
        return (
            slideInHorizontally(enterSpec) { (it * Motion.PAGE_SHIFT).toInt() * dir } +
                fadeIn(tween(Motion.PAGE_ENTER_MS - Motion.ENTER_DELAY_MS, delayMillis = Motion.ENTER_DELAY_MS, easing = Motion.EmphasizedDecelerate))
            ) togetherWith (
            slideOutHorizontally(tween(Motion.EXIT_MS * 2, easing = Motion.EmphasizedAccelerate)) { -(it * Motion.PAGE_SHIFT / 3).toInt() * dir } +
                fadeOut(tween(Motion.EXIT_MS, easing = LinearEasing))
            )
    }

    private fun reducedFade(): ContentTransform =
        fadeIn(tween(Motion.REDUCED_MS)) togetherWith fadeOut(tween(Motion.REDUCED_MS))
}
