package com.music.bitchord.data.collab

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Full stateful simulated YouTube Music Innertube environment exercising real [Innertube],
 * [CollabManager], SAPISIDHASH request signing, replica-settling races, nested response shapes,
 * channel auto-provisioning, local-track resolution, multi-user invite/join, track mutations,
 * and disk persistence end-to-end.
 */
class SimulatedInnertubeCollabEnvTest {

    private lateinit var server: MockWebServer
    private lateinit var sim: SimulatedYtmServer

    @BeforeTest
    fun setUp() {
        sim = SimulatedYtmServer()
        server = MockWebServer()
        server.dispatcher = sim
        server.start()
        val origin = server.url("").toString().trimEnd('/')
        Innertube.musicOriginForTesting = origin
        Innertube.musicBaseUrlForTesting = "$origin/youtubei/v1"
        Innertube.settleRetryDelayMs = 15L
        Innertube.cookie = "SAPISID=test_sapisid_secret_123; __Secure-3PAPISID=test_sapisid_secret_123"
        Innertube.adoptSessionScope(
            pageId = null,
            dataSyncId = "10998877665544332211",
            authUser = "0",
            visitorData = "Cgtsimulatedvisitorid0000000000000000000000000001",
            clientVersion = "1.20261116.01.00",
            loggedIn = true,
        )
    }

    @AfterTest
    fun tearDown() {
        Innertube.cookie = null
        Innertube.selectChannel(null, null)
        Innertube.musicOriginForTesting = null
        Innertube.musicBaseUrlForTesting = null
        Innertube.settleRetryDelayMs = 1_100L
        runCatching { server.shutdown() }
    }

    /**
     * Exact user screenshot scenario:
     * Title `"frequency between 💚 and 💙"` with empty description where YouTube Music returns
     * HTTP 200 with nested `addToToastAction...browseEndpoint.browseId = "VLPL..."` and NO
     * top-level `playlistId`.
     */
    @Test
    fun createsEmojiSharedPlaylistWhenYoutubeReturnsNestedToastBrowseIdWithoutTopLevelPlaylistId() = runBlocking {
        sim.createResponseMode = CreateResponseMode.TOAST_BROWSE_ID_ONLY

        val dir = createTempDirectory("collab_sim_toast").toFile()
        val manager = CollabManager(storageFileProvider = { dir.resolve("state.json") })
        manager.syncAppGoogleAccount(
            signedIn = true,
            accountId = "acct_saksham",
            displayName = "Saksham",
            email = "saksham@example.com",
            avatarUrl = "https://yt3.ggpht.com/saksham",
        )

        val created = manager.createPlaylist(
            title = "frequency between 💚 and 💙",
            description = "",
        ).getOrElse { fail("Expected createPlaylist to succeed, got: ${it.message}", it) }

        assertTrue(created.id.startsWith("ytm:PL_sim_"))
        assertEquals("frequency between 💚 and 💙", created.title)
        assertEquals("Saksham", created.ownerName)
        assertTrue(created.sharingEnabled)

        // Verify blank description was omitted on the wire and SAPISIDHASH was sent
        val createReq = sim.recordedRequests.first { it.path.contains("/playlist/create") }
        assertTrue(createReq.authHeader.startsWith("SAPISIDHASH "))
        assertFalse(createReq.body.containsKey("description"), "Blank description must be omitted from playlist/create")
        assertEquals("UNLISTED", createReq.body["privacyStatus"]?.jsonPrimitive?.content)

        // Verify invite link has valid ?list=...&jct=... token
        val invites = manager.listInvites(created.id).getOrThrow()
        assertEquals(1, invites.size)
        assertTrue(invites.first().inviteUrl.contains("jct=jct_sim_"))
    }

    /**
     * Simulates YouTube Music's documented ~1-20s replica-settling lag:
     * - `playlist/create` returns `command.browseEndpoint.browseId`
     * - First 2 `ACTION_CREATE_COLLABORATION_INVITE_LINK` calls fail with HTTP 409 Conflict
     * - First `browse("VL...")` fails with HTTP 404 Not Found before settling
     * Creation MUST still succeed cleanly on the first tap without throwing.
     */
    @Test
    fun survivesReplicaSettling409OnInviteAnd404OnInitialBrowse() = runBlocking {
        sim.createResponseMode = CreateResponseMode.COMMAND_BROWSE_ID_ONLY
        sim.collabFailuresRemaining.set(2) // First 2 edit_playlist collab calls -> 409 Conflict
        sim.browseFailuresRemaining.set(1) // Initial browse right after create -> 404 Not Found

        val dir = createTempDirectory("collab_sim_settle").toFile()
        val manager = CollabManager(storageFileProvider = { dir.resolve("state.json") })
        manager.syncAppGoogleAccount(
            signedIn = true,
            accountId = "acct_owner",
            displayName = "Pranav",
            email = "pranav@example.com",
            avatarUrl = "https://yt3.ggpht.com/pranav",
        )

        val seedSong = Song(
            videoId = "yKNxeF4KMsY",
            title = "Yellow",
            artist = "Coldplay",
            albumName = "Parachutes",
            thumbnailUrl = "https://i.ytimg.com/vi/yKNxeF4KMsY/hqdefault.jpg",
        )
        val detail = manager.createPlaylist(
            title = "<Late Night> & Chill",
            description = "<b>Roadtrip</b> <3",
            initialSongs = listOf(seedSong),
        ).getOrElse { fail("createPlaylist failed under replica settling: ${it.message}", it) }

        // Title & description sanitized of '<', '>', and HTML tags; fallback detail intact despite 404 browse
        assertEquals("<Late Night> & Chill", detail.title)
        assertEquals(1, detail.tracks.size)
        assertEquals("yKNxeF4KMsY", detail.tracks.first().trackId)

        // Third collab attempt succeeded after 2x 409 retries
        val invites = manager.listInvites(detail.id).getOrThrow()
        assertTrue(invites.single().inviteUrl.contains("jct=jct_sim_"))

        // Next refresh reads settled replica seamlessly
        val settled = manager.loadPlaylistDetail(detail.id).getOrThrow()
        assertEquals("‹Late Night› & Chill", settled.title)
        assertEquals("Roadtrip ‹3", settled.description)
        assertEquals(1, settled.tracks.size)
    }

