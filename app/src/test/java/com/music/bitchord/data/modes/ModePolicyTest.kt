package com.music.bitchord.data.modes

import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.ui.performance.RefreshOverride
import com.music.bitchord.ui.player.VideoOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModePolicyTest {
    private val wifi = DeviceState(metered = false, batteryPercent = 80)
    private val cellular = DeviceState(metered = true, batteryPercent = 80)

    @Test
    fun noModes_meansNoOverrides() {
        val active = ModePolicy.active(ModePreferences(), cellular)
        assertFalse(active.any)
        assertEquals(EffectivePolicy.Unrestricted, ModePolicy.resolve(ModePreferences(), active))
    }

    @Test
    fun dataSaver_cellularOnly_followsTheConnection_andTheSystemSetting() {
        val prefs = ModePreferences(dataSaver = DataSaverSetting.CELLULAR_ONLY)
        assertNull(ModePolicy.active(prefs, wifi).dataSaver)
        assertEquals(ModeTrigger.METERED, ModePolicy.active(prefs, cellular).dataSaver)
        assertEquals(ModeTrigger.SYSTEM_DATA_SAVER, ModePolicy.active(prefs, wifi.copy(systemDataSaver = true)).dataSaver)
        assertEquals(ModeTrigger.MANUAL, ModePolicy.active(prefs.copy(dataSaver = DataSaverSetting.ON), wifi).dataSaver)
    }

    @Test
    fun dataSaver_capsAudioEfficiently_notToTheMinimum_andTurnsVideoToTheBanner() {
        val prefs = ModePreferences(dataSaver = DataSaverSetting.ON)
        val policy = ModePolicy.resolve(prefs, ModePolicy.active(prefs, wifi))
        assertEquals(AudioQuality.MEDIUM, policy.audioCap)
        assertTrue(policy.video.forceOff)
        assertEquals(PrefetchPolicy.NEXT_ONLY, policy.prefetch)
        assertTrue(policy.smallArtwork && policy.updatesWifiOnly && policy.backgroundSyncWifiOnly)
        assertFalse(policy.animatedCovers)
        // Data Saver alone leaves the screen and effects as the listener set them.
        assertEquals(RefreshOverride.None, policy.refresh)
        assertEquals(EffectsLevel.USER, policy.effects)
    }

    @Test
    fun lowPower_auto_turnsOnAtTheThreshold_offOnCharge_withHysteresis() {
        val prefs = ModePreferences(lowPower = LowPowerSetting.AUTO, lowPowerBatteryThreshold = 20)
        assertNull(ModePolicy.active(prefs, wifi.copy(batteryPercent = 21)).lowPower)
        assertEquals(ModeTrigger.BATTERY_LOW, ModePolicy.active(prefs, wifi.copy(batteryPercent = 20)).lowPower)
        // Charging ends it at once.
        assertNull(ModePolicy.active(prefs, wifi.copy(batteryPercent = 15, charging = true), lowPowerWasOn = true).lowPower)
        // Recovering by a point or two isn't enough; clearly above the line is.
        assertEquals(ModeTrigger.BATTERY_LOW, ModePolicy.active(prefs, wifi.copy(batteryPercent = 23), lowPowerWasOn = true).lowPower)
        assertNull(ModePolicy.active(prefs, wifi.copy(batteryPercent = 26), lowPowerWasOn = true).lowPower)
    }

    @Test
    fun lowPower_auto_followsTheSystemBatterySaver_unlessToldNotTo_andCanBeSnoozed() {
        val prefs = ModePreferences(lowPower = LowPowerSetting.AUTO)
        val saver = wifi.copy(systemPowerSave = true)
        assertEquals(ModeTrigger.SYSTEM_POWER_SAVE, ModePolicy.active(prefs, saver).lowPower)
        assertNull(ModePolicy.active(prefs.copy(lowPowerFollowsSystem = false), saver).lowPower)
        assertNull(ModePolicy.active(prefs, saver, lowPowerSnoozed = true).lowPower)
        // A manual ON is not snoozable from the auto path: it is the listener's own choice.
        assertEquals(ModeTrigger.MANUAL, ModePolicy.active(prefs.copy(lowPower = LowPowerSetting.ON), wifi, lowPowerSnoozed = true).lowPower)
    }

    @Test
    fun lowPower_capsTheScreen_reducesEffects_andKeepsTheNextTrackForGapless() {
        val prefs = ModePreferences(lowPower = LowPowerSetting.ON)
        val policy = ModePolicy.resolve(prefs, ModePolicy.active(prefs, wifi))
        assertEquals(RefreshOverride(capHz = 60), policy.refresh)
        assertEquals(EffectsLevel.REDUCED, policy.effects)
        assertEquals(PrefetchPolicy.NEXT_ONLY, policy.prefetch)
        assertTrue(policy.video.forceOff)
        assertFalse(policy.automixLookahead)
        // Low Power never touches audio quality: core playback is not degraded.
        assertNull(policy.audioCap)
    }

    @Test
    fun lowPower_suspendsHighPerformance() {
        val prefs = ModePreferences(lowPower = LowPowerSetting.ON, highPerformance = true)
        val active = ModePolicy.active(prefs, wifi)
        assertFalse(active.highPerformance)
        assertTrue(active.highPerformanceSuspended)
        val policy = ModePolicy.resolve(prefs, active)
        assertEquals(RefreshOverride(capHz = 60), policy.refresh)
        assertEquals(EffectsLevel.REDUCED, policy.effects)
    }

    @Test
    fun highPerformance_alone_holdsTheTopRate_andFullEffects() {
        val prefs = ModePreferences(highPerformance = true)
        val policy = ModePolicy.resolve(prefs, ModePolicy.active(prefs, wifi))
        assertEquals(RefreshOverride(forceMax = true), policy.refresh)
        assertEquals(EffectsLevel.FULL, policy.effects)
        assertEquals(VideoOverride.None, policy.video)
        assertEquals(PrefetchPolicy.FULL, policy.prefetch)
    }

    @Test
    fun bothModes_theStricterLimitWinsPerLever() {
        // Data Saver caps video low; Low Power wants the banner: the banner wins.
        val prefs = ModePreferences(
            dataSaver = DataSaverSetting.ON,
            dataSaverVideo = ModeVideo.LOW,
            lowPower = LowPowerSetting.ON,
            lowPowerVideo = ModeVideo.BANNER,
        )
        val policy = ModePolicy.resolve(prefs, ModePolicy.active(prefs, cellular))
        assertTrue(policy.video.forceOff)
        assertEquals("Low Power Mode", policy.video.reason)
        assertEquals(AudioQuality.MEDIUM, policy.audioCap)
        assertEquals(RefreshOverride(capHz = 60), policy.refresh)
        assertEquals(PrefetchPolicy.NEXT_ONLY, policy.prefetch)
        // Both only cap video low: the cap applies.
        val both = prefs.copy(lowPowerVideo = ModeVideo.LOW)
        assertEquals(VideoOverride(maxHeight = ModePolicy.LOW_VIDEO_HEIGHT, reason = "Data Saver"), ModePolicy.resolve(both, ModePolicy.active(both, cellular)).video)
    }

    @Test
    fun turningAModeOff_restoresTheListenersOwnSettings() {
        val prefs = ModePreferences(dataSaver = DataSaverSetting.CELLULAR_ONLY)
        val onCellular = ModePolicy.resolve(prefs, ModePolicy.active(prefs, cellular))
        assertTrue(onCellular.video.forceOff)
        // Back on Wi-Fi the mode lifts, and nothing it did lingers: no override at all.
        val backOnWifi = ModePolicy.resolve(prefs, ModePolicy.active(prefs, wifi))
        assertEquals(EffectivePolicy.Unrestricted, backOnWifi)
        // And the preferences themselves were never changed by any of it.
        assertEquals(ModePreferences(dataSaver = DataSaverSetting.CELLULAR_ONLY), prefs)
    }

    @Test
    fun effects_areLaidOverTheListenersChoices_andComeBackUntouched() {
        val own = EffectSettings(reduceAnimation = false, reduceBlur = true, animatedCovers = true)
        // Low Power reduces everything and pauses covers.
        assertEquals(EffectSettings(true, true, false), EffectsLevel.REDUCED.over(own, coversAllowed = false))
        // High Performance turns blur and animation back on over a "reduce blur" choice.
        assertEquals(EffectSettings(false, false, true), EffectsLevel.FULL.over(own, coversAllowed = true))
        // With no mode, exactly the listener's own choices: nothing a mode did lingers.
        assertEquals(own, EffectsLevel.USER.over(own, coversAllowed = true))
        // A mode can only pause covers, never turn on ones the listener switched off.
        val noCovers = own.copy(animatedCovers = false)
        assertEquals(noCovers, EffectsLevel.USER.over(noCovers, coversAllowed = true))
    }

    @Test
    fun policyToEffects_endToEnd_lowPowerOnThenOff() {
        val own = EffectSettings(reduceAnimation = false, reduceBlur = false, animatedCovers = true)
        val on = ModePreferences(lowPower = LowPowerSetting.ON)
        val during = ModePolicy.resolve(on, ModePolicy.active(on, wifi))
        assertEquals(EffectSettings(true, true, false), during.effects.over(own, during.animatedCovers))
        val off = on.copy(lowPower = LowPowerSetting.OFF)
        val after = ModePolicy.resolve(off, ModePolicy.active(off, wifi))
        assertEquals(own, after.effects.over(own, after.animatedCovers))
    }
}
