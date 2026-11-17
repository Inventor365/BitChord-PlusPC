package com.music.bitchord.data.spotify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppleMusicImporterTest {

    @Test
    fun extractsPlaylistLinks() {
        assertEquals(
            "https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb",
            AppleMusicImporter.extractPlaylistUrl("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb?l=en"),
        )
        assertEquals(
            "https://music.apple.com/in/playlist/road-trip/pl.u-AkAmPlyUxXxx12",
            AppleMusicImporter.extractPlaylistUrl("Listen to Road Trip on Apple Music. https://music.apple.com/in/playlist/road-trip/pl.u-AkAmPlyUxXxx12"),
        )
        assertNull(AppleMusicImporter.extractPlaylistUrl("https://music.apple.com/us/album/housewife/6822497770"))
        assertNull(AppleMusicImporter.extractPlaylistUrl("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test
    fun readsServerDataTrackList() {
        val html = """
            <html><head><script type="application/json" id="serialized-server-data">
            {"data":[{"intent":{},"data":{"sections":[
              {"itemKind":"containerDetailHeaderLockup","items":[{"title":"Road Trip","trackCount":3,
                "artwork":{"dictionary":{"url":"https://is1-ssl.mzstatic.com/image/thumb/x/{w}x{h}bb.{f}"}}}]},
              {"itemKind":"trackLockup","items":[
                {"title":"Housewife","artistName":"Tate McRae","duration":208810},
                {"title":"Northern Lights","subtitleLinks":[{"title":"Paper Kites"}]}
              ]}
            ]}}]}
            </script></head></html>
        """.trimIndent()
        val playlist = assertNotNull(AppleMusicImporter.parsePage(html))
        assertEquals("Road Trip", playlist.title)
        assertEquals(3, playlist.declaredCount)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/x/600x600bb.jpg", playlist.artworkUrl)
        assertEquals(
            listOf(SpotifyImportTrack("Housewife", "Tate McRae"), SpotifyImportTrack("Northern Lights", "Paper Kites")),
            playlist.tracks,
        )
    }

    @Test
    fun fallsBackToSchemaOrgPlaylist() {
        val html = """<script type="application/ld+json">{"@type":"MusicPlaylist","name":"Chill","numTracks":1,
            "track":[{"@type":"MusicRecording","name":"Slow Tide","byArtist":[{"@type":"MusicGroup","name":"Marlowe"}]}]}</script>"""
        val playlist = assertNotNull(AppleMusicImporter.parsePage(html))
        assertEquals("Chill", playlist.title)
        assertEquals(listOf(SpotifyImportTrack("Slow Tide", "Marlowe")), playlist.tracks)
    }
}
