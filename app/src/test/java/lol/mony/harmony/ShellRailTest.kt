package lol.mony.harmony

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.navigation.Destination
import lol.mony.harmony.ui.navigation.DestinationBadge
import lol.mony.harmony.ui.navigation.HarmonyShell
import lol.mony.harmony.ui.navigation.NavigationLayout
import lol.mony.harmony.ui.navigation.navigationLayoutFor
import lol.mony.harmony.ui.navigation.showsSpaceColumnAt
import lol.mony.harmony.ui.navigation.showsTwoPanesAt
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
 * The app's frame adapts to the window, and its controls stay inside the system bars.
 *
 * Two behaviours are pinned here, because both were wrong in the shell this replaced:
 *
 * - **The frame follows the window's width.** A phone gets a bottom bar, where a thumb reaches;
 *   anything 600dp and wider gets a rail at the side, where a pointer and a two-handed grip both
 *   find it. The old shell drew one 76dp rail at every width, which wasted a tablet's space and
 *   squeezed a phone's content.
 * - **Nothing sits under the status bar or a cutout.** The app draws edge to edge, so a
 *   `fillMaxHeight` column starts at y=0 and its first item lands under the clock. The old rail
 *   had exactly that bug; the insets are passed in here so the arithmetic can be asserted at a
 *   known size instead of depending on a device with a notch.
 *
 * The insets are synthetic: a 40dp status bar and a 30dp start-side cutout on a 320x640dp
 * screen. The production default is the real safe drawing area.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class ShellRailTest {
    @get:Rule val compose = createComposeRule()

    /** A status bar and a start-side cutout, the shape a notched phone reports. */
    private val notched = WindowInsets(left = 30.dp, top = 40.dp, right = 0.dp, bottom = 24.dp)

    private fun shell(
        destination: Destination = Destination.Chats,
        onDestination: (Destination) -> Unit = {},
        chats: Int = 0,
        mentions: Int = 0,
        content: @androidx.compose.runtime.Composable (NavigationLayout) -> Unit =
            { Text("content", Modifier.padding(0.dp)) },
    ) = @androidx.compose.runtime.Composable {
        HarmonyTheme(VisualThemeSettings()) {
            HarmonyShell(
                destination = destination,
                onDestination = onDestination,
                chats = chats,
                mentions = mentions,
                safeArea = notched,
                content = content,
            )
        }
    }

    /**
     * A compact window draws a bottom bar and no rail.
     *
     * The assertion is on which component exists, not on a flag: the bar's own tag is present
     * and the rail's is not, which is what a person sees.
     */
    @Test fun aPhoneGetsABottomBar() {
        compose.setContent(shell())
        compose.waitForIdle()
        compose.onNodeWithTag("nav-bar").assertIsDisplayed()
        assertTrue(
            "a 320dp window drew a rail as well",
            compose.onAllNodesWithTag("rail").fetchSemanticsNodes().isEmpty(),
        )
        // Every destination is reachable from the bar.
        for (label in listOf("Chats", "Social", "Files", "Alerts", "You")) {
            compose.onNodeWithTag("nav-$label").assertIsDisplayed()
        }
    }

    /**
     * Every destination is inside the safe area, and on the screen.
     *
     * This is the reported bug stated as a measurement: with the insets ignored, the first item
     * starts at y=0 and its centre is under the clock. The assertion is on the node's real
     * bounds, so removing the inset padding fails it.
     */
    @Test fun everyDestinationIsInsideTheSafeArea() {
        compose.setContent(shell())
        compose.waitForIdle()
        val top = with(compose.density) { 40.dp.toPx() }
        val left = with(compose.density) { 30.dp.toPx() }
        val screenHeight = with(compose.density) { 640.dp.toPx() }
        val screenWidth = with(compose.density) { 320.dp.toPx() }
        for (label in listOf("Chats", "Social", "Files", "Alerts", "You")) {
            val bounds = compose.onNodeWithTag("nav-$label").fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$label starts at y=${bounds.top}, under a 40dp status bar",
                bounds.top >= top - 1f,
            )
            assertTrue(
                "$label starts at x=${bounds.left}, outside the 320dp screen",
                bounds.left >= -1f && bounds.left < screenWidth,
            )
            assertTrue(
                "$label ends at y=${bounds.bottom}, past the 640dp screen",
                bounds.bottom <= screenHeight + 1f,
            )
            // And it is a real touch target, not a 20dp icon with no room around it.
            assertTrue(
                "$label is only ${bounds.height}px tall, below the 48dp floor",
                bounds.height >= with(compose.density) { 48.dp.toPx() } - 1f,
            )
        }
        // The cutout inset is applied to the bar's own content, so nothing is under it either.
        val bar = compose.onNodeWithTag("nav-bar").fetchSemanticsNode().boundsInRoot
        assertTrue("the bar starts at x=${bar.left}, under the 30dp cutout", bar.left >= left - 1f)
    }

    /**
     * A tap reaches the caller and the shell follows it.
     *
     * The destination is the caller's state, so this drives the same two values the caller sets:
     * a tap reports the destination, and the frame draws the new one selected.
     */
    @Test fun tappingADestinationReportsIt() {
        var chosen: Destination? = null
        var current by mutableStateOf(Destination.Chats)
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                HarmonyShell(
                    destination = current,
                    onDestination = { chosen = it; current = it },
                    safeArea = notched,
                    content = { Text("the destination", Modifier.fillMaxSize().testTag("destination")) },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("nav-Social").performClick()
        compose.waitForIdle()
        assertEquals("the tap reaches the caller", Destination.Social, chosen)
        assertEquals("and the frame follows it", Destination.Social, current)
        // The content is still there: the frame never covers what it navigated to.
        compose.onNodeWithTag("destination").assertIsDisplayed()
    }

    /**
     * The width rule itself, independent of any composition.
     *
     * The tests above draw one window; this pins the boundary, so a shell that drew a bottom bar
     * on a tablet fails here even if the composition test is changed to match it.
     */
    @Test fun theWidthDecidesTheFrame() {
        assertEquals("a phone upright gets a bottom bar", NavigationLayout.BottomBar, navigationLayoutFor(320))
        assertEquals("a large phone still gets a bottom bar", NavigationLayout.BottomBar, navigationLayoutFor(599))
        assertEquals("a phone on its side gets a rail", NavigationLayout.Rail, navigationLayoutFor(600))
        assertEquals("a tablet gets a rail", NavigationLayout.Rail, navigationLayoutFor(1024))
    }

    /**
     * The badge a destination draws, and what it says.
     *
     * A badge is not just a number: TalkBack has to say what it counts, or a person moving by
     * item hears "Chats" and never learns there is anything waiting.
     */
    @Test fun aBadgeSaysWhatItCounts() {
        val badge = DestinationBadge(count = 5, mentions = 2)
        assertEquals("5", badge.text)
        assertEquals("Chats, 5 unread, 2 mentioning you", badge.spoken("Chats"))
        assertEquals("Chats, 1 unread", DestinationBadge(count = 1).spoken("Chats"))
        assertEquals("Chats, 250 unread", DestinationBadge(count = 250).spoken("Chats"))
        // The cap keeps a large count from widening the bar.
        assertEquals("99+", DestinationBadge(count = 250).text)
    }

    /**
     * Three panes need an expanded window, and two need only a wide one.
     *
     * This is the rule a 640dp capture caught: the switcher column is up to 260dp and the list
     * is 340dp, so drawing all three at 600dp left the room a strip of pixels with the message
     * text wrapping one letter per line. The pane count is two decisions, and the second one
     * starts where Material's expanded class does.
     */
    @Test fun aThirdPaneWaitsForAnExpandedWindow() {
        assertTrue("a phone has no second pane", !showsTwoPanesAt(400))
        assertTrue("600dp fits a list beside its detail", showsTwoPanesAt(600))
        assertTrue("a tablet fits them too", showsTwoPanesAt(1024))
        assertTrue("600dp is too narrow for three panes", !showsSpaceColumnAt(600))
        assertTrue("839dp is still too narrow", !showsSpaceColumnAt(839))
        assertTrue("840dp is where the third pane pays for itself", showsSpaceColumnAt(840))
        assertTrue("a tablet has the room", showsSpaceColumnAt(1024))
    }
}
