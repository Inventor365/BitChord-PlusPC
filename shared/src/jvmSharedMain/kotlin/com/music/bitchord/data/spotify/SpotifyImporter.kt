package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger

/** Single track metadata extracted from a Spotify playlist. */
data class SpotifyImportTrack(
    val title: String,
    val artist: String,
    /**
     * The service's own id for the track, kept only while the title is unknown: Spotify blanks
     * the name of a track not licensed in the listener's country, and the id is how it's looked up.
     */
    val spotifyId: String? = null,
)

/**
 * A playlist's songs as read from its service: [tracks] in order, the [declared] total when the
 * service says, and [stoppedBy] when the reading was cut short (the songs before it stand).
 */
data class FetchedPlaylist(
    val title: String,
    val tracks: List<SpotifyImportTrack>,
    val declared: Int?,
    val stoppedBy: Throwable? = null,
    /** Rows the service gave no title for here (not available in the listener's country). */
    val regionHidden: Int = 0,
) {
    /** Songs the service has that weren't read, for any reason (including [regionHidden]). */
    val missing: Int get() = declared?.let { (it - tracks.size).coerceAtLeast(0) } ?: 0
}

/** The result of fetching and resolving a Spotify playlist's tracks. */
data class SpotifyImportResult(
    val title: String,
    val description: String,
    val thumbnailUrl: String?,
    val totalTracks: Int,
    val resolvedVideoIds: List<String>,
    val unmatchedTracks: List<SpotifyImportTrack>,
)

object SpotifyImporter {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** A Spotify playlist id is 22 base-62 characters. */
    private val PLAYLIST_ID = Regex("""[A-Za-z0-9]{22}""")

    private fun isSpotifyHost(host: String) =
        host == "spotify.com" || host.endsWith(".spotify.com")

