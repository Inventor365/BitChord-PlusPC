package com.music.bitchord.data.collab

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

class CollabApiException(
    val status: Int,
    val errorCode: String,
    override val message: String,
) : Exception(message)

@Serializable
data class CollabConvertResult(
    val playlist: CollabPlaylistDetail,
    val alreadyConverted: Boolean = false,
)

@Serializable
data class CollabAcceptInviteResult(
    val playlist: CollabPlaylistDetail,
    val alreadyMember: Boolean = false,
)

sealed interface CollabRealtimeEvent {
    data class Snapshot(val playlistId: String, val revision: Long, val playlist: CollabPlaylistDetail) : CollabRealtimeEvent
    data class Subscribed(val playlistId: String, val revision: Long) : CollabRealtimeEvent
    data class TracksChanged(
        val playlistId: String,
        val revision: Long,
        val opId: String?,
        val tracks: List<CollabTrackEntry>,
        val latestActivity: CollabActivity?,
    ) : CollabRealtimeEvent
    data class MetadataChanged(
        val playlistId: String,
        val revision: Long,
        val opId: String?,
        val summary: CollabPlaylistSummary,
        val latestActivity: CollabActivity?,
    ) : CollabRealtimeEvent
    data class MemberJoined(
        val playlistId: String,
        val revision: Long,
        val member: CollabMember,
        val members: List<CollabMember>,
        val latestActivity: CollabActivity?,
    ) : CollabRealtimeEvent
    data class MembersChanged(
        val playlistId: String,
        val revision: Long,
        val members: List<CollabMember>,
        val latestActivity: CollabActivity?,
    ) : CollabRealtimeEvent
    data class MemberRemoved(
        val playlistId: String,
        val userId: String,
        val reason: String,
        val message: String,
    ) : CollabRealtimeEvent
    data class Stopped(val playlistId: String, val message: String) : CollabRealtimeEvent
    data class ServerError(val playlistId: String?, val code: String, val message: String) : CollabRealtimeEvent
}

/**
 * Shared Ktor REST + WebSocket client for BitChord Collaborative Playlists and Google OIDC auth.
 */
