package com.music.bitchord.playback

/**
 * Whether the line being sung may stand in for the song's title in the session's now-playing
 * metadata.
 *
 * There is one platform session, and everything reads it: the notification and lock screen, but
 * also Android Auto and every Bluetooth head unit, speaker and pair of earbuds over AVRCP. Those
 * must always get the song's real title and artist — a car's now-playing card showing "and true
 * to my" instead of "Lover · Taylor Swift" is the bug this exists to prevent. Media3 cannot give
 * one controller different metadata from another, so the lyric is only ever published when none
 * of them could be listening:
 * - the listener asked for it (it is off by default);
 * - synced lyrics are on and the song is playing;
 * - no car is connected to the session;
 * - the sound isn't going to Bluetooth, a car or a dock — anything that could read AVRCP.
 *
 * Android Auto still has the lyrics: in its own Lyrics tab, where they belong.
 */
object NowPlayingLyricPolicy {
    fun allowed(
        optedIn: Boolean,
        syncedLyrics: Boolean,
        playing: Boolean,
        carConnected: Boolean,
        externalAudioRoute: Boolean,
    ): Boolean = optedIn && syncedLyrics && playing && !carConnected && !externalAudioRoute
}
