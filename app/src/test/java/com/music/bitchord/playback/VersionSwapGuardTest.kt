package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionSwapGuardTest {

    @Test
    fun swap_appliesOnlyToTheRowItWasFoundFor() {
        assertTrue(versionSwapTargetsCurrent(currentId = "keaneVideo", replacingId = "keaneVideo"))
        // The track moved on while the other version was being looked up: the swap must not
        // put Keane's audio version in place of the song now playing.
        assertFalse(versionSwapTargetsCurrent(currentId = "rasputin", replacingId = "keaneVideo"))
        assertFalse(versionSwapTargetsCurrent(currentId = null, replacingId = "keaneVideo"))
    }

    @Test
    fun swap_fromAnOlderClientWithoutAnId_stillGoesThrough() {
        assertTrue(versionSwapTargetsCurrent(currentId = "anything", replacingId = null))
    }

    @Test
    fun optimisticVersion_showsWhileItsSongPlays_andOnceThePlayerCatchesUp() {
        // Pending: the video still plays, the audio version is shown ahead of the swap.
        assertTrue(optimisticVersionApplies(playingId = "keaneVideo", optimisticId = "keaneAudio", optimisticFor = "keaneVideo"))
        // Committed: the player is on the audio version itself.
        assertTrue(optimisticVersionApplies(playingId = "keaneAudio", optimisticId = "keaneAudio", optimisticFor = "keaneVideo"))
    }

    @Test
    fun optimisticVersion_isNeverShownOverAnotherSong() {
        // The full player showed Keane while the mini player and the session played Rasputin.
        assertFalse(optimisticVersionApplies(playingId = "rasputin", optimisticId = "keaneAudio", optimisticFor = "keaneVideo"))
        assertFalse(optimisticVersionApplies(playingId = null, optimisticId = "keaneAudio", optimisticFor = "keaneVideo"))
        assertFalse(optimisticVersionApplies(playingId = "rasputin", optimisticId = "keaneAudio", optimisticFor = null))
    }
}
