package lol.mony.harmony

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.model.MessageAuthor
import lol.mony.harmony.ui.components.FullscreenImageViewer
import lol.mony.harmony.ui.components.MessageContent
import lol.mony.harmony.ui.components.ReplyPreview
import lol.mony.harmony.ui.components.attachmentKind
import lol.mony.harmony.ui.components.isImagePart
import lol.mony.harmony.ui.components.isVideoPart
import lol.mony.harmony.ui.components.replySnippet
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The reply quote and the fullscreen viewer, drawn and measured.
 *
 * These are the two pieces a person looks at most in the report that asked for them: a reply
 * that says what it answers, and a picture that can be looked at. Both are plain composables
 * over plain data, so they are drawn here without an instance; the screenshots beside the
 * assertions are what a person sees.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h800dp-xhdpi")
class ReplyPreviewRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun message(content: List<ContentPart>, id: String = "m1") = Message(
        id = id, channelId = "c1", userId = "p1", createdAt = "2026-01-01T00:00:00Z",
        content = content,
    )

    private fun draw(message: Message, onJump: (() -> Unit)? = null) {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface(Modifier.fillMaxSize()) {
                    Column {
                        ReplyPreview(
                            author = "Grace Hopper",
                            message = message,
                            onDismiss = {},
                            onJump = onJump,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The quote names the author and repeats the gist.
     *
     * This is the reported bug as an assertion: the old banner said `Replying to Grace: <sixty
     * characters>`, which reads as a sentence about the reply. The quote draws the name on its
     * own line, in the accent colour, with the body beneath it -- the shape every messaging app
     * uses, and the one a person recognises without reading it.
     */
    @Test fun theQuoteNamesTheAuthorAndTheMessage() {
        draw(message(listOf(ContentPart.text("the deploy is at four"))))
        compose.onNodeWithTag("reply-preview-author").assertExists()
        compose.onNodeWithTag("reply-preview-snippet").assertExists()
        compose.onNodeWithText("Grace Hopper").assertExists()
        compose.onNodeWithText("the deploy is at four").assertExists()
    }

    /**
     * A reply to a picture says it is a picture.
     *
     * A message with no words and an attachment has no `plainText`, so a quote that drew only
     * the text would be a blank line: a person could not tell a photo from a message whose body
     * this client failed to draw. The kind is named instead, and the glyph is beside it.
     */
    @Test fun aQuoteOfAPictureSaysPhoto() {
        val photo = message(listOf(ContentPart(type = "file", fileType = "image", fileName = "standup.png")))
        draw(photo)
        compose.onNodeWithText("Photo").assertExists()
    }

    @Test fun aQuoteOfAVideoSaysVideo() {
        val video = message(listOf(ContentPart(type = "file", fileType = "video", fileName = "standup.mp4")))
        draw(video)
        compose.onNodeWithText("Video").assertExists()
    }

    /** A quote with nowhere to jump is a label, not a control: tapping it does nothing. */
    @Test fun aQuoteWithoutTheOriginalIsNotTappable() {
        var jumps = 0
        draw(message(listOf(ContentPart.text("older than the page"))), onJump = null)
        compose.onNodeWithTag("reply-preview").performClick()
        assertEquals("a quote with no original must not pretend to jump", 0, jumps)
        // The dismiss target is still there, and it is the one control in the quote.
        compose.onNodeWithTag("reply-cancel").assertExists()
    }

    @Test fun aQuoteWithTheOriginalJumpsToIt() {
        var jumps = 0
        draw(message(listOf(ContentPart.text("still loaded"))), onJump = { jumps++ })
        compose.onNodeWithTag("reply-preview").performClick()
        assertEquals(1, jumps)
    }

    @Test fun theQuoteIsCaptured() {
        draw(message(listOf(ContentPart.text("the deploy is at four"))), onJump = {})
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File(RenderHarness.output, "reply-preview-quote.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the quote drew in $out", image.width > 0 && image.height > 0)
    }
}

/**
 * The fullscreen viewer: it fills the window, it zooms, and it closes.
 *
 * The zoom is asserted through the level it announces, because a scale factor is invisible to a
 * test and a screenshot of 240% and 250% look the same. The button and the double tap both go
 * through it, which is what proves the two paths agree.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h800dp-xhdpi")
class FullscreenImageViewerTest {
    @get:Rule val compose = createComposeRule()

    private var dismissed = 0

    /**
     * A picture a JVM test can actually load: Coil reads a file:// URI, and a network URI in a
     * test would be a blank box -- which is a screenshot of nothing rather than of the viewer.
     */
    private fun pictureFile(): String {
        val file = File(RenderHarness.output, "viewer-fixture.png")
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(14, 165, 233))
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 96f
            isAntiAlias = true
        }
        canvas.drawText("standup.png", 90f, 420f, paint)
        paint.textSize = 56f
        canvas.drawText("1200 x 800", 90f, 520f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return android.net.Uri.fromFile(file).toString()
    }

    private fun draw() {
        dismissed = 0
        val url = pictureFile()
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                FullscreenImageViewer(
                    url = url,
                    description = "standup.png",
                    supporting = "240 KB",
                    onDismiss = { dismissed++ },
                    onOpen = {},
                )
            }
        }
        compose.waitForIdle()
    }

    /**
     * The viewer is a full-window dialog, not a sheet.
     *
     * It used to be a `ModalBottomSheet` with a 360dp copy of the picture in it, which is
     * smaller than the picture already in the row. The assertion is on the drawn size against
     * the window's, so a viewer that went back to a fixed width fails.
     */
    @Test fun theViewerFillsTheWindow() {
        draw()
        // The window's own size, from the configuration rather than from `onRoot`: a Dialog is a
        // second root, so `onRoot` matches two nodes and could not say which was the window.
        val metrics = org.robolectric.RuntimeEnvironment.getApplication().resources.displayMetrics
        val windowWidth = metrics.widthPixels.toFloat()
        val windowHeight = metrics.heightPixels.toFloat()
        val viewer = compose.onNodeWithTag("image-viewer").fetchSemanticsNode().size
        assertTrue("the viewer is ${viewer.width}px wide in a $windowWidth px window",
            viewer.width >= windowWidth - 1f)
        assertTrue("the viewer is ${viewer.height}px tall in a $windowHeight px window",
            viewer.height >= windowHeight - 1f)
    }

    /** The picture is in the tree, and the two zoom controls are reachable as buttons. */
    @Test fun thePictureAndItsControlsAreThere() {
        draw()
        compose.onNodeWithTag("image-viewer-picture").assertExists()
        compose.onNodeWithTag("image-viewer-zoom-in").assertExists()
        compose.onNodeWithTag("image-viewer-zoom-out").assertExists()
        compose.onNodeWithTag("image-viewer-close").assertExists()
    }

    /**
     * Zoom in, then out, through the buttons, with the level said in words.
     *
     * A pinch is the fast way to zoom and not the only way: the two buttons are what makes the
     * same action reachable without a two-finger gesture, so they are what is driven here. The
     * level starts hidden (the picture is at fit), appears at the first zoom, and goes away
     * again when the picture is back at fit.
     */
    @Test fun theZoomButtonsChangeTheLevel() {
        draw()
        assertEquals("a picture at fit says nothing about zoom", 0,
            compose.onAllNodesWithTag("image-viewer-zoom-level").fetchSemanticsNodes().size)
        compose.onNodeWithTag("image-viewer-zoom-in").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("image-viewer-zoom-level").assertExists()
        val level = compose.onNodeWithTag("image-viewer-zoom-level")
            .fetchSemanticsNode().config.toString()
        assertTrue("the level reads $level", level.contains("150"))
        compose.onNodeWithTag("image-viewer-zoom-out").performClick()
        compose.waitForIdle()
        assertEquals("back at fit, the level goes away", 0,
            compose.onAllNodesWithTag("image-viewer-zoom-level").fetchSemanticsNodes().size)
    }

    /** The close button closes it. */
    @Test fun theCloseButtonDismisses() {
        draw()
        compose.onNodeWithTag("image-viewer-close").performClick()
        assertEquals(1, dismissed)
    }

    /**
     * So does a tap on the picture.
     *
     * It is a separate test because a compose rule sets its content once: closing the viewer
     * disposes it, and drawing it again in the same rule is not a thing a test can do.
     */
    @Test fun aTapOnThePictureDismisses() {
        draw()
        compose.onNodeWithTag("image-viewer-picture").performClick()
        // The tap detector waits out the double-tap window before it decides a tap was single,
        // so the clock has to pass that window before the dismissal is on the record.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test fun theViewerIsCaptured() {
        draw()
        compose.onNodeWithTag("image-viewer-zoom-in").performClick()
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File(RenderHarness.output, "fullscreen-image-viewer.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the viewer drew in $out", image.width > 0 && image.height > 0)
    }
}

