package com.music.bitchord.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.theme.DoomColors
import com.music.bitchord.ui.theme.LocalThemeTokens
import com.music.bitchord.ui.theme.ThemeId
import com.music.bitchord.ui.theme.ThemeTokens
import com.music.bitchord.ui.theme.ViceColors
import com.music.bitchord.ui.theme.WinterColors
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppFont

// Apple Music's signature red. No longer the primary accent, but kept for the
// spots (Replay's rank badge) that want that specific red regardless of theme.
val AccentRed = Color(0xFFFA2D48)

private val DarkColors = darkColorScheme(
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

private val LightColors = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color(0xFFF7F7F9),
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFFE5E5EA),
)

/**
 * SF Pro Display, the face Apple Music itself is set in, drawn one weight
 * lighter than it is asked for.
 *
 * Measured against Apple Music's own screens, every weight here came out a
 * cut heavier than the same text there: its titles are Semibold where these
 * asked for Bold, its metadata Medium where these asked for Semibold. The
 * shift is made once, in the family, so it reaches the type scale and every
 * weight set in place alike and keeps the steps between them: Heavy draws
 * Bold, Bold draws Semibold, Semibold draws Medium, Medium draws Regular.
 * Regular stays Regular — no lighter cut is bundled, and Apple's body text is
 * Regular too. A FontWeight in this app therefore names the step it sits at in
 * the scale, not the cut it draws.
 *
 * Compose synthesises nothing, so a weight outside these falls back to the
 * nearest one listed.
 */
val SFProDisplay = FontFamily(
    Font(R.font.sf_pro_display_regular, FontWeight.W400),
    Font(R.font.sf_pro_display_regular, FontWeight.W500),
    Font(R.font.sf_pro_display_medium, FontWeight.W600),
    Font(R.font.sf_pro_display_semibold, FontWeight.W700),
    Font(R.font.sf_pro_display_bold, FontWeight.W800),
)

/*
 * Letter spacing measured off Apple Music's own screens: the spacing the
 * bundled Display cut needs, at the weight it draws, to set a line to the
 * width Apple sets it. Apple sets text under 20pt in SF Pro Text, which is
 * spaced looser than Display, so the small sizes open up — about +0.25 to
 * +0.4 at 12 to 14 — and the spacing closes as the size grows, to just under
 * nothing for semibold titles at 18 to 24. The large titles had nothing to
 * measure against and follow the same trend.
 */
private val BitChordTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 30.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.W700, fontSize = 22.sp, letterSpacing = (-0.25).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.W700, fontSize = 20.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 16.sp, letterSpacing = 0.05.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.W400, fontSize = 16.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 14.sp, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 12.sp, letterSpacing = 0.25.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 11.sp, letterSpacing = 0.3.sp),
).withFamily(SFProDisplay)

/**
 * SF Pro Display as the app first shipped it, before [SFProDisplay] moved every
 * weight a cut lighter: each weight draws its own cut, Heavy included. Kept for
 * [AppFont.LEGACY].
 */
val SFProDisplayLegacy = FontFamily(
    Font(R.font.sf_pro_display_regular, FontWeight.W400),
    Font(R.font.sf_pro_display_medium, FontWeight.W500),
    Font(R.font.sf_pro_display_semibold, FontWeight.W600),
    Font(R.font.sf_pro_display_bold, FontWeight.W700),
    Font(R.font.sf_pro_display_heavy, FontWeight.W800),
)

// Heavy, tight typography — the original scale [AppFont.LEGACY] restores.
private val LegacyTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 34.sp, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 30.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.W700, fontSize = 22.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.W700, fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 16.sp, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.W400, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 11.sp),
).withFamily(SFProDisplayLegacy)

/** Applies [family] to every style in the scale, so nothing is left on Roboto. */
private fun Typography.withFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

/**
 * Vice: Miami at dusk. Violet-black surfaces, hot pink primary, sunset orange and cyan.
 * Every on-colour clears WCAG AA against what it sits on (see ThemeContrastTest).
 */
