package lol.mony.harmony

import kotlinx.serialization.json.Json
import lol.mony.harmony.data.model.Bot
import lol.mony.harmony.data.model.Profile
import lol.mony.harmony.data.model.Reaction
import lol.mony.harmony.data.model.actor
import lol.mony.harmony.data.model.discordUser
import lol.mony.harmony.data.model.reactionActors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who a reaction is from, for every shape a `reactions` row stores its reactor in.
 *
 * The reported bug was that the reactor list read "Someone" for everyone. The cause was that the
 * list only ever read `user_id`, so the two other shapes a reaction names its reactor in -- a
 * `bots` row, and a Discord person a bridge relays in the row's own `metadata` -- fell through to
 * a placeholder, and a `user_id` nothing had looked up fell through to it too.
 *
 * These are assertions on the names the list draws, so a regression that names a reactor anything
 * but who they are fails here rather than on a phone.
 */
class ReactionActorTest {
    private val ada = Profile(id = "p1", authUserId = "a1", username = "ada", displayName = "Ada Lovelace",
        avatarUrl = "/avatars/p1.webp")
    private val grace = Profile(id = "p2", authUserId = "a2", username = "grace", domain = "other.test",
        isLocal = false)
    private val blobcat = Bot(id = "b1", username = "blobcat", displayName = "Blobcat",
        avatarUrl = "https://cdn.example.test/blobcat.png")
    private val people = mapOf("p1" to ada, "p2" to grace)
    private val bots = mapOf("b1" to blobcat)

    private fun reaction(json: String): Reaction = Json.decodeFromString(Reaction.serializer(), json)

    // ---- the reported bug ------------------------------------------------------------------

    @Test fun aProfileReactorIsNamedByTheirProfile() {
        val actor = reaction("""{"message_id":"m1","user_id":"p1","custom_emoji_content":"👍"}""")
            .actor(people, bots)
        assertEquals("Ada Lovelace", actor.name)
        assertEquals("@ada", actor.handle)
        assertEquals("/avatars/p1.webp", actor.avatarPath)
        assertEquals("p1", actor.profileId)
        assertFalse(actor.isBot)
        assertFalse(actor.isBridged)
    }

    @Test fun aReactorWhoseProfileWasNotReadSaysSoRatherThanSomeone() {
        // `reactions.user_id` cascades with its profile, so an unread row is a failed read, not a
        // deleted account -- and either way it must never be an unnamed "Someone".
        val actor = reaction("""{"message_id":"m1","user_id":"unread"}""").actor(people, bots)
        assertEquals("Unknown account", actor.name)
        assertEquals("unread", actor.profileId)
        assertTrue("no name may be a placeholder", actor.name != "Someone")
    }

    @Test fun aBotReactorIsNamedByItsBotRow() {
        val actor = reaction("""{"message_id":"m1","bot_id":"b1","custom_emoji_content":"👍"}""")
            .actor(people, bots)
        assertEquals("Blobcat", actor.name)
        assertEquals("@blobcat", actor.handle)
        assertEquals("b1", actor.botId)
        assertTrue(actor.isBot)
        assertNull("a bot has no profile to open", actor.profileId)
    }

    @Test fun aBotWhoseRowWasNotReadIsStillABotAndNotSomeone() {
        val actor = reaction("""{"message_id":"m1","bot_id":"unread"}""").actor(people, bots)
        assertEquals("Bot", actor.name)
        assertTrue(actor.isBot)
    }

    @Test fun aBridgedDiscordReactorIsNamedByTheRowItself() {
        // The bridge writes no `user_id`: there is no local profile to look up, so the name and
        // picture travel in the reaction's own metadata.
        val actor = reaction(
            """{"message_id":"m1","bot_id":"b1",
                "metadata":{"discord_user":{"id":"d1","username":"discord_ada",
                                            "display_name":"Ada (Discord)",
                                            "avatar_url":"https://cdn.discordapp.com/avatars/d1.png"}}}""",
        ).actor(people, bots)
        assertEquals("Ada (Discord)", actor.name)
        assertEquals("@discord_ada", actor.handle)
        assertEquals("https://cdn.discordapp.com/avatars/d1.png", actor.avatarPath)
        assertTrue(actor.isBridged)
        assertFalse("the bridge's own bot must not take the reaction", actor.isBot)
    }

