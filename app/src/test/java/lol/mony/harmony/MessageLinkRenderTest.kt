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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.ui.components.MessageContent
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * What a message with a link in it draws, and what a tap on that link does.
 *
 * The bug this pins: a URL in a message was plain text with nothing behind it -- a person could
 * read the address and not follow it -- and a link the instance had previewed drew the sentence
 * "Link preview: <url>" instead of a card. So there are three things asserted here, and each one
 * fails without its half of the fix:
 *
 * - the link is on screen as a link (and is drawn, not merely in the tree: the capture is
 *   checked for ink);
 * - tapping it calls back with the URL, which is what the room then hands to the platform;
 * - a message whose `metadata.embeds` carries a card draws that card's site, title and
 *   description, and tapping the card opens the same URL.
 *
 * The fourth case is the one that keeps the fix honest about safety: a `javascript:` URL is named
 * and is not tappable, so a message can never hand a script URL to the platform.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w360dp-h720dp-xhdpi")
class MessageLinkRenderTest {
    @get:Rule val compose = createComposeRule()

    /** A solid block of one colour, so a drawn thumbnail is unmistakable. */
    private fun markerPng(): File {
        val file = File.createTempFile("card", ".png")
        val bitmap = Bitmap.createBitmap(64, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(60, 130, 246))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun message(json: String): Message =
        HarmonyJson.decodeFromString(Message.serializer(), json)

    /** A message whose body is prose and a `url` part, with the card the instance stored for it. */
    private fun linkedMessage(image: String?) = message(
        """{"id":"m1","channel_id":"c1","user_id":"u1","created_at":"2026-11-13T05:39:18+00:00",
           "content":[{"type":"text","text":"look at this "},
                      {"type":"url","url":"https://example.test/post","preview":true}],
           "metadata":{"embeds":{"https://example.test/post":{
             "url":"https://example.test/post","normalizedUrl":"https://example.test/post",
             "provider":"generic","title":"A post about links",
             "description":"What a card should look like.",
             "siteName":"Example","icon":null${if (image != null) ",\"image\":\"$image\"" else ""},
             "color":"#3b82f6",
             "fetchedAt":"2026-11-13T05:39:00+00:00","expiresAt":"2026-11-14T05:39:00+00:00"}}}}""",
    )

    private fun draw(text: String, image: String?, onOpen: (String) -> Unit = {}) {
        val row = linkedMessage(image)
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        MessageContent(row, 15.0, baseUrl = "https://example.test", onOpenLink = onOpen)
                        MessageContent(
                            message("""{"id":"m2","channel_id":"c1","user_id":"u1","created_at":"2026-11-13T05:40:18+00:00",
                                "content":[{"type":"text","text":"$text"}]}"""),
                            15.0, baseUrl = "https://example.test", onOpenLink = onOpen,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String): Bitmap {
        val out = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"), name)
        out.parentFile?.mkdirs()
        val image: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var drawn = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image.getPixel(x, y) != image.getPixel(0, 0)) drawn++
        }
        assertTrue("the message drew something in $out", drawn > 2_000)
        return image
    }