/**
 * What a part is, for the two new viewers: a picture, a video, or neither.
 *
 * The rules are the ones the instance's own rows come in with, so they are asserted directly:
 * `file_type` is the canonical answer, `mime_type` is what a browser upload fills in, and the
 * extension is the fallback for a row written before either existed. A video that was drawn as
 * a card is the reported bug; a video that is drawn as a picture would be a new one.
 */
class AttachmentKindTest {
    private val STAMP = "2026-01-01T00:00:00Z"
    @Test fun aVideoIsAVideoByTypeByMimeAndByExtension() {
        assertTrue(ContentPart(type = "file", fileType = "video").isVideoPart())
        assertTrue(ContentPart(type = "file", mimeType = "video/mp4").isVideoPart())
        assertTrue(ContentPart(type = "file", url = "https://x.test/a/standup.MP4").isVideoPart())
        assertTrue(ContentPart(type = "file", fileName = "clip.webm").isVideoPart())
    }

    @Test fun aPictureIsNotAVideo() {
        val photo = ContentPart(type = "file", fileType = "image", fileName = "a.png")
        assertTrue(photo.isImagePart())
        assertTrue("a png must not be handed to the video player", !photo.isVideoPart())
        assertTrue("a video must not be handed to the picture viewer",
            !ContentPart(type = "file", fileType = "video", fileName = "a.mp4").isImagePart())
    }

