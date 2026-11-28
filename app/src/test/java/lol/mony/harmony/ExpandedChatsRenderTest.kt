package lol.mony.harmony

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import lol.mony.harmony.RenderHarness.capture
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.navigation.Space
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
 * The expanded Chats layout, drawn: a switcher column, the list, and the room.
 *
 * The other wide render test draws the design gallery, which proves the tokens and the frame but
 * not the layout this redesign is actually about. This one draws the real `ChatsDestination` at
 * 1000dp and measures the three panes, because the pane count was wrong in the first version: at
 * 600dp the switcher column and the 340dp list left the room a strip of pixels, and the message
 * text wrapped one letter per line.
 *
 * It runs offline. The destination reads an `AppState`, and with no session it draws the list's
 * own empty state -- which still lays out at the widths the panes get, so the geometry is the
 * thing being asserted and the rows are not.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w1000dp-h800dp-xhdpi")
class ExpandedChatsRenderTest {
    @get:Rule val compose = createComposeRule()

    /** A status bar, as a tablet reports it. */
    private val insets = WindowInsets(left = 0.dp, top = 24.dp, right = 0.dp, bottom = 24.dp)

    private fun draw(theme: VisualThemeSettings = VisualThemeSettings(theme = "dark")) {
        compose.setContent {
            HarmonyTheme(theme) {
                // The app paints its own background behind the system bars; without this the
                // window's white shows through the inset and the capture misrepresents it.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state = AppState(lol.mony.harmony.AppContainer(RuntimeEnvironment.getApplication()))
                    HarmonyShell(
                        spaces = railSpaces, currentSpace = railSpaces[1],
                        onSpace = {}, onAddServer = {}, onMore = {}, safeArea = insets,
                    ) { layout -> ChatsDestination(state, theme, layout) }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The three panes -- the spaces, the list and the room -- each with real width.
     *
     * A width assertion rather than a picture: a screenshot shows that *something* drew, and the
     * bug this replaced was that the something was 40dp wide. The room has to be the widest
     * pane, because that is the one a person reads.
     */
    @Test fun theExpandedLayoutGivesTheRoomTheMostWidth() {
        draw()
        val screen = with(compose.density) { 1000.dp.toPx() }
        val rail = compose.onNodeWithTag("space-rail").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("room-list").fetchSemanticsNode().boundsInRoot
        assertTrue("the rail drew nothing", rail.width > 0f)
        assertTrue("the list drew nothing", list.width > 0f)
        val room = screen - rail.width - list.width
        assertTrue(
            "the room is ${room}px of ${screen}px, narrower than the list's ${list.width}px",
            room > list.width,
        )
        // A room narrower than a phone's reading column is the bug this test exists for.
        val minimum = with(compose.density) { 300.dp.toPx() }
        assertTrue("the room is only ${room}px wide", room >= minimum)
    }

    /**
     * The layout as a picture, with the design system's own components where the rows would be.
     *
     * The real destination needs a session to fill its list, and a capture of three empty panes
     * proves only that the geometry is right -- which the test above already measures. This draws
     * the same frame and the same three panes with the app's own components, so the picture shows
     * the type, shapes and colour at this width instead of a spinner.
     */
    @Test fun theExpandedLayoutInTheDarkLook() {
        gallery(VisualThemeSettings(theme = "dark"), "design-expanded-chats-dark")
    }

    /**
     * The same window in the account's light look.
     *
     * Light and dark are the same layout with two palettes, so one capture each is what proves
     * the second palette reaches the panes rather than only the background.
     */
    @Test fun theExpandedLayoutInTheLightLook() {
        gallery(VisualThemeSettings(theme = "light"), "design-expanded-chats-light")
    }

    private fun gallery(theme: VisualThemeSettings, name: String) {
        compose.setContent {
            HarmonyTheme(theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HarmonyShell(
                        spaces = railSpaces, currentSpace = railSpaces[1],
                        onSpace = {}, onAddServer = {}, onMore = {}, safeArea = insets,
                    ) { _ -> DesignGallery.Content() }
                }
            }
        }
        compose.waitForIdle()
        RenderHarness.run { compose.capture(name) }
    }
}

/**
 * The medium width -- the one the first version got wrong.
 *
 * At 640dp the switcher column and the 340dp list left the room a strip of pixels, with the
 * message text wrapping one letter per line. Robolectric fixes a window per test class, so this
 * is its own class: the *medium* width is the one that broke, and it would never be drawn inside
 * the 1000dp test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w640dp-h800dp-xhdpi")
class MediumChatsRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theRoomSurvivesAtMediumWidth() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state = AppState(lol.mony.harmony.AppContainer(RuntimeEnvironment.getApplication()))
                    HarmonyShell(
                        spaces = railSpaces, currentSpace = railSpaces[1],
                        onSpace = {}, onAddServer = {}, onMore = {},
                        safeArea = WindowInsets(0, 24, 0, 24),
                    ) { layout -> ChatsDestination(state, VisualThemeSettings(), layout) }
                }
            }
        }
        compose.waitForIdle()
        val screen = with(compose.density) { 640.dp.toPx() }
        val list = compose.onNodeWithTag("room-list").fetchSemanticsNode().boundsInRoot
        val room = screen - list.width
        val minimum = with(compose.density) { 280.dp.toPx() }
        assertTrue(
            "at 640dp the room is only ${room}px of ${screen}px, a strip of pixels",
            room >= minimum,
        )
        // And the spaces are the rail here, not a strip above the list: there is exactly one
        // place a server is chosen, at every width.
        assertTrue(
            "a 640dp window drew no space rail",
            compose.onAllNodesWithTag("space-rail").fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "a 640dp window also drew the phone's bar",
            compose.onAllNodesWithTag("space-bar").fetchSemanticsNodes().isEmpty(),
        )
    }
}

/** The spaces these captures draw: Home and three servers, one of them asking for attention. */
private val railSpaces = listOf(
    Space.Home.copy(unread = 2, pings = 2),
    Space("s1", "Design"),
    Space("s2", "E2E Server", unread = 4, pings = 2),
    Space("s3", "Book club"),
)
