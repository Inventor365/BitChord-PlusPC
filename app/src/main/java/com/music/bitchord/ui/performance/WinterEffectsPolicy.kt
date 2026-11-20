package com.music.bitchord.ui.performance

import com.music.bitchord.ui.theme.ThemeEffects

/** The listener's Winter effect switches; null is "not chosen", which takes the tier's default. */
data class WinterEffectPrefs(
    val aurora: Boolean? = null,
    val snow: Boolean? = null,
    val stars: Boolean? = null,
    val glass: Boolean? = null,
    val frost: Boolean? = null,
)

/** What is going on around the effects right now. */
data class EffectsMoment(
    /** Low Power Mode or Data Saver is on in the app. */
    val modeActive: Boolean = false,
    /** Low Power Mode specifically, or the system battery saver: the glass blur goes too. */
    val savingPower: Boolean = false,
    /** The system's animator duration scale is 0 ("remove animations"). */
    val animationsOff: Boolean = false,
    /** The pages aren't on screen: app in the background, or the full player is over them. */
    val hidden: Boolean = false,
)

/**
 * Resolves Winter's effects. Pure, so the rules are tested without a device.
 *
 *     chosen     = the listener's switch, or the device tier's default if never touched
 *     shown      = chosen                                  (off means gone, live)
 *     moving     = NOT (lowPower OR dataSaver OR batterySaver OR animatorScale == 0 OR hidden)
 *     glass      = chosen.glass AND NOT (lowPower OR batterySaver)
 *
 * - Low-end phones start with every heavy effect off; the listener can still turn any on.
 * - A switch turned off removes its effect at once. Saving power never removes one: it stops it
 *   moving, and a still frame shows instead — except the glass blur, the one effect that costs
 *   while still, which saving power swaps for a plain tint.
 * - "Night sky after sunset" doesn't change any of this; it picks the night or day scene (see
 *   [WinterScenePolicy]).
 */
object WinterEffectsPolicy {

    fun defaults(tier: DeviceTier): ThemeEffects = when (tier) {
        DeviceTier.LOW -> ThemeEffects(aurora = false, snow = false, stars = false, glass = false, frost = true)
        DeviceTier.MID, DeviceTier.HIGH -> ThemeEffects(aurora = true, snow = true, stars = true, glass = true, frost = true)
    }

    /** What the switches say, the tier filling in the ones never touched. */
    fun chosen(prefs: WinterEffectPrefs, tier: DeviceTier): ThemeEffects {
        val d = defaults(tier)
        return ThemeEffects(
            aurora = prefs.aurora ?: d.aurora,
            snow = prefs.snow ?: d.snow,
            stars = prefs.stars ?: d.stars,
            glass = prefs.glass ?: d.glass,
            frost = prefs.frost ?: d.frost,
        )
    }

    /** What is shown, and whether it may move, at [moment]. */
    fun resolve(chosen: ThemeEffects, moment: EffectsMoment): ThemeEffects {
        val still = moment.modeActive || moment.savingPower || moment.animationsOff || moment.hidden
        return chosen.copy(
            glass = chosen.glass && !moment.savingPower,
            moving = !still,
        )
    }
}

/**
 * Which Winter scene to draw: the polar night or the arctic day.
 *
 *     night = appIsDark OR (nightSkyAfterSunset AND the sun is down)
 *
 * Light mode stays light all day unless the listener asked for the night sky after sunset
 * (off by default). Sunrise and sunset are 06:00 and 18:00 local time: no location is asked for.
 */
object WinterScenePolicy {
    const val SUNRISE_MINUTE = 6 * 60
    const val SUNSET_MINUTE = 18 * 60

    fun night(appIsDark: Boolean, nightSkyAfterSunset: Boolean, minuteOfDay: Int): Boolean =
        appIsDark || (nightSkyAfterSunset && !sunUp(minuteOfDay))

    fun sunUp(minuteOfDay: Int): Boolean = minuteOfDay in SUNRISE_MINUTE until SUNSET_MINUTE

    /** The sun's way across the sky: 0 at sunrise (left edge), 1 at sunset (right); null when down. */
    fun sunProgress(minuteOfDay: Int): Float? =
        if (!sunUp(minuteOfDay)) null
        else (minuteOfDay - SUNRISE_MINUTE).toFloat() / (SUNSET_MINUTE - SUNRISE_MINUTE)
}
