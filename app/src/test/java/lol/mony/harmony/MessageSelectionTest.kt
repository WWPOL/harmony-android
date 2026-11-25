package lol.mony.harmony

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.model.MessageAuthor
import lol.mony.harmony.ui.components.SelectTextSheetContent
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
 * A message's words are not a selection surface: the row's gestures belong to the row.
 *
 * The reported problem was that text on the UI "should not be selectable so easily". It was not
 * a rare accident: [lol.mony.harmony.ui.components.MessageContent] wrapped every body in a
 * `SelectionContainer`, and a `SelectionContainer` takes the first long press for itself. A
 * message row's only gesture is that same long press -- it opens the actions sheet -- so on a
 * phone one press did both: it painted a word selection with its handles and magnifier *and*
 * opened the sheet, and a double tap selected a word where a messaging app does nothing.
 *
 * These tests inject the touches a thumb makes and read the pixels, because the defect is a
 * painting one: an assertion on the tree alone would pass with the selection still drawn. The
 * long press must open the actions and change nothing else on the screen; the double tap must
 * change nothing at all; the row's own gestures (a drag to reply, a tap on a link) must still
 * work.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// SDK 27 on purpose: the selection magnifier is API 28+, and with it the platform's own missing
// surface hides the defect this file measures. Without a magnifier a selection is only paint, so
// a restored `SelectionContainer` fails here on the pixel count it actually left behind.
@Config(sdk = [27], qualifiers = "w360dp-h800dp-xhdpi")
class MessageSelectionTest {
    @get:Rule val compose = createComposeRule()

    private val author = MessageAuthor(name = "Noah", profileId = "p1")
    private val body = "a sentence somebody wrote here"

    private fun row(content: String = body, id: String = "m2") = RoomRow(
        key = id,
        message = Message(
            id = id, channelId = "c1", userId = "p1",
            createdAt = "2026-01-01T00:01:00Z",
            content = listOf(ContentPart.text(content)),
        ),
    )

