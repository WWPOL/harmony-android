package lol.mony.harmony

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import lol.mony.harmony.data.api.AuthApi
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.model.UnreadCountRow
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.ProvideAppState
import lol.mony.harmony.ui.navigation.Destination
import lol.mony.harmony.ui.navigation.HarmonyShell
import lol.mony.harmony.ui.navigation.MoreSheet
import lol.mony.harmony.ui.navigation.Space
import lol.mony.harmony.ui.screens.chat.ChannelList
import lol.mony.harmony.ui.screens.chat.DirectMessages
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.LocalHarmonyColors
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Two kinds of attention, drawn as two different things.
 *
 * The report: *"don't use the red badge with number ux to indicate unread messages. you can just
 * use the brighter white text for that. the red badge ux is for direct pings."* Before this, every
 * surface that knew a count drew it -- the space bar, the rail, the sheet, the channel column and
 * the direct-message list -- so a rail of red counts said "everything is urgent" and nothing was.
 *
 * The rule now, and what these tests measure:
 *
 * - **Unread** is the name: brighter and heavier than a name with nothing behind it, and no
 *   number anywhere. The brightness is measured off the pixels, against the row's own background,
 *   because a colour role read back from the composition would pass even if the text never drew.
 * - **A ping** -- a mention in a channel, or an unread direct message -- is the red badge, and
 *   nothing else in these surfaces is. The badge's colour is measured too, so a badge that came
 *   back as the theme's primary blue would fail.
 *
 * Every assertion here fails on the code this replaced: the counts drew badges, and an unread name
 * was the same colour as a read one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h900dp-xhdpi")
class UnreadAttentionTest {
    @get:Rule val compose = createComposeRule()

    /**
     * Four spaces, so every case is on screen at once: nothing waiting, unread with no ping,
     * unread with pings, and Home whose unread direct messages *are* pings.
     */
    private val spaces = listOf(
        Space(null, "Home", unread = 4, pings = 4),
        Space("s1", "Quiet"),
        Space("s2", "Busy", unread = 12),
        Space("s3", "Pinged", unread = 3, pings = 1),
    )