private val ViceScheme = darkColorScheme(
    primary = ViceColors.HotPink,
    onPrimary = Color(0xFF1A0010),
    primaryContainer = Color(0xFF5A1046),
    onPrimaryContainer = Color(0xFFFFD8EC),
    inversePrimary = Color(0xFFB0006A),
    secondary = ViceColors.Sunset,
    onSecondary = Color(0xFF231000),
    secondaryContainer = Color(0xFF5C2C0E),
    onSecondaryContainer = Color(0xFFFFDCC6),
    tertiary = ViceColors.Cyan,
    onTertiary = Color(0xFF00201F),
    tertiaryContainer = Color(0xFF004F50),
    onTertiaryContainer = Color(0xFFB8FFFE),
    background = ViceColors.Night,
    onBackground = Color(0xFFF6EEFF),
    surface = ViceColors.Surface,
    onSurface = Color(0xFFF6EEFF),
    surfaceVariant = ViceColors.SurfaceHigh,
    onSurfaceVariant = ViceColors.Lilac,
    surfaceTint = ViceColors.HotPink,
    inverseSurface = Color(0xFFEDE3FA),
    inverseOnSurface = Color(0xFF1E1530),
    error = Color(0xFFFF6E6E),
    onError = Color(0xFF2B0000),
    errorContainer = Color(0xFF6B1219),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = ViceColors.Outline,
    outlineVariant = Color(0xFF2B1D44),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2C1D48),
    surfaceDim = ViceColors.Night,
    surfaceContainerLowest = Color(0xFF07040F),
    surfaceContainerLow = Color(0xFF110A20),
    surfaceContainer = ViceColors.Surface,
    surfaceContainerHigh = Color(0xFF1C1231),
    surfaceContainerHighest = ViceColors.SurfaceHigh,
)

/**
 * Doom: Latverian steel. Gunmetal and black, toxic emerald primary, cold brass and steel.
 */
private val DoomScheme = darkColorScheme(
    primary = DoomColors.Emerald,
    onPrimary = Color(0xFF00170A),
    primaryContainer = Color(0xFF0E4A2C),
    onPrimaryContainer = Color(0xFFB6FFD3),
    inversePrimary = Color(0xFF00733D),
    secondary = DoomColors.Brass,
    onSecondary = Color(0xFF1C1300),
    secondaryContainer = Color(0xFF4A3B16),
    onSecondaryContainer = Color(0xFFF3E1B1),
    tertiary = DoomColors.Steel,
    onTertiary = Color(0xFF0E1418),
    tertiaryContainer = Color(0xFF34404A),
    onTertiaryContainer = Color(0xFFDDE6EE),
    background = DoomColors.Void,
    onBackground = Color(0xFFE7ECEF),
    surface = DoomColors.Gunmetal,
    onSurface = Color(0xFFE7ECEF),
    surfaceVariant = DoomColors.Plate,
    onSurfaceVariant = DoomColors.Ash,
    surfaceTint = DoomColors.Emerald,
    inverseSurface = Color(0xFFDDE3E7),
    inverseOnSurface = Color(0xFF161B1F),
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF2B0300),
    errorContainer = Color(0xFF6A1A12),
    onErrorContainer = Color(0xFFFFDAD4),
    outline = DoomColors.Outline,
    outlineVariant = Color(0xFF232B31),
    scrim = Color.Black,
    surfaceBright = Color(0xFF283037),
    surfaceDim = DoomColors.Void,
    surfaceContainerLowest = Color(0xFF040506),
    surfaceContainerLow = Color(0xFF0D1114),
    surfaceContainer = DoomColors.Gunmetal,
    surfaceContainerHigh = Color(0xFF181E22),
    surfaceContainerHighest = DoomColors.Plate,
)

/** Winter, polar night: navy to black surfaces, ice-blue primary, aurora green and violet. */
private val WinterNightScheme = darkColorScheme(
    primary = WinterColors.Ice,
    onPrimary = Color(0xFF04121F),
    primaryContainer = Color(0xFF15406A),
    onPrimaryContainer = Color(0xFFD6EEFF),
    inversePrimary = Color(0xFF1D6FC0),
    secondary = WinterColors.AuroraGreen,
    onSecondary = Color(0xFF00241A),
    secondaryContainer = Color(0xFF0E4A3A),
    onSecondaryContainer = Color(0xFFC6FFE9),
    tertiary = WinterColors.AuroraViolet,
    onTertiary = Color(0xFF1A0E40),
    tertiaryContainer = Color(0xFF3A2C78),
    onTertiaryContainer = Color(0xFFE8DEFF),
    background = WinterColors.Polar,
    onBackground = Color(0xFFEAF4FF),
    surface = WinterColors.NightSurface,
    onSurface = Color(0xFFEAF4FF),
    surfaceVariant = WinterColors.NightSurfaceHigh,
    onSurfaceVariant = Color(0xFFA8BFD8),
    surfaceTint = WinterColors.Ice,
    inverseSurface = Color(0xFFE3EEF9),
    inverseOnSurface = Color(0xFF14243B),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF2D0000),
    errorContainer = Color(0xFF6B1A16),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF34507A),
    outlineVariant = Color(0xFF1E3354),
    scrim = Color.Black,
    surfaceBright = Color(0xFF213659),
    surfaceDim = WinterColors.Polar,
    surfaceContainerLowest = Color(0xFF030713),
    surfaceContainerLow = Color(0xFF0A1428),
    surfaceContainer = WinterColors.NightSurface,
    surfaceContainerHigh = Color(0xFF13213B),
    surfaceContainerHighest = WinterColors.NightSurfaceHigh,
)

