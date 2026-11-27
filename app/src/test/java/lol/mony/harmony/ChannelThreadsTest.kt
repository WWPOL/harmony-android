package lol.mony.harmony

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import kotlinx.coroutines.runBlocking
import lol.mony.harmony.data.api.AuthApi
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.ProvideAppState
import lol.mony.harmony.ui.screens.chat.ChannelList
import lol.mony.harmony.ui.screens.chat.RoomScreen
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
 * The channel column: an active thread nests under the channel it belongs to.
 *
 * The reference's channel list draws a channel's *open* threads under it
 * (`activeChannelThreads`: unarchived, and inside the thread's own auto-archive clock), and this
 * column is the app's counterpart. The fixture answers with two open threads and two closed ones
 * in three different states, so a list that nests the wrong thread, nests it under the wrong
 * channel, draws the wrong branch, or drops it altogether fails here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h900dp-xhdpi")
class ChannelThreadsTest {
    @get:Rule val compose = createComposeRule()

    private fun signedIn(instance: FixtureInstance): AppState = runBlocking {
        val state = AppState(RenderHarness.container())
        state.auth.chooseInstance(Endpoint(instance.baseUrl, "fixture-anon"), "Fixture Harmony")
        val result = state.auth.signIn("ada@example.test", "fixture-password")
        assertTrue("the fixture refused the sign-in: $result", result is AuthApi.Result.Ok)
        state.onSignedIn(state.auth.state.value as AuthState.SignedIn)
        state
    }

    private fun draw(state: AppState, serverId: String = "s1") {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ProvideAppState(state) { ChannelList(state, serverId, Modifier.fillMaxSize()) }
                }
            }
        }
    }

    private fun waitForTag(tag: String) = compose.waitUntil(180_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    /** A dp length in this window's pixels, so the assertions are written in the app's own units. */
    private fun px(dp: Float): Float = with(compose.density) { dp.dp.toPx() }

    /**
     * The reported screen: the open thread is in the list, indented under its own channel, drawn
     * the way Discord draws one.
     *
     * Measured, not asserted by eye. The thread's row is the column's own row -- the full width,
     * so the whole row is the tap target -- with its name one level in, in the channel's name
     * column, and no glyph of its own, because the channel above it is the glyph. What says the
     * row belongs to that channel is the branch: a hairline in the gutter from the channel's glyph
     * into the row, and on to the next thread when there is one. A row that lost the indent, kept
     * a glyph, or drew no branch fails here.
     */
    @Test fun anActiveThreadIsIndentedUnderItsChannelWithABranch() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            draw(state)
            waitForTag("channel-list")
            waitForTag("thread-t-open")

            val channelRow = compose.onNodeWithTag("channel-general").fetchSemanticsNode().boundsInRoot
            val threadRow = compose.onNodeWithTag("thread-t-open").fetchSemanticsNode().boundsInRoot
            assertEquals("the thread's row is ${threadRow.width} wide, the column's ${channelRow.width}",
                channelRow.width, threadRow.width, 0.5f)
            assertTrue("the thread's row is above its channel's, so it is not under it",
                threadRow.top > channelRow.top)
            // Under *this* channel: the next channel's row is below the thread's.
            val below = compose.onNodeWithTag("channel-design").fetchSemanticsNode().boundsInRoot
            assertTrue("the thread is not under its channel, but after the next one", threadRow.bottom <= below.top)

            // The indent: the thread's name is in the channel's name column, one level in from
            // the row's edge. A thread drawn at the edge is the row this test is here to catch.
            val channelTitle = compose.onNodeWithText("general", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val threadTitle = compose.onNodeWithText("Release planning", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("the thread's name starts at ${threadTitle.left}, the channel's at ${channelTitle.left}",
                channelTitle.left, threadTitle.left, 0.5f)
            val indent = px(32f)
            assertTrue("the thread's name starts at ${threadTitle.left}, the row's edge at ${threadRow.left}: not indented",
                threadTitle.left - threadRow.left >= indent)

            // The branch, measured on the paint: the thread's row is a hairline in the gutter,
            // not a glyph. Every pixel the gutter draws is within a stroke's width of the
            // branch's spine or of the arm into the row, and the arm is there at the row's middle.
            val gutterEnd = px(40f)
            val spine = px(28f)
            val stroke = px(2.5f)
            val armEnd = px(42f)
            val threadPixels = compose.onNodeWithTag("thread-t-open").captureToImage().asAndroidBitmap()
            val background = threadPixels.getPixel(threadPixels.width - 1, 0)
            var inGutter = 0
            var offBranch = 0
            var armAtMiddle = 0
            for (y in 0 until threadPixels.height) {
                for (x in 0 until gutterEnd.toInt()) {
                    if (threadPixels.getPixel(x, y) == background) continue
                    inGutter++
                    val onSpine = kotlin.math.abs(x - spine) <= stroke
                    val nearMiddle = kotlin.math.abs(y - threadPixels.height / 2f) <= stroke
                    val onArm = nearMiddle && x <= armEnd
                    if (!onSpine && !onArm) offBranch++
                    if (onArm && x > spine + stroke) armAtMiddle++
                }
            }
            assertTrue("the thread's row draws nothing in the gutter, so there is no branch", inGutter > 0)
            assertTrue("$offBranch of the gutter's $inGutter pixels are off the branch: it draws a glyph",
                offBranch == 0)
            assertTrue("the branch never turns into the row: no arm at the row's middle", armAtMiddle > 0)

            // The reply count in the thread's supporting line was the other half of the old
            // special row; the count lives in the room's Threads sheet now.
            assertTrue("the thread still draws its reply count",
                compose.onAllNodesWithText("4 replies", substring = true).fetchSemanticsNodes().isEmpty())

            RenderHarness.run { compose.capture("channel-threads") }
        }
    }

    /**
     * Only the open thread is nested. A closed one is not in the column, which is the rule the
     * reference applies to its sidebar and nowhere else.
     */
    @Test fun aThreadThatIsNotOpenIsNotInTheChannelColumn() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            draw(state)
            waitForTag("thread-t-open")

            for (closed in listOf("t-expired", "t-archived")) {
                assertTrue(
                    "the closed thread $closed is nested under its channel",
                    compose.onAllNodesWithTag("thread-$closed").fetchSemanticsNodes().isEmpty(),
                )
            }
        }
    }

    /**
     * A channel with no open thread nests nothing, even though the channel beside it does.
     *
     * The fixture answers the threads of each channel separately, so a list that drew every
     * thread it had read under every channel would show `t-open` twice.
     */
    @Test fun aThreadIsNestedUnderTheChannelItBelongsTo() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            draw(state)
            waitForTag("thread-t-open")
            waitForTag("channel-welcome")

            val nested = state.threadsByChannel.value
            assertEquals("the open threads are not filed under their own channel",
                listOf("t-open", "t-open-2"), nested["c2"].orEmpty().map { it.id })
            assertTrue("a channel with no threads was given one: ${nested["c1"]}",
                nested["c1"].orEmpty().isEmpty())
        }
    }

    /**
     * The report itself: being in an active thread puts it in the column under its channel.
     *
     * A thread reached from a notification or a link is opened by id, and the column may never
     * have read it -- a cached channel list is not re-read on every visit. So the room is what
     * says the thread is being read, and an active thread it is reading belongs in the column.
     * This drives that path: the id is put where a notification puts it, the room opens it, and
     * the column's own state has to gain the thread.
     */
    @Test fun aThreadBeingReadIsNestedUnderItsChannel() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            // Nothing has read this channel's threads: the column's list starts empty.
            assertTrue("the column already had the thread: ${state.threadsByChannel.value}",
                state.threadsByChannel.value["c2"].orEmpty().isEmpty())

            state.openThreadId.value = "t-open"
            compose.setContent {
                HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                    ProvideAppState(state) {
                        RoomScreen(state, "c2", direct = false,
                            appearance = VisualThemeSettings(theme = "dark"), onBack = {})
                    }
                }
            }
            // The room's header is what says the thread, not the channel, is being read.
            compose.waitUntil(180_000) {
                compose.onAllNodesWithText("Thread in", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            assertEquals("the thread being read is not nested under its channel",
                listOf("t-open"), state.threadsByChannel.value["c2"].orEmpty().map { it.id })
            // The rest of the channel's open threads are not read by this path, so the column has
            // the one thread the room reported: nesting a thread is not a reason to read them all.
            assertTrue("the column gained a thread nothing reported",
                state.threadsByChannel.value["c2"].orEmpty().size == 1)
        }
    }
}
