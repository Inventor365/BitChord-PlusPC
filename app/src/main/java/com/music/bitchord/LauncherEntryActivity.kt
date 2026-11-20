package com.music.bitchord

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Where every home-screen icon (each `.Launcher…` activity-alias) lands: it opens [MainActivity]
 * by its own name and is gone before it draws.
 *
 * Why it exists: switching the app icon disables one alias and enables another, and Android
 * both finishes every activity recorded under a component being disabled and removes every task
 * whose base intent names one. When the aliases pointed straight at MainActivity, the running
 * screen *was* the alias and its task was started through it, so a swap closed Sonara — which
 * is why icon changes used to wait until the app left the screen. This activity runs in a task
 * of its own (an empty affinity in the manifest) and opens MainActivity, by its real name, in
 * MainActivity's own task; no alias is ever on screen or at the root of the app's task, and the
 * icon can change the moment a theme is picked (see [ThemeIcons]). Measured on a Xiaomi 11i
 * (Android 16, Launcher3): the swap no longer closes the app.
 *
 * It shares MainActivity's theme, so the system's splash carries straight on to it.
 */
class LauncherEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(intent).setClass(this, MainActivity::class.java)
                // The launcher's own flags (a new task, reset-if-needed) were for this hop.
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setComponent(android.content.ComponentName(this, MainActivity::class.java)),
        )
        finish()
        // No animation for a hop nobody sees.
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
