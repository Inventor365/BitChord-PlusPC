package com.music.bitchord.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The app's looks. Each is one [ThemeTokens] — plus, on Android, a colour scheme and an icon —
 * so a new theme is one more entry here and its assets.
 *
 * The keys are what is stored: never rename one.
 */
enum class ThemeId(val key: String) {
    SONARA("sonara"),
    VICE("vice"),
    DOOM("doom"),
    WINTER("winter"),
    ;

    /** The theme's main look: its only one, or for Winter the polar night. */
    val tokens: ThemeTokens
        get() = tokens(dark = true)

    /** The look for a dark or light app: only Winter (and Sonara, by its colours) has both. */
    fun tokens(dark: Boolean): ThemeTokens = when (this) {
        SONARA -> ThemeTokens.Sonara
        VICE -> ThemeTokens.Vice
        DOOM -> ThemeTokens.Doom
        WINTER -> if (dark) ThemeTokens.WinterNight else ThemeTokens.WinterDay
    }

    companion object {
        fun fromKey(key: String?): ThemeId = entries.firstOrNull { it.key == key } ?: SONARA
    }
}

/** The picture a theme lays behind the top of every page. */
enum class ThemeMotif {
    /** Nothing: the page's own background. */
    NONE,

    /** A banded synthwave sun going down behind two palm silhouettes, over a horizon grid. */
    SUNSET,

    /** Forged iron, an engraved arcane dial with an emerald glow, a riveted seam. */
    CREST,

    /** Polar night: stars, the aurora and an ice shelf against the sky. */
    AURORA,

    /** Winter day: a pale sky, layered glaciers and an ice shelf. */
    GLACIER,
}

/** How a theme edges its cards, tiles and bars. */
enum class ThemeEdge {
    /** As the app always has: nothing beyond the shape. */
    NONE,

    /** Brushed metal: a soft light across the top of the surface, no outline (Doom). */
    FORGED,

    /** Frost creeping in at the corners (Winter). */
    FROST,
}

/** The fine surface texture of a theme's cards and hero. */
enum class ThemeTexture {
    /** A dotted grid, as on the Sonara Pass. */
    DOTS,

    /** A receding retro grid. */
    GRID,

    /** Brushed-metal hairlines and rivets. */
    RIVETS,

    /** Frost: a pale bloom and crystals at the corners. */
    FROST,
}

/**
 * Everything a component reads to look native to a theme. Components take these instead of
 * hard-coded colours or shapes; the Material colour scheme carries the rest.
 *
 * [Sonara] reproduces the app exactly as it looked before themes, value for value.
 */
