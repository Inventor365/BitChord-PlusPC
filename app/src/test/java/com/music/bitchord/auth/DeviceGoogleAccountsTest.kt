package com.music.bitchord.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceGoogleAccountsTest {

    @Test
    fun onlyGoogleHttpsPagesAreOpenedFromAnAccountManagerAnswer() {
        assertTrue(DeviceGoogleAccounts.isGoogleUrl("https://accounts.google.com/MergeSession?uberauth=x&continue=https://music.youtube.com/"))
        assertTrue(DeviceGoogleAccounts.isGoogleUrl("https://www.google.com/accounts/TokenAuth?auth=x"))
        assertFalse(DeviceGoogleAccounts.isGoogleUrl("http://accounts.google.com/MergeSession"))
        assertFalse(DeviceGoogleAccounts.isGoogleUrl("https://accounts.google.com.evil.example/"))
        assertFalse(DeviceGoogleAccounts.isGoogleUrl("https://evilgoogle.com/"))
        // A bare token (not a link) is not something to load.
        assertFalse(DeviceGoogleAccounts.isGoogleUrl("ya29.a0AfH6SM"))
    }

    @Test
    fun theFallbackLoginCarriesTheChosenAddress_encoded() {
        val url = DeviceGoogleAccounts.prefilledLoginUrl("listener+music@gmail.com")
        assertTrue(url.startsWith(DeviceGoogleAccounts.LOGIN_URL))
        assertEquals("listener%2Bmusic%40gmail.com", url.substringAfter("&Email="))
    }
}