    /** A pixel of the card's own picture, so a thumbnail that never loaded fails the capture. */
    private fun drewPicture(image: Bitmap, rgb: Int): Boolean {
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = image.getPixel(x, y)
            if (Color.red(pixel) == Color.red(rgb) && Color.green(pixel) == Color.green(rgb) &&
                Color.blue(pixel) == Color.blue(rgb)) return true
        }
        return false
    }

    @Test fun aLinkAndItsCardDrawAndBothOpenTheUrl() {
        var opened: String? = null
        draw("a bare https://example.test/bare link", markerPng().toURI().toString(), onOpen = { opened = it })

        // The link itself, and the card the instance stored for it.
        compose.onNodeWithTag("link-m1").assertExists()
        compose.onNodeWithTag("embed-card-m1", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("A post about links", substring = true).fetchSemanticsNodes().let {
            assertTrue("the card's title is on screen", it.isNotEmpty())
        }
        compose.onAllNodesWithText("Example", substring = true).fetchSemanticsNodes().let {
            assertTrue("the card's site is on screen", it.isNotEmpty())
        }
        compose.onAllNodesWithText("What a card should look like.", substring = true).fetchSemanticsNodes().let {
            assertTrue("the card's description is on screen", it.isNotEmpty())
        }
        // Coil decodes off the main thread: the picture arrives a frame or two after the card.
        var shot: Bitmap? = null
        compose.waitUntil(60_000) {
            shot = compose.onRoot().captureToImage().asAndroidBitmap()
            drewPicture(shot!!, Color.rgb(60, 130, 246))
        }
        val out = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"),
            "message-links.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { shot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the card's own thumbnail drew in $out", drewPicture(shot!!, Color.rgb(60, 130, 246)))

        compose.onNodeWithTag("embed-card-m1", useUnmergedTree = true).performClick()
        assertEquals("the card opens its own destination", "https://example.test/post", opened)
        opened = null
        compose.onNodeWithTag("link-m1").performClick()
        assertEquals("the link opens what it names", "https://example.test/post", opened)
    }

    /**
     * A message stored as one `text` part by another client: its URL still has to be a link.
     *
     * The link is an annotation *inside* the sentence, not a box beside it -- which is what makes
     * the words around it wrap through it the way the reference's `<a>` does -- so the tap goes
     * at the URL's own characters rather than at a node of its own.
     */
    @Test fun aBareUrlInsideATextPartIsALink() {
        var opened: String? = null
        draw("https://example.test/typed is in the prose", null, onOpen = { opened = it })

        compose.onNodeWithTag("text-link-https://example.test/typed").assertExists()
        // The link leads the sentence, so a tap at its first character is a tap on the link and
        // on nothing else. (Where the link *is* inside a longer sentence is `linkSpans`' rule,
        // asserted without a screen in `MessageLinkPartsTest`.)
        compose.onNodeWithTag("text-link-https://example.test/typed").performTouchInput {
            click(Offset(2f, height / 2f))
        }
        assertEquals("https://example.test/typed", opened)
        capture("message-link-in-text.png")
    }

    @Test fun aCardWithoutAPictureStillDrawsItsText() {
        draw("look at https://example.test/post", null)
        compose.onNodeWithTag("embed-card-m1", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("A post about links", substring = true).fetchSemanticsNodes().let {
            assertTrue("a card with no picture still names itself", it.isNotEmpty())
        }
        capture("message-embed-text-only.png")
    }

    /**
     * A GIF page's picture is the card, with the text under it, and its favicon sits on a chip.
     *
     * This is the reference's `--media` shape and its `.link-embed-card__icon` rule, which exists
     * because a favicon is usually a dark glyph on transparency and would vanish on the app's
     * dark theme. The capture is the check: a card that drew its text and skipped both would
     * still pass a test that only looked for the tags.
     */
    @Test fun aGifPageDrawsItsPictureAsTheCard() {
        // A 16:9 frame, the shape a GIF page's content has, so the capture shows the media card
        // at its own aspect rather than a square.
        val marker = File.createTempFile("gif", ".png").also { file ->
            val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(60, 130, 246))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // A favicon that is *dark*, so the white chip is what makes it visible at all.
        val icon = File.createTempFile("favicon", ".png").also { file ->
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(16, 16, 16))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val row = message(
            """{"id":"g1","channel_id":"c1","user_id":"u1","created_at":"2026-11-13T05:44:18+00:00",
               "content":[{"type":"url","url":"https://tenor.test/view/cat","preview":true}],
               "metadata":{"embeds":{"https://tenor.test/view/cat":{
                 "url":"https://tenor.test/view/cat","provider":"generic","title":"a dancing cat",
                 "siteName":"Tenor","mediaOnly":true,
                 "image":"${marker.toURI()}","icon":"${icon.toURI()}"}}}}""",
        )
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface { Column(Modifier.fillMaxWidth().padding(12.dp)) { MessageContent(row, 15.0) } }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("embed-card-g1", useUnmergedTree = true).assertExists()
        // The picture is the card, so it is the full width and the text is beneath it.
        var shot: Bitmap? = null
        compose.waitUntil(60_000) {
            shot = compose.onRoot().captureToImage().asAndroidBitmap()
            drewPicture(shot!!, Color.rgb(60, 130, 246))
        }
        val image = compose.onNodeWithTag("embed-image", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val title = compose.onNodeWithTag("embed-title", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("the picture is above the title, not beside it", image.bottom <= title.top + 1.dp)
        // Far wider than the 80dp square a side thumbnail takes: the picture *is* the card.
        assertTrue("a media card's picture takes the card's width",
            (image.right - image.left) > 200.dp)
        // The site is named under the picture, as the reference's media card does.
        compose.onAllNodesWithText("Tenor", substring = true).fetchSemanticsNodes().let {
            assertTrue("the media card names its site", it.isNotEmpty())
        }
        capture("message-embed-media.png")
    }

    @Test fun aScriptUrlIsNamedAndNotTappable() {
        var opened: String? = null
        val row = message(
            """{"id":"m3","channel_id":"c1","user_id":"u1","created_at":"2026-11-13T05:41:18+00:00",
               "content":[{"type":"url","url":"javascript:alert(1)","preview":false}]}""",
        )
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface { Column(Modifier.fillMaxWidth().padding(12.dp)) { MessageContent(row, 15.0, onOpenLink = { opened = it }) } }
            }
        }
        compose.waitForIdle()
        // The URL is still read, so a person can see what the message says.
        compose.onAllNodesWithText("javascript:alert(1)", substring = true).fetchSemanticsNodes().let {
            assertTrue("an unsafe URL is named", it.isNotEmpty())
        }
        // ...but nothing was drawn as a link, and nothing can be tapped into the platform. The
        // link tag marks a *link*, so an unsafe URL must not have it: a tag on something that
        // cannot be tapped would let a test -- and a reader of one -- believe it could be.
        assertTrue(compose.onAllNodesWithTag("link-m3").fetchSemanticsNodes().isEmpty())
        assertEquals(null, opened)
    }

    /**
     * A suppressed embed (`metadata.suppress_embeds`) draws its link and no card.
     *
     * This is the author's or a moderator's choice, and the stored preview stays so it can be
     * turned back on -- so the card has to be filtered at draw time, not deleted on read.
     */
    @Test fun aSuppressedMessageDrawsItsLinkAndNoCard() {
        val row = message(
            """{"id":"m4","channel_id":"c1","user_id":"u1","created_at":"2026-11-13T05:42:18+00:00",
               "content":[{"type":"url","url":"https://example.test/post","preview":true}],
               "metadata":{"suppress_embeds":true,
                 "embeds":{"https://example.test/post":{"url":"https://example.test/post","title":"A post about links"}}}}""",
        )
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface { Column(Modifier.fillMaxWidth().padding(12.dp)) { MessageContent(row, 15.0) } }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("link-m4").assertExists()
        assertTrue("a suppressed message draws no card",
            compose.onAllNodesWithTag("embed-card-m4", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("A post about links", substring = true).fetchSemanticsNodes().isEmpty())
    }

    /** An `embed` part, the shape the instance stores for a card it built itself. */
    @Test fun anEmbedPartDrawsItsCard() {
        val row = message(
            """{"id":"m5","channel_id":"c1","user_id":"u1","created_at":"2026-11-13T05:43:18+00:00",
               "content":[{"type":"embed","url":"https://example.test/posts/2","provider":"harmony-post",
                           "previewId":"https://example.test/posts/2"}],
               "metadata":{"embeds":{"https://example.test/posts/2":{
                 "url":"https://example.test/posts/2","provider":"harmony-post","title":"Carol",
                 "description":"Hello from the other instance.","siteName":"example.test"}}}}""",
        )
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface { Column(Modifier.fillMaxWidth().padding(12.dp)) { MessageContent(row, 15.0) } }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("embed-card-https://example.test/posts/2", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("Carol", substring = true).fetchSemanticsNodes().let {
            assertTrue("the embedded post's title is on screen", it.isNotEmpty())
        }
    }
}
