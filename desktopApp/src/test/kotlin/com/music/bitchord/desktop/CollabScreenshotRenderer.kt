package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.music.bitchord.data.collab.CollabManager
import com.music.bitchord.ui.collab.CollabEditDetailsDialog
import com.music.bitchord.ui.collab.CollabInviteAndManageDialog
import com.music.bitchord.ui.collab.CollabPlaylistDetailScreen
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Renders the collaborative playlist screens to PNG, off screen, for the README and release
 * notes. Not a test of anything: it only runs when asked,
 *
 *     ./gradlew :desktopApp:test --tests '*CollabScreenshotRenderer*' \
 *         -Dbitchord.renderScreenshots=docs/screenshots
 *
 * The screens are the real composables drawn against the app's own colour scheme. The playlist
 * in them is sample data read from a state file, and its artwork is generated here, so nothing
 * leaves the machine and nothing copyrighted is drawn.
 */
class CollabScreenshotRenderer {

    @Test
    fun renderCollaborativePlaylistScreens() {
        val outDir = System.getProperty("bitchord.renderScreenshots")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val target = if (outDir.isAbsolute) outDir else File("..", outDir.path)
        target.mkdirs()

        val art = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        art.createContext("/art/") { exchange ->
            val seed = exchange.requestURI.path.substringAfterLast('/').substringBefore('.').hashCode()
            val bytes = cover(seed)
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        art.createContext("/") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        art.start()
        val origin = "http://localhost:${art.address.port}"

        try {
            val stateFile = File.createTempFile("collab_screens", ".json").apply { writeText(sampleState(origin)) }
            val manager = CollabManager(storageFileProvider = { stateFile })
            manager.initialize()
            repeat(100) {
                if (manager.details.value.isNotEmpty() && manager.session.value != null) return@repeat
                Thread.sleep(50)
            }
            val detail = manager.details.value.getValue(PLAYLIST_ID)

            shoot(File(target, "collab-playlist.png"), 430, 932) {
                CollabPlaylistDetailScreen(
                    playlistId = PLAYLIST_ID,
                    manager = manager,
                    currentPlayingVideoId = detail.tracks[1].trackId,
                    onBack = {},
                    onPlaySongs = { _, _, _, _ -> },
                    onOpenAuthDialog = {},
                    contentPadding = PaddingValues(bottom = 24.dpValue()),
                    onEditCover = {},
                )
            }
            shoot(File(target, "collab-members.png"), 430, 932) {
                CollabInviteAndManageDialog(detail = detail, manager = manager, initialTab = 1, onDismiss = {}, onDeletedOrLeft = {}, onEditDetails = {})
            }
            shoot(File(target, "collab-edit.png"), 430, 932) {
                CollabEditDetailsDialog(detail = detail, manager = manager, onEditCover = {}, onDismiss = {})
            }
        } finally {
            art.stop(0)
        }
    }

    private fun Int.dpValue() = androidx.compose.ui.unit.Dp(this.toFloat())

    private fun shoot(file: File, width: Int, height: Int, content: @Composable () -> Unit) {
        val scale = 2f
        val scene = ImageComposeScene(
            width = (width * scale).toInt(),
            height = (height * scale).toInt(),
            density = Density(scale),
        ) {
            MaterialTheme(colorScheme = desktopColorScheme()) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
        try {
            // Artwork loads asynchronously; keep drawing frames until it has had time to arrive.
            var image = scene.render(0)
            val start = System.nanoTime()
            while (System.nanoTime() - start < 4_000_000_000L) {
                runBlocking { kotlinx.coroutines.delay(100) }
                image = scene.render(System.nanoTime() - start)
            }
            file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("rendered ${file.absolutePath}")
        } finally {
            scene.close()
        }
    }

    /** A two-colour gradient with a soft disc on it: enough to read as a record sleeve. */
    private fun cover(seed: Int): ByteArray {
        val palette = listOf(
            0xFF6B6B to 0x4D2C91, 0x00C2A8 to 0x0B3D91, 0xFFB347 to 0xC2185B, 0x7F7FD5 to 0x1B1464,
            0x43CEA2 to 0x185A9D, 0xF857A6 to 0x5F0A87, 0xFFD166 to 0xEF476F, 0x56CCF2 to 0x2F3C7E,
        )
        val (a, b) = palette[Math.floorMod(seed, palette.size)]
        val size = 480
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            paint = GradientPaint(0f, 0f, java.awt.Color(a), size.toFloat(), size.toFloat(), java.awt.Color(b))
            fillRect(0, 0, size, size)
            color = java.awt.Color(255, 255, 255, 46)
            val d = size * (40 + Math.floorMod(seed / 7, 35)) / 100
            fillOval(size - d - Math.floorMod(seed / 3, 120), Math.floorMod(seed / 5, 140), d, d)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private fun sampleState(origin: String): String {
        val people = listOf("Pranav" to "u_pranav", "Saksham" to "u_saksham", "Ananya" to "u_ananya")
        val songs = listOf(
            "Northern Lights" to "Paper Kites", "Slow Tide" to "Marlowe", "Glasshouse" to "Idle Hours",
            "Seventeen Miles" to "The Low Suns", "Afterglow Avenue" to "Nova Reyes", "Hollow Coast" to "Wren & Co.",
            "Paper Planes at Dusk" to "Kite Season", "Undertow" to "Salt Harbour",
        )
        fun q(s: String) = "\"" + s.replace("\"", "\\\"") + "\""
        val members = people.mapIndexed { i, (name, id) ->
            """{"playlistId":${q(PLAYLIST_ID)},"userId":${q(id)},"role":${q(if (i == 0) "OWNER" else "EDITOR")},"displayName":${q(name)},"avatarUrl":${q("$origin/art/avatar$i.png")}}"""
        }
        val tracks = songs.mapIndexed { i, (title, artist) ->
            val (name, id) = people[i % people.size]
            """{"entryId":"e$i","playlistId":${q(PLAYLIST_ID)},"position":$i,"trackId":"track_%05d","title":${q(title)},"artist":${q(artist)},"thumbnailUrl":${q("$origin/art/song$i.png")},"durationMs":${180_000 + i * 11_000},"contributorId":${q(id)},"contributorName":${q(name)},"contributorAvatarUrl":${q("$origin/art/avatar${i % people.size}.png")},"addedAtMs":1}""".format(i)
        }
        val activities = listOf("Saksham added Slow Tide", "Ananya joined via invite link", "Pranav renamed the playlist to \"Road Trip, Windows Down\"")
            .mapIndexed { i, text -> """{"id":"a$i","playlistId":${q(PLAYLIST_ID)},"actorName":${q(text.substringBefore(' '))},"action":"track.add","summary":${q(text)},"createdAtMs":1}""" }
        val common = """"id":${q(PLAYLIST_ID)},"title":"Road Trip, Windows Down","description":"Everyone adds three. No skips.","artworkUrl":${q("$origin/art/cover.png")},"ownerUserId":"u_pranav","ownerName":"Pranav","ownerAvatarUrl":${q("$origin/art/avatar0.png")},"myRole":"OWNER","sharingEnabled":true,"memberCount":3,"memberAvatars":[${(0..2).joinToString(",") { q("$origin/art/avatar$it.png") }}],"trackCount":${songs.size},"revision":4,"updatedAtMs":1"""
        return """
            {"version":2,"serverUrlOverride":${q(origin)},
             "customServerSession":{"token":"sample","expiresAtMs":4102444800000,"user":{"id":"u_pranav","email":"pranav@example.com","displayName":"Pranav","avatarUrl":${q("$origin/art/avatar0.png")}}},
             "playlists":[{$common}],
             "details":{${q(PLAYLIST_ID)}:{$common,"members":[${members.joinToString(",")}],"tracks":[${tracks.joinToString(",")}],"activities":[${activities.joinToString(",")}]}}}
        """.trimIndent()
    }

    private companion object {
        const val PLAYLIST_ID = "cpl_sample_roadtrip"
    }
}
