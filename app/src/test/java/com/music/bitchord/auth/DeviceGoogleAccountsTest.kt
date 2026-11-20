package com.music.bitchord.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceGoogleAccountsTest {

    @Test
    fun theLoginCarriesTheChosenAccount_forGooglesCurrentAndOlderPages() {
        val url = DeviceGoogleAccounts.prefilledLoginUrl("listener+music@gmail.com")
        assertTrue(url.startsWith(DeviceGoogleAccounts.LOGIN_URL))
        // `login_hint` is what fills the address in today (checked on a phone); `Email` for older pages.
        assertTrue(url.contains("&login_hint=listener%2Bmusic%40gmail.com"))
        assertEquals("listener%2Bmusic%40gmail.com", url.substringAfter("&Email="))
    }
}
