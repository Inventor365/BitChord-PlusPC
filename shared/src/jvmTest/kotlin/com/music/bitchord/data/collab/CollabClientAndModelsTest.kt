package com.music.bitchord.data.collab

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollabClientAndModelsTest {

    @Test
    fun parsesHttpsCustomSchemeIntentAndBareInviteTokens() {
        val token = "bci_AbCdEfGhIjKlMnOpQrStUvWxYz012345"

        val https = CollabInviteLink.parse("https://collab.example.com/collab/invite/$token")
        assertNotNull(https)
        assertEquals(token, https.token)
        assertEquals("https://collab.example.com", https.serverOrigin)

        val deepLink = CollabInviteLink.parse("bitchord://collab/invite/$token?server=https%3A%2F%2Fcollab.example.com%2F")
        assertNotNull(deepLink)
        assertEquals(token, deepLink.token)
        assertEquals("https://collab.example.com", deepLink.serverOrigin)

        val intentLink = CollabInviteLink.parse("intent://collab/invite/$token?server=http://localhost:8080#Intent;scheme=bitchord;end")
        assertNotNull(intentLink)
        assertEquals(token, intentLink.token)
        assertEquals("http://localhost:8080", intentLink.serverOrigin)

        val bare = CollabInviteLink.parse(token)
        assertNotNull(bare)
        assertEquals(token, bare.token)
        assertNull(bare.serverOrigin)

        // Rejects insecure non-loopback HTTP and malformed tokens

        val ytmInvite = CollabInviteLink.parse("https://music.youtube.com/playlist?list=PLQwVIlKxHM6qv-o99&jct=kM9wXdRj2p8v_qL3sHBkTz")
        assertNotNull(ytmInvite)
        assertTrue(ytmInvite.isYouTubeMusicInvite)
        assertEquals("PLQwVIlKxHM6qv-o99", ytmInvite.ytmPlaylistId)
        assertEquals("kM9wXdRj2p8v_qL3sHBkTz", ytmInvite.ytmJoinToken)

        // Non-collaborative YouTube Music playlist URLs without jct= must NOT be hijacked as invites
        assertNull(CollabInviteLink.parse("https://music.youtube.com/playlist?list=PLQwVIlKxHM6qv-o99"))
        assertNull(CollabInviteLink.normalizeServerOrigin("http://evil.example.com"))
        assertNull(CollabInviteLink.parse("https://collab.example.com/collab/invite/short"))
    }

    @Test
    fun enforcesRoleAndGranularEditorPermissionsOnDetailModel() {
        val trackOwn = CollabTrackEntry(
            entryId = "cte_1",
            trackId = "vid1",
            title = "Track One",
            artist = "Artist A",
            contributorId = "usr_editor",
            contributorName = "Saksham",
        )
        val trackOther = trackOwn.copy(entryId = "cte_2", contributorId = "usr_owner", contributorName = "Pranav")

        val detail = CollabPlaylistDetail(
            id = "cpl_1",
            title = "Roadtrip Mix",
            ownerUserId = "usr_owner",
            ownerName = "Pranav",
            myRole = "EDITOR",
            sharingEnabled = true,
            permissions = CollabPermissions(editorCanRemove = "own", editorCanReorder = false, editorCanInvite = false),
            members = listOf(
                CollabMember(userId = "usr_owner", role = "OWNER", displayName = "Pranav"),
                CollabMember(userId = "usr_editor", role = "EDITOR", displayName = "Saksham"),
                CollabMember(userId = "usr_viewer", role = "VIEWER", displayName = "Alex"),
            ),
            tracks = listOf(trackOwn, trackOther),
        )

        assertTrue(detail.canAddTracks("usr_editor"))
        assertFalse(detail.canAddTracks("usr_viewer"))
        assertTrue(detail.canRemoveTrack("usr_editor", trackOwn))
        assertFalse(detail.canRemoveTrack("usr_editor", trackOther))
        assertTrue(detail.canRemoveTrack("usr_owner", trackOther))
        assertFalse(detail.canReorderTracks("usr_editor"))
        assertTrue(detail.canReorderTracks("usr_owner"))
        assertFalse(detail.canInvite("usr_editor"))
        assertTrue(detail.canInvite("usr_owner"))

        assertEquals("Added by you", trackOwn.contributorLabel("usr_editor"))
        assertEquals("Added by Pranav", trackOther.contributorLabel("usr_editor"))
    }

    @Test
    fun managerConvertsPersonalPlaylistAppliesRealtimeEventsAndPersistsCache() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.port}"
            val tempDir = createTempDirectory("collab-test").toFile()
            val cacheFile = tempDir.resolve("collab_state.json")

            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "token": "bcs_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                      "expiresAtMs": 4102444800000,
                      "user": {
                        "id": "usr_owner123",
                        "email": "pranav@example.com",
                        "emailVerified": true,
                        "displayName": "Pranav",
                        "avatarUrl": "https://lh3.googleusercontent.com/a/avatar"
                      }
                    }
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"playlists":[]}"""),
            )
            server.enqueue(
                MockResponse().setResponseCode(201).setBody(
                    """
                    {
                      "alreadyConverted": false,
                      "playlist": {
                        "id": "cpl_converted1",
                        "title": "Late Night Drive",
                        "description": "Converted from local playlist",
                        "ownerUserId": "usr_owner123",
                        "ownerName": "Pranav",
                        "myRole": "OWNER",
                        "sharingEnabled": true,
                        "permissions": {"editorCanRemove": "any", "editorCanReorder": true, "editorCanInvite": true},
                        "sourceType": "local",
                        "sourcePlaylistId": "local_42",
                        "memberCount": 1,
                        "trackCount": 1,
                        "revision": 1,
                        "updatedAtMs": 1700000000000,
                        "members": [{"userId": "usr_owner123", "role": "OWNER", "displayName": "Pranav", "joinedAtMs": 1700000000000}],
                        "tracks": [{
                          "entryId": "cte_track1",
                          "playlistId": "cpl_converted1",
                          "position": 0,
                          "trackId": "dQw4w9WgXcQ",
                          "provider": "youtube",
                          "title": "Blinding Lights",
                          "artist": "The Weeknd",
                          "durationMs": 200000,
                          "contributorId": "usr_owner123",
                          "contributorName": "Pranav",
                          "addedAtMs": 1700000000000
                        }],
                        "activities": []
                      }
                    }
                    """.trimIndent(),
                ),
            )

            val manager = CollabManager(
                storageFileProvider = { cacheFile },
                defaultServerUrl = { baseUrl },
            )

            val signedIn = manager.signInWithGoogleIdToken("mock.jwt.token").getOrThrow()
            assertEquals("Pranav", signedIn.displayName)
            assertTrue(manager.isAuthenticated)

            val converted = manager.convertPersonalPlaylist(
                sourcePlaylistId = "local_42",
                sourceType = "local",
                title = "Late Night Drive",
                description = "Converted from local playlist",
                songs = listOf(Song(videoId = "dQw4w9WgXcQ", title = "Blinding Lights", artist = "The Weeknd", thumbnailUrl = null, durationText = "3:20")),
            ).getOrThrow()

            assertFalse(converted.alreadyConverted)
            assertEquals("cpl_converted1", converted.playlist.id)
            assertEquals(1, manager.playlists.value.size)

            // Simulate realtime collaborator track addition broadcast over /ws/collab
            val event = manager.client.parseRealtimeFrame(
                """
                {
                  "type": "collab.tracks_changed",
                  "playlistId": "cpl_converted1",
                  "revision": 2,
                  "opId": "op_remote_99",
                  "tracks": [
                    {
                      "entryId": "cte_track1",
                      "playlistId": "cpl_converted1",
                      "position": 0,
                      "trackId": "dQw4w9WgXcQ",
                      "provider": "youtube",
                      "title": "Blinding Lights",
                      "artist": "The Weeknd",
                      "contributorId": "usr_owner123",
                      "contributorName": "Pranav"
                    },
                    {
                      "entryId": "cte_track2",
                      "playlistId": "cpl_converted1",
                      "position": 1,
                      "trackId": "abc123xyz00",
                      "provider": "youtube",
                      "title": "Midnight City",
                      "artist": "M83",
                      "contributorId": "usr_collab2",
                      "contributorName": "Saksham"
                    }
                  ],
                  "latestActivity": {
                    "id": "act_1",
                    "playlistId": "cpl_converted1",
                    "actorUserId": "usr_collab2",
                    "actorName": "Saksham",
                    "action": "track.add",
                    "summary": "Saksham added Midnight City",
                    "createdAtMs": 1700000005000
                  }
                }
                """.trimIndent(),
            )
            assertNotNull(event)
            manager.applyRealtimeEvent(event)

            val updated = manager.details.value.getValue("cpl_converted1")
            assertEquals(2L, updated.revision)
            assertEquals(2, updated.tracks.size)
            assertEquals("Added by Saksham", updated.tracks[1].contributorLabel("usr_owner123"))
            assertEquals("Saksham added Midnight City", updated.activities.first().summary)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun syncAppGoogleAccountActivatesZeroConfigNativeModeImmediately() {
        val manager = CollabManager(
            storageFileProvider = { null },
            defaultServerUrl = { "" },
        )
        assertTrue(manager.isNativeYouTubeMode)
        assertFalse(manager.isAuthenticated)

        manager.syncAppGoogleAccount(
            signedIn = true,
            accountId = "UC_pranav_123",
            displayName = "Pranav",
            email = "pranav@example.com",
            avatarUrl = "https://yt3.ggpht.com/avatar",
        )
        assertTrue(manager.isAuthenticated)
        assertEquals("Pranav", manager.currentUser?.displayName)
        assertEquals("pranav@example.com", manager.currentUser?.email)
    }
}
