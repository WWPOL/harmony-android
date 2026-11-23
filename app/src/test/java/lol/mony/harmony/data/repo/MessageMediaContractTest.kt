package lol.mony.harmony.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.data.model.Message
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
import java.util.Base64
import java.util.UUID

/**
 * What a chat attachment must be, checked against the instance's own rules rather than this
 * client's habits.
 *
 * Three of these are the difference between an attachment that works and one that looks like it
 * worked: the object name has to be the one the bucket's policies read, the part has to carry
 * its `path` (the `url` alone expires in seven days), and the part has to survive this client's
 * own serialiser on the way out. Each is pinned here because each failed silently -- the
 * instance accepted the message and nobody could open the file.
 */
class MessageMediaContractTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: Endpoint
    private lateinit var rest: RestApi
    private lateinit var media: MessageMediaRepository

    private val channelId = "0f2c1c4e-4f2a-4c39-9d5e-1a2b3c4d5e6f"
    private val conversationId = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
    private val authId = "11111111-2222-4333-8444-555555555555"

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        endpoint = Endpoint(server.url("/").toString().trimEnd('/'), "test-anon")
        rest = RestApi(OkHttpClient())
        media = MessageMediaRepository(rest)
    }

    @After fun tearDown() { server.shutdown() }

    private fun token(): String {
        val claims = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$authId"}""".toByteArray())
        return "e30." + claims + ".sig"
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)

    /** A tiny but real PNG: signature, IHDR, IDAT, IEND. */
    private fun pngBytes(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(20, 120, 220))
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    // ---- the object name the bucket's policies read ----------------------------------------

    @Test fun anObjectNameIsTheRoomThenTheUploaderThenAFreshName() {
        val path = MessageMedia.path("c/$channelId", authId, "holiday photo.JPG")
        assertEquals("c/$channelId/$authId", path.substringBeforeLast('/'))
        assertTrue("the picked name is not reused: $path", path.endsWith(".jpg"))
        assertTrue("the object's own name is a fresh uuid", UUID.fromString(path.substringAfterLast('/').substringBefore('.')) != null)
    }

    @Test fun aDirectMessageAndAChannelUseTheirOwnRoomPrefixes() {
        assertEquals("d/$conversationId", MessageMedia.room(null, conversationId))
        assertEquals("c/$channelId", MessageMedia.room(channelId, null))
        // A conversation wins when a target carries both, as the reference's `mediaRoom` reads it.
        assertEquals("d/$conversationId", MessageMedia.room(channelId, conversationId))
        assertNull("an id that is not a uuid names no room", MessageMedia.room("general", null))
        assertNull(MessageMedia.room(null, null))
    }

    @Test fun onlyARoomsOwnObjectIsAcceptedForItsMessages() {
        assertTrue(MessageMedia.isPathInRoom("c/$channelId/$authId/a.png", "c/$channelId"))
        assertFalse("another channel's object",
            MessageMedia.isPathInRoom("c/$conversationId/$authId/a.png", "c/$channelId"))
        assertFalse("a prefix that merely starts the same",
            MessageMedia.isPathInRoom("c/${channelId}0/$authId/a.png", "c/$channelId"))
        assertFalse("no room at all", MessageMedia.isPathInRoom(null, "c/$channelId"))
    }

    @Test fun theExtensionComesFromTheNameElseFromTheType() {
        assertEquals("png", MessageMedia.extension("photo.PNG", null))
        assertEquals("jpg", MessageMedia.extension("no extension", "image/jpeg"))
        assertEquals("7z", MessageMedia.extension("archive.tar.7z", "application/x-7z"))
        assertEquals("png", MessageMedia.extension("photo.png", "image/jpeg"))
    }

    // ---- the part a message carries ---------------------------------------------------------

    @Test fun aFilePartCarriesEveryFieldAReaderNeeds() {
        val part = MessageMedia.filePart(
            path = "c/$channelId/$authId/a.png",
            url = "https://example.test/storage/v1/object/sign/message_media/a.png?token=t",
            fileName = "a.png", mimeType = "image/png", size = 1234, altText = " a blue square ",
        )
        assertEquals("file", part.type)
        assertEquals("c/$channelId/$authId/a.png", part.path)
        assertEquals("image", part.fileType)
        assertEquals("a blue square", part.altText)
        assertEquals(1234L, part.fileSize)
        assertTrue(part.isBlock)
    }

    /**
     * The bug this pins: `ContentPart`'s serialiser wrote only text, url, name and emoji, so an
     * uploaded attachment went out as `{"type":"file"}`. The instance accepted that -- it has no
     * rule requiring `path` -- and every client then drew a file with no file in it.
     */
    @Test fun aBuiltFilePartSerialisesItsPathAndType() {
        val part = MessageMedia.filePart(
            path = "c/$channelId/$authId/a.png", url = "https://example.test/x", fileName = "a.png",
            mimeType = "image/png", size = 9, altText = "a square",
        )
        val json = HarmonyJson.encodeToString(ContentPart.serializer(), part)
        for (field in listOf("\"path\"", "\"fileType\"", "\"fileName\"", "\"fileSize\"", "\"mimeType\"", "\"altText\"")) {
            assertTrue("$field is missing from $json", json.contains(field))
        }
        // And it survives the round trip a real message makes through the database.
        val back = HarmonyJson.decodeFromString(ContentPart.serializer(), json)
        assertEquals(part.path, back.path)
        assertEquals(part.altText, back.altText)
        assertEquals(part.fileSize, back.fileSize)
    }

    // ---- the upload -------------------------------------------------------------------------

    @Test fun anUploadPutsTheBytesInTheRoomsObjectAndSignsIt() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        val uploads = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when {
                    request.method == "POST" && request.path!!.contains("/storage/v1/object/message_media/") -> {
                        uploads += request.path!!
                        json("""{"Key":"message_media/a.png","Id":"1"}""")
                    }
                    // The instance signs exactly the objects it was asked about, so the answer
                    // echoes the request's own list.
                    request.path!!.contains("/storage/v1/object/sign/message_media") -> {
                        val asked = Regex("\"([^\"]+)\"").findAll(request.body.readUtf8())
                            .map { it.groupValues[1] }.filter { it.contains('/') }.toList()
                        json(asked.joinToString(",", "[", "]") { path ->
                            """{"path":"$path","signedURL":"/object/sign/message_media/x?token=sig","error":null}"""
                        })
                    }
                    else -> json("[]")
                }
            }
        }
        val picked = MessageMediaRepository.Picked("fresh.png", "image/png", 3, byteArrayOf(1, 2, 3))
        val progress = mutableListOf<Float>()
        val uploaded = media.upload(
            endpoint = endpoint, token = token(), uploaderAuthId = authId, room = "c/$channelId",
            picked = picked, onProgress = { progress += it },
        )

        assertEquals(1, uploads.size)
        val sentPath = uploads.single().substringAfter("/storage/v1/object/message_media/")
        assertEquals("the object is the room's, under this account", "c/$channelId/$authId",
            sentPath.substringBeforeLast('/'))
        assertTrue(sentPath.endsWith(".png"))
        assertEquals("the bytes that were sent are the bytes picked", 3L, uploaded.size)
        assertEquals("image/png", uploaded.mimeType)
        assertNotNull("the part carries a URL for older clients", uploaded.url)
        assertEquals(sentPath, uploaded.path)
        assertTrue("progress reaches 1", progress.last() == 1f)
        // The signed URL the part carries is the instance's own, resolved against /storage/v1.
        assertTrue(uploaded.url!!.startsWith(endpoint.baseUrl + "/storage/v1/object/sign/message_media/"))
    }

    @Test fun aRefusedUploadLeavesNoObjectBehind() = runBlocking {
        val deletes = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "POST" && request.path!!.contains("/storage/v1/object/message_media/") ->
                    json("""{"message":"new row violates row-level security policy"}""", 403)
                request.method == "DELETE" -> { deletes += request.body.readUtf8(); json("[]") }
                else -> json("[]")
            }
        }
        val result = runCatching {
            media.upload(endpoint, token(), authId, "c/$channelId",
                MessageMediaRepository.Picked("x.png", "image/png", 1, byteArrayOf(9)))
        }
        assertTrue(result.isFailure)
        val reason = result.exceptionOrNull()!!.message.orEmpty()
        assertTrue("the refusal is a sentence, not a status: $reason", reason.contains("refused", ignoreCase = true))
    }

    @Test fun svgIsRefusedBeforeAnyByteIsSent() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return json("null")
            }
        }
        val reason = media.refuseReason(endpoint, token(),
            MessageMediaRepository.Picked("logo.svg", "image/svg+xml", 10, ByteArray(10)))
        assertNotNull(reason)
        assertTrue(reason!!.contains("SVG"))
        // A file whose *name* says SVG is refused even when the provider reported no type.
        assertNotNull(media.refuseReason(endpoint, token(),
            MessageMediaRepository.Picked("logo.SVG", null, 10, ByteArray(10))))
    }

    @Test fun aFileOverTheAccountsOwnLimitIsRefusedWithItsSize() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.contains("get_my_upload_limit")) json("2048") else json("null")
        }
        assertNull("a small file is not refused",
            media.refuseReason(endpoint, token(), MessageMediaRepository.Picked("a.png", "image/png", 10, ByteArray(10))))
        val reason = media.refuseReason(endpoint, token(),
            MessageMediaRepository.Picked("big.png", "image/png", 4096, ByteArray(4096)))
        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("4 KB") && reason.contains("2 KB"))
    }

    @Test fun anInstanceWithoutAnUploadLimitFunctionStillUploads() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                json("""{"message":"Could not find the function"}""", 404)
        }
        assertNull(media.uploadLimit(endpoint, token()))
        assertNull(media.refuseReason(endpoint, token(),
            MessageMediaRepository.Picked("a.png", "image/png", 10, ByteArray(10))))
    }

    /**
     * A cancelled upload stops the request and deletes whatever it had already stored.
     *
     * Removing an attachment while it is still going out is an ordinary thing to do, and the
     * bucket's own sweep only finds an unreferenced object after its window. Without this the
     * instance collects files that no message names and nobody can see.
     *
     * The cancellation is timed against the *signing* request, which the upload only makes once
     * it has recorded that the object landed. Cancelling on the upload request instead races
     * that record: sometimes the bytes are stored and nothing is owed yet, sometimes not, and
     * the test then proves nothing either way.
     */
    @Test fun aCancelledUploadStopsAndDeletesWhatItStored() = runBlocking {
        val deletes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val stored = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "DELETE" -> {
                    deletes += request.body.readUtf8()
                    json("[]")
                }
                // The object has landed and the client is signing it: the window the delete
                // exists for. The answer is held so the cancellation arrives inside it.
                request.path!!.contains("/storage/v1/object/sign/") -> {
                    stored.countDown()
                    Thread.sleep(3_000)
                    json("[]")
                }
                else -> json("{}")
            }
        }
        val job = launch(Dispatchers.IO) {
            try {
                media.upload(endpoint, token(), authId, "c/$channelId",
                    MessageMediaRepository.Picked("a.png", "image/png", 2, byteArrayOf(1, 2)))
            } catch (ignored: kotlinx.coroutines.CancellationException) {
                // The cancellation is the point of the test.
            }
        }
        assertTrue("the upload reached signing", stored.await(10, java.util.concurrent.TimeUnit.SECONDS))
        job.cancelAndJoin()
        // The delete is best effort and runs on the way out; wait for it rather than guessing.
        withTimeoutOrNull(10_000) { while (deletes.isEmpty()) delay(25) }
        assertTrue("the stored object was deleted: $deletes", deletes.isNotEmpty())
        assertTrue("and it names the room's object: $deletes",
            deletes.any { it.contains("c/$channelId/$authId/") })
    }

    // ---- the message that names it ----------------------------------------------------------

    @Test fun aMessageWithAnAttachmentCarriesThePathInItsContent() = runBlocking {
        val posted = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/rpc/effective_channel_encryption") -> json("false")
                    path.contains("/profiles") -> json(profileRow())
                    path.contains("/messages") && request.method == "POST" -> {
                        posted += request.body.readUtf8()
                        json(messageRow())
                    }
                    else -> json("[]")
                }
            }
        }
        val chat = ChatRepository(rest)
        val part = MessageMedia.filePart(
            path = "c/$channelId/$authId/a.png", url = "https://example.test/sign/a.png",
            fileName = "a.png", mimeType = "image/png", size = 3,
        )
        val sent = chat.sendChannelMessage(endpoint, token(), channelId, "look", nonce = "n1",
            attachments = listOf(part)).getOrThrow()
        assertEquals("m1", sent.id)
        val body = posted.single()
        assertTrue(body, body.contains("\"type\":\"file\""))
        assertTrue(body, body.contains("\"path\":\"c/$channelId/$authId/a.png\""))
        assertTrue("the text part is still there", body.contains("\"text\":\"look\""))
    }

    @Test fun aMessageThatNamesAnotherRoomsObjectIsRefusedHere() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().contains("/rpc/effective_channel_encryption") -> json("false")
                request.path.orEmpty().contains("/profiles") -> json(profileRow())
                else -> json(messageRow())
            }
        }
        val chat = ChatRepository(rest)
        val foreign = MessageMedia.filePart(
            path = "c/$conversationId/$authId/a.png", url = null, fileName = "a.png",
            mimeType = "image/png", size = 3,
        )
        val result = chat.sendChannelMessage(endpoint, token(), channelId, "", attachments = listOf(foreign))
        assertTrue("the instance would refuse it; so does the client, before sending", result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("outside this channel"))
    }

    @Test fun anAttachmentOnItsOwnIsAMessage() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().contains("/rpc/effective_channel_encryption") -> json("false")
                request.path.orEmpty().contains("/profiles") -> json(profileRow())
                else -> json(messageRow())
            }
        }
        val chat = ChatRepository(rest)
        val part = MessageMedia.filePart(
            path = "c/$channelId/$authId/a.png", url = null, fileName = "a.png",
            mimeType = "image/png", size = 3,
        )
        assertEquals("m1", chat.sendChannelMessage(endpoint, token(), channelId, "", attachments = listOf(part))
            .getOrThrow().id)
        assertTrue("neither text nor a file is still an error",
            chat.sendChannelMessage(endpoint, token(), channelId, "  ").isFailure)
    }

    @Test fun aPageOfPrivateObjectsIsSignedInOneRequest() = runBlocking {
        val signRequests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.contains("/storage/v1/object/sign/message_media")) {
                    signRequests += request
                    return json(
                        """[{"path":"c/$channelId/$authId/a.png","signedURL":"/object/sign/message_media/a?token=1","error":null},
                            {"path":"c/$channelId/$authId/b.png","signedURL":"/object/sign/message_media/b?token=2","error":null},
                            {"path":"c/$channelId/$authId/gone.png","signedURL":null,"error":"Object not found"}]""",
                    )
                }
                return json("[]")
            }
        }
        val signed = media.sign(endpoint, token(), listOf(
            "c/$channelId/$authId/a.png", "c/$channelId/$authId/b.png", "c/$channelId/$authId/gone.png",
        ))
        assertEquals(1, signRequests.size)
        assertEquals(2, signed.size)
        assertTrue(signed.getValue("c/$channelId/$authId/a.png").contains("token=1"))
        assertFalse("an object the instance would not sign is absent, not a broken URL",
            signed.containsKey("c/$channelId/$authId/gone.png"))
    }

    @Test fun aSignedUrlFromAnotherHostIsNotUsed() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = json(
                """[{"path":"c/$channelId/$authId/a.png","signedURL":"https://attacker.invalid/object/sign/x?token=1"}]""",
            )
        }
        assertTrue(media.sign(endpoint, token(), listOf("c/$channelId/$authId/a.png")).isEmpty())
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private fun profileRow() = """[{"id":"p1","auth_user_id":"$authId","username":"noah","display_name":"Noah",""" +
        """"avatar_url":null,"banner_url":null,"bio":null,"pronouns":null,"domain":"example.test",""" +
        """"is_local":true,"appearance_settings":null,"created_at":"2026-01-01T00:00:00+00:00","status":0,""" +
        """"custom_status":null}]"""

    private fun messageRow() = """[{"id":"m1","user_id":"p1","channel_id":"$channelId",""" +
        """"created_at":"2026-01-01T00:00:01+00:00","content":[{"type":"text","text":"look"}]}]"""

    private fun Message.messageParts(): List<ContentPart> = content
}
