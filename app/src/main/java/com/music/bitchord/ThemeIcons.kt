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

    /** The icon that goes with [theme], or null for the listener's own choice. */
    fun iconFor(theme: ThemeId): AppIcon? = when (theme) {
        ThemeId.SONARA -> null
        ThemeId.VICE -> AppIcon.ThemeVice
        ThemeId.DOOM -> AppIcon.ThemeDoom
    }

    private val themeIcons = setOf(AppIcon.ThemeVice, AppIcon.ThemeDoom)

    /**
     * Queues the icon for [theme] (or the listener's own, for Sonara or with the theme icon
     * turned off). Returns true when the icon will change, so the caller can say so.
     */
    fun follow(context: Context, theme: ThemeId): Boolean {
        val current = AppIcon.current(context)
        if (current !in themeIcons) AppSettings.iconBeforeTheme = current.name
        val wanted = iconFor(theme).takeIf { AppSettings.themeIcon.value }
            ?: AppIcon.entries.firstOrNull { it.name == AppSettings.iconBeforeTheme }
            ?: AppIcon.Default
        // The queued change, or what is already there, is what will be on the home screen.
        val shown = AppIcon.entries.firstOrNull { it.name == AppSettings.pendingIcon } ?: current
        if (wanted == current) {
            AppSettings.pendingIcon = null
            return shown != current
        }
        AppSettings.pendingIcon = wanted.name
        return wanted != shown
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