/** Winter day: snow and frosted-glass surfaces, glacier blues, deep navy text. */
private val WinterDayScheme = lightColorScheme(
    primary = WinterColors.IceBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E8FA),
    onPrimaryContainer = Color(0xFF062A4F),
    inversePrimary = WinterColors.Ice,
    secondary = Color(0xFF0E7C9E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEEF8),
    onSecondaryContainer = Color(0xFF00313F),
    tertiary = Color(0xFF5B4BB0),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6E0FF),
    onTertiaryContainer = Color(0xFF1C0F55),
    background = WinterColors.Snow,
    onBackground = WinterColors.Navy,
    surface = WinterColors.SnowSurface,
    onSurface = WinterColors.Navy,
    surfaceVariant = WinterColors.Glacier,
    onSurfaceVariant = WinterColors.NavySoft,
    surfaceTint = WinterColors.IceBlue,
    inverseSurface = Color(0xFF16304F),
    inverseOnSurface = Color(0xFFE9F3FC),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF9DB9D3),
    outlineVariant = Color(0xFFD4E4F2),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDDE9F4),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F8FD),
    surfaceContainer = Color(0xFFECF4FB),
    surfaceContainerHigh = WinterColors.Glacier,
    surfaceContainerHighest = WinterColors.GlacierDeep,
)

/** Quicksand: clean, rounded and airy — Winter's headings. A variable font; OFL, see assets/licenses. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val QuicksandFamily = FontFamily(
    Font(R.font.quicksand_variable, FontWeight.W600, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.quicksand_variable, FontWeight.W700, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** Righteous: rounded, retro, a little glam — Vice's headings. OFL; see assets/licenses. */
val RighteousFamily = FontFamily(Font(R.font.righteous_regular, FontWeight.W400))

/**
 * Oswald: a clean condensed sans in the spirit of a blockbuster title card — Doom's headings.
 * A variable font; OFL, see assets/licenses.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val OswaldFamily = FontFamily(
    Font(R.font.oswald_variable, FontWeight.W500, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.oswald_variable, FontWeight.W600, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.oswald_variable, FontWeight.W700, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** [id]'s colour scheme. Vice and Doom have only their dark one; Sonara and Winter both. */
fun themeColorScheme(id: ThemeId, dark: Boolean): ColorScheme = when (id) {
    ThemeId.SONARA -> if (dark) DarkColors else LightColors
    ThemeId.VICE -> ViceScheme
    ThemeId.DOOM -> DoomScheme
    ThemeId.WINTER -> if (dark) WinterNightScheme else WinterDayScheme
}

/** The tokens for [id] in a dark or light app, with the heading face only this module can load. */
fun themeTokensFor(id: ThemeId, dark: Boolean = true): ThemeTokens = when (id) {
    ThemeId.SONARA -> ThemeTokens.Sonara
    ThemeId.VICE -> ThemeTokens.Vice.copy(headingFamily = RighteousFamily)
    ThemeId.DOOM -> ThemeTokens.Doom.copy(headingFamily = OswaldFamily)
    ThemeId.WINTER -> id.tokens(dark).copy(headingFamily = QuicksandFamily)
}

/**
 * The page titles and greetings in the theme's own face; titles of songs, body and labels stay
 * in the app's face, which is set for reading at small sizes. Each face is set at the weight it
 * reads best in, and a condensed one a little looser.
 */
