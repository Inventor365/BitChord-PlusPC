package com.music.bitchord

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app is Sonara in every language: no string resource shows "BitChord" (any spelling),
 * except `Music/BitChord`, the real download folder, kept so upgrades find what they saved.
 */
class BrandStringsTest {
    private val brand = Regex("""bit[-_ ]?chord""", RegexOption.IGNORE_CASE)
    private val element = Regex("""<(string|item)\b[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)

    @Test
    fun noStringResourceShowsTheOldName() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val offenders = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
            .mapNotNull { File(it, "strings.xml").takeIf(File::isFile) }
            .flatMap { file ->
                element.findAll(file.readText()).mapNotNull { m ->
                    val text = m.groupValues[2].replace("Music/BitChord", "")
                    if (brand.containsMatchIn(text)) "${file.parentFile.name}: ${m.groupValues[2].take(80)}" else null
                }.toList()
            }
        assertTrue("Old name still shown:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
