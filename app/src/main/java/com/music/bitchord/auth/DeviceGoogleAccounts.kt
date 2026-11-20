package com.music.bitchord.auth

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent

/**
 * Signing in with a Google account that is already on the device, instead of typing it in again.
 *
 * The listener picks the account in Android's own chooser ([chooserIntent]) — no permission is
 * needed, and only the account they pick becomes visible to the app. Google's sign-in then opens
 * with that account already entered ([prefilledLoginUrl]), where Google can confirm it with a
 * "Check your phone" prompt that this same device receives, or a passkey or the screen lock —
 * whatever the account allows — instead of a password. The confirm-and-capture flow then saves
 * the session; no password or token is ever held by the app.
 *
 * Why not one tap: Android's one-time web sign-in link (`weblogin:`) would skip Google's page
 * entirely, but Google issues it only to its own apps. Asked for it, the server answers
 * WILL_NOT_SIGN_IN after an "UnsupportedTokenLabel" consent screen (measured on a Poco F3, Play
 * services 26.45), so that path was removed rather than shown to fail.
 */
object DeviceGoogleAccounts {
    const val ACCOUNT_TYPE = "com.google"

    /** Google's standard web login for YouTube Music. */
    const val LOGIN_URL =
        "https://accounts.google.com/ServiceLogin" +
            "?ltmpl=music&service=youtube&prompt=select_account" +
            "&continue=https%3A%2F%2Fmusic.youtube.com%2F"

    /** Whether this device signs in to Google at all (Play services present). */
    fun available(context: Context): Boolean =
        runCatching {
            AccountManager.get(context).authenticatorTypes.any { it.type == ACCOUNT_TYPE }
        }.getOrDefault(false)

    /** Android's account chooser, Google accounts only, with "Add account" offered too. */
    fun chooserIntent(): Intent = AccountManager.newChooseAccountIntent(
        null,
        null,
        arrayOf(ACCOUNT_TYPE),
        null,
        null,
        null,
        null,
    )

    /** The account name a chooser result carries, or null when the listener backed out. */
    fun chosenAccount(result: Intent?): String? =
        result?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            ?.takeIf { result.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) == ACCOUNT_TYPE }

    /**
     * The web login with [email] already entered, so the listener goes straight to Google's
     * passwordless step for the account on this phone ("Check your phone", a prompt this same
     * device receives) rather than typing an address. Google's current sign-in honours
     * `login_hint`; the older `Email` is kept for the pages that still read it.
     */
    fun prefilledLoginUrl(email: String): String {
        val e = java.net.URLEncoder.encode(email, Charsets.UTF_8.name())
        return "$LOGIN_URL&login_hint=$e&Email=$e"
    }
}
