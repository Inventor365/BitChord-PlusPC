package com.music.bitchord.desktop

import com.music.bitchord.data.collab.CollabManager
import com.music.bitchord.data.collab.toCollabUserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Desktop singleton wiring for BitChord Collaborative Playlists.
 * Defaults `defaultServerUrl` to `bitchord.collab.server` (`""` unless explicitly configured), so
 * signing into BitChord with Google immediately enables Collaborative Playlists out of the box.
 */
internal object DesktopCollab {

    /**
     * Where the collaborative-playlist state lives. The folder keeps BitChord's name on every
     * platform, so an install upgraded from BitChord PlusPC finds the playlists, invite links and
     * contributor stamps it already had. Sonara 2.0.0 and 2.1.0 wrote Windows' copy to a
     * `Sonara` folder instead; a state file found only there is still read.
     */
    private val storageFile: File by lazy {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val home = System.getProperty("user.home").orEmpty()
        val baseDir = when {
            "mac" in os -> File(home, "Library/Application Support/BitChord")
            "win" in os -> {
                val roaming = System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: "$home/AppData/Roaming"
                val legacy = File(roaming, "BitChord")
                val renamed = File(roaming, "Sonara")
                if (!File(legacy, STATE_FILE).isFile && File(renamed, STATE_FILE).isFile) renamed else legacy
            }
            else -> File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "bitchord")
        }
        File(baseDir, STATE_FILE)
    }

    private const val STATE_FILE = "collab_state.json"

    val manager: CollabManager by lazy {
        CollabManager(
            storageFileProvider = { storageFile },
            defaultServerUrl = {
                System.getProperty("bitchord.collab.server")
                    ?.trim()
                    ?.takeUnless { it.contains("bitchord-listen-together.onrender.com", ignoreCase = true) }
                    .orEmpty()
            },
            defaultGoogleClientId = {
                System.getProperty("bitchord.collab.googleClientId").orEmpty()
            },
        )
    }

    fun init() {
        manager.initialize()
    }

    /**
     * Lets the owner choose a picture for a collaborative playlist's cover and uploads it.
     * Blocks on the system file dialog, so call it off the UI thread. Returns a sentence to
     * show when it did not work, or null when it did or the dialog was cancelled.
     */
    suspend fun pickAndUploadCover(playlistId: String): String? = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, "Choose a playlist cover", FileDialog.LOAD).apply {
            setFilenameFilter { _, name ->
                name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "bmp", "gif")
            }
            isVisible = true
        }
        val name = dialog.file ?: return@withContext null
        val image = runCatching { coverBytes(File(dialog.directory, name)) }.getOrNull()
            ?: return@withContext "That file couldn't be read as a picture. Choose a JPEG or PNG."
        manager.setPlaylistCover(playlistId, image).fold(
            onSuccess = { null },
            onFailure = { it.toCollabUserMessage("The cover couldn't be uploaded. Try again in a moment.") },
        )
    }

    /**
     * The picture as YouTube Music takes it: a JPEG or PNG under the upload limit. A file that
     * already is one goes up untouched; anything else — or anything too large — is redrawn as a
     * JPEG no wider or taller than [MAX_COVER_EDGE].
     */
    private fun coverBytes(file: File): ByteArray? {
        val raw = file.readBytes()
        val isJpeg = raw.size >= 3 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte() && raw[2] == 0xFF.toByte()
        val isPng = raw.size >= 8 && raw[0] == 0x89.toByte() && raw[1] == 0x50.toByte() &&
            raw[2] == 0x4E.toByte() && raw[3] == 0x47.toByte()
        if ((isJpeg || isPng) && raw.size <= MAX_COVER_UPLOAD_BYTES) return raw
        val source = ImageIO.read(raw.inputStream()) ?: return null
        val scale = minOf(1.0, MAX_COVER_EDGE.toDouble() / maxOf(source.width, source.height))
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())
        val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        canvas.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            drawImage(source, 0, 0, width, height, java.awt.Color.BLACK, null)
            dispose()
        }
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(canvas, "jpg", out)) return null
        return out.toByteArray().takeIf { it.isNotEmpty() && it.size <= MAX_COVER_UPLOAD_BYTES }
    }

    private const val MAX_COVER_EDGE = 1_500
    private const val MAX_COVER_UPLOAD_BYTES = 8 * 1024 * 1024
}