    /**
     * Simulates a bare Google account with no YouTube channel yet:
     * 1. When YouTube provides a `channelCreationToken` via `channelCreationDialogRenderer` /
     *    `account/accounts_list`, `Innertube.createPlaylist` auto-provisions the channel via
     *    `channel/create_channel` and succeeds transparently!
     * 2. When YouTube requires manual interactive web confirmation (no token), surfaces
     *    `CHANNEL_REQUIRED:` instead of `"playlist created but no id came back"`.
     */
    @Test
    fun autoProvisionsYoutubeChannelWhenTokenOfferedAndDiagnosesManualChannelRequirement() = runBlocking {
        // Case A: Auto-provisionable channel token
        sim.requireChannelCreation = true
        sim.offerAutoChannelToken = true

        val createdId = Innertube.createPlaylist(
            title = "First Playlist Ever",
            privacy = PlaylistPrivacy.PRIVATE,
        )
        assertTrue(createdId.startsWith("PL_sim_"))
        assertTrue(sim.channelCreated.get(), "Expected channel/create_channel to be called automatically")

        // Case B: Hard gated channel creation without token -> clear CHANNEL_REQUIRED message
        sim.channelCreated.set(false)
        sim.requireChannelCreation = true
        sim.offerAutoChannelToken = false

        val err = runCatching {
            Innertube.createPlaylist("Blocked Until Channel", PlaylistPrivacy.PRIVATE)
        }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(
            err.message.orEmpty().startsWith("CHANNEL_REQUIRED:"),
            "Expected CHANNEL_REQUIRED diagnostic, got: ${err.message}",
        )
    }

    /**
     * Tests UNLISTED PAfeature_enablement gate falling back to PRIVATE + silent read-after-write
     * library verification when playlist/create returns HTTP 200 `{}` after creating the playlist.
     */
    @Test
    fun fallsBackFromUnlistedGateToPrivateAndRecoversSilentCreateFromLibrary() = runBlocking {
        // 1. UNLISTED gated by PAfeature_enablement, PRIVATE allowed
        sim.gateUnlistedCreate = true
        val idFromFallback = Innertube.createPlaylist(
            title = "Unlisted Gate Fallback",
            privacy = PlaylistPrivacy.UNLISTED,
        )
        assertTrue(idFromFallback.startsWith("PL_sim_"))
        sim.gateUnlistedCreate = false

        // 2. Silent HTTP 200 `{}` create response recovered from FEmusic_liked_playlists
        sim.createResponseMode = CreateResponseMode.SILENT_EMPTY_OBJECT
        val idFromLib = Innertube.createPlaylist(
            title = "Recovered From Library",
            privacy = PlaylistPrivacy.PRIVATE,
        )
        assertTrue(idFromLib.startsWith("PL_sim_"))
    }

    /**
     * End-to-end multi-user collaborative lifecycle:
     * - Convert personal playlist (>50 tracks + local non-YT track resolved via search + idempotency)
     * - Guest previewInvite & Collaborator acceptInvite (plus owner/existing-member re-join tolerance)
     * - Add tracks (including duplicates with DEDUPE_OPTION_SKIP) & contributor attribution
     * - Immediate removeTrack & reorderTracks even before setVideoId refresh
     * - Rename/description update, revokeInvite, stopSharing, leavePlaylist, deletePlaylist, and disk reload
     */
    @Test
    fun fullMultiUserCollaborationLifecycleAcrossAllOperations() = runBlocking {
        val dirOwner = createTempDirectory("collab_owner").toFile()
        val ownerMgr = CollabManager(storageFileProvider = { dirOwner.resolve("owner.json") })
        ownerMgr.syncAppGoogleAccount(
            signedIn = true,
            accountId = "acct_pranav",
            displayName = "Pranav",
            email = "pranav@example.com",
            avatarUrl = "https://yt3.ggpht.com/pranav",
        )

        // 1. Convert a 55-track personal playlist including a local file ("local:mp3_1")
        val personalSongs = (1..54).map { idx ->
            Song(
                videoId = "vid_%07d".format(idx), // 11 chars: vid_0000001
                title = "Song $idx",
                artist = "Artist $idx",
                thumbnailUrl = null,
            )
        } + Song(
            videoId = "local:device_mp3_999",
            title = "Midnight City",
            artist = "M83",
            thumbnailUrl = null,
        )

        val converted = ownerMgr.convertPersonalPlaylist(
            sourcePlaylistId = "local:my_favorites",
            sourceType = "local",
            title = "Shared Favorites Mix",
            description = "Converted from local playlist",
            songs = personalSongs,
        ).getOrElse { fail("convertPersonalPlaylist failed: ${it.message}", it) }

        assertFalse(converted.alreadyConverted)
        assertEquals(55, converted.playlist.tracks.size)
        // Local track was resolved to simulated search result "4NRXx6U8ABQ" (11 chars), never sent as "local:..."
        assertTrue(converted.playlist.tracks.any { it.trackId == "4NRXx6U8ABQ" && it.title == "Midnight City" })

        // Idempotent second conversion returns alreadyConverted = true without creating a duplicate playlist
        val secondConvert = ownerMgr.convertPersonalPlaylist(
            sourcePlaylistId = "local:my_favorites",
            sourceType = "local",
            title = "Shared Favorites Mix",
            songs = personalSongs,
        ).getOrThrow()
        assertTrue(secondConvert.alreadyConverted)
        assertEquals(converted.playlist.id, secondConvert.playlist.id)

        // 2. Create a clean 2-track collaborative playlist for multi-user collaboration
        val cpl = ownerMgr.createPlaylist(
            title = "Duo Session",
            description = "Initial",
            initialSongs = listOf(
                Song(videoId = "11111111111", title = "Track Alpha", artist = "Band A", thumbnailUrl = null),
                Song(videoId = "22222222222", title = "Track Beta", artist = "Band B", thumbnailUrl = null),
            ),
        ).getOrThrow()

        val invite = ownerMgr.createInvite(cpl.id).getOrThrow()
        assertTrue(invite.inviteUrl.startsWith("https://music.youtube.com/playlist?list="))
        assertTrue(invite.inviteUrl.contains("&jct="))

        // 3. Guest previews invite link while signed out
        val savedCookie = Innertube.cookie
        Innertube.cookie = null
        val dirGuest = createTempDirectory("collab_guest").toFile()
        val collabMgr = CollabManager(storageFileProvider = { dirGuest.resolve("collab.json") })
        val (parsedInvite, preview) = collabMgr.previewInvite(invite.inviteUrl).getOrThrow()
        assertTrue(parsedInvite.isYouTubeMusicInvite)
        assertEquals("Duo Session", preview.title)
        assertEquals(2, preview.trackCount)

        // 4. Collaborator signs in with Google & joins via invite URL
        Innertube.cookie = savedCookie
        collabMgr.syncAppGoogleAccount(
            signedIn = true,
            accountId = "acct_saksham",
            displayName = "Saksham",
            email = "saksham@example.com",
            avatarUrl = "https://yt3.ggpht.com/saksham",
        )
        sim.activeActorName = "Saksham"
        sim.activeActorAvatar = "https://yt3.ggpht.com/saksham"

        val joined = collabMgr.acceptInvite(invite.inviteUrl).getOrThrow()
        assertFalse(joined.alreadyMember)
        assertEquals(cpl.id, joined.playlist.id)
        assertEquals(2, joined.playlist.members.size)

        // Re-joining when already a collaborator (YouTube returns 400 FAILED_PRECONDITION) succeeds gracefully
        sim.rejectNextJoinAsAlreadyMember = true
        val rejoined = collabMgr.acceptInvite(invite.inviteUrl).getOrThrow()
        assertTrue(rejoined.alreadyMember)

        // 5. Collaborator adds a new song + a duplicate song -> DEDUPE_OPTION_SKIP succeeds & attributes contributor
        val afterAdd = collabMgr.addSongs(
            playlistId = cpl.id,
            songs = listOf(
                Song(videoId = "33333333333", title = "Track Gamma", artist = "Band C", thumbnailUrl = null),
                Song(videoId = "11111111111", title = "Track Alpha", artist = "Band A", thumbnailUrl = null),
            ),
        ).getOrThrow()
        assertEquals(3, afterAdd.tracks.size)
        val gammaEntry = afterAdd.tracks.first { it.trackId == "33333333333" }
        assertEquals("Saksham", gammaEntry.contributorName)
        assertEquals("Added by you", gammaEntry.contributorLabel(collabMgr.currentUser?.id))
        assertEquals("Added by Saksham", gammaEntry.contributorLabel("acct_pranav"))

        // 6. Reorder tracks (reverse order) & verify positions
        val reversedIds = afterAdd.tracks.map { it.entryId }.reversed()
        val afterReorder = collabMgr.reorderTracks(cpl.id, reversedIds).getOrThrow()
        assertEquals(reversedIds, afterReorder.tracks.map { it.entryId })

        // 7. Remove middle track & verify removal
        val toRemove = afterReorder.tracks[1].entryId
        val afterRemove = collabMgr.removeTrack(cpl.id, toRemove).getOrThrow()
        assertEquals(2, afterRemove.tracks.size)
        assertFalse(afterRemove.tracks.any { it.entryId == toRemove })

        // 8. Collaborator leaves playlist
        collabMgr.leavePlaylist(cpl.id).getOrThrow()
        assertFalse(collabMgr.playlists.value.any { it.id == cpl.id })

        // 9. Owner updates metadata, stops sharing, verifies disk persistence, and deletes playlist
        sim.activeActorName = "Pranav"
        val renamed = ownerMgr.updatePlaylistMetadata(
            playlistId = cpl.id,
            title = "Duo Session Final",
            description = "Updated description",
        ).getOrThrow()
        assertEquals("Duo Session Final", renamed.title)
        assertEquals("Updated description", renamed.description)

        val stopped = ownerMgr.stopSharing(cpl.id).getOrThrow()
        assertFalse(stopped.sharingEnabled)
        ownerMgr.flushToDisk()

        // Cold-restart reload from disk preserves collaborative playlists across process restarts
        val reloadedMgr = CollabManager(storageFileProvider = { dirOwner.resolve("owner.json") })
        reloadedMgr.syncAppGoogleAccount(
            signedIn = true,
            accountId = "acct_pranav",
            displayName = "Pranav",
            email = "pranav@example.com",
        )
        reloadedMgr.refreshPlaylists().getOrThrow()
        assertTrue(reloadedMgr.playlists.value.any { it.id == cpl.id && it.title == "Duo Session Final" })

        ownerMgr.deletePlaylist(cpl.id).getOrThrow()
        assertFalse(ownerMgr.playlists.value.any { it.id == cpl.id })
    }

