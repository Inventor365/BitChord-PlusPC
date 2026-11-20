package com.music.bitchord.data.spotify

import android.util.Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One playlist import — Spotify, Apple Music or Amazon Music — from reading the playlist to the
 * last song added, run in the app's process rather than the dialog that started it, so leaving
 * the dialog, the screen or the app doesn't stop it. The dialog only watches [state].
 *
 * Every stage is counted (and logged under [TAG]) so an import that comes up short says where:
 * songs the service didn't give up, songs YouTube Music has no match for, songs it wouldn't
 * put in a playlist, and songs never sent because adding stopped.
 */
object PlaylistImport {

    enum class Phase { IDLE, FETCHING, MATCHING, ADDING, DONE, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val title: String = "",
        /** Songs read from the service (so far, while reading). */
        val fetched: Int = 0,
        /** The playlist's size, once the service has said. */
        val fetchTotal: Int? = null,
        /** Songs the service says the playlist has but that couldn't be read. */
        val fetchMissing: Int = 0,
        /** Of those, the ones the service doesn't name in this country. */
        val regionHidden: Int = 0,
        val matchDone: Int = 0,
        val matched: Int = 0,
        val added: Int = 0,
        val toAdd: Int = 0,
        val unmatched: List<SpotifyImportTrack> = emptyList(),
        /** Matched, but YouTube Music wouldn't put them in a playlist. */
        val rejected: Int = 0,
        /** Matched, but adding stopped (offline, signed out) before they were sent. */
        val notSent: Int = 0,
        /** Matched a song already in the playlist (the same song twice in the source). */
        val repeats: Int = 0,
        /** The listener was signed in, so "saved on this device" means YouTube Music refused. */
        val wantedRemote: Boolean = false,
        val browseId: String? = null,
        val savedLocally: Boolean = false,
        val error: String? = null,
        /** A retry of [unmatched] is running. */
        val retrying: Boolean = false,
    ) {
        val working: Boolean get() = phase == Phase.FETCHING || phase == Phase.MATCHING || phase == Phase.ADDING || retrying
    }

    /** A finished import, for the app to put the playlist in the library and say so. */
    data class Finished(val browseId: String, val title: String, val songs: Int, val savedLocally: Boolean, val cover: String?)

    private const val TAG = "PlaylistImport"
    private const val FETCH_DEADLINE_MS = 4 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _finished = MutableSharedFlow<Finished>(extraBufferCapacity = 4)
    val finished: SharedFlow<Finished> = _finished

    // What a retry needs from the import it follows.
    private var language: String? = null
    private var playlistId: String? = null