private fun Typography.withHeadings(tokens: ThemeTokens): Typography {
    val family = tokens.headingFamily ?: return this
    val (weight, spacing) = when (tokens.id) {
        ThemeId.DOOM -> FontWeight.W600 to 0.4.sp
        ThemeId.WINTER -> FontWeight.W700 to 0.sp
        else -> FontWeight.W400 to 0.2.sp
    }
    fun TextStyle.heading() = copy(fontFamily = family, fontWeight = weight, letterSpacing = spacing)
    return copy(
        displayLarge = displayLarge.heading(),
        displayMedium = displayMedium.heading(),
        displaySmall = displaySmall.heading(),
        headlineLarge = headlineLarge.heading(),
        headlineMedium = headlineMedium.heading(),
        headlineSmall = headlineSmall.heading(),
    )
}

/** The Material shape scale in the theme's language: soft for Vice, chamfered for Doom. */
private fun shapesFor(tokens: ThemeTokens): Shapes = when {
    tokens.angular -> Shapes(
        extraSmall = CutCornerShape(4.dp),
        small = CutCornerShape(7.dp),
        medium = CutCornerShape(10.dp),
        large = CutCornerShape(14.dp),
        extraLarge = CutCornerShape(20.dp),
    )
    tokens.id == ThemeId.VICE || tokens.id == ThemeId.WINTER -> Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )
    else -> Shapes()
}

/**
 * The app's theme: [themeId]'s colour scheme, type, shapes and tokens. Vice and Doom are
 * dark-only, so [darkTheme] applies to Sonara alone. A change of theme crossfades every colour
 * over [THEME_FADE_MS]; [reduceMotion] makes it a cut.
 */
@Composable
fun BitChordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    font: AppFont = AppFont.DEFAULT,
    themeId: ThemeId = ThemeId.SONARA,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val tokens = remember(themeId, darkTheme) { themeTokensFor(themeId, darkTheme) }
    val target = themeColorScheme(themeId, darkTheme)
    val base = when (font) {
        AppFont.DEFAULT -> BitChordTypography
        AppFont.LEGACY -> LegacyTypography
    }
    val typography = remember(base, tokens) { base.withHeadings(tokens) }
    CompositionLocalProvider(LocalThemeTokens provides tokens) {
        MaterialTheme(
            colorScheme = animatedColorScheme(target, animate = !reduceMotion),
            typography = typography,
            shapes = remember(tokens) { shapesFor(tokens) },
            content = content,
        )
    }
}

/** How long a change of theme takes to fade across. */
private const val THEME_FADE_MS = 420

/** [target], with every role easing to its new colour when the theme or mode changes. */
@Composable
private fun animatedColorScheme(target: ColorScheme, animate: Boolean): ColorScheme {
    val spec = if (animate) tween<Color>(THEME_FADE_MS) else snap()
    @Composable
    fun Color.eased(label: String) = animateColorAsState(this, spec, label = label).value
    return target.copy(
        primary = target.primary.eased("primary"),
        onPrimary = target.onPrimary.eased("onPrimary"),
        primaryContainer = target.primaryContainer.eased("primaryContainer"),
        onPrimaryContainer = target.onPrimaryContainer.eased("onPrimaryContainer"),
        secondary = target.secondary.eased("secondary"),
        onSecondary = target.onSecondary.eased("onSecondary"),
        tertiary = target.tertiary.eased("tertiary"),
        background = target.background.eased("background"),
        onBackground = target.onBackground.eased("onBackground"),
        surface = target.surface.eased("surface"),
        onSurface = target.onSurface.eased("onSurface"),
        surfaceVariant = target.surfaceVariant.eased("surfaceVariant"),
        onSurfaceVariant = target.onSurfaceVariant.eased("onSurfaceVariant"),
        surfaceContainer = target.surfaceContainer.eased("surfaceContainer"),
        surfaceContainerHigh = target.surfaceContainerHigh.eased("surfaceContainerHigh"),
        surfaceContainerHighest = target.surfaceContainerHighest.eased("surfaceContainerHighest"),
        outline = target.outline.eased("outline"),
        outlineVariant = target.outlineVariant.eased("outlineVariant"),
    )
}

/**
 * Draws the status and navigation bar glyphs dark or light.
 *
 * `enableEdgeToEdge()` decides this from the *system* dark-mode setting, which
 * is the wrong input the moment the in-app theme disagrees with it: Light theme
 * on a phone in dark mode left white icons on a white bar, invisible. The bars
 * have to follow the content the app is actually painting. The player supplies
 * its own stable light-icon value and paints contrast behind it; every other
 * surface follows the theme. Hence a parameter rather than reading it here.
 */
@Composable
fun SystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = findWindow(view) ?: return
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = dark
            isAppearanceLightNavigationBars = dark
        }
    }
}

