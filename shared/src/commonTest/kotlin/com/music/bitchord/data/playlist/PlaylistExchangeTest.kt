package com.music.bitchord.data.playlist

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistExchangeTest {

    private val sampleSongs = listOf(
        Song(
            videoId = "yKNxeF4KMsY",
            title = "Yellow",
            artist = "Coldplay",
            albumName = "Parachutes",
            thumbnailUrl = "https://i.ytimg.com/vi/yKNxeF4KMsY/hqdefault.jpg",
            durationText = "4:27",
        ),
        Song(
            videoId = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            albumName = "Whenever You Need Somebody",
            thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            durationText = "3:33",
        ),
    )

    @Test
    fun roundTripsM3uExportAndImport() {
        val m3u = PlaylistExchange.exportM3u("Road Trip", sampleSongs)
        val imported = PlaylistExchange.parseAny(m3u)
        assertEquals("Road Trip", imported.title)
        assertEquals(2, imported.songs.size)
        assertEquals("yKNxeF4KMsY", imported.songs[0].videoId)
        assertEquals("Yellow", imported.songs[0].title)
        assertEquals("Coldplay", imported.songs[0].artist)
        assertEquals("4:27", imported.songs[0].durationText)
        assertEquals("dQw4w9WgXcQ", imported.songs[1].videoId)
    }

    @Test
    fun roundTripsJsonExportAndImport() {
        val json = PlaylistExchange.exportJson("Late Night", sampleSongs, description = "Synth & indie")
        val imported = PlaylistExchange.parseAny(json)
        assertEquals("Late Night", imported.title)
        assertEquals(2, imported.songs.size)
        assertEquals("Parachutes", imported.songs[0].albumName)
        assertEquals("Rick Astley", imported.songs[1].artist)
    }

    @Test
    fun roundTripsCsvExportAndImport() {
        val csv = PlaylistExchange.exportCsv(sampleSongs)
        val imported = PlaylistExchange.parseAny(csv, defaultTitle = "CSV Copy")
        assertEquals("CSV Copy", imported.title)
        assertEquals(2, imported.songs.size)
        assertEquals("yKNxeF4KMsY", imported.songs[0].videoId)
        assertEquals("dQw4w9WgXcQ", imported.songs[1].videoId)
    }
}
