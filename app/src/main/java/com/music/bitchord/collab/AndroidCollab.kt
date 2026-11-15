package com.music.bitchord.collab

import android.content.Context
import android.content.Intent
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
                readBuildConfigString("COLLAB_SERVER").ifBlank { BuildConfig.LISTEN_TOGETHER_SERVER }
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
        AndroidCollab.manager.adoptServerOriginIfUnset(parsed.serverOrigin)
        _pending.value = parsed
        return true
    }

    fun handled() {
        _pending.value = null
    }
}