    /**
     * Exact two-device reproduction of the reported BitChord-to-BitChord bugs:
     * 1. Stranger pasting an invalid/expired `jct` on an unlisted collaborative playlist
     *    (`meta.isCollaborative == true`) MUST NOT be swallowed as fake-joined; it MUST fail
     *    and never save a read-only viewer bookmark into `collabMgr.playlists`.
     * 2. Collaborator pasting a real invite link (`?list=PL...&jct=tok+with/base64=`, even wrapped
     *    inside chat share text) retries across YouTube's ~15s `jct` propagation window, joins as
     *    a real collaborator in `pl.collaborators`, survives transient post-join `403` ACL lag on
     *    `addSongs`, and NEVER fails with `403 PERMISSION_DENIED`.
     * 3. Owner device (`ownerMgr`) with `subscribePlaylist` open automatically refreshes via
     *    `WEB_REMIX` -> `WEB` `get_panel` fallback + `contributorsAvatars` `accessibilityText` +
     *    facepile avatars, updating `members` from 1 -> 2 (`Pranav` + `Saksham`), updating
     *    Library card `memberCount` to 2, recording `"Saksham joined"`, and showing `"Added by Saksham"`.
     */
    @Test
    fun bitChordToBitChordInviteJoinGrantsWriteAccessAndLiveRefreshesOwnerMembers() = runBlocking {
        sim.omitWebRemixGetPanel = true // Real YouTube behavior: WEB_REMIX get_panel empty, WEB get_panel has roster
        sim.customJctSuffix = "tok+plus/slash="

        val dirOwner = createTempDirectory("b2b_owner").toFile()
        val ownerMgr = CollabManager(storageFileProvider = { dirOwner.resolve("owner.json") })
        sim.activeActorName = "Pranav"
        sim.activeActorAvatar = "https://yt3.ggpht.com/pranav"
        ownerMgr.syncAppGoogleAccount(
            signedIn = true,
            accountId = "acct_pranav",
            displayName = "Pranav",
            email = "pranav@example.com",
            avatarUrl = "https://yt3.ggpht.com/pranav",
        )

        val created = ownerMgr.createPlaylist(
            title = "BitChord Duo Collab",
            description = "Testing real 2-device sync",
            initialSongs = listOf(
                Song(videoId = "aaaaaaaaaaa", title = "Owner Song", artist = "Artist 1", thumbnailUrl = null),
            ),
        ).getOrThrow()
        assertEquals(1, created.members.size)
        assertEquals(1, created.memberCount)

        // Owner opens playlist screen -> starts live 5s (60ms in test) auto-polling
        ownerMgr.subscribePlaylist(created.id)

        val invite = ownerMgr.createInvite(created.id).getOrThrow()
        assertTrue(invite.inviteUrl.contains("jct="))
        val rawPid = created.id.removePrefix("ytm:")

        // Device B: Saksham's BitChord instance
        val dirDeviceB = createTempDirectory("b2b_device_b").toFile()
        val deviceBMgr = CollabManager(storageFileProvider = { dirDeviceB.resolve("deviceb.json") })
        sim.activeActorName = "Saksham"
        sim.activeActorAvatar = "https://yt3.ggpht.com/saksham"
        deviceBMgr.syncAppGoogleAccount(
            signedIn = true,
            accountId = "acct_saksham",
            displayName = "Saksham",
            email = "saksham@example.com",
            avatarUrl = "https://yt3.ggpht.com/saksham",
        )

        // Negative check: wrong jct MUST NOT be swallowed via meta.isCollaborative == true!
        val badJoin = deviceBMgr.acceptInvite("https://music.youtube.com/playlist?list=$rawPid&jct=wrong_token_999")
        assertTrue(badJoin.isFailure, "Joining with invalid jct must fail rather than bookmarking read-only")
        assertTrue(deviceBMgr.playlists.value.none { it.id == created.id })

        // Positive check: real invite pasted inside chat message, with 2x initial propagation 400s
        // AND 1x post-join 403 ACL propagation lag on first ACTION_ADD_VIDEO!
        sim.joinFailuresRemaining.set(2)
        sim.addVideoAclFailuresRemaining.set(1)
        val chatMessage = "Hey join our playlist on BitChord! ${invite.inviteUrl}"

        val joined = deviceBMgr.acceptInvite(chatMessage).getOrElse {
            fail("Expected BitChord-to-BitChord acceptInvite to succeed, got: ${it.message}", it)
        }
        assertEquals(created.id, joined.playlist.id)

        // Also verify direct bitchord://collab/ytm/... link parses identically
        val parsedInvite = CollabInviteLink.parse(chatMessage)
        assertNotNull(parsedInvite)
        val directLink = CollabInviteLink.buildBitChordYtmDeepLink(parsedInvite.ytmPlaylistId!!, parsedInvite.ytmJoinToken!!)
        val reparsedDirect = CollabInviteLink.parse(directLink)
        assertNotNull(reparsedDirect)
        assertEquals(parsedInvite.ytmJoinToken, reparsedDirect.ytmJoinToken)

        // Device B adds a song -> must succeed with NO 403 PERMISSION_DENIED!
        val afterDeviceBAdd = deviceBMgr.addSongs(
            playlistId = created.id,
            songs = listOf(
                Song(videoId = "bbbbbbbbbbb", title = "Collaborator Song", artist = "Artist 2", thumbnailUrl = null),
            ),
        ).getOrElse {
            fail("Device B addSongs threw 403/error after joining: ${it.message}", it)
        }
        assertEquals(2, afterDeviceBAdd.tracks.size)
        assertEquals("Saksham", afterDeviceBAdd.tracks.last().contributorName)

        // Switch perspective back to Owner Device (Pranav) and wait ~180ms for live polling tick
        sim.activeActorName = "Pranav"
        sim.activeActorAvatar = "https://yt3.ggpht.com/pranav"
        kotlinx.coroutines.delay(220L)

        val ownerLiveDetail = ownerMgr.details.value[created.id]
        assertNotNull(ownerLiveDetail)
        assertEquals(2, ownerLiveDetail.members.size, "Owner must see newly joined collaborator in members")
        assertEquals(2, ownerLiveDetail.memberCount)
        assertTrue(ownerLiveDetail.members.any { it.displayName == "Saksham" && it.role == "EDITOR" })
        assertEquals(2, ownerLiveDetail.tracks.size)
        assertEquals("Saksham", ownerLiveDetail.tracks.last().contributorName)
        assertTrue(ownerLiveDetail.activities.any { it.action == "member.join" && it.summary.contains("Saksham") })

        // Library shelf summary on Owner device also shows 2 members
        ownerMgr.refreshPlaylists().getOrThrow()
        val ownerSummary = ownerMgr.playlists.value.first { it.id == created.id }
        assertEquals(2, ownerSummary.memberCount)
        assertEquals(2, ownerSummary.trackCount)

        ownerMgr.unsubscribePlaylist(created.id)
    }
}

