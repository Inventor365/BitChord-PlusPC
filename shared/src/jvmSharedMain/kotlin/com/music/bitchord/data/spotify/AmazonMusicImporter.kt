package com.music.bitchord.data.spotify

/**
 * Imports an Amazon Music playlist into Sonara through the same pipeline as Spotify and Apple
 * Music: the playlist becomes a list of (title, artist) pairs, and
 * [SpotifyImporter.resolveToSongs] matches each on YouTube Music and reports the ones it
 * couldn't find.
 *
 * **Why the link alone isn't enough.** Amazon publishes no API, embed or oEmbed for playlist
 * contents, and music.amazon.com serves an empty app shell whose tracks arrive later from a
 * private, signed-in player API. Reading that would mean reverse-engineering Amazon's player
 * or asking for the listener's Amazon password, and neither is done here. So an Amazon link
 * is recognised (to say so plainly and to name the playlist kind), and the songs come from
 * text the listener already has:
 *
 * - the track list copied from the playlist page in a browser (music.amazon.com: select the
 *   rows, copy) — blocks of title, artist, album and a duration;
 * - simple lines, `Title - Artist`, `Title by Artist` or tab-separated;
 * - a CSV with title and artist columns, as exported by playlist-transfer tools or from
 *   Amazon's own "Request your data" archive.
 */
object AmazonMusicImporter {

    /** What an Amazon Music link points at. */
    enum class LinkKind { PLAYLIST, USER_PLAYLIST, ALBUM, OTHER }

    data class AmazonLink(val kind: LinkKind, val id: String, val domain: String)

    private val AMAZON_URL = Regex("""https?://(?:www\.)?(?:music\.amazon\.[a-z.]{2,10}(?:/\S*)?|amazon\.[a-z.]{2,10}/music\S*)""", RegexOption.IGNORE_CASE)

    /** The Amazon Music link in [input], if it holds one, or null. */
    fun parseLink(input: String): AmazonLink? {
        val raw = AMAZON_URL.find(input.trim())?.value?.trimEnd('.', ',', ')', '"') ?: return null
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.").orEmpty()
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        // amazon.com/music/player/playlists/<id> is the older form of the same page.
        val tail = if (host.startsWith("amazon.")) segments.dropWhile { it != "playlists" && it != "user-playlists" && it != "albums" } else segments
        val kindWord = tail.getOrNull(0) ?: return AmazonLink(LinkKind.OTHER, "", host)
        val id = tail.getOrNull(1)?.takeIf { ID.matches(it) } ?: return AmazonLink(LinkKind.OTHER, "", host)
        val kind = when (kindWord) {
            "playlists" -> LinkKind.PLAYLIST
            "user-playlists" -> LinkKind.USER_PLAYLIST
            "albums" -> LinkKind.ALBUM
            else -> LinkKind.OTHER
        }
        return AmazonLink(kind, id, host)
    }

    fun isAmazonMusicLink(input: String): Boolean = parseLink(input) != null

