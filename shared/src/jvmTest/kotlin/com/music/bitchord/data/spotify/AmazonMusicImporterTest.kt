package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmazonMusicImporterTest {

    @Test
    fun recognisesAmazonMusicLinks() {
        val editorial = AmazonMusicImporter.parseLink("https://music.amazon.com/playlists/B07QXQHXQ2")!!
        assertEquals(AmazonMusicImporter.LinkKind.PLAYLIST, editorial.kind)
        assertEquals("B07QXQHXQ2", editorial.id)

        val shared = AmazonMusicImporter.parseLink(
            "Listen to my playlist on Amazon Music https://music.amazon.in/user-playlists/a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6sune?ref=dm_sh_xyz&marketplaceId=A21TJRUUN4KGV",
        )!!
        assertEquals(AmazonMusicImporter.LinkKind.USER_PLAYLIST, shared.kind)
        assertEquals("music.amazon.in", shared.domain)

        assertEquals(
            AmazonMusicImporter.LinkKind.PLAYLIST,
            AmazonMusicImporter.parseLink("https://www.amazon.co.uk/music/player/playlists/B08ABCDEF1")!!.kind,
        )
        assertEquals(AmazonMusicImporter.LinkKind.ALBUM, AmazonMusicImporter.parseLink("https://music.amazon.de/albums/B0C1234567")!!.kind)
        assertEquals(AmazonMusicImporter.LinkKind.OTHER, AmazonMusicImporter.parseLink("https://music.amazon.com/")?.kind)

        assertNull(AmazonMusicImporter.parseLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(AmazonMusicImporter.parseLink("https://www.amazon.com/dp/B07QXQHXQ2"))
        assertNull(AmazonMusicImporter.parseLink("just some text"))
    }

    @Test
    fun readsATrackListCopiedFromThePlaylistPage() {
        val copied = """
            1
            Housewife [Explicit]
            Tate McRae
            Housewife - Single
            3:28
            2
            Stop The Wedding!
            Ashe
            Stop The Wedding!
            2:59
            3
            Been By Now
            Morgan Wallen
            I'm The Problem
            3:12
        """.trimIndent()
        assertEquals(
            listOf(
                SpotifyImportTrack("Housewife", "Tate McRae"),
                SpotifyImportTrack("Stop The Wedding!", "Ashe"),
                SpotifyImportTrack("Been By Now", "Morgan Wallen"),
            ),
            AmazonMusicImporter.parseTracklist(copied),
        )
    }

    @Test
    fun readsSimpleLines_andIgnoresTheLink() {
        val text = """
            https://music.amazon.com/user-playlists/abcdef123456
            1. Northern Lights - Paper Kites
            Slow Tide – Marlowe
            Glasshouse by Idle Hours
            Undertow	Salt Harbour
            Northern Lights - Paper Kites
        """.trimIndent()
        assertEquals(
            listOf(
                SpotifyImportTrack("Northern Lights", "Paper Kites"),
                SpotifyImportTrack("Slow Tide", "Marlowe"),
                SpotifyImportTrack("Glasshouse", "Idle Hours"),
                SpotifyImportTrack("Undertow", "Salt Harbour"),
            ),
            AmazonMusicImporter.parseTracklist(text),
        )
    }

    @Test
    fun readsCsvExports() {
        val csv = """
            "Track name","Artist name","Album","Duration"
            "Housewife [Explicit]","Tate McRae","Housewife - Single","3:28"
            "Song, With Comma","Some ""Quoted"" Artist","X","1:00"
        """.trimIndent()
        assertEquals(
            listOf(
                SpotifyImportTrack("Housewife", "Tate McRae"),
                SpotifyImportTrack("Song, With Comma", "Some \"Quoted\" Artist"),
            ),
            AmazonMusicImporter.parseTracklist(csv),
        )
        val tsv = "Title\tArtist\nSlow Tide\tMarlowe"
        assertEquals(listOf(SpotifyImportTrack("Slow Tide", "Marlowe")), AmazonMusicImporter.parseTracklist(tsv))
    }

    @Test
    fun nothingToImportFromALinkAlone() {
        assertTrue(AmazonMusicImporter.parseTracklist("https://music.amazon.com/playlists/B07QXQHXQ2").isEmpty())
        assertTrue(AmazonMusicImporter.parseTracklist("   \n  ").isEmpty())
    }

    @Test
    fun amazonsExplicitTagNoLongerCostsTheMatch() {
        val youtube = listOf(
            Song(videoId = "aaaaaaaaaaa", title = "Housewife", artist = "Tate McRae", thumbnailUrl = null),
            Song(videoId = "bbbbbbbbbbb", title = "Housewife (Live)", artist = "Tribute Band", thumbnailUrl = null),
        )
        val parsed = AmazonMusicImporter.parseTracklist("Housewife [Explicit] - Tate McRae").single()
        val match = TrackMatcher.best(youtube, TrackMatcher.Target(title = parsed.title, artist = parsed.artist))
        assertEquals("aaaaaaaaaaa", match?.videoId)
    }
}
