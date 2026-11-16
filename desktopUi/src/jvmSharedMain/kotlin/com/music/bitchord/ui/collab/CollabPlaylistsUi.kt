package com.music.bitchord.ui.collab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.music.bitchord.data.collab.CollabInvitePreview
import com.music.bitchord.data.collab.CollabInviteLink
import com.music.bitchord.data.collab.CollabInviteView
import com.music.bitchord.data.collab.CollabManager
import com.music.bitchord.data.collab.CollabMember
import com.music.bitchord.data.collab.CollabPermissions
import com.music.bitchord.data.collab.CollabPermissionsPatch
import com.music.bitchord.data.collab.CollabPlaylistDetail
import com.music.bitchord.data.collab.CollabPlaylistSummary
import com.music.bitchord.data.collab.CollabRole
import com.music.bitchord.data.collab.CollabTrackEntry
import com.music.bitchord.data.collab.CollabUser
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.LIBRARY_GRID_SPACING
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import com.music.bitchord.ui.screens.SectionHeader
import com.music.bitchord.ui.components.ShelfRow
import com.music.bitchord.ui.components.filterPlaylists
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * First-party Collaborative Playlists section inside LibraryScreen (shared across Android & Desktop).
 * Organizes playlists into "Collaborative Playlists" (owned) and "Shared with Me" (joined), plus
 * creation and invite-join actions, while leaving Guest mode completely unaffected.
 */
