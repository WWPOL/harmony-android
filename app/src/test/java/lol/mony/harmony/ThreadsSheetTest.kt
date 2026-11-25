package lol.mony.harmony

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import lol.mony.harmony.RenderHarness.capture
import lol.mony.harmony.data.api.AuthApi
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.ProvideAppState
import lol.mony.harmony.ui.screens.chat.RoomScreen
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
 * A channel with threads says it has threads.
 *
 * This is the regression for a report from a real build: a channel that had threads claimed it
 * had none. The sheet drew `listed.filter { it.isActive() }` -- the *sidebar's* rule, which drops
 * an archived thread and one whose own auto-archive clock has run out -- and then showed "No open
 * threads" when that left nothing. Since this client creates every thread with a 1440-minute
 * window, every thread a channel had ever had vanished from the sheet a day after its last reply,
 * while the instance still held it and the reference client's Threads list still drew it
 * (`AllThreadsModal.vue`: `channelThreads(id, { includeArchived: true })`).
 *
 * The fixture answers with one thread in each of the three states -- open, past its window,
 * archived -- and this drives the real `RoomScreen` from the overflow menu to the sheet, so a
 * build that filters again fails on the row it dropped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h900dp-xhdpi")
class ThreadsSheetTest {
    @get:Rule val compose = createComposeRule()

    /** Signs in against the fixture, the same calls the app makes against a real instance. */
    private fun signedIn(instance: FixtureInstance): AppState = runBlocking {
        val state = AppState(RenderHarness.container())
        state.auth.chooseInstance(Endpoint(instance.baseUrl, "fixture-anon"), "Fixture Harmony")
        val result = state.auth.signIn("ada@example.test", "fixture-password")
        assertTrue("the fixture refused the sign-in: $result", result is AuthApi.Result.Ok)
        state.onSignedIn(state.auth.state.value as AuthState.SignedIn)
        state
    }

    /**
     * Waits for the room itself, not for a string that happens to be on it.
     *
     * The room's composer is part of its own structure and arrives with the screen, so this is
     * a settle that cannot be met by a channel's name, a message body or a spinner's caption.
     */
    private fun waitForRoom() = compose.waitUntil(180_000) {
        compose.onAllNodesWithTag("message-composer").fetchSemanticsNodes().isNotEmpty()
    }

    /** Opens the room's overflow menu and taps Threads, the way a thumb reaches the sheet. */
    private fun openThreads() {
        waitForRoom()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Threads").performClick()
        compose.waitUntil(180_000) { compose.onAllNodesWithTag("thread-list").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun draw(state: AppState, channelId: String) {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                ProvideAppState(state) {
                    RoomScreen(state, channelId, direct = false,
                        appearance = VisualThemeSettings(theme = "dark"), onBack = {})
                }
            }
        }
    }

    /**
     * The reported bug, at the sheet: all three of the fixture's threads are listed.
     *
     * Before the fix the two closed ones were filtered out, so this failed on "Frame sketches"
     * and "Buried thread" -- and a channel whose threads were *all* closed showed the empty
     * sentence instead, which is what a person reported as the app claiming there were none.
     */
    @Test fun everyThreadOfTheChannelIsListedWhateverItsState() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            draw(state, "c2")
            openThreads()
            // The authors are resolved a moment after the rows arrive, so the capture waits for
            // the name rather than recording "Deleted account" over a channel whose people the
            // fixture does have -- a picture of a race, not of the screen.
            compose.waitUntil(180_000) {
                compose.onAllNodesWithText("Grace Hopper", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            compose.capture("threads-sheet")
            for (name in listOf("Release planning", "Frame sketches", "Buried thread")) {
                assertTrue("the sheet dropped the thread \"$name\"",
                    compose.onAllNodesWithText(name, substring = true).fetchSemanticsNodes().isNotEmpty())
            }
            assertTrue("the sheet still claims the channel has no threads",
                compose.onAllNodesWithText("No threads yet", substring = true).fetchSemanticsNodes().isEmpty())
            assertTrue("the sheet still claims the channel has no open threads",
                compose.onAllNodesWithText("No open threads", substring = true).fetchSemanticsNodes().isEmpty())
        }
    }

    /**
     * A thread that is out of the sidebar says why, so a person can tell the two apart.
     *
     * The archived one is named as archived; the one whose own clock ran out names the window
     * that applied, which is the thread's own `auto_archive_duration` and not a fixed "24 hours".
     */
    @Test fun aThreadThatIsNotOpenIsMarkedRatherThanHidden() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            draw(state, "c2")
            openThreads()

            compose.onNodeWithTag("thread-t-archived").assertExists()
            compose.onNodeWithTag("thread-t-expired").assertExists()
            compose.onNodeWithTag("thread-t-open").assertExists()
            assertTrue("the archived thread is not marked as archived",
                compose.onAllNodesWithText("Archived", substring = true).fetchSemanticsNodes().isNotEmpty())
            assertTrue("the thread past its window does not name the window",
                compose.onAllNodesWithText("Idle past its window of 1 day", substring = true)
                    .fetchSemanticsNodes().isNotEmpty())
        }
    }

    /**
     * The sidebar keeps the open threads only, while the sheet lists every one.
     *
     * This is the two rules the reference keeps apart, in one place: `activeChannelThreads` for
     * the channel list, `channelThreads(…, includeArchived)` for the Threads list. Reading the
     * sheet must not leave the sidebar claiming the closed threads are open.
     */
    @Test fun theSidebarStillNestsOnlyTheOpenThread() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            draw(state, "c2")
            openThreads()
            compose.onNodeWithTag("thread-t-open").assertExists()

            val nested = state.threadsByChannel.value["c2"].orEmpty().map { it.id }
            assertTrue("the sheet's read put the closed threads in the sidebar too: $nested",
                nested == listOf("t-open"))
        }
    }
}
