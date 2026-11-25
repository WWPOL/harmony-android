package lol.mony.harmony

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import lol.mony.harmony.data.api.AuthApi
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.ProvideAppState
import lol.mony.harmony.ui.SignedInShell
import lol.mony.harmony.ui.screens.chat.SwipeToChannelList
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
 * A room gives way to its channel list when it is dragged out of the start edge, and a drag the
 * other way still answers a message.
 *
 * The two gestures share one axis and had to be given one direction each. The reply travels away
 * from the start edge; this one travels towards it, from a narrow strip at that edge, so a thumb
 * that starts on the edge and pulls rightwards gets the list back, and a thumb that pushes
 * leftwards still gets a reply. These tests inject the drags a thumb can make and require exactly
 * one outcome on each, which is what a direction collision would break.
 *
 * They drive [SwipeToChannelList] with a real [SwipeToReplyRow] inside it -- the two gestures
 * competing for one drag, as they do in a room -- rather than a whole `ChatsDestination`, which
 * would need a signed-in instance to measure a direction.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h640dp-xhdpi")
class SwipeToChannelListTest {
    @get:Rule val compose = createComposeRule()

    /** How many times the pane asked to close, so a double fire is caught too. */
    private var closes = 0

    /** How many times the row inside it replied, so the two gestures can be told apart. */
    private var replies = 0

