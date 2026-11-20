package com.music.bitchord.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun assertAA(theme: String, role: String, fg: Color, bg: Color, minimum: Double = 4.5) {
        val ratio = contrast(fg, bg)
        assertTrue("$theme $role contrast ${"%.2f".format(ratio)} < $minimum", ratio >= minimum)
    }

    @Test
    fun everyThemesTextClearsWcagAA() {
        for (id in ThemeId.entries) {
            for (dark in listOf(true, false)) {
                if (id.tokens.darkOnly && !dark) continue // Vice and Doom are dark-only.
                val s = themeColorScheme(id, dark)
                val name = "$id${if (dark) "/dark" else "/light"}"
                assertAA(name, "onBackground", s.onBackground, s.background)
                assertAA(name, "onSurface", s.onSurface, s.surface)
                assertAA(name, "onSurfaceVariant on surface", s.onSurfaceVariant, s.surface)
                assertAA(name, "onSurfaceVariant on background", s.onSurfaceVariant, s.background)
                assertAA(name, "onPrimary", s.onPrimary, s.primary)
                if (id != ThemeId.SONARA) {
                    // Sonara's scheme only sets the roles it always has; the new themes set all.
                    assertAA(name, "onSecondary", s.onSecondary, s.secondary)
                    assertAA(name, "onTertiary", s.onTertiary, s.tertiary)
                    assertAA(name, "onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer)
                    assertAA(name, "onSecondaryContainer", s.onSecondaryContainer, s.secondaryContainer)
                    assertAA(name, "onError", s.onError, s.error)
                    // The accent used as text or an icon on the page.
                    assertAA(name, "primary on background", s.primary, s.background)
                }
            }
        }
    }

    @Test
    fun accentButtonsKeepTheirLabelsReadable() {
        for (t in listOf(ThemeTokens.Vice, ThemeTokens.Doom, ThemeTokens.WinterNight, ThemeTokens.WinterDay)) {
            assertAA("${t.id}/${t.motif}", "onAccent on accent", t.onAccent, t.accent)
        }
    }

    @Test
    fun sonaraIsExactlyTheLookBeforeThemes() {
        val t = ThemeTokens.Sonara
        assertEquals(Color(0xFFFF2D55), t.accent)
        assertEquals(Color(0xFFFF6B81), t.accent2)
        assertEquals(Color(0xFFFF9AAB), t.accent3)
        assertEquals(Color(0xFF5C0A1C), t.accentDeep)
        assertEquals(Color(0xFF0A0A0C), t.ink)
        assertEquals(listOf(Color(0xFF2A0B14), Color(0xFF111114), Color(0xFF18181C)), t.passGradient)
        assertEquals(RoundedCornerShape(26.dp), t.cardShape)
        assertEquals(CircleShape, t.controlShape)
        // No backdrop, tint, progress colours or glow: every themed hook is a no-op.
        assertTrue(t.backdropGradient.isEmpty() && t.playerTint.isEmpty() && t.progressColors.isEmpty())
        assertEquals(null, t.lyricGlow)
        assertEquals(null, t.glow)
        assertEquals(ThemeMotif.NONE, t.motif)
        val dark = themeColorScheme(ThemeId.SONARA, dark = true)
        assertEquals(Color.White, dark.primary)
        assertEquals(Color.Black, dark.background)
    }

    @Test
    fun storedKeysAreStable_andAnUnknownOneFallsBackToSonara() {
        assertEquals(listOf("sonara", "vice", "doom", "winter"), ThemeId.entries.map { it.key })
        assertEquals(ThemeId.VICE, ThemeId.fromKey("vice"))
        assertEquals(ThemeId.SONARA, ThemeId.fromKey(null))
        assertEquals(ThemeId.SONARA, ThemeId.fromKey("removed-theme"))
    }

    @Test
    fun viceAndDoomAreDarkOnly_andEveryThemeIsRounded() {
        assertTrue(ThemeTokens.Vice.darkOnly && ThemeTokens.Doom.darkOnly)
        assertTrue(!ThemeTokens.Sonara.darkOnly && !ThemeTokens.WinterNight.darkOnly)
        // Doom was chamfered once and read as fussy: every theme is rounded now.
        for (id in ThemeId.entries) assertTrue("$id", !id.tokens.angular)
    }

    @Test
    fun winterHasADayAndANight_andFollowsTheAppsMode() {
        assertTrue(!ThemeId.WINTER.tokens.darkOnly)
        assertEquals(ThemeMotif.AURORA, ThemeId.WINTER.tokens(dark = true).motif)
        assertEquals(ThemeMotif.GLACIER, ThemeId.WINTER.tokens(dark = false).motif)
        assertTrue(themeColorScheme(ThemeId.WINTER, dark = false).background.luminance() > 0.8f)
        assertTrue(themeColorScheme(ThemeId.WINTER, dark = true).background.luminance() < 0.05f)
    }
}
