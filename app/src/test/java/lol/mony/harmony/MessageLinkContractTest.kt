package lol.mony.harmony

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.repo.ChatRepository
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The link and embed contract, over the wire: what a send stores, and what a read resolves.
 *
 * Two reports drove this file. A link in a message could not be tapped, and a link got no preview
 * card. Both have the same root: this client stored a message body as one `text` part, and the
 * instance only previews `url` parts -- `process_local_link_previews` skips every part whose type
 * is not `url`, and so does the reference's `resolveEmbedPayload`. So the test that matters is the
 * one on the request body: a typed URL has to leave here as a `url` part, or nothing downstream
 * can ever make it a link.
 *
 * The read half pins the other end: the preview cards live in `messages.metadata.embeds`, and a
 * part has to find its own card there -- by URL, by trailing-slashless URL (which is the key the
 * database's own trigger writes), and by `previewId` for an `embed` part.
 */
class MessageLinkContractTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: Endpoint
    private lateinit var repo: ChatRepository
    private val requests = mutableListOf<RecordedRequest>()

    @Before fun setup() {
        server = MockWebServer(); server.start()
        endpoint = Endpoint(server.url("/").toString().trimEnd('/'), "test-anon")
        repo = ChatRepository(RestApi(OkHttpClient()))
    }
    @After fun teardown() { server.shutdown() }

    private fun json(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(body)

    /** The `profiles` row `ProfileRepository.mine` reads: the account's own profile, as an array. */
    private fun profileRow() = """[{"id":"11111111-1111-4111-8111-111111111111","auth_user_id":"auth-1",
        "username":"me","display_name":"Me","avatar_url":null,"banner_url":null,"bio":null,
        "pronouns":null,"domain":"example.test","is_local":true,"appearance_settings":null,
        "created_at":"2026-01-01T00:00:00+00:00","status":0,"custom_status":null}]"""

    /**
     * Routes by path, so the three requests a send makes cannot be confused by their order.
     *
     * The insert is answered with no representation, which PostgREST really does, so the send
     * falls back to looking its own row up by the nonce it carried -- and that read has to
     * answer, or a send that landed reads as one the instance refused.
     */
    private fun route() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                val nonce = request.requestUrl!!.queryParameter("metadata->>client_nonce")
                return when {
                    path.endsWith("/rpc/effective_channel_encryption") -> json("false")
                    path.endsWith("/profiles") -> json(profileRow())
                    path.endsWith("/messages") && nonce != null -> json(
                        """[{"id":"m1","channel_id":"c1","user_id":"11111111-1111-4111-8111-111111111111",
                            "created_at":"2026-11-13T05:39:18+00:00","content":[{"type":"text","text":"stored"}],
                            "metadata":{"client_nonce":"$nonce"}}]""")
                    path.endsWith("/messages") -> json("[]")
                    else -> MockResponse().setResponseCode(404).setBody("""{"message":"no route"}""")
                }
            }
        }
    }

    /** The body of the one request that wrote a message. */
    private fun insertedBody(): JsonObject {
        val insert = requests.first { it.method == "POST" && it.requestUrl!!.encodedPath.endsWith("/messages") }
        return HarmonyJson.parseToJsonElement(insert.body.readUtf8()) as JsonObject
    }

    private fun storedParts(body: JsonObject) = body["content"]!!.jsonArray.map { it.jsonObject }
    private fun storedTypes(body: JsonObject) = storedParts(body).map { it["type"]!!.jsonPrimitive.content }

    @Test fun aTypedUrlIsStoredAsAUrlPart() = runBlocking {
        route()
        repo.sendChannelMessage(endpoint, "token", "c1", "look at https://example.test/post please", nonce = "n1").getOrThrow()

        val body = insertedBody()
        assertEquals("prose, link, prose", listOf("text", "url", "text"), storedTypes(body))
        val link = storedParts(body).single { it["type"]!!.jsonPrimitive.content == "url" }
        assertEquals("https://example.test/post", link["url"]!!.jsonPrimitive.content)
        assertEquals("true", link["preview"]!!.jsonPrimitive.content)
    }

    /**
     * The part shape is what the instance's own trigger reads, and it is the whole point: a
     * message stored as one text part is one no client can preview.
     */
    @Test fun aUrlInsideATextPartWouldNeverBePreviewed() = runBlocking {
        route()
        repo.sendChannelMessage(endpoint, "token", "c1", "look at https://example.test/post", nonce = "n1").getOrThrow()

        val parts = storedParts(insertedBody())
        assertTrue("the URL must not be buried in a text part",
            parts.none { it["type"]!!.jsonPrimitive.content == "text" &&
                (it["text"]?.jsonPrimitive?.content ?: "").contains("https://") })
    }

    @Test fun aMaskedLinkKeepsItsLabelAndASuppressedOneItsFlag() = runBlocking {
        route()
        repo.sendChannelMessage(endpoint, "token", "c1",
            "read [the post](https://example.test/posts/1) and <https://example.test/quiet>",
            nonce = "n1").getOrThrow()

        val links = storedParts(insertedBody()).filter { it["type"]!!.jsonPrimitive.content == "url" }
        assertEquals(2, links.size)
        assertEquals("the post", links[0]["label"]!!.jsonPrimitive.content)
        assertEquals("https://example.test/posts/1", links[0]["url"]!!.jsonPrimitive.content)
        assertEquals("true", links[0]["preview"]!!.jsonPrimitive.content)
        assertEquals("https://example.test/quiet", links[1]["url"]!!.jsonPrimitive.content)
        assertEquals("false", links[1]["preview"]!!.jsonPrimitive.content)
    }

    @Test fun aMessageWithNoLinkIsStillOneTextPart() = runBlocking {
        route()
        repo.sendChannelMessage(endpoint, "token", "c1", "just words", nonce = "n1").getOrThrow()
        assertEquals(listOf("text"), storedTypes(insertedBody()))
    }

    @Test fun anEditStoresItsLinksAsPartsToo() = runBlocking {
        val original = """[{"id":"m1","channel_id":"c1","user_id":"u1","created_at":"2026-11-13T05:39:18+00:00",
            "content":[{"type":"text","text":"before"}]}]"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/messages") && request.method == "GET" -> json(original)
                    path.endsWith("/messages") && request.method == "PATCH" -> json(original)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        repo.editMessage(endpoint, "token", "m1", "now https://example.test/post").getOrThrow()

        val patch = requests.first { it.method == "PATCH" }
        val body = HarmonyJson.parseToJsonElement(patch.body.readUtf8()) as JsonObject
        val types = body["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("text", "url"), types)
    }

    // ---- the read half: the stored cards ----

    private val pageWithEmbeds = """[
      {"id":"m1","channel_id":"c1","user_id":"u1","created_at":"2026-11-13T05:39:18+00:00",
       "content":[{"type":"text","text":"look at "},{"type":"url","url":"https://example.test/post","preview":true}],
       "metadata":{"embeds":{"https://example.test/post":{
         "cacheKey":"generic:https://example.test/post","url":"https://example.test/post",
         "normalizedUrl":"https://example.test/post","provider":"generic","title":"A post about links",
         "description":"What a card should look like.","siteName":"Example","image":"/storage/v1/object/public/x.png",
         "icon":"https://example.test/favicon.ico","color":"#3b82f6",
         "fetchedAt":"2026-11-13T05:39:00+00:00","expiresAt":"2026-11-14T05:39:00+00:00"}}}},
      {"id":"m2","channel_id":"c1","user_id":"u1","created_at":"2026-11-13T05:40:18+00:00",
       "content":[{"type":"embed","url":"https://example.test/posts/2","provider":"harmony-post","previewId":"https://example.test/posts/2"}],
       "metadata":{"embeds":{"https://example.test/posts/2":{
         "url":"https://example.test/posts/2","normalizedUrl":"https://example.test/posts/2",
         "provider":"harmony-post","title":"Carol","description":"Hello from the other instance.",
         "siteName":"example.test","harmony":{"postId":"2"}}}}},
      {"id":"m3","channel_id":"c1","user_id":"u1","created_at":"2026-11-13T05:41:18+00:00",
       "content":[{"type":"text","text":"quiet "},{"type":"url","url":"https://example.test/quiet","preview":false}],
       "metadata":{"embeds":{"https://example.test/quiet":{"url":"https://example.test/quiet","title":"hidden"}}}},
      {"id":"m4","channel_id":"c1","user_id":"u1","created_at":"2026-11-13T05:42:18+00:00",
       "content":[{"type":"url","url":"https://example.test/gone","preview":true}],
       "metadata":{"embeds":{"https://example.test/gone":{"url":"https://example.test/gone","failed":true}}}},
      {"id":"m5","channel_id":"c1","user_id":"u1","created_at":"2026-11-13T05:43:18+00:00",
       "content":[{"type":"url","url":"https://example.test/post","preview":true}],
       "metadata":{"suppress_embeds":true,
         "embeds":{"https://example.test/post":{"url":"https://example.test/post","title":"A post about links"}}}}
    ]"""

    private suspend fun page(): Map<String, Message> {
        server.enqueue(json(pageWithEmbeds))
        return repo.channelMessages(endpoint, "token", "c1").getOrThrow().associateBy { it.id }
    }

    @Test fun aPageCarriesTheCardsTheInstanceStored() = runBlocking {
        val byId = page()
        val preview = byId.getValue("m1").previewFor(byId.getValue("m1").content.single { it.type == "url" })
        assertNotNull("the stored card must be found by the URL its part carries", preview)
        assertEquals("A post about links", preview!!.title)
        assertEquals("What a card should look like.", preview.description)
        assertEquals("Example", preview.site)
        assertEquals("/storage/v1/object/public/x.png", preview.image)
        assertEquals("#3b82f6", preview.color)
        assertTrue(preview.isDrawable)
    }

    @Test fun anEmbedPartFindsItsCardByPreviewId() = runBlocking {
        val row = page().getValue("m2")
        val preview = row.previewFor(row.content.single())
        assertEquals("harmony-post", preview!!.provider)
        assertEquals("Carol", preview.title)
    }

    @Test fun aSuppressedPreviewIsNotDrawnAndASuppressedMessageHasNone() = runBlocking {
        val byId = page()
        val quiet = byId.getValue("m3")
        assertNull("<url> asked for no card", quiet.previewFor(quiet.content.single { it.type == "url" }))
        val failed = byId.getValue("m4")
        assertFalse("a page that could not be read is not a card", failed.previewFor(failed.content.single())!!.isDrawable)
        val suppressed = byId.getValue("m5")
        assertTrue(suppressed.embedsSuppressed)
        assertNull(suppressed.previewFor(suppressed.content.single()))
    }

    /** A message the instance has previewed still reads as its own text, card or no card. */
    @Test fun theStoredTextOfAPreviewedMessageIsUnchanged() = runBlocking {
        val byId = page()
        assertEquals("look at https://example.test/post", byId.getValue("m1").plainText)
    }

    @Test fun aMessageWhoseBodyIsALinkIsEditableAndRoundTrips() = runBlocking {
        val byId = page()
        val link = byId.getValue("m1")
        assertTrue("a body of prose and links can be edited", link.isEditable)
        assertEquals("look at https://example.test/post", link.editText)
        val masked = byId.getValue("m2")
        assertFalse("an embed part is not editable", masked.isEditable)
    }
}
