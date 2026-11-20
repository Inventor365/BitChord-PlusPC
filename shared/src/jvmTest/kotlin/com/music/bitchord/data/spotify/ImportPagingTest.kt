package com.music.bitchord.data.spotify

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The paging that takes an import past its first 100 songs, against saved response shapes and
 * generated playlists the size of the ones that stopped at 92 (812 on Spotify, 1,400 on Apple).
 */
class ImportPagingTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/import/$name")) { "missing fixture $name" }.readText()

    private val noSleep: suspend (Long) -> Unit = {}

    @Test
    fun readsSpotifyWebApiPage_skippingRowsWithoutATrack() {
        val page = assertNotNull(ImportPages.spotifyWebApi(fixture("spotify_webapi_page.json")))
        assertEquals(
            listOf(
                SpotifyImportTrack("Ain't No Sunshine", "Bill Withers"),
                SpotifyImportTrack("Let's Stay Together", "Al Green"),
                SpotifyImportTrack("Ooh Child", "The Five Stairsteps, Guest"),
            ),
            page.tracks,
        )
        assertEquals(812, page.total)
        assertNotNull(page.next)
    }

    @Test
    fun readsSpotifyPathfinderPage_countingEveryRowItMovedPast() {
        val (page, rows) = assertNotNull(ImportPages.spotifyPathfinder(fixture("spotify_pathfinder_page.json")))
        assertEquals(listOf("Cupid", "Lovely Day"), page.tracks.map { it.title })
        assertEquals(3, rows)
        assertEquals(812, page.total)
    }

    @Test
    fun readsAppleCatalogPage() {
        val page = assertNotNull(ImportPages.appleTracks(fixture("apple_tracks_page.json")))
        assertEquals(
            listOf(SpotifyImportTrack("A Change Is Gonna Come", "Sam Cooke"), SpotifyImportTrack("Respect", "Aretha Franklin")),
            page.tracks,
        )
        assertEquals(1400, page.total)
        assertEquals("/v1/catalog/in/playlists/pl.u-yZyVEyZtd6JWbgy/tracks?offset=200", page.next)
    }

    @Test
    fun findsAppleWebAppScriptAndItsToken() {
        assertEquals(listOf("/assets/index~f3a1b2c4d5.js"), ImportPages.appleAppScripts(fixture("apple_page_head.html")))
        val token = assertNotNull(ImportPages.appleDeveloperToken(fixture("apple_app_script.js")))
        assertTrue(token.startsWith("eyJhbGciOiJFUzI1NiIs"))
        assertNull(ImportPages.appleDeveloperToken("const x=\"eyJh\";"))
    }

    /** A service holding [total] songs, answering pages of up to [size] from any offset. */
    private fun service(total: Int, size: Int = 100): (Int) -> Pair<ImportPage, Int> = { offset ->
        val tracks = (offset until minOf(offset + size, total)).map { SpotifyImportTrack("Song $it", "Artist $it") }
        val next = if (offset + size < total) "/next?offset=${offset + size}" else null
        ImportPage(tracks, total, next) to tracks.size
    }

    @Test
    fun spotifySizedPlaylistIsReadToTheEnd() = runBlocking {
        val result = pageAll(start = 100, sleep = noSleep) { service(812)(it) }
        assertNull(result.stoppedBy)
        assertEquals(712, result.tracks.size)
        assertEquals("Song 811", result.tracks.last().title)
        assertEquals(812, result.total)
    }

    @Test
    fun appleSizedPlaylistIsReadToTheEnd() = runBlocking {
        val result = pageAll(start = 100, sleep = noSleep) { service(1400)(it) }
        assertEquals(1300, result.tracks.size)
        assertEquals((100 until 1400).map { "Song $it" }, result.tracks.map { it.title })
    }

    @Test
    fun rateLimitsAndServerErrorsAreRetriedWithGrowingPauses() = runBlocking {
        val pauses = mutableListOf<Long>()
        var calls = 0
        val result = pageAll(start = 0, sleep = { pauses += it }) { offset ->
            calls++
            when (calls) {
                2 -> throw ImportHttpException(429)
                3 -> throw ImportHttpException(503)
                4 -> throw ImportHttpException(429, retryAfterSec = 7)
                else -> service(300)(offset)
            }
        }
        assertNull(result.stoppedBy)
        assertEquals(300, result.tracks.size)
        assertEquals(listOf(800L, 1600L, 7000L), pauses)
    }

    @Test
    fun aRefusalThatWontPassStopsAtOnce_keepingWhatWasRead(): Unit = runBlocking {
        var calls = 0
        val result = pageAll(start = 0, sleep = noSleep) { offset ->
            if (++calls == 3) throw ImportHttpException(403) else service(800)(offset)
        }
        assertEquals(200, result.tracks.size)
        assertIs<ImportHttpException>(result.stoppedBy)
        assertEquals(3, calls)
    }

    @Test
    fun skippedRowsStillMoveTheOffsetOn() = runBlocking {
        // Every page holds one row with no song (a local file): 100 rows, 99 songs.
        val offsets = mutableListOf<Int>()
        val result = pageAll(start = 0, sleep = noSleep) { offset ->
            offsets += offset
            val (page, rows) = service(300)(offset)
            page.copy(tracks = page.tracks.drop(1)) to rows
        }
        assertEquals(listOf(0, 100, 200), offsets)
        assertEquals(297, result.tracks.size)
    }

    @Test
    fun fetchedPlaylistSaysHowManyAreMissing() {
        val partial = FetchedPlaylist("Soul", List(300) { SpotifyImportTrack("t$it", "a") }, declared = 1400, stoppedBy = ImportHttpException(403))
        assertEquals(1100, partial.missing)
        assertEquals(0, FetchedPlaylist("x", emptyList(), declared = null).missing)
    }

    // ---- Round 2: the imports that sat on "Reading playlist…" ----

    @Test
    fun aRetryAfterOfHoursFailsAtOnce_insteadOfBeingWaitedOut(): Unit = runBlocking {
        // Spotify's Web API answers an anonymous token with 429 and Retry-After: 25159 (~7 h).
        val pauses = mutableListOf<Long>()
        var calls = 0
        val result = pageAll(start = 0, sleep = { pauses += it }) { calls++; throw ImportHttpException(429, retryAfterSec = 25_159) }
        assertIs<ImportHttpException>(result.stoppedBy)
        assertEquals(1, calls)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun anEmptyPageEndsPaging(): Unit = runBlocking {
        val offsets = mutableListOf<Int>()
        val result = pageAll(start = 0, sleep = noSleep) { offset ->
            offsets += offset
            if (offset >= 200) ImportPage(emptyList(), total = null, next = "/more") to 0 else service(1000)(offset)
        }
        assertNull(result.stoppedBy)
        assertEquals(200, result.tracks.size)
        assertEquals(listOf(0, 100, 200), offsets)
    }

    @Test
    fun aServiceThatNeverAdvancesIsNotLoopedOn(): Unit = runBlocking {
        // Says there is more, and keeps handing back zero rows moved past.
        var calls = 0
        val result = pageAll(start = 0, sleep = noSleep) { calls++; ImportPage(listOf(SpotifyImportTrack("Same", "A")), total = 900, next = "/same") to 0 }
        assertEquals(1, calls)
        assertEquals(1, result.tracks.size)
    }

    @Test
    fun aServiceThatPagesForeverStopsAtThePageCap(): Unit = runBlocking {
        var calls = 0
        val result = pageAll(start = 0, maxPages = 7, sleep = noSleep) { offset ->
            calls++
            ImportPage(listOf(SpotifyImportTrack("t$offset", "a")), total = null, next = "/next") to 1
        }
        assertEquals(7, calls)
        assertEquals(7, result.tracks.size)
    }

    @Test
    fun anHttpErrorMidwayKeepsThePagesBefore_andReportsProgressOnTheWay(): Unit = runBlocking {
        val progress = mutableListOf<Pair<Int, Int?>>()
        val result = pageAll(start = 100, sleep = noSleep, onPage = { read, total -> progress += read to total }) { offset ->
            if (offset == 400) throw ImportHttpException(404) else service(882)(offset)
        }
        assertEquals(300, result.tracks.size)
        assertIs<ImportHttpException>(result.stoppedBy)
        assertEquals(listOf<Pair<Int, Int?>>(100 to 882, 200 to 882, 300 to 882), progress)
    }

    @Test
    fun regionHiddenSpotifyRowsAreKeptByIdNotDroppedSilently() {
        val (page, rows) = assertNotNull(ImportPages.spotifyPathfinder(fixture("spotify_pathfinder_restricted.json")))
        assertEquals(3, rows)
        assertEquals(listOf("Kesariya", ""), page.tracks.map { it.title })
        assertEquals("6Gx3Ry4ORogdMibbdITOuN", page.tracks[1].spotifyId)
        assertEquals("Pritam", page.tracks[1].artist)
    }

    @Test
    fun appleTokenIsFoundWhateverItsHeaderOrder_andTheWebPlayersIsPreferred() {
        val token = assertNotNull(ImportPages.appleDeveloperToken(fixture("apple_app_script_real_shape.js")))
        assertTrue(token.startsWith("eyJ0eXAiOiJKV1Qi"))
        assertTrue(token.contains("eyJpc3MiOiJBTVBXZWJQbGF5")) // {"iss":"AMPWebPlay"
    }

    @Test
    fun applesPageCountIsNotThePlaylistSize_itsNextLinkSaysToReadOn() {
        val html = """{"url":"/v1/catalog/in/playlists/pl.u-yZyVEyZtd6JWbgy/tracks?l=en-GB&offset=300","trackCount":300}"""
        assertEquals(300, AppleMusicImporter.nextPageOffset(html))
        assertNull(AppleMusicImporter.nextPageOffset("""{"trackCount":42}"""))
    }
}