class CollabClient(
    private val httpClient: HttpClient = defaultHttpClient(),
) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun authenticateWithIdToken(baseUrl: String, idToken: String): CollabAuthSession {
        val body = buildJsonObject { put("idToken", idToken.trim()) }
        val res = postJson(baseUrl, "/api/collab/auth/google", token = null, body = body)
        return json.decodeFromString(res)
    }

    suspend fun authenticateWithCode(
        baseUrl: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
        clientId: String,
    ): CollabAuthSession {
        val body = buildJsonObject {
            put("code", code)
            put("codeVerifier", codeVerifier)
            put("redirectUri", redirectUri)
            put("clientId", clientId)
        }
        val res = postJson(baseUrl, "/api/collab/auth/google/code", token = null, body = body)
        return json.decodeFromString(res)
    }

    suspend fun getMe(baseUrl: String, token: String): Pair<CollabUser, Long> {
        val raw = getJson(baseUrl, "/api/collab/auth/me", token = token)
        val obj = json.parseToJsonElement(raw).jsonObject
        val user = json.decodeFromJsonElement<CollabUser>(obj.getValue("user"))
        val exp = obj["expiresAtMs"]?.jsonPrimitive?.longOrNull ?: 0L
        return user to exp
    }

    suspend fun logout(baseUrl: String, token: String) {
        runCatching { postJson(baseUrl, "/api/collab/auth/logout", token = token, body = buildJsonObject {}) }
    }

    suspend fun listPlaylists(baseUrl: String, token: String, includeStopped: Boolean = false): List<CollabPlaylistSummary> {
        val suffix = if (includeStopped) "?includeStopped=true" else ""
        val raw = getJson(baseUrl, "/api/collab/playlists$suffix", token = token)
        val obj = json.parseToJsonElement(raw).jsonObject
        return json.decodeFromJsonElement(obj.getValue("playlists"))
    }

    suspend fun createPlaylist(
        baseUrl: String,
        token: String,
        title: String,
        description: String = "",
        artworkUrl: String? = null,
        permissions: CollabPermissions = CollabPermissions(),
        tracks: List<CollabTrackInput> = emptyList(),
    ): CollabPlaylistDetail {
        @Serializable
        data class CreateReq(
            val title: String,
            val description: String = "",
            val artworkUrl: String? = null,
            val permissions: CollabPermissions = CollabPermissions(),
            val tracks: List<CollabTrackInput> = emptyList(),
        )
        val raw = postRaw(
            baseUrl = baseUrl,
            path = "/api/collab/playlists",
            token = token,
            bodyJson = json.encodeToString(CreateReq(title, description, artworkUrl, permissions, tracks)),
        )
        val obj = json.parseToJsonElement(raw).jsonObject
        return json.decodeFromJsonElement(obj.getValue("playlist"))
    }

    suspend fun convertPlaylist(
        baseUrl: String,
        token: String,
        sourcePlaylistId: String,
        sourceType: String,
        title: String,
        description: String = "",
        artworkUrl: String? = null,
        attributeExistingTracksToOwner: Boolean = true,
        permissions: CollabPermissions = CollabPermissions(),
        tracks: List<CollabTrackInput>,
    ): CollabConvertResult {
        @Serializable
        data class ConvertReq(
            val sourcePlaylistId: String,
            val sourceType: String,
            val title: String,
            val description: String = "",
            val artworkUrl: String? = null,
            val attributeExistingTracksToOwner: Boolean = true,
            val permissions: CollabPermissions = CollabPermissions(),
            val tracks: List<CollabTrackInput>,
        )
        val raw = postRaw(
            baseUrl = baseUrl,
            path = "/api/collab/playlists/convert",
            token = token,
            bodyJson = json.encodeToString(
                ConvertReq(
                    sourcePlaylistId = sourcePlaylistId,
                    sourceType = sourceType,
                    title = title,
                    description = description,
                    artworkUrl = artworkUrl,
                    attributeExistingTracksToOwner = attributeExistingTracksToOwner,
                    permissions = permissions,
                    tracks = tracks,
                ),
            ),
        )
        return json.decodeFromString(raw)
    }

    suspend fun getPlaylist(baseUrl: String, token: String, playlistId: String): CollabPlaylistDetail {
        val raw = getJson(baseUrl, "/api/collab/playlists/${enc(playlistId)}", token = token)
        val obj = json.parseToJsonElement(raw).jsonObject
        return json.decodeFromJsonElement(obj.getValue("playlist"))
    }

    suspend fun updatePlaylist(
        baseUrl: String,
        token: String,
        playlistId: String,
        title: String? = null,
        description: String? = null,
        artworkUrl: String? = null,
        permissions: CollabPermissionsPatch? = null,
        opId: String = newOpId(),
    ): CollabPlaylistDetail {
        @Serializable
        data class PatchReq(
            val title: String? = null,
            val description: String? = null,
            val artworkUrl: String? = null,
            val permissions: CollabPermissionsPatch? = null,
            val opId: String? = null,
        )
        val raw = patchRaw(
            baseUrl = baseUrl,
            path = "/api/collab/playlists/${enc(playlistId)}",
            token = token,
            bodyJson = json.encodeToString(PatchReq(title, description, artworkUrl, permissions, opId)),
        )
        val obj = json.parseToJsonElement(raw).jsonObject
        return json.decodeFromJsonElement(obj.getValue("playlist"))
    }

    suspend fun stopSharing(baseUrl: String, token: String, playlistId: String): CollabPlaylistDetail {
        val raw = postJson(baseUrl, "/api/collab/playlists/${enc(playlistId)}/stop-sharing", token = token, body = buildJsonObject {})
        val obj = json.parseToJsonElement(raw).jsonObject
        return json.decodeFromJsonElement(obj.getValue("playlist"))
    }

    suspend fun deletePlaylist(baseUrl: String, token: String, playlistId: String) {
        deleteRaw(baseUrl, "/api/collab/playlists/${enc(playlistId)}", token = token)
    }

    suspend fun addTracks(
        baseUrl: String,
        token: String,
        playlistId: String,
        tracks: List<CollabTrackInput>,
        position: Int? = null,
        opId: String = newOpId(),
    ): CollabPlaylistDetail {
        @Serializable
        data class AddTracksReq(
            val tracks: List<CollabTrackInput>,
            val position: Int? = null,
            val opId: String? = null,
        )
        val raw = postRaw(
            baseUrl = baseUrl,
            path = "/api/collab/playlists/${enc(playlistId)}/tracks",
            token = token,
            bodyJson = json.encodeToString(AddTracksReq(tracks, position, opId)),
        )
        val obj = json.parseToJsonElement(raw).jsonObject
        return json.decodeFromJsonElement(obj.getValue("playlist"))
    }

    suspend fun removeTrack(
        baseUrl: String,
        token: String,
        playlistId: String,
        entryId: String,
        opId: String = newOpId(),
    ): CollabPlaylistDetail {
        val raw = deleteRaw(
            baseUrl = baseUrl,
            path = "/api/collab/playlists/${enc(playlistId)}/tracks/${enc(entryId)}?opId=${enc(opId)}",
            token = token,
        )
        val obj = json.parseToJsonElement(raw).jsonObject
        return json.decodeFromJsonElement(obj.getValue("playlist"))
    }

    suspend fun reorderTracks(
        baseUrl: String,
        token: String,
        playlistId: String,
        entryIds: List<String>,
        opId: String = newOpId(),
    ): CollabPlaylistDetail {
        @Serializable
        data class ReorderReq(val entryIds: List<String>, val opId: String? = null)
        val raw = putRaw(
            baseUrl = baseUrl,
            path = "/api/collab/playlists/${enc(playlistId)}/tracks/order",
            token = token,
            bodyJson = json.encodeToString(ReorderReq(entryIds, opId)),
        )
        val obj = json.parseToJsonElement(raw).jsonObject
        return json.decodeFromJsonElement(obj.getValue("playlist"))
    }

    suspend fun updateMemberRole(
        baseUrl: String,
        token: String,
        playlistId: String,
        userId: String,
        role: CollabRole,
    ): CollabPlaylistDetail {
        val raw = patchRaw(
            baseUrl = baseUrl,
            path = "/api/collab/playlists/${enc(playlistId)}/members/${enc(userId)}",
            token = token,
            bodyJson = json.encodeToString(buildJsonObject { put("role", role.name) }),
        )
        val obj = json.parseToJsonElement(raw).jsonObject
        return json.decodeFromJsonElement(obj.getValue("playlist"))
    }

    suspend fun transferOwnership(
        baseUrl: String,
        token: String,
        playlistId: String,
        newOwnerUserId: String,
    ): CollabPlaylistDetail {
        val raw = postJson(
            baseUrl = baseUrl,
            path = "/api/collab/playlists/${enc(playlistId)}/transfer-ownership",
            token = token,
            body = buildJsonObject { put("newOwnerUserId", newOwnerUserId) },
        )
        val obj = json.parseToJsonElement(raw).jsonObject
        return json.decodeFromJsonElement(obj.getValue("playlist"))
    }

    suspend fun removeMember(
        baseUrl: String,
        token: String,
        playlistId: String,
        userId: String,
    ): CollabPlaylistDetail? {
        val raw = deleteRaw(baseUrl, "/api/collab/playlists/${enc(playlistId)}/members/${enc(userId)}", token = token)
        val obj = json.parseToJsonElement(raw).jsonObject
        return obj["playlist"]?.let { json.decodeFromJsonElement<CollabPlaylistDetail>(it) }
    }

    suspend fun leavePlaylist(baseUrl: String, token: String, playlistId: String) {
        postJson(baseUrl, "/api/collab/playlists/${enc(playlistId)}/leave", token = token, body = buildJsonObject {})
    }

    suspend fun createInvite(
        baseUrl: String,
        token: String,
        playlistId: String,
        role: CollabRole = CollabRole.EDITOR,
        expiresInSec: Long? = 7L * 24L * 3_600L,
        maxUses: Int = 0,
    ): CollabInviteView {
        @Serializable
        data class InviteReq(val role: String, val expiresInSec: Long? = null, val maxUses: Int = 0)
        val raw = postRaw(
            baseUrl = baseUrl,
            path = "/api/collab/playlists/${enc(playlistId)}/invites",
            token = token,
            bodyJson = json.encodeToString(InviteReq(role.name, expiresInSec, maxUses)),
        )
        return json.decodeFromString(raw)
    }

    suspend fun listInvites(baseUrl: String, token: String, playlistId: String): List<CollabInviteView> {
        val raw = getJson(baseUrl, "/api/collab/playlists/${enc(playlistId)}/invites", token = token)
        val obj = json.parseToJsonElement(raw).jsonObject
        return json.decodeFromJsonElement(obj.getValue("invites"))
    }

    suspend fun revokeInvite(baseUrl: String, token: String, playlistId: String, inviteId: String) {
        deleteRaw(baseUrl, "/api/collab/playlists/${enc(playlistId)}/invites/${enc(inviteId)}", token = token)
    }

    suspend fun previewInvite(baseUrl: String, inviteToken: String): CollabInvitePreview {
        val raw = getJson(baseUrl, "/api/collab/invites/${enc(inviteToken)}/preview", token = null)
        return json.decodeFromString(raw)
    }

    suspend fun acceptInvite(baseUrl: String, token: String, inviteToken: String): CollabAcceptInviteResult {
        val raw = postJson(baseUrl, "/api/collab/invites/${enc(inviteToken)}/accept", token = token, body = buildJsonObject {})
        return json.decodeFromString(raw)
    }

    /**
     * Connects to `/ws/collab`, sends outbound subscribe/unsubscribe frames from [outbound], and
     * delivers parsed [CollabRealtimeEvent] frames until cancelled or disconnected.
     */
    suspend fun runRealtimeSocket(
        baseUrl: String,
        token: String,
        initialSubscriptions: Map<String, Long>,
        outbound: Channel<String>,
        onEvent: (CollabRealtimeEvent) -> Unit,
    ) {
        val wsUrl = toWsUrl(baseUrl, "/ws/collab")
        httpClient.webSocket(
            urlString = wsUrl,
            request = {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.SecWebSocketProtocol, "bitchord.collab.v1")
            },
        ) {
            coroutineScope {
                for ((pid, rev) in initialSubscriptions) {
                    val sub = buildJsonObject {
                        put("type", "subscribe")
                        put("playlistId", pid)
                        put("sinceRevision", rev)
                    }
                    send(Frame.Text(json.encodeToString(sub)))
                }

                val writer = launch {
                    for (msg in outbound) {
                        send(Frame.Text(msg))
                    }
                }

                val pinger = launch {
                    while (isActive) {
                        delay(25_000L)
                        val ping = buildJsonObject {
                            put("type", "ping")
                            put("clientMs", System.currentTimeMillis())
                        }
                        send(Frame.Text(json.encodeToString(ping)))
                    }
                }

                try {
                    for (frame in incoming) {
                        val text = (frame as? Frame.Text)?.readText() ?: continue
                        parseRealtimeFrame(text)?.let(onEvent)
                    }
                } finally {
                    pinger.cancel()
                    writer.cancel()
                }
            }
        }
    }

    internal fun parseRealtimeFrame(raw: String): CollabRealtimeEvent? = runCatching {
        val obj = json.parseToJsonElement(raw).jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val playlistId = obj["playlistId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val revision = obj["revision"]?.jsonPrimitive?.longOrNull ?: 0L
        when (type) {
            "collab.snapshot" -> CollabRealtimeEvent.Snapshot(
                playlistId = playlistId,
                revision = revision,
                playlist = json.decodeFromJsonElement(obj.getValue("playlist")),
            )
            "collab.subscribed" -> CollabRealtimeEvent.Subscribed(playlistId, revision)
            "collab.tracks_changed" -> CollabRealtimeEvent.TracksChanged(
                playlistId = playlistId,
                revision = revision,
                opId = obj["opId"]?.jsonPrimitive?.contentOrNull,
                tracks = json.decodeFromJsonElement(obj.getValue("tracks")),
                latestActivity = obj["latestActivity"]?.takeIf { it is JsonObject }?.let { json.decodeFromJsonElement(it) },
            )
            "collab.metadata_changed" -> CollabRealtimeEvent.MetadataChanged(
                playlistId = playlistId,
                revision = revision,
                opId = obj["opId"]?.jsonPrimitive?.contentOrNull,
                summary = json.decodeFromJsonElement(obj.getValue("playlist")),
                latestActivity = obj["latestActivity"]?.takeIf { it is JsonObject }?.let { json.decodeFromJsonElement(it) },
            )
            "collab.member_joined" -> CollabRealtimeEvent.MemberJoined(
                playlistId = playlistId,
                revision = revision,
                member = json.decodeFromJsonElement(obj.getValue("member")),
                members = json.decodeFromJsonElement(obj.getValue("members")),
                latestActivity = obj["latestActivity"]?.takeIf { it is JsonObject }?.let { json.decodeFromJsonElement(it) },
            )
            "collab.members_changed" -> CollabRealtimeEvent.MembersChanged(
                playlistId = playlistId,
                revision = revision,
                members = json.decodeFromJsonElement(obj.getValue("members")),
                latestActivity = obj["latestActivity"]?.takeIf { it is JsonObject }?.let { json.decodeFromJsonElement(it) },
            )
            "collab.member_removed" -> CollabRealtimeEvent.MemberRemoved(
                playlistId = playlistId,
                userId = obj["userId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                reason = obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "Removed from playlist.",
            )
            "collab.stopped" -> CollabRealtimeEvent.Stopped(
                playlistId = playlistId,
                message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "Sharing stopped by owner.",
            )
            "collab.error" -> CollabRealtimeEvent.ServerError(
                playlistId = playlistId.ifEmpty { null },
                code = obj["error"]?.jsonPrimitive?.contentOrNull ?: "error",
                message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "Collaboration error",
            )
            else -> null
        }
    }.getOrNull()

    private suspend fun getJson(baseUrl: String, path: String, token: String?): String {
        val response = httpClient.get(joinUrl(baseUrl, path)) {
            if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $token")
        }
        return checkResponse(response)
    }

    private suspend fun postJson(baseUrl: String, path: String, token: String?, body: JsonObject): String =
        postRaw(baseUrl, path, token, json.encodeToString(body))

    private suspend fun postRaw(baseUrl: String, path: String, token: String?, bodyJson: String): String {
        val response = httpClient.post(joinUrl(baseUrl, path)) {
            contentType(ContentType.Application.Json)
            if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $token")
            setBody(bodyJson)
        }
        return checkResponse(response)
    }

    private suspend fun patchRaw(baseUrl: String, path: String, token: String?, bodyJson: String): String {
        val response = httpClient.patch(joinUrl(baseUrl, path)) {
            contentType(ContentType.Application.Json)
            if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $token")
            setBody(bodyJson)
        }
        return checkResponse(response)
    }

    private suspend fun putRaw(baseUrl: String, path: String, token: String?, bodyJson: String): String {
        val response = httpClient.put(joinUrl(baseUrl, path)) {
            contentType(ContentType.Application.Json)
            if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $token")
            setBody(bodyJson)
        }
        return checkResponse(response)
    }

    private suspend fun deleteRaw(baseUrl: String, path: String, token: String?): String {
        val response = httpClient.delete(joinUrl(baseUrl, path)) {
            if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $token")
        }
        return checkResponse(response)
    }

    private suspend fun checkResponse(response: HttpResponse): String {
        val text = response.bodyAsText()
        if (response.status.isSuccess()) return text
        val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val code = parsed?.get("error")?.jsonPrimitive?.contentOrNull ?: "http_${response.status.value}"
        val msg = parsed?.get("message")?.jsonPrimitive?.contentOrNull
            ?: text.take(200).ifBlank { "Request failed (${response.status.value})" }
        throw CollabApiException(response.status.value, code, msg)
    }

    companion object {
        fun newOpId(): String = "op_${UUID.randomUUID().toString().replace("-", "").take(16)}"

        fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000L
                connectTimeoutMillis = 10_000L
                socketTimeoutMillis = 25_000L
            }
            install(WebSockets)
        }

        private fun joinUrl(baseUrl: String, path: String): String =
            baseUrl.trim().trimEnd('/') + path

        private fun toWsUrl(baseUrl: String, path: String): String {
            val clean = baseUrl.trim().trimEnd('/')
            val wsBase = when {
                clean.startsWith("https://", ignoreCase = true) -> "wss://" + clean.substring(8)
                clean.startsWith("http://", ignoreCase = true) -> "ws://" + clean.substring(7)
                else -> "wss://$clean"
            }
            return wsBase + path
        }

        private fun enc(segment: String): String =
            URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