@Immutable
data class ThemeTokens(
    val id: ThemeId,
    /** Vice and Doom are designed for the dark; they ignore the Light setting. */
    val darkOnly: Boolean,
    /** The signature colour: buttons, switches, the mark, the active tab. */
    val accent: Color,
    /** Second accent, for gradients and highlights. */
    val accent2: Color,
    /** Third accent, used sparingly (a single highlight, a grid line). */
    val accent3: Color,
    /** A deep shade of the accent, for the far end of gradients. */
    val accentDeep: Color,
    /** Text and glyphs drawn on [accent]. */
    val onAccent: Color,
    /** The darkest surface the theme paints (welcome screen, hero floor). */
    val ink: Color,
    /** The Sonara Pass card's gradient, top-left to bottom-right. */
    val passGradient: List<Color>,
    /** Behind the top of every page: from the top edge down to the page's background. Empty: flat. */
    val backdropGradient: List<Color>,
    /** A soft glow in the hero, or null. */
    val heroGlow: Color?,
    val motif: ThemeMotif,
    val texture: ThemeTexture,
    /** Laid over Now Playing's artwork backdrop, top to bottom. Empty: none. */
    val playerTint: List<Color>,
    /** The played part of the progress bar, left to right. Empty: the player's own white. */
    val progressColors: List<Color>,
    /** The bloom behind the line being sung, or null for the player's own white. */
    val lyricGlow: Color?,
    /** A soft neon glow around accented controls, or null. */
    val glow: Color?,
    /** Cards and tiles. */
    val cardShape: Shape,
    /** Buttons, the pills of segmented controls, the tab bar's selection. */
    val controlShape: Shape,
    /** Small chips and badges. */
    val chipShape: Shape,
    /** Display and headline type; null keeps the app's own face. */
    val headingFamily: FontFamily? = null,
    /** A soft diagonal sheen over Now Playing, as light on velvet; null for none. */
    val playerSheen: Color? = null,
    /** How cards, bars and tiles are edged: see [ThemeEdge]. */
    val edge: ThemeEdge = ThemeEdge.NONE,
    /**
     * Chamfered rather than rounded: corners cut, not curved. Every theme today is rounded — Doom
     * was cut once and read as fussy — but the shapes still honour it for a theme that wants it.
     */
    val angular: Boolean = false,
    /** The theme has animated layers (aurora, snow, stars) and the effect switches that go with them. */
    val animated: Boolean = false,
    /**
     * Page titles over the theme's hero (Home's greeting, Library, Settings, Explore, Search): their
     * colour, the subtitle's under them, and a soft glow behind both, chosen to sit naturally on
     * the hero art rather than as page text dropped on a picture. Null keeps the scheme's colours.
     */
    val heroTitle: Color? = null,
    val heroSubtitle: Color? = null,
    val heroShadow: Color? = null,
) {

    companion object {
        val WinterNight: ThemeTokens get() = WinterNightTokens
        val WinterDay: ThemeTokens get() = WinterDayTokens

        val Sonara = ThemeTokens(
            id = ThemeId.SONARA,
            darkOnly = false,
            accent = Color(0xFFFF2D55),
            accent2 = Color(0xFFFF6B81),
            accent3 = Color(0xFFFF9AAB),
            accentDeep = Color(0xFF5C0A1C),
            onAccent = Color.White,
            ink = Color(0xFF0A0A0C),
            passGradient = listOf(Color(0xFF2A0B14), Color(0xFF111114), Color(0xFF18181C)),
            backdropGradient = emptyList(),
            heroGlow = null,
            motif = ThemeMotif.NONE,
            texture = ThemeTexture.DOTS,
            playerTint = emptyList(),
            progressColors = emptyList(),
            lyricGlow = null,
            glow = null,
            cardShape = RoundedCornerShape(26.dp),
            controlShape = CircleShape,
            chipShape = CircleShape,
        )

        /** Miami at dusk: violet-black night, hot pink and sunset orange, a cyan edge. */
        val Vice = ThemeTokens(
            id = ThemeId.VICE,
            darkOnly = true,
            accent = ViceColors.HotPink,
            accent2 = ViceColors.Sunset,
            accent3 = ViceColors.Cyan,
            accentDeep = Color(0xFF3B0A45),
            onAccent = Color(0xFF1A0010),
            ink = ViceColors.Night,
            passGradient = listOf(Color(0xFF3A0D4F), Color(0xFF1A0B33), ViceColors.Night),
            backdropGradient = listOf(Color(0xFF2A0E45), Color(0xFF170A2B), ViceColors.Night),
            heroGlow = ViceColors.HotPink,
            motif = ThemeMotif.SUNSET,
            texture = ThemeTexture.GRID,
            playerTint = listOf(Color(0xFF2A0B40).copy(alpha = 0.34f), Color.Transparent, ViceColors.HotPink.copy(alpha = 0.16f)),
            progressColors = listOf(ViceColors.HotPink, ViceColors.Sunset),
            lyricGlow = ViceColors.HotPink,
            glow = ViceColors.HotPink,
            // Warm white with a neon-pink glow over the sunset; the subtitle in soft pink.
            heroTitle = Color(0xFFFFF4F9),
            heroSubtitle = Color(0xFFFFC2E2),
            heroShadow = ViceColors.HotPink.copy(alpha = 0.55f),
            cardShape = RoundedCornerShape(28.dp),
            controlShape = CircleShape,
            chipShape = CircleShape,
        )

        /** Latverian steel: gunmetal and black, a toxic emerald glow, cold brass. */
        val Doom = ThemeTokens(
            id = ThemeId.DOOM,
            darkOnly = true,
            accent = DoomColors.Emerald,
            accent2 = DoomColors.Brass,
            accent3 = DoomColors.Steel,
            accentDeep = Color(0xFF0A3320),
            onAccent = Color(0xFF00170A),
            ink = DoomColors.Void,
            passGradient = listOf(Color(0xFF1A2A22), Color(0xFF12171B), DoomColors.Void),
            backdropGradient = listOf(Color(0xFF13221B), Color(0xFF0C1013), DoomColors.Void),
            heroGlow = DoomColors.Emerald,
            motif = ThemeMotif.CREST,
            texture = ThemeTexture.RIVETS,
            // Cape-green velvet: deep green falling to near black, with a sheen across it.
            playerTint = listOf(
                Color(0xFF0D3B25).copy(alpha = 0.55f),
                Color(0xFF07200F).copy(alpha = 0.30f),
                Color(0xFF02100A).copy(alpha = 0.62f),
            ),
            playerSheen = Color(0xFF6CFFB5),
            edge = ThemeEdge.FORGED,
            progressColors = listOf(DoomColors.Emerald, Color(0xFF8CFFC0)),
            lyricGlow = DoomColors.Emerald,
            glow = DoomColors.Emerald,
            // Pale emerald-white with a green glow over the crest; the subtitle in sage.
            heroTitle = Color(0xFFEAFFF3),
            heroSubtitle = Color(0xFFA6DCBE),
            heroShadow = DoomColors.Emerald.copy(alpha = 0.45f),
            cardShape = RoundedCornerShape(24.dp),
            controlShape = CircleShape,
            chipShape = CircleShape,
        )
    }
}

