package com.music.bitchord

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.theme.ThemeId

/**
 * Keeps the home-screen icon in step with the theme.
 *
 * Swapping launcher aliases while the app is on screen can make some launchers close it, so a
 * change is only queued here and made when Sonara next leaves the screen. Choosing Sonara again
 * gives back whichever icon the listener had chosen before a theme took over.
 */
object ThemeIcons {
    private lateinit var app: Application

    fun init(application: Application) {
        app = application
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = applyPending(app)
        })
    }

    /** The icon that goes with [theme] in a dark or light app, or null for the listener's own choice. */
    fun iconFor(theme: ThemeId, dark: Boolean = true): AppIcon? = when (theme) {
        ThemeId.SONARA -> null
        ThemeId.VICE -> AppIcon.ThemeVice
        ThemeId.DOOM -> AppIcon.ThemeDoom
        // Winter has a polar-night icon and a snow-day one, following the app's light or dark.
        ThemeId.WINTER -> if (dark) AppIcon.ThemeWinter else AppIcon.ThemeWinterLight
    }

    private val themeIcons = setOf(AppIcon.ThemeVice, AppIcon.ThemeDoom, AppIcon.ThemeWinter, AppIcon.ThemeWinterLight)

    /** Whether the app is drawn dark right now: its own setting, or the system's under System. */
    fun appIsDark(context: Context): Boolean = when (AppSettings.themeMode.value) {
        com.music.bitchord.data.settings.ThemeMode.DARK -> true
        com.music.bitchord.data.settings.ThemeMode.LIGHT -> false
        com.music.bitchord.data.settings.ThemeMode.SYSTEM ->
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Puts the icon for [theme] on the home screen now (or the listener's own, for Sonara or with
     * "Match app icon" off). Returns true when the icon changed.
     *
     * Immediate since every launcher alias opens [LauncherEntryActivity] rather than MainActivity:
     * disabling an alias used to finish the running screen, so the swap had to wait for the app
     * to leave the screen. If the package manager refuses, it's queued for then instead.
     */
    fun follow(context: Context, theme: ThemeId, dark: Boolean = appIsDark(context)): Boolean {
        val current = AppIcon.current(context)
        if (current !in themeIcons) AppSettings.iconBeforeTheme = current.name
        val wanted = iconFor(theme, dark).takeIf { AppSettings.themeIcon.value }
            ?: AppIcon.entries.firstOrNull { it.name == AppSettings.iconBeforeTheme }
            ?: AppIcon.Default
        AppSettings.pendingIcon = null
        if (wanted == current) return false
        val applied = runCatching { AppIcon.apply(context.applicationContext, wanted) }
        if (applied.isFailure) {
            android.util.Log.w("ThemeIcons", "icon swap refused; queued for when the app leaves", applied.exceptionOrNull())
            AppSettings.pendingIcon = wanted.name
        }
        return true
    }

    /** The listener picked an icon by hand: that wins over anything queued by a theme. */
    fun chosenByHand() {
        AppSettings.pendingIcon = null
        AppSettings.iconBeforeTheme = null
    }

    private fun applyPending(context: Context) {
        val icon = AppIcon.entries.firstOrNull { it.name == AppSettings.pendingIcon } ?: return
        AppSettings.pendingIcon = null
        runCatching { AppIcon.apply(context, icon) }
    }
}