    /**
     * A pane as wide as a phone, over a list that says it is the list.
     *
     * The row inside it is the real reply gesture, so the two are competing for the same drag
     * exactly as they do in a room.
     */
    private fun pane() {
        closes = 0
        replies = 0
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().testTag("channel-list")) {
                        Text("Channels", Modifier.padding(16.dp))
                    }
                    SwipeToChannelList(onClose = { closes++ }) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Box(Modifier.fillMaxSize()) {
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
                }
            }
        }
        compose.waitForIdle()
    }

    /** Lets any settle animation finish, so a "did not fire" assertion is not a race. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /**
     * The gesture the phone was missing: a drag out of the start edge gives the list back.
     *
     * `swipeRight` starts at the pane's leading edge and ends near its trailing one, which is the
     * motion a thumb makes when it pulls a screen back to the list behind it. The reply must not
     * fire as well: one drag, one outcome.
     */
    @Test fun aRightwardSwipeFromTheStartEdgeClosesTheRoom() {
        pane()
        compose.onNodeWithTag("room-swipe").performTouchInput { swipeRight() }
        settle()
        assertEquals("one close from one drag out of the start edge", 1, closes)
        assertEquals("the same drag must not also reply", 0, replies)
    }

    /**
     * The reply keeps the other direction, from the very strip the pane listens on.
     *
     * This is the half a careless fix breaks. The drag starts inside the pane's own start-edge
     * band -- the one place the two gestures meet -- and travels the other way, so a pane that
     * claimed the whole horizontal axis, or both directions of it, would swallow the reply.
     */
    @Test fun aLeftwardSwipeFromTheStartEdgeRepliesInsteadOfClosingTheRoom() {
        pane()
        compose.onNodeWithTag("row").performTouchInput {
            down(Offset(2f, centerY))
            moveBy(Offset(-200f, 0f))
            up()
        }
        settle()
        assertEquals("a drag away from the edge is the reply", 1, replies)
        assertEquals("and it must not close the room", 0, closes)
    }

    /**
     * A rightward drag that does not start at the edge is not a navigation.
     *
     * Without this, a row would hand the list back whenever it was dragged the wrong way, which
     * is the opposite of what the two gestures are for.
     */
    @Test fun aRightwardSwipeFromTheMiddleDoesNotCloseTheRoom() {
        pane()
        compose.onNodeWithTag("room-swipe").performTouchInput {
            down(center)
            moveBy(Offset(240f, 0f))
            up()
        }
        settle()
        assertEquals(0, closes)
    }

    /**
     * A short drag from the edge is undone: the pane goes back over the list.
     *
     * This is the other half of a threshold. A pane that closed on any touch at the edge would
     * make the strip unusable, and a test that only injected a long drag could not tell.
     */
    @Test fun aShortDragFromTheEdgeSnapsBack() {
        pane()
        val before = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot.left
        compose.onNodeWithTag("room-swipe").performTouchInput {
            down(Offset(2f, centerY))
            moveBy(Offset(28f, 0f))
            up()
        }
        settle()
        assertEquals("28px is far short of a third of the pane", 0, closes)
        val after = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot.left
        assertEquals("the pane slid back over the list", before, after, 0.5f)
    }

    /**
     * A vertical drag that starts at the edge is the list's own scroll, not a navigation.
     *
     * The edge strip is where a thumb rests, so a pane that read every drag there as a back
     * gesture would make scrolling a message list unusable along that side.
     */
    @Test fun aVerticalDragFromTheEdgeDoesNotCloseTheRoom() {
        pane()
        compose.onNodeWithTag("room-swipe").performTouchInput {
            down(Offset(2f, centerY))
            moveBy(Offset(0f, -200f))
            up()
        }
        settle()
        assertEquals(0, closes)
    }

    /**
     * The pane follows the finger, so the list is uncovered while the drag is in progress.
     *
     * The row is the pane's own content, so where the row is drawn is where the pane is. A
     * gesture that only fired on release would leave this at zero until the room closed, and a
     * person would be dragging a screen that did not move.
     */
    @Test fun thePaneFollowsTheFinger() {
        pane()
        val before = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot.left
        val width = compose.onNodeWithTag("room-swipe").fetchSemanticsNode().size.width.toFloat()
        compose.onNodeWithTag("room-swipe").performTouchInput {
            down(Offset(2f, centerY))
            // Half the pane: past the threshold on any width, so the release is a close.
            moveBy(Offset(width * 0.5f, 0f))
        }
        compose.waitForIdle()
        val during = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot.left
        assertTrue("the pane is at $during, having started at $before", during > before + 1f)
        // Letting go this far is a close; this also releases the injected pointer.
        compose.onNodeWithTag("room-swipe").performTouchInput { up() }
        settle()
        assertEquals(1, closes)
    }

    /**
     * A capture of the gesture in progress, for a person to look at.
     *
     * The channel list of the server is drawn under the pane, and the pane is held a third of the
     * way out: the room has moved off the list it was covering, and the list is what the thumb is
     * revealing. That is the whole visible difference the change makes.
     */
    @Test fun theGestureInProgressIsCaptured() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().testTag("channel-list")) {
                        Column(Modifier.padding(16.dp)) {
                            Text("E2E Server", style = MaterialTheme.typography.titleLarge)
                            for (name in listOf("# welcome", "# general", "# design")) {
                                Text(name, Modifier.padding(vertical = 8.dp))
                            }
                        }
                    }
                    SwipeToChannelList(onClose = {}) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.fillMaxSize()) {
                                Text("general", Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.titleLarge)
                                for (text in listOf(
                                    "The list stays put while I read now.",
                                    "Which way should the room go to give it back?",
                                )) {
                                    Box(Modifier.fillMaxWidth().height(56.dp).padding(12.dp)) {
                                        Text(text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val width = compose.onNodeWithTag("room-swipe").fetchSemanticsNode().size.width.toFloat()
        compose.onNodeWithTag("room-swipe").performTouchInput {
            down(Offset(2f, centerY))
            // Held most of the way out, so the uncovered list is readable in the capture.
            moveBy(Offset(width * 0.62f, 0f))
        }
        compose.waitForIdle()
        RenderHarness.run { compose.capture("swipe-to-channel-list") }
        assertTrue(
            "the list under the pane is in the tree",
            compose.onAllNodesWithTag("channel-list").fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithTag("room-swipe").performTouchInput { up() }
        compose.waitForIdle()
    }
}

/**
 * The gesture wired where a person meets it: a real `ChatsDestination` with a room open.
 *
 * [SwipeToChannelListTest] measures the gesture itself. This one proves the destination puts it
 * around the room, that the room is the pane it moves, and that the list it uncovers is the one
 * belonging to the server the room is in -- which is the thing the request was for.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h800dp-xhdpi")
class SwipeBackToChannelListJourneyTest {
    @get:Rule val compose = createComposeRule()

    private fun signedIn(instance: FixtureInstance): AppState = runBlocking {
        val state = AppState(RenderHarness.container())
        state.auth.chooseInstance(Endpoint(instance.baseUrl, "fixture-anon"), "Fixture Harmony")
        val result = state.auth.signIn("ada@example.test", "fixture-password")
        assertTrue("the fixture refused the sign-in: $result", result is AuthApi.Result.Ok)
        state.onSignedIn(state.auth.state.value as AuthState.SignedIn)
        state
    }

    private fun waitFor(tag: String) = compose.waitUntil(180_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * A room opened from the server's channel list returns to that list on a drag from the edge.
     *
     * The swipe starts at the pane's leading edge and travels to its trailing one, which is the
     * gesture a thumb makes to put a screen back; the list has to be the one it came from, so the
     * channel's own row is what the test looks for afterwards.
     */
    @Test fun aDragFromTheStartEdgeReturnsToTheChannelList() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            compose.setContent {
                HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ProvideAppState(state) {
                            SignedInShell(state, VisualThemeSettings(theme = "dark"), { _, _ -> }, {})
                        }
                    }
                }
            }
            compose.waitForIdle()
            waitFor("space-server-E2E Server")
            // The server's list, as the frame's own bar chooses it.
            compose.onNodeWithTag("space-server-E2E Server").performClick()
            waitFor("channel-list")
            compose.onNodeWithTag("channel-general").performClick()
            // The composer is in the room whatever its history says, so it is the marker that
            // the room opened rather than that a page of messages arrived.
            waitFor("message-composer")

            compose.onNodeWithTag("room-swipe").performTouchInput { swipeRight() }
            compose.waitUntil(180_000) {
                compose.onAllNodesWithTag("message-composer").fetchSemanticsNodes().isEmpty()
            }
            // Back on the server's own channel list, not on Home's conversations.
            assertTrue(
                "the list the room returned to is not the server's",
                compose.onAllNodesWithTag("channel-list").fetchSemanticsNodes().isNotEmpty(),
            )
            compose.onNodeWithTag("channel-general").assertExists()
        }
    }
}