/** Winter's palette. */
object WinterColors {
    // Night
    val Polar = Color(0xFF050B1A)
    val NightSky = Color(0xFF0B1E3D)
    val NightSurface = Color(0xFF0E1A31)
    val NightSurfaceHigh = Color(0xFF162744)
    val Ice = Color(0xFF9FDCFF)
    val AuroraGreen = Color(0xFF5CF2B5)
    val AuroraTeal = Color(0xFF3FC8E8)
    val AuroraViolet = Color(0xFFA48BFA)
    val Frost = Color(0xFFDDF3FF)
    // Day
    val Snow = Color(0xFFF6FAFE)
    val SnowSurface = Color(0xFFFFFFFF)
    val Glacier = Color(0xFFE3F0FA)
    val GlacierDeep = Color(0xFFBFDDF2)
    val IceBlue = Color(0xFF1D6FC0)
    val Navy = Color(0xFF0C2340)
    val NavySoft = Color(0xFF3F5975)
}

/** Vice's palette. */
object ViceColors {
    val Night = Color(0xFF0B0716)
    val Surface = Color(0xFF150D27)
    val SurfaceHigh = Color(0xFF221539)
    val Outline = Color(0xFF3D2A5C)
    val HotPink = Color(0xFFFF3EA5)
    val Sunset = Color(0xFFFF8A3D)
    val Sun = Color(0xFFFFD166)
    val Cyan = Color(0xFF2DE2E6)
    val Violet = Color(0xFF8B5CF6)
    val Lilac = Color(0xFFB9A7D9)
}

/** Doom's palette. */
object DoomColors {
    val Void = Color(0xFF07090A)
    val Gunmetal = Color(0xFF121619)
    val Plate = Color(0xFF1C2227)
    val Outline = Color(0xFF34404A)
    val Emerald = Color(0xFF2BE07F)
    val Brass = Color(0xFFC9A75A)
    val Steel = Color(0xFFA9B6C1)
    val Ash = Color(0xFF8A969F)
}

private val WinterShape = RoundedCornerShape(24.dp)

