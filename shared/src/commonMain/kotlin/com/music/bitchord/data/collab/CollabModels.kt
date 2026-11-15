package com.music.bitchord.data.collab

import com.music.bitchord.data.model.Song
import kotlinx.serialization.Serializable

@Serializable
enum class CollabRole {
    OWNER,
    EDITOR,
    VIEWER,
}

@Serializable
data class CollabUser(
    val id: String,
    val email: String = "",
    val emailVerified: Boolean = true,
    val displayName: String,
    val avatarUrl: String? = null,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

@Serializable
data class CollabAuthSession(
    val token: String,
    val expiresAtMs: Long,
    val user: CollabUser,
)

@Serializable
data class CollabPermissions(
    val editorCanRemove: String = "any", // "any" | "own"
    val editorCanReorder: Boolean = true,
    val editorCanInvite: Boolean = false,
) {
    val editorsRemoveOwnOnly: Boolean get() = editorCanRemove.equals("own", ignoreCase = true)
}

@Serializable
data class CollabPermissionsPatch(
    val editorCanRemove: String? = null,
    val editorCanReorder: Boolean? = null,
    val editorCanInvite: Boolean? = null,
)

@Serializable
data class CollabMember(
    val playlistId: String = "",
    val userId: String,
    val role: String, // "OWNER" | "EDITOR" | "VIEWER"
    val displayName: String,
    val avatarUrl: String? = null,
    val joinedAtMs: Long = 0L,
    val addedById: String? = null,
) {
    val parsedRole: CollabRole
        get() = runCatching { CollabRole.valueOf(role.uppercase()) }.getOrDefault(CollabRole.VIEWER)
}

@Serializable
data class CollabTrackEntry(
    val entryId: String,
    val playlistId: String = "",
    val position: Int = 0,
    val trackId: String,
    val provider: String = "youtube", // "youtube" | "spotify" | "local" | "addon"
    val title: String,
    val artist: String,
    val albumName: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
    val isExplicit: Boolean = false,
    val contributorId: String? = null,
    val contributorName: String? = null,
    val contributorAvatarUrl: String? = null,
    val addedAtMs: Long = 0L,
) {
    fun toSong(): Song = Song(
        videoId = trackId,
        title = title,
        artist = artist,
        albumName = albumName,
        thumbnailUrl = thumbnailUrl,
        durationText = durationMs?.takeIf { it > 0L }?.let(::formatCollabDuration),
        isExplicit = isExplicit,
    )

    /** Contributor label for UI rows ("Added by you" vs "Added by <name>"). */
    fun contributorLabel(currentUserId: String?, addedByYouLabel: String = "Added by you"): String? {
        if (contributorId != null && contributorId == currentUserId) return addedByYouLabel
        val name = contributorName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return "Added by $name"
    }
}

private fun formatCollabDuration(ms: Long): String {
    val totalSec = (ms / 1_000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return "$mins:${secs.toString().padStart(2, '0')}"
}

@Serializable
data class CollabTrackInput(
    val trackId: String,
    val videoId: String = trackId,
    val provider: String = "youtube",
    val title: String,
    val artist: String,
    val albumName: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
    val isExplicit: Boolean = false,
)

fun Song.toCollabTrackInput(providerOverride: String? = null): CollabTrackInput? {
    val id = videoId.trim().ifEmpty { localUri?.trim().orEmpty() }
    if (id.isEmpty()) return null
    val inferredProvider = providerOverride ?: when {
        localUri != null -> "local"
        id.startsWith("spotify:") -> "spotify"
        else -> "youtube"
    }
    return CollabTrackInput(
        trackId = id,
        videoId = id,
        provider = inferredProvider,
        title = title.ifBlank { "Untitled" },
        artist = artist.ifBlank { "Unknown Artist" },
        albumName = albumName?.takeIf { it.isNotBlank() },
        thumbnailUrl = thumbnailUrl?.takeIf { it.startsWith("http") },
        durationMs = parseCollabDurationMs(durationText).takeIf { it > 0L },
        isExplicit = isExplicit == true,
    )
}

private fun parseCollabDurationMs(text: String?): Long {
    if (text.isNullOrBlank()) return 0L
    val parts = text.trim().split(":")
    val nums = parts.map { it.toLongOrNull() ?: return 0L }
    return when (nums.size) {
        2 -> (nums[0] * 60L + nums[1]) * 1_000L
        3 -> (nums[0] * 3_600L + nums[1] * 60L + nums[2]) * 1_000L
        else -> 0L
    }
}

@Serializable
data class CollabActivity(
    val id: String,
    val playlistId: String = "",
    val actorUserId: String = "",
    val actorName: String,
    val actorAvatarUrl: String? = null,
    val action: String = "",
    val summary: String,
    val createdAtMs: Long = 0L,
)

@Serializable
data class CollabPlaylistSummary(
    val id: String,
    val title: String,
    val description: String = "",
    val artworkUrl: String? = null,
    val ownerUserId: String,
    val ownerName: String,
    val ownerAvatarUrl: String? = null,
    val myRole: String = "VIEWER",
    val sharingEnabled: Boolean = true,
    val permissions: CollabPermissions = CollabPermissions(),
    val sourceType: String? = null,
    val sourcePlaylistId: String? = null,
    val memberCount: Int = 1,
    val trackCount: Int = 0,
    val revision: Long = 1L,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
) {
    val parsedRole: CollabRole
        get() = runCatching { CollabRole.valueOf(myRole.uppercase()) }.getOrDefault(CollabRole.VIEWER)

    val isOwner: Boolean get() = parsedRole == CollabRole.OWNER
    val canEditTracks: Boolean get() = sharingEnabled && (parsedRole == CollabRole.OWNER || parsedRole == CollabRole.EDITOR)
    val canReorderTracks: Boolean
        get() = sharingEnabled && (parsedRole == CollabRole.OWNER || (parsedRole == CollabRole.EDITOR && permissions.editorCanReorder))
    val canInvite: Boolean
        get() = sharingEnabled && (parsedRole == CollabRole.OWNER || (parsedRole == CollabRole.EDITOR && permissions.editorCanInvite))
}

@Serializable
data class CollabPlaylistDetail(
    val id: String,
    val title: String,
    val description: String = "",
    val artworkUrl: String? = null,
    val ownerUserId: String,
    val ownerName: String,
    val ownerAvatarUrl: String? = null,
    val myRole: String = "VIEWER",
    val sharingEnabled: Boolean = true,
    val permissions: CollabPermissions = CollabPermissions(),
    val sourceType: String? = null,
    val sourcePlaylistId: String? = null,
    val memberCount: Int = 1,
    val trackCount: Int = 0,
    val revision: Long = 1L,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val members: List<CollabMember> = emptyList(),
    val tracks: List<CollabTrackEntry> = emptyList(),
    val activities: List<CollabActivity> = emptyList(),
) {
    fun toSummary(): CollabPlaylistSummary = CollabPlaylistSummary(
        id = id,
        title = title,
        description = description,
        artworkUrl = artworkUrl ?: tracks.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
        ownerUserId = ownerUserId,
        ownerName = ownerName,
        ownerAvatarUrl = ownerAvatarUrl,
        myRole = myRole,
        sharingEnabled = sharingEnabled,
        permissions = permissions,
        sourceType = sourceType,
        sourcePlaylistId = sourcePlaylistId,
        memberCount = members.size.coerceAtLeast(memberCount),
        trackCount = tracks.size,
        revision = revision,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )

    fun roleOf(userId: String?): CollabRole {
        if (userId != null && userId == ownerUserId) return CollabRole.OWNER
        val memberRole = members.firstOrNull { it.userId == userId }?.parsedRole
        if (memberRole != null) return memberRole
        return runCatching { CollabRole.valueOf(myRole.uppercase()) }.getOrDefault(CollabRole.VIEWER)
    }

    fun canAddTracks(userId: String?): Boolean {
        val role = roleOf(userId)
        return role == CollabRole.OWNER || (sharingEnabled && role == CollabRole.EDITOR)
    }

    fun canRemoveTrack(userId: String?, entry: CollabTrackEntry): Boolean {
        val role = roleOf(userId)
        if (role == CollabRole.OWNER) return true
        if (!sharingEnabled || role != CollabRole.EDITOR) return false
        return if (permissions.editorsRemoveOwnOnly) {
            userId != null && entry.contributorId == userId
        } else {
            true
        }
    }

    fun canReorderTracks(userId: String?): Boolean {
        val role = roleOf(userId)
        return role == CollabRole.OWNER || (sharingEnabled && role == CollabRole.EDITOR && permissions.editorCanReorder)
    }

    fun canInvite(userId: String?): Boolean {
        val role = roleOf(userId)
        return sharingEnabled && (role == CollabRole.OWNER || (role == CollabRole.EDITOR && permissions.editorCanInvite))
    }

    fun canManageSettings(userId: String?): Boolean = roleOf(userId) == CollabRole.OWNER
}

@Serializable
data class CollabInviteView(
    val inviteId: String,
    val playlistId: String,
    val token: String = "",
    val role: String,
    val expiresAtMs: Long? = null,
    val maxUses: Int = 0,
    val useCount: Int = 0,
    val createdAtMs: Long = 0L,
    val inviteUrl: String = "",
)

@Serializable
data class CollabInvitePreview(
    val playlistId: String,
    val title: String,
    val description: String = "",
    val artworkUrl: String? = null,
    val ownerName: String,
    val ownerAvatarUrl: String? = null,
    val memberCount: Int = 1,
    val trackCount: Int = 0,
    val role: String = "EDITOR",
    val expired: Boolean = false,
    val revoked: Boolean = false,
)
