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
    ;

    val tokens: ThemeTokens
        get() = when (this) {
            SONARA -> ThemeTokens.Sonara
            VICE -> ThemeTokens.Vice
            DOOM -> ThemeTokens.Doom
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

    /** A chamfered crest plate, engraved, with a faint glow at its heart. */
    CREST,
}

/** The fine surface texture of a theme's cards and hero. */
enum class ThemeTexture {
    /** A dotted grid, as on the Sonara Pass. */
    DOTS,

    /** A receding retro grid. */
    GRID,

    /** Brushed-metal hairlines and rivets. */
    RIVETS,
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
) {
    /** Chamfered rather than rounded: corners are cut, not curved. */
    val angular: Boolean get() = motif == ThemeMotif.CREST

    companion object {
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
            playerTint = listOf(Color(0xFF0A3320).copy(alpha = 0.30f), Color.Transparent, DoomColors.Void.copy(alpha = 0.30f)),
            progressColors = listOf(DoomColors.Emerald, Color(0xFF8CFFC0)),
            lyricGlow = DoomColors.Emerald,
            glow = DoomColors.Emerald,
            cardShape = CutCornerShape(14.dp),
            controlShape = CutCornerShape(10.dp),
            chipShape = CutCornerShape(7.dp),
        )
    }
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
