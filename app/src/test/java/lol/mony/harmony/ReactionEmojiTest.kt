package lol.mony.harmony

import lol.mony.harmony.data.model.DiscordEmoji
import lol.mony.harmony.data.model.Emoji
import lol.mony.harmony.data.model.Reaction
import lol.mony.harmony.ui.components.customEmojiLabel
import lol.mony.harmony.data.model.isEmojiRowId
import lol.mony.harmony.ui.components.pillSpoken
import lol.mony.harmony.ui.components.reactionLabel
import lol.mony.harmony.ui.components.reactionPills
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A custom emoji is drawn as its picture, and its name or id is never drawn in its place.
 *
 * The reported bug: a custom emoji reaction showed a long name or a raw id under the message.
 * `reactions` stores an emoji one of two ways -- `emoji_id`, a uuid naming an `emojis` row, or
 * `custom_emoji_content`, a glyph, a `:shortcode:` or a bridged `discord:name:id` -- and the app
 * drew whichever string it happened to hold as text. A uuid is not an emoji, so a pill whose row
 * was written by the reference client (which puts a custom emoji's uuid in `emoji_id` and leaves
 * the content null) read `3f2a9c1e-...` to the person looking at it.
 *
 * These tests are the model the pills are drawn from, so they fail without a screen.
 */
class ReactionEmojiTest {

    private val catId = "3f2a9c1e-1111-4222-8333-444455556666"
    private val catRow = Emoji(id = catId, name = "cat", url = "https://example.test/storage/v1/object/public/emojis/cat.png")

    // ---- what a reaction's stored content means -------------------------------------------

    @Test fun aStoredShortcodeIsItsName() {
        assertEquals("blobcat", Emoji.fromStoredContent(":blobcat:").name)
        assertNull(Emoji.fromStoredContent(":blobcat:").glyph)
    }

    @Test fun aStoredUnicodeGlyphIsDrawnAsItself() {
        assertEquals("\uD83D\uDC4D", Emoji.fromStoredContent("\uD83D\uDC4D").glyph)
    }

    @Test fun aRemoteShortcodeKeepsItsHost() {
        val remote = Emoji.fromStoredContent(":cat@har.mony.lol:")
        assertEquals("cat", remote.name)
        assertEquals("har.mony.lol", remote.domain)
    }

    @Test fun aBridgedDiscordEmojiCarriesItsOwnPicture() {
        val bridged = Emoji.fromStoredContent("discord:blobcat:1234567890123456789")
        assertEquals("blobcat", bridged.name)
        assertEquals("https://cdn.discordapp.com/emojis/1234567890123456789.png", bridged.url)
        assertEquals(DiscordEmoji.DOMAIN, bridged.domain)
    }

    @Test fun anAnimatedBridgedEmojiIsAGif() {
        val animated = DiscordEmoji.parse("discord:a:party:1234567890123456789")
        assertEquals("https://cdn.discordapp.com/emojis/1234567890123456789.gif", animated!!.imageUrl)
        assertEquals("discord:a:party:1234567890123456789", animated.token)
    }

    @Test fun aStoredRowIdIsNotReadAsAName() {
        // A uuid is alphanumeric and hyphenated, so a naive "bare name" rule would draw it as
        // `:<uuid>:` -- the reported bug, with punctuation around it.
        val id = Emoji.fromStoredContent(catId)
        assertEquals(catId, id.id)
        assertNull(id.name)
        assertEquals(":emoji:", id.shortcode)
        assertEquals(":emoji:", reactionLabel(Reaction(messageId = "m1", customEmojiContent = catId)))
    }

    @Test fun aBareNameIsStillReadable() {
        // What a client before 1.9.4 wrote, and what a pill on an old message still carries.
        assertEquals("RIP", Emoji.fromStoredContent("RIP").name)
    }

    // ---- the label a pill draws ------------------------------------------------------------

    @Test fun aCustomEmojiWithARowDrawsItsShortcodeNotItsId() {
        val reaction = Reaction(messageId = "m1", customEmojiContent = null, emojiId = catId)
        val label = reactionLabel(reaction, catRow)
        assertEquals(":cat:", label)
        assertFalse("a row id must never be the label: $label", label.contains(catId))
    }

