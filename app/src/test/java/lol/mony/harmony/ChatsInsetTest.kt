package lol.mony.harmony

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.navigation.Destination
import lol.mony.harmony.ui.navigation.HarmonyShell
import lol.mony.harmony.ui.screens.chat.ChatsDestination
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The destination's content stays out from under the status bar.
 *
 * This is a regression test for a bug a device screenshot caught and no test did. The app draws
 * edge to edge, so a destination's first row begins at y=0 unless something insets it. The space
 * switcher is that first row in Chats, and on the device the "Home" pill sat under the status bar
 * with the clock drawn over it.
 *
 * The fix is in the shell, not the destination: [HarmonyShell] pads the whole content by the safe
 * area once, so no destination can forget it and none can pad it twice. These tests assert the
 * *content's* bounds through the shell, which is the contract a screen can rely on.
 *
 * Robolectric fixes a window size per test class, so the compact and expanded cases are two
 * classes: one of them would otherwise never be drawn.
 *
 * No instance is needed: this asserts layout, and an empty DM list lays out the same way a full
 * one does.
 */

/** A status bar and a start-side cutout, the shape a notched phone reports. */
private val notched = WindowInsets(left = 30.dp, top = 40.dp, right = 0.dp, bottom = 24.dp)

@Composable
private fun ShellWithChats() {
    HarmonyTheme(VisualThemeSettings()) {
        val state = AppState(lol.mony.harmony.AppContainer(RuntimeEnvironment.getApplication()))
        HarmonyShell(Destination.Chats, {}, safeArea = notched) { layout ->
            ChatsDestination(state, VisualThemeSettings(), layout)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class ChatsInsetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theCompactSwitcherIsBelowTheStatusBar() {
        compose.setContent { ShellWithChats() }
        compose.waitForIdle()

        val strip = compose.onNodeWithTag("space-switcher").fetchSemanticsNode().boundsInRoot
        val statusBar = with(compose.density) { 40.dp.toPx() }
        assertTrue(
            "the space switcher starts at y=${strip.top}, under a 40dp status bar",
            strip.top >= statusBar - 1f,
        )
        // And the whole strip is on the screen, not pushed off it by the inset.
        val screenHeight = with(compose.density) { 640.dp.toPx() }
        assertTrue(
            "the space switcher ends at y=${strip.bottom}, past a 640dp screen",
            strip.bottom <= screenHeight + 1f,
        )
    }

    /**
     * A compact window draws the strip and the bottom bar.
     *
     * Stated as a test because the strip and the wide column are different composables: a phone
     * that drew the column would be a regression in the switcher itself.
     */
    @Test fun aPhoneDrawsTheStripAndTheBar() {
        compose.setContent { ShellWithChats() }
        compose.waitForIdle()
        compose.onNodeWithTag("nav-bar").assertExists()
        assertTrue(
            "a compact window drew no space switcher",
            compose.onAllNodesWithTag("space-switcher").fetchSemanticsNodes().isNotEmpty(),
        )
    }
}

/**
 * The same contract at 900dp: content below the status bar, a rail instead of a bar.
 *
 * The wide space switcher is a different composable from the strip and gets its inset from the
 * same place -- the shell -- so this class is what proves the policy holds at both widths.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w900dp-h640dp-xhdpi")
class WideChatsInsetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theWideSpaceColumnIsBelowTheStatusBar() {
        compose.setContent { ShellWithChats() }
        compose.waitForIdle()

        val column = compose.onNodeWithTag("space-switcher").fetchSemanticsNode().boundsInRoot
        val statusBar = with(compose.density) { 40.dp.toPx() }
        assertTrue(
            "the wide space column starts at y=${column.top}, under a 40dp status bar",
            column.top >= statusBar - 1f,
        )
    }

    @Test fun aWideWindowDrawsTheRailAndNoBar() {
        compose.setContent { ShellWithChats() }
        compose.waitForIdle()
        compose.onNodeWithTag("rail").assertExists()
        assertTrue(
            "a wide window also drew a bottom bar",
            compose.onAllNodesWithTag("nav-bar").fetchSemanticsNodes().isEmpty(),
        )
    }
}