/** Polar night: navy to black, the aurora's greens and violets, ice for the accent. */
private val WinterNightTokens = ThemeTokens(
    id = ThemeId.WINTER,
    darkOnly = false,
    accent = WinterColors.Ice,
    accent2 = WinterColors.AuroraGreen,
    accent3 = WinterColors.Frost,
    accentDeep = Color(0xFF0B2A4A),
    onAccent = Color(0xFF04121F),
    ink = WinterColors.Polar,
    passGradient = listOf(Color(0xFF123660), Color(0xFF0A1832), WinterColors.Polar),
    backdropGradient = listOf(Color(0xFF0E2547), Color(0xFF09162E), WinterColors.Polar),
    heroGlow = WinterColors.AuroraGreen,
    motif = ThemeMotif.AURORA,
    texture = ThemeTexture.FROST,
    // The sleeve's colours under the aurora's: teal above, violet below.
    playerTint = listOf(Color(0xFF1C6E74).copy(alpha = 0.32f), Color.Transparent, Color(0xFF3B2A78).copy(alpha = 0.30f)),
    progressColors = listOf(WinterColors.Ice, WinterColors.AuroraGreen),
    lyricGlow = WinterColors.Ice,
    glow = WinterColors.AuroraTeal,
    cardShape = WinterShape,
    controlShape = CircleShape,
    chipShape = CircleShape,
    playerSheen = WinterColors.AuroraGreen,
    edge = ThemeEdge.FROST,
    animated = true,
    // Icy white under the aurora, frost blue below it, a faint teal glow behind both.
    heroTitle = Color(0xFFF3FBFF),
    heroSubtitle = Color(0xFFC4E7FF),
    heroShadow = WinterColors.AuroraTeal.copy(alpha = 0.40f),
)

/** Winter day: snow-white and glacier blue, deep navy for text, icy blue for the accent. */
private val WinterDayTokens = WinterNightTokens.copy(
    accent = WinterColors.IceBlue,
    accent2 = Color(0xFF38B6E0),
    accent3 = Color(0xFF8CC8EC),
    accentDeep = Color(0xFF0C2D52),
    onAccent = Color.White,
    ink = Color(0xFF0E2A4A),
    passGradient = listOf(Color(0xFF2E78C0), Color(0xFF1B4E86), Color(0xFF0E2A4A)),
    backdropGradient = listOf(Color(0xFFCDE6F8), Color(0xFFE6F2FB), WinterColors.Snow),
    heroGlow = Color(0xFFFFFFFF),
    motif = ThemeMotif.GLACIER,
    playerTint = listOf(Color(0xFFDDF3FF).copy(alpha = 0.16f), Color.Transparent, Color(0xFF9FD3F5).copy(alpha = 0.18f)),
    progressColors = listOf(Color(0xFFFFFFFF), WinterColors.Ice),
    lyricGlow = Color(0xFFBFE6FF),
    glow = Color(0xFF8CC8EC),
    playerSheen = Color(0xFFFFFFFF),
    // White on the arctic sky, carried by a deep-blue glow so it holds over snow and sun alike —
    // navy type dropped on a blue sky read as stuck on rather than part of the scene.
    heroTitle = Color.White,
    heroSubtitle = Color(0xFFF2F8FD),
    heroShadow = Color(0xFF0B3463).copy(alpha = 0.62f),
)

/** The active theme's tokens. The app provides them; anything outside a theme reads Sonara's. */
val LocalThemeTokens = staticCompositionLocalOf { ThemeTokens.Sonara }

/**
 * A corner of [corner] in the active theme's language: as it is under Sonara, a little softer
 * under Vice, and cut instead of curved under Doom. For artwork, tiles and rows — the shapes a
 * page is mostly made of.
 */
@androidx.compose.runtime.Composable
@androidx.compose.runtime.ReadOnlyComposable
fun themedShape(corner: androidx.compose.ui.unit.Dp): Shape {
    val tokens = LocalThemeTokens.current
    return when {
        tokens.angular -> CutCornerShape(corner * 0.7f)
        tokens.id == ThemeId.VICE -> RoundedCornerShape(corner * 1.25f)
        else -> RoundedCornerShape(corner)
    }
}
