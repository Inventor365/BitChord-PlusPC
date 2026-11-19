package com.music.bitchord.ui.performance

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Applies a [RefreshDecision] to an activity's window, and keeps an adaptive one honest.
 *
 * - **Pinned**: the display mode at that rate (same resolution) is preferred for the window,
 *   and on Android 15+ the content view asks for exactly that rate with the power-saving
 *   balance switched off.
 * - **Adaptive**: no mode is forced, so the display rests at its low rate when nothing moves.
 *   While the listener touches the screen, for a moment after a fling, and while some part of
 *   the app holds a [hold] (a page transition, the player sheet), the window votes for the
 *   boost rate. On Android 15+ the vote comes from a one-pixel view redrawn each frame with
 *   `requestedFrameRate` set, because Android 15 drops a screen that moves with no finger on it
 *   to 60 Hz unless something drawn that frame asks for more. On Android 11–14 the window's
 *   preferred rate is raised for the same span. Older versions keep the system's choice.
 *
 * Main thread only.
 */
class DisplayRefreshController(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private var decision: RefreshDecision = RefreshDecision.Adaptive(boostHz = 60, ceilingHz = 60, capped = false)
    private var contentView: View? = null
    private var fingerDown = false
    private var holds = 0
    private var boosting = false
    private var voter: View? = null
    private var touchTail = false

    private val endTouchBoost = Runnable {
        touchTail = false
        updateBoost()
    }

    /** The rates the window's display offers, rounded: 60, 90, 120… */
    fun supportedRates(): List<Int> = currentDisplay()?.supportedModes
        ?.map { it.refreshRate.roundToInt() }
        ?.distinct()
        ?.sorted()
        .orEmpty()

    fun apply(decision: RefreshDecision, contentView: View) {
        this.decision = decision
        this.contentView = contentView
        val window = activity.window
        val display = currentDisplay()
        val attrs = window.attributes
        when (decision) {
            is RefreshDecision.Pinned -> {
                attrs.preferredDisplayModeId = display.modeAt(decision.hz)?.modeId ?: 0
                attrs.preferredRefreshRate = decision.hz.toFloat()
            }
            is RefreshDecision.Adaptive -> {
                // A cap is the one case adaptive still holds a mode: the display may not rise
                // above it, and nothing lower than the lowest real mode exists to fall to.
                attrs.preferredDisplayModeId = if (decision.capped) display.modeAt(decision.ceilingHz)?.modeId ?: 0 else 0
                attrs.preferredRefreshRate = 0f
            }
        }
        window.attributes = attrs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.setFrameRatePowerSavingsBalanced(decision is RefreshDecision.Adaptive)
            contentView.requestedFrameRate = when (decision) {
                is RefreshDecision.Pinned -> decision.hz.toFloat()
                is RefreshDecision.Adaptive -> View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            }
        }
        boosting = false
        updateBoost()
    }

    /** Re-asserts the last decision, for when the window comes back to the front. */
    fun reapply() {
        val view = contentView ?: return
        apply(decision, view)
    }

    /** Called for every touch the activity dispatches. */
    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                fingerDown = true
                touchTail = false
                handler.removeCallbacks(endTouchBoost)
                updateBoost()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fingerDown = false
                // A fling keeps the list moving after the finger lifts; the boost outlasts it.
                touchTail = true
                handler.removeCallbacks(endTouchBoost)
                handler.postDelayed(endTouchBoost, TOUCH_TAIL_MS)
                updateBoost()
            }
        }
    }

    /** Asks for the boost rate until the returned function is called. */
    fun hold(): () -> Unit {
        holds++
        updateBoost()
        var released = false
        return {
            if (!released) {
                released = true
                holds = (holds - 1).coerceAtLeast(0)
                updateBoost()
            }
        }
    }

    private fun updateBoost() {
        val wanted = fingerDown || touchTail || holds > 0
        boostNow(wanted)
    }

    private fun boostNow(on: Boolean) {
        val adaptive = decision as? RefreshDecision.Adaptive
        val target = on && adaptive != null
        if (target == boosting) return
        boosting = target
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> voteFrames(if (target) adaptive!!.boostHz else null)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val attrs = activity.window.attributes
                attrs.preferredRefreshRate = if (target) adaptive!!.boostHz.toFloat() else 0f
                activity.window.attributes = attrs
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun voteFrames(hz: Int?) {
        val root = activity.window.decorView as? ViewGroup ?: return
        if (hz == null) {
            (voter as? FrameVoteView)?.stop()
            return
        }
        val view = (voter as? FrameVoteView) ?: FrameVoteView(activity).also {
            voter = it
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            root.addView(it, ViewGroup.LayoutParams(1, 1))
        }
        view.start(hz.toFloat())
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
        else (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay

    /** The display mode at [hz] with the current mode's resolution, if there is one. */
    private fun Display?.modeAt(hz: Int): Display.Mode? {
        val current = this?.mode ?: return null
        return supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .minByOrNull { abs(it.refreshRate - hz) }
            ?.takeIf { abs(it.refreshRate - hz) < 3f }
    }

    /**
     * A transparent pixel that asks for [start]'s rate in every frame it is drawn, and is
     * redrawn every frame until [stop]: Android 15 counts a view's vote only in frames that
     * view draws.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private class FrameVoteView(context: Context) : View(context) {
        private var running = false
        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                invalidate()
                postOnAnimation(this)
            }
        }

        fun start(hz: Float) {
            requestedFrameRate = hz
            if (running) return
            running = true
            postOnAnimation(tick)
        }

        fun stop() {
            running = false
            requestedFrameRate = REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
        }

        override fun onDraw(canvas: Canvas) = Unit
    }

    private companion object {
        /** How long the boost outlasts a lifted finger: long enough for a fling to settle. */
        const val TOUCH_TAIL_MS = 1_600L
    }
}
