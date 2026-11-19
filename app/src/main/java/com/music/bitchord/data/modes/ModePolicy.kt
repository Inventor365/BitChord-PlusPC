package com.music.bitchord.data.modes

import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.ui.performance.RefreshOverride
import com.music.bitchord.ui.player.VideoOverride

/** Data Saver: off, always on, or on by itself whenever the connection is metered. */
enum class DataSaverSetting { OFF, ON, CELLULAR_ONLY }

/** Low Power Mode: off, always on, or on by itself (battery threshold, system battery saver). */
enum class LowPowerSetting { OFF, ON, AUTO }

/** What a mode does to music video: the static banner, or a low picture. */
enum class ModeVideo { BANNER, LOW }

/** How far ahead the next tracks are fetched. Never less than the next one: gapless needs it. */
enum class PrefetchPolicy { FULL, NEXT_ONLY }

/** Animations, blur and animated covers: as the listener set them, all on, or reduced. */
enum class EffectsLevel { USER, FULL, REDUCED }

/** The effect settings the app runs with, laid over the listener's own choices. */
data class EffectSettings(val reduceAnimation: Boolean, val reduceBlur: Boolean, val animatedCovers: Boolean)

/**
 * What [level] makes of the listener's own effect choices. The choices themselves are inputs
 * only: back at [EffectsLevel.USER] they come out exactly as they went in.
 */
fun EffectsLevel.over(own: EffectSettings, coversAllowed: Boolean): EffectSettings = when (this) {
    EffectsLevel.USER -> own
    EffectsLevel.FULL -> own.copy(reduceAnimation = false, reduceBlur = false)
    EffectsLevel.REDUCED -> own.copy(reduceAnimation = true, reduceBlur = true)
}.let { it.copy(animatedCovers = it.animatedCovers && coversAllowed) }

/** What turned a mode on — shown in the indicator's explanation. */
enum class ModeTrigger { MANUAL, METERED, SYSTEM_DATA_SAVER, BATTERY_LOW, SYSTEM_POWER_SAVE }

/** The listener's mode settings. Persisted; never changed by a mode. */
data class ModePreferences(
    val dataSaver: DataSaverSetting = DataSaverSetting.OFF,
    /** The highest audio quality Data Saver allows: efficient, not the minimum. */
    val dataSaverAudioCap: AudioQuality = AudioQuality.MEDIUM,
    val dataSaverVideo: ModeVideo = ModeVideo.BANNER,
    val lowPower: LowPowerSetting = LowPowerSetting.OFF,
    /** In AUTO, follow the system's own battery saver. */
    val lowPowerFollowsSystem: Boolean = true,
    /** In AUTO, turn on at or below this battery percentage when not charging; 0 = never. */
    val lowPowerBatteryThreshold: Int = 20,
    val lowPowerVideo: ModeVideo = ModeVideo.BANNER,
    val highPerformance: Boolean = false,
)

/** What the device says right now. */
data class DeviceState(
    val metered: Boolean? = null,
    val systemDataSaver: Boolean = false,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val systemPowerSave: Boolean = false,
)

/** Which modes are on, and why. A null trigger means the mode is off. */
data class ActiveModes(
    val dataSaver: ModeTrigger? = null,
    val lowPower: ModeTrigger? = null,
    val highPerformance: Boolean = false,
    /** High Performance was asked for but Low Power Mode is on, which wins. */
    val highPerformanceSuspended: Boolean = false,
) {
    val any: Boolean get() = dataSaver != null || lowPower != null || highPerformance
}

/**
 * The settings feature code reads, resolved from the active modes. Feature code never checks
 * mode flags itself: it reads one of these.
 */
data class EffectivePolicy(
    val video: VideoOverride = VideoOverride.None,
    val refresh: RefreshOverride = RefreshOverride.None,
    /** Cap on streaming audio quality, or null for the listener's own choice. */
    val audioCap: AudioQuality? = null,
    val prefetch: PrefetchPolicy = PrefetchPolicy.FULL,
    val effects: EffectsLevel = EffectsLevel.USER,
    /** Fetch artwork at a smaller size. */
    val smallArtwork: Boolean = false,
    /** Hold periodic background sync (playlist downloads) until Wi-Fi. */
    val backgroundSyncWifiOnly: Boolean = false,
    /** Download app updates on Wi-Fi only, unless the listener says otherwise. */
    val updatesWifiOnly: Boolean = false,
    /** Stream animated covers (Spotify Canvas, Apple motion). */
    val animatedCovers: Boolean = true,
    /** Run the upcoming-track analysis Automix uses (CPU and a download each). */
    val automixLookahead: Boolean = true,
) {
    companion object {
        val Unrestricted = EffectivePolicy()
    }
}

