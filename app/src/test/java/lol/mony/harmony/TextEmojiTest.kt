package lol.mony.harmony

import lol.mony.harmony.data.model.Emoji
import lol.mony.harmony.ui.components.TextToken
import lol.mony.harmony.ui.components.emojiNamed
import lol.mony.harmony.ui.components.textTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `:shortcode:` a person typed into a message draws that emoji, not its name.
 *
 * This is the shape the reported bug took most often: this client has no emoji picker, so a
 * custom emoji is used by typing `:blobcat:`, and the reference draws blobcat's picture for it
 * (`renderMarkdownToHTML`'s `emoji` token). The app drew the name, so the emoji "did not show"
 * and a long `:name:` sat where it belonged.
 *
 * The tokenizer is a pure function, so the rules are asserted without a screen.
 */
class TextEmojiTest {

    private fun names(text: String) = textTokens(text).filterIsInstance<TextToken.Shortcode>().map { it.name }

    private fun literals(text: String) = textTokens(text).filterIsInstance<TextToken.Literal>().map { it.text }

    @Test fun aShortcodeInASentenceIsAnEmojiToken() {
        val tokens = textTokens("shipping :blobcat: today")
        assertEquals(listOf("shipping ", " today"), literals("shipping :blobcat: today"))
        assertEquals(listOf("blobcat"), tokens.filterIsInstance<TextToken.Shortcode>().map { it.name })
    }

    @Test fun theTokensCoverTheWholeText() {
        // Every character is in exactly one token, so nothing a person typed is dropped.
        val text = "a :cat: b :dog: c"
        val rebuilt = textTokens(text).joinToString("") {
            when (it) {
                is TextToken.Literal -> it.text
                is TextToken.Shortcode -> ":" + it.name + ":"
                is TextToken.Link -> it.url
            }
        }
        assertEquals(text, rebuilt)
    }

    @Test fun aBridgedEmojiIsOneToken() {
        // `:discord:a:party:1234567890123456789:` -- the reference's Discord token, whose own
        // colons are not delimiters.
        assertEquals(listOf("discord:a:party:1234567890123456789"),
            names("yay :discord:a:party:1234567890123456789:!"))
    }

    @Test fun aCodeSpanIsLeftAlone() {
        // `` `:cat:` `` is how a person writes about a shortcode rather than using one.
        assertEquals(emptyList<String>(), names("write `:cat:` to use it"))
        assertTrue(literals("write `:cat:` to use it").single().contains(":cat:"))
    }

    @Test fun textWithNoShortcodeIsOneLiteral() {
        assertEquals(listOf("just words"), literals("just words"))
        assertEquals(emptyList<String>(), names("a:b and 10:30 and : spaced :"))
        assertEquals(emptyList<TextToken>(), textTokens(""))
    }

    @Test fun theDisambiguatorIsPartOfTheToken() {
        // Two custom emoji may share a name; `~N` picks the Nth, as the reference writes it.
        assertEquals(listOf("blobcat~2"), names(":blobcat~2:"))
    }

    @Test fun aNameTheInstanceHasIsDrawnAsItsEmoji() {
        val row = Emoji(id = "e1", name = "blobcat", url = "https://example.test/storage/v1/object/public/emojis/b.png")
        val emojis = mapOf("e1" to row)
        assertEquals(row, emojiNamed(emojis, "blobcat"))
        // A row may store its name with the colons a shortcode carries; it still answers to the
        // bare name, and the row is drawn as it was stored.
        assertEquals("e1", emojiNamed(mapOf("e1" to row.copy(name = ":blobcat:")), "blobcat")?.id)
        assertEquals("e1", emojiNamed(emojis, "blobcat~2")?.id)
    }

    @Test fun aNameNothingAnswersToStaysText() {
        // The reference renders the literal `:name:` when its resolver has nothing, rather than
        // leaving a hole; so a name this instance does not have must resolve to nothing.
        assertNull(emojiNamed(emptyMap(), "blobcat"))
    }

    @Test fun aBridgedNameNeedsNoRow() {
        // The identifier carries Discord's snowflake, so its picture is known without a lookup.
        val bridged = emojiNamed(emptyMap(), "discord:party:1234567890123456789")
        assertEquals("https://cdn.discordapp.com/emojis/1234567890123456789.png", bridged?.url)
    }
}
