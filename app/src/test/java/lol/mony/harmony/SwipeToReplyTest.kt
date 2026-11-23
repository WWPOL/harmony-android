package lol.mony.harmony

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.screens.chat.SwipeToReplyRow
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

/**
 * A message replies when it is dragged *away* from the start edge, not towards it.
 *
 * The reported bug is a direction collision: the reply travelled the same way a navigation
 * drawer opens, so a person swiping to reply was swiping the gesture that opens the drawer with
 * the channel list in it. These tests inject the two swipes a thumb can make and require the
 * reply on exactly one of them, so restoring the old direction fails them -- not merely a test
 * that calls `onReply` directly.
 *
 * They drive [SwipeToReplyRow], the composable that owns the gesture, rather than a whole
 * `RoomScreen`: the direction is the thing under test, and it needs no signed-in instance, no
 * network and no message history to be measured.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h640dp-xhdpi")
class SwipeToReplyTest {
    @get:Rule val compose = createComposeRule()

    /** How many times the row asked to reply, so a double fire is caught too. */
    private var replies = 0

    /** A row as wide as a phone, with a label that can be seen to move. */
    private fun row() {
        replies = 0
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    SwipeToReplyRow(
                        key = "m1",
                        onReply = { replies++ },
                        modifier = Modifier.fillMaxWidth().testTag("row"),
                    ) {
                        Box(Modifier.fillMaxWidth().height(56.dp).padding(12.dp)) {
                            Text("a message", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The fix: a drag towards the start edge is the reply.
     *
     * `swipeLeft` starts near the trailing edge and ends near the leading one, which is the
     * motion a thumb makes when it drags a row out of its own way.
     */
    @Test fun aLeftwardSwipeReplies() {
        row()
        compose.onNodeWithTag("row").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals("one reply from one leftward swipe", 1, replies)
    }

    /**
     * The bug: the other direction is *not* a reply.
     *
     * This is the half that failed before the fix. A rightward drag is the drawer's gesture, so
     * a row that consumed it would take the channel list's swipe away from the frame.
     */
    @Test fun aRightwardSwipeDoesNotReply() {
        row()
        compose.onNodeWithTag("row").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertTrue("a rightward swipe must not reply, it is the drawer's direction", replies == 0)
    }

    /**
     * The feedback appears on the side the row uncovers, which is the trailing edge.
     *
     * The arrow is drawn under the row and is therefore only in the tree while a drag is in
     * progress. A row that slid the other way would draw it on the leading edge instead, so the
     * test holds a finger down mid-drag and reads where the arrow landed.
     */
    @Test fun theFeedbackArrowAppearsOnTheTrailingEdge() {
        row()
        val rootWidth = compose.onRoot().fetchSemanticsNode().size.width
        compose.onNodeWithTag("row").performTouchInput {
            down(centerRight - androidx.compose.ui.geometry.Offset(1f, 0f))
            // Well past the 64dp threshold, but not so far that the row reaches its half-width
            // limit and stops following the finger.
            moveTo(androidx.compose.ui.geometry.Offset(rootWidth / 2f, centerY))
        }
        compose.waitForIdle()
        val arrow = compose.onNodeWithTag("reply-feedback").fetchSemanticsNode()
        val arrowCenter = arrow.boundsInRoot.center.x
        assertTrue(
            "the reply arrow belongs on the trailing edge, but its centre is at $arrowCenter of $rootWidth",
            arrowCenter > rootWidth / 2f,
        )
        // Letting go past the threshold is a reply; this also releases the injected pointer.
        compose.onNodeWithTag("row").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, replies)
    }

    /**
     * A capture of the gesture in progress, for a person to look at.
     *
     * It draws a short channel of rows, holds one of them mid-drag past the threshold, and
     * writes the picture: the row has moved away from its start edge and the reply arrow is on
     * the trailing edge it uncovered. That is the whole visible difference the fix makes, and a
     * direction that regressed would put the arrow on the other side.
     */
    @Test fun theGestureInProgressIsCaptured() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface(Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.layout.Column {
                        for ((index, text) in listOf(
                            "Can you look at the deploy script?",
                            "The drawer and the reply both went the same way.",
                            "Which way should the reply go?",
                        ).withIndex()) {
                            SwipeToReplyRow(key = "m$index", onReply = {}, modifier = Modifier.testTag("row$index")) {
                                Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(text, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        // The middle row, held open at about the threshold, so the arrow is fully faded in.
        compose.onNodeWithTag("row1").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(-120f, 0f))
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = java.io.File(
            System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"),
            "swipe-to-reply-direction.png",
        )
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the gesture drew in $out", bitmap.width > 0 && bitmap.height > 0)
    }

    /**
     * A drag shorter than the threshold is not a reply, in either direction.
     *
     * Without this, a test could pass by replying on any touch at all.
     */
    @Test fun aShortDragIsNotAReply() {
        row()
        compose.onNodeWithTag("row").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(-20f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals("20px is far short of 64dp", 0, replies)
    }
}
