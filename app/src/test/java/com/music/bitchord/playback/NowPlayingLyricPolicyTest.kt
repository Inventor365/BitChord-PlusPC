package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingLyricPolicyTest {

    private fun allowed(
        optedIn: Boolean = true,
        synced: Boolean = true,
        playing: Boolean = true,
        car: Boolean = false,
        route: Boolean = false,
    ) = NowPlayingLyricPolicy.allowed(optedIn, synced, playing, car, route)

    @Test
    fun offUnlessTheListenerAskedForIt() {
        assertFalse(allowed(optedIn = false))
        assertTrue(allowed())
    }

    @Test
    fun aCarAlwaysGetsTheRealTitle() {
        assertFalse(allowed(car = true))
    }

    @Test
    fun bluetoothAndCarAudioRoutesAlwaysGetTheRealTitle() {
        // AVRCP reads the same session metadata: head units, speakers, earbuds.
        assertFalse(allowed(route = true))
    }

    @Test
    fun pausedOrWithoutSyncedLyricsTheTitleShows() {
        assertFalse(allowed(playing = false))
        assertFalse(allowed(synced = false))
    }
}