    /**
     * The phone's bar over a caption, with Home current.
     *
     * Home is current on purpose: it is the one space whose label is drawn in the selected role,
     * so the two names the brightness test compares -- "Quiet" and "Busy" -- are both drawn in
     * the ordinary way and differ only in whether anything is waiting.
     */
    private fun bar() = compose.setContent {
        HarmonyTheme(VisualThemeSettings(theme = "dark")) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                HarmonyShell(
                    spaces = spaces, currentSpace = spaces.first(),
                    onSpace = {}, onAddServer = {}, onMore = {},
                ) { _ ->
                    // The caption is for the capture: it says which of the two things each item
                    // is, so the picture is evidence on its own rather than a row of faces.
                    Text(
                        "Unread is the bright name (Busy).\n" +
                            "The red badge is a ping (Home, Pinged).\n" +
                            "Quiet has nothing waiting.",
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // ---- the space bar --------------------------------------------------------------------

    /**
     * The bar draws a badge for the two spaces with pings and for neither of the others.
     *
     * Home's four unread direct messages are four pings, so it has one; "Busy" has twelve unread
     * messages and no mention, so it has none -- that is the case the report is about.
     */
    @Test fun onlyAPingingSpaceGetsABadge() {
        bar()
        compose.waitForIdle()
        assertEquals("the bar drew the wrong number of ping badges", 2,
            compose.onAllNodesWithTag("ping-badge", useUnmergedTree = true).fetchSemanticsNodes().size)
        // The counts are not drawn as text anywhere: "12" is a number a badge would have shown.
        assertTrue("an unread count is drawn as a number",
            compose.onAllNodesWithText("12").fetchSemanticsNodes().isEmpty())
        RenderHarness.run { compose.capture("unread-space-bar") }
    }

    /**
     * An unread name is brighter than a read one, measured on the pixels of the bar.
     *
     * The contrast is against the bar's own background, so the same assertion holds in a light
     * look where "brighter" means darker ink. A space with twelve unread messages has to stand
     * out from a quiet one without a badge, which is the whole of the change.
     */
    @Test fun anUnreadNameIsBrighterThanAReadOne() {
        bar()
        compose.waitForIdle()
        // Each tile is captured on its own, so the two pictures compared are taken the same way
        // and the surface behind each name is the one it was actually drawn on.
        val quiet = AttentionPaint.contrastOf(compose.onNodeWithText("Quiet", useUnmergedTree = true).captureToImage().asAndroidBitmap())
        val busy = AttentionPaint.contrastOf(compose.onNodeWithText("Busy", useUnmergedTree = true).captureToImage().asAndroidBitmap())
        assertTrue("a quiet name has no contrast at all: it did not draw", quiet > 0.05f)
        assertTrue(
            "an unread name ($busy) is not brighter than a read one ($quiet)",
            busy > quiet + 0.05f,
        )
    }

    /**
     * The badge is the ping colour, not the theme's accent.
     *
     * Read off the pill's own pixels: the badge is red (`HarmonyColors.badge`), so the red in it
     * has to dominate. The sample is taken beside the digit rather than through it, because the
     * digit is white and the middle of a one-digit pill is the digit. A badge drawn in `primary`
     * -- which is what the unread case used to draw whenever nothing mentioned the account --
     * fails here whatever the accent is.
     */
    @Test fun theBadgeIsThePingRed() {
        bar()
        compose.waitForIdle()
        val badge = compose.onAllNodesWithTag("ping-badge", useUnmergedTree = true)[0]
            .captureToImage().asAndroidBitmap()
        // The pill's fill, sampled across the top edge above the digits and its bottom edge below
        // them: whichever of the two the antialiasing leaves alone, the fill is the pill's colour.
        val samples = listOf(
            badge.getPixel(badge.width / 2, 0),
            badge.getPixel(badge.width / 2, badge.height - 1),
            badge.getPixel(1, badge.height / 2),
        )
        for (colour in samples) {
            val r = (colour shr 16) and 0xFF
            val g = (colour shr 8) and 0xFF
            val b = colour and 0xFF
            assertTrue("the ping badge's fill is rgb($r,$g,$b), not red", r > 180 && r > g + 100 && r > b + 100)
        }
    }

    // ---- the wide rail and the map ---------------------------------------------------------

    /**
     * The "More" sheet: a ping is the badge and unread is a sentence.
     *
     * The sheet is the one place a count in words is kept, because it is where somebody decides
     * *which* server to open; the badge there is still only the ping's. So "Busy" says
     * "12 unread" and draws no badge, and "Pinged" says "1 pinging you, 3 unread" and draws one.
     */
    @Test fun theSheetSaysUnreadInWordsAndBadgesOnlyPings() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize()) {
                    MoreSheet(
                        spaces = spaces, currentSpace = spaces.first(), currentDestination = null,
                        onSpace = {}, onDestination = {}, onAddServer = {}, onDismiss = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        assertEquals("the sheet drew the wrong number of ping badges", 2,
            compose.onAllNodesWithTag("ping-badge", useUnmergedTree = true).fetchSemanticsNodes().size)
        // The unread counts are words, and the ping is both: the sentence says what the badge
        // cannot, and the badge says which of the rows is asking for an answer.
        assertTrue("the sheet does not say how much is unread in words",
            compose.onAllNodesWithText("12 unread", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("the sheet does not say what is pinging",
            compose.onAllNodesWithText("pinging you", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("an unread count is drawn as a bare number",
            compose.onAllNodesWithText("12").fetchSemanticsNodes().isEmpty())
        RenderHarness.run { compose.capture("unread-more-sheet") }
    }

    // ---- the channel column ---------------------------------------------------------------

    /**
     * The column: an unread channel is bright, a read one is muted, and only a mention draws a
     * badge -- drawn from the instance's own unread rows, so the counts come from the same
     * `UnreadCountRow` the app fills its badges from.
     */
    @Test fun anUnreadChannelIsBrightAndOnlyAMentionGetsABadge() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            // `general` (c2) has six unread of which two mention this account; `design` (c3) has
            // three unread and no mention; `welcome` (c1) is read.
            state.applyUnread(listOf(
                UnreadCountRow(channelId = "c2", unreadMessages = 6, unreadMentions = 2),
                UnreadCountRow(channelId = "c3", unreadMessages = 3),
            ))
            compose.setContent {
                HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ProvideAppState(state) { ChannelList(state, "s1", Modifier.fillMaxSize()) }
                    }
                }
            }
            compose.waitUntil(180_000) {
                compose.onAllNodesWithTag("channel-design").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()

            assertEquals("the column drew the wrong number of ping badges", 1,
                compose.onAllNodesWithTag("ping-badge", useUnmergedTree = true).fetchSemanticsNodes().size)
            // The mention's count is on the badge; the plain unread count is drawn nowhere.
            assertTrue("an unread channel count is drawn as a number",
                compose.onAllNodesWithText("3").fetchSemanticsNodes().isEmpty())

            // Each row is measured from its own capture rather than from the window's, so the
            // numbers compared are two pictures of the same size taken the same way. Neither of
            // these two channels has a ping, so nothing but the name's own colour can move the
            // number.
            val read = AttentionPaint.contrastOf(compose.onNodeWithTag("channel-welcome").captureToImage().asAndroidBitmap())
            val unread = AttentionPaint.contrastOf(compose.onNodeWithTag("channel-design").captureToImage().asAndroidBitmap())
            assertTrue("a read channel's name has no contrast: it did not draw", read > 0.05f)
            assertTrue(
                "an unread channel's name ($unread) is not brighter than a read one's ($read)",
                unread > read + 0.05f,
            )
            RenderHarness.run { compose.capture("unread-channel-column") }
        }
    }

    // ---- the direct-message list ----------------------------------------------------------

    /**
     * A direct message is a ping: its row keeps the red badge and its name brightens.
     *
     * This is the one list where unread *is* a ping, because the person who sent it is waiting on
     * an answer rather than on a read.
     */
    @Test fun anUnreadDirectMessageIsAPing() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            state.applyUnread(listOf(UnreadCountRow(conversationId = "cv1", unreadMessages = 2)))
            compose.setContent {
                HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ProvideAppState(state) { DirectMessages(state, Modifier.fillMaxSize()) }
                    }
                }
            }
            compose.waitUntil(180_000) {
                compose.onAllNodesWithText("Grace Hopper").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            assertEquals("an unread direct message drew no ping badge", 1,
                compose.onAllNodesWithTag("ping-badge", useUnmergedTree = true).fetchSemanticsNodes().size)
            RenderHarness.run { compose.capture("unread-direct-messages") }
        }
    }

    // ---- what a screen reader hears -------------------------------------------------------

    /** The two counts are spoken as the two different things they are. */
    @Test fun spokenSaysUnreadAndPingsApart() {
        assertEquals("Busy, 12 unread", Space("s", "Busy", unread = 12).spoken())
        assertEquals("Pinged, 3 unread, 1 pinging you", Space("s", "Pinged", unread = 3, pings = 1).spoken())
        assertEquals("Pinged, 6 unread, 2 pinging you", Space("s", "Pinged", unread = 6, pings = 2).spoken())
        assertEquals("Quiet", Space("s", "Quiet").spoken())
        // Home has no channels, so it never has unread messages that are not pings; a Home whose
        // two counts agree says the ping once rather than twice.
        assertEquals("Home, 2 pinging you", Space(null, "Home", unread = 2, pings = 2).spoken())
    }

    /**
     * The badge says what it counts, and the two spaces count different things.
     *
     * A server's badge counts mentions; Home's counts unread direct messages. TalkBack reading
     * "3" beside "Design" would leave out the only part that matters.
     */
    @Test fun theBadgeSaysWhatItCounts() {
        assertEquals("1 mention", Space("s", "Design", unread = 1, pings = 1).pingDescription())
        assertEquals("3 mentions", Space("s", "Design", unread = 9, pings = 3).pingDescription())
        assertEquals("1 unread direct message", Space(null, "Home", unread = 1, pings = 1).pingDescription())
        assertEquals("4 unread direct messages", Space(null, "Home", unread = 4, pings = 4).pingDescription())
    }

    private fun signedIn(instance: FixtureInstance): AppState = runBlocking {
        val state = AppState(RenderHarness.container())
        state.auth.chooseInstance(Endpoint(instance.baseUrl, "fixture-anon"), "Fixture Harmony")
        val result = state.auth.signIn("ada@example.test", "fixture-password")
        assertTrue("the fixture refused the sign-in: $result", result is AuthApi.Result.Ok)
        state.onSignedIn(state.auth.state.value as AuthState.SignedIn)
        state
    }

}

/**
 * The same two rules in a wide window, where the frame is a rail rather than a bar.
 *
 * It is its own class because Robolectric fixes a window's size per test class: drawing a rail
 * in the compact class would silently draw a bar and assert nothing about the rail.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w900dp-h700dp-xhdpi")
class UnreadAttentionWideTest {
    @get:Rule val compose = createComposeRule()

    private val spaces = listOf(
        Space(null, "Home", unread = 4, pings = 4),
        Space("s1", "Quiet"),
        Space("s2", "Busy", unread = 12),
        Space("s3", "Pinged", unread = 3, pings = 1),
    )

    @Test fun theWideRailBrightensUnreadAndBadgesOnlyPings() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize(), color = LocalHarmonyColors.current.surfaceSidebar) {
                    HarmonyShell(
                        spaces = spaces, currentSpace = spaces.first(),
                        onSpace = {}, onAddServer = {}, onMore = {},
                    ) { _ -> Surface(Modifier.fillMaxSize()) {} }
                }
            }
        }
        compose.waitForIdle()
        assertTrue("a wide window did not draw a rail",
            compose.onAllNodesWithTag("space-rail").fetchSemanticsNodes().isNotEmpty())
        assertEquals("the rail drew the wrong number of ping badges", 2,
            compose.onAllNodesWithTag("ping-badge", useUnmergedTree = true).fetchSemanticsNodes().size)
        val quiet = AttentionPaint.contrastOf(compose.onNodeWithText("Quiet", useUnmergedTree = true).captureToImage().asAndroidBitmap())
        val busy = AttentionPaint.contrastOf(compose.onNodeWithText("Busy", useUnmergedTree = true).captureToImage().asAndroidBitmap())
        assertTrue("a quiet name has no contrast at all: it did not draw", quiet > 0.05f)
        assertTrue("an unread name ($busy) is not brighter than a read one ($quiet)", busy > quiet + 0.05f)
        RenderHarness.run { compose.capture("unread-wide-rail") }
    }
}

/**
 * The measurement both attention tests share: how much ink a row draws, and how bright it is.
 *
 * It lives outside the test classes so the compact window and the wide one measure the same way;
 * two copies would be two chances to compare different things and call it a result.
 */
internal object AttentionPaint {
    /**
     * How far the brightest ink in a capture sits from the surface behind it, 0..1.
     *
     * The background is the most common colour in the picture, so the measurement does not need
     * to know which surface the row was drawn on: a name drawn brighter (or, in a light look,
     * darker) than the quiet one moves this number, and a name left in the quiet colour does not.
     * A row is captured on its own, so the background it is measured against is its own.
     */
    fun contrastOf(bitmap: Bitmap): Float {
        val counts = HashMap<Int, Int>()
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val colour = bitmap.getPixel(x, y)
            counts[colour] = (counts[colour] ?: 0) + 1
        }
        val background = counts.maxByOrNull { it.value }?.key ?: return 0f
        val base = luminance(background)
        var most = 0f
        for ((colour, _) in counts) {
            most = maxOf(most, abs(luminance(colour) - base))
        }
        return most
    }

    /** Perceived luminance of an sRGB colour, 0..1, without the gamma the eye does not apply. */
    private fun luminance(colour: Int): Float {
        val r = ((colour shr 16) and 0xFF) / 255f
        val g = ((colour shr 8) and 0xFF) / 255f
        val b = (colour and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
