package com.music.bitchord.collab

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.collab.CollabInviteLink
import com.music.bitchord.data.collab.CollabManager
import com.music.bitchord.data.collab.ParsedCollabInvite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Android singleton wiring for BitChord Collaborative Playlists + Deep-Link Invite relay.
 *
 * Defaults `defaultServerUrl` to `BuildConfig.COLLAB_SERVER` (`""` unless explicitly configured),
 * so Collaborative Playlists work out of the box using the user's BitChord Google / YouTube Music
 * login with zero extra server or OAuth Client ID setup.
 */
object AndroidCollab {

    @Volatile
    private var appContext: Context? = null

    val manager: CollabManager by lazy {
        CollabManager(
            storageFileProvider = {
                appContext?.let { File(it.filesDir, "collab/collab_state.json") }
            },
            defaultServerUrl = {
                readBuildConfigString("COLLAB_SERVER")
                    .takeUnless { it.contains("bitchord-listen-together.onrender.com", ignoreCase = true) }
                    .orEmpty()
            },
            defaultGoogleClientId = {
                readBuildConfigString("COLLAB_GOOGLE_CLIENT_ID")
            },
        )
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        manager.initialize()
    }

    /**
     * Whether Android currently routes `music.youtube.com` links to BitChord by default.
     * On Android 12+ (`S`), checks `DomainVerificationManager` for `music.youtube.com`.
     */
    fun isYouTubeMusicDefaultLinkHandler(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching {
            val mgr = context.getSystemService(DomainVerificationManager::class.java) ?: return false
            val state = mgr.getDomainVerificationUserState(context.packageName) ?: return false
            if (!state.isLinkHandlingAllowed) return false
            val status = state.hostToStateMap["music.youtube.com"]
            status == DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
                status == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        }.getOrDefault(false)
    }

    /**
     * Opens Android's "Open by default" settings page for BitChord so the user can tick
     * `music.youtube.com` (and `www.youtube.com`) once to open YouTube Music playlist links
     * directly in BitChord by default.
     */
    fun openYouTubeMusicDefaultLinkSettings(context: Context) {
        val pkgUri = Uri.parse("package:${context.packageName}")
        val primary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkgUri)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(primary) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private fun readBuildConfigString(field: String): String =
        runCatching { BuildConfig::class.java.getField(field).get(null) as? String }
            .getOrNull()
            ?.trim()
            .orEmpty()
}

object CollabInviteIntent {

    private const val EXTRA_CONSUMED = "bitchord.collabInviteConsumed"
    private val URL_IN_TEXT = Regex("""(?:https?://|bitchord://|intent://)\S+""", RegexOption.IGNORE_CASE)

    private val _pending = MutableStateFlow<ParsedCollabInvite?>(null)
    val pending: StateFlow<ParsedCollabInvite?> = _pending.asStateFlow()

    fun consume(intent: Intent?): Boolean {
        if (intent == null || intent.getBooleanExtra(EXTRA_CONSUMED, false)) return false
        val candidate = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                URL_IN_TEXT.findAll(text).map { it.value }.firstOrNull { CollabInviteLink.parse(it) != null }
                    ?: text.trim().takeIf { CollabInviteLink.parse(it) != null }
            }
            else -> null
        } ?: return false

        val parsed = CollabInviteLink.parse(candidate) ?: return false
        intent.putExtra(EXTRA_CONSUMED, true)
        if (!parsed.isYouTubeMusicInvite) {
            AndroidCollab.manager.adoptServerOriginIfUnset(parsed.serverOrigin)
        }
        _pending.value = parsed
        return true
    }

    fun handled() {
        _pending.value = null
    }
}
