package com.music.bitchord.data.collab

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The parts of YouTube Music's collaboration protocol that can be checked without a signed-in
 * account, against values the real service produced.
 */
class CollabInnertubeProtocolTest {

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    /**
     * Expected values are what music.youtube.com's own server put in the playlist page's
     * `browse` request when the page was opened as `/playlist?list=…&jct=<token>`.
     */
    @Test
    fun inviteTokenIsFoldedIntoBrowseParamsExactlyAsTheServerDoes() {
        assertEquals(
            "ugIWQWJDZEVmR2hJaktsTW5PcFFyU3RVdsIFAwjYBA%3D%3D",
            Innertube.collaborationInviteBrowseParams("AbCdEfGhIjKlMnOpQrStUv"),
        )
        assertEquals(
            "ugIdTDFfeUFLcUZrelRhYmMtWFlaXzAxMjM0NTY3ODnCBQMI2AQ%3D",
            Innertube.collaborationInviteBrowseParams("L1_yAKqFkzTabc-XYZ_0123456789"),
        )
        // URL-safe alphabet: a token whose bytes land on index 62 comes out as '-', not '+'.
        assertEquals(
            "ugIXYWJ-YWJ-YWJ-YWJ-YWJ-YWJ-YWJ-YS7CBQMI2AQ%3D",
            Innertube.collaborationInviteBrowseParams("ab~ab~ab~ab~ab~ab~ab~a."),
        )
    }

    /** The header below is a collaborative playlist's, as YouTube Music served it (trimmed). */
    @Test
    fun collaboratorsPanelParamsMatchTheOnesARealHeaderCarries() {
        val page = json(
            """
            {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
              {"musicResponsiveHeaderRenderer":{
                "title":{"runs":[{"text":"Shared Mix"}]},
                "facepile":{"avatarStackViewModel":{
                  "avatars":[
                    {"avatarViewModel":{"image":{"sources":[{"url":"https://yt3.ggpht.com/a=s48"}]},"avatarImageSize":"AVATAR_SIZE_XS"}},
                    {"avatarViewModel":{"image":{"sources":[{"url":"https://yt3.ggpht.com/b=s48"}]},"avatarImageSize":"AVATAR_SIZE_XS"}}
                  ],
                  "rendererContext":{
                    "accessibilityContext":{"label":"YTMUser"},
                    "commandContext":{"onTap":{"innertubeCommand":{
                      "clickTrackingParams":"CAQQ",
                      "showEngagementPanelEndpoint":{
                        "identifier":{"tag":"PAplaylist_collaborate"},
                        "globalConfiguration":{
                          "initialState":{"engagementPanelSectionListRenderer":{"content":{"contentLoadingRenderer":{"useSpinner":true}},"targetId":"PAplaylist_collaborate"}},
                          "params":"0ggkCiJQTHh5VGFEejhmNVBCYy04a0UzNmd2Qi1lZmxoT0RHMmR3"
                        },
                        "engagementPanelPresentationConfigs":{"engagementPanelPopupPresentationConfig":{"popupType":"PANEL_POPUP_TYPE_DIALOG"}}
                      }}}}
                  }
                }}
              }}
            ]}}}}]}}}
            """.trimIndent(),
        )
        val rawId = "PLxyTaDz8f5PBc-8kE36gvB-eflhODG2dw"
        val meta = assertNotNull(InnertubeParser.parseYtmCollabHeader(page, rawId))
        assertTrue(meta.isCollaborative)
        assertEquals(2, meta.facepileAvatars.size)
        assertFalse(meta.owned)

        // What the header carries is exactly what can be built from the playlist id alone.
        assertEquals(meta.panelParams, Innertube.collaboratorsPanelParams(rawId))
        assertEquals(meta.panelParams, InnertubeParser.parseCollaboratorsPanelParams(page))
        assertEquals(meta.panelParams, Innertube.collaboratorsPanelParams("VL$rawId"))
    }

    @Test
    fun rosterIsReadFromThePanelsCollaboratorRows() {
        val panel = json(
            """
            {"content":{"playlistCollaborationViewModel":{
              "collaboratePlaylistCollaborationSetting":{"switchListItemViewModel":{"title":{"content":"Collaborate"}}},
              "playlistCollaborators":[
                {"contentListItemViewModel":{"title":{"content":"Pranav"},"avatar":{"avatarViewModel":{"image":{"sources":[{"url":"https://yt3.ggpht.com/p"}]}}}}},
                {"contentListItemViewModel":{"title":{"content":"Saksham"},"avatar":{"avatarViewModel":{"image":{"sources":[{"url":"https://yt3.ggpht.com/s"}]}}},
                  "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"UCsaksham"}}}}}}}
              ]}}}
            """.trimIndent(),
        )
        val roster = InnertubeParser.parseCollaboratorsPanel(panel, strict = true)
        assertEquals(listOf("Pranav", "Saksham"), roster.map { it.second })
        assertEquals("UCsaksham", roster[1].first)

        // A page that merely has list rows on it is not a roster.
        val unrelated = json("""{"contents":[{"contentListItemViewModel":{"title":{"content":"Some setting"}}}]}""")
        assertTrue(InnertubeParser.parseCollaboratorsPanel(unrelated, strict = true).isEmpty())
    }

