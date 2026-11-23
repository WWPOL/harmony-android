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
import lol.mony.harmony.ui.navigation.HarmonyShell
import lol.mony.harmony.ui.navigation.NavigationLayout
import lol.mony.harmony.ui.navigation.Space
import lol.mony.harmony.ui.navigation.barCapacity
import lol.mony.harmony.ui.navigation.barSpaces
import lol.mony.harmony.ui.navigation.isCurrentSpace
import lol.mony.harmony.ui.navigation.navigationLayoutFor
import lol.mony.harmony.ui.navigation.showsTwoPanesAt
import lol.mony.harmony.ui.navigation.sheetSpaces
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The frame is the spaces, and its controls stay inside the system bars.
 *
 * Three behaviours are pinned here, because each one is a claim the redesign makes:
 *
 * - **The servers are the navigation.** A server is a tile in the bar (or a row in the rail) at
 *   every width, so switching server while a room is open is one tap. The old frame spent its
 *   navigation on five destinations and hid the servers inside one of them.
 * - **The frame follows the window's width.** A phone gets a bar at the bottom, where a thumb
 *   reaches; anything 600dp and wider gets a rail at the side, where a pointer and a two-handed
 *   grip both find it and where every server can be listed by name.
 * - **Nothing sits under the status bar or a cutout.** The app draws edge to edge, so a
 *   `fillMaxHeight` column starts at y=0 and its first item lands under the clock.
 *
 * The insets are synthetic: a 40dp status bar and a 30dp start-side cutout on a 320x640dp
 * screen. The production default is the real safe drawing area.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class SpaceRailTest {
    @get:Rule val compose = createComposeRule()

    /** A status bar and a start-side cutout, the shape a notched phone reports. */
    private val notched = WindowInsets(left = 30.dp, top = 40.dp, right = 0.dp, bottom = 24.dp)

    private val spaces = listOf(
        Space.Home.copy(badge = 4),
        Space("s1", "Design", badge = 2),
        Space("s2", "E2E Server", badge = 7, mentions = 1),
        Space("s3", "Book club"),
    )

    private fun shell(
        current: Space? = spaces.first(),
        onSpace: (Space) -> Unit = {},
        content: @androidx.compose.runtime.Composable (NavigationLayout) -> Unit =
            { Text("content", Modifier.padding(0.dp)) },
    ) = @androidx.compose.runtime.Composable {
        HarmonyTheme(VisualThemeSettings()) {
            HarmonyShell(
                spaces = spaces,
                currentSpace = current,
                onSpace = onSpace,
                onAddServer = {},
                onMore = {},
                safeArea = notched,
                content = content,
            )
        }
    }

    /** A compact window draws the spaces as a bar, and no rail. */
    @Test fun aPhoneGetsTheSpaceBar() {
        compose.setContent(shell())
        compose.waitForIdle()
        compose.onNodeWithTag("space-bar").assertIsDisplayed()
        assertTrue(
            "a 320dp window drew a rail as well",
            compose.onAllNodesWithTag("space-rail").fetchSemanticsNodes().isEmpty(),
        )
        // Home and the "More" control are always there, whatever the server list is.
        compose.onNodeWithTag("space-home").assertIsDisplayed()
        compose.onNodeWithTag("space-more").assertIsDisplayed()
    }

    /**
     * Every space in the frame is inside the safe area, on the screen, and big enough to hit.
     *
     * This is the reported bug stated as a measurement: with the insets ignored, the first item
     * starts at y=0 and its centre is under the clock. The assertion is on the node's real
     * bounds, so removing the inset padding fails it.
     */
    @Test fun everySpaceIsInsideTheSafeArea() {
        compose.setContent(shell())
        compose.waitForIdle()
        val top = with(compose.density) { 40.dp.toPx() }
        val left = with(compose.density) { 30.dp.toPx() }
        val screenHeight = with(compose.density) { 640.dp.toPx() }
        val screenWidth = with(compose.density) { 320.dp.toPx() }
        for (tag in listOf("space-home", "space-server-Design", "space-more")) {
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag starts at y=${bounds.top}, under a 40dp status bar", bounds.top >= top - 1f)
            assertTrue(
                "$tag starts at x=${bounds.left}, outside the 320dp screen",
                bounds.left >= -1f && bounds.left < screenWidth,
            )
            assertTrue("$tag ends at y=${bounds.bottom}, past a 640dp screen", bounds.bottom <= screenHeight + 1f)
            assertTrue(
                "$tag is only ${bounds.height}px tall, below the 48dp floor",
                bounds.height >= with(compose.density) { 48.dp.toPx() } - 1f,
            )
        }
        val bar = compose.onNodeWithTag("space-bar").fetchSemanticsNode().boundsInRoot
        assertTrue("the bar starts at x=${bar.left}, under the 30dp cutout", bar.left >= left - 1f)
    }

    /**
     * A tap on a server reports it, and the frame follows.
     *
     * This is the whole point of the change, stated as an interaction: the server a person is
     * *not* in is one tap from the screen they are on, including while a room is open.
     */
    @Test fun tappingAServerReportsIt() {
        var chosen: Space? = null
        var current by mutableStateOf(spaces.first())
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                HarmonyShell(
                    spaces = spaces,
                    currentSpace = current,
                    onSpace = { chosen = it; current = it },
                    onAddServer = {},
                    onMore = {},
                    safeArea = notched,
                    content = { Text("the room", Modifier.fillMaxSize().testTag("room")) },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("space-server-Design").performClick()
        compose.waitForIdle()
        assertEquals("the tap reaches the caller", "Design", chosen?.name)
        assertEquals("and the frame follows it", "s1", current?.id)
        // The content is still there: the frame never covers what it navigated to.
        compose.onNodeWithTag("room").assertIsDisplayed()
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
     * The bar holds Home and the three most *active* servers, not the three most recent.
     *
     * A phone's bar is the app's fastest surface, and a bar that scrolls is a bar whose last
     * items are never seen. So it is capped -- and capped by attention, because a server with
     * mentions in it should not sit behind a server that was read this morning.
     */
    @Test fun theBarPutsTheServersThatNeedAttentionInIt() {
        val quiet = Space("s1", "Quiet", badge = 0)
        val busy = Space("s2", "Busy", badge = 12)
        val mentioned = Space("s3", "Mentioned", badge = 3, mentions = 1)
        val alsoQuiet = Space("s4", "Also quiet")
        val bar = barSpaces(listOf(Space.Home, quiet, busy, mentioned, alsoQuiet))
        assertEquals("Home is always first", "Home", bar.first().name)
        assertEquals("mentions come first, then unread, then the rest", 4, bar.size)
        assertEquals("Mentioned", bar[1].name)
        assertEquals("Busy", bar[2].name)
        // The bar is capped, so the fourth server is the sheet's business, not the bar's.
        assertEquals(1 + 3, bar.size)
        assertTrue("a quiet server still makes the bar", bar.any { it.name == "Quiet" })
        // The sheet holds every server, in the same order of attention.
        val sheet = sheetSpaces(listOf(Space.Home, quiet, busy, mentioned, alsoQuiet))
        assertEquals("the sheet lists every server, Home aside", 4, sheet.size)
        assertTrue("Home is the frame's, not the sheet's", sheet.none { it.isHome })
        assertEquals("Mentioned", sheet.first().name)
    }

    /**
     * The bar's arithmetic: Home, the servers and "More" all fit, at every phone width.
     *
     * This is a bug that shipped in this change and a test caught: the item width was the bar's
     * width divided by the *spaces*, which left "More" -- the only way to the servers that did
     * not fit -- pushed off the right edge of the screen. The rule is here rather than inside the
     * composable so it can be checked at every width instead of only the one a test draws.
     */
    @Test fun theBarAlwaysHasRoomForMore() {
        for (width in listOf(320, 360, 400, 412, 480, 599)) {
            for (servers in 0..12) {
                val capacity = barCapacity(width, servers)
                // Home + the servers it chose + More all fit inside the bar's usable width.
                val used = capacity.itemWidth * (capacity.servers + 2)
                assertTrue(
                    "at ${width}dp with $servers servers the bar needs ${used}dp of ${width - 12}dp",
                    used <= width - 12,
                )
                // And an item is never narrower than a face and a name need, nor wider than one.
                assertTrue("${capacity.itemWidth}dp is too narrow", capacity.itemWidth >= 52)
                assertTrue("${capacity.itemWidth}dp is too wide", capacity.itemWidth <= 88)
                // The bar never claims more servers than the person has.
                assertTrue("the bar claims ${capacity.servers} of $servers", capacity.servers <= servers)
            }
        }
        // A phone has room for a few; the widest phone in the list gets more than the narrowest.
        assertTrue("a 320dp phone holds some servers", barCapacity(320, 12).servers >= 1)
        assertTrue(
            "a 599dp phone holds at least as many as a 320dp one",
            barCapacity(599, 12).servers >= barCapacity(320, 12).servers,
        )
    }

    /** Home is current when nothing else is, and a server is current only when it is selected. */
    @Test fun theCurrentSpaceIsTheOneBeingRead() {
        assertTrue("no server selected means Home", isCurrentSpace(Space.Home, null, showingDms = false))
        assertTrue("the DM flag means Home", isCurrentSpace(Space.Home, "s1", showingDms = true))
        assertTrue("a server is current when it is selected", isCurrentSpace(Space("s1", "Design"), "s1", false))
        assertTrue("and not when another one is", !isCurrentSpace(Space("s2", "Other"), "s1", false))
    }

    /** A badge is not a bare number: TalkBack has to say what it counts. */
    @Test fun aBadgeSaysWhatItCounts() {
        assertEquals("Design, 5 unread, 2 mentioning you", Space("s", "Design", badge = 5, mentions = 2).spoken())
        assertEquals("Design, 1 unread", Space("s", "Design", badge = 1).spoken())
        assertEquals("Design", Space("s", "Design").spoken())
    }

    /**
     * Two panes need a wide window, and the frame is what decides it.
     *
     * The pane count is the destination's decision, made from the width the frame measured, so
     * the two cannot disagree about how wide the window is.
     */
    @Test fun aListBesideItsRoomNeedsAWideWindow() {
        assertTrue("a phone has one pane", !showsTwoPanesAt(400))
        assertTrue("600dp fits a list beside its detail", showsTwoPanesAt(600))
        assertTrue("a tablet fits them too", showsTwoPanesAt(1024))
    }

    /** The shell's own reading of the width, so the frame and the content agree. */
    @Test fun theFrameProvidesTheWidthItMeasured() {
        var seen = 0
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                HarmonyShell(
                    spaces = spaces,
                    currentSpace = spaces.first(),
                    onSpace = {},
                    onAddServer = {},
                    onMore = {},
                    safeArea = notched,
                ) {
                    seen = lol.mony.harmony.ui.navigation.LocalWindowWidth.current
                    Text("content")
                }
            }
        }
        compose.waitForIdle()
        assertEquals("the shell measured a 320dp window", 320, seen)
    }

    /** A space with no id is Home; that is the whole of the distinction. */
    @Test fun homeIsTheSpaceWithoutAServer() {
        assertTrue(Space.Home.isHome)
        assertNull(Space.Home.id)
        assertTrue(!Space("s1", "Design").isHome)
    }
}
