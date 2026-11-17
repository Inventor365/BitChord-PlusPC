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

    suspend fun fetchPlaylist(url: String): AppleMusicPlaylist = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        val html = Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 404) error("Apple Music couldn't find that playlist. Check that it is shared publicly.")
            if (!response.isSuccessful || body.isBlank()) error("Failed to load Apple Music playlist (HTTP ${response.code})")
            body
        }
        parsePage(html) ?: error("Could not read that Apple Music playlist. Make sure it is shared publicly.")
    }

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