    @Test fun aCustomEmojiWithoutARowDrawsTheShortcodeItStores() {
        val label = reactionLabel(Reaction(messageId = "m1", customEmojiContent = ":blobcat:", emojiId = catId))
        assertEquals(":blobcat:", label)
        assertFalse(label, label.contains(catId))
    }

    @Test fun anEmojiWithNoReadableNameIsNotDrawnAsItsId() {
        // The row was not read and the content is empty: the old label drew the id here.
        val label = reactionLabel(Reaction(messageId = "m1", emojiId = catId))
        assertEquals(":emoji:", label)
        assertFalse("an id is not a name: $label", label.contains(catId))
    }

    @Test fun aUnicodeReactionDrawsItsGlyph() {
        assertEquals("\uD83D\uDC4D", reactionLabel(Reaction(messageId = "m1", customEmojiContent = "\uD83D\uDC4D")))
    }

    @Test fun aNameThatIsAlreadyBracketedIsNotBracketedTwice() {
        assertEquals(":cat:", customEmojiLabel(":cat:"))
        assertEquals(":cat:", customEmojiLabel("cat"))
        assertEquals("", customEmojiLabel(null))
    }

    // ---- one pill per emoji ----------------------------------------------------------------

    @Test fun twoPeopleWithTheSameCustomEmojiAreOnePill() {
        val pills = reactionPills(
            listOf(
                Reaction(id = "r1", messageId = "m1", userId = "someone", emojiId = catId),
                Reaction(id = "r2", messageId = "m1", userId = "me", emojiId = catId),
            ),
            mapOf(catId to catRow), myId = "me",
        )
        assertEquals(1, pills.size)
        assertEquals(2, pills.single().count)
        assertTrue("the pill this account used reads as its own", pills.single().mine)
        assertEquals(":cat:", pills.single().label)
        assertEquals("https://example.test/storage/v1/object/public/emojis/cat.png", pills.single().emoji.url)
    }

    @Test fun twoDifferentCustomEmojiAreTwoPillsEvenWithOneName() {
        val dogId = "3f2a9c1e-9999-4222-8333-444455556666"
        val pills = reactionPills(
            listOf(
                Reaction(messageId = "m1", userId = "a", emojiId = catId),
                Reaction(messageId = "m1", userId = "b", emojiId = dogId),
            ),
            mapOf(
                catId to catRow,
                dogId to Emoji(id = dogId, name = "dog", url = "https://example.test/dog.png"),
            ),
            myId = null,
        )
        assertEquals(listOf(catId, dogId), pills.map { it.key })
        assertEquals(listOf(":cat:", ":dog:"), pills.map { it.label })
    }

    @Test fun aPillWhoseRowArrivesLateFillsInItsPicture() {
        val reaction = Reaction(messageId = "m1", customEmojiContent = ":cat:", emojiId = catId)
        val before = reactionPills(listOf(reaction), emptyMap(), null).single()
        assertEquals(":cat:", before.label)
        assertNull("nothing to draw yet", before.emoji.url)

        val after = reactionPills(listOf(reaction), mapOf(catId to catRow), null).single()
        assertEquals(":cat:", after.label)
        assertEquals(catRow.url, after.emoji.url)
    }

    @Test fun aPurgedEmojiKeepsItsName() {
        // admin_purge_emoji leaves custom_emoji_content ':<name>:' and nulls emoji_id.
        val purged = reactionPills(
            listOf(Reaction(messageId = "m1", customEmojiContent = ":cat:", emojiId = null)),
            emptyMap(), null,
        ).single()
        assertEquals(":cat:", purged.label)
        assertNull("its file is gone, so there is no picture to draw", purged.emoji.url)
    }