    /**
     * The songs in pasted text — a copied Amazon Music track list, `Title - Artist` lines, or a
     * CSV — in order, without exact repeats. Links inside the text are ignored.
     */
    fun parseTracklist(text: String): List<SpotifyImportTrack> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !AMAZON_URL.containsMatchIn(it) && !it.startsWith("http", ignoreCase = true) }
        if (lines.isEmpty()) return emptyList()
        val tracks = parseCsv(lines) ?: parseCopiedPage(lines) ?: parseSimpleLines(lines)
        return tracks
            .map { SpotifyImportTrack(title = cleanTitle(it.title), artist = it.artist.trim()) }
            .filter { it.title.isNotEmpty() }
            .distinct()
    }

    /**
     * Amazon tags titles in its catalogue — "Song [Explicit]", "Song [Clean]" — which no other
     * service prints, and which would cost a match. Trailing duration or track numbers that
     * come along with copied rows go too.
     */
    fun cleanTitle(title: String): String = title
        .replace(TAG_SUFFIX, "")
        .replace(LEADING_INDEX, "")
        .trim()

    // ---- CSV ----

    private fun parseCsv(lines: List<String>): List<SpotifyImportTrack>? {
        val header = splitCsv(lines.first()).map { it.lowercase().trim() }
        val titleCol = header.indexOfFirst { it in TITLE_HEADERS }
        val artistCol = header.indexOfFirst { it in ARTIST_HEADERS }
        if (titleCol < 0 || artistCol < 0) return null
        return lines.drop(1).mapNotNull { line ->
            val cells = splitCsv(line)
            val title = cells.getOrNull(titleCol)?.trim().orEmpty()
            if (title.isEmpty()) null else SpotifyImportTrack(title, cells.getOrNull(artistCol)?.trim().orEmpty())
        }
    }

    /** RFC 4180-ish: commas (or tabs/semicolons when the header used them), quotes doubled. */
    private fun splitCsv(line: String): List<String> {
        val sep = when {
            line.count { it == '\t' } > line.count { it == ',' } -> '\t'
            line.count { it == ';' } > line.count { it == ',' } -> ';'
            else -> ','
        }
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == sep && !quoted -> { out += cell.toString(); cell.clear() }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    // ---- Text copied from the playlist page ----

    /**
     * A copied Amazon Music track list reads as one block per song — title, artist, maybe the
     * album, then its duration ("3:21") — with an index number before each now and then. The
     * duration is the anchor: there is one per song, and nothing else looks like it.
     */
    private fun parseCopiedPage(lines: List<String>): List<SpotifyImportTrack>? {
        val durations = lines.count { DURATION.matches(it) }
        if (durations < 2 || durations < lines.size / 6) return null
        val out = mutableListOf<SpotifyImportTrack>()
        val block = mutableListOf<String>()
        for (line in lines) {
            if (DURATION.matches(line)) {
                val fields = block.filterNot { INDEX_ONLY.matches(it) || it.equals("explicit", true) || it.equals("e", true) }
                if (fields.isNotEmpty()) out += SpotifyImportTrack(fields[0], fields.getOrNull(1).orEmpty())
                block.clear()
            } else {
                block += line
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    // ---- One song per line ----

    private fun parseSimpleLines(lines: List<String>): List<SpotifyImportTrack> = lines.mapNotNull { line ->
        val text = line.replace(LEADING_INDEX, "").trim()
        if (text.isEmpty() || DURATION.matches(text)) return@mapNotNull null
        val tab = text.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
        when {
            tab.size >= 2 -> SpotifyImportTrack(tab[0], tab[1])
            SEPARATOR.containsMatchIn(text) -> {
                val (title, artist) = text.split(SEPARATOR, limit = 2)
                SpotifyImportTrack(title.trim(), artist.trim())
            }
            BY.containsMatchIn(text) -> {
                val (title, artist) = text.split(BY, limit = 2)
                SpotifyImportTrack(title.trim(), artist.trim())
            }
            else -> SpotifyImportTrack(text, "")
        }
    }

    private val ID = Regex("""^[A-Za-z0-9_-]{6,64}$""")
    private val DURATION = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")
    private val INDEX_ONLY = Regex("""^\d{1,4}\.?$""")
    private val LEADING_INDEX = Regex("""^\d{1,4}[.)]\s+""")
    private val TAG_SUFFIX = Regex("""\s*\[(explicit|clean)]\s*$""", RegexOption.IGNORE_CASE)
    private val SEPARATOR = Regex("""\s+[-–—]\s+""")
    private val BY = Regex("""\s+by\s+""", RegexOption.IGNORE_CASE)
    private val TITLE_HEADERS = setOf("title", "track", "track name", "track title", "song", "song name", "name")
    private val ARTIST_HEADERS = setOf("artist", "artists", "artist name", "artist name(s)", "artist(s)", "performer")
}
