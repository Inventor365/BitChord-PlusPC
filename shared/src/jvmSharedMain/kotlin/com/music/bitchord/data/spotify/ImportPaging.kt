package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Request

/** A playlist service answered with an error status. [retryAfterSec] is its Retry-After, if any. */
class ImportHttpException(val code: Int, val retryAfterSec: Long? = null) : java.io.IOException("HTTP $code")

/**
 * Runs [block], again after a growing pause when it fails in a way that passes: a 429, a 5xx
 * or a dropped connection. A server's Retry-After is honoured (up to [MAX_RETRY_AFTER_MS]).
 * Anything else — a 404, a 401, a parse failure — fails at once: waiting won't change it.
 */
internal suspend fun <T> withImportBackoff(
    attempts: Int = 5,
    baseDelayMs: Long = 800L,
    sleep: suspend (Long) -> Unit = { delay(it) },
    block: suspend () -> T,
): T {
    var wait = baseDelayMs
    var last: Throwable? = null
    repeat(attempts) { attempt ->
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            last = e
            val passing = when (e) {
                is ImportHttpException -> e.code == 429 || e.code >= 500
                is java.io.IOException -> true
                else -> false
            }
            // A Retry-After longer than we'd ever wait is the service refusing, not busy:
            // Spotify answers an anonymous token's Web API request with ~7 hours. Fail now, so
            // the caller can take another path, instead of sitting through retries.
            val asked = (e as? ImportHttpException)?.retryAfterSec?.times(1000)
            if (!passing || attempt == attempts - 1 || (asked != null && asked > MAX_RETRY_AFTER_MS)) throw e
            sleep(asked ?: wait)
            wait *= 2
        }
    }
    throw last ?: IllegalStateException("unreachable")
}

/** The longest Retry-After waited out; anything longer fails at once. */
internal const val MAX_RETRY_AFTER_MS = 10_000L

/** GETs [request]'s body, turning an error status into an [ImportHttpException]. */
/**
 * The client imports use: the app's, with a deadline on each whole call. A read timeout alone
 * lets a response that trickles in hold a request open indefinitely.
 */
private val importClient by lazy { Http.client.newBuilder().callTimeout(IMPORT_CALL_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS).build() }

/** No single request of an import may take longer than this. */
internal const val IMPORT_CALL_TIMEOUT_S = 20L

internal fun fetchBody(request: Request): String {
    val started = System.nanoTime()
    // Logged without the query: it can carry ids, never tokens, but the path is what matters.
    val where = "${request.url.host}${request.url.encodedPath}"
    return try {
        importClient.newCall(request).execute().use { response ->
            val ms = (System.nanoTime() - started) / 1_000_000
            if (!response.isSuccessful) {
                ImportLog.i("GET $where -> HTTP ${response.code} in ${ms}ms retry-after=${response.header("Retry-After")}")
                throw ImportHttpException(response.code, response.header("Retry-After")?.trim()?.toLongOrNull())
            }
            response.body?.string().orEmpty().also {
                ImportLog.i("GET $where -> ${response.code} ${it.length}B in ${(System.nanoTime() - started) / 1_000_000}ms")
            }
        }
    } catch (e: java.io.IOException) {
        if (e !is ImportHttpException) ImportLog.i("GET $where -> ${e.javaClass.simpleName}: ${e.message} after ${(System.nanoTime() - started) / 1_000_000}ms")
        throw e
    }
}

/** The import's own log lines, under one tag so a stuck import can be followed in logcat. */
internal object ImportLog {
    fun i(message: String) = com.music.bitchord.data.DebugLog.i("PlaylistImport", message)
}

/** One page of a playlist's songs, and where the next one is. */
data class ImportPage(
    val tracks: List<SpotifyImportTrack>,
    /** The playlist's total, when the page says. */
    val total: Int?,
    /** The next page's URL or path, null at the end. */
    val next: String?,
)

/**
 * Reads the pages playlist services answer with. Pure functions of the response text, so the
 * paging is tested against saved responses rather than live services.
 */
object ImportPages {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * A page of Spotify's Web API `v1/playlists/{id}/tracks` (`items[].track`, `total`, `next`).
     * Rows without a track — a removed song, a local file — are skipped; episodes count as tracks.
     */
    fun spotifyWebApi(body: String): ImportPage? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val items = root["items"] as? JsonArray ?: return null
        val tracks = items.mapNotNull { item ->
            val track = (item as? JsonObject)?.get("track") as? JsonObject ?: return@mapNotNull null
            val title = track.str("name")?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val artist = (track["artists"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.str("name") }
                .joinToString(", ")
            SpotifyImportTrack(title, artist)
        }
        return ImportPage(tracks, root.int("total"), root.str("next"))
    }

