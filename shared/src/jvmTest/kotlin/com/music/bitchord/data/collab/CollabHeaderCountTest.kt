package com.music.bitchord.data.collab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollabHeaderCountTest {
    private val manager = CollabManager(storageFileProvider = { null })

    private fun header(vararg runs: String): JsonObject = Json.parseToJsonElement(
        """{"header":{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Road Trip"}]},""" +
            """"secondSubtitle":{"runs":[${runs.joinToString(",") { """{"text":"$it"}""" }}]}}}}""",
    ) as JsonObject

    @Test
    fun readsTheLeadingNumber() {
        assertEquals(150, manager.headerTrackCount(header("150 songs", " • ", "9+ hours")))
        assertEquals(1234, manager.headerTrackCount(header("1,234 songs")))
        assertEquals(87, manager.headerTrackCount(header("87 canciones")))
        assertEquals(2048, manager.headerTrackCount(header("2 048 titres")))
    }

    @Test
    fun noCountWhenTheHeaderHasNone() {
        assertNull(manager.headerTrackCount(Json.parseToJsonElement("""{"header":{}}""") as JsonObject))
        assertNull(manager.headerTrackCount(header("Playlist")))
    }
}
