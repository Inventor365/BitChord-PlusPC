package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/** A public Apple Music playlist, read off its web page. */
data class AppleMusicPlaylist(
    val title: String,
    val artworkUrl: String?,
    val tracks: List<SpotifyImportTrack>,
    /** How many songs the playlist says it has; more than [tracks] when the page held only some. */
    val declaredCount: Int?,
)

/**
 * Imports a public Apple Music playlist the way [SpotifyImporter] imports a Spotify one: the
 * playlist's own web page is read for each song's title and artist, and the songs are then
 * matched on YouTube Music by [SpotifyImporter.resolveToSongs].
 *
 * music.apple.com renders a playlist page with its data embedded as JSON
 * (`<script id="serialized-server-data">`): a header section with the name, artwork and song
 * count, and a `trackLockup` section with one item per song (`title`, `artistName`,
 * `duration` in ms, the album in its first tertiary link). The page's schema.org
 * `MusicPlaylist` block is read as a fallback when that shape changes.
 *
 * Works for editorial playlists (`pl.…`) and shared personal ones (`pl.u-…`), which must be
 * shared publicly from the Music app first. No Apple account is needed.
 *
 * The page holds only the first 100 songs. The rest come from Apple's catalog API
 * (`amp-api.music.apple.com/v1/catalog/{storefront}/playlists/{id}/tracks`, paged by `next`),
 * with the developer token the Apple Music web app itself ships in its script — the same
 * request music.apple.com makes as you scroll. Each page is retried with backoff on 429s and
 * 5xx; if the API can't be read, the page's songs stand and the import says how many are
 * missing.
 */