    /**
     * A page of the web player's `fetchPlaylist` query
     * (`data.playlistV2.content.items[].itemV2.data`, `totalCount`). It has no next link: the
     * caller moves the offset on by [ImportPage.tracks]' raw row count, returned as [rows].
     */
    fun spotifyPathfinder(body: String): Pair<ImportPage, Int>? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val content = root.obj("data")?.obj("playlistV2")?.obj("content") ?: return null
        val items = content["items"] as? JsonArray ?: return null
        val tracks = items.mapNotNull { item ->
            val data = (item as? JsonObject)?.obj("itemV2")?.obj("data") ?: return@mapNotNull null
            val title = data.str("name")?.trim().orEmpty()
            val artist = (data.obj("artists")?.get("items") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.obj("profile")?.str("name") }
                .joinToString(", ")
            if (title.isEmpty()) {
                // Spotify blanks the name of a track not licensed in the listener's country
                // (playability COUNTRY_RESTRICTED) but keeps its id: kept, to be looked up.
                val id = data.str("uri")?.removePrefix("spotify:track:")?.takeIf { it.length == 22 } ?: return@mapNotNull null
                return@mapNotNull SpotifyImportTrack("", artist, spotifyId = id)
            }
            SpotifyImportTrack(title, artist)
        }
        return ImportPage(tracks, content.int("totalCount"), next = null) to items.size
    }

    /**
     * A page of Apple's `v1/catalog/{storefront}/playlists/{id}/tracks`
     * (`data[].attributes.{name,artistName}`, `next` as a path, `meta.total`).
     */
    fun appleTracks(body: String): ImportPage? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val data = root["data"] as? JsonArray ?: return null
        val tracks = data.mapNotNull { row ->
            val attributes = (row as? JsonObject)?.obj("attributes") ?: return@mapNotNull null
            val title = attributes.str("name")?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            SpotifyImportTrack(title, attributes.str("artistName")?.trim().orEmpty())
        }
        return ImportPage(tracks, root.obj("meta")?.int("total"), root.str("next"))
    }

    /** The scripts music.apple.com's page loads its app from (`/assets/index…js`). */
    fun appleAppScripts(html: String): List<String> =
        Regex("""<script[^>]+src="(/assets/index[^"]*\.js)"""").findAll(html).map { it.groupValues[1] }.toList()

    /**
     * The developer token the Apple Music web app ships in its bundle. The bundle holds several
     * JWTs for different Apple services; the web player's is the one issued to `AMPWebPlay`
     * (key id `WebPlayKid`), so that one is preferred, and failing that the first. Matched as any
     * JWT — its header can begin `{"typ":…` (`eyJ0eXAi…`) as well as `{"alg":…`. Null when none.
     */
    fun appleDeveloperToken(script: String): String? {
        val tokens = Regex("""["'`](eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,})["'`]""")
            .findAll(script).map { it.groupValues[1] }.distinct().toList()
        return tokens.firstOrNull { jwtPayload(it)?.contains("AMPWebPlay") == true } ?: tokens.firstOrNull()
    }

    private fun jwtPayload(token: String): String? = runCatching {
        String(java.util.Base64.getUrlDecoder().decode(token.split('.')[1].trimEnd('=')))
    }.getOrNull()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}

/**
 * Pages through a playlist from [start] until [fetch] says there's no more, or [limit] rows.
 * [fetch] takes the offset and returns the page and how many rows it moved past (a skipped
 * row still moves the offset on). Each page is retried through [withImportBackoff].
 */
internal suspend fun pageAll(
    start: Int,
    limit: Int = MAX_IMPORT_TRACKS,
    maxPages: Int = MAX_IMPORT_PAGES,
    sleep: suspend (Long) -> Unit = { delay(it) },
    onPage: (read: Int, total: Int?) -> Unit = { _, _ -> },
    fetch: suspend (offset: Int) -> Pair<ImportPage, Int>,
): PagedTracks {
    val out = ArrayList<SpotifyImportTrack>()
    var offset = start
    var total: Int? = null
    var pages = 0
    // Stops on: the end (no next / offset past the total / an empty page), the offset not
    // advancing, the row limit, or the page cap — never loops on a service that misbehaves.
    while (offset < limit && pages++ < maxPages) {
        val (page, rows) = try {
            withImportBackoff(sleep = sleep) { fetch(offset) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ImportLog.i("paging stopped at $offset: ${e.javaClass.simpleName}: ${e.message}")
            // What came before stands; the caller says how much is missing.
            return PagedTracks(out, total, stoppedBy = e)
        }
        total = page.total ?: total
        out += page.tracks
        ImportLog.i("page at $offset: ${page.tracks.size} songs, $rows rows, total=${page.total}, next=${page.next != null}")
        onPage(out.size, total)
        if (rows <= 0) break // didn't advance: a service repeating itself must not loop us
        offset += rows
        val atEnd = rows == 0 || (page.next == null && page.total != null && offset >= page.total) ||
            (page.next == null && page.total == null)
        if (atEnd) break
    }
    return PagedTracks(out, total, stoppedBy = null)
}

/** The songs read past the first page, the playlist's total if known, and what cut it short. */
data class PagedTracks(
    val tracks: List<SpotifyImportTrack>,
    val total: Int?,
    val stoppedBy: Throwable?,
)

/** Imports stop here: YouTube Music playlists hold 5,000 songs. */
const val MAX_IMPORT_TRACKS = 5_000

/** A safety net under the row limit: no import reads more pages than this. */
const val MAX_IMPORT_PAGES = 120