    /**
     * Starts an import, unless one is running. [fetch] reads the playlist (title and songs);
     * [name] overrides its title. Signed out, or if YouTube Music won't create the playlist, it
     * is saved on this device instead.
     */
    fun start(
        fetch: suspend (onProgress: (read: Int, total: Int?) -> Unit) -> FetchedPlaylist,
        language: String?,
        privacy: PlaylistPrivacy,
        signedIn: Boolean,
        name: String? = null,
    ) {
        if (job?.isActive == true) return
        this.language = language
        playlistId = null
        _state.value = State(phase = Phase.FETCHING, wantedRemote = signedIn)
        job = scope.launch {
            try {
                // Every request has its own deadline; this one is for the whole reading stage,
                // so no service, however it misbehaves, leaves the dialog on "Reading" forever.
                val playlist = kotlinx.coroutines.withTimeoutOrNull(FETCH_DEADLINE_MS) {
                    fetch { read, total -> _state.update { it.copy(fetched = read, fetchTotal = total ?: it.fetchTotal) } }
                } ?: error("Reading the playlist took too long. Check your connection and try again.")
                val title = name?.trim()?.takeIf { it.isNotEmpty() } ?: playlist.title
                _state.update {
                    it.copy(
                        phase = Phase.MATCHING,
                        title = title,
                        fetched = playlist.tracks.size,
                        fetchMissing = playlist.missing,
                        regionHidden = playlist.regionHidden.coerceAtMost(playlist.missing),
                    )
                }
                Log.i(TAG, "fetched ${playlist.tracks.size} of ${playlist.declared ?: "?"} \"$title\"" +
                    (playlist.stoppedBy?.let { " (stopped: ${it.message})" } ?: ""))

                val (songs, unmatched) = SpotifyImporter.resolveToSongs(playlist.tracks, language) { done, _ ->
                    _state.update { it.copy(matchDone = done) }
                }
                Log.i(TAG, "matched ${songs.size} of ${playlist.tracks.size}; ${unmatched.size} unmatched")
                if (songs.isEmpty()) error("None of the songs were found on YouTube Music.")
                // The same song can sit in a playlist twice under different album entries (a
                // soundtrack and a compilation): both match one YouTube video, which goes in once.
                val unique = songs.distinctBy(Song::videoId).size
                _state.update {
                    it.copy(phase = Phase.ADDING, matched = songs.size, unmatched = unmatched, toAdd = unique, repeats = songs.size - unique)
                }

                val outcome = save(title, privacy, signedIn, songs)
                Log.i(TAG, "added ${outcome.report.added.size} of ${songs.size}; rejected ${outcome.report.rejected.size}; " +
                    "not sent ${outcome.report.notSent.size}" + (outcome.report.stoppedBy?.let { " (${it.message})" } ?: ""))
                _state.update {
                    it.copy(
                        phase = Phase.DONE,
                        added = outcome.report.added.size,
                        rejected = outcome.report.rejected.size,
                        notSent = outcome.report.notSent.size,
                        browseId = outcome.browseId,
                        savedLocally = outcome.local,
                    )
                }
                _finished.tryEmit(Finished(outcome.browseId, title, outcome.report.added.size, outcome.local, songs.firstOrNull()?.thumbnailUrl))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "import failed", e)
                _state.update { it.copy(phase = Phase.FAILED, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Tries the songs that didn't match once more, adding the ones found to the same playlist. */
    fun retryUnmatched() {
        val current = _state.value
        val browseId = current.browseId ?: return
        if (current.phase != Phase.DONE || current.unmatched.isEmpty() || job?.isActive == true) return
        _state.update { it.copy(retrying = true, matchDone = 0) }
        job = scope.launch {
            try {
                val (songs, stillUnmatched) = SpotifyImporter.resolveToSongs(current.unmatched, language) { done, _ ->
                    _state.update { it.copy(matchDone = done) }
                }
                val report = if (songs.isEmpty()) {
                    AddReport(emptyList(), emptyList())
                } else if (current.savedLocally) {
                    LocalPlaylistStore.appendSongs(browseId, songs)
                    AddReport(songs.map(Song::videoId).distinct(), emptyList())
                } else {
                    addRemote(requireNotNull(playlistId), songs.map(Song::videoId), base = current.added)
                }
                Log.i(TAG, "retry matched ${songs.size} of ${current.unmatched.size}; added ${report.added.size}")
                _state.update {
                    it.copy(
                        retrying = false,
                        matched = it.matched + songs.size,
                        added = it.added + report.added.size,
                        rejected = it.rejected + report.rejected.size,
                        notSent = it.notSent + report.notSent.size,
                        unmatched = stillUnmatched,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(retrying = false) }
            }
        }
    }

    /** Clears a finished or failed import, so the dialog starts fresh. A running one is left alone. */
    fun reset() {
        if (job?.isActive == true) return
        _state.value = State()
    }

    private class Saved(val browseId: String, val local: Boolean, val report: AddReport)

    private suspend fun save(title: String, privacy: PlaylistPrivacy, signedIn: Boolean, songs: List<Song>): Saved {
        val ids = songs.map(Song::videoId).distinct()
        if (signedIn) {
            val created = YtMusicRepository.createPlaylist(title = title, privacy = privacy).getOrNull()
            if (created != null) {
                playlistId = created
                return Saved("VL$created", local = false, report = addRemote(created, ids))
            }
        }
        val local = LocalPlaylistStore.savePlaylist(title, songs.distinctBy(Song::videoId))
        return Saved(local.browseId, local = true, report = AddReport(ids, emptyList()))
    }

    private suspend fun addRemote(playlistId: String, ids: List<String>, base: Int = 0): AddReport =
        addAllReliably(
            ids = ids,
            statusOf = Innertube::httpStatusOf,
            onProgress = { added -> _state.update { it.copy(added = base + added) } },
        ) { batch -> YtMusicRepository.addToPlaylist(playlistId, batch).getOrThrow() }
}
