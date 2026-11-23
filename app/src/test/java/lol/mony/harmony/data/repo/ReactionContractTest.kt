package lol.mony.harmony.data.repo

import kotlinx.coroutines.runBlocking
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a reaction is, as the instance stores it: the column, the filter, and the sentence.
 *
 * The instance has three rules the client has to get right, and each of them was a way the old
 * code could silently do the wrong thing:
 *
 *  1. A custom emoji is `emoji_id` and a unicode one is `custom_emoji_content`
 *     (`check_reaction_emoji`). Writing a shortcode into the content column stores a different
 *     reaction -- one that draws as text and that the reference client does not group with the
 *     image.
 *  2. Removing a reaction has to match the column it was written to. Filtering a custom
 *     reaction by its content matched nothing, so the pill could not be taken back.
 *  3. The database refuses the 21st reaction and says so in its own words; the client has to
 *     turn that into something a person can read.
 *
 * The library query is the fourth: it is what makes the server's own emoji reachable at all,
 * and it must not offer a removed emoji or one from a server the room is not in.
 */
class ReactionContractTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: Endpoint
    private lateinit var chat: ChatRepository

    /** Every request the client made, in order, so a test can assert what was *not* sent. */
    private val requests = mutableListOf<RecordedRequest>()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        endpoint = Endpoint(server.url("/").toString().trimEnd('/'), "test-anon")
        chat = ChatRepository(RestApi(OkHttpClient()))
    }

    @After fun tearDown() { server.shutdown() }

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body)
            .setHeader("Content-Type", "application/json"))
    }

    private fun take(): RecordedRequest {
        val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        requests += request
        return request
    }

    private fun token(): String {
        val claims = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"auth-1"}""".toByteArray())
        return "e30." + claims + ".sig"
    }

    private fun reactionRow(id: String, emojiId: String?, content: String?) =
        """[{"id":""" + jsonString(id) + ""","message_id":"m1","user_id":"p1",""" +
            (emojiId?.let { """"emoji_id":""" + jsonString(it) + """,""" } ?: "") +
            (content?.let { """"custom_emoji_content":""" + jsonString(it) + """,""" } ?: "") +
            """"created_at":"2026-01-01T00:00:00+00:00"}]"""

    /** A JSON string literal, so a glyph survives the row it is written into. */
    private fun jsonString(value: String): String = buildString {
        append('"')
        for (char in value) when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> append(char)
        }
        append('"')
    }

    /** A unicode reaction is stored in the content column, which is the only column it has. */
    @Test fun aUnicodeReactionIsStoredAsItsContent() = runBlocking {
        enqueue(201, reactionRow("r1", null, "\uD83D\uDC4D"))
        chat.addReaction(endpoint, token(), "m1", "p1", null, "\uD83D\uDC4D").getOrThrow()
        val request = take()
        assertEquals("POST", request.method)
        assertEquals("/rest/v1/reactions", request.path!!.substringBefore('?'))
        val body = request.body.readUtf8()
        assertTrue("the glyph is the content: $body", body.contains("\"custom_emoji_content\":\"\uD83D\uDC4D\""))
        assertTrue("and there is no emoji_id: $body", !body.contains("emoji_id"))
    }

    /**
     * A custom emoji is stored as its `emoji_id`, which is what the instance's trigger checks.
     *
     * The row id is what makes the pill an image for a client that can resolve it; the content
     * column stays empty, which is the shape the reference client writes and the trigger accepts.
     */
    @Test fun aCustomReactionIsStoredAsItsEmojiId() = runBlocking {
        enqueue(201, reactionRow("r1", "e-cat", null))
        chat.addReaction(endpoint, token(), "m1", "p1", "e-cat", null).getOrThrow()
        val body = take().body.readUtf8()
        assertTrue("the row is named by id: $body", body.contains("\"emoji_id\":\"e-cat\""))
        assertTrue("and not by content: $body", !body.contains("custom_emoji_content"))
    }

    /** Removing a custom reaction filters by `emoji_id`, so the row it wrote is the row it finds. */
    @Test fun removingACustomReactionFiltersById() = runBlocking {
        enqueue(204, "")
        chat.removeReaction(endpoint, token(), "m1", "p1", "e-cat", null).getOrThrow()
        val request = take()
        assertEquals("DELETE", request.method)
        val query = request.path!!
        assertTrue("filters by the emoji's id: $query", query.contains("emoji_id=eq.e-cat"))
        assertTrue("and never by its content: $query", !query.contains("custom_emoji_content"))
        assertTrue("for this message: $query", query.contains("message_id=eq.m1"))
        assertTrue("and this account: $query", query.contains("user_id=eq.p1"))
    }

    /** Removing a unicode reaction filters by its content, which is where it was written. */
    @Test fun removingAUnicodeReactionFiltersByContent() = runBlocking {
        enqueue(204, "")
        chat.removeReaction(endpoint, token(), "m1", "p1", null, "\uD83D\uDC4D").getOrThrow()
        val query = take().path!!
        assertTrue("filters by the glyph: $query", query.contains("custom_emoji_content=eq."))
        assertTrue("and not by an id: $query", !query.contains("emoji_id="))
    }

    /** The 20-reaction limit arrives as a database exception, and leaves as a sentence. */
    @Test fun theReactionLimitIsASentence() = runBlocking {
        enqueue(400, """{"code":"P0001","message":"Maximum emoji reactions per message reached (20)"}""")
        val error = runCatching { chat.addReaction(endpoint, token(), "m1", "p1", null, "\uD83D\uDC4D").getOrThrow() }
            .exceptionOrNull()
        assertTrue("a person can read it: ${error?.message}", error is IllegalArgumentException)
        assertTrue("it names the limit: ${error?.message}", error!!.message!!.contains("20"))
    }

    /** A removed emoji is refused by the trigger; its own words mean nothing to a person. */
    @Test fun aRemovedEmojiIsRefusedInWords() = runBlocking {
        enqueue(400, """{"code":"22023","message":"REACTION_EMOJI: that emoji is not available"}""")
        val error = runCatching { chat.addReaction(endpoint, token(), "m1", "p1", "e-cat", null).getOrThrow() }
            .exceptionOrNull()
        assertEquals("That emoji is no longer available.", error?.message)
    }

    /** A server's emoji this account may not use here says which rule it broke. */
    @Test fun anExternalEmojiIsRefusedInWords() = runBlocking {
        enqueue(403, """{"code":"42501","message":"Missing permission: USE_EXTERNAL_EMOJIS"}""")
        val error = runCatching { chat.addReaction(endpoint, token(), "m1", "p1", "e-cat", null).getOrThrow() }
            .exceptionOrNull()
        assertEquals("That emoji belongs to another server, and you cannot use it here.", error?.message)
    }

    /**
     * The library query asks for the room's server, the instance's imports and this account's own.
     *
     * Those are the three kinds of row the instance's `emojis` table holds, and they are the
     * three a person can react with. A server's emoji from *another* server are deliberately
     * absent: they are the ones the channel's own permission check would refuse.
     */
    @Test fun theLibraryAsksForTheRoomsServerInstanceAndPersonalEmoji() = runBlocking {
        enqueue(200, """[{"id":"e1","name":"cat","url":"https://x/cat.png","server_id":"s1","scope":"server"}]""")
        val library = chat.emojiLibrary(endpoint, token(), serverId = "s1").getOrThrow()
        assertEquals("one row read", 1, library.size)
        assertEquals("cat", library.first().name)
        val query = take().path!!
        assertTrue("the room's server: $query", query.contains("server_id.eq.s1"))
        assertTrue("the instance's imports: $query", query.contains("scope.eq.instance"))
        assertTrue("this account's own: $query", query.contains("scope.eq.user"))
        assertTrue("a removed emoji is not offered: $query", query.contains("deleted_at=is.null"))
    }

    /** A DM has no server, so the query asks only for what is usable anywhere. */
    @Test fun aDirectMessageAsksForNoServer() = runBlocking {
        enqueue(200, "[]")
        chat.emojiLibrary(endpoint, token(), serverId = null).getOrThrow()
        val query = take().path!!
        assertTrue("no server filter: $query", !query.contains("server_id"))
        assertTrue("the instance's imports: $query", query.contains("scope.eq.instance"))
        assertTrue("this account's own: $query", query.contains("scope.eq.user"))
    }

    /** A row with no picture, or no name, is not something the picker can offer. */
    @Test fun theLibraryDropsRowsWithNothingToDraw() = runBlocking {
        enqueue(200, """[{"id":"e1","name":"cat","url":"https://x/cat.png","scope":"server"},""" +
            """{"id":"e2","name":"gone","url":null,"scope":"server"},""" +
            """{"id":"e3","name":null,"url":"https://x/x.png","scope":"server"}]""")
        val library = chat.emojiLibrary(endpoint, token(), serverId = "s1").getOrThrow()
        assertEquals("only the drawable one", listOf("cat"), library.map { it.name })
    }
}
