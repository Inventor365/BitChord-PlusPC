package com.music.bitchord.data.spotify

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The imports that stopped at 92: the first batch went in with the new playlist, and every
 * later one was sent while YouTube was still refusing edits to it (409) and dropped unseen.
 */
class PlaylistImportAdderTest {

    private class Status(val code: Int) : RuntimeException("HTTP $code")

    private val statusOf: (Throwable?) -> Int? = { (it as? Status)?.code }
    private val ids = (1..781).map { "v$it" }

    @Test
    fun aJustCreatedPlaylistIsWaitedOut_andEverySongArrives() = runBlocking {
        val playlist = mutableListOf<String>()
        var refusalsLeft = 6 // ~15s of "still settling"
        val waits = mutableListOf<Long>()
        val report = addAllReliably(ids, statusOf = statusOf, sleep = { waits += it }) { batch ->
            if (refusalsLeft-- > 0) throw Status(409)
            playlist += batch
        }
        assertEquals(ids, playlist)
        assertEquals(ids, report.added)
        assertTrue(report.rejected.isEmpty() && report.notSent.isEmpty())
        assertNull(report.stoppedBy)
        assertEquals(6, waits.size)
    }

    @Test
    fun songsYouTubeWontTakeAreFoundOneByOne_andTheRestGoIn() = runBlocking {
        val refused = setOf("v7", "v120", "v500")
        val playlist = mutableListOf<String>()
        val report = addAllReliably(ids, statusOf = statusOf, sleep = {}) { batch ->
            if (batch.any { it in refused }) throw Status(400)
            playlist += batch
        }
        assertEquals(refused, report.rejected.toSet())
        assertEquals(ids - refused, playlist)
        assertEquals(778, report.added.size)
    }

    @Test
    fun goingOfflineStops_andSaysWhatWasntSent(): Unit = runBlocking {
        var sent = 0
        val report = addAllReliably(ids, statusOf = statusOf, sleep = {}) { batch ->
            if (sent >= 200) throw java.io.IOException("offline")
            sent += batch.size
        }
        assertEquals(200, report.added.size)
        assertEquals(581, report.notSent.size)
        assertEquals("v201", report.notSent.first())
        assertIs<java.io.IOException>(report.stoppedBy)
    }

    @Test
    fun repeatsAreSentOnce_andProgressIsReported() = runBlocking {
        val seen = mutableListOf<Int>()
        val report = addAllReliably(listOf("a", "b", "a", "c"), chunk = 2, statusOf = statusOf, sleep = {}, onProgress = { seen += it }) {}
        assertEquals(listOf("a", "b", "c"), report.added)
        assertEquals(listOf(2, 3), seen)
    }
}
