package com.music.bitchord.data.collab

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Shared reactive manager for Collaborative Playlists across Android and Desktop.
 *
 * Responsibilities:
 *  - Persists verified Google OIDC `CollabAuthSession`, configured server URL, Google OAuth Client
 *    ID, and locally cached playlist summaries + detail snapshots in an atomic JSON file so playlists
 *    appear immediately on cold launch even before the network answers.
 *  - Maintains a single auto-reconnecting `/ws/collab` socket while authenticated and subscribed to
 *    any open playlist(s), reconciling missed updates via `sinceRevision` and deduplicating own
 *    mutations via `opId`.
 *  - Completely independent from Listen Together (`PartySync` / `ListenTogether`).
 */
class CollabManager(
    private val storageFileProvider: () -> File?,
    private val defaultServerUrl: () -> String = { "" },
    private val defaultGoogleClientId: () -> String = { "" },
    val client: CollabClient = CollabClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class PersistedState(
        val version: Int = 1,
        val serverUrlOverride: String = "",
        val googleClientIdOverride: String = "",
        val session: CollabAuthSession? = null,
        val playlists: List<CollabPlaylistSummary> = emptyList(),
        val details: Map<String, CollabPlaylistDetail> = emptyMap(),
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

    private val activeSubscriptions = LinkedHashSet<String>()
    private val recentOwnOps = ArrayDeque<String>(64)
    private var wsJob: Job? = null
    private var outboundFrames: Channel<String>? = null
    @Volatile
    private var initialized = false

    fun effectiveServerUrl(): String {
        val override = _serverUrlOverride.value.trim().trimEnd('/')
        if (override.isNotEmpty()) return override
        return defaultServerUrl().trim().trimEnd('/')
    }

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

    fun initialize() {
        if (initialized) return
        initialized = true
        scope.launch {
            loadFromDisk()
            if (isAuthenticated && effectiveServerUrl().isNotBlank()) {
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
        saveToDiskAsync()
        restartRealtimeIfNeeded()
        if (isAuthenticated && normalized.isNotEmpty()) {
            scope.launch { refreshPlaylists() }
        }
    }

    fun adoptServerOriginIfUnset(origin: String?) {
        val clean = CollabInviteLink.normalizeServerOrigin(origin) ?: return
        if (effectiveServerUrl().isBlank()) {
            _serverUrlOverride.value = clean
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
        applyAuthenticatedSession(auth)
        refreshPlaylists().getOrNull()
        auth.user
    }.onFailure { handleAuthError(it) }

    suspend fun signInWithBrowserPkce(openBrowser: (String) -> Unit): Result<CollabUser> = runCatching {
        val server = requireServerUrl()
        val clientId = effectiveGoogleClientId()
        require(clientId.isNotBlank()) {
            "Set a Google OAuth Client ID in Collaborative Settings before signing in."
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
        applyAuthenticatedSession(auth)
        refreshPlaylists().getOrNull()
        auth.user
    }.onFailure { handleAuthError(it) }

    suspend fun signOut() {
        val server = effectiveServerUrl()
        val tok = _session.value?.token
        _session.value = null
        _playlists.value = emptyList()
        _details.value = emptyMap()
        stopRealtime()
        saveToDisk()
        if (server.isNotBlank() && !tok.isNullOrBlank()) {
            client.logout(server, tok)
        }
    }

    suspend fun refreshSessionAndPlaylists() {
        val server = effectiveServerUrl().takeIf { it.isNotBlank() } ?: return
        val tok = _session.value?.token?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val (user, exp) = client.getMe(server, tok)
            _session.value = CollabAuthSession(token = tok, expiresAtMs = exp, user = user)
            saveToDisk()
        }.onFailure { err ->
            if (err is CollabApiException && err.status == 401) {
                _session.value = null
                stopRealtime()
                saveToDisk()
                return
            }
        }
        refreshPlaylists()
    }

    suspend fun refreshPlaylists(): Result<List<CollabPlaylistSummary>> = runCatching {
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
    }.onFailure { handleAuthError(it) }

    suspend fun loadPlaylistDetail(playlistId: String): Result<CollabPlaylistDetail> = runCatching {
        val server = requireServerUrl()
        val tok = requireToken()
        val detail = client.getPlaylist(server, tok, playlistId)
        storeDetailSnapshot(detail)
        detail
    }.onFailure { handleAuthError(it) }

    suspend fun createPlaylist(
        title: String,
        description: String = "",
        artworkUrl: String? = null,
        permissions: CollabPermissions = CollabPermissions(),
        initialSongs: List<Song> = emptyList(),
    ): Result<CollabPlaylistDetail> = runCatching {
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
    }.onFailure { handleAuthError(it) }

    /**
     * Converts an existing personal playlist into a collaborative playlist without mutating or
     * deleting the original personal playlist, attributing existing tracks to the converting owner.
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
    }.onFailure { handleAuthError(it) }

    suspend fun updatePlaylistMetadata(
        playlistId: String,
        title: String? = null,
        description: String? = null,
        artworkUrl: String? = null,
        permissions: CollabPermissionsPatch? = null,
    ): Result<CollabPlaylistDetail> = runCatching {
        val server = requireServerUrl()
        val tok = requireToken()
        val opId = rememberOwnOp()
        val detail = client.updatePlaylist(server, tok, playlistId, title, description, artworkUrl, permissions, opId)
        storeDetailSnapshot(detail)
        detail
    }.onFailure { handleAuthError(it) }

    suspend fun stopSharing(playlistId: String): Result<CollabPlaylistDetail> = runCatching {
        val server = requireServerUrl()
        val tok = requireToken()
        val detail = client.stopSharing(server, tok, playlistId)
        storeDetailSnapshot(detail)
        detail
    }.onFailure { handleAuthError(it) }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> = runCatching {
        val server = requireServerUrl()
        val tok = requireToken()
        client.deletePlaylist(server, tok, playlistId)
        removeLocalPlaylist(playlistId)
    }.onFailure { handleAuthError(it) }

    suspend fun addSongs(playlistId: String, songs: List<Song>, position: Int? = null): Result<CollabPlaylistDetail> = runCatching {
        val inputs = songs.mapNotNull { it.toCollabTrackInput() }
        require(inputs.isNotEmpty()) { "No playable tracks to add." }
        val server = requireServerUrl()
        val tok = requireToken()
        val opId = rememberOwnOp()
        val detail = client.addTracks(server, tok, playlistId, inputs, position, opId)
        storeDetailSnapshot(detail)
        detail
    }.onFailure { handleAuthError(it) }

    suspend fun removeTrack(playlistId: String, entryId: String): Result<CollabPlaylistDetail> = runCatching {
        val server = requireServerUrl()
        val tok = requireToken()
        val opId = rememberOwnOp()
        val detail = client.removeTrack(server, tok, playlistId, entryId, opId)
        storeDetailSnapshot(detail)
        detail
    }.onFailure { handleAuthError(it) }

    suspend fun reorderTracks(playlistId: String, orderedEntryIds: List<String>): Result<CollabPlaylistDetail> = runCatching {
        val server = requireServerUrl()
        val tok = requireToken()
        val opId = rememberOwnOp()
        val detail = client.reorderTracks(server, tok, playlistId, orderedEntryIds, opId)
        storeDetailSnapshot(detail)
        detail
    }.onFailure { handleAuthError(it) }

    suspend fun createInvite(
        playlistId: String,
        role: CollabRole = CollabRole.EDITOR,
        expiresInSec: Long? = 7L * 24L * 3_600L,
        maxUses: Int = 0,
    ): Result<CollabInviteView> = runCatching {
        val server = requireServerUrl()
        val tok = requireToken()
        client.createInvite(server, tok, playlistId, role, expiresInSec, maxUses)
    }.onFailure { handleAuthError(it) }

    suspend fun listInvites(playlistId: String): Result<List<CollabInviteView>> = runCatching {
        val server = requireServerUrl()
        val tok = requireToken()
        client.listInvites(server, tok, playlistId)
    }.onFailure { handleAuthError(it) }

    suspend fun revokeInvite(playlistId: String, inviteId: String): Result<Unit> = runCatching {
        val server = requireServerUrl()
        val tok = requireToken()
        client.revokeInvite(server, tok, playlistId, inviteId)
    }.onFailure { handleAuthError(it) }

    suspend fun previewInvite(rawLinkOrToken: String): Result<Pair<ParsedCollabInvite, CollabInvitePreview>> = runCatching {
        val parsed = CollabInviteLink.parse(rawLinkOrToken)
            ?: error("Invalid collaborative playlist invite link.")
        adoptServerOriginIfUnset(parsed.serverOrigin)
        val server = parsed.serverOrigin ?: requireServerUrl()
        val preview = client.previewInvite(server, parsed.token)
        parsed.copy(serverOrigin = server) to preview
    }

    suspend fun acceptInvite(rawLinkOrToken: String): Result<CollabAcceptInviteResult> = runCatching {
        val parsed = CollabInviteLink.parse(rawLinkOrToken)
            ?: error("Invalid collaborative playlist invite link.")
        adoptServerOriginIfUnset(parsed.serverOrigin)
        val server = parsed.serverOrigin ?: requireServerUrl()
        val tok = requireToken()
        val res = client.acceptInvite(server, tok, parsed.token)
        storeDetailSnapshot(res.playlist)
        res
    }.onFailure { handleAuthError(it) }

    suspend fun updateMemberRole(playlistId: String, userId: String, role: CollabRole): Result<CollabPlaylistDetail> = runCatching {
        val server = requireServerUrl()
        val tok = requireToken()
        val detail = client.updateMemberRole(server, tok, playlistId, userId, role)
        storeDetailSnapshot(detail)
        detail
    }.onFailure { handleAuthError(it) }

    suspend fun transferOwnership(playlistId: String, newOwnerUserId: String): Result<CollabPlaylistDetail> = runCatching {
        val server = requireServerUrl()
        val tok = requireToken()
        val detail = client.transferOwnership(server, tok, playlistId, newOwnerUserId)
        storeDetailSnapshot(detail)
        detail
    }.onFailure { handleAuthError(it) }

    suspend fun removeMember(playlistId: String, userId: String): Result<CollabPlaylistDetail?> = runCatching {
        val server = requireServerUrl()
        val tok = requireToken()
        val detail = client.removeMember(server, tok, playlistId, userId)
        if (detail != null) storeDetailSnapshot(detail)
        detail
    }.onFailure { handleAuthError(it) }

    suspend fun leavePlaylist(playlistId: String): Result<Unit> = runCatching {
        val server = requireServerUrl()
        val tok = requireToken()
        client.leavePlaylist(server, tok, playlistId)
        removeLocalPlaylist(playlistId)
    }.onFailure { handleAuthError(it) }

    /** Subscribes [playlistId] over `/ws/collab` so live collaborator edits arrive immediately. */
    fun subscribePlaylist(playlistId: String) {
        synchronized(activeSubscriptions) {
            activeSubscriptions.add(playlistId)
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
        val frame = buildJsonObject {
            put("type", "unsubscribe")
            put("playlistId", playlistId)
        }
        outboundFrames?.trySend(diskJson.encodeToString(frame))
        if (empty) stopRealtime()
    }

    private fun ensureRealtimeRunning() {
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
                    // Exponential backoff with cap at 20s; reconnect sends sinceRevision for clean state catch-up.
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
        outboundFrames?.close()
        outboundFrames = null
        _realtimeConnected.value = false
    }

    internal fun applyRealtimeEvent(event: CollabRealtimeEvent) {
        when (event) {
            is CollabRealtimeEvent.Snapshot -> {
                storeDetailSnapshot(event.playlist)
            }
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
            is CollabRealtimeEvent.ServerError -> {
                _notices.tryEmit(event.message)
            }
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

    private fun applyAuthenticatedSession(auth: CollabAuthSession) {
        _session.value = auth
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
        if (error is CollabApiException && error.status == 401) {
            _session.value = null
            stopRealtime()
            saveToDiskAsync()
            _notices.tryEmit("Collaborative session expired. Please sign in with Google again.")
        } else if (error !is CancellationException) {
            error.message?.takeIf { it.isNotBlank() }?.let(_notices::tryEmit)
        }
    }

    private fun requireServerUrl(): String {
        val url = effectiveServerUrl()
        require(url.isNotBlank()) { "Configure a Collaboration Server URL in Settings first." }
        return url
    }

    private fun requireToken(): String {
        val s = _session.value ?: throw CollabApiException(401, "auth_required", "Sign in with Google to use collaborative playlists.")
        if (s.expiresAtMs > 0L && s.expiresAtMs <= System.currentTimeMillis()) {
            _session.value = null
            throw CollabApiException(401, "auth_required", "Google session expired. Please sign in again.")
        }
        return s.token
    }

    private fun saveToDiskAsync() {
        scope.launch { saveToDisk() }
    }

    private suspend fun saveToDisk() = withContext(Dispatchers.IO) {
        val target = storageFileProvider() ?: return@withContext
        runCatching {
            target.parentFile?.mkdirs()
            val snapshot = PersistedState(
                serverUrlOverride = _serverUrlOverride.value,
                googleClientIdOverride = _googleClientIdOverride.value,
                session = _session.value,
                playlists = _playlists.value,
                details = _details.value,
            )
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(diskJson.encodeToString(snapshot))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private suspend fun loadFromDisk() = withContext(Dispatchers.IO) {
        val target = storageFileProvider() ?: return@withContext
        if (!target.isFile) return@withContext
        runCatching {
            val loaded = diskJson.decodeFromString<PersistedState>(target.readText())
            _serverUrlOverride.value = loaded.serverUrlOverride
            _googleClientIdOverride.value = loaded.googleClientIdOverride
            if (loaded.session != null && (loaded.session.expiresAtMs <= 0L || loaded.session.expiresAtMs > System.currentTimeMillis())) {
                _session.value = loaded.session
            }
            _playlists.value = loaded.playlists
            _details.value = loaded.details
        }
    }
}