/**
 * Resolves the modes. Pure: everything it needs is passed in, so the precedence and restore
 * rules are tested without a device.
 *
 * Precedence:
 * - Low Power Mode suspends High Performance (and the indicator says so).
 * - Data Saver and Low Power can both be on; for each lever the stricter limit wins (video off
 *   beats a low cap, the lower audio cap wins, prefetch is the smaller of the two).
 * - Nothing here writes preferences: turning a mode off makes its overrides disappear, and the
 *   listener's own settings apply again untouched.
 */
object ModePolicy {

    /** Battery has to climb this far past the threshold before an automatic Low Power ends. */
    const val BATTERY_HYSTERESIS = 5

    /** The picture height a "low" video limit allows. */
    const val LOW_VIDEO_HEIGHT = 360

    /**
     * @param lowPowerWasOn whether automatic Low Power was on a moment ago (for hysteresis).
     * @param lowPowerSnoozed the listener turned automatic Low Power off from its indicator;
     *        it stays off until the phone is charged or the battery recovers.
     */
    fun active(
        prefs: ModePreferences,
        device: DeviceState,
        lowPowerWasOn: Boolean = false,
        lowPowerSnoozed: Boolean = false,
    ): ActiveModes {
        val dataSaver = when (prefs.dataSaver) {
            DataSaverSetting.OFF -> null
            DataSaverSetting.ON -> ModeTrigger.MANUAL
            DataSaverSetting.CELLULAR_ONLY -> when {
                device.metered == true -> ModeTrigger.METERED
                device.systemDataSaver -> ModeTrigger.SYSTEM_DATA_SAVER
                else -> null
            }
        }
        val lowPower = when (prefs.lowPower) {
            LowPowerSetting.OFF -> null
            LowPowerSetting.ON -> ModeTrigger.MANUAL
            LowPowerSetting.AUTO -> autoLowPower(prefs, device, lowPowerWasOn).takeUnless { lowPowerSnoozed }
        }
        val wantsHighPerformance = prefs.highPerformance
        return ActiveModes(
            dataSaver = dataSaver,
            lowPower = lowPower,
            highPerformance = wantsHighPerformance && lowPower == null,
            highPerformanceSuspended = wantsHighPerformance && lowPower != null,
        )
    }

    private fun autoLowPower(prefs: ModePreferences, device: DeviceState, wasOn: Boolean): ModeTrigger? {
        if (prefs.lowPowerFollowsSystem && device.systemPowerSave) return ModeTrigger.SYSTEM_POWER_SAVE
        val threshold = prefs.lowPowerBatteryThreshold
        val battery = device.batteryPercent ?: return null
        if (threshold <= 0 || device.charging) return null
        // On at the threshold; once on, it holds until the battery is clearly back above it,
        // so a reading wobbling around the line doesn't flip the mode back and forth.
        val limit = if (wasOn) threshold + BATTERY_HYSTERESIS else threshold
        return if (battery <= limit) ModeTrigger.BATTERY_LOW else null
    }

    fun resolve(prefs: ModePreferences, active: ActiveModes, reasonDataSaver: String = "Data Saver", reasonLowPower: String = "Low Power Mode"): EffectivePolicy {
        if (!active.any) return EffectivePolicy.Unrestricted
        val ds = active.dataSaver != null
        val lp = active.lowPower != null

        // Video: off beats a cap; with both modes capping, the lower cap.
        val dsVideo = if (ds) prefs.dataSaverVideo else null
        val lpVideo = if (lp) prefs.lowPowerVideo else null
        val video = when {
            dsVideo == ModeVideo.BANNER || lpVideo == ModeVideo.BANNER -> VideoOverride(
                forceOff = true,
                reason = if (dsVideo == ModeVideo.BANNER) reasonDataSaver else reasonLowPower,
            )
            dsVideo == ModeVideo.LOW || lpVideo == ModeVideo.LOW -> VideoOverride(
                maxHeight = LOW_VIDEO_HEIGHT,
                reason = if (dsVideo == ModeVideo.LOW) reasonDataSaver else reasonLowPower,
            )
            else -> VideoOverride.None
        }

        val refresh = when {
            lp -> RefreshOverride(capHz = 60)
            active.highPerformance -> RefreshOverride(forceMax = true)
            else -> RefreshOverride.None
        }

        return EffectivePolicy(
            video = video,
            refresh = refresh,
            audioCap = if (ds) prefs.dataSaverAudioCap else null,
            prefetch = if (ds || lp) PrefetchPolicy.NEXT_ONLY else PrefetchPolicy.FULL,
            effects = when {
                lp -> EffectsLevel.REDUCED
                active.highPerformance -> EffectsLevel.FULL
                else -> EffectsLevel.USER
            },
            smallArtwork = ds,
            backgroundSyncWifiOnly = ds || lp,
            updatesWifiOnly = ds,
            animatedCovers = !(ds || lp),
            automixLookahead = !lp,
        )
    }
}
