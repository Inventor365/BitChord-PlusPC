package com.music.bitchord.ui.performance

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshRatePolicyTest {
    private val panel60x120 = listOf(60, 120)
    private val panel60x90x120 = listOf(60, 90, 120)

    @Test
    fun offersOnlyRatesTheDisplayHas() {
        assertEquals(
            listOf(RefreshRatePreference.ADAPTIVE, RefreshRatePreference.HZ_60, RefreshRatePreference.HZ_120),
            RefreshRatePolicy.options(panel60x120),
        )
        assertEquals(RefreshRatePreference.entries.toList(), RefreshRatePolicy.options(panel60x90x120))
        // 59.94/60 rounding still counts as 60.
        assertEquals(listOf(RefreshRatePreference.ADAPTIVE, RefreshRatePreference.HZ_60), RefreshRatePolicy.options(listOf(59)))
    }

    @Test
    fun adaptiveBoostsToTheTopRate() {
        assertEquals(
            RefreshDecision.Adaptive(boostHz = 120, ceilingHz = 120, capped = false),
            RefreshRatePolicy.resolve(RefreshRatePreference.ADAPTIVE, panel60x120),
        )
    }

    @Test
    fun fixedRateIsPinned_andAMissingRateFallsToTheNearest() {
        assertEquals(RefreshDecision.Pinned(60), RefreshRatePolicy.resolve(RefreshRatePreference.HZ_60, panel60x120))
        assertEquals(RefreshDecision.Pinned(120), RefreshRatePolicy.resolve(RefreshRatePreference.HZ_90, panel60x120))
        assertEquals(RefreshDecision.Pinned(90), RefreshRatePolicy.resolve(RefreshRatePreference.HZ_90, panel60x90x120))
    }

    @Test
    fun aCapLimitsEveryChoice_withoutChangingIt() {
        val cap60 = RefreshOverride(capHz = 60)
        assertEquals(RefreshDecision.Pinned(60), RefreshRatePolicy.resolve(RefreshRatePreference.HZ_120, panel60x120, cap60))
        assertEquals(
            RefreshDecision.Adaptive(boostHz = 60, ceilingHz = 60, capped = true),
            RefreshRatePolicy.resolve(RefreshRatePreference.ADAPTIVE, panel60x120, cap60),
        )
        // A 90 cap on a 60/90/120 panel lands on 90.
        assertEquals(RefreshDecision.Pinned(90), RefreshRatePolicy.resolve(RefreshRatePreference.HZ_120, panel60x90x120, RefreshOverride(capHz = 90)))
    }

    @Test
    fun forceMaxBoostsToTheTopAllowedRate_withALongTail_andTheCapWins() {
        // Over any fixed choice: the top rate whenever anything moves, never pinned while still.
        assertEquals(
            RefreshDecision.Adaptive(boostHz = 120, ceilingHz = 120, capped = false, longTail = true),
            RefreshRatePolicy.resolve(RefreshRatePreference.HZ_60, panel60x120, RefreshOverride(forceMax = true)),
        )
        assertEquals(
            RefreshDecision.Adaptive(boostHz = 60, ceilingHz = 60, capped = true, longTail = true),
            RefreshRatePolicy.resolve(RefreshRatePreference.ADAPTIVE, panel60x120, RefreshOverride(capHz = 60, forceMax = true)),
        )
    }

    @Test
    fun noReportedRatesFallsBackTo60() {
        assertEquals(RefreshDecision.Adaptive(60, 60, false), RefreshRatePolicy.resolve(RefreshRatePreference.ADAPTIVE, emptyList()))
    }
}
