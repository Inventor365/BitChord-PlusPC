package com.music.bitchord.playback

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

/**
 * The playhead, deliberately kept out of [PlayerState].
 */
@Stable
class PlaybackPosition {
    /** The latest reading of the playhead. Written through [report], with the time it was taken. */
    var positionMs by mutableLongStateOf(0L)
        private set

    var sampledAtNanos by mutableLongStateOf(0L)
        private set

    /** Transport state captured with this reading; null for producers using the UI-state fallback. */
    var isPlaying by mutableStateOf<Boolean?>(null)
        private set

    /** Only publish a new seek identity together with its landing position, never the old reading. */
    internal var sampledSeeks by mutableIntStateOf(0)
        private set

    var seeks by mutableIntStateOf(0)

    var advancing by mutableStateOf(true)

    fun report(positionMs: Long, sampledAtNanos: Long = System.nanoTime(), isPlaying: Boolean? = null) {
        Snapshot.withMutableSnapshot {
            this.positionMs = positionMs
            this.sampledAtNanos = sampledAtNanos
            this.isPlaying = isPlaying
            sampledSeeks = seeks
        }
    }
}