object AppleMusicImporter {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** An Apple Music playlist id: `pl.` then a hex id, or `pl.u-` then a shared-library id. */
    private val PLAYLIST_ID = Regex("""^pl\.(u-)?[A-Za-z0-9]{6,64}$""")
    private val EMBEDDED_URL = Regex("""https?://\S*music\.apple\.com/\S+""", RegexOption.IGNORE_CASE)
    private val SERVER_DATA = Regex(
        """<script[^>]*id="serialized-server-data"[^>]*>(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val LD_JSON = Regex(
        """<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * The canonical playlist URL in [input] — a link pasted alone or inside share text
     * ("Listen to … on Apple Music: https://music.apple.com/…") — or null if it holds none.
     */
    fun extractPlaylistUrl(input: String): String? {
        val candidate = EMBEDDED_URL.find(input.trim())?.value?.trimEnd('.', ',', ')', '"') ?: return null
        val uri = runCatching { java.net.URI(candidate) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        if (host != "music.apple.com" && !host.endsWith(".music.apple.com")) return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val idx = segments.indexOf("playlist")
        if (idx == -1) return null
        val id = segments.drop(idx + 1).lastOrNull { PLAYLIST_ID.matches(it) } ?: return null
        val storefront = segments.firstOrNull()?.takeIf { it.length == 2 && it.all(Char::isLetter) } ?: "us"
        val slug = segments.getOrNull(idx + 1)?.takeIf { it != id } ?: "playlist"
        return "https://music.apple.com/$storefront/playlist/$slug/$id"
    }

    fun isAppleMusicLink(input: String): Boolean = extractPlaylistUrl(input) != null

    suspend fun fetchPlaylist(url: String): AppleMusicPlaylist = fetchPage(url).first

    /**
     * Every song of the playlist at [url]: the page's, then the rest from the catalog API.
     *
     * The page's header `trackCount` is the number of songs *on the page* (300 for a long
     * playlist), not in the playlist, so it can't say whether to read on: the page's own link to
     * its next page of tracks (`…/tracks?offset=300`) does. Paging then runs until Apple's
     * `next` runs out, and Apple's `meta.total`, when given, is the playlist's size.
     */
    suspend fun fetchAll(
        url: String,
        onProgress: (read: Int, total: Int?) -> Unit = { _, _ -> },
    ): FetchedPlaylist = withContext(Dispatchers.IO) {
        val (page, html) = fetchPage(url)
        onProgress(page.tracks.size, null)
        val nextOffset = nextPageOffset(html)
        ImportLog.i("apple: page holds ${page.tracks.size} songs (trackCount=${page.declaredCount}), next page at ${nextOffset ?: "none"}")
        if (nextOffset == null) {
            return@withContext FetchedPlaylist(page.title, page.tracks, page.tracks.size)
        }
        val segments = java.net.URI(url).path.split('/').filter { it.isNotEmpty() }
        val storefront = segments.first()
        val id = segments.last()
        val rest = runCatching { developerToken(html) }.fold(
            onSuccess = { token ->
                pageAll(page.tracks.size, onPage = { read, total -> onProgress(page.tracks.size + read, total) }) { offset ->
                    catalogPage(storefront, id, token, offset)
                }
            },
            onFailure = {
                ImportLog.i("apple: no developer token: ${it.message}")
                PagedTracks(emptyList(), null, stoppedBy = it)
            },
        )
        ImportLog.i("apple: read past the page via the catalog API: ${rest.tracks.size} songs, total=${rest.total}, stopped=${rest.stoppedBy?.message}")
        FetchedPlaylist(
            title = page.title,
            tracks = page.tracks + rest.tracks,
            declared = rest.total ?: if (rest.stoppedBy == null) page.tracks.size + rest.tracks.size else null,
            stoppedBy = rest.stoppedBy,
        )
    }

    /** The offset of the page's link to its next page of tracks, if it has one. */
    internal fun nextPageOffset(html: String): Int? =
        Regex("""/v1/catalog/[a-z]{2}/playlists/[^"?]+/tracks\?[^"]*?offset=(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()

    private suspend fun fetchPage(url: String): Pair<AppleMusicPlaylist, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        val html = try {
            withImportBackoff { fetchBody(request) }
        } catch (e: ImportHttpException) {
            if (e.code == 404) error("Apple Music couldn't find that playlist. Check that it is shared publicly.")
            error("Failed to load Apple Music playlist (HTTP ${e.code})")
        }
        val page = parsePage(html) ?: error("Could not read that Apple Music playlist. Make sure it is shared publicly.")
        page to html
    }

    /** The web app's developer token, from the script the playlist page loads. Cached for the process. */
    private suspend fun developerToken(html: String): String {
        cachedToken?.let { return it }
        val scripts = ImportPages.appleAppScripts(html).ifEmpty {
            // The page in hand may be trimmed; the home page always loads the app.
            ImportPages.appleAppScripts(withImportBackoff { fetchBody(get("$WEB/us/browse")) })
        }
        for (path in scripts) {
            val script = withImportBackoff { fetchBody(get("$WEB$path")) }
            ImportPages.appleDeveloperToken(script)?.let { cachedToken = it; return it }
        }
        error("Apple Music's web app has no token to read past the playlist's first page")
    }

    private fun catalogPage(storefront: String, id: String, token: String, offset: Int): Pair<ImportPage, Int> {
        val body = try {
            fetchBody(
                Request.Builder()
                    .url("$API/v1/catalog/$storefront/playlists/$id/tracks?offset=$offset&limit=$API_PAGE")
                    .header("User-Agent", USER_AGENT)
                    .header("Authorization", "Bearer $token")
                    .header("Origin", WEB)
                    .header("Accept", "application/json")
                    .build(),
            )
        } catch (e: ImportHttpException) {
            // A stale token: fetch a fresh one next time instead of failing every import.
            if (e.code == 401 || e.code == 403) cachedToken = null
            throw e
        }
        val page = ImportPages.appleTracks(body) ?: error("unreadable Apple Music page")
        // Apple's `next` is the path of the following page; its absence is the end.
        val rows = if (page.next == null) page.tracks.size else API_PAGE
        return page to rows
    }

    private fun get(url: String) = Request.Builder().url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "en-US,en;q=0.9")
        .build()

    @Volatile private var cachedToken: String? = null
    private const val WEB = "https://music.apple.com"
    private const val API = "https://amp-api.music.apple.com"
    private const val API_PAGE = 100
    /** The songs a playlist page carries before the app pages the rest in. */
    private const val PAGE_SONGS = 100

    /** Reads a playlist page's embedded data; null when the page holds no songs. */
    fun parsePage(html: String): AppleMusicPlaylist? =
        SERVER_DATA.find(html)?.groupValues?.get(1)?.let { parseServerData(it) }
            ?: LD_JSON.findAll(html).firstNotNullOfOrNull { parseLdJson(it.groupValues[1]) }

    private fun parseServerData(raw: String): AppleMusicPlaylist? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val sections = findArrays(root, "sections").flatten().mapNotNull { it as? JsonObject }
        if (sections.isEmpty()) return null
        val header = sections.firstOrNull { it.str("itemKind")?.contains("HeaderLockup", ignoreCase = true) == true }
            ?.items()?.firstOrNull()
        val tracks = sections
            .filter { it.str("itemKind") == "trackLockup" }
            .flatMap { it.items() }
            .mapNotNull { item ->
                val title = item.str("title")?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                val artist = item.str("artistName")
                    ?: (item["subtitleLinks"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("title") }?.joinToString(", ")
                SpotifyImportTrack(title = title, artist = artist?.trim().orEmpty())
            }
        if (tracks.isEmpty()) return null
        return AppleMusicPlaylist(
            title = header?.str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: "Imported Apple Music Playlist",
            artworkUrl = (header?.get("artwork") as? JsonObject)?.let(::artworkUrl),
            tracks = tracks,
            declaredCount = header?.get("trackCount")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        )
    }

    private fun parseLdJson(raw: String): AppleMusicPlaylist? {
        val obj = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        if (obj.str("@type") != "MusicPlaylist") return null
        val tracks = (obj["track"] as? JsonArray).orEmpty().mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val title = track.str("name")?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val artist = when (val by = track["byArtist"]) {
                is JsonArray -> by.mapNotNull { (it as? JsonObject)?.str("name") }.joinToString(", ")
                is JsonObject -> by.str("name").orEmpty()
                else -> ""
            }
            SpotifyImportTrack(title = title, artist = artist)
        }
        if (tracks.isEmpty()) return null
        return AppleMusicPlaylist(
            title = obj.str("name") ?: "Imported Apple Music Playlist",
            artworkUrl = obj.str("image"),
            tracks = tracks,
            declaredCount = obj["numTracks"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        )
    }

    /** The artwork template (`…/{w}x{h}bb.{f}`) filled in at a size a playlist cover wants. */
    private fun artworkUrl(artwork: JsonObject): String? {
        val dict = artwork["dictionary"] as? JsonObject ?: artwork
        return dict.str("url")?.replace("{w}", "600")?.replace("{h}", "600")?.replace("{f}", "jpg")
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.items(): List<JsonObject> =
        (this["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun findArrays(element: JsonElement, key: String): List<JsonArray> = when (element) {
        is JsonObject -> element.entries.flatMap { (k, v) ->
            if (k == key && v is JsonArray) listOf(v) else findArrays(v, key)
        }
        is JsonArray -> element.flatMap { findArrays(it, key) }
        else -> emptyList()
    }
}
