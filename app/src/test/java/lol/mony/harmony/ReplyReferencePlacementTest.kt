package lol.mony.harmony

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.model.MessageAuthor
import lol.mony.harmony.ui.screens.chat.MessageRow
import lol.mony.harmony.ui.screens.chat.RoomRow
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
 * Where a reply's reference is drawn in a message row.
 *
 * A row that answers something stacks it the way a messaging app does: the reply reference
 * first, then the author's name, then the message. It used to sit *under* the name and directly
 * above the body, where it read as the first line of the message rather than as the context the
 * message answers. This measures the drawn order, so putting the reference back between the name
 * and the body fails it; the capture beside it is what a person sees.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w360dp-h800dp-xhdpi")
class ReplyReferencePlacementTest {
    @get:Rule val compose = createComposeRule()

    private val author = MessageAuthor(name = "Noah", profileId = "p1")

    /** One row from that author, answering an earlier message unless [replyTo] is null. */
    private fun row(replyTo: String? = "m1"): RoomRow = RoomRow(
        key = "m2",
        message = Message(
            id = "m2",
            channelId = "c1",
            userId = "p1",
            replyTo = replyTo,
            createdAt = "2026-01-01T00:01:00Z",
            content = listOf(ContentPart.text("the answer to the first one")),
        ),
    )

    private fun draw(row: RoomRow) {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    Column(Modifier.fillMaxWidth()) {
                        MessageRow(
                            row = row, author = author, showHeader = true, baseUrl = null,
                            appearance = VisualThemeSettings(), reactions = emptyList(), emojis = emptyMap(),
                            myId = "p1",
                            onActions = {}, onReact = {}, onAuthor = {}, onShowReactors = {},
                            onReply = {}, onRetry = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun theReplyReferenceSitsAboveTheAuthorNameAndTheBody() {
        draw(row())

        val reference = compose.onNodeWithTag("reply-reference", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val name = compose.onNodeWithTag("message-author-name", useUnmergedTree = true).getUnclippedBoundsInRoot()
        // The row and its text part share the `message-<id>` tag, so the body is found by its text.
        val body = compose.onNodeWithText("the answer to the first one", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "the reply reference ends at ${reference.bottom}, past the name's top at ${name.top}: " +
                "it is drawn under the author's name instead of above it",
            reference.bottom <= name.top,
        )
        assertTrue(
            "the author's name ends at ${name.bottom}, past the body's top at ${body.top}",
            name.bottom <= body.top,
        )
    }

    /** A row that answers nothing draws no reference at all. */
    @Test fun aPlainRowDrawsNoReference() {
        draw(row(replyTo = null))
        assertEquals(0, compose.onAllNodesWithTag("reply-reference", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun theStackIsCaptured() {
        draw(row())
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"),
            "reply-reference-above-username.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the row drew in $out", image.width > 0 && image.height > 0)
    }
}
