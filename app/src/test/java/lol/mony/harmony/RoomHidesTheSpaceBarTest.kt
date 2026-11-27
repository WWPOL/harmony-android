package lol.mony.harmony

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.navigation.HarmonyShell
import lol.mony.harmony.ui.navigation.NavigationLayout
import lol.mony.harmony.ui.navigation.Space
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone's space bar stands down while a room is open.
 *
 * The report: "the bottom bar showing home and your servers is a bit unnecessary feeling when
 * you're viewing a channel". It was: a bar whose job is choosing a space, drawn under a screen
 * that is not a space, costing the message list the height a phone has least of. These tests
 * state both halves of the rule -- gone while a room is open, back with the list -- and the
 * third: a wide window keeps its rail, because a rail is beside the content rather than under it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h800dp-xhdpi")
class RoomHidesTheSpaceBarTest {
    @get:Rule val compose = createComposeRule()

    private val spaces = listOf(Space.Home, Space("s1", "E2E Server"))

    @Composable
    private fun shell(hideBar: Boolean) {
        HarmonyTheme(VisualThemeSettings()) {
            HarmonyShell(
                spaces = spaces,
                currentSpace = spaces.last(),
                onSpace = {},
                onAddServer = {},
                onMore = {},
                hideBar = hideBar,
            ) { layout ->
                Box(Modifier.fillMaxSize().testTag("content-" + layout.name)) {
                    Text("a room", Modifier.padding(16.dp))
                }
            }
        }
    }

    /** The list: the bar is there, and it is where a space is chosen from. */
    @Test fun theBarIsThereOnTheChannelList() {
        compose.setContent { shell(hideBar = false) }
        compose.waitForIdle()
        compose.onNodeWithTag("space-bar").assertExists()
        compose.onNodeWithTag("space-home").assertExists()
    }

    /** The room: it is not, and the content gains its height and the bottom inset it owned. */
    @Test fun theBarIsGoneInARoom() {
        compose.setContent { shell(hideBar = true) }
        compose.waitForIdle()
        assertTrue(
            "the space bar is still drawn under a room",
            compose.onAllNodesWithTag("space-bar").fetchSemanticsNodes().isEmpty(),
        )
        // The content is still the whole content: hiding the bar is not hiding the room.
        compose.onNodeWithTag("content-BottomBar").assertExists()
    }

}

/**
 * The room gains the bar's height, measured against the bar the window drew.
 *
 * A compose rule sets its content once, so the two cases cannot be drawn in one test. Instead
 * this asserts the two facts that make the claim: the bar has a height worth reclaiming, and the
 * content in a room reaches the bottom of the window -- which is only true if the content took
 * back the inset the bar used to own, since the window is edge to edge.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h800dp-xhdpi")
class TheRoomReachesTheBottomTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theBarHasAHeightWorthReclaiming() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                HarmonyShell(
                    spaces = listOf(Space.Home, Space("s1", "E2E Server")),
                    currentSpace = Space("s1", "E2E Server"),
                    onSpace = {}, onAddServer = {}, onMore = {},
                ) { layout -> Box(Modifier.fillMaxSize().testTag("content-" + layout.name)) }
            }
        }
        compose.waitForIdle()
        val bar = compose.onNodeWithTag("space-bar").fetchSemanticsNode().boundsInRoot
        assertTrue("the bar is only ${bar.height}px tall, so hiding it would gain nothing",
            bar.height >= with(compose.density) { 48.dp.toPx() } - 1f)
    }

    @Test fun aRoomWithoutTheBarReachesTheWindowsBottom() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                HarmonyShell(
                    spaces = listOf(Space.Home, Space("s1", "E2E Server")),
                    currentSpace = Space("s1", "E2E Server"),
                    onSpace = {}, onAddServer = {}, onMore = {},
                    hideBar = true,
                ) { layout -> Box(Modifier.fillMaxSize().testTag("content-" + layout.name)) }
            }
        }
        compose.waitForIdle()
        val content = compose.onNodeWithTag("content-BottomBar").fetchSemanticsNode().boundsInRoot
        val screen = with(compose.density) { 800.dp.toPx() }
        assertTrue(
            "the content ends at ${content.bottom} in an $screen px window, so the bar's height " +
                "went nowhere",
            content.bottom >= screen - 1f,
        )
    }
}

/**
 * The same rule at a wide width: the rail stays.
 *
 * A rail is a column beside the content, so it costs the message list no height and switching
 * server while reading is one tap. The report was about a phone's bottom bar; a change that
 * removed the rail as well would take away the navigation the wide layout is built around.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w900dp-h700dp-xhdpi")
class RoomKeepsTheSpaceRailTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theRailStaysWhileARoomIsOpen() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                HarmonyShell(
                    spaces = listOf(Space.Home, Space("s1", "E2E Server")),
                    currentSpace = Space("s1", "E2E Server"),
                    onSpace = {},
                    onAddServer = {},
                    onMore = {},
                    hideBar = true,
                ) { layout ->
                    Box(Modifier.fillMaxSize().testTag("content-" + layout.name))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("space-rail").assertExists()
        assertTrue(
            "a wide window drew a bottom bar",
            compose.onAllNodesWithTag("space-bar").fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag("content-Rail").assertExists()
        // The rail is beside the content, not over it: the content is the rest of the width.
        val rail = compose.onNodeWithTag("space-rail").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("content-Rail").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the content starts at ${content.left} and the rail ends at ${rail.right}",
            content.left >= rail.right - 1f,
        )
    }
}

/** [NavigationLayout] names, so a test's tag says which layout drew it. */
private val NavigationLayout.name: String
    get() = when (this) {
        NavigationLayout.BottomBar -> "BottomBar"
        NavigationLayout.Rail -> "Rail"
    }
