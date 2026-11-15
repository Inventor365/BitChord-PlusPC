package com.music.bitchord.data.playlist

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Portable playlist import/export across M3U (`.m3u` / `.m3u8`), JSON (`.json`), and CSV (`.csv`).
 */
object PlaylistExchange {

    @Serializable
    data class PortableTrack(
        val videoId: String,
        val title: String,
        val artist: String,
        val albumName: String? = null,
        val thumbnailUrl: String? = null,
        val durationText: String? = null,
        val isExplicit: Boolean? = null,
        val contributorName: String? = null,
        val addedAtMs: Long? = null,
    )

    @Serializable
    data class PortablePlaylist(
        val format: String = "bitchord.playlist.v1",
        val title: String,
        val description: String = "",
        val artworkUrl: String? = null,
        val tracks: List<PortableTrack> = emptyList(),
    )

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun exportM3u(title: String, songs: List<Song>): String = buildString {
        appendLine("#EXTM3U")
        if (title.isNotBlank()) {
            appendLine("#PLAYLIST:${title.replace('\n', ' ').trim()}")
        }
        for (song in songs) {
            val seconds = (song.durationText.durationMillis() / 1_000L).takeIf { it > 0L } ?: -1L
            val label = if (song.artist.isNotBlank()) "${song.artist} - ${song.title}" else song.title
            appendLine("#EXTINF:$seconds,${label.replace('\n', ' ').trim()}")
            val target = when {
                !song.localPath.isNullOrBlank() -> song.localPath
                !song.localUri.isNullOrBlank() -> song.localUri
                else -> "https://music.youtube.com/watch?v=${song.videoId}"
            }
            appendLine(target)
        }
    }

    fun exportJson(
        title: String,
        songs: List<Song>,
        description: String = "",
        artworkUrl: String? = null,
    ): String {
        val payload = PortablePlaylist(
            title = title,
            description = description,
            artworkUrl = artworkUrl,
            tracks = songs.map { s ->
                PortableTrack(
                    videoId = s.videoId,
                    title = s.title,
                    artist = s.artist,
                    albumName = s.albumName,
                    thumbnailUrl = s.thumbnailUrl,
                    durationText = s.durationText,
                    isExplicit = s.isExplicit,
                )
            },
        )
        return json.encodeToString(payload)
    }

    fun exportCsv(songs: List<Song>): String = buildString {
        appendLine("Title,Artist,Album,YouTube Video ID")
        for (song in songs) {
            appendLine(
                listOf(
                    csvCell(song.title),
                    csvCell(song.artist),
                    csvCell(song.albumName.orEmpty()),
                    csvCell(song.videoId),
                ).joinToString(","),
            )
        }
    }

    /**
     * Auto-detects JSON, M3U/M3U8, or CSV playlist content and returns a [CsvPlaylist].
     */
    fun parseAny(content: String, defaultTitle: String? = null): CsvPlaylist {
        val trimmed = content.trim().trimStart('\uFEFF')
        if (trimmed.isEmpty()) return CsvPlaylist(title = defaultTitle, songs = emptyList())
        if (trimmed.startsWith("{")) {
            runCatching {
                val parsed = json.decodeFromString<PortablePlaylist>(trimmed)
                val songs = parsed.tracks.mapNotNull { t ->
                    if (t.videoId.isBlank() && t.title.isBlank()) return@mapNotNull null
                    val vid = t.videoId.ifBlank {
                        "json_${t.title.hashCode().toUInt().toString(16)}"
                    }
                    Song(
                        videoId = vid,
                        title = t.title.ifBlank { vid },
                        artist = t.artist.ifBlank { "Unknown Artist" },
                        albumName = t.albumName,
                        thumbnailUrl = t.thumbnailUrl ?: vid.takeIf { it.length == 11 }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
                        durationText = t.durationText,
                        isExplicit = t.isExplicit,
                    )
                }
                return CsvPlaylist(
                    title = parsed.title.ifBlank { defaultTitle },
                    songs = songs,
                )
            }
        }
        if (trimmed.startsWith("#EXTM3U") || trimmed.lineSequence().any { it.trim().startsWith("#EXTINF:") }) {
            return parseM3u(trimmed, defaultTitle)
        }
        return CsvPlaylistParser.parse(trimmed, defaultTitle)
    }

    fun parseM3u(content: String, defaultTitle: String? = null): CsvPlaylist {
        var playlistTitle = defaultTitle
        var pendingInfo: Pair<String?, Int?>? = null
        val songs = mutableListOf<Song>()

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#PLAYLIST:", ignoreCase = true) -> {
                    val t = line.substringAfter(':').trim()
                    if (t.isNotEmpty()) playlistTitle = t
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val body = line.substringAfter(':')
                    val secPart = body.substringBefore(',').trim().toIntOrNull()
                    val labelPart = body.substringAfter(',', "").trim().takeIf { it.isNotEmpty() }
                    pendingInfo = labelPart to secPart
                }
                line.startsWith("#") -> Unit
                else -> {
                    val vid = CsvPlaylistParser.extractVideoId(line)
                    val infoLabel = pendingInfo?.first
                    val sec = pendingInfo?.second
                    pendingInfo = null

                    val (artist, title) = if (infoLabel != null && " - " in infoLabel) {
                        val a = infoLabel.substringBefore(" - ").trim()
                        val t = infoLabel.substringAfter(" - ").trim()
                        a to t
                    } else {
                        "Unknown Artist" to (infoLabel ?: line.substringAfterLast('/').substringBeforeLast('.').ifBlank { vid })
                    }

                    val durationText = sec?.takeIf { it > 0 }?.let { total ->
                        val h = total / 3600
                        val m = (total % 3600) / 60
                        val s = total % 60
                        if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
                        else "$m:${s.toString().padStart(2, '0')}"
                    }

                    val isLocal = vid.isEmpty() && (line.startsWith("/") || line.startsWith("file://") || line.startsWith("content://"))
                    val effectiveId = when {
                        vid.isNotEmpty() -> vid
                        isLocal -> "local_${line.hashCode().toUInt().toString(16)}"
                        title.isNotBlank() -> "m3u_${songs.size + 1}_${title.hashCode().toUInt().toString(16)}"
                        else -> continue
                    }
                    songs += Song(
                        videoId = effectiveId,
                        title = title.ifBlank { effectiveId },
                        artist = artist,
                        thumbnailUrl = if (vid.isNotEmpty()) "https://i.ytimg.com/vi/$vid/hqdefault.jpg" else null,
                        durationText = durationText,
                        localPath = line.takeIf { line.startsWith("/") },
                        localUri = line.takeIf { line.startsWith("file://") || line.startsWith("content://") },
                    )
                }
            }
        }
        return CsvPlaylist(title = playlistTitle, songs = songs)
    }

    private fun csvCell(value: String): String {
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            return "\"${value.replace("\"", "\"\"")}\""
        }
        return value
    }
}
