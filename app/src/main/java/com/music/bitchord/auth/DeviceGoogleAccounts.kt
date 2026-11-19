package com.music.bitchord.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.music.bitchord.data.DebugLog as Log

/**
 * Signing in with a Google account that is already on the device, instead of typing it in again.
 *
 * The listener picks the account in Android's own chooser ([chooserIntent]) — no permission is
 * needed, and only the account they pick becomes visible to the app. For that account Android is
 * asked ([webSignInUrl]) for a one-time web sign-in link to YouTube Music; the system shows its
 * own consent prompt the first time. Opening the link in the sign-in WebView starts a normal
 * signed-in web session, which the existing confirm-and-capture flow then saves — so, exactly as
 * with the typed-in login, no password or token is ever held by the app.
 *
 * Not every Google Play services build hands that link to an app outside Google. When it does not,
 * the web login opens with the chosen address already filled in ([prefilledLoginUrl]), and the
 * listener only confirms it.
 */
object DeviceGoogleAccounts {
    const val ACCOUNT_TYPE = "com.google"

    /** One-time web sign-in, continuing to YouTube Music. */
    private const val WEB_LOGIN =
        "weblogin:service=youtube&continue=https://music.youtube.com/"

    /** Google's standard web login for YouTube Music. */
    const val LOGIN_URL =
        "https://accounts.google.com/ServiceLogin" +
            "?ltmpl=music&service=youtube&prompt=select_account" +
            "&continue=https%3A%2F%2Fmusic.youtube.com%2F"

    private const val TAG = "BitChord"

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
     * Asks for the one-time sign-in link for [accountName], showing Android's consent prompt from
     * [activity] when one is needed. [onResult] gets the link on the main thread, or null when
     * the device would not give one (declined, unsupported, offline).
     */
    fun webSignInUrl(activity: Activity, accountName: String, onResult: (String?) -> Unit) {
        val manager = AccountManager.get(activity)
        val account = Account(accountName, ACCOUNT_TYPE)
        try {
            manager.getAuthToken(
                account,
                WEB_LOGIN,
                null,
                activity,
                { future ->
                    val url = runCatching { future.result.getString(AccountManager.KEY_AUTHTOKEN) }
                        .onFailure { Log.w(TAG, "device sign-in link unavailable: ${it.javaClass.simpleName}") }
                        .getOrNull()
                    // Single-use: forget it at once, so the next sign-in asks for a fresh one
                    // rather than being handed this spent link from the cache.
                    url?.let { manager.invalidateAuthToken(ACCOUNT_TYPE, it) }
                    onResult(url?.takeIf(::isGoogleUrl))
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "device sign-in link request failed: ${e.javaClass.simpleName}")
            onResult(null)
        }
    }

    /** The web login with [email] already entered, for when the one-time link is not offered. */
    fun prefilledLoginUrl(email: String): String =
        "$LOGIN_URL&Email=${java.net.URLEncoder.encode(email, Charsets.UTF_8.name())}"

    /** Only ever a Google sign-in page is opened from an account-manager answer. */
    internal fun isGoogleUrl(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && (host == "google.com" || host.endsWith(".google.com"))
    }
}
