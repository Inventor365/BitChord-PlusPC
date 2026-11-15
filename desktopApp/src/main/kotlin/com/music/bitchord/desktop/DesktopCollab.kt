package com.music.bitchord.desktop

import com.music.bitchord.data.collab.CollabManager
import java.io.File

/**
 * Desktop singleton wiring for BitChord Collaborative Playlists.
 * Stores local JSON snapshot + Google OIDC session token inside the OS-standard BitChord config directory.
 */
internal object DesktopCollab {

    private val storageFile: File by lazy {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val home = System.getProperty("user.home").orEmpty()
        val baseDir = when {
            "mac" in os -> File(home, "Library/Application Support/BitChord")
            "win" in os -> File(System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: "$home/AppData/Roaming", "BitChord")
            else -> File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "bitchord")
        }
        File(baseDir, "collab_state.json")
    }

    val manager: CollabManager by lazy {
        CollabManager(
            storageFileProvider = { storageFile },
            defaultServerUrl = {
                System.getProperty("bitchord.collab.server")?.takeIf { it.isNotBlank() }
                    ?: System.getProperty("bitchord.listentogether.server").orEmpty()
            },
            defaultGoogleClientId = {
                System.getProperty("bitchord.collab.googleClientId").orEmpty()
            },
        )
    }

    fun init() {
        manager.initialize()
    }
}
