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

        // Signing in starts a library refresh in the background. Let it finish first, or it can
        // browse the new playlist and take the one 404 this test stages for the create.
        awaitBackgroundLibraryRefresh()
        sim.browseFailuresRemaining.set(1)

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

    private suspend fun awaitBackgroundLibraryRefresh() {
        repeat(200) {
            if (sim.recordedRequests.any { it.path.contains("/playlist/get_add_to_playlist") }) {
                kotlinx.coroutines.delay(60L)
                return
            }
            kotlinx.coroutines.delay(10L)
        }
    }

    private fun managerFor(actor: String, dirName: String = "collab_${actor.lowercase()}"): CollabManager {
        val dir = createTempDirectory(dirName).toFile()
        val manager = CollabManager(storageFileProvider = { dir.resolve("state.json") })
        actAs(actor)
        manager.syncAppGoogleAccount(
            signedIn = true,
            accountId = "acct_${actor.lowercase()}",
            displayName = actor,
            email = "${actor.lowercase()}@example.com",
            avatarUrl = "https://yt3.ggpht.com/${actor.lowercase()}",
        )
        return manager
    }

    private fun actAs(actor: String) {
        sim.activeActorName = actor
        sim.activeActorAvatar = "https://yt3.ggpht.com/${actor.lowercase()}"
    }

    private suspend fun sharedPlaylist(owner: CollabManager): Pair<CollabPlaylistDetail, String> {
        val created = owner.createPlaylist(
            title = "Road Trip",
            initialSongs = listOf(Song(videoId = "aaaaaaaaaaa", title = "Owner Song", artist = "Artist", thumbnailUrl = null)),
        ).getOrThrow()
        val invite = owner.createInvite(created.id).getOrThrow()
        return created to invite.inviteUrl
    }

    /**
     * The v1.10.3 screenshot: `edit_playlist` answered the join with
     * `401 "You are not authorized to edit this playlist."`. YouTube Music says exactly that
     * while a freshly minted `jct` has not reached the replica answering, so it has to be
     * waited out — it used to end the attempt on the spot and land on screen as raw JSON.
     */
    @Test
    fun joinAnswered401WhileInviteTokenPropagatesIsRetriedUntilItLands() = runBlocking {
        val owner = managerFor("Pranav")
        val (created, inviteUrl) = sharedPlaylist(owner)
        val rawId = created.id.removePrefix("ytm:")

        val guest = managerFor("Saksham")
        sim.joinUnauthorizedRemaining.set(3)
        val joined = guest.acceptInvite(inviteUrl).getOrElse { fail("join should outlast the 401s: ${it.message}", it) }

        assertFalse(joined.alreadyMember)
        assertTrue(sim.playlists.getValue(rawId).collaborators.any { it.second == "Saksham" })
        assertEquals(0, sim.joinUnauthorizedRemaining.get().coerceAtLeast(0))
        // Write access is checked the way the web client's "Save to playlist" dialog asks.
        assertTrue(
            sim.recordedRequests.any { call ->
                call.path.contains("/playlist/get_add_to_playlist") && (call.body["videoIds"] as? JsonArray).orEmpty().isNotEmpty()
            },
        )
        val added = guest.addSongs(created.id, listOf(Song(videoId = "bbbbbbbbbbb", title = "Guest Song", artist = "Artist", thumbnailUrl = null)))
        assertTrue(added.isSuccess, "a joined collaborator can add songs")
    }

    /**
     * 401 is also what an account that is *already* a collaborator gets for joining again.
     * A second device, or a reinstall, signed in as the same person must be let in, not shown
     * an error — which needs the write-access check to actually see the membership.
     */
    @Test
    fun rejoiningAsAnExistingCollaboratorIsRecognisedDespiteThe401() = runBlocking {
        val owner = managerFor("Pranav")
        val (created, inviteUrl) = sharedPlaylist(owner)

        val firstDevice = managerFor("Saksham", "collab_saksham_phone")
        firstDevice.acceptInvite(inviteUrl).getOrThrow()

        // A second install for the same account: nothing remembered locally.
        val secondDevice = managerFor("Saksham", "collab_saksham_tablet")
        val again = secondDevice.acceptInvite(inviteUrl).getOrElse {
            fail("an existing collaborator must be let back in, got: ${it.message}", it)
        }
        assertTrue(again.alreadyMember)
        assertEquals(created.id, again.playlist.id)
        assertEquals(CollabRole.EDITOR.name, again.playlist.myRole)

        // The owner opening their own invite link is the same case.
        actAs("Pranav")
        val ownerTap = owner.acceptInvite(inviteUrl).getOrThrow()
        assertTrue(ownerTap.alreadyMember)
    }

    /**
     * When the hand-built join is refused outright, the join is made the way
     * music.youtube.com makes it: the playlist page is requested with the invite's `jct` folded
     * into its `browse` params, and the join command its invite dialog carries is run as served.
     */
    @Test
    fun joinFallsBackToTheInvitePagesOwnJoinCommand() = runBlocking {
        val owner = managerFor("Pranav")
        val (created, inviteUrl) = sharedPlaylist(owner)
        val rawId = created.id.removePrefix("ytm:")
        val jct = CollabInviteLink.parse(inviteUrl)!!.ytmJoinToken!!

        val guest = managerFor("Saksham")
        sim.joinOnlyThroughInvitePage = true
        sim.recordedRequests.clear()
        val joined = guest.acceptInvite(inviteUrl).getOrElse { fail("invite-page join should succeed: ${it.message}", it) }

        assertFalse(joined.alreadyMember)
        assertTrue(sim.playlists.getValue(rawId).collaborators.any { it.second == "Saksham" })
        assertTrue(
            sim.recordedRequests.any { call ->
                call.path.contains("/youtubei/v1/browse?") &&
                    call.body["params"]?.jsonPrimitive?.contentOrNull == Innertube.collaborationInviteBrowseParams(jct)
            },
            "the playlist page must be requested with the invite's params",
        )
        assertTrue(
            sim.recordedRequests.none { it.path.contains("/youtubei/v1/feedback") },
            "the dialog's decline command must never be run",
        )
    }

    /** A join YouTube Music really does refuse reads as a sentence, never as the response body. */
    @Test
    fun refusedJoinIsExplainedInWordsNotRawJson() = runBlocking {
        val owner = managerFor("Pranav")
        val (created, _) = sharedPlaylist(owner)
        val rawId = created.id.removePrefix("ytm:")

        val guest = managerFor("Saksham")
        sim.wrongTokenStatus = 401
        val refused = guest.acceptInvite("https://music.youtube.com/playlist?list=$rawId&jct=replaced_by_newer_link")
        val error = refused.exceptionOrNull() ?: fail("a stale invite must not join")

        val shown = error.toCollabUserMessage()
        assertFalse(shown.contains("{") || shown.contains("youtubei") || shown.contains("Client request"), shown)
        assertTrue(shown.contains("invite", ignoreCase = true), shown)
        assertTrue(guest.playlists.value.none { it.id == created.id })
        assertTrue(sim.playlists.getValue(rawId).collaborators.none { it.second == "Saksham" })
    }

    /**
     * The roster panel is asked for with params built from the playlist id when the header
     * offers none, so the owner's "Members (1)" becomes "Members (2)" once someone joins.
     */
    @Test
    fun ownerRosterGrowsUsingPanelParamsBuiltFromThePlaylistId() = runBlocking {
        sim.omitHeaderPanelParams = true
        sim.getPanelRequiresParams = true

        val owner = managerFor("Pranav")
        val (created, inviteUrl) = sharedPlaylist(owner)
        assertEquals(1, owner.loadPlaylistDetail(created.id).getOrThrow().members.size)

        val guest = managerFor("Saksham")
        guest.acceptInvite(inviteUrl).getOrThrow()

        actAs("Pranav")
        val refreshed = owner.loadPlaylistDetail(created.id).getOrThrow()
        assertEquals(2, refreshed.memberCount)
        assertTrue(refreshed.members.any { it.displayName == "Saksham" && it.role == CollabRole.EDITOR.name })
        assertTrue(refreshed.members.any { it.displayName == "Pranav" && it.role == CollabRole.OWNER.name })
    }

    private fun seedSourcePlaylist(id: String, count: Int, owner: String = "Pranav"): List<String> {
        val pl = SimulatedYtmServer.SimPlaylist(id = id, title = "Everything I Like", description = "", privacy = "PRIVATE", ownerName = owner)
        val ids = (1..count).map { "src_%07d".format(it) }
        ids.forEachIndexed { index, vid ->
            pl.tracks.add(SimulatedYtmServer.SimTrack("src_svid_$index", vid, "Source Song ${index + 1}", "Artist", owner, null))
        }
        sim.playlists[id] = pl
        return ids
    }

    /**
     * Converting a long YouTube Music playlist. The screen that starts the conversion has only
     * the first page of it loaded; the new playlist refuses edits with 409 while it settles;
     * and one of the songs is one YouTube Music will no longer add. Every other song has to
     * arrive, in order, and the one that could not has to be named rather than lost quietly.
     */
    @Test
    fun convertingALongPlaylistMigratesEverySongAcrossPagesSettlingAndABadTrack() = runBlocking {
        val owner = managerFor("Pranav")
        val sourceIds = seedSourcePlaylist("PL_source_long", 230)
        val unavailable = sourceIds[120]
        sim.unavailableVideoIds.add(unavailable)
        sim.addVideoSettlingRemaining.set(6)

        val firstPageOnly = sourceIds.take(100).mapIndexed { index, vid ->
            Song(videoId = vid, title = "Source Song ${index + 1}", artist = "Artist", thumbnailUrl = null)
        }
        val converted = owner.convertPersonalPlaylist(
            sourcePlaylistId = "VLPL_source_long",
            sourceType = "ytm",
            title = "Everything We Like",
            songs = firstPageOnly,
        ).getOrElse { fail("conversion failed: ${it.message}", it) }

        val expected = sourceIds - unavailable
        val rawId = converted.playlist.id.removePrefix("ytm:")
        assertEquals(expected, sim.playlists.getValue(rawId).tracks.map { it.videoId }, "every available song, in the source's order")
        assertEquals(230, converted.requestedTrackCount)
        assertEquals(229, converted.migratedTrackCount)
        assertEquals(listOf("Source Song 121"), converted.skippedTitles)
        assertEquals(229, converted.playlist.tracks.size, "the detail reads every page, not just the first")
        assertEquals(229, converted.playlist.trackCount)
        assertEquals(230, sim.playlists.getValue("PL_source_long").tracks.size, "the original is left as it was")

        // Opening it later reads all pages again.
        assertEquals(229, owner.loadPlaylistDetail(converted.playlist.id).getOrThrow().tracks.size)
    }

    /** A song YouTube Music refuses among the ones sent with `playlist/create` must not sink the conversion. */
    @Test
    fun conversionSurvivesAnUnavailableSongAmongTheFirstFifty() = runBlocking {
        val owner = managerFor("Pranav")
        val sourceIds = seedSourcePlaylist("PL_source_bad_seed", 120)
        sim.unavailableVideoIds.addAll(listOf(sourceIds[10], sourceIds[77]))

        val converted = owner.convertPersonalPlaylist(
            sourcePlaylistId = "PL_source_bad_seed",
            sourceType = "ytm",
            title = "Still Converts",
            songs = emptyList(),
        ).getOrElse { fail("conversion failed: ${it.message}", it) }

        val rawId = converted.playlist.id.removePrefix("ytm:")
        assertEquals(sourceIds - setOf(sourceIds[10], sourceIds[77]), sim.playlists.getValue(rawId).tracks.map { it.videoId })
        assertEquals(118, converted.migratedTrackCount)
        assertEquals(2, converted.skippedTitles.size)
    }

    /**
     * Converting a playlist that was converted before does not make a second copy — and it
     * brings across what the copy is missing, whether the source grew or the earlier
     * conversion stopped short.
     */
    @Test
    fun convertingAgainTopsUpWhatTheCollaborativeCopyIsMissing() = runBlocking {
        val owner = managerFor("Pranav")
        seedSourcePlaylist("PL_source_growing", 60)
        val first = owner.convertPersonalPlaylist("PL_source_growing", "ytm", "Growing", songs = emptyList()).getOrThrow()
        val rawId = first.playlist.id.removePrefix("ytm:")
        assertEquals(60, first.playlist.tracks.size)

        // The copy lost its tail (as conversions before this fix could), and the source gained three.
        val copy = sim.playlists.getValue(rawId)
        repeat(20) { copy.tracks.removeAt(copy.tracks.lastIndex) }
        val source = sim.playlists.getValue("PL_source_growing")
        listOf("new_0000001", "new_0000002", "new_0000003").forEachIndexed { index, vid ->
            source.tracks.add(SimulatedYtmServer.SimTrack("new_svid_$index", vid, "New Song ${index + 1}", "Artist", "Pranav", null))
        }

        val second = owner.convertPersonalPlaylist("PL_source_growing", "ytm", "Growing", songs = emptyList()).getOrThrow()
        assertTrue(second.alreadyConverted)
        assertEquals(first.playlist.id, second.playlist.id)
        assertEquals(source.tracks.map { it.videoId }.toSet(), copy.tracks.map { it.videoId }.toSet())
        assertEquals(63, second.playlist.tracks.size)
        assertEquals(63, second.migratedTrackCount)
    }

    /** The owner can rename the playlist, and a page that still serves the old title does not undo it on screen. */
    @Test
    fun ownerRenamesPlaylistAndALaggingPageDoesNotRevertIt() = runBlocking {
        val owner = managerFor("Pranav")
        val (created, _) = sharedPlaylist(owner)
        val rawId = created.id.removePrefix("ytm:")

        val renamed = owner.updatePlaylistMetadata(created.id, title = "  Summer <3 Mix ", description = "Windows down").getOrThrow()
        assertEquals("Summer ‹3 Mix", renamed.title)
        assertEquals("Summer ‹3 Mix", sim.playlists.getValue(rawId).title)
        assertEquals("Windows down", sim.playlists.getValue(rawId).description)

        // A replica that has not caught up yet answers the next refresh.
        sim.playlists.getValue(rawId).title = "Road Trip"
        assertEquals("Summer ‹3 Mix", owner.loadPlaylistDetail(created.id).getOrThrow().title)
        assertEquals("Summer ‹3 Mix", owner.playlists.value.first { it.id == created.id }.title)
        assertTrue(owner.details.value.getValue(created.id).activities.any { it.action == "playlist.update" })
    }

    /**
     * A song added from the search panel is on screen at once and stays there while YouTube's
     * page has not caught up — it used to vanish until the playlist was closed and reopened.
     */
    @Test
    fun addedSongStaysOnScreenWhileThePageStillListsTheOldPlaylist() = runBlocking {
        val owner = managerFor("Pranav")
        val (created, _) = sharedPlaylist(owner)
        val rawId = created.id.removePrefix("ytm:")

        val after = owner.addSongs(created.id, listOf(Song(videoId = "ccccccccccc", title = "Love Me Not", artist = "Ravyn Lenae", thumbnailUrl = null))).getOrThrow()
        assertTrue(after.tracks.any { it.trackId == "ccccccccccc" })

        // The page answering the next refreshes is a replica that has not seen the add yet.
        val simPlaylist = sim.playlists.getValue(rawId)
        val row = simPlaylist.tracks.first { it.videoId == "ccccccccccc" }
        simPlaylist.tracks.remove(row)
        repeat(2) {
            val refreshed = owner.loadPlaylistDetail(created.id, allPages = false).getOrThrow()
            assertTrue(refreshed.tracks.any { it.trackId == "ccccccccccc" && it.title == "Love Me Not" }, "the added song must not drop off the screen")
            assertEquals(2, refreshed.trackCount)
        }

        // Once the page lists it, there is exactly one of it.
        simPlaylist.tracks.add(row)
        val settled = owner.loadPlaylistDetail(created.id).getOrThrow()
        assertEquals(1, settled.tracks.count { it.trackId == "ccccccccccc" })

        // A refused add is taken back off the screen.
        sim.unavailableVideoIds.add("ddddddddddd")
        val refused = owner.addSongs(created.id, listOf(Song(videoId = "ddddddddddd", title = "Gone", artist = "Nobody", thumbnailUrl = null)))
        assertTrue(refused.isFailure)
        assertTrue(owner.details.value.getValue(created.id).tracks.none { it.trackId == "ddddddddddd" })
        assertTrue(owner.loadPlaylistDetail(created.id).getOrThrow().tracks.none { it.trackId == "ddddddddddd" })
    }

    /**
     * Creating a playlist does not wait out YouTube Music's settling for the invite link — that
     * is fetched in the background — and asking for the link again reuses the one already known.
     */
    @Test
    fun creationDoesNotWaitForTheInviteLinkAndTheLinkIsReused() = runBlocking {
        val owner = managerFor("Pranav")
        sim.collabFailuresRemaining.set(3) // still settling when the playlist is created
        val created = owner.createPlaylist(title = "Quick").getOrThrow()
        val inviteCalls = {
            sim.recordedRequests.count { call ->
                call.path.contains("/browse/edit_playlist") &&
                    call.body.toString().contains("ACTION_CREATE_COLLABORATION_INVITE_LINK")
            }
        }
        // Usable straight away, link or no link.
        assertEquals("Quick", created.title)

        val first = owner.createInvite(created.id).getOrThrow()
        assertTrue(first.inviteUrl.contains("jct="))
        kotlinx.coroutines.delay(300L) // let the background link fetch finish or stand down
        val callsAfterFirst = inviteCalls()
        val second = owner.createInvite(created.id).getOrThrow()
        assertEquals(first.inviteUrl, second.inviteUrl)
        assertEquals(callsAfterFirst, inviteCalls(), "a known link is handed out without another request")
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

    /** The playlist header's collaborate button carries no `get_panel` params. */
    @Volatile var omitHeaderPanelParams: Boolean = false

    /** `get_panel` answers only to params, never to a bare `playlistId`. */
    @Volatile var getPanelRequiresParams: Boolean = false
    @Volatile var customJctSuffix: String? = null
    @Volatile var activeActorName: String = "Pranav"
    @Volatile var activeActorAvatar: String = "https://yt3.ggpht.com/pranav"

    val channelCreated = java.util.concurrent.atomic.AtomicBoolean(false)
    val collabFailuresRemaining = AtomicInteger(0)
    val browseFailuresRemaining = AtomicInteger(0)
    val joinFailuresRemaining = AtomicInteger(0)
    val addVideoAclFailuresRemaining = AtomicInteger(0)

    /** 401s served to a valid join while the freshly minted token "propagates". */
    val joinUnauthorizedRemaining = AtomicInteger(0)

    /** What a join with a token that is not the playlist's is answered with. */
    @Volatile var wrongTokenStatus: Int = 403

    /** Only the join command served on the invite page is honoured; a hand-built one gets 401. */
    @Volatile var joinOnlyThroughInvitePage: Boolean = false

    /** `get_add_to_playlist` lists nothing unless asked the way the web client asks (`videoIds`). */
    @Volatile var addToPlaylistRequiresVideoIds: Boolean = true

    /** 409s served to `ACTION_ADD_VIDEO` while a just-created playlist "settles". */
    val addVideoSettlingRemaining = AtomicInteger(0)

    /** Tracks YouTube Music will not add; a batch carrying one is refused whole. */
    val unavailableVideoIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Rows per playlist page, as YouTube Music pages them. */
    @Volatile var browsePageSize: Int = 100

    private fun unauthorizedToEdit(): MockResponse = MockResponse().setResponseCode(401).setBody(
        """{"error":{"code":401,"message":"You are not authorized to edit this playlist.","errors":[{"message":"You are not authorized to edit this playlist.","domain":"global","reason":"unauthorized"}],"status":"UNAUTHENTICATED"}}""",
    )

    private fun isMember(pl: SimPlaylist): Boolean =
        pl.ownerName.equals(activeActorName, ignoreCase = true) ||
            pl.collaborators.any { it.second.equals(activeActorName, ignoreCase = true) }
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
            path.contains("/youtubei/v1/playlist/get_add_to_playlist") -> handleGetAddToPlaylist(bodyObj)
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
            if (vid in unavailableVideoIds) {
                return MockResponse().setResponseCode(400)
                    .setBody("""{"error":{"code":400,"message":"Request contains an invalid argument.","status":"INVALID_ARGUMENT"}}""")
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
                    // Real YouTube Music: an account that is already in is told it may not edit.
                    if (isMember(pl)) return unauthorizedToEdit()
                    val token = act["joinCollaborationToken"]?.jsonPrimitive?.contentOrNull
                    if (token != pl.jct) {
                        if (wrongTokenStatus == 401) return unauthorizedToEdit()
                        return MockResponse().setResponseCode(wrongTokenStatus)
                            .setBody("""{"error":{"code":$wrongTokenStatus,"message":"Sorry, something went wrong.","status":"PERMISSION_DENIED"}}""")
                    }
                    if (joinUnauthorizedRemaining.getAndDecrement() > 0) return unauthorizedToEdit()
                    val servedByInvitePage = body["params"]?.jsonPrimitive?.contentOrNull == INVITE_PAGE_GRANT
                    if (joinOnlyThroughInvitePage && !servedByInvitePage) return unauthorizedToEdit()
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
                    if (actEl === actions.first() && addVideoSettlingRemaining.getAndDecrement() > 0) {
                        return MockResponse().setResponseCode(409)
                            .setBody("""{"error":{"code":409,"message":"The operation was aborted.","status":"ABORTED"}}""")
                    }
                    val batchIds = actions.mapNotNull { it.jsonObject["addedVideoId"]?.jsonPrimitive?.contentOrNull }
                    if (batchIds.any { it in unavailableVideoIds }) {
                        return MockResponse().setResponseCode(400)
                            .setBody("""{"error":{"code":400,"message":"Request contains an invalid argument.","status":"INVALID_ARGUMENT"}}""")
                    }
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
        val continuation = body["continuation"]?.jsonPrimitive?.contentOrNull
        if (continuation != null && continuation.startsWith("cont|")) {
            val (_, contPid, contOffset) = continuation.split("|")
            val contPl = playlists[contPid] ?: return okJson(buildJsonObject {})
            return okJson(
                buildJsonObject {
                    putJsonArray("onResponseReceivedActions") {
                        add(
                            buildJsonObject {
                                putJsonObject("appendContinuationItemsAction") {
                                    put("continuationItems", trackRowsPage(contPl, contOffset.toInt()))
                                }
                            },
                        )
                    }
                },
            )
        }
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
                                            if (!omitHeaderPanelParams) {
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
        }

        val trackItems = trackRowsPage(pl, 0)

        val inviteParams = body["params"]?.jsonPrimitive?.contentOrNull
        val servesInviteDialog = inviteParams != null && pl.jct != null && !isMember(pl) &&
            inviteParams == Innertube.collaborationInviteBrowseParams(pl.jct!!)
        val root = buildJsonObject {
            if (servesInviteDialog) {
                putJsonArray("actions") {
                    add(
                        buildJsonObject {
                            putJsonObject("openPopupAction") {
                                putJsonObject("popup") {
                                    putJsonObject("dialogViewModel") {
                                        putJsonObject("content") {
                                            putJsonObject("collaborativePlaylistInviteMessageViewModel") {
                                                putJsonObject("inviteMessage") { put("content", "${pl.ownerName} invited you to collaborate") }
                                                putJsonObject("accountName") { put("content", activeActorName) }
                                            }
                                        }
                                        putJsonArray("buttons") {
                                            add(
                                                buildJsonObject {
                                                    putJsonObject("buttonViewModel") {
                                                        put("title", "Cancel")
                                                        putJsonObject("onTap") {
                                                            putJsonObject("innertubeCommand") {
                                                                putJsonObject("commandMetadata") {
                                                                    putJsonObject("webCommandMetadata") {
                                                                        put("sendPost", true)
                                                                        put("apiUrl", "/youtubei/v1/feedback")
                                                                    }
                                                                }
                                                                putJsonObject("declinePlaylistCollaborationCommand") { put("playlistId", pl.id) }
                                                            }
                                                        }
                                                    }
                                                },
                                            )
                                            add(
                                                buildJsonObject {
                                                    putJsonObject("buttonViewModel") {
                                                        put("title", "Join")
                                                        putJsonObject("onTap") {
                                                            putJsonObject("innertubeCommand") {
                                                                putJsonObject("commandMetadata") {
                                                                    putJsonObject("webCommandMetadata") {
                                                                        put("sendPost", true)
                                                                        put("apiUrl", "/youtubei/v1/browse/edit_playlist")
                                                                    }
                                                                }
                                                                putJsonObject("playlistEditEndpoint") {
                                                                    put("playlistId", pl.id)
                                                                    put("params", INVITE_PAGE_GRANT)
                                                                    putJsonArray("actions") {
                                                                        add(
                                                                            buildJsonObject {
                                                                                put("action", "ACTION_JOIN_COLLABORATION")
                                                                                put("joinCollaborationToken", pl.jct)
                                                                            },
                                                                        )
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
                        },
                    )
                }
            }
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

    /** One page of a playlist's rows, with the token for the next when there is one. */
    private fun trackRowsPage(pl: SimPlaylist, offset: Int): JsonArray {
        val page = pl.tracks.drop(offset).take(browsePageSize)
        val nextOffset = offset + page.size
        return buildJsonArray {
            page.forEach { t ->
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
            if (nextOffset < pl.tracks.size) {
                add(
                    buildJsonObject {
                        putJsonObject("continuationItemRenderer") {
                            putJsonObject("continuationEndpoint") {
                                putJsonObject("continuationCommand") { put("token", "cont|${pl.id}|$nextOffset") }
                            }
                        }
                    },
                )
            }
        }
    }

    private fun handleGetAddToPlaylist(body: JsonObject): MockResponse {
        val askedLikeWebClient = (body["videoIds"] as? JsonArray).orEmpty().isNotEmpty()
        val writable = playlists.values.filter { pl ->
            (askedLikeWebClient || !addToPlaylistRequiresVideoIds) && (
                pl.ownerName.equals(activeActorName, ignoreCase = true) ||
                    (!pl.closedToContributions && pl.collaborators.any { it.second.equals(activeActorName, ignoreCase = true) })
                )
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
        val pid = playlists.keys.firstOrNull { Innertube.collaboratorsPanelParams(it) == params }
            ?: params.takeIf { it.startsWith("panel_") }?.removePrefix("panel_")
            ?: (if (getPanelRequiresParams) "" else body["playlistId"]?.jsonPrimitive?.contentOrNull.orEmpty())
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

    private companion object {
        const val INVITE_PAGE_GRANT = "invite_page_grant"
    }

    private fun okJson(obj: JsonObject): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(obj.toString())
}
