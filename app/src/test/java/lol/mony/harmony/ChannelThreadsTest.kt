package lol.mony.harmony

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
 * column is the app's counterpart. The fixture answers with one thread in each of the three
 * states, on two different channels, so a list that nests the wrong thread, nests it under the
 * wrong channel, or drops it altogether fails here.
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

    /**
     * The reported screen: the open thread is in the list, indented under its own channel.
     *
     * Indented is measured, not asserted by eye: the thread's own title starts further in than
     * the channel's name and sits below it, and the next channel's row is below the thread's,
     * which is what "nested under the channel" means on screen.
     */
    @Test fun anActiveThreadIsIndentedUnderItsOwnChannel() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            draw(state)
            waitForTag("channel-list")
            waitForTag("thread-t-open")

            val channel = compose.onNodeWithText("general", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val thread = compose.onNodeWithText("Release planning", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "the thread's title starts at ${thread.left}, no further in than the channel's ${channel.left}",
                thread.left > channel.left,
            )
            assertTrue(
                "the thread's title is above its channel's, so it is not nested under it",
                thread.top > channel.top,
            )
            // Nested under *this* channel: the next channel's row is below the thread's.
            val below = compose.onNodeWithText("design", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("the thread is not under its channel, but after the next one", thread.top < below.top)

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
            assertEquals("the open thread is not filed under its own channel",
                listOf("t-open"), nested["c2"].orEmpty().map { it.id })
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
        }
    }
}
