package lol.mony.harmony

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.repo.MessageMedia
import lol.mony.harmony.data.repo.MessageMediaRepository
import lol.mony.harmony.ui.components.MessageContent
import lol.mony.harmony.ui.screens.chat.Attachment
import lol.mony.harmony.ui.screens.chat.AttachmentState
import lol.mony.harmony.ui.screens.chat.AttachmentStrip
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
 * What an attachment looks like on the way out and once it is in the channel.
 *
 * The composer's strip and the message's own body are drawn from the app's real composables
 * with the shapes the upload actually produces: a file that is still going up, one that was
 * refused, one whose location was removed, and one that is already stored. The captures are the
 * evidence that "attachments work" is not just a repository claim -- a person has to be able to
 * see the file, know what happened to its details, and take it back off the message.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w360dp-h720dp-xhdpi")
class AttachmentRenderTest {
    @get:Rule val compose = createComposeRule()

    private val channelId = "0f2c1c4e-4f2a-4c39-9d5e-1a2b3c4d5e6f"
    private val authId = "11111111-2222-4333-8444-555555555555"
    private val path = "c/$channelId/$authId/a.png"

    /**
     * A real JPEG, drawn by the platform's own encoder, so the strip's thumbnail is the app's
     * preview of actual bytes rather than an empty box. A fixture of zeroes would prove the row
     * lays out and nothing about what a person sees.
     */
    private fun jpegBytes(color: Int, width: Int = 96, height: Int = 96): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // A gradient, so a preview that draws the wrong region is visible as well as a blank one.
        for (y in 0 until height) for (x in 0 until width) {
            val blend = (x * 255 / width)
            bitmap.setPixel(x, y, Color.rgb(Color.red(color) * blend / 255, Color.green(color), Color.blue(color)))
        }
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    private fun picked(name: String = "holiday.png", size: Long = 48_000, bytes: ByteArray? = null): MessageMediaRepository.Picked {
        val mime = when (name.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mov", "mp4" -> "video/quicktime"
            else -> "application/octet-stream"
        }
        val data = bytes ?: when {
            mime.startsWith("image/") -> jpegBytes(Color.rgb(40, 160, 220))
            else -> ByteArray(size.toInt().coerceAtMost(64))
        }
        return MessageMediaRepository.Picked(name, mime, size, data)
    }

    private fun uploading() = Attachment(
        id = "up", original = picked(), picked = picked(),
        state = AttachmentState.Uploading, progress = 0.45f,
    )

    private fun strippedLocation() = Attachment(
        id = "stripped", original = picked("beach.jpg"), picked = picked("beach.jpg", bytes = jpegBytes(Color.rgb(240, 180, 60))),
        state = AttachmentState.Ready, progress = 1f,
        details = lol.mony.harmony.data.repo.MediaMetadata.Details("jpeg", location = true, details = true),
        stripped = true,
        uploaded = MessageMediaRepository.Uploaded(
            path = "c/$channelId/$authId/beach.jpg", url = "https://example.test/sign/beach.jpg",
            fileName = "beach.jpg", mimeType = "image/jpeg", size = 48_000,
        ),
    )

    private fun refused() = Attachment(
        id = "refused", original = picked("huge.mov", 900_000), picked = picked("huge.mov", 900_000),
        state = AttachmentState.Failed("\"huge.mov\" is 879 KB, over this account's 50 KB upload limit."),
    )

    /** A file the person chose to keep the details of: the position really is still in it. */
    private fun keptLocation() = Attachment(
        id = "kept", original = picked("home.jpg"), picked = picked("home.jpg"),
        state = AttachmentState.Ready, progress = 1f,
        details = lol.mony.harmony.data.repo.MediaMetadata.Details("jpeg", location = true, details = true),
        stripped = false, keepDetails = true,
        uploaded = MessageMediaRepository.Uploaded(
            path = "c/$channelId/$authId/home.jpg", url = "https://example.test/sign/home.jpg",
            fileName = "home.jpg", mimeType = "image/jpeg", size = 48_000,
        ),
    )

