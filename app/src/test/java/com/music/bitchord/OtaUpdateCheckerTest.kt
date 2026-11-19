package com.music.bitchord

import com.music.bitchord.data.AppUpdateChecker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OtaUpdateCheckerTest {

    @Test
    fun `parses root ota json for Android phone and Android TV`() {
        val otaFile = File("ota.json").takeIf { it.exists() } ?: File("../ota.json")
        assertTrue("ota.json must exist at repository root", otaFile.exists())
        val payload = Json.parseToJsonElement(otaFile.readText()).jsonObject

        val phoneInfo = AppUpdateChecker.parseOtaPayload(
            payload = payload,
            isTv = false,
            supportedAbis = listOf("arm64-v8a"),
            fetchExternalText = { "Build date: 30/10/2026\n- Verified OTA" },
        )
        assertNotNull(phoneInfo)
        assertEquals("2.1.2", phoneInfo!!.version)
        assertEquals("https://github.com/Inventor365/Sonara/releases/download/v2.1.2/Sonara-v2.1.2.apk", phoneInfo.apkUrl)
        assertTrue(phoneInfo.notes.orEmpty().contains("Sonara v2.1.2"))
        assertTrue(phoneInfo.notes.orEmpty().contains("Verified OTA"))

        val tvInfo = AppUpdateChecker.parseOtaPayload(
            payload = payload,
            isTv = true,
            supportedAbis = listOf("armeabi-v7a"),
        )
        assertNotNull(tvInfo)
        assertEquals("2.1.2", tvInfo!!.version)
        assertEquals("https://github.com/Inventor365/Sonara/releases/download/v2.1.2/SonaraTV-v2.1.2.apk", tvInfo.apkUrl)
    }

    @Test
    fun `parses minimal single-link ota json`() {
        val minimal = Json.parseToJsonElement(
            """
            {
              "version": "v2.0",
              "downloadUrl": "https://example.com/BitChord-v2.0.apk",
              "changelog": "- Faster startup\n- New equalizer"
            }
            """.trimIndent(),
        ).jsonObject

        val info = AppUpdateChecker.parseOtaPayload(minimal, isTv = false)
        assertNotNull(info)
        assertEquals("2.0", info!!.version)
        assertEquals("https://example.com/BitChord-v2.0.apk", info.apkUrl)
        assertEquals("- Faster startup\n- New equalizer", info.notes)
    }

    /** GitHub's releases API, used when the OTA manifest can't be reached: assets are found by name. */
    private fun releasePayload(vararg names: String) = Json.parseToJsonElement(
        """{"tag_name":"v2.1.3","html_url":"https://github.com/Inventor365/Sonara/releases/tag/v2.1.3","assets":[""" +
            names.joinToString(",") { """{"name":"$it","state":"uploaded","browser_download_url":"https://github.com/Inventor365/Sonara/releases/download/v2.1.3/$it"}""" } +
            "]}",
    ).jsonObject

    @Test
    fun `release api fallback picks Sonara APKs for phone and TV`() {
        val payload = releasePayload(
            "Sonara-v2.1.3_arm64-v8a.apk", "Sonara-v2.1.3.apk", "SonaraTV-v2.1.3.apk", "SonaraWear-v2.1.3.apk",
        )
        val phone = AppUpdateChecker.parseOtaPayload(payload, isTv = false, supportedAbis = listOf("arm64-v8a"))!!
        assertEquals("2.1.3", phone.version)
        assertEquals("https://github.com/Inventor365/Sonara/releases/download/v2.1.3/Sonara-v2.1.3.apk", phone.apkUrl)
        val tv = AppUpdateChecker.parseOtaPayload(payload, isTv = true, supportedAbis = listOf("arm64-v8a"))!!
        assertEquals("https://github.com/Inventor365/Sonara/releases/download/v2.1.3/SonaraTV-v2.1.3.apk", tv.apkUrl)
    }

    @Test
    fun `release api fallback still finds APKs named before the rename`() {
        val payload = releasePayload("BitChord-v2.1.3_arm64-v8a.apk", "BitChordTV-v2.1.3.apk")
        val phone = AppUpdateChecker.parseOtaPayload(payload, isTv = false, supportedAbis = listOf("arm64-v8a"))!!
        assertEquals("https://github.com/Inventor365/Sonara/releases/download/v2.1.3/BitChord-v2.1.3_arm64-v8a.apk", phone.apkUrl)
        val tv = AppUpdateChecker.parseOtaPayload(payload, isTv = true, supportedAbis = listOf("arm64-v8a"))!!
        assertEquals("https://github.com/Inventor365/Sonara/releases/download/v2.1.3/BitChordTV-v2.1.3.apk", tv.apkUrl)
    }
}
