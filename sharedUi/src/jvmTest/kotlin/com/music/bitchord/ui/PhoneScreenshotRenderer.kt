package com.music.bitchord.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.screens.HomeScreen
import com.music.bitchord.ui.screens.SearchScreen
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import com.music.bitchord.data.settings.LibrarySort
import com.music.bitchord.ui.components.ExplicitPrompt
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import com.sun.net.httpserver.HttpServer
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Renders the phone's Home and Search pages to PNG, off screen, for the README and the
 * release notes. Not a test of anything: it only runs when asked,
 *
 *     ./gradlew :sharedUi:jvmTest --tests '*PhoneScreenshotRenderer*' \
 *         -Dbitchord.renderScreenshots=docs/screenshots
 *
 * The pages are the real composables in the phone's dark scheme and its SF Pro type. The
 * shelves are sample data with no artwork, so nothing leaves the machine.
 */
@OptIn(ExperimentalMaterial3Api::class)
class PhoneScreenshotRenderer {

    @Test
    fun renderPhoneScreens() {
        val outDir = System.getProperty("bitchord.renderScreenshots")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val target = if (outDir.isAbsolute) outDir else File("..", outDir.path)
        target.mkdirs()
        AppUi.install(object : AppUiHost {
            override val swipeToPlayNext = MutableStateFlow(false)
            override val pinnedPlaylists = MutableStateFlow(emptyList<String>())
            override val librarySort = MutableStateFlow(LibrarySort.entries.first())
            override val blockExplicit = MutableStateFlow(false)
            override val explicitAllowed = MutableStateFlow(emptySet<String>())
            override fun askExplicitBlocked(prompt: ExplicitPrompt) = Unit

            @Composable
            override fun downloadedIds(): Set<String> = emptySet()
        })

        val art = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        art.createContext("/art/") { exchange ->
            val bytes = cover(exchange.requestURI.path.substringAfterLast('/').substringBefore('.').hashCode())
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        art.start()
        origin = "http://localhost:${art.address.port}"
        try {
            render(target)
        } finally {
            art.stop(0)
        }
    }

    private var origin = ""

    private fun render(target: File) {
        val shelves = listOf(
            HomeShelf("Quick picks", sampleItems("Northern Lights", "Slow Tide", "Glasshouse", "Undertow"), subtitle = "Start radio from a song"),
            HomeShelf("Your playlists", sampleItems("Road Trip, Windows Down", "Late Night Drive", "Focus"), showAll = true),
            HomeShelf("New releases", sampleItems("Afterglow Avenue", "Hollow Coast", "Paper Planes")),
        )
        shoot(File(target, "home.png")) {
            HomeScreen(
                state = UiState.Success(shelves),
                listState = rememberLazyListState(),
                onItemClick = { _, _ -> },
                onRetry = {},
                refreshing = false,
                onRefresh = {},
                pullState = rememberPullToRefreshState(),
                contentPadding = PaddingValues(top = 72.dp, bottom = 24.dp),
                title = "Listen now",
                listenerName = "Pranav",
                onShelfShowAll = {},
            )
        }
        val categories = listOf(
            "Chill" to 0xFF5EC2B7, "Workout" to 0xFFE85D4A, "Focus" to 0xFF6C7CE7, "Party" to 0xFFE6A23C,
            "Romance" to 0xFFE0607E, "Sleep" to 0xFF4B5D9E, "Feel good" to 0xFF8BD17C, "Commute" to 0xFFB57EDC,
        ).map { (title, argb) -> MoodGenre(title = title, browseId = "FEmusic_moods_and_genres_category", params = title, stripeColor = argb) }
        shoot(File(target, "search.png")) {
            SearchScreen(
                query = "",
                onQueryChange = {},
                filter = SearchFilter.ALL,
                currentSong = null,
                isPlaying = false,
                onFilterChange = {},
                results = null,
                loadingMore = false,
                onLoadMore = {},
                listState = rememberLazyListState(),
                scrollResetTrigger = 0,
                focusRequested = false,
                onFocusHandled = {},
                onSongClick = { _, _ -> },
                onSongLongPress = {},
                onSongSwipe = {},
                onTopResultPlay = {},
                onTopResultPlaylist = {},
                onBrowseClick = {},
                history = emptyList(),
                suggestions = emptyList(),
                typeaheadResults = emptyList(),
                onSubmit = {},
                onSuggestionClick = {},
                onHistoryClick = {},
                onHistoryRemove = {},
                onHistoryClear = {},
                contentPadding = PaddingValues(bottom = 24.dp),
                topPadding = 64.dp,
                browseCategories = categories,
            )
        }
    }

    private fun sampleItems(vararg titles: String) = titles.mapIndexed { i, title ->
        ShelfItem(title = title, subtitle = listOf("Paper Kites", "Marlowe", "Idle Hours", "Nova Reyes")[i % 4], thumbnailUrl = "$origin/art/${title.filter(Char::isLetter)}.png", videoId = "v$i$title", browseId = null)
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

    private fun shoot(file: File, content: @Composable () -> Unit) {
        val scale = 2.5f
        val width = 412
        val height = 892
        val scene = ImageComposeScene(
            width = (width * scale).toInt(),
            height = (height * scale).toInt(),
            density = Density(scale),
        ) {
            MaterialTheme(colorScheme = phoneDark, typography = phoneTypography()) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
        try {
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

    private val phoneDark = darkColorScheme(
        primary = Color.White,
        onPrimary = Color.Black,
        background = Color.Black,
        onBackground = Color.White,
        surface = Color(0xFF0D0D0F),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF1C1C1E),
        onSurfaceVariant = Color(0xFF8E8E93),
        outline = Color(0xFF2C2C2E),
    )

    /** The phone's type scale, set in the SF Pro Display it bundles. */
    private fun phoneTypography(): Typography {
        val fonts = File("../app/src/main/res/font")
        val family = FontFamily(
            Font(File(fonts, "sf_pro_display_regular.otf"), FontWeight.W400),
            Font(File(fonts, "sf_pro_display_medium.otf"), FontWeight.W500),
            Font(File(fonts, "sf_pro_display_semibold.otf"), FontWeight.W600),
            Font(File(fonts, "sf_pro_display_bold.otf"), FontWeight.W700),
            Font(File(fonts, "sf_pro_display_heavy.otf"), FontWeight.W800),
        )
        fun t(w: Int, size: Int, spacing: Float = 0f) =
            TextStyle(fontFamily = family, fontWeight = FontWeight(w), fontSize = size.sp, letterSpacing = spacing.sp)
        val d = Typography()
        return d.copy(
            displayLarge = t(800, 34, -0.8f),
            headlineLarge = t(800, 30, -0.7f),
            headlineMedium = t(700, 22, -0.4f),
            titleLarge = t(700, 20, -0.3f),
            titleMedium = t(600, 16, -0.2f),
            titleSmall = t(600, 14),
            bodyLarge = t(400, 16),
            bodyMedium = t(400, 14),
            bodySmall = t(400, 12),
            labelLarge = t(600, 14),
            labelMedium = t(600, 12),
            labelSmall = t(600, 11),
        )
    }
}