    /**
     * The strip says what happened to each file, and warns only about a position that is
     * actually still in the bytes being sent.
     *
     * The warning is the whole point of the stripper: a person has to be able to tell "location
     * removed" from "this still says where I live". Saying both about one file is worse than
     * saying neither, because it teaches them the warning means nothing.
     */
    @Test fun aKeptLocationWarnsAndARemovedOneDoesNot() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        AttachmentStrip(listOf(keptLocation()), onRemove = {}, onKeepDetails = { _, _ -> })
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue("a file whose position is still in it is called out",
            compose.onAllNodesWithTag("attachment-location-warning").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("location kept", substring = true)
            .fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun aFailedFileSaysWhatToDoInsteadOfBeingSilentlyDropped() {
        val rows = mutableStateOf(listOf(strippedLocation(), refused()))
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        AttachmentStrip(rows.value, onRemove = {}, onKeepDetails = { _, _ -> })
                    }
                }
            }
        }
        compose.waitForIdle()
        // The row carries the instance's reason, and the strip says what to do about it: Send
        // waits for every file, so a failed one would otherwise hold the message back silently.
        assertTrue(compose.onAllNodesWithText("over this account's", substring = true)
            .fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("attachment-failed-hint").assertExists()

        // And it disappears the moment the failed file is taken off, so it is about the file and
        // not a permanent line in the composer.
        rows.value = listOf(strippedLocation())
        compose.waitForIdle()
        assertTrue("no hint once nothing has failed",
            compose.onAllNodesWithTag("attachment-failed-hint").fetchSemanticsNodes().isEmpty())
    }

    @Test fun aRemovedLocationIsReportedAndNotWarnedAbout() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        AttachmentStrip(listOf(strippedLocation()), onRemove = {}, onKeepDetails = { _, _ -> })
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("location removed", substring = true)
            .fetchSemanticsNodes().isNotEmpty())
        assertTrue("a position that was removed is not warned about",
            compose.onAllNodesWithTag("attachment-location-warning").fetchSemanticsNodes().isEmpty())
    }

    @Test fun theComposerShowsWhatIsGoingUpAndWhatWasRemoved() {
        val rows = listOf(uploading(), strippedLocation(), refused())
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        AttachmentStrip(rows, onRemove = {}, onKeepDetails = { _, _ -> })
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("attachment-up").assertExists()
        compose.onNodeWithTag("attachment-stripped").assertExists()
        compose.onNodeWithTag("attachment-refused").assertExists()
        // The upload reports itself, and the file that lost its position says so in words.
        compose.onNodeWithTag("attachment-progress-up").assertExists()
        compose.onNodeWithTag("attachment-note-stripped").assertExists()
        assertTrue("the stripper's answer is shown, not assumed",
            compose.onAllNodesWithText("location removed", substring = true).fetchSemanticsNodes().isNotEmpty())
        // A refusal is the reason, in the instance's own numbers.
        assertTrue(compose.onAllNodesWithText("over this account's", substring = true)
            .fetchSemanticsNodes().isNotEmpty())
        // And every row can be taken back off the message.
        compose.onNodeWithTag("attachment-remove-up").assertExists()
        compose.onNodeWithTag("attachment-remove-stripped").assertExists()

        // The preview is the picked bytes, not a grey box: Coil decodes off the main thread, so
        // this waits for the blue picture rather than asserting that a tag exists. An empty
        // fixture drew three blank squares once, and the capture looked like a broken app.
        var image: Bitmap? = null
        compose.waitUntil(60_000) {
            image = compose.onRoot().captureToImage().asAndroidBitmap()
            countColour(image!!, Color.rgb(40, 160, 220)) > 200
        }
        assertTrue("the picked photo is previewed in the strip",
            countColour(image!!, Color.rgb(40, 160, 220)) > 200)
        assertTrue("and the second file's own picture is drawn, not the first one's",
            countColour(image!!, Color.rgb(240, 180, 60)) > 200)
        val out = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"),
            "attachment-composer.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { image!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** How many pixels are within a few steps of [wanted], so a JPEG's own noise does not hide it. */
    private fun countColour(image: Bitmap, wanted: Int): Int {
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = image.getPixel(x, y)
            if (kotlin.math.abs(Color.red(pixel) - Color.red(wanted)) < 24 &&
                kotlin.math.abs(Color.green(pixel) - Color.green(wanted)) < 24 &&
                kotlin.math.abs(Color.blue(pixel) - Color.blue(wanted)) < 24
            ) count++
        }
        return count
    }

    /**
     * A private attachment draws from the URL its `path` was signed to, and says so when that
     * URL is not available -- it never falls back to the expired `url` the part also carries.
     */
    @Test fun aStoredAttachmentDrawsFromItsSignedPath() {
        val png = markerPng()
        val part = MessageMedia.filePart(
            path = path, url = "https://example.test/expired.png", fileName = "a.png",
            mimeType = "image/png", size = 3, altText = "a bright block",
        )
        val message = Message(
            id = "m1", createdAt = "2026-11-13T05:39:18+00:00",
            content = listOf(ContentPart.text("look at this"), part),
        )
        val signed = mutableStateOf<String?>(null)
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        MessageContent(message, 16.0, baseUrl = "https://example.test",
                            signedUrl = { signed.value })
                    }
                }
            }
        }
        compose.waitForIdle()
        // Before the URL arrives the row says what it is waiting for rather than showing a
        // broken picture from the part's own expired URL.
        compose.onNodeWithTag("attachment-private-$path").assertExists()

        signed.value = png.toURI().toString()
        compose.onNodeWithTag("attachment-image").assertExists()

        // Coil decodes off the main thread, so the picture arrives a frame or two later; the
        // assertion is on the pixels, so an image view that never resolves fails here instead
        // of passing on the tag alone.
        var image: Bitmap? = null
        compose.waitUntil(60_000) {
            image = compose.onRoot().captureToImage().asAndroidBitmap()
            hasMarker(image!!)
        }
        assertTrue("the attachment's picture painted", hasMarker(image!!))
        val out = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"),
            "attachment-message.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { image!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the attachment drew in $out", hasMarker(image!!))
    }

    /** A solid block of one colour, so a drawn pixel is unmistakable. */
    private fun markerPng(): File {
        val file = File.createTempFile("attachment", ".png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(0, 200, 120))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun hasMarker(image: Bitmap): Boolean {
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = image.getPixel(x, y)
            if (Color.green(pixel) > 180 && Color.red(pixel) < 60 && Color.blue(pixel) in 90..150) return true
        }
        return false
    }
}