    @Test
    fun joinCommandIsLiftedFromTheInviteDialogAndNothingElseIs() {
        val page = json(
            """
            {"actions":[{"openPopupAction":{"popup":{"dialogViewModel":{
              "content":{"collaborativePlaylistInviteMessageViewModel":{"inviteMessage":{"content":"Pranav invited you"}}},
              "buttons":[
                {"buttonViewModel":{"title":"Cancel","onTap":{"innertubeCommand":{
                  "commandMetadata":{"webCommandMetadata":{"sendPost":true,"apiUrl":"/youtubei/v1/feedback"}},
                  "declinePlaylistCollaborationCommand":{"playlistId":"PLabc"}}}}},
                {"buttonViewModel":{"title":"Join","onTap":{"innertubeCommand":{
                  "commandMetadata":{"webCommandMetadata":{"sendPost":true,"apiUrl":"/youtubei/v1/browse/edit_playlist"}},
                  "playlistEditEndpoint":{"playlistId":"PLabc","params":"served","actions":[{"action":"ACTION_JOIN_COLLABORATION","joinCollaborationToken":"tok"}]}}}}},
                {"buttonViewModel":{"title":"Join","onTap":{"innertubeCommand":{
                  "commandMetadata":{"webCommandMetadata":{"sendPost":true,"apiUrl":"/youtubei/v1/playlist/join_collaboration"}},
                  "joinPlaylistCollaborationCommand":{"playlistId":"PLabc","token":"tok"}}}}}
              ]}}}}]}
            """.trimIndent(),
        )
        val commands = InnertubeParser.parseCollaborationJoinCommands(page, "VLPLabc")
        assertEquals(listOf("browse/edit_playlist", "playlist/join_collaboration"), commands.map { it.endpoint })
        assertEquals("served", commands[0].body["params"].toString().trim('"'))

        // A join for some other playlist is not this playlist's join.
        assertTrue(InnertubeParser.parseCollaborationJoinCommands(page, "PLother").none { it.endpoint == "browse/edit_playlist" })
    }

    @Test
    fun editCommandsProveWriteAccessButAJoinOfferDoesNot() {
        val member = json(
            """{"menu":{"items":[{"menuServiceItemRenderer":{"serviceEndpoint":{"playlistEditEndpoint":{
                 "playlistId":"PLabc","actions":[{"action":"ACTION_REMOVE_VIDEO","setVideoId":"s1","removedVideoId":"v1"}]}}}}]}}""",
        )
        val invited = json(
            """{"button":{"command":{"playlistEditEndpoint":{
                 "playlistId":"PLabc","actions":[{"action":"ACTION_JOIN_COLLABORATION","joinCollaborationToken":"tok"}]}}}}""",
        )
        assertTrue(InnertubeParser.hasPlaylistEditAffordance(member, "PLabc"))
        assertFalse(InnertubeParser.hasPlaylistEditAffordance(member, "PLsomethingElse"))
        assertFalse(InnertubeParser.hasPlaylistEditAffordance(invited, "PLabc"))

        // Nor does being offered a join put the playlist among the ones the account can add to.
        assertTrue(InnertubeParser.parseAddToPlaylistIds(invited).isEmpty())
        assertEquals(setOf("PLabc"), InnertubeParser.parseAddToPlaylistIds(member))
    }

    /** The exact text of the v1.10.3 screenshot, as the HTTP client's exception carried it. */
    @Test
    fun rawHttpFailureIsReducedToTheSentenceWrittenForAPerson() {
        val raw = """
            Client request(POST https://music.youtube.com/youtubei/v1/browse/edit_playlist?prettyPrint=false&hl=en) invalid: 401 . Text: "{
              "error": {
                "code": 401,
                "message": "You are not authorized to edit this playlist.",
                "errors": [
                  {
                    "message": "You are not authorized to edit this playlist.",
                    "domain": "global",
                    "reason": "unauthorized"
                  }
                ],
                "status": "UNAUTHENTICATED"
              }
            }
            "
        """.trimIndent()
        assertEquals(
            "YouTube Music: You are not authorized to edit this playlist. (HTTP 401)",
            collabUserMessage(raw),
        )
        assertEquals(
            "YouTube Music is having trouble right now (HTTP 503). Try again in a moment.",
            collabUserMessage("""Server error(POST https://music.youtube.com/youtubei/v1/browse: 503 Service Unavailable. Text: """""),
        )
        // Messages already written for people pass through untouched.
        assertEquals("Paste a YouTube Music collaboration link.", collabUserMessage("Paste a YouTube Music collaboration link."))
        assertEquals("Couldn't save.", collabUserMessage(null, fallback = "Couldn't save."))
        assertEquals("Couldn't save.", IllegalStateException().toCollabUserMessage("Couldn't save."))
    }
}
