package lol.mony.harmony

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.Emoji
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.model.Reaction
import lol.mony.harmony.ui.components.MessageContent
import lol.mony.harmony.ui.components.MessageReactions
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A custom emoji draws as its **picture**, on a message and on a reaction pill.
 *
 * The reported bug was that custom emoji did not show at all: a reaction pill drew the name or the
 * id its row stored as text, and an emoji part whose url did not resolve drew nothing. Both are
 * assertions on pixels here, because a test that only checks the tree would pass on a pill showing
 * `:blobcat:` and would pass on an `AsyncImage` that never resolved.
 *
 * The pictures are `file://` URIs to a PNG this test writes, so nothing depends on a network the
 * JVM runner does not have.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class EmojiPillRenderTest {
    @get:Rule val compose = createComposeRule()

    private val emojiId = "3f2a9c1e-1111-4222-8333-444455556666"

    /** A solid block of one colour, so a drawn pixel is unmistakable. */
    private fun markerPng(): File {
        val file = File.createTempFile("emoji-pill", ".png")
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(255, 0, 170))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private val message = Message(
        id = "m1", createdAt = "2026-11-13T05:39:18+00:00",
        content = listOf(ContentPart.text("nice")),
    )

    @Test fun aCustomEmojiPillDrawsItsImageNotItsNameOrId() {
        val png = markerPng()
        val emoji = Emoji(id = emojiId, name = "blobcat", url = png.toURI().toString())
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        MessageReactions(
                            message = message,
                            reactions = listOf(
                                Reaction(id = "r1", messageId = "m1", userId = "someone", emojiId = emojiId),
                                Reaction(id = "r2", messageId = "m1", userId = "me", emojiId = emojiId),
                            ),
                            myId = "me",
                            onToggle = {}, onShowReactors = {},
                            emojis = mapOf(emojiId to emoji),
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("reaction-$emojiId").assertExists()

        var image: Bitmap? = null
        compose.waitUntil(60_000) {
            image = compose.onRoot().captureToImage().asAndroidBitmap()
            hasMarker(image!!)
        }
        write("reaction-emoji-image.png", image!!)
        assertTrue("the pill's own picture drew", hasMarker(image!!))
        // The id must not be anywhere on the row: it is the label the bug drew.
        assertTrue("the row must not read the emoji's id", !drewText(image!!, emojiId))
    }

    @Test fun aMessageEmojiDrawsItsImage() {
        val png = markerPng()
        val emoji = Emoji(id = emojiId, name = "blobcat", url = png.toURI().toString())
        val withEmoji = message.copy(content = listOf(
            ContentPart.text("shipping "),
            ContentPart(type = "emoji", emoji = emoji),
            ContentPart.text(" today"),
        ))
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface { Column(Modifier.fillMaxWidth().padding(12.dp)) { MessageContent(withEmoji, 18.0) } }
            }
        }
        compose.onNodeWithTag("emoji-blobcat").assertExists()
        var image: Bitmap? = null
        compose.waitUntil(60_000) {
            image = compose.onRoot().captureToImage().asAndroidBitmap()
            hasMarker(image!!)
        }
        write("message-emoji-pill.png", image!!)
        assertTrue("the message's own emoji picture drew", hasMarker(image!!))
    }

    /**
     * An emoji part whose row this client could not read is drawn as the shortcode it stores.
     *
     * This is the other half of the bug: an `AsyncImage` whose url does not resolve paints
     * nothing, so the emoji used to be a hole in the sentence.
     */
    @Test fun anEmojiWhosePictureCannotLoadDrawsItsShortcode() {
        val unreachable = message.copy(content = listOf(
            ContentPart.text("hi "),
            ContentPart(type = "emoji", emoji = Emoji(name = "blobcat", url = "file:///nonexistent/blobcat.png")),
        ))
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface { Column(Modifier.fillMaxWidth().padding(12.dp)) { MessageContent(unreachable, 18.0) } }
            }
        }
        // The picture cannot load, so the emoji must become the shortcode. Waiting for that text
        // is the assertion: a body that drew nothing where the emoji belongs would time out here
        // instead of passing on the surrounding sentence, which is what a bare `nonBackground`
        // count over the whole capture would have done.
        compose.waitUntil(60_000) {
            compose.onAllNodesWithText(":blobcat:", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("emoji-blobcat").assertExists()
        val image: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        write("message-emoji-fallback.png", image)
        assertTrue("the fallback shortcode drew something", nonBackground(image) > 100)
    }

    /**
     * A `:shortcode:` typed into the text draws that emoji's picture.
     *
     * This client has no picker, so this is how a custom emoji is actually used -- and the
     * reference draws the picture for it. The bug drew the name, so the sentence read
     * `shipping :blobcat: today` where the emoji belonged.
     */
    @Test fun aShortcodeTypedIntoTheTextDrawsItsEmoji() {
        val png = markerPng()
        val emoji = Emoji(id = emojiId, name = "blobcat", url = png.toURI().toString())
        val typed = message.copy(content = listOf(ContentPart.text("shipping :blobcat: today")))
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        MessageContent(typed, 18.0, emojis = mapOf(emojiId to emoji))
                    }
                }
            }
        }
        compose.onNodeWithTag("text-emoji-blobcat").assertExists()
        var image: Bitmap? = null
        compose.waitUntil(60_000) {
            image = compose.onRoot().captureToImage().asAndroidBitmap()
            hasMarker(image!!)
        }
        write("message-text-emoji.png", image!!)
        assertTrue("the typed shortcode drew its emoji's picture", hasMarker(image!!))
    }

    /** A reaction whose emoji row was not read still says something a person can read. */
    @Test fun aPillWithNoRowDrawsTheShortcodeTheReactionStores() {        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        MessageReactions(
                            message = message,
                            reactions = listOf(Reaction(id = "r1", messageId = "m1", userId = "me",
                                customEmojiContent = ":blobcat:", emojiId = emojiId)),
                            myId = "me", onToggle = {}, onShowReactors = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("reaction-$emojiId").assertExists()
        // The shortcode is drawn inside the pill, not the uuid the row is keyed by.
        compose.onNodeWithTag("reaction-emoji-$emojiId", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(":blobcat:", substring = true, useUnmergedTree = true).assertExists()
        val image: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        write("reaction-emoji-fallback.png", image)
        assertTrue("the pill drew its shortcode", nonBackground(image) > 100)
    }

    private fun write(name: String, bitmap: Bitmap) {
        val out = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"), name)
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** True when a pixel of the marker colour is in the bitmap. */
    private fun hasMarker(bitmap: Bitmap): Boolean {
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.red(pixel) > 200 && Color.green(pixel) < 80 && Color.blue(pixel) in 130..210) return true
        }
        return false
    }

    /** Pixels that are not the surface's own background, the cheapest proof that something drew. */
    private fun nonBackground(bitmap: Bitmap): Int {
        val background = bitmap.getPixel(0, 0)
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) != background) count++
        }
        return count
    }

    /**
     * True when the bitmap holds a horizontal run of ink long enough to be the given text.
     *
     * A cheap stand-in for OCR: a uuid is 36 characters, so if it were drawn it would be a wide
     * band of non-background pixels on one line. The pill itself is a few pixels tall, so a wide
     * run is the failure the bug produced.
     */
    private fun drewText(bitmap: Bitmap, text: String): Boolean {
        val background = bitmap.getPixel(0, 0)
        val threshold = (text.length * 4)
        for (y in 0 until bitmap.height) {
            var run = 0
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != background) run++ else run = 0
                if (run > threshold) return true
            }
        }
        return false
    }
}
