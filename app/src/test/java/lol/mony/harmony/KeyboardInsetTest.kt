package lol.mony.harmony

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the frame does when the keyboard is up.
 *
 * This is the regression for a bug reported from a phone with three screenshots: typing a
 * message left "a huge deadspace where I think the ui thinks the keyboard is" between the
 * composer and the keyboard, a multi-line draft was hard to use, and a reply banner or a
 * connection banner made it worse until the message list itself was gone.
 *
 * The cause was one inset counted twice and one bar drawn where nothing could see it.
 * `WindowInsets.safeDrawing` is `systemBars ∪ ime ∪ displayCutout` -- the keyboard is folded into
 * the same bottom edge as the navigation bar -- and the frame handed that to the bottom bar. With
 * the keyboard up the bar therefore padded itself by the keyboard's height and drew a surface
 * that height tall, every pixel of it behind the keyboard; the room above it lost that height,
 * and then padded for the keyboard *again* with its own `imePadding()`. On a 640dp-tall phone
 * with a 300dp keyboard that leaves about 150dp for an app bar, a reply banner and a composer.
 *
 * The frame now takes the keyboard out of the bar's insets, stands the bar down while it is up,
 * and gives the content the height above it exactly once. These tests assert that from the
 * outside, as bounds on the screen: the content reaches the keyboard, the bar is not drawn behind
 * it, and the composer is inside the content rather than under the app bar.
 *
 * The keyboard is synthetic, like the notch in [SpaceRailTest]: the real one is an input method
 * Robolectric does not run. A 300dp keyboard on a 640dp screen is the phone case from the report.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class KeyboardInsetTest {
    @get:Rule val compose = createComposeRule()

    /** A status bar, a start-side cutout and a navigation bar, the shape a notched phone reports. */
    private val bars = WindowInsets(left = 30.dp, top = 40.dp, right = 0.dp, bottom = 24.dp)

    /**
     * The keyboard as the platform reports it: it covers the navigation bar, so its height is
     * the whole bottom edge and `safeDrawing` is the *larger* of the two, not their sum.
     */
    private val keyboard = WindowInsets(bottom = 300.dp)

    private val spaces = listOf(Space.Home, Space("s1", "E2E Server"))

    /** A frame whose content stands in for a room: an app bar, a list, and a composer. */
    @Composable
    private fun Shell(keyboardShown: Boolean = true) {
        HarmonyTheme(VisualThemeSettings()) {
            HarmonyShell(
                spaces = spaces,
                currentSpace = Space.Home,
                onSpace = {},
                onAddServer = {},
                onMore = {},
                safeArea = bars,
                ime = if (keyboardShown) keyboard else WindowInsets(0, 0, 0, 0),
            ) { layout: NavigationLayout ->
                Column(Modifier.fillMaxSize().testTag("room")) {
                    Box(Modifier.fillMaxWidth().height(64.dp).testTag("app-bar")) { Text("games") }
                    Box(Modifier.fillMaxWidth().weight(1f).testTag("message-list")) { Text("a message") }
                    Box(Modifier.fillMaxWidth().height(56.dp).testTag("composer")) { Text("Message #games") }
                }
            }
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun px(dp: Int) = with(compose.density) { dp.dp.toPx() }

    /**
     * The content ends where the keyboard begins.
     *
     * This is the deadspace stated as a measurement. The keyboard's top edge is at 640 - 300 =
     * 340dp; the content must reach it and stop there. Before the fix the content stopped at
     * 640 - 300 - 24 - 300 = 16dp, because the frame padded for the keyboard and the bar drew
     * another keyboard's worth below it.
     */
    @Test fun theContentEndsAtTheKeyboardNotAboveIt() {
        compose.setContent { Shell() }
        compose.waitForIdle()

        val room = bounds("room")
        val keyboardTop = px(640 - 300)
        assertEquals(
            "the content ends at y=${room.bottom}, not at the keyboard's top edge y=$keyboardTop",
            keyboardTop, room.bottom, 1f,
        )
    }

    /**
     * The bottom bar is not drawn behind the keyboard.
     *
     * A bar there is not navigation: it cannot be seen, and it cannot be tapped through the
     * keyboard. Its height was also taken from the room, which is what squeezed the composer.
     */
    @Test fun theSpaceBarStandsDownForTheKeyboard() {
        compose.setContent { Shell() }
        compose.waitForIdle()
        assertTrue(
            "the space bar is still drawn while the keyboard covers it",
            compose.onAllNodesWithTag("space-bar").fetchSemanticsNodes().isEmpty(),
        )
    }

    /** With the keyboard down the bar is back, and the content sits above it. */
    @Test fun theBarComesBackWhenTheKeyboardGoesAway() {
        compose.setContent { Shell(keyboardShown = false) }
        compose.waitForIdle()

        val bar = bounds("space-bar")
        val room = bounds("room")
        assertTrue(
            "the bar is not drawn with the keyboard down",
            compose.onAllNodesWithTag("space-bar").fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "the room ends at y=${room.bottom}, past the bar's top y=${bar.top}",
            room.bottom <= bar.top + 1f,
        )
        // The bar's items stay inside the navigation bar, and the bar is at the bottom of the
        // window rather than floating above it.
        assertTrue(
            "the bar ends at y=${bar.bottom}, under the 24dp navigation bar",
            bar.bottom <= px(640 - 24) + 1f,
        )
        assertTrue("the bar is not at the bottom of the window", bar.top > px(640 / 2))
    }

    /**
     * The composer is at the bottom of the room, and the room keeps its message list.
     *
     * This is the second half of the report: a multi-line draft was hard to use, and a reply
     * banner pushed the messages off the screen. The composer's bottom edge is the room's, so a
     * taller field grows upward into the list instead of pushing the list out of the window.
     */
    @Test fun theComposerSitsAtTheBottomOfTheRoomWithTheListAboveIt() {
        compose.setContent { Shell() }
        compose.waitForIdle()

        val room = bounds("room")
        val composer = bounds("composer")
        val list = bounds("message-list")
        assertEquals("the composer's bottom is the room's", room.bottom, composer.bottom, 1f)
        assertTrue(
            "the message list is only ${list.height}px tall above a 64dp bar and a 56dp composer",
            list.height >= px(640 - 300 - 40 - 64 - 56) - 1f,
        )
    }

    /**
     * The frame insets the keyboard once, not once by the frame and once by the screen.
     *
     * A destination that padded for the IME itself -- which the room did, with `imePadding()` --
     * would shrink inside the space the frame had already given it. The room is drawn here
     * without any inset of its own, so what is measured is the frame's contract: the content
     * reaches the keyboard's top edge exactly.
     */
    @Test fun theFrameInsetsTheKeyboardOnce() {
        compose.setContent { Shell() }
        compose.waitForIdle()

        // The app bar is still below the status bar, and the composer is still above the
        // keyboard: both edges of the content are inside the space the frame measured.
        val bar = bounds("app-bar")
        val composer = bounds("composer")
        assertEquals("the app bar is not below the status bar", px(40), bar.top, 1f)
        assertEquals("the composer is not above the keyboard", px(640 - 300), composer.bottom, 1f)
    }

    /**
     * The message list keeps its room when a reply banner and a connection banner are both up.
     *
     * This is the report's third screenshot: with the keyboard, a "Replying to phat" banner and
     * the connection strip all on screen, the messages were gone and only a thin blue line
     * remained of the list. Each banner is a fixed height, and the composer is bounded, so what
     * is left is the list's -- provided the frame is not also drawing a keyboard's worth of
     * space below the composer, which is what took it.
     */
    @Test fun theListSurvivesTheBannersTheKeyboardAndAComposer() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                HarmonyShell(
                    spaces = spaces,
                    currentSpace = Space.Home,
                    onSpace = {},
                    onAddServer = {},
                    onMore = {},
                    safeArea = bars,
                    ime = keyboard,
                ) {
                    Column(Modifier.fillMaxSize().testTag("room")) {
                        Box(Modifier.fillMaxWidth().height(64.dp).testTag("app-bar")) { Text("games") }
                        Box(Modifier.fillMaxWidth().height(48.dp).testTag("reply-banner")) {
                            Text("Replying to phat: if consumers are not receiving go…")
                        }
                        Box(Modifier.fillMaxWidth().height(40.dp).testTag("connection-banner")) {
                            Text("Live connection dropped. Reconnecting.")
                        }
                        Box(Modifier.fillMaxWidth().weight(1f).testTag("message-list")) { Text("a message") }
                        Box(Modifier.fillMaxWidth().height(96.dp).testTag("composer")) { Text("Message") }
                    }
                }
            }
        }
        compose.waitForIdle()

        // 640 - 300 (keyboard) - 40 (status bar) - 64 - 48 - 40 - 96 = 52dp of list.
        val list = bounds("message-list")
        assertTrue(
            "the message list is ${list.height}px tall, which is not enough to read a message",
            list.height >= px(52) - 1f,
        )
        // And the list is between the banners and the composer, not behind them.
        val composer = bounds("composer")
        assertTrue("the composer overlaps the list", composer.top >= list.bottom - 1f)
    }
}