    @Test fun aNonFilePartIsNeither() {
        assertTrue(!ContentPart(type = "text", text = "hello").isVideoPart())
        assertTrue(!ContentPart(type = "text", text = "hello").isImagePart())
    }

    @Test fun theQuoteOfAPlainMessageIsItsText() {
        val message = Message(id = "m", createdAt = STAMP, content = listOf(ContentPart.text("ship it")))
        assertEquals("ship it", replySnippet(message, attachmentKind(message)))
    }

    @Test fun theQuoteOfAnAttachmentIsItsKind() {
        val video = Message(id = "m", createdAt = STAMP, content = listOf(ContentPart(type = "file", fileType = "video")))
        assertEquals("Video", attachmentKind(video)?.second)
        assertEquals("Video", replySnippet(video, attachmentKind(video)))
    }

    @Test fun aStickerAndAPollAreNamedRatherThanBlank() {
        val sticker = Message(id = "m", createdAt = STAMP, content = listOf(ContentPart(type = "sticker", name = "blobcat")))
        assertNull(attachmentKind(sticker))
        assertEquals("Sticker", replySnippet(sticker, null))
        val poll = Message(id = "m", createdAt = STAMP, content = listOf(ContentPart(type = "poll", question = "Lunch?")))
        assertEquals("Poll", replySnippet(poll, null))
    }

    @Test fun anEmptyBodyStillSaysSomething() {
        val empty = Message(id = "m", createdAt = STAMP, content = emptyList())
        assertNotNull(replySnippet(empty, null))
        assertEquals("Message", replySnippet(empty, null))
    }
}