@Composable
fun CollabLibrarySection(
    manager: CollabManager,
    query: String,
    onOpenPlaylist: (String) -> Unit,
    onNewCollaborative: () -> Unit,
    onJoinInvite: () -> Unit,
    onOpenAuthSettings: () -> Unit,
    showOpenLinksBanner: Boolean = false,
    onEnableDefaultYouTubeMusicLinks: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val session by manager.session.collectAsState()
    val allPlaylists by manager.playlists.collectAsState()
    val realtimeConnected by manager.realtimeConnected.collectAsState()

    // Auto-refresh collaborative playlists on display and every 12s while visible
    LaunchedEffect(session?.user?.id) {
        if (session != null) {
            while (true) {
                manager.refreshPlaylists()
                delay(12_000L)
            }
        }
    }

    val filtered = remember(allPlaylists, query) {
        if (query.isBlank()) allPlaylists
        else filterPlaylists(allPlaylists, query) { "${it.title} ${it.ownerName}" }
    }
    val owned = remember(filtered, session) {
        filtered.filter { it.isOwner || it.ownerUserId == session?.user?.id }
    }
    val sharedWithMe = remember(filtered, session) {
        filtered.filterNot { it.isOwner || it.ownerUserId == session?.user?.id }
    }

    if (query.isNotBlank() && owned.isEmpty() && sharedWithMe.isEmpty()) return

    Column(Modifier.fillMaxWidth()) {
        if (query.isBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = PAGE_GUTTER),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    SectionHeader(
                        title = "Collaborative Playlists",
                        subtitle = if (session != null) {
                            buildString {
                                append("Signed in as ${session!!.user.displayName}")
                                if (realtimeConnected) append(" · Live sync")
                            }
                        } else {
                            "Create playlists with friends and see who added every song"
                        },
                    )
                }
                if (session != null) {
                    IconButton(onClick = { scope.launch { manager.refreshPlaylists() } }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh collaborative playlists")
                    }
                }
            }
            ShelfRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING),
            ) {
                item(key = "collab:new") {
                    CollabActionTile(
                        icon = Icons.Rounded.Group,
                        title = "New Collaborative",
                        subtitle = if (session != null) "Invite friends to add songs" else "Sign in with Google",
                        onClick = onNewCollaborative,
                    )
                }
                item(key = "collab:join") {
                    CollabActionTile(
                        icon = Icons.Rounded.Link,
                        title = "Join via Invite",
                        subtitle = "Paste an invite link or code",
                        onClick = onJoinInvite,
                    )
                }
                if (showOpenLinksBanner && onEnableDefaultYouTubeMusicLinks != null) {
                    item(key = "collab:default_links") {
                        CollabActionTile(
                            icon = Icons.Rounded.Link,
                            title = "Open YT Music Links",
                            subtitle = "Open music.youtube.com playlists in BitChord by default",
                            onClick = onEnableDefaultYouTubeMusicLinks,
                        )
                    }
                }
                items(owned.size, key = { "collab:owned:${owned[it].id}" }) { index ->
                    CollabPlaylistCard(
                        summary = owned[index],
                        onClick = { onOpenPlaylist(owned[index].id) },
                    )
                }
                item(key = "collab:account") {
                    CollabActionTile(
                        icon = Icons.Rounded.ManageAccounts,
                        title = if (session != null) session!!.user.displayName else "Collab Account",
                        subtitle = if (session != null) "Google verified · Settings" else "Configure server & Google sign-in",
                        onClick = onOpenAuthSettings,
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
        } else if (owned.isNotEmpty()) {
            SectionHeader(title = "Collaborative Playlists")
            ShelfRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING),
            ) {
                items(owned.size, key = { "collab:owned:${owned[it].id}" }) { index ->
                    CollabPlaylistCard(
                        summary = owned[index],
                        onClick = { onOpenPlaylist(owned[index].id) },
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
        }

        if (sharedWithMe.isNotEmpty()) {
            SectionHeader(
                title = "Shared with Me",
                subtitle = "${sharedWithMe.size} collaborative ${if (sharedWithMe.size == 1) "playlist" else "playlists"}",
            )
            ShelfRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING),
            ) {
                items(sharedWithMe.size, key = { "collab:shared:${sharedWithMe[it].id}" }) { index ->
                    CollabPlaylistCard(
                        summary = sharedWithMe[index],
                        onClick = { onOpenPlaylist(sharedWithMe[index].id) },
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun CollabActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(SHELF_CARD_WIDTH)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .border(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun CollabPlaylistCard(
    summary: CollabPlaylistSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(SHELF_CARD_WIDTH)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ),
                ),
        ) {
            if (!summary.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = summary.artworkUrl.artworkAt(420),
                    contentDescription = summary.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Group,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.align(Alignment.Center).size(44.dp),
                )
            }
            // Subtle collaborative pill badge at bottom-start of cover
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (summary.sharingEnabled) Icons.Rounded.Group else Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = if (summary.sharingEnabled) "${summary.memberCount}" else "Private",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = summary.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${summary.ownerName} · ${summary.trackCount} ${if (summary.trackCount == 1) "song" else "songs"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Full-screen Collaborative Playlist Detail view for Android & Desktop.
 */
@Composable
fun CollabPlaylistDetailScreen(
    playlistId: String,
    manager: CollabManager,
    currentPlayingVideoId: String?,
    onBack: () -> Unit,
    onPlaySongs: (songs: List<Song>, startIndex: Int, shuffle: Boolean, playlistTitle: String) -> Unit,
    onOpenAuthDialog: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(bottom = 110.dp),
) {
    val scope = rememberCoroutineScope()
    val session by manager.session.collectAsState()
    val detailsMap by manager.details.collectAsState()
    val realtimeConnected by manager.realtimeConnected.collectAsState()
    val detail = detailsMap[playlistId]
    val currentUserId = session?.user?.id

    var loading by remember(playlistId) { mutableStateOf(detail == null) }
    var errorMessage by remember(playlistId) { mutableStateOf<String?>(null) }
    var contributorFilter by remember(playlistId) { mutableStateOf<String?>(null) }
    var showActivity by remember(playlistId) { mutableStateOf(false) }
    var showAddSearch by remember(playlistId) { mutableStateOf(false) }
    var showManageDialog by remember(playlistId) { mutableStateOf(false) }
    var manageInitialTab by remember(playlistId) { mutableIntStateOf(0) }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searchingTracks by remember { mutableStateOf(false) }

    DisposableEffect(playlistId, session?.token) {
        if (session != null) {
            manager.subscribePlaylist(playlistId)
        }
        onDispose {
            manager.unsubscribePlaylist(playlistId)
        }
    }

    LaunchedEffect(playlistId, session?.token) {
        if (session == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = detail == null
        manager.loadPlaylistDetail(playlistId).fold(
            onSuccess = {
                errorMessage = null
                loading = false
            },
            onFailure = {
                errorMessage = it.message
                loading = false
            },
        )
    }

    LaunchedEffect(searchQuery, showAddSearch) {
        if (!showAddSearch || searchQuery.trim().length < 2) {
            searchResults = emptyList()
            searchingTracks = false
            return@LaunchedEffect
        }
        searchingTracks = true
        delay(280L)
        val q = searchQuery.trim()
        val found = YtMusicRepository.search(q, SearchFilter.SONGS).getOrNull().orEmpty()
            .mapNotNull { res ->
                when (res) {
                    is SearchResult.Track -> res.song
                    is SearchResult.TopTrack -> res.song
                    else -> null
                }
            }
            .take(12)
        searchResults = found
        searchingTracks = false
    }

    if (session == null && detail == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.Group, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(14.dp))
            Text("Sign in to view this collaborative playlist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Collaborative playlists use verified Google identity so every song shows who added it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Button(onClick = onOpenAuthDialog) { Text("Sign in with Google") }
            }
        }
        return
    }

    if (loading && detail == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val current = detail
    if (current == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(errorMessage ?: "Collaborative playlist unavailable", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Button(
                    onClick = {
                        scope.launch {
                            loading = true
                            manager.loadPlaylistDetail(playlistId)
                            loading = false
                        }
                    },
                ) { Text("Retry") }
            }
        }
        return
    }

    val visibleTracks = remember(current.tracks, contributorFilter) {
        if (contributorFilter == null) current.tracks
        else current.tracks.filter { it.contributorId == contributorFilter }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        // Top navigation & quick actions bar
        item(key = "topbar") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        scope.launch {
                            loading = true
                            manager.loadPlaylistDetail(playlistId)
                            loading = false
                        }
                    },
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh playlist")
                }
                if (current.canInvite(currentUserId)) {
                    OutlinedButton(
                        onClick = {
                            manageInitialTab = 0
                            showManageDialog = true
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Rounded.PersonAdd, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Invite")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(
                    onClick = {
                        manageInitialTab = 1
                        showManageDialog = true
                    },
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Manage collaborative playlist")
                }
            }
        }

        // Apple Music-inspired centered playlist header with member avatar stack
        item(key = "header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val coverUrl = current.artworkUrl
                    ?: current.tracks.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
                                    MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = coverUrl.artworkAt(600),
                            contentDescription = current.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Group,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (current.sharingEnabled) Icons.Rounded.Group else Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = buildString {
                            append(if (current.sharingEnabled) "COLLABORATIVE PLAYLIST" else "PRIVATE SNAPSHOT")
                            if (realtimeConnected && current.sharingEnabled) append(" · LIVE")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.8.sp,
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                if (current.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = current.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(10.dp))
                MemberAvatarStackRow(
                    members = current.members,
                    ownerName = current.ownerName,
                    trackCount = current.tracks.size,
                    onClick = {
                        manageInitialTab = 1
                        showManageDialog = true
                    },
                )

                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            val songs = visibleTracks.map { it.toSong() }
                            if (songs.isNotEmpty()) onPlaySongs(songs, 0, false, current.title)
                        },
                        enabled = visibleTracks.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play", fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            val songs = visibleTracks.map { it.toSong() }
                            if (songs.isNotEmpty()) {
                                onPlaySongs(songs, songs.indices.random(), true, current.title)
                            }
                        },
                        enabled = visibleTracks.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Rounded.Shuffle, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Shuffle", fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    if (current.canAddTracks(currentUserId)) {
                        OutlinedButton(
                            onClick = { showAddSearch = !showAddSearch },
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Icon(if (showAddSearch) Icons.Rounded.Close else Icons.Rounded.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (showAddSearch) "Close Search" else "Add Songs")
                        }
                    }
                    if (current.activities.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showActivity = !showActivity },
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Icon(Icons.Rounded.History, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Activity (${current.activities.size})")
                        }
                    }
                }
            }
        }

        // Inline Add Songs search panel
        item(key = "add-songs-panel") {
            AnimatedVisibility(visible = showAddSearch && current.canAddTracks(currentUserId)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PAGE_GUTTER, vertical = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search songs to add to ${current.title}…") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Rounded.Close, null)
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (searchingTracks) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                        }
                        searchResults.forEach { song ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch { manager.addSongs(current.id, listOf(song)) }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AsyncImage(
                                    model = song.thumbnailUrl.artworkAt(120),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(
                                    onClick = {
                                        scope.launch { manager.addSongs(current.id, listOf(song)) }
                                    },
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = "Add song", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Lightweight Recent Activity strip
        item(key = "activity-panel") {
            AnimatedVisibility(visible = showActivity && current.activities.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "RECENT ACTIVITY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        current.activities.take(8).forEach { act ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CollaboratorAvatar(
                                    name = act.actorName,
                                    avatarUrl = act.actorAvatarUrl,
                                    size = 22.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = act.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Contributor filter pills (Spotify Blend-style attribution filter)
        if (current.members.size > 1 && current.tracks.isNotEmpty()) {
            item(key = "contributor-filters") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = contributorFilter == null,
                        onClick = { contributorFilter = null },
                        label = { Text("All (${current.tracks.size})") },
                    )
                    current.members.forEach { member ->
                        val count = current.tracks.count { it.contributorId == member.userId }
                        if (count > 0) {
                            val label = if (member.userId == currentUserId) "You ($count)" else "${member.displayName} ($count)"
                            FilterChip(
                                selected = contributorFilter == member.userId,
                                onClick = {
                                    contributorFilter = if (contributorFilter == member.userId) null else member.userId
                                },
                                leadingIcon = {
                                    CollaboratorAvatar(name = member.displayName, avatarUrl = member.avatarUrl, size = 18.dp)
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        }

        if (visibleTracks.isEmpty()) {
            item(key = "empty-tracks") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (current.tracks.isEmpty()) "No songs yet — tap Add Songs to start collaborating." else "No songs from this contributor.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        itemsIndexed(
            items = visibleTracks,
            key = { _, entry -> entry.entryId },
        ) { index, entry ->
            val canReorder = contributorFilter == null && current.canReorderTracks(currentUserId)
            val canRemove = current.canRemoveTrack(currentUserId, entry)
            val isPlaying = currentPlayingVideoId != null && currentPlayingVideoId == entry.trackId

            CollabTrackRow(
                entry = entry,
                currentUserId = currentUserId,
                isPlaying = isPlaying,
                canMoveUp = canReorder && index > 0,
                canMoveDown = canReorder && index < visibleTracks.lastIndex,
                canRemove = canRemove,
                onClick = {
                    val songs = visibleTracks.map { it.toSong() }
                    onPlaySongs(songs, index, false, current.title)
                },
                onMoveUp = {
                    val ids = current.tracks.map { it.entryId }.toMutableList()
                    val pos = ids.indexOf(entry.entryId)
                    if (pos > 0) {
                        val tmp = ids[pos - 1]
                        ids[pos - 1] = ids[pos]
                        ids[pos] = tmp
                        scope.launch { manager.reorderTracks(current.id, ids) }
                    }
                },
                onMoveDown = {
                    val ids = current.tracks.map { it.entryId }.toMutableList()
                    val pos = ids.indexOf(entry.entryId)
                    if (pos >= 0 && pos < ids.lastIndex) {
                        val tmp = ids[pos + 1]
                        ids[pos + 1] = ids[pos]
                        ids[pos] = tmp
                        scope.launch { manager.reorderTracks(current.id, ids) }
                    }
                },
                onRemove = {
                    scope.launch { manager.removeTrack(current.id, entry.entryId) }
                },
            )
            if (index < visibleTracks.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = PAGE_GUTTER + 60.dp, end = PAGE_GUTTER),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                )
            }
        }
    }

    if (showManageDialog) {
        CollabInviteAndManageDialog(
            detail = current,
            manager = manager,
            initialTab = manageInitialTab,
            onDismiss = { showManageDialog = false },
            onDeletedOrLeft = {
                showManageDialog = false
                onBack()
            },
        )
    }
}

@Composable
private fun CollabTrackRow(
    entry: CollabTrackEntry,
    currentUserId: String?,
    isPlaying: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (!entry.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = entry.thumbnailUrl.artworkAt(140),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val attribution = entry.contributorLabel(currentUserId)
                if (attribution != null) {
                    CollaboratorAvatar(
                        name = entry.contributorName ?: "?",
                        avatarUrl = entry.contributorAvatarUrl,
                        size = 16.dp,
                    )
                }
                val sub = buildString {
                    append(entry.artist)
                    if (attribution != null) {
                        append(" · ")
                        append(attribution)
                    }
                }
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        entry.toSong().durationText?.let { dur ->
            Spacer(Modifier.width(6.dp))
            Text(
                text = dur,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canMoveUp) {
            IconButton(onClick = onMoveUp, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(16.dp))
            }
        }
        if (canMoveDown) {
            IconButton(onClick = onMoveDown, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(16.dp))
            }
        }
        if (canRemove) {
            IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "Remove from playlist",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@Composable
fun MemberAvatarStackRow(
    members: List<CollabMember>,
    ownerName: String,
    trackCount: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shown = members.take(5)
        if (shown.isNotEmpty()) {
            Box(modifier = Modifier.width((24 + (shown.size - 1) * 16).dp).height(24.dp)) {
                shown.forEachIndexed { idx, m ->
                    Box(
                        modifier = Modifier
                            .offset(x = (idx * 16).dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(1.5.dp),
                    ) {
                        CollaboratorAvatar(name = m.displayName, avatarUrl = m.avatarUrl, size = 21.dp)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "${ownerName} · ${members.size.coerceAtLeast(1)} ${if (members.size == 1) "member" else "members"} · $trackCount ${if (trackCount == 1) "song" else "songs"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun CollaboratorAvatar(
    name: String,
    avatarUrl: String?,
    size: Dp = 24.dp,
) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = initial,
                fontSize = (size.value * 0.48f).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Dialog for creating a brand-new Collaborative Playlist OR converting an existing personal
 * playlist ("Make Collaborative") with clear explanation & permission controls.
 */
@Composable
fun MakeCollaborativeDialog(
    manager: CollabManager,
    sourcePlaylistId: String? = null,
    sourceType: String = "ytm",
    initialTitle: String = "",
    initialDescription: String = "",
    initialArtworkUrl: String? = null,
    existingSongs: List<Song> = emptyList(),
    onDismiss: () -> Unit,
    onOpenAuthDialog: () -> Unit,
    onCreated: (CollabPlaylistDetail) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val session by manager.session.collectAsState()
    val isConversion = sourcePlaylistId != null

    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var editorsRemoveAny by remember { mutableStateOf(true) }
    var editorsReorder by remember { mutableStateOf(true) }
    var editorsInvite by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!submitting) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Group, null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = if (isConversion) "Make Playlist Collaborative" else "New Collaborative Playlist",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (isConversion) {
                    Text(
                        text = "Your original playlist stays untouched. A shared collaborative copy with all ${existingSongs.size} songs attributed to you will be created so friends can join via link and add tracks in real time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (session == null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Sign in with Google to collaborate",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (manager.isNativeYouTubeMode) {
                                    "Sign in to BitChord with your Google / YouTube Music account once — collaborative playlists work automatically with zero extra setup."
                                } else {
                                    "Sign in with Google to verify your contributor identity on your collaboration server."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = onOpenAuthDialog) {
                                Text("Sign in with Google")
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Playlist title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description (optional)") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (manager.isNativeYouTubeMode) {
                        Text(
                            "Shared via your YouTube Music account — anyone you send the invite link to can add and edit tracks together.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "Collaborator permissions",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        PermissionCheckboxRow(
                            checked = editorsRemoveAny,
                            onCheckedChange = { editorsRemoveAny = it },
                            label = "Editors can remove any song (off = only songs they added)",
                        )
                        PermissionCheckboxRow(
                            checked = editorsReorder,
                            onCheckedChange = { editorsReorder = it },
                            label = "Editors can reorder tracks",
                        )
                        PermissionCheckboxRow(
                            checked = editorsInvite,
                            onCheckedChange = { editorsInvite = it },
                            label = "Editors can generate invite links",
                        )
                    }
                }

                errorText?.let { rawErr ->
                    val needsChannel = rawErr.startsWith("CHANNEL_REQUIRED:")
                    val needsReauth = rawErr.startsWith("SESSION_EXPIRED:")
                    val cleanErr = rawErr
                        .removePrefix("CHANNEL_REQUIRED:")
                        .removePrefix("SESSION_EXPIRED:")
                        .trim()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(cleanErr, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (needsChannel || needsReauth) {
                            OutlinedButton(
                                onClick = onOpenAuthDialog,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (needsChannel) "Set Up YouTube Channel" else "Reconnect Google Account")
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") }
                    if (session != null) {
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = !submitting && title.trim().isNotEmpty(),
                            onClick = {
                                submitting = true
                                errorText = null
                                val perms = CollabPermissions(
                                    editorCanRemove = if (editorsRemoveAny) "any" else "own",
                                    editorCanReorder = editorsReorder,
                                    editorCanInvite = editorsInvite,
                                )
                                scope.launch {
                                    val result = if (isConversion && sourcePlaylistId != null) {
                                        manager.convertPersonalPlaylist(
                                            sourcePlaylistId = sourcePlaylistId,
                                            sourceType = sourceType,
                                            title = title,
                                            description = description,
                                            artworkUrl = initialArtworkUrl,
                                            songs = existingSongs,
                                            permissions = perms,
                                        ).map { it.playlist }
                                    } else {
                                        manager.createPlaylist(
                                            title = title,
                                            description = description,
                                            artworkUrl = initialArtworkUrl,
                                            permissions = perms,
                                            initialSongs = existingSongs,
                                        )
                                    }
                                    submitting = false
                                    result.fold(
                                        onSuccess = onCreated,
                                        onFailure = { errorText = it.message ?: "Could not create collaborative playlist." },
                                    )
                                }
                            },
                        ) {
                            if (submitting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (isConversion) "Make Collaborative" else "Create")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Invite generator + Member/Role management + "Stop sharing" reversal dialog.
 */
@Composable
fun CollabInviteAndManageDialog(
    detail: CollabPlaylistDetail,
    manager: CollabManager,
    initialTab: Int = 0,
    onDismiss: () -> Unit,
    onDeletedOrLeft: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val currentUserId = manager.currentUser?.id
    val isOwner = detail.canManageSettings(currentUserId)
    val canInvite = detail.canInvite(currentUserId)

    var tab by remember { mutableIntStateOf(if (!canInvite && initialTab == 0) 1 else initialTab) }
    var inviteRole by remember { mutableStateOf(CollabRole.EDITOR) }
    var expiryHours by remember { mutableStateOf<Long?>(168L) } // 7 days default
    var createdInvite by remember { mutableStateOf<CollabInviteView?>(null) }
    var activeInvites by remember { mutableStateOf<List<CollabInviteView>>(emptyList()) }
    var copiedNotice by remember { mutableStateOf(false) }
    var confirmStopSharing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var statusError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(detail.id, isOwner, tab) {
        if (isOwner && tab == 0) {
            activeInvites = manager.listInvites(detail.id).getOrNull().orEmpty()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 580.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = detail.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
                }

                LaunchedEffect(detail.id) {
                    while (true) {
                        manager.loadPlaylistDetail(detail.id)
                        delay(5_000L)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (canInvite) {
                        FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Invite Link") })
                    }
                    FilterChip(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        label = { Text("Members (${maxOf(detail.memberCount, detail.members.size)})") },
                    )
                    if (isOwner) {
                        FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("Sharing & Rules") })
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { manager.loadPlaylistDetail(detail.id) } }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh members")
                    }
                }
                val isYtmNative = manager.isNativeYouTubeMode || detail.id.startsWith("ytm:")
                if (tab == 0 && canInvite) {
                    Text("Role granted by invite link", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = inviteRole == CollabRole.EDITOR,
                            onClick = { inviteRole = CollabRole.EDITOR },
                            label = { Text("Editor (can add songs)") },
                        )
                        FilterChip(
                            selected = inviteRole == CollabRole.VIEWER,
                            onClick = { inviteRole = CollabRole.VIEWER },
                            label = { Text("Viewer (read-only)") },
                        )
                    }

                    if (!isYtmNative) {
                        Text("Link expiration", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(24L to "24h", 168L to "7 days", 0L to "Never").forEach { (hrs, lbl) ->
                                FilterChip(
                                    selected = (expiryHours ?: 0L) == hrs,
                                    onClick = { expiryHours = if (hrs == 0L) 0L else hrs },
                                    label = { Text(lbl) },
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                statusError = null
                                val sec = expiryHours?.let { if (it <= 0L) 0L else it * 3_600L }
                                manager.createInvite(detail.id, inviteRole, sec).fold(
                                    onSuccess = { inv ->
                                        createdInvite = inv
                                        clipboard.setText(AnnotatedString(inv.inviteUrl))
                                        copiedNotice = true
                                        if (isOwner) activeInvites = manager.listInvites(detail.id).getOrNull().orEmpty()
                                    },
                                    onFailure = { statusError = it.message },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Link, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Create & Copy Invite Link")
                    }

                    createdInvite?.let { inv ->
                        val parsedYtm = CollabInviteLink.parse(inv.inviteUrl)
                        val directAppLink = if (parsedYtm?.isYouTubeMusicInvite == true) {
                            CollabInviteLink.buildBitChordYtmDeepLink(parsedYtm.ytmPlaylistId!!, parsedYtm.ytmJoinToken!!)
                        } else null
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(inv.inviteUrl, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (copiedNotice) {
                                            Text("Copied to clipboard", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    IconButton(onClick = { clipboard.setText(AnnotatedString(inv.inviteUrl)); copiedNotice = true }) {
                                        Icon(Icons.Rounded.ContentCopy, "Copy")
                                    }
                                }
                                if (directAppLink != null) {
                                    OutlinedButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(directAppLink))
                                            copiedNotice = true
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Copy Direct BitChord App Link (bitchord://…)")
                                    }
                                }
                            }
                        }
                    }

                    if (isOwner && activeInvites.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Active invite links", style = MaterialTheme.typography.labelLarge)
                        activeInvites.forEach { inv ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (isYtmNative) "YouTube Music collaboration link" else "${inv.role} · ${inv.useCount} uses",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        inv.inviteUrl.ifBlank { inv.token.take(16) + "…" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (inv.inviteUrl.isNotBlank()) {
                                    IconButton(onClick = { clipboard.setText(AnnotatedString(inv.inviteUrl)); copiedNotice = true }) {
                                        Icon(Icons.Rounded.ContentCopy, "Copy")
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            manager.revokeInvite(detail.id, inv.inviteId)
                                            activeInvites = manager.listInvites(detail.id).getOrNull().orEmpty()
                                        }
                                    },
                                ) { Text("Revoke", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }

                if (tab == 1) {
                    detail.members.forEach { member ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CollaboratorAvatar(name = member.displayName, avatarUrl = member.avatarUrl, size = 34.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = if (member.userId == currentUserId) "${member.displayName} (You)" else member.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(member.role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!isYtmNative && isOwner && member.parsedRole != CollabRole.OWNER) {
                                val nextRole = if (member.parsedRole == CollabRole.EDITOR) CollabRole.VIEWER else CollabRole.EDITOR
                                TextButton(
                                    onClick = { scope.launch { manager.updateMemberRole(detail.id, member.userId, nextRole) } },
                                ) { Text("Make ${nextRole.name.lowercase().replaceFirstChar { it.uppercase() }}") }
                                IconButton(
                                    onClick = { scope.launch { manager.removeMember(detail.id, member.userId) } },
                                ) { Icon(Icons.Rounded.Close, "Remove member", tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }

                    if (!isOwner && currentUserId != null) {
                        HorizontalDivider()
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    manager.leavePlaylist(detail.id).onSuccess { onDeletedOrLeft() }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ExitToApp, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Leave Collaborative Playlist", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (tab == 2 && isOwner) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Editors can remove any song", style = MaterialTheme.typography.bodyMedium)
                            Text("When off, editors can only remove songs they added themselves", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = !detail.permissions.editorsRemoveOwnOnly,
                            onCheckedChange = { allowAny ->
                                scope.launch {
                                    manager.updatePlaylistMetadata(
                                        playlistId = detail.id,
                                        permissions = CollabPermissionsPatch(editorCanRemove = if (allowAny) "any" else "own"),
                                    )
                                }
                            },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Editors can reorder tracks", style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(
                            checked = detail.permissions.editorCanReorder,
                            onCheckedChange = { allow ->
                                scope.launch {
                                    manager.updatePlaylistMetadata(
                                        playlistId = detail.id,
                                        permissions = CollabPermissionsPatch(editorCanReorder = allow),
                                    )
                                }
                            },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Editors can create invite links", style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(
                            checked = detail.permissions.editorCanInvite,
                            onCheckedChange = { allow ->
                                scope.launch {
                                    manager.updatePlaylistMetadata(
                                        playlistId = detail.id,
                                        permissions = CollabPermissionsPatch(editorCanInvite = allow),
                                    )
                                }
                            },
                        )
                    }

                    HorizontalDivider()
                    if (detail.sharingEnabled) {
                        if (!confirmStopSharing) {
                            OutlinedButton(
                                onClick = { confirmStopSharing = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.Lock, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Stop Sharing (Make Private)")
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "Stop sharing this playlist?",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "All active invite links will be revoked and collaborators will lose access immediately. Your playlist and historical contributor attribution will be preserved privately.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { confirmStopSharing = false }) { Text("Keep Shared") }
                                        Button(
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                            onClick = {
                                                scope.launch {
                                                    manager.stopSharing(detail.id)
                                                    confirmStopSharing = false
                                                }
                                            },
                                        ) { Text("Stop Sharing") }
                                    }
                                }
                            }
                        }
                    }

                    if (!confirmDelete) {
                        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Delete Collaborative Playlist", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            onClick = {
                                scope.launch {
                                    manager.deletePlaylist(detail.id).onSuccess { onDeletedOrLeft() }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Confirm Permanent Delete") }
                    }
                }

                statusError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Join Collaborative Playlist via Invite Link/Token dialog (supports logged-out preview + Google auth gate).
 */
@Composable
fun CollabJoinInviteDialog(
    manager: CollabManager,
    initialInviteInput: String = "",
    onDismiss: () -> Unit,
    onOpenAuthDialog: () -> Unit,
    onJoined: (CollabPlaylistDetail) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val session by manager.session.collectAsState()

    var input by remember(initialInviteInput) { mutableStateOf(initialInviteInput) }
    var preview by remember { mutableStateOf<CollabInvitePreview?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialInviteInput) {
        val candidate = initialInviteInput.ifBlank {
            val clipText = runCatching { clipboard.getText()?.text.orEmpty() }.getOrDefault("")
            if (CollabInviteLink.parse(clipText) != null) clipText else ""
        }
        if (candidate.isNotBlank()) {
            if (input.isBlank()) input = candidate
            loading = true
            manager.previewInvite(candidate).fold(
                onSuccess = { (_, p) -> preview = p; errorText = null },
                onFailure = { errorText = it.message },
            )
            loading = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp),
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Join Collaborative Playlist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; preview = null; errorText = null },
                    label = { Text("YouTube Music ?list=…&jct=… link or bci_… token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (preview == null) {
                    Button(
                        enabled = !loading && input.isNotBlank(),
                        onClick = {
                            scope.launch {
                                loading = true
                                errorText = null
                                manager.previewInvite(input).fold(
                                    onSuccess = { (_, p) -> preview = p },
                                    onFailure = { errorText = it.message ?: "Invalid invite link." },
                                )
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("Preview Invite")
                    }
                } else {
                    val p = preview!!
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(p.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Hosted by ${p.ownerName} · ${p.memberCount} members · ${p.trackCount} songs · Join as ${p.role}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (p.expired || p.revoked) {
                                Text(
                                    if (p.revoked) "This invite link has been revoked." else "This invite link has expired.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    if (!p.expired && !p.revoked) {
                        if (session == null) {
                            Button(onClick = onOpenAuthDialog, modifier = Modifier.fillMaxWidth()) {
                                Text("Sign in with Google to Join")
                            }
                        } else {
                            Button(
                                enabled = !loading,
                                onClick = {
                                    scope.launch {
                                        loading = true
                                        manager.acceptInvite(input).fold(
                                            onSuccess = { onJoined(it.playlist) },
                                            onFailure = { errorText = it.message },
                                        )
                                        loading = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Accept & Join Playlist") }
                        }
                    }
                }

                errorText?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

/**
 * Verified Google OpenID Connect Sign-In & Collaboration Server settings dialog.
 * Supports browser-based OAuth 2.0 + PKCE (S256) and verified Google OIDC ID Tokens.
 */
@Composable
fun CollabGoogleAuthDialog(
    manager: CollabManager,
    onDismiss: () -> Unit,
    onOpenAppGoogleSignIn: (() -> Unit)? = null,
    onRequestAndroidGoogleIdToken: ((suspend () -> String?)?) = null,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val session by manager.session.collectAsState()

    var serverUrl by remember { mutableStateOf(manager.effectiveServerUrl()) }
    var googleClientId by remember { mutableStateOf(manager.effectiveGoogleClientId()) }
    var showCustomServer by remember { mutableStateOf(!manager.isNativeYouTubeMode) }
    var manualIdToken by remember { mutableStateOf("") }
    var showTokenPaste by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Collaborative Account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                val activeUser: CollabUser? = session?.user
                if (!showCustomServer) {
                    Text(
                        "Collaborative playlists use the Google / YouTube Music account signed into BitChord automatically — no extra server or OAuth Client ID needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (activeUser != null) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CollaboratorAvatar(name = activeUser.displayName, avatarUrl = activeUser.avatarUrl, size = 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(activeUser.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        activeUser.email.ifBlank { "Connected via BitChord Google Sign-In" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OutlinedButton(
                                    onClick = { scope.launch { manager.refreshPlaylists() } },
                                ) {
                                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Sync")
                                }
                            }
                        }
                    } else if (onOpenAppGoogleSignIn != null) {
                        Button(
                            onClick = {
                                onDismiss()
                                onOpenAppGoogleSignIn()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Sign in to BitChord with Google")
                        }
                    }

                    TextButton(onClick = { showCustomServer = true }) {
                        Text("Advanced: Use a self-hosted Collaboration Server")
                    }
                } else {
                    Text(
                        "Self-hosted Collaboration Server mode (OpenID Connect + WebSocket). Leave Server URL blank to use built-in YouTube Music collaboration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Collaboration Server URL (blank = built-in YouTube Music)") },
                        placeholder = { Text("https://your-bitchord-backend.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (serverUrl.isNotBlank()) {
                        OutlinedTextField(
                            value = googleClientId,
                            onValueChange = { googleClientId = it },
                            label = { Text("Google OAuth Client ID") },
                            placeholder = { Text("xxxx.apps.googleusercontent.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            enabled = !busy && googleClientId.isNotBlank(),
                            onClick = {
                                manager.setServerUrl(serverUrl)
                                manager.setGoogleClientId(googleClientId)
                                busy = true
                                errorText = null
                                scope.launch {
                                    val tok = onRequestAndroidGoogleIdToken?.let { runCatching { it.invoke() }.getOrNull() }
                                    val res = if (!tok.isNullOrBlank()) {
                                        manager.signInWithGoogleIdToken(tok)
                                    } else {
                                        manager.signInWithBrowserPkce { url -> uriHandler.openUri(url) }
                                    }
                                    res.fold(
                                        onSuccess = { onDismiss() },
                                        onFailure = { errorText = it.message },
                                    )
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (busy) "Verifying Google identity…" else "Sign in to Custom Server with Google")
                        }
                        TextButton(onClick = { showTokenPaste = !showTokenPaste }) {
                            Text(if (showTokenPaste) "Hide Google ID token input" else "Paste verified Google OIDC ID token")
                        }
                        if (showTokenPaste) {
                            OutlinedTextField(
                                value = manualIdToken,
                                onValueChange = { manualIdToken = it },
                                label = { Text("Verified Google OIDC ID Token (JWT)") },
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(
                                enabled = !busy && manualIdToken.isNotBlank(),
                                onClick = {
                                    manager.setServerUrl(serverUrl)
                                    manager.setGoogleClientId(googleClientId)
                                    busy = true
                                    errorText = null
                                    scope.launch {
                                        manager.signInWithGoogleIdToken(manualIdToken).fold(
                                            onSuccess = { onDismiss() },
                                            onFailure = { errorText = it.message },
                                        )
                                        busy = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Verify ID Token") }
                        }
                    } else {
                        Button(
                            onClick = {
                                manager.setServerUrl("")
                                showCustomServer = false
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Use Built-In Google / YouTube Music Collaboration")
                        }
                    }
                    TextButton(
                        onClick = {
                            serverUrl = ""
                            manager.setServerUrl("")
                            showCustomServer = false
                        },
                    ) {
                        Text("Switch back to Built-In YouTube Music Mode")
                    }
                }

                errorText?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
                }
            }
        }
    }
}
