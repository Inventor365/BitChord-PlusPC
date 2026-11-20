package com.music.bitchord.ui.performance

import com.music.bitchord.ui.theme.ThemeEffects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WinterEffectsPolicyTest {

    private val all = ThemeEffects(aurora = true, snow = true, stars = true, glass = true, frost = true)

    @Test
    fun lowTierStartsWithStillsOnly_andTheListenerCanStillTurnThingsOn() {
        val low = WinterEffectsPolicy.chosen(WinterEffectPrefs(), DeviceTier.LOW)
        assertFalse(low.aurora || low.snow || low.stars || low.glass)
        assertTrue(low.frost)
        assertTrue(WinterEffectsPolicy.chosen(WinterEffectPrefs(aurora = true), DeviceTier.LOW).aurora)
    }

    @Test
    fun midAndHighTiersStartWithEverythingOn_andAnOffSwitchWins() {
        assertEquals(all, WinterEffectsPolicy.chosen(WinterEffectPrefs(), DeviceTier.HIGH))
        val noSnow = WinterEffectsPolicy.chosen(WinterEffectPrefs(snow = false), DeviceTier.MID)
        assertFalse(noSnow.snow)
        assertTrue(noSnow.aurora)
    }

    @Test
    fun aSwitchTurnedOffRemovesItsEffect_whateverTheMoment() {
        // The bug: "off" used to mean "the still version", which looks the same in a screenshot.
        for (moment in listOf(EffectsMoment(), EffectsMoment(savingPower = true), EffectsMoment(hidden = true))) {
            val r = WinterEffectsPolicy.resolve(all.copy(aurora = false, stars = false), moment)
            assertFalse("$moment", r.aurora)
            assertFalse("$moment", r.stars)
            assertTrue("$moment", r.snow)
        }
    }

    @Test
    fun savingPowerAnimationsOffOrHidden_holdsEverythingStill_butRemovesNothing() {
        for (moment in listOf(
            EffectsMoment(modeActive = true),
            EffectsMoment(savingPower = true),
            EffectsMoment(animationsOff = true),
            EffectsMoment(hidden = true),
        )) {
            val r = WinterEffectsPolicy.resolve(all, moment)
            assertFalse("$moment", r.moving)
            assertFalse("$moment", r.anyAnimated)
            assertTrue("still shown: $moment", r.aurora && r.stars && r.snow && r.frost)
        }
    }

    @Test
    fun onlySavingPowerTakesTheGlassBlurAway() {
        assertTrue(WinterEffectsPolicy.resolve(all, EffectsMoment(hidden = true)).glass)
        assertTrue(WinterEffectsPolicy.resolve(all, EffectsMoment(modeActive = true)).glass)
        assertFalse(WinterEffectsPolicy.resolve(all, EffectsMoment(savingPower = true)).glass)
    }

    @Test
    fun nothingPaused_meansTheChoiceRunsAndMoves() {
        val chosen = ThemeEffects(aurora = true, snow = false, stars = true, glass = true, frost = false)
        val r = WinterEffectsPolicy.resolve(chosen, EffectsMoment())
        assertEquals(chosen.copy(moving = true), r)
        assertTrue(r.anyAnimated)
        // Nothing that moves is on: nothing ticks.
        assertFalse(WinterEffectsPolicy.resolve(ThemeEffects(glass = true, frost = true), EffectsMoment()).anyAnimated)
    }

    @Test
    fun lightModeStaysLightAllDay_unlessNightSkyAfterSunsetIsOn() {
        val noon = 12 * 60
        val night = 22 * 60
        val dawn = 5 * 60 + 59
        assertFalse(WinterScenePolicy.night(appIsDark = false, nightSkyAfterSunset = false, minuteOfDay = night))
        assertTrue(WinterScenePolicy.night(appIsDark = false, nightSkyAfterSunset = true, minuteOfDay = night))
        assertTrue(WinterScenePolicy.night(appIsDark = false, nightSkyAfterSunset = true, minuteOfDay = dawn))
        assertFalse(WinterScenePolicy.night(appIsDark = false, nightSkyAfterSunset = true, minuteOfDay = noon))
        assertTrue(WinterScenePolicy.night(appIsDark = true, nightSkyAfterSunset = false, minuteOfDay = noon))
    }

    @Test
    fun theSunCrossesFromLeftAtSunriseToRightAtSunset() {
        assertEquals(0f, WinterScenePolicy.sunProgress(6 * 60)!!, 1e-6f)
        assertEquals(0.5f, WinterScenePolicy.sunProgress(12 * 60)!!, 1e-6f)
        assertEquals(0.75f, WinterScenePolicy.sunProgress(15 * 60)!!, 1e-6f)
        assertNull(WinterScenePolicy.sunProgress(18 * 60))
        assertNull(WinterScenePolicy.sunProgress(3 * 60))
    }

    @Test
    fun tierRule() {
        assertEquals(DeviceTier.LOW, DeviceTier.classify(3f, lowRam = false, cores = 8, sdk = 34, maxRefreshHz = 120))
        assertEquals(DeviceTier.LOW, DeviceTier.classify(8f, lowRam = true, cores = 8, sdk = 34, maxRefreshHz = 120))
        assertEquals(DeviceTier.LOW, DeviceTier.classify(8f, lowRam = false, cores = 8, sdk = 28, maxRefreshHz = 120))
        assertEquals(DeviceTier.MID, DeviceTier.classify(6f, lowRam = false, cores = 8, sdk = 34, maxRefreshHz = 120))
        assertEquals(DeviceTier.MID, DeviceTier.classify(8f, lowRam = false, cores = 8, sdk = 34, maxRefreshHz = 60))
        assertEquals(DeviceTier.HIGH, DeviceTier.classify(8f, lowRam = false, cores = 8, sdk = 34, maxRefreshHz = 120))
    }
}