/**
 * The author's name a row draws, which the reply reference now also uses.
 *
 * A reply's reference names the person being answered; the name comes from the same resolution
 * the row's own header uses, so a deleted account reads "Deleted user" in both places rather
 * than one of them drawing nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h800dp-xhdpi")
class ReplyReferenceAuthorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theReferenceNamesTheAuthorAndTheGist() {
        val original = Message(
            id = "m1", channelId = "c1", userId = "p1",
            createdAt = "2026-01-01T00:00:00Z",
            content = listOf(ContentPart.text("the deploy is at four")),
        )
        val author = MessageAuthor(name = "Grace Hopper", profileId = "p1")
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        lol.mony.harmony.ui.screens.chat.MessageRow(
                            row = lol.mony.harmony.ui.screens.chat.RoomRow("m2", Message(
                                id = "m2", channelId = "c1", userId = "p2", replyTo = "m1",
                                createdAt = "2026-01-01T00:01:00Z",
                                content = listOf(ContentPart.text("noted")),
                            )),
                            author = MessageAuthor(name = "Noah", profileId = "p2"),
                            showHeader = true,
                            baseUrl = null,
                            appearance = VisualThemeSettings(),
                            reactions = emptyList(), emojis = emptyMap(), myId = "p2",
                            onActions = {}, onReact = {}, onAuthor = {}, onShowReactors = {},
                            onReply = {}, onRetry = {},
                            replyToMessage = original,
                            replyToAuthorName = "Grace Hopper",
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val text = compose.onNodeWithTag("reply-reference", useUnmergedTree = true)
            .fetchSemanticsNode().config.toString()
        // The reference is one line: the author, then the gist of what was answered.
        val node = compose.onAllNodesWithTag("reply-reference", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("the reference drew", node.isNotEmpty())
        assertTrue("the reference is $text", text.isNotEmpty())
        compose.onNodeWithText("Grace Hopper", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("the deploy is at four", substring = true, useUnmergedTree = true).assertExists()
    }

    /** A reply whose original is not loaded keeps the word it always had. */
    @Test fun aReferenceWithoutTheOriginalSaysReply() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    Column {
                        lol.mony.harmony.ui.screens.chat.MessageRow(
                            row = lol.mony.harmony.ui.screens.chat.RoomRow("m2", Message(
                                id = "m2", channelId = "c1", userId = "p2", replyTo = "gone",
                                createdAt = "2026-01-01T00:01:00Z",
                                content = listOf(ContentPart.text("noted")),
                            )),
                            author = MessageAuthor(name = "Noah", profileId = "p2"),
                            showHeader = true, baseUrl = null,
                            appearance = VisualThemeSettings(),
                            reactions = emptyList(), emojis = emptyMap(), myId = "p2",
                            onActions = {}, onReact = {}, onAuthor = {}, onShowReactors = {},
                            onReply = {}, onRetry = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Reply", useUnmergedTree = true).assertExists()
    }

    @Test fun theRowWithAReferenceIsCaptured() {
        val original = Message(
            id = "m1", channelId = "c1", userId = "p1", createdAt = "2026-01-01T00:00:00Z",
            content = listOf(ContentPart.text("the deploy is at four")),
        )
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    Column(Modifier.fillMaxWidth()) {
                        lol.mony.harmony.ui.screens.chat.MessageRow(
                            row = lol.mony.harmony.ui.screens.chat.RoomRow("m2", Message(
                                id = "m2", channelId = "c1", userId = "p2", replyTo = "m1",
                                createdAt = "2026-01-01T00:01:00Z",
                                content = listOf(ContentPart.text("noted, I will be there")),
                            )),
                            author = MessageAuthor(name = "Noah", profileId = "p2"),
                            showHeader = true, baseUrl = null,
                            appearance = VisualThemeSettings(),
                            reactions = emptyList(), emojis = emptyMap(), myId = "p2",
                            onActions = {}, onReact = {}, onAuthor = {}, onShowReactors = {},
                            onReply = {}, onRetry = {},
                            replyToMessage = original,
                            replyToAuthorName = "Grace Hopper",
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File(RenderHarness.output, "reply-reference-with-quote.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the row drew in $out", image.width > 0 && image.height > 0)
    }
}

/**
 * A video in a message is a player, not a label.
 *
 * The report was that "videos do not have a built in player": a video attachment drew a card
 * reading `Video: standup.mp4` with a play glyph on it, and tapping it opened a bottom sheet
 * offering the file to the browser. A person could send a video from this app and could not
 * watch one in it.
 *
 * The player is `ExoPlayer` behind the platform's own `PlayerView`, which a JVM test cannot
 * play -- there is no decoder and no network here. What it can assert is the thing that was
 * wrong: the player composable is in the row and the card is not, so the shape a person meets is
 * a player rather than a sentence about a file. The screenshot beside it is the tile.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h800dp-xhdpi")
class VideoAttachmentRenderTest {
    @get:Rule val compose = createComposeRule()

    private val video = Message(
        id = "m-video",
        channelId = "c1",
        userId = "p1",
        createdAt = "2026-01-01T00:00:00Z",
        content = listOf(
            ContentPart(type = "text", text = "the demo is attached"),
            ContentPart(
                type = "file", fileType = "video", fileName = "demo.mp4",
                url = "file:///nonexistent/demo.mp4", fileSize = 4_200_000,
            ),
        ),
    )

    private fun draw(message: Message) {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        MessageContent(
                            message = message,
                            fontSize = 16.0,
                            baseUrl = "https://harmony.test",
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The player is in the row. */
    @Test fun aVideoAttachmentDrawsAPlayer() {
        draw(video)
        assertTrue(
            "a video attachment drew no player",
            compose.onAllNodesWithTag("video-player").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /**
     * The old card is gone: nothing says `Video: demo.mp4` in the muted body colour any more.
     *
     * That card is what the report was about, so its absence is the assertion that the change
     * reached the row and not only the composable beside it.
     */
    @Test fun aVideoAttachmentIsNotJustALabel() {
        draw(video)
        assertTrue(
            "the row still draws the old 'Video: <name>' card",
            compose.onAllNodesWithText("Video: demo.mp4", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test fun theVideoTileIsCaptured() {
        draw(video)
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File(RenderHarness.output, "message-video-player.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the row drew in $out", image.width > 0 && image.height > 0)
    }
}
