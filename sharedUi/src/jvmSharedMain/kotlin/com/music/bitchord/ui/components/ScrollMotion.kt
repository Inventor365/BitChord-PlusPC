package com.music.bitchord.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Whether a page is scrolling right now, app-wide. Fed by [connection], which the app installs
 * around its pages; read by effects that cost the most per frame while content moves under
 * them — live blur above all, which is re-sampled on every scrolled frame.
 *
 * A snapshot state, so a read inside a modifier node's observed block (Haze's effect block)
 * updates that node the moment a scroll starts or settles.
 */
object ScrollMotion {
    var scrolling by mutableStateOf(false)
        private set

    @Volatile
    private var lastMovedAt = 0L
    private var watcher: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Observes every nested scroll under it without consuming any of it. */
    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (consumed != Offset.Zero) moved()
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            moved()
            return Velocity.Zero
        }
    }

    private fun moved() {
        lastMovedAt = System.nanoTime()
        if (!scrolling) scrolling = true
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            // Settled once nothing has moved for a beat; a fling keeps pinging until it stops.
            while (System.nanoTime() - lastMovedAt < SETTLE_NANOS) delay(SETTLE_POLL_MS)
            scrolling = false
        }
    }

    private const val SETTLE_NANOS = 160_000_000L
    private const val SETTLE_POLL_MS = 40L
}
