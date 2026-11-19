package com.music.bitchord.ui.performance

import kotlin.math.abs

/**
 * The listener's refresh-rate choice. [ADAPTIVE] (the default) lets the display rest at its
 * low rate while nothing moves and asks for the top rate while the screen is scrolled, swiped
 * or animated; the others hold one rate.
 */
enum class RefreshRatePreference(val hz: Int?) {
    ADAPTIVE(null),
    HZ_60(60),
    HZ_90(90),
    HZ_120(120),
    ;

    companion object {
        fun fromKey(key: String?): RefreshRatePreference = entries.firstOrNull { it.name == key } ?: ADAPTIVE
    }
}

/**
 * What a mode lays over the listener's choice, without touching it: Low Power Mode caps the
 * rate, High Performance holds the top one. [None] leaves the choice as it is.
 */
data class RefreshOverride(
    /** The highest rate allowed, or null for no cap. Wins over [forceMax]. */
    val capHz: Int? = null,
    /** Hold the highest allowed rate the whole time. */
    val forceMax: Boolean = false,
) {
    companion object {
        val None = RefreshOverride()
    }
}

/** What the window is asked for. */
sealed interface RefreshDecision {
    /** Hold [hz] the whole time. */
    data class Pinned(val hz: Int) : RefreshDecision

    /** Rest where the system likes, rise to [boostHz] while something moves, never above [ceilingHz]. */
    data class Adaptive(val boostHz: Int, val ceilingHz: Int, val capped: Boolean) : RefreshDecision
}

/**
 * Turns a [RefreshRatePreference], the rates the display offers and any [RefreshOverride] into
 * a [RefreshDecision]. Pure, so it is tested without a device; the platform applies the result.
 */
object RefreshRatePolicy {

    /** Rates within this many hertz of each other are the same rate (59.94 vs 60). */
    private const val SAME_RATE_HZ = 2

    /** The choices worth offering on a display with [supportedHz]: Adaptive, then each rate it really has. */
    fun options(supportedHz: List<Int>): List<RefreshRatePreference> =
        listOf(RefreshRatePreference.ADAPTIVE) + RefreshRatePreference.entries.filter { pref ->
            val hz = pref.hz ?: return@filter false
            normalize(supportedHz).any { abs(it - hz) <= SAME_RATE_HZ }
        }

    fun resolve(
        preference: RefreshRatePreference,
        supportedHz: List<Int>,
        override: RefreshOverride = RefreshOverride.None,
    ): RefreshDecision {
        val rates = normalize(supportedHz)
        val max = rates.last()
        // The cap lands on a rate the display has: the highest at or under it, else its lowest.
        val ceiling = override.capHz?.let { cap -> rates.lastOrNull { it <= cap + SAME_RATE_HZ } ?: rates.first() } ?: max
        val capped = ceiling < max
        if (override.forceMax) return RefreshDecision.Pinned(ceiling)
        val wanted = preference.hz ?: return RefreshDecision.Adaptive(boostHz = ceiling, ceilingHz = ceiling, capped = capped)
        // A saved rate this display lacks (restored on another phone, or 90 on a 60/120 panel)
        // becomes the nearest one it has, never above the ceiling.
        // A tie (90 between 60 and 120) goes up: whoever asked for 90 wanted more than 60.
        val nearest = rates.filter { it <= ceiling }.sortedDescending().minByOrNull { abs(it - wanted) } ?: rates.first()
        return RefreshDecision.Pinned(nearest)
    }

    private fun normalize(supportedHz: List<Int>): List<Int> =
        supportedHz.filter { it in 24..480 }.distinct().sorted().ifEmpty { listOf(60) }
}
