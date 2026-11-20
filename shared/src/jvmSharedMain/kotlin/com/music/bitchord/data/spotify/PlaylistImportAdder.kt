package com.music.bitchord.data.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** What became of the songs an import tried to add. */
data class AddReport(
    /** In the playlist now, in order. */
    val added: List<String>,
    /** Refused one by one: YouTube won't put these in a playlist (removed, region-locked). */
    val rejected: List<String>,
    /** Never sent because adding stopped — signed out, offline — with [stoppedBy] saying why. */
    val notSent: List<String> = emptyList(),
    val stoppedBy: Throwable? = null,
)

/**
 * Adds songs to a playlist the way [com.music.bitchord.data.collab.CollabManager] adds them
 * when it converts one, so a long import arrives whole:
 *
 * - in batches of [chunk];
 * - a batch refused because the playlist is still settling (a 409, a 5xx — a playlist YouTube
 *   has just created refuses edits for up to ~20s) is retried after [settleDelayMs], up to
 *   [settleWaits] times;
 * - a batch refused for any other reason is halved until the songs that YouTube won't take are
 *   found one by one, and the rest go in;
 * - signed out (401) or offline stops it: no smaller batch would fare better, so what's left
 *   is reported as not sent rather than tried a song at a time.
 *
 * [add] sends one batch and throws on refusal; [statusOf] reads the HTTP status off a failure.
 * [onProgress] hears the count added so far.
 */
suspend fun addAllReliably(
    ids: List<String>,
    chunk: Int = 50,
    settleWaits: Int = 8,
    settleDelayMs: Long = 2_500L,
    statusOf: (Throwable?) -> Int?,
    sleep: suspend (Long) -> Unit = { delay(it) },
    onProgress: (added: Int) -> Unit = {},
    add: suspend (List<String>) -> Unit,
): AddReport {
    val added = ArrayList<String>()
    val rejected = ArrayList<String>()
    val unique = ids.distinct()

    fun settling(e: Throwable?): Boolean {
        val status = statusOf(e)
        if (status != null) return status == 409 || status >= 500
        val message = e?.message.orEmpty()
        return message.contains("ABORTED", ignoreCase = true) || message.contains("409")
    }

    fun fatal(e: Throwable?): Boolean =
        e is CancellationException || e is java.io.IOException || statusOf(e) == 401

    suspend fun send(batch: List<String>, waitForSettling: Boolean) {
        if (batch.isEmpty()) return
        var result = runCatching { add(batch) }
        var waits = 0
        while (result.isFailure && waitForSettling && waits++ < settleWaits && settling(result.exceptionOrNull())) {
            sleep(settleDelayMs)
            result = runCatching { add(batch) }
        }
        if (result.isSuccess) {
            added += batch
            onProgress(added.size)
            return
        }
        val error = result.exceptionOrNull()
        if (fatal(error)) throw error!!
        if (batch.size == 1) {
            rejected += batch
            return
        }
        val middle = batch.size / 2
        send(batch.subList(0, middle), waitForSettling = false)
        send(batch.subList(middle, batch.size), waitForSettling = false)
    }

    val batches = unique.chunked(chunk)
    for ((i, batch) in batches.withIndex()) {
        try {
            send(batch, waitForSettling = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val left = batches.drop(i).flatten().filterNot { it in added || it in rejected }
            return AddReport(added, rejected, notSent = left, stoppedBy = e)
        }
    }
    return AddReport(added, rejected)
}
