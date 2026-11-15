package com.music.bitchord

import com.music.bitchord.data.stats.ListeningStats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class StatsDayKeyTest {
    private val original = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun dayKeyAlwaysUsesAsciiDigitsForNonLatinLocales() {
        for (tag in listOf("mr-IN", "bn-IN", "ar-SA")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            val key = ListeningStats.dayKey("2026-11", 5)
            assertEquals("2026-11-05", key)
            assertEquals(LocalDate.of(2026, 11, 5), LocalDate.parse(key))
        }
    }
}