internal enum class CreateResponseMode {
    STANDARD_PLAYLIST_ID,
    TOAST_BROWSE_ID_ONLY,
    COMMAND_BROWSE_ID_ONLY,
    SILENT_EMPTY_OBJECT,
}

internal data class RecordedYtmCall(
    val path: String,
    val authHeader: String,
    val body: JsonObject,
)

/**
 * Stateful in-memory YouTube Music Innertube (`youtubei/v1`) simulator supporting:
 * - `playlist/create` (all real response shapes + channel-creation dialog + PAfeature_enablement gate)
 * - `browse/edit_playlist` (privacy, invite link minting, join, add, remove, move, rename, description, close)
 * - `browse` (`VL<playlistId>`, `FEmusic_liked_playlists`)
 * - `get_panel` (`PAplaylist_collaborate`)
 * - `search`, `like/like`, `like/removelike`, `playlist/delete`, `account/accounts_list`, `channel/create_channel`
 */
internal class SimulatedYtmServer : Dispatcher() {
    private val json = Json { ignoreUnknownKeys = true }
    private val seq = AtomicInteger(100)

    data class SimTrack(
        val setVideoId: String,
        val videoId: String,
        val title: String,
        val artist: String,
        val contributorName: String,
        val contributorAvatar: String?,
    )

    data class SimPlaylist(
        val id: String,
        var title: String,
        var description: String,
        var privacy: String,
        var collaborative: Boolean = false,
        var closedToContributions: Boolean = false,
        var jct: String? = null,
        val ownerName: String = "Pranav",
        val ownerAvatar: String = "https://yt3.ggpht.com/pranav",
        val collaborators: MutableList<Triple<String, String, String?>> = mutableListOf(),
        val tracks: MutableList<SimTrack> = mutableListOf(),
        val savedByActors: MutableSet<String> = mutableSetOf(),
    )

    val playlists = ConcurrentHashMap<String, SimPlaylist>()
    val recordedRequests = CopyOnWriteArrayList<RecordedYtmCall>()

    @Volatile var createResponseMode: CreateResponseMode = CreateResponseMode.STANDARD_PLAYLIST_ID
    @Volatile var requireChannelCreation: Boolean = false
    @Volatile var offerAutoChannelToken: Boolean = false
    @Volatile var gateUnlistedCreate: Boolean = false
    @Volatile var rejectNextJoinAsAlreadyMember: Boolean = false
    @Volatile var omitWebRemixGetPanel: Boolean = false
    @Volatile var customJctSuffix: String? = null
    @Volatile var activeActorName: String = "Pranav"
    @Volatile var activeActorAvatar: String = "https://yt3.ggpht.com/pranav"