    /** The row, drawn by the room's own composable, with the callbacks a gesture would reach. */
    private fun draw(
        row: RoomRow = row(),
        onActions: () -> Unit = {},
        onReply: () -> Unit = {},
        onOpenLink: (String) -> Unit = {},
    ) {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    Column(Modifier.fillMaxWidth()) {
                        MessageRow(
                            row = row, author = author, showHeader = true, baseUrl = null,
                            appearance = VisualThemeSettings(), reactions = emptyList(),
                            emojis = emptyMap(), myId = "p1",
                            onActions = onActions, onReact = {}, onAuthor = {},
                            onShowReactors = {}, onReply = onReply, onRetry = {},
                            onOpenLink = onOpenLink,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Every pixel of the row, so a highlight of any size is visible to an assertion. */
    private fun pixels(): IntArray {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = IntArray(image.width * image.height)
        image.getPixels(out, 0, image.width, 0, 0, image.width, image.height)
        return out
    }

    private fun differingPixels(before: IntArray, after: IntArray): Int =
        before.indices.count { before[it] != after[it] }

    private fun save(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"), name)
        out.parentFile?.mkdirs()
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the row drew in $out", image.width > 0 && image.height > 0)
    }

    /**
     * The reported gesture: a long press on the words.
     *
     * It opens the actions -- that is the row's gesture and it is unchanged -- and it must not
     * paint a selection. The row is the whole tree here and the actions are a callback, so the
     * picture after the press has to be the picture before it: any pixel that moved is a
     * highlight, a handle or the magnifier.
     */
    @Test fun aLongPressOnTheBodyOpensTheActionsAndPaintsNothing() {
        var actions = 0
        draw(onActions = { actions++ })
        val before = pixels()
        compose.onNodeWithText(body).performTouchInput { longClick() }
        compose.waitForIdle()
        val changed = differingPixels(before, pixels())
        save("message-long-press-no-selection.png")
        assertEquals("a long press on a message opens its actions", 1, actions)
        assertEquals("a long press must not paint a text selection, but $changed pixels changed",
            0, changed)
    }

    /**
     * A double tap is nothing at all on a message.
     *
     * Before the fix it selected the word under the finger: 1594 pixels of this very row. A
     * quick-react on a double tap is a separate feature (C07) and is not implemented; what a
     * double tap must not do is start a selection.
     */
    @Test fun aDoubleTapOnTheBodyPaintsNothing() {
        draw()
        val before = pixels()
        compose.onNodeWithText(body).performTouchInput { doubleClick() }
        compose.waitForIdle()
        val changed = differingPixels(before, pixels())
        save("message-double-tap-no-selection.png")
        assertEquals("a double tap must not select a word, but $changed pixels changed", 0, changed)
    }

    /**
     * A selection cannot be dragged out of the body either.
     *
     * `SelectionContainer` also starts a selection on a second press followed by a drag. A
     * horizontal drag is the row's reply gesture ([lol.mony.harmony.ui.screens.chat.SwipeToReplyRow]),
     * so it must still reply, and the row must come to rest exactly where it started.
     */
    @Test fun aDragRepliesAndLeavesNoSelectionBehind() {
        var replies = 0
        draw(onReply = { replies++ })
        val before = pixels()
        compose.onNodeWithTag("message-row-m2").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val changed = differingPixels(before, pixels())
        assertEquals("a leftward drag on a message still replies", 1, replies)
        assertEquals("the row must come back to rest, but $changed pixels stayed changed", 0, changed)
    }

    /**
     * A link in the body is still a link.
     *
     * Dropping the container must not drop the link's own tap: the words around a URL are
     * annotated by `LinkedText`, and that annotation is what opens it.
     */
    @Test fun aTapOnALinkInTheBodyStillOpensIt() {
        val opened = mutableListOf<String>()
        draw(row = row("https://example.test/post is in the prose"), onOpenLink = { opened += it })
        // The link is an annotation *inside* the sentence, so the tap goes at the URL's own
        // characters rather than at a node of its own: dropping the selection container must
        // not drop the link's tap.
        compose.onAllNodesWithTag("message-m2", useUnmergedTree = true)[1].performTouchInput {
            click(Offset(2f, height / 2f))
        }
        compose.waitForIdle()
        assertEquals(listOf("https://example.test/post"), opened)
    }

    /**
     * The other half of the same claim: selection still exists, one deliberate step away.
     *
     * The actions sheet's **Select text** opens this sheet, and here a long press is *meant* to
     * select. Without this case, deleting the container everywhere would pass every test above
     * while quietly removing the ability to copy part of a message.
     */
    @Test fun theSelectSheetIsWhereABodyStillSelects() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    SelectTextSheetContent(message = row().message, fontSize = 16.0)
                }
            }
        }
        compose.waitForIdle()
        val before = pixels()
        compose.onNodeWithText(body).performTouchInput { longClick() }
        compose.waitForIdle()
        val changed = differingPixels(before, pixels())
        save("message-select-text-sheet.png")
        assertTrue("the Select text sheet is where a body is selectable, but nothing was painted",
            changed > 0)
    }

    /**
     * The bug, drawn, and the proof that the measurement above can see one.
     *
     * This is the same row, the same long press and the same theme; the one thing it adds is the
     * `SelectionContainer` the app used to wrap every body in. It must paint a selection -- if it
     * did not, `aLongPressOnTheBodyOpensTheActionsAndPaintsNothing` would be measuring nothing
     * and would pass however the body were drawn. `message-selection-before.png` is this picture;
     * `message-long-press-no-selection.png` is the same press on the row as it ships.
     */
    @Test fun theBodyInAContainerStillSelectsSoTheMeasurementIsReal() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    Column(Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            MessageRow(
                                row = row(), author = author, showHeader = true, baseUrl = null,
                                appearance = VisualThemeSettings(), reactions = emptyList(),
                                emojis = emptyMap(), myId = "p1",
                                onActions = {}, onReact = {}, onAuthor = {}, onShowReactors = {},
                                onReply = {}, onRetry = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val before = pixels()
        compose.onNodeWithText(body).performTouchInput { longClick() }
        compose.waitForIdle()
        val changed = differingPixels(before, pixels())
        save("message-selection-before.png")
        assertTrue("a body in a SelectionContainer must paint a selection, but nothing changed",
            changed > 0)
    }
}
