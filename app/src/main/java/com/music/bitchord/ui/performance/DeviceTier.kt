package com.music.bitchord.ui.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * How much a phone can spend on decoration, judged once from what doesn't change: memory, the
 * low-RAM flag, the processor's core count, the Android version and the screen's top refresh
 * rate. It only sets defaults — the listener's own switches always win.
 */
enum class DeviceTier {
    /** Low-RAM devices, under 4 GB, or Android 9 and older: stills only. */
    LOW,

    /** Most phones: the effects, with a lighter snowfall. */
    MID,

    /** 8 GB or more, eight cores, Android 12+ and a fast screen: everything, in full. */
    HIGH,
    ;

    /** How much of the full snowfall this tier carries (0 = none). */
    val snowScale: Float
        get() = when (this) {
            LOW -> 0f
            MID -> 0.6f
            HIGH -> 1f
        }

    companion object {
        @Volatile private var cached: DeviceTier? = null

        fun of(context: Context): DeviceTier = cached ?: classify(
            totalRamGb = ramGb(context),
            lowRam = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true,
            cores = Runtime.getRuntime().availableProcessors(),
            sdk = Build.VERSION.SDK_INT,
            maxRefreshHz = maxRefreshHz(context),
        ).also { cached = it }

        /** The fastest mode the built-in screen offers. */
        private fun maxRefreshHz(context: Context): Int = runCatching {
            val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
            display.supportedModes.maxOf { it.refreshRate }.toInt()
        }.getOrDefault(60)

        /** The rule itself, separate from the device so it can be tested. */
        fun classify(totalRamGb: Float, lowRam: Boolean, cores: Int, sdk: Int, maxRefreshHz: Int): DeviceTier = when {
            lowRam || totalRamGb < 3.5f || sdk < Build.VERSION_CODES.Q -> LOW
            totalRamGb >= 7.5f && cores >= 8 && sdk >= Build.VERSION_CODES.S && maxRefreshHz >= 90 -> HIGH
            else -> MID
        }

        private fun ramGb(context: Context): Float {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
            return info.totalMem / (1024f * 1024f * 1024f)
        }
    }
}