    val channelCreated = java.util.concurrent.atomic.AtomicBoolean(false)
    val collabFailuresRemaining = AtomicInteger(0)
    val browseFailuresRemaining = AtomicInteger(0)
    val joinFailuresRemaining = AtomicInteger(0)
    val addVideoAclFailuresRemaining = AtomicInteger(0)
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        if (path == "/" || path.startsWith("/?")) {
            val html = """
                <html><script>ytcfg.set({
                  "LOGGED_IN": true,
                  "DATASYNC_ID": "10998877665544332211||",
                  "SESSION_INDEX": "0",
                  "VISITOR_DATA": "Cgtsimulatedvisitorid0000000000000000000000000001",
                  "INNERTUBE_CLIENT_VERSION": "1.20261116.01.00"
                });</script></html>
            """.trimIndent()
            return MockResponse().setResponseCode(200).setBody(html)
        }
        if (path.startsWith("/sw.js_data")) {
            return MockResponse().setResponseCode(200)
                .setBody(")]}'\n[[\"Cgtsimulatedvisitorid0000000000000000000000000001\"]]")
        }

        val rawBody = request.body.readUtf8()
        val bodyObj = if (rawBody.isNotBlank()) {
            json.parseToJsonElement(rawBody).jsonObject
        } else {
            buildJsonObject {}
        }
        recordedRequests.add(
            RecordedYtmCall(
                path = path,
                authHeader = request.getHeader("Authorization").orEmpty(),
                body = bodyObj,
            ),
        )

        return when {
            path.contains("/youtubei/v1/account/accounts_list") -> {
                val resp = buildJsonObject {
                    if (offerAutoChannelToken) {
                        put("channelCreationToken", "cct_auto_token_123")
                    }
                }
                okJson(resp)
            }

            path.contains("/youtubei/v1/channel/create_channel") -> {
                val tok = bodyObj["channelCreationToken"]?.jsonPrimitive?.contentOrNull
                if (!tok.isNullOrBlank()) {
                    channelCreated.set(true)
                    requireChannelCreation = false
                    okJson(buildJsonObject { put("channelId", "UC_sim_new_channel") })
                } else {
                    MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Missing token"}}""")
                }
            }