    @Test fun aBridgedPillDrawsDiscordsPicture() {
        val pill = reactionPills(
            listOf(Reaction(messageId = "m1", customEmojiContent = "discord:blobcat:1234567890123456789")),
            emptyMap(), null,
        ).single()
        assertEquals(":blobcat:", pill.label)
        assertEquals("https://cdn.discordapp.com/emojis/1234567890123456789.png", pill.emoji.url)
    }

    @Test fun aBridgedPillTakesThePictureItsMetadataCarries() {
        // An animated bridged emoji: the identifier alone would rebuild a .png, and the bridge
        // stores the real .gif in the reaction's metadata.
        val gif = "https://cdn.discordapp.com/emojis/1234567890123456789.gif"
        val pill = reactionPills(
            listOf(Reaction(messageId = "m1", customEmojiContent = "discord:a:party:1234567890123456789",
                metadata = JsonObject(mapOf("remote_emoji_url" to JsonPrimitive(gif))))),
            emptyMap(), null,
        ).single()
        assertEquals(gif, pill.emoji.url)
    }

    @Test fun aPictureOnSomeOtherHostIsNotFetched() {
        // Any bot can write reaction metadata and every viewer would load that url, so the
        // reference honours Discord's own hosts and so does this: the identifier's .png stands.
        val hostile = JsonObject(mapOf("remote_emoji_url" to JsonPrimitive("https://tracker.example/pixel.png")))
        val pill = reactionPills(
            listOf(Reaction(messageId = "m1", customEmojiContent = "discord:blobcat:1234567890123456789",
                metadata = hostile)),
            emptyMap(), null,
        ).single()
        assertEquals("https://cdn.discordapp.com/emojis/1234567890123456789.png", pill.emoji.url)
        assertEquals(null, DiscordEmoji.cdnUrl("https://tracker.example/pixel.png"))
        assertEquals(null, DiscordEmoji.cdnUrl("http://cdn.discordapp.com/emojis/1.png"))
        assertEquals("https://cdn.discordapp.com/emojis/1.png",
            DiscordEmoji.cdnUrl("https://cdn.discordapp.com/emojis/1.png"))
    }

    @Test fun aPillSaysItsEmojiItsCountAndWhetherItIsYours() {
        val pill = reactionPills(
            listOf(Reaction(messageId = "m1", userId = "me", emojiId = catId)),
            mapOf(catId to catRow), "me",
        ).single()
        assertEquals(":cat:, 1 reaction, including yours", pillSpoken(pill))
    }

    // ---- how a toggle writes the reaction --------------------------------------------------

    @Test fun aReactionThatNamesARowIsWrittenToTheIdColumn() {
        val reaction = Reaction(messageId = "m1", emojiId = catId)
        assertEquals(catId, reaction.toggleEmojiId)
        assertNull("a uuid is not an emoji, so it does not go in the content column", reaction.toggleContent)
    }

    @Test fun aReactionThatStoresAContentIsWrittenToTheContentColumn() {
        val unicode = Reaction(messageId = "m1", customEmojiContent = "\uD83D\uDC4D")
        assertNull(unicode.toggleEmojiId)
        assertEquals("\uD83D\uDC4D", unicode.toggleContent)

        val bridged = Reaction(messageId = "m1", customEmojiContent = "discord:blobcat:1234567890123456789")
        assertNull(bridged.toggleEmojiId)
        assertEquals("discord:blobcat:1234567890123456789", bridged.toggleContent)
    }

    @Test fun aUuidStoredAsContentIsWrittenToTheIdColumn() {
        // A client that wrote the row id into the content column: the instance refuses that, so
        // this client writes it where it belongs instead of repeating the mistake.
        val mislabeled = Reaction(messageId = "m1", customEmojiContent = catId)
        assertEquals(catId, mislabeled.toggleEmojiId)
        assertNull(mislabeled.toggleContent)
    }

    @Test fun aRowIdIsToldFromAnEmoji() {
        assertTrue(isEmojiRowId(catId))
        assertFalse(isEmojiRowId("\uD83D\uDC4D"))
        assertFalse(isEmojiRowId(":cat:"))
        assertFalse(isEmojiRowId("cat"))
        assertFalse(isEmojiRowId(null))
    }
}
