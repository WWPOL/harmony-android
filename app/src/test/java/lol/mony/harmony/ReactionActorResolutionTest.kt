package lol.mony.harmony

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.model.Reaction
import lol.mony.harmony.data.model.actor
import lol.mony.harmony.ui.AppState
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The lookup that turns a reaction's reactor into a person the list can name.
 *
 * A reaction row names its reactor by id and carries nothing else, so the names only appear if
 * something reads the `profiles` and `bots` rows the page's reactions point at. Nothing did: the
 * author lookup ran on messages, and a person who only reacted never wrote one -- which is why
 * every reactor read "Someone".
 *
 * These drive the real [AppState] against a fake instance, so what is asserted is the request the
 * app makes and the people map it fills, not a function called by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ReactionActorResolutionTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: Endpoint
    private lateinit var state: AppState
    private val requests = mutableListOf<String>()

    @Before fun setup() {
        server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                synchronized(requests) { requests += path }
                val body = when {
                    path.contains("/profiles") -> PROFILES
                    path.contains("/bots") -> BOTS
                    else -> "[]"
                }
                return MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
        endpoint = Endpoint(server.url("/").toString().trimEnd('/'), "test-anon")
        state = AppState(AppContainer(RuntimeEnvironment.getApplication()))
    }

    @After fun teardown() { server.shutdown() }

    private fun reaction(json: String): Reaction = Json.decodeFromString(Reaction.serializer(), json)

    private fun token(): String {
        val claims = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"auth-1"}""".toByteArray())
        return "e30.$claims.sig"
    }

    // ---- the reported bug ------------------------------------------------------------------

    @Test fun aReactorWhoNeverWroteAMessageIsNamed() = runBlocking {
        val rows = listOf(
            reaction("""{"message_id":"m1","user_id":"p1","custom_emoji_content":"👍"}"""),
            reaction("""{"message_id":"m1","user_id":"p2","custom_emoji_content":"👍"}"""),
        )
        state.resolveReactionActors(endpoint, token(), rows)

        // The names the sheet draws, through the same model the sheet uses.
        val names = rows.map { it.actor(state.people.value, state.bots.value).name }
        assertEquals(listOf("Ada Lovelace", "Grace Hopper"), names)
    }

    @Test fun oneReadCoversEveryReactorOnThePage() = runBlocking {
        val rows = listOf(
            reaction("""{"message_id":"m1","user_id":"p1"}"""),
            reaction("""{"message_id":"m2","user_id":"p2"}"""),
            reaction("""{"message_id":"m2","user_id":"p1"}"""),
        )
        state.resolveReactionActors(endpoint, token(), rows)

        val profileReads = synchronized(requests) { requests.count { it.contains("/profiles") } }
        assertEquals("both reactors in one read, not one per reaction", 1, profileReads)
        assertTrue(state.people.value.containsKey("p1"))
        assertTrue(state.people.value.containsKey("p2"))
    }

    @Test fun aBotReactorIsResolvedFromItsOwnTable() = runBlocking {
        val rows = listOf(reaction("""{"message_id":"m1","bot_id":"b1","custom_emoji_content":"👍"}"""))
        state.resolveReactionActors(endpoint, token(), rows)

        assertEquals("Blobcat", rows.single().actor(state.people.value, state.bots.value).name)
        assertTrue(synchronized(requests) { requests.any { it.contains("/bots") } })
    }

    @Test fun aBridgedReactorIsNotLookedUpAtAll() = runBlocking {
        // Its name and picture travel in the row; asking for a profile would be a read that can
        // only come back empty.
        val rows = listOf(reaction(
            """{"message_id":"m1","metadata":{"discord_user":{"id":"d1","username":"discord_ada"}}}""",
        ))
        state.resolveReactionActors(endpoint, token(), rows)

        assertEquals("discord_ada", rows.single().actor(state.people.value, state.bots.value).name)
        assertTrue("nothing to read for a bridged reactor",
            synchronized(requests) { requests.none { it.contains("/profiles") || it.contains("/bots") } })
    }

    @Test fun aReactorAlreadyKnownIsNotReadAgain() = runBlocking {
        state.rememberPeople(listOf(
            lol.mony.harmony.data.model.Profile(id = "p1", authUserId = "a1", username = "ada", displayName = "Ada Lovelace"),
        ))
        state.resolveReactionActors(endpoint, token(), listOf(reaction("""{"message_id":"m1","user_id":"p1"}""")))

        assertTrue("a known profile is not refetched",
            synchronized(requests) { requests.none { it.contains("/profiles") } })
    }

    @Test fun aFailedLookupLeavesTheRowUnnamedRatherThanBreakingThePage() = runBlocking {
        server.shutdown() // the instance stops answering mid-page
        val rows = listOf(reaction("""{"message_id":"m1","user_id":"p1"}"""))
        state.resolveReactionActors(endpoint, token(), rows)

        // The lookup is swallowed on purpose: the page keeps drawing, and the row says plainly
        // that the account could not be read instead of claiming a name it does not have.
        assertEquals("Unknown account", rows.single().actor(state.people.value, state.bots.value).name)
        server = MockWebServer().also { it.start() } // teardown shuts this one down
    }

    private companion object {
        /** Two `profiles` rows with the column types the schema declares. */
        val PROFILES = """
            [{"id":"p1","auth_user_id":"a1","username":"ada","display_name":"Ada Lovelace",
              "avatar_url":"/avatars/p1.webp","banner_url":null,"bio":null,"pronouns":null,
              "domain":"example.test","is_local":true,"appearance_settings":null,
              "created_at":"2026-01-01T00:00:00+00:00","status":0,"custom_status":null},
             {"id":"p2","auth_user_id":"a2","username":"grace","display_name":"Grace Hopper",
              "avatar_url":null,"banner_url":null,"bio":null,"pronouns":null,
              "domain":"example.test","is_local":true,"appearance_settings":null,
              "created_at":"2026-01-01T00:00:00+00:00","status":0,"custom_status":null}]
        """.trimIndent()

        val BOTS = """
            [{"id":"b1","username":"blobcat","display_name":"Blobcat",
              "avatar_url":"https://cdn.example.test/blobcat.png","banner_url":null,"bio":null,
              "owner_id":null,"is_verified":false,"bot_type":null}]
        """.trimIndent()
    }
}