            path.contains("/youtubei/v1/playlist/create") -> handleCreatePlaylist(bodyObj)
            path.contains("/youtubei/v1/playlist/get_add_to_playlist") -> handleGetAddToPlaylist()
            path.contains("/youtubei/v1/browse/edit_playlist") -> handleEditPlaylist(bodyObj)
            path.contains("/youtubei/v1/browse") -> handleBrowse(bodyObj)
            path.contains("/youtubei/v1/get_panel") -> handleGetPanel(bodyObj)
            path.contains("/youtubei/v1/search") -> handleSearch()
            path.contains("/youtubei/v1/playlist/delete") -> {
                val pid = bodyObj["playlistId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                playlists.remove(pid)
                okJson(buildJsonObject { put("status", "STATUS_SUCCEEDED") })
            }
            path.contains("/youtubei/v1/like/like") -> {
                val pid = bodyObj["target"]?.jsonObject?.get("playlistId")?.jsonPrimitive?.contentOrNull.orEmpty()
                playlists[pid]?.savedByActors?.add(activeActorName.lowercase())
                okJson(buildJsonObject { put("status", "STATUS_SUCCEEDED") })
            }
            path.contains("/youtubei/v1/like/removelike") -> {
                val pid = bodyObj["target"]?.jsonObject?.get("playlistId")?.jsonPrimitive?.contentOrNull.orEmpty()
                playlists[pid]?.savedByActors?.remove(activeActorName.lowercase())
                okJson(buildJsonObject { put("status", "STATUS_SUCCEEDED") })
            }
            else -> okJson(buildJsonObject {})
        }
    }

    private fun handleCreatePlaylist(body: JsonObject): MockResponse {
        if (requireChannelCreation && !channelCreated.get()) {
            val popup = buildJsonObject {
                putJsonArray("actions") {
                    add(
                        buildJsonObject {
                            putJsonObject("openPopupAction") {
                                putJsonObject("popup") {
                                    putJsonObject("channelCreationDialogRenderer") {
                                        putJsonObject("title") { put("simpleText", "Create a channel") }
                                    }
                                }
                            }
                        },
                    )
                }
            }
            return okJson(popup)
        }

        val privacy = body["privacyStatus"]?.jsonPrimitive?.contentOrNull ?: "PRIVATE"
        if (gateUnlistedCreate && privacy != "PRIVATE") {
            val gated = buildJsonObject {
                putJsonArray("actions") {
                    add(
                        buildJsonObject {
                            putJsonObject("showEngagementPanelEndpoint") {
                                putJsonObject("identifier") { put("tag", "PAfeature_enablement") }
                            }
                        },
                    )
                }
            }
            return okJson(gated)
        }

        val title = body["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val desc = body["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val id = "PL_sim_${seq.incrementAndGet()}"
        val pl = SimPlaylist(
            id = id,
            title = title,
            description = desc,
            privacy = privacy,
            ownerName = activeActorName,
            ownerAvatar = activeActorAvatar,
        )
        pl.collaborators.add(Triple("UC_owner_${pl.ownerName.lowercase()}", pl.ownerName, pl.ownerAvatar))
        pl.savedByActors.add(pl.ownerName.lowercase())
        body["videoIds"]?.jsonArray?.forEach { vidEl ->
            val vid = vidEl.jsonPrimitive.content
            if (!Regex("^[A-Za-z0-9_-]{11}$").matches(vid)) {
                return MockResponse().setResponseCode(400)
                    .setBody("""{"error":{"code":400,"message":"Invalid videoId: $vid","status":"INVALID_ARGUMENT"}}""")
            }
            val svid = "svid_${seq.incrementAndGet()}"
            pl.tracks.add(SimTrack(svid, vid, "Track $vid", "Artist", activeActorName, activeActorAvatar))
        }
        playlists[id] = pl

        val resp = when (createResponseMode) {
            CreateResponseMode.STANDARD_PLAYLIST_ID -> buildJsonObject {
                put("playlistId", id)
                put("status", "STATUS_SUCCEEDED")
            }
            CreateResponseMode.TOAST_BROWSE_ID_ONLY -> buildJsonObject {
                putJsonObject("responseContext") { put("visitorData", "Cgtsimulatedvisitorid0000000000000000000000000001") }
                putJsonArray("actions") {
                    add(
                        buildJsonObject {
                            putJsonObject("addToToastAction") {
                                putJsonObject("item") {
                                    putJsonObject("notificationTextRenderer") {
                                        putJsonObject("navigationEndpoint") {
                                            putJsonObject("browseEndpoint") {
                                                put("browseId", "VL$id")
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
            }
            CreateResponseMode.COMMAND_BROWSE_ID_ONLY -> buildJsonObject {
                putJsonObject("command") {
                    putJsonObject("browseEndpoint") {
                        put("browseId", "VL$id")
                    }
                }
            }
            CreateResponseMode.SILENT_EMPTY_OBJECT -> buildJsonObject {
                putJsonObject("responseContext") { put("visitorData", "Cgtsimulatedvisitorid0000000000000000000000000001") }
            }
        }
        return okJson(resp)
    }

    private fun handleEditPlaylist(body: JsonObject): MockResponse {
        val pid = body["playlistId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val pl = playlists[pid]
            ?: return MockResponse().setResponseCode(404).setBody("""{"error":{"code":404,"message":"Not found"}}""")
        val actions = body["actions"]?.jsonArray.orEmpty()
        val addedResults = mutableListOf<Pair<String, String>>()
        var mintedInviteLink: String? = null

        for (actEl in actions) {
            val act = actEl.jsonObject
            when (act["action"]?.jsonPrimitive?.contentOrNull) {
                "ACTION_SET_PLAYLIST_PRIVACY" -> {
                    pl.privacy = act["playlistPrivacy"]?.jsonPrimitive?.contentOrNull ?: pl.privacy
                }
                "ACTION_SET_PLAYLIST_NAME" -> {
                    pl.title = act["playlistName"]?.jsonPrimitive?.contentOrNull ?: pl.title
                }
                "ACTION_SET_PLAYLIST_DESCRIPTION" -> {
                    pl.description = act["playlistDescription"]?.jsonPrimitive?.contentOrNull ?: pl.description
                }
                "ACTION_CREATE_COLLABORATION_INVITE_LINK" -> {
                    if (collabFailuresRemaining.getAndDecrement() > 0) {
                        return MockResponse().setResponseCode(409)
                            .setBody("""{"error":{"code":409,"message":"Playlist settling","status":"ABORTED"}}""")
                    }
                    pl.collaborative = true
                    pl.closedToContributions = false
                    if (pl.jct == null) pl.jct = customJctSuffix ?: "jct_sim_${pid.takeLast(6)}"
                    val encodedJct = java.net.URLEncoder.encode(pl.jct, "UTF-8").replace("+", "%20")
                    mintedInviteLink = "https://www.youtube.com/playlist?list=$pid&jct=$encodedJct"
                }
                "ACTION_JOIN_COLLABORATION" -> {
                    if (joinFailuresRemaining.getAndDecrement() > 0) {
                        return MockResponse().setResponseCode(400)
                            .setBody("""{"error":{"code":400,"message":"Precondition check failed.","status":"FAILED_PRECONDITION"}}""")
                    }
                    if (rejectNextJoinAsAlreadyMember) {
                        rejectNextJoinAsAlreadyMember = false
                        return MockResponse().setResponseCode(400)
                            .setBody("""{"error":{"code":400,"message":"Already collaborator","status":"FAILED_PRECONDITION"}}""")
                    }
                    val token = act["joinCollaborationToken"]?.jsonPrimitive?.contentOrNull
                    if (token != pl.jct) {
                        return MockResponse().setResponseCode(403)
                            .setBody("""{"error":{"code":403,"message":"Sorry, something went wrong.","status":"PERMISSION_DENIED"}}""")
                    }
                    if (pl.collaborators.none { it.second.equals(activeActorName, ignoreCase = true) }) {
                        pl.collaborators.add(Triple("UC_collab_${activeActorName.lowercase()}", activeActorName, activeActorAvatar))
                    }
                }
                "ACTION_LEAVE_COLLABORATIVE_PLAYLIST" -> {
                    pl.collaborators.removeIf { it.second.equals(activeActorName, ignoreCase = true) && !it.second.equals(pl.ownerName, ignoreCase = true) }
                }
                "ACTION_SET_CLOSED_TO_CONTRIBUTIONS" -> {
                    val closed = act["closedToContributions"]?.jsonPrimitive?.booleanOrNull ?: true
                    pl.closedToContributions = closed
                    if (closed) {
                        pl.collaborative = false
                        pl.jct = null
                    }
                }
                "ACTION_ADD_VIDEO" -> {
                    val isAllowed = pl.ownerName.equals(activeActorName, ignoreCase = true) ||
                        (!pl.closedToContributions && pl.collaborators.any { it.second.equals(activeActorName, ignoreCase = true) })
                    if (!isAllowed || addVideoAclFailuresRemaining.getAndDecrement() > 0) {
                        return MockResponse().setResponseCode(403)
                            .setBody("""{"error":{"code":403,"message":"Sorry, something went wrong.","status":"PERMISSION_DENIED"}}""")
                    }
                    val vid = act["addedVideoId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val dedupe = act["dedupeOption"]?.jsonPrimitive?.contentOrNull
                    val alreadyHas = pl.tracks.any { it.videoId == vid }
                    if (alreadyHas && dedupe != "DEDUPE_OPTION_SKIP") {
                        return okJson(buildJsonObject { put("status", "STATUS_FAILED") })
                    }
                    if (!alreadyHas) {
                        val svid = "svid_${seq.incrementAndGet()}"
                        val simTitle = if (vid == "4NRXx6U8ABQ") "Midnight City" else "Track $vid"
                        val simArtist = if (vid == "4NRXx6U8ABQ") "M83" else "Artist"
                        pl.tracks.add(SimTrack(svid, vid, simTitle, simArtist, activeActorName, activeActorAvatar))
                        addedResults.add(vid to svid)
                    }
                }
                "ACTION_REMOVE_VIDEO" -> {
                    val isAllowed = pl.ownerName.equals(activeActorName, ignoreCase = true) ||
                        (!pl.closedToContributions && pl.collaborators.any { it.second.equals(activeActorName, ignoreCase = true) })
                    if (!isAllowed) {
                        return MockResponse().setResponseCode(403)
                            .setBody("""{"error":{"code":403,"message":"Sorry, something went wrong.","status":"PERMISSION_DENIED"}}""")
                    }
                    val svid = act["setVideoId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val removed = pl.tracks.removeIf { it.setVideoId == svid }
                    if (!removed) {
                        return okJson(buildJsonObject { put("status", "STATUS_FAILED") })
                    }
                }
                "ACTION_MOVE_VIDEO_BEFORE" -> {
                    val svid = act["setVideoId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val before = act["movedSetVideoIdSuccessor"]?.jsonPrimitive?.contentOrNull
                    val idx = pl.tracks.indexOfFirst { it.setVideoId == svid }
                    if (idx >= 0) {
                        val item = pl.tracks.removeAt(idx)
                        if (before == null) {
                            pl.tracks.add(item)
                        } else {
                            val targetIdx = pl.tracks.indexOfFirst { it.setVideoId == before }
                            if (targetIdx >= 0) pl.tracks.add(targetIdx, item) else pl.tracks.add(item)
                        }
                    }
                }
            }
        }

        val resp = buildJsonObject {
            put("status", "STATUS_SUCCEEDED")
            if (mintedInviteLink != null) {
                put("collaborationInviteLink", mintedInviteLink)
            }
            if (addedResults.isNotEmpty()) {
                putJsonArray("playlistEditResults") {
                    addedResults.forEach { (vid, svid) ->
                        add(
                            buildJsonObject {
                                putJsonObject("playlistEditVideoAddedResultData") {
                                    put("videoId", vid)
                                    put("setVideoId", svid)
                                }
                            },
                        )
                    }
                }
            }
        }
        return okJson(resp)
    }

    private fun handleBrowse(body: JsonObject): MockResponse {
        val browseId = body["browseId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (browseId == "FEmusic_liked_playlists") {
            val items = buildJsonArray {
                playlists.values.filter { activeActorName.lowercase() in it.savedByActors }.forEach { pl ->
                    add(
                        buildJsonObject {
                            putJsonObject("musicTwoRowItemRenderer") {
                                putJsonObject("title") {
                                    putJsonArray("runs") { add(buildJsonObject { put("text", pl.title) }) }
                                }
                                putJsonObject("navigationEndpoint") {
                                    putJsonObject("browseEndpoint") { put("browseId", "VL${pl.id}") }
                                }
                            }
                        },
                    )
                }
            }
            return okJson(
                buildJsonObject {
                    putJsonObject("contents") {
                        putJsonObject("singleColumnBrowseResultsRenderer") {
                            putJsonArray("tabs") {
                                add(
                                    buildJsonObject {
                                        putJsonObject("tabRenderer") {
                                            putJsonObject("content") {
                                                putJsonObject("sectionListRenderer") {
                                                    putJsonArray("contents") {
                                                        add(buildJsonObject { putJsonObject("gridRenderer") { put("items", items) } })
                                                    }
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
            )
        }

        if (browseFailuresRemaining.getAndDecrement() > 0) {
            return MockResponse().setResponseCode(404).setBody("""{"error":{"code":404,"message":"Settling"}}""")
        }

        val pid = browseId.removePrefix("VL")
        val pl = playlists[pid]
            ?: return MockResponse().setResponseCode(404).setBody("""{"error":{"code":404,"message":"Playlist not found"}}""")

        val isOwned = pl.ownerName.equals(activeActorName, ignoreCase = true)
        val headerObj = buildJsonObject {
            putJsonObject("title") {
                putJsonArray("runs") { add(buildJsonObject { put("text", pl.title) }) }
            }
            if (pl.description.isNotBlank()) {
                putJsonObject("description") {
                    putJsonObject("musicDescriptionShelfRenderer") {
                        putJsonObject("description") {
                            putJsonArray("runs") { add(buildJsonObject { put("text", pl.description) }) }
                        }
                    }
                }
            }
            putJsonObject("straplineTextOne") {
                putJsonArray("runs") { add(buildJsonObject { put("text", pl.ownerName) }) }
            }
            if (pl.collaborative && !pl.closedToContributions) {
                putJsonObject("facepile") {
                    putJsonObject("avatarStackViewModel") {
                        putJsonObject("text") { put("content", pl.ownerName) }
                        putJsonArray("avatars") {
                            pl.collaborators.forEach { (_, _, av) ->
                                add(
                                    buildJsonObject {
                                        putJsonObject("avatarViewModel") {
                                            putJsonObject("image") {
                                                putJsonArray("sources") {
                                                    add(buildJsonObject { put("url", av ?: "https://yt3.ggpht.com/default") })
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        putJsonObject("rendererContext") {
                            putJsonObject("accessibilityContext") {
                                val labelText = if (pl.collaborators.size <= 1) {
                                    pl.ownerName
                                } else {
                                    val extra = pl.collaborators.size - 1
                                    "by ${pl.ownerName} and $extra ${if (extra == 1) "other" else "others"}"
                                }
                                put("label", labelText)
                            }
                            putJsonObject("commandContext") {
                                putJsonObject("onTap") {
                                    putJsonObject("innertubeCommand") {
                                        putJsonObject("showEngagementPanelEndpoint") {
                                            putJsonObject("identifier") { put("tag", "PAplaylist_collaborate") }
                                            putJsonObject("globalConfiguration") { put("params", "panel_$pid") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val trackItems = buildJsonArray {
            pl.tracks.forEach { t ->
                add(
                    buildJsonObject {
                        putJsonObject("musicResponsiveListItemRenderer") {
                            putJsonObject("playlistItemData") {
                                put("videoId", t.videoId)
                                put("playlistSetVideoId", t.setVideoId)
                            }
                            putJsonObject("contributorsAvatars") {
                                putJsonObject("avatarStackViewModel") {
                                    putJsonArray("avatars") {
                                        add(
                                            buildJsonObject {
                                                putJsonObject("avatarViewModel") {
                                                    put("accessibilityText", t.contributorName)
                                                    putJsonObject("image") {
                                                        putJsonArray("sources") {
                                                            add(buildJsonObject { put("url", t.contributorAvatar ?: "https://yt3.ggpht.com/default") })
                                                        }
                                                    }
                                                }
                                            },
                                        )
                                    }
                                    putJsonObject("rendererContext") {
                                        putJsonObject("commandContext") {
                                            putJsonObject("onTap") {
                                                putJsonObject("innertubeCommand") {
                                                    putJsonObject("browseEndpoint") {
                                                        put("browseId", "UC_contrib_${t.contributorName.lowercase()}")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            putJsonArray("flexColumns") {
                                add(
                                    buildJsonObject {
                                        putJsonObject("musicResponsiveListItemFlexColumnRenderer") {
                                            putJsonObject("text") {
                                                putJsonArray("runs") {
                                                    add(
                                                        buildJsonObject {
                                                            put("text", t.title)
                                                            putJsonObject("navigationEndpoint") {
                                                                putJsonObject("watchEndpoint") { put("videoId", t.videoId) }
                                                            }
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        putJsonObject("musicResponsiveListItemFlexColumnRenderer") {
                                            putJsonObject("text") {
                                                putJsonArray("runs") {
                                                    add(buildJsonObject { put("text", t.artist) })
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                            putJsonArray("fixedColumns") {
                                add(
                                    buildJsonObject {
                                        putJsonObject("musicResponsiveListItemFixedColumnRenderer") {
                                            putJsonObject("text") {
                                                putJsonArray("runs") {
                                                    add(buildJsonObject { put("text", "3:45") })
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }

        val root = buildJsonObject {
            putJsonObject("contents") {
                putJsonObject("twoColumnBrowseResultsRenderer") {
                    putJsonArray("tabs") {
                        add(
                            buildJsonObject {
                                putJsonObject("tabRenderer") {
                                    putJsonObject("content") {
                                        putJsonObject("sectionListRenderer") {
                                            putJsonArray("contents") {
                                                add(
                                                    buildJsonObject {
                                                        if (isOwned) {
                                                            putJsonObject("musicEditablePlaylistDetailHeaderRenderer") {
                                                                put("playlistId", pl.id)
                                                                putJsonObject("editHeader") {
                                                                    putJsonObject("musicPlaylistEditHeaderRenderer") {
                                                                        put("privacy", pl.privacy)
                                                                    }
                                                                }
                                                                putJsonObject("header") {
                                                                    put("musicResponsiveHeaderRenderer", headerObj)
                                                                }
                                                            }
                                                        } else {
                                                            put("musicResponsiveHeaderRenderer", headerObj)
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                    putJsonObject("secondaryContents") {
                        putJsonObject("sectionListRenderer") {
                            putJsonArray("contents") {
                                add(
                                    buildJsonObject {
                                        putJsonObject("musicPlaylistShelfRenderer") {
                                            put("playlistId", pl.id)
                                            put("contents", trackItems)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        return okJson(root)
    }

    private fun handleGetAddToPlaylist(): MockResponse {
        val writable = playlists.values.filter { pl ->
            pl.ownerName.equals(activeActorName, ignoreCase = true) ||
                (!pl.closedToContributions && pl.collaborators.any { it.second.equals(activeActorName, ignoreCase = true) })
        }
        val resp = buildJsonObject {
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        putJsonObject("addToPlaylistRenderer") {
                            putJsonArray("playlists") {
                                writable.forEach { pl ->
                                    add(
                                        buildJsonObject {
                                            putJsonObject("playlistAddToOptionRenderer") {
                                                put("playlistId", pl.id)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
        return okJson(resp)
    }

    private fun handleGetPanel(body: JsonObject): MockResponse {
        val clientName = body["context"]?.jsonObject?.get("client")?.jsonObject?.get("clientName")?.jsonPrimitive?.contentOrNull
        if (omitWebRemixGetPanel && clientName == "WEB_REMIX") {
            return okJson(buildJsonObject {})
        }
        val params = body["params"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val pid = params.removePrefix("panel_").ifBlank { body["playlistId"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        val pl = playlists[pid] ?: return okJson(buildJsonObject {})
        val resp = buildJsonObject {
            putJsonObject("playlistCollaborationViewModel") {
                putJsonArray("playlistCollaborators") {
                    pl.collaborators.forEach { (cid, name, av) ->
                        add(
                            buildJsonObject {
                                putJsonObject("contentListItemViewModel") {
                                    putJsonObject("title") { put("content", name) }
                                    putJsonObject("avatar") {
                                        putJsonObject("avatarViewModel") {
                                            putJsonObject("image") {
                                                putJsonArray("sources") {
                                                    add(buildJsonObject { put("url", av ?: "https://yt3.ggpht.com/default") })
                                                }
                                            }
                                        }
                                    }
                                    putJsonObject("rendererContext") {
                                        putJsonObject("commandContext") {
                                            putJsonObject("onTap") {
                                                putJsonObject("innertubeCommand") {
                                                    putJsonObject("browseEndpoint") {
                                                        put("browseId", cid)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        return okJson(resp)
    }

    private fun handleSearch(): MockResponse {
        val resp = buildJsonObject {
            putJsonObject("contents") {
                putJsonObject("tabbedSearchResultsRenderer") {
                    putJsonArray("tabs") {
                        add(
                            buildJsonObject {
                                putJsonObject("tabRenderer") {
                                    putJsonObject("content") {
                                        putJsonObject("sectionListRenderer") {
                                            putJsonArray("contents") {
                                                add(
                                                    buildJsonObject {
                                                        putJsonObject("musicShelfRenderer") {
                                                            putJsonObject("title") {
                                                                putJsonArray("runs") { add(buildJsonObject { put("text", "Songs") }) }
                                                            }
                                                            putJsonArray("contents") {
                                                                add(
                                                                    buildJsonObject {
                                                                        putJsonObject("musicResponsiveListItemRenderer") {
                                                                            putJsonObject("playlistItemData") { put("videoId", "4NRXx6U8ABQ") }
                                                                            putJsonArray("flexColumns") {
                                                                                add(
                                                                                    buildJsonObject {
                                                                                        putJsonObject("musicResponsiveListItemFlexColumnRenderer") {
                                                                                            putJsonObject("text") {
                                                                                                putJsonArray("runs") {
                                                                                                    add(
                                                                                                        buildJsonObject {
                                                                                                            put("text", "Midnight City")
                                                                                                            putJsonObject("navigationEndpoint") {
                                                                                                                putJsonObject("watchEndpoint") {
                                                                                                                    put("videoId", "4NRXx6U8ABQ")
                                                                                                                    putJsonObject("watchEndpointMusicSupportedConfigs") {
                                                                                                                        putJsonObject("watchEndpointMusicConfig") {
                                                                                                                            put("musicVideoType", "MUSIC_VIDEO_TYPE_ATV")
                                                                                                                        }
                                                                                                                    }
                                                                                                                }
                                                                                                            }
                                                                                                        },
                                                                                                    )
                                                                                                }
                                                                                            }
                                                                                        }
                                                                                    },
                                                                                )
                                                                                add(
                                                                                    buildJsonObject {
                                                                                        putJsonObject("musicResponsiveListItemFlexColumnRenderer") {
                                                                                            putJsonObject("text") {
                                                                                                putJsonArray("runs") {
                                                                                                    add(buildJsonObject { put("text", "M83") })
                                                                                                    add(buildJsonObject { put("text", " • ") })
                                                                                                    add(buildJsonObject { put("text", "4:03") })
                                                                                                }
                                                                                            }
                                                                                        }
                                                                                    },
                                                                                )
                                                                            }
                                                                        }
                                                                    },
                                                                )
                                                            }
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        return okJson(resp)
    }

    private fun okJson(obj: JsonObject): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(obj.toString())
}