    @Test fun aBridgedReactorWithNoDisplayNameFallsBackToItsUsername() {
        val actor = reaction(
            """{"message_id":"m1","metadata":{"discord_user":{"id":"d1","username":"discord_ada"}}}""",
        ).actor(people, bots)
        assertEquals("discord_ada", actor.name)
    }

    @Test fun twoBridgedRowsThatNameNoIdAreStillTwoLines() {
        // A bridge that omits the Discord id still writes the reaction's own id, which is what
        // keeps two people from being drawn as one.
        val rows = listOf(
            reaction("""{"id":"r1","message_id":"m1","metadata":{"discord_user":{"username":"one"}}}"""),
            reaction("""{"id":"r2","message_id":"m1","metadata":{"discord_user":{"username":"two"}}}"""),
        )
        val actors = reactionActors(rows, people, bots)
        assertEquals(listOf("discord:r1", "discord:r2"), actors.map { it.key })
    }

    @Test fun aRowWithNoReactorAtAllIsNotDrawnAsAPerson() {
        val actor = reaction("""{"id":"r1","message_id":"m1","custom_emoji_content":"👍"}""").actor(people, bots)
        assertEquals("Unknown account", actor.name)
        assertNull(actor.profileId)
        assertNull(actor.botId)
    }

    // ---- the details the list draws ---------------------------------------------------------

    @Test fun aRemoteReactorKeepsItsDomainInItsHandle() {
        val actor = reaction("""{"message_id":"m1","user_id":"p2"}""").actor(people, bots)
        assertEquals("@grace@other.test", actor.handle)
    }

    @Test fun thisAccountIsMarkedSoTheListCanSayYou() {
        assertTrue(reaction("""{"message_id":"m1","user_id":"p1"}""").actor(people, bots, myId = "p1").isYou)
        assertFalse(reaction("""{"message_id":"m1","user_id":"p1"}""").actor(people, bots, myId = "p2").isYou)
    }

    @Test fun theSamePersonIsOneLineAndDifferentPeopleAreNot() {
        val rows = listOf(
            reaction("""{"id":"r1","message_id":"m1","user_id":"p1"}"""),
            reaction("""{"id":"r2","message_id":"m1","user_id":"p1"}"""),
            reaction("""{"id":"r3","message_id":"m1","bot_id":"b1"}"""),
            reaction("""{"id":"r4","message_id":"m1","metadata":{"discord_user":{"id":"d1","username":"d"}}}"""),
        )
        val actors = reactionActors(rows, people, bots)
        assertEquals(listOf("user:p1", "user:p1", "bot:b1", "discord:d1"), actors.map { it.key })
        assertEquals(4, actors.size)
        assertEquals("a line per row, each named", 4, actors.count { it.name.isNotBlank() })
    }

    @Test fun aDiscordPersonIsRecognisedByTheMetadataKeyItIsStoredUnder() {
        val bridged = reaction("""{"message_id":"m1","metadata":{"discord_user":{"id":"d1"}}}""")
        val plain = reaction("""{"message_id":"m1","user_id":"p1"}""")
        assertTrue(bridged.discordUser != null)
        assertNull(plain.discordUser)
        // A metadata object without the key is not a bridged reaction either.
        assertNull(reaction("""{"message_id":"m1","metadata":{"remote_emoji_url":"https://x.test/a.png"}}""").discordUser)
    }

    @Test fun aBridgedReactorIsNotLookedUpAsALocalAccount() {
        // The reactor list resolves only what it can look up: a bridged row names no profile.
        val bridged = reaction(
            """{"message_id":"m1","bot_id":"b1","metadata":{"discord_user":{"id":"d1","username":"d"}}}""",
        ).actor(people, bots)
        assertNull(bridged.profileId)
        assertTrue(bridged.isPerson.not())
    }
}
