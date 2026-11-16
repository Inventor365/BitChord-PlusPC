package com.music.bitchord.data.collab

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.playlistMoves
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Dual-engine Collaborative Playlists manager shared across Android and Desktop:
 *
 *  1. **Zero-Config Native YouTube Music Mode (default when no custom server URL is set)**:
 *     Uses the Google / YouTube Music session ALREADY signed into BitChord (`Innertube.cookie` +
 *     `SAPISIDHASH`) to create, convert, join (`?list=PL...&jct=...`), edit, reorder, and close
 *     real YouTube Music collaborative playlists with zero extra login, zero OAuth Client ID, and
 *     zero external server setup.
 *
 *  2. **Self-Hosted PlusPC Backend Mode (when a custom `https://...` Collaboration Server URL is set)**:
 *     Uses `CollabClient` REST + `/ws/collab` WebSocket sync with Google OIDC verification.
 */
class CollabManager(
    private val storageFileProvider: () -> File?,
    private val defaultServerUrl: () -> String = { "" },
    private val defaultGoogleClientId: () -> String = { "" },
    val client: CollabClient = CollabClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    data class LocalTrackStamp(
        val userId: String,
        val displayName: String,
        val avatarUrl: String? = null,
        val addedAtMs: Long = 0L,
    )

    @Serializable
    private data class PersistedState(
        val version: Int = 2,
        val serverUrlOverride: String = "",
        val googleClientIdOverride: String = "",
        val customServerSession: CollabAuthSession? = null,
        val playlists: List<CollabPlaylistSummary> = emptyList(),
        val details: Map<String, CollabPlaylistDetail> = emptyMap(),
        val ytmCollabIds: Set<String> = emptySet(),
        val ytmInviteLinks: Map<String, String> = emptyMap(),
        val ytmConvertedSources: Map<String, String> = emptyMap(),
        val ytmStoppedIds: Set<String> = emptySet(),
        val localTrackStamps: Map<String, LocalTrackStamp> = emptyMap(),
        val localActivities: Map<String, List<CollabActivity>> = emptyMap(),
    )

    private val diskJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val _serverUrlOverride = MutableStateFlow("")
    val serverUrlOverride: StateFlow<String> = _serverUrlOverride.asStateFlow()

    private val _googleClientIdOverride = MutableStateFlow("")
    val googleClientIdOverride: StateFlow<String> = _googleClientIdOverride.asStateFlow()

    private val _customServerSession = MutableStateFlow<CollabAuthSession?>(null)
    private val _appGoogleUser = MutableStateFlow<CollabUser?>(null)

    private val _session = MutableStateFlow<CollabAuthSession?>(null)
    val session: StateFlow<CollabAuthSession?> = _session.asStateFlow()

    private val _playlists = MutableStateFlow<List<CollabPlaylistSummary>>(emptyList())
    val playlists: StateFlow<List<CollabPlaylistSummary>> = _playlists.asStateFlow()

    private val _details = MutableStateFlow<Map<String, CollabPlaylistDetail>>(emptyMap())
    val details: StateFlow<Map<String, CollabPlaylistDetail>> = _details.asStateFlow()

    private val _realtimeConnected = MutableStateFlow(false)
    val realtimeConnected: StateFlow<Boolean> = _realtimeConnected.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val ytmCollabIds = LinkedHashSet<String>()
    private val ytmInviteLinks = LinkedHashMap<String, String>()
    private val ytmConvertedSources = LinkedHashMap<String, String>()
    private val ytmStoppedIds = LinkedHashSet<String>()
    private val diskMutex = Mutex()
    @Volatile private var diskLoaded = false
    private val localTrackStamps = LinkedHashMap<String, LocalTrackStamp>()
    private val localActivities = LinkedHashMap<String, List<CollabActivity>>()

    private val activeSubscriptions = LinkedHashSet<String>()
    private val recentOwnOps = ArrayDeque<String>(64)
    private var wsJob: Job? = null
    private var ytmPollJob: Job? = null
    private var outboundFrames: Channel<String>? = null
    @Volatile
    private var initialized = false

    fun effectiveServerUrl(): String {
        val override = _serverUrlOverride.value.trim().trimEnd('/')
        if (override.isNotEmpty()) return override
        return defaultServerUrl().trim().trimEnd('/')
    }

    val isNativeYouTubeMode: Boolean
        get() = effectiveServerUrl().isBlank()

    fun effectiveGoogleClientId(): String {
        val override = _googleClientIdOverride.value.trim()
        if (override.isNotEmpty()) return override
        return defaultGoogleClientId().trim()
    }

    val currentUser: CollabUser? get() = _session.value?.user
    val isAuthenticated: Boolean
        get() {
            val s = _session.value ?: return false
            return s.token.isNotBlank() && (s.expiresAtMs <= 0L || s.expiresAtMs > System.currentTimeMillis())
        }

    private fun recomputeEffectiveSession() {
        val next = if (isNativeYouTubeMode) {
            val user = _appGoogleUser.value ?: fallbackInnertubeUser()
            if (user != null) {
                CollabAuthSession(token = YTM_NATIVE_SESSION_TOKEN, expiresAtMs = 0L, user = user)
            } else {
                null
            }
        } else {
            val cs = _customServerSession.value
            if (cs != null && (cs.expiresAtMs <= 0L || cs.expiresAtMs > System.currentTimeMillis())) cs else null
        }
        _session.value = next
    }

    private fun fallbackInnertubeUser(): CollabUser? {
        if (!Innertube.hasSession) return null
        return CollabUser(
            id = "ytm:self",
            displayName = "Google Account",
            emailVerified = true,
        )
    }

    /**
     * Bridges BitChord's built-in Google / YouTube Music login directly into Collaborative Playlists.
     * As soon as the user signs into BitChord with Google, Collaborative Playlists works out of the box!
     */
    fun syncAppGoogleAccount(
        signedIn: Boolean,
        accountId: String? = null,
        displayName: String? = null,
        email: String? = null,
        avatarUrl: String? = null,
    ) {
        val prevUser = _appGoogleUser.value
        if (!signedIn && !Innertube.hasSession) {
            _appGoogleUser.value = null
            recomputeEffectiveSession()
            if (isNativeYouTubeMode) {
                stopRealtime()
            }
            return
        }
        val cleanName = displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: prevUser?.displayName
            ?: "You"
        val cleanId = accountId?.trim()?.takeIf { it.isNotEmpty() }
            ?: email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "ytm:$it" }
            ?: "ytm:${cleanName.lowercase()}"
        val updated = CollabUser(
            id = cleanId,
            email = email?.trim().orEmpty(),
            emailVerified = true,
            displayName = cleanName,
            avatarUrl = avatarUrl?.takeIf { it.startsWith("http") } ?: prevUser?.avatarUrl,
        )
        val becameSignedIn = prevUser == null
        _appGoogleUser.value = updated
        recomputeEffectiveSession()

        if (isNativeYouTubeMode && (becameSignedIn || _playlists.value.isEmpty())) {
            scope.launch {
                ensureDiskLoaded()
                // Enrich profile from YouTube Music account menu if name was generic
                if (cleanName == "You" || cleanName == "Google Account") {
                    YtMusicRepository.account().getOrNull()?.let { info ->
                        _appGoogleUser.value = updated.copy(
                            id = info.email.takeIf { it.isNotBlank() }?.let { "ytm:${it.lowercase()}" } ?: updated.id,
                            displayName = info.name.ifBlank { updated.displayName },
                            email = info.email.ifBlank { updated.email },
                            avatarUrl = info.thumbnailUrl ?: updated.avatarUrl,
                        )
                        recomputeEffectiveSession()
                    }
                }
                refreshPlaylists()
            }
        }
    }

    fun initialize() {
        if (initialized) return
        initialized = true
        scope.launch {
            ensureDiskLoaded()
            recomputeEffectiveSession()
            if (isNativeYouTubeMode && Innertube.hasSession && _appGoogleUser.value == null) {
                YtMusicRepository.account().getOrNull()?.let { info ->
                    _appGoogleUser.value = CollabUser(
                        id = info.email.takeIf { it.isNotBlank() }?.let { "ytm:${it.lowercase()}" } ?: "ytm:${info.name.lowercase()}",
                        email = info.email,
                        emailVerified = true,
                        displayName = info.name.ifBlank { "You" },
                        avatarUrl = info.thumbnailUrl,
                    )
                    recomputeEffectiveSession()
                }
            }
            if (isAuthenticated) {
                refreshSessionAndPlaylists()
            }
        }
    }

    fun setServerUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        if (normalized.isNotEmpty() && CollabInviteLink.normalizeServerOrigin(normalized) == null) {
            _notices.tryEmit("Collaboration server must use https:// (or http://localhost for local dev).")
            return
        }
        _serverUrlOverride.value = normalized
        recomputeEffectiveSession()
        saveToDiskAsync()
        restartRealtimeIfNeeded()
        if (isAuthenticated) {
            scope.launch { refreshPlaylists() }
        }
    }

    fun adoptServerOriginIfUnset(origin: String?) {
        val clean = CollabInviteLink.normalizeServerOrigin(origin) ?: return
        if (effectiveServerUrl().isBlank()) {
            _serverUrlOverride.value = clean
            recomputeEffectiveSession()
            saveToDiskAsync()
        }
    }

    fun setGoogleClientId(clientId: String) {
        _googleClientIdOverride.value = clientId.trim()
        saveToDiskAsync()
    }

    suspend fun signInWithGoogleIdToken(idToken: String): Result<CollabUser> = runCatching {
        val server = requireServerUrl()
        val auth = client.authenticateWithIdToken(server, idToken)
        applyAuthenticatedCustomSession(auth)
        refreshPlaylists().getOrNull()
        auth.user
    }.onFailure { handleAuthError(it) }

    suspend fun signInWithBrowserPkce(openBrowser: (String) -> Unit): Result<CollabUser> = runCatching {
        val server = requireServerUrl()
        val clientId = effectiveGoogleClientId()
        require(clientId.isNotBlank()) {
            "Set a Google OAuth Client ID for your custom collaboration server, or clear the Server URL to use built-in Google / YouTube Music collaboration."
        }
        val (challenge, callback) = PkceGoogleAuth.awaitLoopbackAuthorization(
            clientId = clientId,
            openBrowser = openBrowser,
        )
        val auth = client.authenticateWithCode(
            baseUrl = server,
            code = callback.code,
            codeVerifier = challenge.codeVerifier,
            redirectUri = callback.redirectUri,
            clientId = clientId,
        )
        applyAuthenticatedCustomSession(auth)
        refreshPlaylists().getOrNull()
        auth.user
    }.onFailure { handleAuthError(it) }

    suspend fun signOut() {
        if (isNativeYouTubeMode) {
            _notices.tryEmit("Collaborative playlists follow your BitChord Google / YouTube Music account.")
            return
        }
        val server = effectiveServerUrl()
        val tok = _customServerSession.value?.token
        _customServerSession.value = null
        recomputeEffectiveSession()
        _playlists.value = emptyList()
        _details.value = emptyMap()
        stopRealtime()
        saveToDisk()
        if (server.isNotBlank() && !tok.isNullOrBlank()) {
            client.logout(server, tok)
        }
    }

    suspend fun refreshSessionAndPlaylists() {
        if (isNativeYouTubeMode) {
            recomputeEffectiveSession()
            if (isAuthenticated) refreshPlaylists()
            return
        }
        val server = effectiveServerUrl().takeIf { it.isNotBlank() } ?: return
        val tok = _customServerSession.value?.token?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val (user, exp) = client.getMe(server, tok)
            _customServerSession.value = CollabAuthSession(token = tok, expiresAtMs = exp, user = user)
            recomputeEffectiveSession()
            saveToDisk()
        }.onFailure { err ->
            if (err is CollabApiException && err.status == 401) {
                _customServerSession.value = null
                recomputeEffectiveSession()
                stopRealtime()
                saveToDisk()
                return
            }
        }
        refreshPlaylists()
    }

    suspend fun refreshPlaylists(): Result<List<CollabPlaylistSummary>> = runCatching {
        ensureDiskLoaded()
        if (isNativeYouTubeMode) {
            requireNativeGoogleSignIn()
            _loading.value = true
            try {
                val discovered = discoverNativeYouTubeCollabPlaylists()
                _playlists.value = discovered
                saveToDisk()
                discovered
            } finally {
                _loading.value = false
            }
        } else {
            val server = requireServerUrl()
            val tok = requireToken()
            _loading.value = true
            try {
                val list = client.listPlaylists(server, tok, includeStopped = true)
                _playlists.value = list
                saveToDisk()
                list
            } finally {
                _loading.value = false
            }
        }
    }.onFailure { handleAuthError(it) }

    suspend fun loadPlaylistDetail(playlistId: String): Result<CollabPlaylistDetail> = runCatching {
        if (isNativeYouTubeMode || playlistId.startsWith(YTM_PREFIX)) {
            val rawId = playlistId.removePrefix(YTM_PREFIX).removePrefix("VL")
            val detail = fetchNativeYtmPlaylistDetail(rawId)
            storeDetailSnapshot(detail)
            detail
        } else {
            val server = requireServerUrl()
            val tok = requireToken()
            val detail = client.getPlaylist(server, tok, playlistId)
            storeDetailSnapshot(detail)
            detail
        }
    }.onFailure { handleAuthError(it) }

    suspend fun createPlaylist(
        title: String,
        description: String = "",
        artworkUrl: String? = null,
        permissions: CollabPermissions = CollabPermissions(),
        initialSongs: List<Song> = emptyList(),
    ): Result<CollabPlaylistDetail> = runCatching {
        if (isNativeYouTubeMode) {
            val me = requireNativeGoogleSignIn()
            val cleanTitle = title.trim().ifBlank { "Collaborative Playlist" }
            val cleanDesc = description.trim()
            val resolvedSongs = resolveYouTubeSongs(initialSongs)
            val seedVideoIds = resolvedSongs.map { it.videoId }.take(50)
            // Create directly as UNLISTED so collaboration invite links mint without waiting ~18s for
            // PRIVATE -> UNLISTED propagation (Innertube.createPlaylist auto-falls back to PRIVATE if gated).
            val rawId = Innertube.createPlaylist(
                title = cleanTitle,
                privacy = PlaylistPrivacy.UNLISTED,
                description = cleanDesc,
                videoIds = seedVideoIds,
            ).removePrefix("VL")
            resolvedSongs.map { it.videoId }.drop(50).chunked(100).forEach { chunk ->
                runCatching { Innertube.addToPlaylist(rawId, chunk, allowDuplicates = true, retryCollaboratorAcl = true) }
            }
            val inviteUrl = runCatching { Innertube.enablePlaylistCollaboration(rawId, ensureUnlisted = false) }
                .recoverCatching { Innertube.enablePlaylistCollaboration(rawId, ensureUnlisted = true) }
                .getOrDefault("https://music.youtube.com/playlist?list=$rawId")
            synchronized(ytmCollabIds) {
                ytmCollabIds.add(rawId)
                ytmStoppedIds.remove(rawId)
                ytmInviteLinks[rawId] = inviteUrl
            }
            if (!inviteUrl.contains("jct=")) {
                scheduleDeferredInviteLinkFetch(rawId)
            }
            val now = System.currentTimeMillis()
            resolvedSongs.forEach { s ->
                localTrackStamps["$rawId:${s.videoId}"] = LocalTrackStamp(me.id, me.displayName, me.avatarUrl, now)
            }
            recordLocalActivity(rawId, me, "playlist.create", "${me.displayName} created collaborative playlist \"$cleanTitle\"")
            val detail = fetchNativeYtmPlaylistDetail(
                rawId = rawId,
                fallbackTitle = cleanTitle,
                fallbackDescription = cleanDesc,
                fallbackArtwork = artworkUrl,
                initialSongs = resolvedSongs,
                fallbackPermissions = permissions,
            )
            storeDetailSnapshot(detail)
            detail
        } else {
            val server = requireServerUrl()
            val tok = requireToken()
            val detail = client.createPlaylist(
                baseUrl = server,
                token = tok,
                title = title.trim(),
                description = description.trim(),
                artworkUrl = artworkUrl?.takeIf { it.startsWith("http") },
                permissions = permissions,
                tracks = initialSongs.mapNotNull { it.toCollabTrackInput() },
            )
            storeDetailSnapshot(detail)
            detail
        }
    }.onFailure { handleAuthError(it) }

    /**
     * Converts an existing personal playlist into a collaborative playlist without deleting or
     * mutating the original personal playlist, attributing existing tracks to the owner.
     */
    suspend fun convertPersonalPlaylist(
        sourcePlaylistId: String,
        sourceType: String,
        title: String,
        description: String = "",
        artworkUrl: String? = null,
        songs: List<Song>,
        permissions: CollabPermissions = CollabPermissions(),
        attributeExistingTracksToOwner: Boolean = true,
    ): Result<CollabConvertResult> = runCatching {
        if (isNativeYouTubeMode) {
            val me = requireNativeGoogleSignIn()
            val sourceKey = "$sourceType:$sourcePlaylistId"
            val existingRawId = synchronized(ytmCollabIds) { ytmConvertedSources[sourceKey] }
            if (!existingRawId.isNullOrBlank() && existingRawId !in ytmStoppedIds) {
                val existingDetail = runCatching { fetchNativeYtmPlaylistDetail(existingRawId) }.getOrNull()
                if (existingDetail != null) {
                    storeDetailSnapshot(existingDetail)
                    return@runCatching CollabConvertResult(playlist = existingDetail, alreadyConverted = true)
                }
            }

            val cleanTitle = title.trim().ifBlank { "Collaborative Playlist" }
            val cleanDesc = description.trim()
            val resolvedSongs = resolveYouTubeSongs(songs)
            val allVideoIds = resolvedSongs.map { it.videoId }
            val rawId = Innertube.createPlaylist(
                title = cleanTitle,
                privacy = PlaylistPrivacy.UNLISTED,
                description = cleanDesc,
                videoIds = allVideoIds.take(50),
            ).removePrefix("VL")
            // Add remaining tracks in chunks of 100 so large personal playlists convert reliably
            allVideoIds.drop(50).chunked(100).forEach { chunk ->
                runCatching { Innertube.addToPlaylist(rawId, chunk, allowDuplicates = true, retryCollaboratorAcl = true) }
            }
            val inviteUrl = runCatching { Innertube.enablePlaylistCollaboration(rawId, ensureUnlisted = false) }
                .recoverCatching { Innertube.enablePlaylistCollaboration(rawId, ensureUnlisted = true) }
                .getOrDefault("https://music.youtube.com/playlist?list=$rawId")
            synchronized(ytmCollabIds) {
                ytmCollabIds.add(rawId)
                ytmStoppedIds.remove(rawId)
                ytmInviteLinks[rawId] = inviteUrl
                ytmConvertedSources[sourceKey] = rawId
            }
            if (!inviteUrl.contains("jct=")) {
                scheduleDeferredInviteLinkFetch(rawId)
            }
            if (attributeExistingTracksToOwner) {
                val now = System.currentTimeMillis()
                allVideoIds.forEach { vid ->
                    localTrackStamps["$rawId:$vid"] = LocalTrackStamp(me.id, me.displayName, me.avatarUrl, now)
                }
            }
            recordLocalActivity(rawId, me, "playlist.convert", "${me.displayName} made \"$cleanTitle\" collaborative (${allVideoIds.size} songs)")
            val detail = fetchNativeYtmPlaylistDetail(
                rawId = rawId,
                fallbackTitle = cleanTitle,
                fallbackDescription = cleanDesc,
                fallbackArtwork = artworkUrl,
                initialSongs = resolvedSongs,
                fallbackPermissions = permissions,
            ).copy(sourceType = sourceType, sourcePlaylistId = sourcePlaylistId)
            storeDetailSnapshot(detail)
            CollabConvertResult(playlist = detail, alreadyConverted = false)
        } else {
            val server = requireServerUrl()
            val tok = requireToken()
            val res = client.convertPlaylist(
                baseUrl = server,
                token = tok,
                sourcePlaylistId = sourcePlaylistId,
                sourceType = sourceType,
                title = title.trim(),
                description = description.trim(),
                artworkUrl = artworkUrl?.takeIf { it.startsWith("http") },
                attributeExistingTracksToOwner = attributeExistingTracksToOwner,
                permissions = permissions,
                tracks = songs.mapNotNull { it.toCollabTrackInput() },
            )
            storeDetailSnapshot(res.playlist)
            res
        }
    }.onFailure { handleAuthError(it) }

    suspend fun updatePlaylistMetadata(
        playlistId: String,
        title: String? = null,
        description: String? = null,
        artworkUrl: String? = null,
        permissions: CollabPermissionsPatch? = null,
    ): Result<CollabPlaylistDetail> = runCatching {
        if (isNativeYouTubeMode || playlistId.startsWith(YTM_PREFIX)) {
            requireNativeGoogleSignIn()
            val rawId = playlistId.removePrefix(YTM_PREFIX).removePrefix("VL")
            val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
            val cleanDesc = description?.trim()
            if (cleanTitle != null) Innertube.renamePlaylist(rawId, cleanTitle)
            if (cleanDesc != null) Innertube.setPlaylistDescription(rawId, cleanDesc)
            val cur = _details.value[playlistId]
            val mergedPerms = if (permissions != null && cur != null) {
                cur.permissions.copy(
                    editorCanRemove = permissions.editorCanRemove ?: cur.permissions.editorCanRemove,
                    editorCanReorder = permissions.editorCanReorder ?: cur.permissions.editorCanReorder,
                    editorCanInvite = permissions.editorCanInvite ?: cur.permissions.editorCanInvite,
                )
            } else cur?.permissions ?: CollabPermissions()
            val fetched = fetchNativeYtmPlaylistDetail(
                rawId = rawId,
                fallbackTitle = cleanTitle ?: cur?.title ?: "Collaborative Playlist",
                fallbackDescription = cleanDesc ?: cur?.description.orEmpty(),
                fallbackArtwork = artworkUrl ?: cur?.artworkUrl,
                fallbackPermissions = mergedPerms,
            )
            val detail = fetched.copy(
                title = cleanTitle ?: fetched.title,
                description = cleanDesc ?: fetched.description,
                permissions = mergedPerms,
            )
            storeDetailSnapshot(detail)
            detail
        } else {
            val server = requireServerUrl()
            val tok = requireToken()
            val opId = rememberOwnOp()
            val detail = client.updatePlaylist(server, tok, playlistId, title, description, artworkUrl, permissions, opId)
            storeDetailSnapshot(detail)
            detail
        }
    }.onFailure { handleAuthError(it) }

    suspend fun stopSharing(playlistId: String): Result<CollabPlaylistDetail> = runCatching {
        if (isNativeYouTubeMode || playlistId.startsWith(YTM_PREFIX)) {
            val me = requireNativeGoogleSignIn()
            val rawId = playlistId.removePrefix(YTM_PREFIX).removePrefix("VL")
            Innertube.closePlaylistCollaboration(rawId, makePrivate = true)
            synchronized(ytmCollabIds) {
                ytmStoppedIds.add(rawId)
                ytmInviteLinks.remove(rawId)
            }
            recordLocalActivity(rawId, me, "playlist.stop_sharing", "${me.displayName} stopped sharing and made the playlist private")
            val detail = fetchNativeYtmPlaylistDetail(rawId).copy(sharingEnabled = false)
            storeDetailSnapshot(detail)
            detail
        } else {
            val server = requireServerUrl()
            val tok = requireToken()
            val detail = client.stopSharing(server, tok, playlistId)
            storeDetailSnapshot(detail)
            detail
        }
    }.onFailure { handleAuthError(it) }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> = runCatching {
        if (isNativeYouTubeMode || playlistId.startsWith(YTM_PREFIX)) {
            requireNativeGoogleSignIn()
            val rawId = playlistId.removePrefix(YTM_PREFIX).removePrefix("VL")
            Innertube.deletePlaylist(rawId)
            synchronized(ytmCollabIds) {
                ytmCollabIds.remove(rawId)
                ytmInviteLinks.remove(rawId)
                ytmStoppedIds.remove(rawId)
            }
            removeLocalPlaylist(playlistId)
        } else {
            val server = requireServerUrl()
            val tok = requireToken()
            client.deletePlaylist(server, tok, playlistId)
            removeLocalPlaylist(playlistId)
        }
    }.onFailure { handleAuthError(it) }

    suspend fun addSongs(playlistId: String, songs: List<Song>, position: Int? = null): Result<CollabPlaylistDetail> = runCatching {
        if (isNativeYouTubeMode || playlistId.startsWith(YTM_PREFIX)) {
            val me = requireNativeGoogleSignIn()
            val rawId = playlistId.removePrefix(YTM_PREFIX).removePrefix("VL")
            val cplId = "$YTM_PREFIX$rawId"
            val resolvedSongs = resolveYouTubeSongs(songs)
            require(resolvedSongs.isNotEmpty()) { "Select playable songs to add to this collaborative playlist." }
            val videoIds = resolvedSongs.map { it.videoId }
            val setMap = HashMap<String, String>()
            videoIds.chunked(100).forEach { chunk ->
                setMap.putAll(
                    Innertube.addToPlaylist(
                        playlistId = rawId,
                        videoIds = chunk,
                        allowDuplicates = true,
                        retryCollaboratorAcl = true,
                    ),
                )
            }
            val now = System.currentTimeMillis()
            resolvedSongs.forEach { s ->
                val stamp = LocalTrackStamp(me.id, me.displayName, me.avatarUrl, now)
                localTrackStamps["$rawId:${s.videoId}"] = stamp
                setMap[s.videoId]?.let { svid -> localTrackStamps["$rawId:$svid"] = stamp }
                recordLocalActivity(rawId, me, "track.add", "${me.displayName} added ${s.title}")
            }
            var detail = fetchNativeYtmPlaylistDetail(rawId)
            // Reconcile read-after-write lag if YouTube's browse replica hasn't shown the newly added rows yet
            val existingKeys = detail.tracks.flatMap { listOf(it.entryId, it.trackId) }.toHashSet()
            val missingOptimistic = resolvedSongs.mapNotNull { s ->
                val svid = setMap[s.videoId] ?: s.videoId
                if (svid in existingKeys || s.videoId in existingKeys) null
                else CollabTrackEntry(
                    entryId = svid,
                    playlistId = cplId,
                    position = detail.tracks.size,
                    trackId = s.videoId,
                    provider = "youtube",
                    title = s.title,
                    artist = s.artist,
                    albumName = s.albumName,
                    thumbnailUrl = s.thumbnailUrl,
                    durationMs = s.toCollabTrackInput()?.durationMs,
                    isExplicit = s.isExplicit == true,
                    contributorId = me.id,
                    contributorName = me.displayName,
                    contributorAvatarUrl = me.avatarUrl,
                    addedAtMs = now,
                )
            }
            if (missingOptimistic.isNotEmpty()) {
                val combined = (detail.tracks + missingOptimistic).mapIndexed { idx, e -> e.copy(position = idx) }
                detail = detail.copy(tracks = combined, trackCount = combined.size)
            }
            storeDetailSnapshot(detail)
            detail
        } else {
            val inputs = songs.mapNotNull { it.toCollabTrackInput() }
            require(inputs.isNotEmpty()) { "No playable tracks to add." }
            val server = requireServerUrl()
            val tok = requireToken()
            val opId = rememberOwnOp()
            val detail = client.addTracks(server, tok, playlistId, inputs, position, opId)
            storeDetailSnapshot(detail)
            detail
        }
    }.onFailure { handleAuthError(it) }

    suspend fun removeTrack(playlistId: String, entryId: String): Result<CollabPlaylistDetail> = runCatching {
        if (isNativeYouTubeMode || playlistId.startsWith(YTM_PREFIX)) {
            val me = requireNativeGoogleSignIn()
            val rawId = playlistId.removePrefix(YTM_PREFIX).removePrefix("VL")
            var current = _details.value[playlistId]
            var targetEntry = current?.tracks?.firstOrNull { it.entryId == entryId }
            // If entryId was an optimistic videoId before YouTube minted setVideoId, refresh once to resolve setVideoId
            if (targetEntry == null || targetEntry.entryId == targetEntry.trackId) {
                val refreshed = runCatching { fetchNativeYtmPlaylistDetail(rawId) }.getOrNull()
                if (refreshed != null) {
                    current = refreshed
                    targetEntry = refreshed.tracks.firstOrNull {
                        it.entryId == entryId || it.trackId == (targetEntry?.trackId ?: entryId)
                    } ?: targetEntry
                }
            }
            val resolvedTarget = targetEntry ?: error("Track entry not found in playlist.")
            Innertube.removeFromPlaylist(rawId, listOf(resolvedTarget.entryId to resolvedTarget.trackId))
            recordLocalActivity(rawId, me, "track.remove", "${me.displayName} removed ${resolvedTarget.title}")
            var detail = fetchNativeYtmPlaylistDetail(rawId)
            if (detail.tracks.any { it.entryId == resolvedTarget.entryId }) {
                val filtered = detail.tracks
                    .filterNot { it.entryId == resolvedTarget.entryId }
                    .mapIndexed { idx, e -> e.copy(position = idx) }
                detail = detail.copy(tracks = filtered, trackCount = filtered.size)
            }
            storeDetailSnapshot(detail)
            detail
        } else {
            val server = requireServerUrl()
            val tok = requireToken()
            val opId = rememberOwnOp()
            val detail = client.removeTrack(server, tok, playlistId, entryId, opId)
            storeDetailSnapshot(detail)
            detail
        }
    }.onFailure { handleAuthError(it) }

    suspend fun reorderTracks(playlistId: String, orderedEntryIds: List<String>): Result<CollabPlaylistDetail> = runCatching {
        if (isNativeYouTubeMode || playlistId.startsWith(YTM_PREFIX)) {
            val me = requireNativeGoogleSignIn()
            val rawId = playlistId.removePrefix(YTM_PREFIX).removePrefix("VL")
            var currentTracks = _details.value[playlistId]?.tracks.orEmpty()
            var effectiveOrderedIds = orderedEntryIds
            if (currentTracks.any { it.entryId == it.trackId }) {
                val refreshed = runCatching { fetchNativeYtmPlaylistDetail(rawId) }.getOrNull()
                if (refreshed != null && refreshed.tracks.size == currentTracks.size) {
                    val oldToNew = currentTracks.zip(refreshed.tracks).associate { (o, n) -> o.entryId to n.entryId }
                    effectiveOrderedIds = orderedEntryIds.map { oldToNew[it] ?: it }
                    currentTracks = refreshed.tracks
                }
            }
            val currentIds = currentTracks.map { it.entryId }
            val moves = playlistMoves(currentIds, effectiveOrderedIds)
            if (moves.isNotEmpty()) {
                moves.chunked(50).forEach { batch ->
                    Innertube.movePlaylistItems(rawId, batch)
                }
                recordLocalActivity(rawId, me, "track.reorder", "${me.displayName} reordered the playlist")
            }
            var detail = fetchNativeYtmPlaylistDetail(rawId)
            val byId = detail.tracks.associateBy { it.entryId }
            if (effectiveOrderedIds.size == detail.tracks.size && effectiveOrderedIds.all { it in byId }) {
                val reordered = effectiveOrderedIds.mapIndexedNotNull { idx, id -> byId[id]?.copy(position = idx) }
                detail = detail.copy(tracks = reordered)
            }
            storeDetailSnapshot(detail)
            detail
        } else {
            val server = requireServerUrl()
            val tok = requireToken()
            val opId = rememberOwnOp()
            val detail = client.reorderTracks(server, tok, playlistId, orderedEntryIds, opId)
            storeDetailSnapshot(detail)
            detail
        }
    }.onFailure { handleAuthError(it) }

    suspend fun createInvite(
        playlistId: String,
        role: CollabRole = CollabRole.EDITOR,
        expiresInSec: Long? = 7L * 24L * 3_600L,
        maxUses: Int = 0,
    ): Result<CollabInviteView> = runCatching {
        if (isNativeYouTubeMode || playlistId.startsWith(YTM_PREFIX)) {
            requireNativeGoogleSignIn()
            val rawId = playlistId.removePrefix(YTM_PREFIX).removePrefix("VL")
            val inviteUrl = if (role == CollabRole.VIEWER) {
                "https://music.youtube.com/playlist?list=$rawId"
            } else {
                Innertube.enablePlaylistCollaboration(rawId).also { url ->
                    synchronized(ytmCollabIds) {
                        ytmStoppedIds.remove(rawId)
                        ytmInviteLinks[rawId] = url
                    }
                    saveToDiskAsync()
                }
            }
            CollabInviteView(
                inviteId = "ytm_inv_$rawId",
                playlistId = playlistId,
                token = inviteUrl.substringAfter("jct=", missingDelimiterValue = rawId).substringBefore('&'),
                role = role.name,
                expiresAtMs = null,
                maxUses = 0,
                useCount = 0,
                createdAtMs = System.currentTimeMillis(),
                inviteUrl = inviteUrl,
            )
        } else {
            val server = requireServerUrl()
            val tok = requireToken()
            client.createInvite(server, tok, playlistId, role, expiresInSec, maxUses)
        }
    }.onFailure { handleAuthError(it) }

    suspend fun listInvites(playlistId: String): Result<List<CollabInviteView>> = runCatching {
        if (isNativeYouTubeMode || playlistId.startsWith(YTM_PREFIX)) {
            val rawId = playlistId.removePrefix(YTM_PREFIX).removePrefix("VL")
            var url = synchronized(ytmCollabIds) { ytmInviteLinks[rawId] }
            if ((url.isNullOrBlank() || !url.contains("jct=")) && rawId !in ytmStoppedIds && Innertube.hasSession) {
                val fetchedUrl = runCatching { Innertube.enablePlaylistCollaboration(rawId) }.getOrNull()
                if (!fetchedUrl.isNullOrBlank()) {
                    url = fetchedUrl
                    synchronized(ytmCollabIds) { ytmInviteLinks[rawId] = fetchedUrl }
                    saveToDiskAsync()
                }
            }
            if (url.isNullOrBlank()) emptyList()
            else listOf(
                CollabInviteView(
                    inviteId = "ytm_inv_$rawId",
                    playlistId = playlistId,
                    token = url.substringAfter("jct=", missingDelimiterValue = rawId).substringBefore('&'),
                    role = CollabRole.EDITOR.name,
                    createdAtMs = System.currentTimeMillis(),
                    inviteUrl = url,
                ),
            )
        } else {
            val server = requireServerUrl()
            val tok = requireToken()
            client.listInvites(server, tok, playlistId)
        }
    }.onFailure { handleAuthError(it) }

    suspend fun revokeInvite(playlistId: String, inviteId: String): Result<Unit> = runCatching {
        if (isNativeYouTubeMode || playlistId.startsWith(YTM_PREFIX)) {
            requireNativeGoogleSignIn()
            val rawId = playlistId.removePrefix(YTM_PREFIX).removePrefix("VL")
            Innertube.closePlaylistCollaboration(rawId, makePrivate = false)
            synchronized(ytmCollabIds) { ytmInviteLinks.remove(rawId) }
            saveToDiskAsync()
        } else {
            val server = requireServerUrl()
            val tok = requireToken()
            client.revokeInvite(server, tok, playlistId, inviteId)
        }
    }.onFailure { handleAuthError(it) }

    suspend fun previewInvite(rawLinkOrToken: String): Result<Pair<ParsedCollabInvite, CollabInvitePreview>> = runCatching {
        val parsed = CollabInviteLink.parse(rawLinkOrToken)
            ?: error("Paste a YouTube Music collaboration link (https://music.youtube.com/playlist?list=…&jct=…) or bci_… invite link.")
        if (parsed.isYouTubeMusicInvite) {
            val rawId = parsed.ytmPlaylistId!!
            val browse = runCatching { Innertube.browse("VL$rawId") }.getOrNull()
            val meta = browse?.let { InnertubeParser.parseYtmCollabHeader(it, rawId) }
            val shelf = browse?.let { InnertubeParser.parsePlaylistShelf(it) }
            val preview = CollabInvitePreview(
                playlistId = "$YTM_PREFIX$rawId",
                title = meta?.title?.takeIf { it.isNotBlank() } ?: "Collaborative Playlist",
                description = meta?.description.orEmpty(),
                artworkUrl = meta?.thumbnailUrl ?: shelf?.songs?.firstOrNull()?.thumbnailUrl,
                ownerName = meta?.ownerName?.takeIf { it.isNotBlank() } ?: "YouTube Music",
                ownerAvatarUrl = meta?.facepileAvatars?.firstOrNull(),
                memberCount = (meta?.facepileAvatars?.size ?: 1).coerceAtLeast(1),
                trackCount = shelf?.songs?.size ?: 0,
                role = "EDITOR",
                expired = false,
                revoked = false,
            )
            parsed to preview
        } else {
            adoptServerOriginIfUnset(parsed.serverOrigin)
            val server = parsed.serverOrigin ?: requireServerUrl()
            val preview = client.previewInvite(server, parsed.token)
            parsed.copy(serverOrigin = server) to preview
        }
    }

    suspend fun acceptInvite(rawLinkOrToken: String): Result<CollabAcceptInviteResult> = runCatching {
        val parsed = CollabInviteLink.parse(rawLinkOrToken)
            ?: error("Invalid collaborative playlist invite link.")
        if (parsed.isYouTubeMusicInvite) {
            val me = requireNativeGoogleSignIn()
            val rawId = parsed.ytmPlaylistId!!
            val jct = parsed.ytmJoinToken!!
            val wasAlreadyTracked = synchronized(ytmCollabIds) { rawId in ytmCollabIds }
            Innertube.joinCollaborativePlaylist(rawId, jct)
            synchronized(ytmCollabIds) {
                ytmCollabIds.add(rawId)
                ytmStoppedIds.remove(rawId)
                ytmInviteLinks[rawId] = "https://music.youtube.com/playlist?list=$rawId&jct=$jct"
            }
            if (!wasAlreadyTracked) {
                recordLocalActivity(rawId, me, "member.join", "${me.displayName} joined via invite link")
            }
            val detail = fetchNativeYtmPlaylistDetail(rawId)
            storeDetailSnapshot(detail)
            CollabAcceptInviteResult(playlist = detail, alreadyMember = wasAlreadyTracked)
        } else {
            adoptServerOriginIfUnset(parsed.serverOrigin)
            val server = parsed.serverOrigin ?: requireServerUrl()
            val tok = requireToken()
            val res = client.acceptInvite(server, tok, parsed.token)
            storeDetailSnapshot(res.playlist)
            res
        }
    }.onFailure { handleAuthError(it) }

    suspend fun updateMemberRole(playlistId: String, userId: String, role: CollabRole): Result<CollabPlaylistDetail> = runCatching {
        if (isNativeYouTubeMode || playlistId.startsWith(YTM_PREFIX)) {
            error("YouTube Music collaborative playlists grant all collaborators Editor access.")
        }
        val server = requireServerUrl()
        val tok = requireToken()
        val detail = client.updateMemberRole(server, tok, playlistId, userId, role)
        storeDetailSnapshot(detail)
        detail
    }.onFailure { handleAuthError(it) }

    suspend fun transferOwnership(playlistId: String, newOwnerUserId: String): Result<CollabPlaylistDetail> = runCatching {
        if (isNativeYouTubeMode || playlistId.startsWith(YTM_PREFIX)) {
            error("Playlist ownership is tied to your YouTube Music channel.")
        }
        val server = requireServerUrl()
        val tok = requireToken()
        val detail = client.transferOwnership(server, tok, playlistId, newOwnerUserId)
        storeDetailSnapshot(detail)
        detail
    }.onFailure { handleAuthError(it) }

    suspend fun removeMember(playlistId: String, userId: String): Result<CollabPlaylistDetail?> = runCatching {
        if (isNativeYouTubeMode || playlistId.startsWith(YTM_PREFIX)) {
            error("Use Stop Sharing or Revoke Invite Link to close YouTube Music collaboration.")
        }
        val server = requireServerUrl()
        val tok = requireToken()
        val detail = client.removeMember(server, tok, playlistId, userId)
        if (detail != null) storeDetailSnapshot(detail)
        detail
    }.onFailure { handleAuthError(it) }

    suspend fun leavePlaylist(playlistId: String): Result<Unit> = runCatching {
        if (isNativeYouTubeMode || playlistId.startsWith(YTM_PREFIX)) {
            requireNativeGoogleSignIn()
            val rawId = playlistId.removePrefix(YTM_PREFIX).removePrefix("VL")
            Innertube.leaveCollaborativePlaylist(rawId)
            synchronized(ytmCollabIds) { ytmCollabIds.remove(rawId) }
            removeLocalPlaylist(playlistId)
        } else {
            val server = requireServerUrl()
            val tok = requireToken()
            client.leavePlaylist(server, tok, playlistId)
            removeLocalPlaylist(playlistId)
        }
    }.onFailure { handleAuthError(it) }

    /** Subscribes [playlistId] for live collaborator updates (WebSocket or 12s Innertube sync). */
    fun subscribePlaylist(playlistId: String) {
        synchronized(activeSubscriptions) {
            activeSubscriptions.add(playlistId)
        }
        if (isNativeYouTubeMode || playlistId.startsWith(YTM_PREFIX)) {
            ensureYtmPollingRunning()
            return
        }
        val rev = _details.value[playlistId]?.revision ?: -1L
        val frame = buildJsonObject {
            put("type", "subscribe")
            put("playlistId", playlistId)
            put("sinceRevision", rev)
        }
        val sent = outboundFrames?.trySend(diskJson.encodeToString(frame))?.isSuccess == true
        if (!sent) ensureRealtimeRunning()
    }

    fun unsubscribePlaylist(playlistId: String) {
        val empty = synchronized(activeSubscriptions) {
            activeSubscriptions.remove(playlistId)
            activeSubscriptions.isEmpty()
        }
        if (!isNativeYouTubeMode && !playlistId.startsWith(YTM_PREFIX)) {
            val frame = buildJsonObject {
                put("type", "unsubscribe")
                put("playlistId", playlistId)
            }
            outboundFrames?.trySend(diskJson.encodeToString(frame))
        }
        if (empty) stopRealtime()
    }

    // ---- Native YouTube Music Collaboration Helpers -------------------------

    private suspend fun discoverNativeYouTubeCollabPlaylists(): List<CollabPlaylistSummary> = coroutineScope {
        val libraryItems = YtMusicRepository.libraryPlaylists().getOrNull().orEmpty()
        val editableIds = Innertube.getEditablePlaylistIdsForCurrentUser()
        val trackedIds = synchronized(ytmCollabIds) { ytmCollabIds.toSet() }
        val candidateRawIds = LinkedHashSet<String>()
        candidateRawIds.addAll(trackedIds)
        libraryItems.forEach { item ->
            val bid = item.browseId?.removePrefix("VL")?.trim().orEmpty()
            if (bid.isNotEmpty() && bid != "LM" && bid != "SE" && !bid.startsWith("RD")) {
                candidateRawIds.add(bid)
            }
        }

        val summaries = ArrayList<CollabPlaylistSummary>()
        // Inspect in small concurrent batches so Library loads fast
        for (batch in candidateRawIds.take(30).chunked(6)) {
            val fetched = batch.map { rawId ->
                async {
                    runCatching {
                        val browse = Innertube.browse("VL$rawId")
                        val meta = InnertubeParser.parseYtmCollabHeader(browse, rawId) ?: return@runCatching null
                        val isKnown = rawId in trackedIds
                        if (!meta.isCollaborative && !isKnown) return@runCatching null
                        synchronized(ytmCollabIds) { ytmCollabIds.add(rawId) }
                        val shelf = InnertubeParser.parsePlaylistShelf(browse)
                        val rowContribs = InnertubeParser.parsePlaylistTrackContributors(browse)
                        val distinctContribCount = (
                            setOf(meta.ownerName.lowercase()) +
                                rowContribs.values.map { it.name.lowercase() }
                            ).filter { it.isNotBlank() }.size
                        val summaryCount = parseSummaryCollaboratorCount(meta.collaboratorSummary)
                        val effectiveMemberCount = maxOf(
                            1,
                            meta.facepileAvatars.size,
                            distinctContribCount,
                            summaryCount,
                            _details.value["$YTM_PREFIX$rawId"]?.memberCount ?: 1,
                        )
                        val me = currentUser
                        val stopped = rawId in ytmStoppedIds
                        CollabPlaylistSummary(
                            id = "$YTM_PREFIX$rawId",
                            title = meta.title,
                            description = meta.description,
                            artworkUrl = meta.thumbnailUrl ?: shelf?.songs?.firstOrNull()?.thumbnailUrl,
                            ownerUserId = if (meta.owned) (me?.id ?: "ytm:self") else "ytm:owner:$rawId",
                            ownerName = if (meta.owned) (me?.displayName ?: meta.ownerName) else meta.ownerName,
                            ownerAvatarUrl = if (meta.owned) (me?.avatarUrl ?: meta.facepileAvatars.firstOrNull()) else meta.facepileAvatars.firstOrNull(),
                            myRole = when {
                                meta.owned -> CollabRole.OWNER.name
                                rawId in editableIds || isKnown -> CollabRole.EDITOR.name
                                else -> CollabRole.VIEWER.name
                            },
                            sharingEnabled = !stopped,
                            memberCount = effectiveMemberCount,
                            memberAvatars = meta.facepileAvatars.ifEmpty {
                                listOfNotNull(me?.avatarUrl)
                            }.distinct().take(5),
                            trackCount = shelf?.songs?.size ?: 0,
                            revision = (_details.value["$YTM_PREFIX$rawId"]?.revision ?: 1L),
                            updatedAtMs = System.currentTimeMillis(),
                        )
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
            summaries.addAll(fetched)
        }
        summaries.sortedByDescending { it.updatedAtMs }
    }

    private suspend fun fetchNativeYtmPlaylistDetail(
        rawId: String,
        fallbackTitle: String = "Collaborative Playlist",
        fallbackDescription: String = "",
        fallbackArtwork: String? = null,
        initialSongs: List<Song> = emptyList(),
        fallbackPermissions: CollabPermissions? = null,
    ): CollabPlaylistDetail {
        val cplId = "$YTM_PREFIX$rawId"
        val prev = _details.value[cplId]
        val me = currentUser
        val browse = runCatching { Innertube.browse("VL$rawId") }.getOrNull()
        val meta = browse?.let { InnertubeParser.parseYtmCollabHeader(it, rawId) }
        val shelf = browse?.let { InnertubeParser.parsePlaylistShelf(it) }
        val rowContribMap = browse?.let { InnertubeParser.parsePlaylistTrackContributors(it) }.orEmpty()

        // 1. Fetch collaborator roster from PAplaylist_collaborate panel (WEB_REMIX + WEB fallback)
        //    and any inline panel inside browse.
        val inlinePanelMembers = browse?.let { InnertubeParser.parseCollaboratorsPanel(it) }.orEmpty()
        val fetchedPanelMembers = if (meta?.isCollaborative == true || !meta?.panelParams.isNullOrBlank()) {
            runCatching {
                InnertubeParser.parseCollaboratorsPanel(
                    Innertube.getCollaboratorsPanel(meta?.panelParams, rawId),
                )
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val panelMembers = (fetchedPanelMembers + inlinePanelMembers)
            .distinctBy { (_, name, _) -> name.lowercase() }

        val owned = meta?.owned ?: prev?.let { it.myRole == CollabRole.OWNER.name } ?: true
        val ownerUserId = if (owned) (me?.id ?: "ytm:self") else (panelMembers.firstOrNull()?.first ?: "ytm:owner:$rawId")
        val ownerName = if (owned) (me?.displayName ?: meta?.ownerName ?: "You") else (meta?.ownerName ?: "Owner")
        val ownerAvatar = if (owned) (me?.avatarUrl ?: meta?.facepileAvatars?.firstOrNull()) else meta?.facepileAvatars?.firstOrNull()

        // 2. Merge panel members + track contributors + current user (if collaborator) + facepile avatars
        val members = ArrayList<CollabMember>()
        fun addOrMergeMember(userId: String, role: CollabRole, displayName: String, avatarUrl: String?) {
            val cleanName = displayName.trim().ifBlank { "Collaborator" }
            val existingIdx = members.indexOfFirst {
                it.userId == userId || it.displayName.equals(cleanName, ignoreCase = true)
            }
            if (existingIdx >= 0) {
                val cur = members[existingIdx]
                if (cur.avatarUrl == null && avatarUrl != null) {
                    members[existingIdx] = cur.copy(avatarUrl = avatarUrl)
                }
                return
            }
            members.add(
                CollabMember(
                    playlistId = cplId,
                    userId = userId,
                    role = role.name,
                    displayName = cleanName,
                    avatarUrl = avatarUrl,
                ),
            )
        }

        if (panelMembers.isNotEmpty()) {
            panelMembers.forEachIndexed { idx, (cid, name, av) ->
                val isMe = me != null && name.equals(me.displayName, ignoreCase = true)
                val resolvedId = when {
                    isMe -> me.id
                    idx == 0 && owned -> ownerUserId
                    cid.isNotBlank() -> cid
                    else -> "ytm:${name.lowercase()}"
                }
                val role = if (idx == 0) CollabRole.OWNER else CollabRole.EDITOR
                addOrMergeMember(resolvedId, role, name, av ?: (if (isMe) me.avatarUrl else null))
            }
        } else {
            addOrMergeMember(ownerUserId, CollabRole.OWNER, ownerName, ownerAvatar)
        }

        if (!owned && me != null) {
            addOrMergeMember(me.id, CollabRole.EDITOR, me.displayName, me.avatarUrl)
        }

        // Include every track contributor discovered in browse rows
        rowContribMap.values.forEach { info ->
            val isMe = me != null && info.name.equals(me.displayName, ignoreCase = true)
            val isOwner = info.name.equals(ownerName, ignoreCase = true)
            val uid = when {
                isMe -> me.id
                isOwner -> ownerUserId
                !info.channelId.isNullOrBlank() -> info.channelId
                else -> "ytm:${info.name.lowercase()}"
            }
            addOrMergeMember(uid, if (isOwner) CollabRole.OWNER else CollabRole.EDITOR, info.name, info.avatarUrl)
        }

        // If YouTube's header facepile or "by Owner and <Name / N others>" reports collaborators
        // who haven't added a track yet and weren't returned by get_panel, surface them immediately!
        val facepileAvatars = meta?.facepileAvatars.orEmpty()
        val summaryTotal = maxOf(facepileAvatars.size, parseSummaryCollaboratorCount(meta?.collaboratorSummary))
        if (summaryTotal > members.size) {
            val usedAvatars = members.mapNotNull { it.avatarUrl }.toHashSet()
            val unusedAvatars = facepileAvatars.filterNot { it in usedAvatars }
            val missingCount = summaryTotal - members.size
            for (idx in 0 until missingCount) {
                val av = unusedAvatars.getOrNull(idx) ?: facepileAvatars.getOrNull(members.size)
                addOrMergeMember(
                    userId = "ytm:facepile:${rawId}_${members.size}",
                    role = CollabRole.EDITOR,
                    displayName = "Collaborator ${members.size}",
                    avatarUrl = av,
                )
            }
        }

        // Record live activity when collaborator count grows on the owner's/member's open playlist
        if (prev != null && members.size > prev.members.size) {
            val prevNames = prev.members.map { it.displayName.lowercase() }.toHashSet()
            val newlyJoined = members.filterNot { it.displayName.lowercase() in prevNames }
            newlyJoined.forEach { joinedMember ->
                recordLocalActivity(
                    rawId = rawId,
                    actor = CollabUser(
                        id = joinedMember.userId,
                        email = "",
                        emailVerified = true,
                        displayName = joinedMember.displayName,
                        avatarUrl = joinedMember.avatarUrl,
                    ),
                    action = "member.join",
                    summary = "${joinedMember.displayName} joined the collaborative playlist",
                )
            }
        }

        val effectiveSongs = shelf?.songs?.takeIf { it.isNotEmpty() } ?: initialSongs
        val entries = effectiveSongs.mapIndexed { idx, song ->
            val setVid = song.setVideoId ?: song.videoId
            val rowFromYtm = rowContribMap[setVid] ?: rowContribMap[song.videoId]
            val localStamp = localTrackStamps["$rawId:$setVid"] ?: localTrackStamps["$rawId:${song.videoId}"]

            val contribName = rowFromYtm?.name ?: localStamp?.displayName ?: ownerName
            val contribAvatar = rowFromYtm?.avatarUrl ?: localStamp?.avatarUrl ?: ownerAvatar
            val contribId = when {
                !rowFromYtm?.channelId.isNullOrBlank() && (me == null || !contribName.equals(me.displayName, ignoreCase = true)) -> rowFromYtm!!.channelId!!
                localStamp != null -> localStamp.userId
                me != null && contribName.equals(me.displayName, ignoreCase = true) -> me.id
                contribName.equals(ownerName, ignoreCase = true) -> ownerUserId
                else -> members.firstOrNull { it.displayName.equals(contribName, ignoreCase = true) }?.userId ?: "ytm:$contribName"
            }

            CollabTrackEntry(
                entryId = setVid,
                playlistId = cplId,
                position = idx,
                trackId = song.videoId,
                provider = "youtube",
                title = song.title,
                artist = song.artist,
                albumName = song.albumName,
                thumbnailUrl = song.thumbnailUrl,
                durationMs = song.toCollabTrackInput()?.durationMs,
                isExplicit = song.isExplicit == true,
                contributorId = contribId,
                contributorName = contribName,
                contributorAvatarUrl = contribAvatar,
                addedAtMs = localStamp?.addedAtMs ?: 0L,
            )
        }

        val prevRev = prev?.revision ?: 0L
        val stopped = rawId in ytmStoppedIds
        return CollabPlaylistDetail(
            id = cplId,
            title = meta?.title?.takeIf { it.isNotBlank() } ?: prev?.title ?: fallbackTitle,
            description = meta?.description?.takeIf { it.isNotBlank() } ?: prev?.description?.takeIf { it.isNotBlank() } ?: fallbackDescription,
            artworkUrl = meta?.thumbnailUrl ?: prev?.artworkUrl ?: fallbackArtwork ?: entries.firstOrNull()?.thumbnailUrl,
            ownerUserId = ownerUserId,
            ownerName = ownerName,
            ownerAvatarUrl = ownerAvatar,
            myRole = if (owned) CollabRole.OWNER.name else CollabRole.EDITOR.name,
            sharingEnabled = !stopped,
            permissions = fallbackPermissions ?: prev?.permissions ?: CollabPermissions(
                editorCanRemove = "any",
                editorCanReorder = true,
                editorCanInvite = true,
            ),
            memberCount = maxOf(members.size, summaryTotal, 1),
            memberAvatars = (facepileAvatars + members.mapNotNull { it.avatarUrl }).distinct().take(5),
            trackCount = entries.size,
            revision = prevRev + 1L,
            updatedAtMs = System.currentTimeMillis(),
            members = members,
            tracks = entries,
            activities = synchronized(localActivities) { localActivities[rawId].orEmpty() },
        )
    }

    private fun parseSummaryCollaboratorCount(summary: String?): Int {
        val text = summary?.trim()?.takeIf { it.isNotEmpty() } ?: return 0
        val othersMatch = OTHERS_COUNT_REGEX.find(text)
        if (othersMatch != null) {
            val others = othersMatch.groupValues[1].toIntOrNull() ?: 0
            return 1 + others
        }
        return 1
    }

    /**
     * Resolves songs to playable YouTube Music tracks with valid 11-character `videoId`s so
     * converting or adding local/Spotify songs never sends `local:…` IDs to YouTube's playlist API.
     */
    private suspend fun resolveYouTubeSongs(songs: List<Song>): List<Song> {
        if (songs.isEmpty()) return emptyList()
        val out = ArrayList<Song>(songs.size)
        for (song in songs) {
            val vid = song.videoId.trim()
            if (YT_VIDEO_ID_PATTERN.matches(vid)) {
                out.add(if (vid == song.videoId) song else song.copy(videoId = vid))
            } else if (song.title.isNotBlank()) {
                val matched: Song? = runCatching {
                    val query = listOf(song.title, song.artist).filter(String::isNotBlank).joinToString(" ")
                    val sr = Innertube.search(query)
                    InnertubeParser.parseSearchSongs(sr).firstOrNull { YT_VIDEO_ID_PATTERN.matches(it.videoId) }
                        ?: InnertubeParser.parseSearch(sr).firstNotNullOfOrNull { item ->
                            when (item) {
                                is com.music.bitchord.data.model.SearchResult.TopTrack -> item.song
                                is com.music.bitchord.data.model.SearchResult.Track -> item.song
                                else -> null
                            }?.takeIf { YT_VIDEO_ID_PATTERN.matches(it.videoId) }
                        }
                }.getOrNull()
                if (matched != null) {
                    out.add(song.copy(videoId = matched.videoId, thumbnailUrl = song.thumbnailUrl ?: matched.thumbnailUrl))
                }
            }
        }
        return out
    }

    /** Background retry when YouTube Music's newly created playlist takes a few seconds to settle before minting `jct=`. */
    private fun scheduleDeferredInviteLinkFetch(rawId: String) {
        scope.launch {
            for (waitMs in listOf(2_500L, 5_000L)) {
                delay(if (Innertube.settleRetryDelayMs < 100L) 20L else waitMs)
                if (rawId in ytmStoppedIds || !Innertube.hasSession) return@launch
                val currentUrl = synchronized(ytmCollabIds) { ytmInviteLinks[rawId] }
                if (!currentUrl.isNullOrBlank() && currentUrl.contains("jct=")) return@launch
                val url = runCatching { Innertube.enablePlaylistCollaboration(rawId, ensureUnlisted = true) }.getOrNull()
                if (!url.isNullOrBlank() && url.contains("jct=")) {
                    synchronized(ytmCollabIds) { ytmInviteLinks[rawId] = url }
                    saveToDiskAsync()
                    return@launch
                }
            }
        }
    }

    private fun recordLocalActivity(rawId: String, actor: CollabUser, action: String, summary: String) {
        synchronized(localActivities) {
            val next = CollabActivity(
                id = "act_${System.currentTimeMillis()}_${(100..999).random()}",
                playlistId = "$YTM_PREFIX$rawId",
                actorUserId = actor.id,
                actorName = actor.displayName,
                actorAvatarUrl = actor.avatarUrl,
                action = action,
                summary = summary,
                createdAtMs = System.currentTimeMillis(),
            )
            localActivities[rawId] = (listOf(next) + localActivities[rawId].orEmpty()).take(50)
        }
    }

    private fun ensureYtmPollingRunning() {
        if (ytmPollJob?.isActive == true) return
        ytmPollJob = scope.launch {
            _realtimeConnected.value = true
            try {
                while (isActive) {
                    delay(if (Innertube.settleRetryDelayMs < 100L) 60L else 5_000L)
                    val subs = synchronized(activeSubscriptions) { activeSubscriptions.toList() }
                    if (subs.isEmpty()) break
                    if (!Innertube.hasSession) continue
                    for (pid in subs) {
                        if (pid.startsWith(YTM_PREFIX) || isNativeYouTubeMode) {
                            val rawId = pid.removePrefix(YTM_PREFIX).removePrefix("VL")
                            runCatching { fetchNativeYtmPlaylistDetail(rawId) }
                                .getOrNull()
                                ?.let(::storeDetailSnapshot)
                        }
                    }
                }
            } finally {
                _realtimeConnected.value = false
            }
        }
    }

    // ---- Custom Backend Realtime WebSocket ----------------------------------

    private fun ensureRealtimeRunning() {
        if (isNativeYouTubeMode) {
            ensureYtmPollingRunning()
            return
        }
        if (wsJob?.isActive == true) return
        if (!isAuthenticated || effectiveServerUrl().isBlank()) return
        val hasSubs = synchronized(activeSubscriptions) { activeSubscriptions.isNotEmpty() }
        if (!hasSubs) return

        wsJob = scope.launch {
            var attempt = 0
            while (isActive && isAuthenticated) {
                val subsSnapshot = synchronized(activeSubscriptions) {
                    if (activeSubscriptions.isEmpty()) return@launch
                    activeSubscriptions.associateWith { pid -> _details.value[pid]?.revision ?: -1L }
                }
                val server = effectiveServerUrl().takeIf { it.isNotBlank() } ?: break
                val tok = _session.value?.token?.takeIf { it.isNotBlank() } ?: break
                val channel = Channel<String>(Channel.BUFFERED)
                outboundFrames = channel
                try {
                    _realtimeConnected.value = true
                    attempt = 0
                    client.runRealtimeSocket(
                        baseUrl = server,
                        token = tok,
                        initialSubscriptions = subsSnapshot,
                        outbound = channel,
                        onEvent = ::applyRealtimeEvent,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // Exponential backoff with cap at 20s
                } finally {
                    _realtimeConnected.value = false
                    outboundFrames = null
                    channel.close()
                }
                attempt = (attempt + 1).coerceAtMost(5)
                delay((1_000L shl (attempt - 1)).coerceAtMost(20_000L))
            }
        }
    }

    private fun restartRealtimeIfNeeded() {
        stopRealtime()
        ensureRealtimeRunning()
    }

    private fun stopRealtime() {
        wsJob?.cancel()
        wsJob = null
        ytmPollJob?.cancel()
        ytmPollJob = null
        outboundFrames?.close()
        outboundFrames = null
        _realtimeConnected.value = false
    }

    internal fun applyRealtimeEvent(event: CollabRealtimeEvent) {
        when (event) {
            is CollabRealtimeEvent.Snapshot -> storeDetailSnapshot(event.playlist)
            is CollabRealtimeEvent.Subscribed -> Unit
            is CollabRealtimeEvent.TracksChanged -> {
                val current = _details.value[event.playlistId] ?: return
                if (event.revision <= current.revision && isOwnOp(event.opId)) return
                if (event.revision < current.revision) return
                val updatedActivities = prependActivity(current.activities, event.latestActivity)
                val updated = current.copy(
                    tracks = event.tracks,
                    trackCount = event.tracks.size,
                    revision = event.revision,
                    activities = updatedActivities,
                )
                storeDetailSnapshot(updated)
            }
            is CollabRealtimeEvent.MetadataChanged -> {
                val current = _details.value[event.playlistId]
                if (current != null && event.revision >= current.revision) {
                    val s = event.summary
                    val updated = current.copy(
                        title = s.title,
                        description = s.description,
                        artworkUrl = s.artworkUrl,
                        sharingEnabled = s.sharingEnabled,
                        permissions = s.permissions,
                        memberCount = s.memberCount,
                        trackCount = s.trackCount,
                        revision = event.revision,
                        updatedAtMs = s.updatedAtMs,
                        activities = prependActivity(current.activities, event.latestActivity),
                    )
                    storeDetailSnapshot(updated)
                } else {
                    upsertSummary(event.summary)
                }
            }
            is CollabRealtimeEvent.MemberJoined -> {
                val current = _details.value[event.playlistId] ?: return
                if (event.revision < current.revision) return
                val updated = current.copy(
                    members = event.members,
                    memberCount = event.members.size,
                    revision = event.revision,
                    activities = prependActivity(current.activities, event.latestActivity),
                )
                storeDetailSnapshot(updated)
            }
            is CollabRealtimeEvent.MembersChanged -> {
                val current = _details.value[event.playlistId] ?: return
                if (event.revision < current.revision) return
                val me = currentUser?.id
                val newMyRole = event.members.firstOrNull { it.userId == me }?.role ?: current.myRole
                val newOwner = event.members.firstOrNull { it.parsedRole == CollabRole.OWNER }
                val updated = current.copy(
                    ownerUserId = newOwner?.userId ?: current.ownerUserId,
                    ownerName = newOwner?.displayName ?: current.ownerName,
                    ownerAvatarUrl = newOwner?.avatarUrl ?: current.ownerAvatarUrl,
                    myRole = newMyRole,
                    members = event.members,
                    memberCount = event.members.size,
                    revision = event.revision,
                    activities = prependActivity(current.activities, event.latestActivity),
                )
                storeDetailSnapshot(updated)
            }
            is CollabRealtimeEvent.MemberRemoved -> {
                if (event.userId == currentUser?.id) {
                    _notices.tryEmit(event.message)
                    removeLocalPlaylist(event.playlistId)
                }
            }
            is CollabRealtimeEvent.Stopped -> {
                _notices.tryEmit(event.message)
                val current = _details.value[event.playlistId]
                if (current != null && current.ownerUserId != currentUser?.id) {
                    removeLocalPlaylist(event.playlistId)
                } else if (current != null) {
                    storeDetailSnapshot(current.copy(sharingEnabled = false))
                }
            }
            is CollabRealtimeEvent.ServerError -> _notices.tryEmit(event.message)
        }
    }

    private fun prependActivity(existing: List<CollabActivity>, added: CollabActivity?): List<CollabActivity> {
        if (added == null || existing.any { it.id == added.id }) return existing
        return (listOf(added) + existing).take(100)
    }

    private fun rememberOwnOp(): String {
        val op = CollabClient.newOpId()
        synchronized(recentOwnOps) {
            if (recentOwnOps.size >= 64) recentOwnOps.removeFirst()
            recentOwnOps.addLast(op)
        }
        return op
    }

    private fun isOwnOp(opId: String?): Boolean {
        if (opId.isNullOrBlank()) return false
        return synchronized(recentOwnOps) { opId in recentOwnOps }
    }

    private fun applyAuthenticatedCustomSession(auth: CollabAuthSession) {
        _customServerSession.value = auth
        recomputeEffectiveSession()
        saveToDiskAsync()
        ensureRealtimeRunning()
    }

    private fun storeDetailSnapshot(detail: CollabPlaylistDetail) {
        _details.update { it + (detail.id to detail) }
        upsertSummary(detail.toSummary())
    }

    private fun upsertSummary(summary: CollabPlaylistSummary) {
        _playlists.update { current ->
            val filtered = current.filterNot { it.id == summary.id }
            (listOf(summary) + filtered).sortedByDescending { it.updatedAtMs }
        }
        saveToDiskAsync()
    }

    private fun removeLocalPlaylist(playlistId: String) {
        unsubscribePlaylist(playlistId)
        _details.update { it - playlistId }
        _playlists.update { list -> list.filterNot { it.id == playlistId } }
        saveToDiskAsync()
    }

    private fun handleAuthError(error: Throwable) {
        if (!isNativeYouTubeMode && error is CollabApiException && error.status == 401) {
            _customServerSession.value = null
            recomputeEffectiveSession()
            stopRealtime()
            saveToDiskAsync()
            _notices.tryEmit("Custom collaboration server session expired. Sign in again or clear Server URL.")
        } else if (error !is CancellationException) {
            error.message?.takeIf { it.isNotBlank() }?.let(_notices::tryEmit)
        }
    }

    private fun requireNativeGoogleSignIn(): CollabUser {
        recomputeEffectiveSession()
        val u = _session.value?.user
        if (u != null && Innertube.hasSession) return u
        throw CollabApiException(401, "auth_required", "Sign in to BitChord with your Google account first.")
    }

    private fun requireServerUrl(): String {
        val url = effectiveServerUrl()
        require(url.isNotBlank()) { "Configure a Collaboration Server URL in Settings first." }
        return url
    }

    private fun requireToken(): String {
        val s = _customServerSession.value ?: throw CollabApiException(401, "auth_required", "Sign in with Google to use this collaboration server.")
        if (s.expiresAtMs > 0L && s.expiresAtMs <= System.currentTimeMillis()) {
            _customServerSession.value = null
            recomputeEffectiveSession()
            throw CollabApiException(401, "auth_required", "Session expired. Please sign in again.")
        }
        return s.token
    }

    private fun saveToDiskAsync() {
        scope.launch { saveToDisk() }
    }

    internal suspend fun flushToDisk() = saveToDisk()

    private suspend fun ensureDiskLoaded() {
        if (diskLoaded) return
        diskMutex.withLock {
            if (diskLoaded) return
            loadFromDiskLocked()
            diskLoaded = true
        }
    }

    private suspend fun saveToDisk() = withContext(Dispatchers.IO) {
        ensureDiskLoaded()
        val target = storageFileProvider() ?: return@withContext
        diskMutex.withLock {
            runCatching {
                target.parentFile?.mkdirs()
                val snapshot = PersistedState(
                    serverUrlOverride = _serverUrlOverride.value,
                    googleClientIdOverride = _googleClientIdOverride.value,
                    customServerSession = _customServerSession.value,
                    playlists = _playlists.value,
                    details = _details.value,
                    ytmCollabIds = synchronized(ytmCollabIds) { ytmCollabIds.toSet() },
                    ytmInviteLinks = synchronized(ytmCollabIds) { LinkedHashMap(ytmInviteLinks) },
                    ytmConvertedSources = synchronized(ytmCollabIds) { LinkedHashMap(ytmConvertedSources) },
                    ytmStoppedIds = synchronized(ytmCollabIds) { ytmStoppedIds.toSet() },
                    localTrackStamps = LinkedHashMap(localTrackStamps),
                    localActivities = synchronized(localActivities) { LinkedHashMap(localActivities) },
                )
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeText(diskJson.encodeToString(snapshot))
                if (!tmp.renameTo(target)) {
                    target.writeText(tmp.readText())
                    tmp.delete()
                }
            }
        }
    }

    private suspend fun loadFromDiskLocked() = withContext(Dispatchers.IO) {
        val target = storageFileProvider() ?: return@withContext
        if (!target.isFile) return@withContext
        runCatching {
            val loaded = diskJson.decodeFromString<PersistedState>(target.readText())
            val cleanedOverride = loaded.serverUrlOverride.takeUnless {
                it.contains("bitchord-listen-together.onrender.com", ignoreCase = true) ||
                    it.contains("bitchord.kushagrasingh.in", ignoreCase = true)
            }.orEmpty()
            if (_serverUrlOverride.value.isBlank()) _serverUrlOverride.value = cleanedOverride
            if (_googleClientIdOverride.value.isBlank()) _googleClientIdOverride.value = loaded.googleClientIdOverride
            if (_customServerSession.value == null &&
                loaded.customServerSession != null &&
                (loaded.customServerSession.expiresAtMs <= 0L || loaded.customServerSession.expiresAtMs > System.currentTimeMillis())
            ) {
                _customServerSession.value = loaded.customServerSession
            }
            synchronized(ytmCollabIds) {
                ytmCollabIds.addAll(loaded.ytmCollabIds)
                loaded.ytmInviteLinks.forEach { (k, v) -> ytmInviteLinks.putIfAbsent(k, v) }
                loaded.ytmConvertedSources.forEach { (k, v) -> ytmConvertedSources.putIfAbsent(k, v) }
                ytmStoppedIds.addAll(loaded.ytmStoppedIds)
            }
            loaded.localTrackStamps.forEach { (k, v) -> localTrackStamps.putIfAbsent(k, v) }
            synchronized(localActivities) {
                loaded.localActivities.forEach { (k, v) -> localActivities.putIfAbsent(k, v) }
            }
            if (_playlists.value.isEmpty()) {
                _playlists.value = loaded.playlists
            } else {
                val currentIds = _playlists.value.map { it.id }.toHashSet()
                _playlists.value = (_playlists.value + loaded.playlists.filterNot { it.id in currentIds })
                    .sortedByDescending { it.updatedAtMs }
            }
            if (_details.value.isEmpty()) {
                _details.value = loaded.details
            } else {
                _details.value = loaded.details + _details.value
            }
        }
    }

    companion object {
        const val YTM_PREFIX = "ytm:"
        const val YTM_NATIVE_SESSION_TOKEN = "ytm_native_google_session"
        private val YT_VIDEO_ID_PATTERN = Regex("^[A-Za-z0-9_-]{11}$")
        private val OTHERS_COUNT_REGEX = Regex("""\band\s+(\d+)\s+other""", RegexOption.IGNORE_CASE)
    }
}