    /** Parses a raw user string or link into a Spotify playlist ID if valid. */
    fun extractPlaylistId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("spotify:playlist:")) {
            val id = trimmed.removePrefix("spotify:playlist:").substringBefore("?").substringBefore("/")
            return id.takeIf { PLAYLIST_ID.matches(it) }
        }

        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        if (!isSpotifyHost(uri.host?.lowercase().orEmpty())) return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val playlistIdx = segments.indexOf("playlist")
        if (playlistIdx == -1 || playlistIdx + 1 >= segments.size) return null
        return segments[playlistIdx + 1].takeIf { PLAYLIST_ID.matches(it) }
    }

    /** The playlist's title and songs; see [fetchPlaylist] for how much was read. */
    suspend fun fetchPlaylistTracks(playlistId: String): Pair<String, List<SpotifyImportTrack>> =
        fetchPlaylist(playlistId).let { it.title to it.tracks }

    /** Which source the rest of the playlist was read from, for the log. */
    private enum class Path { WEB_PLAYER_QUERY, WEB_API }

    /**
     * A public Spotify playlist, every song of it.
     *
     * The embed page carries the title, the first [EMBED_TRACK_LIMIT] songs and an anonymous
     * token. With that token the rest is paged — the web player's own query first (what
     * open.spotify.com uses, and what answers an anonymous token), Spotify's Web API
     * (`offset`/`limit`/`next`) if that refuses — each page retried with backoff on brief 429s
     * and 5xx. The Web API answers an anonymous token with a 429 and a Retry-After of hours,
     * which used to be waited out a page at a time: an import that sat on "Reading" for minutes. A playlist that still can't be read to the end comes back with what was
     * read and [FetchedPlaylist.stoppedBy], so the import can say how much is missing instead
     * of quietly stopping at 100.
     */
    suspend fun fetchPlaylist(
        playlistId: String,
        onProgress: (read: Int, total: Int?) -> Unit = { _, _ -> },
    ): FetchedPlaylist =
        withContext(Dispatchers.IO) {
            val url = "https://open.spotify.com/embed/playlist/$playlistId"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val html = runCatching { withImportBackoff { fetchBody(request) } }.getOrElse { e ->
                error("Failed to load Spotify playlist (${e.message})")
            }.ifBlank { error("Failed to load Spotify playlist") }

            // Extract <script id="__NEXT_DATA__" type="application/json">...</script>
            val scriptRegex = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            val match = scriptRegex.find(html)
                ?: error("Could not parse Spotify playlist metadata. Make sure the playlist is public.")

            val jsonString = match.groupValues[1]
            val root = json.parseToJsonElement(jsonString).jsonObject

            val props = root["props"]?.jsonObject
                ?.get("pageProps")?.jsonObject
            val stateData = props?.get("state")?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("entity")?.jsonObject
                ?: props?.get("entity")?.jsonObject
                ?: error("Spotify playlist entity missing or private")

            val playlistTitle = stateData["name"]?.jsonPrimitive?.content
                ?: stateData["title"]?.jsonPrimitive?.content
                ?: "Imported Spotify Playlist"

            val trackListJson = stateData["trackList"]?.jsonArray
                ?: stateData["tracks"]?.jsonArray
                ?: JsonArray(emptyList())

            val tracks = mutableListOf<SpotifyImportTrack>()
            for (element in trackListJson) {
                val obj = element.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content
                    ?: obj["name"]?.jsonPrimitive?.content
                    ?: continue
                val subtitle = obj["subtitle"]?.jsonPrimitive?.content
                    ?: obj["artists"]?.jsonArray?.joinToString(", ") {
                        it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                    }
                    ?: ""
                if (title.isNotBlank()) {
                    tracks.add(SpotifyImportTrack(title = title.trim(), artist = subtitle.trim()))
                } else {
                    // Not licensed here: no title, but the id to look it up by.
                    obj["uri"]?.jsonPrimitive?.content?.removePrefix("spotify:track:")?.takeIf { PLAYLIST_ID.matches(it) }
                        ?.let { tracks.add(SpotifyImportTrack(title = "", artist = subtitle.trim(), spotifyId = it)) }
                }
            }

            if (tracks.isEmpty()) {
                error("No tracks found in public Spotify playlist.")
            }

            // The embed page stops at its first 100 rows; a full page means there may be more.
            if (trackListJson.size < EMBED_TRACK_LIMIT) {
                ImportLog.i("spotify: whole playlist in the embed page: ${tracks.size} rows")
                val hidden = dropRegionHidden(tracks)
                return@withContext FetchedPlaylist(playlistTitle, tracks, declared = trackListJson.size, regionHidden = hidden)
            }
            val token = props?.get("state")?.jsonObject
                ?.get("settings")?.jsonObject
                ?.get("session")?.jsonObject
                ?.get("accessToken")?.jsonPrimitive?.content
                ?: return@withContext FetchedPlaylist(
                    playlistTitle, tracks, declared = null,
                    stoppedBy = IllegalStateException("Spotify gave no token to read past the first $EMBED_TRACK_LIMIT songs"),
                )
            val start = trackListJson.size
            val first = tracks.size
            onProgress(first, null)
            var path = Path.WEB_PLAYER_QUERY
            var rest = pageAll(start, onPage = { read, total -> onProgress(first + read, total) }) { offset ->
                pathfinderPage(playlistId, token, offset)
            }
            if (rest.stoppedBy != null && rest.tracks.isEmpty()) {
                // The web player's query refused outright (a changed query hash, say): the Web API.
                ImportLog.i("spotify: web player query refused (${rest.stoppedBy?.message}); trying the Web API")
                path = Path.WEB_API
                rest = pageAll(start, onPage = { read, total -> onProgress(first + read, total) }) { offset ->
                    webApiPage(playlistId, token, offset)
                }
            }
            ImportLog.i("spotify: read past the embed via $path: ${rest.tracks.size} rows, total=${rest.total}, stopped=${rest.stoppedBy?.message}")
            tracks.addAll(rest.tracks)
            val hidden = dropRegionHidden(tracks)
            FetchedPlaylist(
                playlistTitle,
                tracks,
                declared = rest.total ?: (tracks.size + hidden).takeIf { rest.stoppedBy == null },
                stoppedBy = rest.stoppedBy,
                regionHidden = hidden,
            )
        }

    /**
     * The YouTube Music song that is [track], or null if there is none: the
     * best candidate by title, artist, length and album, and failing that the
     * search's first hit.
     */
    suspend fun matchTrack(track: SpotifyTrack): Song? {
        val query = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")
        val candidates = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
            ?.filterIsInstance<SearchResult.Track>()
            ?.map { it.song }
            .orEmpty()
        return TrackMatcher.best(
            candidates,
            TrackMatcher.Target(
                title = track.title,
                artist = track.artist,
                durationSec = track.durationMs.takeIf { it > 0 }?.div(1000),
                album = track.album,
            ),
        ) ?: candidates.firstOrNull()
    }

    /** Tracks the embed page lists before it cuts off. */
    private const val EMBED_TRACK_LIMIT = 100
    private const val SEARCH_RETRY_DELAY_MS = 900L
    private const val SEARCH_ATTEMPTS = 3

    /** Searches in flight at once while matching: quick, without tripping YouTube's rate limit. */
    const val MATCH_PARALLELISM = 5
    private const val PAGE_SIZE = 100
    private const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
    private const val FETCH_PLAYLIST_HASH = "19ff1327c29e99c208c86d7a9d8f1929cfdf3d3202a0ff4253c821f1901aa94d"

    /**
     * Drops the rows Spotify left untitled — tracks not licensed in the listener's country,
     * which it names nowhere for an anonymous visitor (the playlist query, the track's own page
     * and its oEmbed all give a blank title, checked from India) — and returns how many there were.
     */
    private fun dropRegionHidden(tracks: MutableList<SpotifyImportTrack>): Int {
        val before = tracks.size
        tracks.removeAll { it.title.isBlank() }
        return (before - tracks.size).also { if (it > 0) ImportLog.i("spotify: $it tracks have no title here (not available in this country)") }
    }

    private fun webApiPage(playlistId: String, token: String, offset: Int): Pair<ImportPage, Int> {
        val url = "https://api.spotify.com/v1/playlists/$playlistId/tracks".toHttpUrl().newBuilder()
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("fields", "items(track(name,artists(name))),total,next")
            .build()
        val body = fetchBody(
            Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build(),
        )
        val page = ImportPages.spotifyWebApi(body) ?: error("unreadable Spotify page")
        // Rows moved past, skipped ones included: the page's own count, not the songs kept.
        val rows = if (page.next == null) page.tracks.size else PAGE_SIZE
        return page to rows
    }

    private fun pathfinderPage(playlistId: String, token: String, offset: Int): Pair<ImportPage, Int> {
        val variables = """{"uri":"spotify:playlist:$playlistId","offset":$offset,"limit":$PAGE_SIZE,"enableWatchFeedEntrypoint":false}"""
        val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$FETCH_PLAYLIST_HASH"}}"""
        val url = PATHFINDER_URL.toHttpUrl().newBuilder()
            .addQueryParameter("operationName", "fetchPlaylist")
            .addQueryParameter("variables", variables)
            .addQueryParameter("extensions", extensions)
            .build()
        val body = fetchBody(
            Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer $token")
                .header("App-platform", "WebPlayer")
                .header("Accept", "application/json")
                .build(),
        )
        return ImportPages.spotifyPathfinder(body) ?: error("unreadable Spotify page")
    }

    /**
     * Resolves a list of Spotify tracks to full Song objects using Innertube search.
     * Optionally filters or prioritizes matches by specified [language].
     * Reports real-time progress via [onProgress].
     */
    suspend fun resolveToSongs(
        tracks: List<SpotifyImportTrack>,
        language: String? = null,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): Pair<List<Song>, List<SpotifyImportTrack>> = coroutineScope {
        val total = tracks.size
        val completedCount = AtomicInteger(0)
        // Searches that failed outright (network, rate limit) rather than finding nothing:
        // when every one did, the import failed — it did not find "none of those songs".
        val failedSearches = AtomicInteger(0)
        val semaphore = Semaphore(MATCH_PARALLELISM)

        val cleanLang = language?.trim()?.takeIf {
            !it.equals("No Preference", ignoreCase = true) &&
            !it.equals("Multilingual", ignoreCase = true) &&
            it.isNotBlank()
        }

        val knownLanguages = listOf(
            "Tamil", "Telugu", "Kannada", "Malayalam", "Hindi",
            "English", "Punjabi", "Marathi", "Bengali", "Bhojpuri", "Gujarati"
        )

        val deferredResults = tracks.map { track ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val query = if (cleanLang != null) {
                        "${track.title} ${track.artist} $cleanLang".trim()
                    } else {
                        "${track.title} ${track.artist}".trim()
                    }

                    // A 429 or a dropped connection mid-import is usually gone a moment later, and
                    // costing a song for it is not worth it: a few tries, each pause longer.
                    val searchResult = runCatching {
                        withImportBackoff(attempts = SEARCH_ATTEMPTS, baseDelayMs = SEARCH_RETRY_DELAY_MS) {
                            YtMusicRepository.search(query, SearchFilter.SONGS).getOrElse { e ->
                                // Every failure here passes: a search that errors is worth a retry.
                                throw if (e is java.io.IOException) e else java.io.IOException(e.message, e)
                            }
                        }
                    }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; failedSearches.incrementAndGet() }
                        .getOrNull()
                    val candidates = searchResult?.filterIsInstance<SearchResult.Track>()?.map { it.song }.orEmpty()

                    val target = TrackMatcher.Target(
                        title = track.title,
                        artist = track.artist,
                    )
                    val bestMatch = TrackMatcher.best(candidates, target) ?: candidates.firstOrNull()

                    val matchedSong = if (cleanLang != null && candidates.isNotEmpty()) {
                        val conflictingLangs = knownLanguages.filter { !it.equals(cleanLang, ignoreCase = true) }
                        val nonConflictingCandidates = candidates.filter { candidate ->
                            val meta = "${candidate.title} ${candidate.artist} ${candidate.albumName.orEmpty()}".lowercase()
                            !conflictingLangs.any {
                                meta.contains("(${it.lowercase()})") ||
                                meta.contains("${it.lowercase()} version") ||
                                meta.contains("${it.lowercase()} song")
                            }
                        }
                        TrackMatcher.best(nonConflictingCandidates, target)
                            ?: nonConflictingCandidates.firstOrNull()
                            ?: bestMatch
                    } else {
                        bestMatch
                    }

                    val done = completedCount.incrementAndGet()
                    onProgress(done, total)

                    if (matchedSong != null) {
                        Pair(matchedSong, null)
                    } else {
                        Pair(null, track)
                    }
                }
            }
        }

        val results = deferredResults.awaitAll()
        if (total > 0 && failedSearches.get() == total) {
            throw java.io.IOException("Couldn't reach YouTube Music to match the songs. Check your connection and try again.")
        }
        val songs = results.mapNotNull { it.first }
        val unmatched = results.mapNotNull { it.second }

        Pair(songs, unmatched)
    }
}
