package com.music.bitchord.ui.performance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app-wide handles on the window's refresh rate: what the modes lay over the listener's
 * choice ([override]), and a way for any screen to ask for the boost rate while it animates.
 */
object RefreshRateRuntime {
    private val _override = MutableStateFlow(RefreshOverride.None)

    /** Set by the modes layer (Low Power caps, High Performance holds the top rate). */
    val override: StateFlow<RefreshOverride> = _override.asStateFlow()

    fun setOverride(value: RefreshOverride) {
        _override.value = value
    }

    /** The activity's controller while it is on screen. Main thread. */
    internal var controller: DisplayRefreshController? = null

    /** Asks for the boost rate until the returned function runs. A no-op with no window. */
    fun hold(): () -> Unit = controller?.hold() ?: {}
}

/** Holds the boost rate while [active] — a transition or a dragged sheet. */
@Composable
fun BoostRefreshWhile(active: Boolean) {
    DisposableEffect(active) {
        val release = if (active) RefreshRateRuntime.hold() else null
        onDispose { release?.invoke() }
    }
}
