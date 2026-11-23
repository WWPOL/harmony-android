package lol.mony.harmony

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import lol.mony.harmony.data.api.AuthApi
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.ProvideAppState
import lol.mony.harmony.ui.SignedInShell
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
 * The app as a person arrives at it: the frame's spaces around the conversation.
 *
 * This is the redesign's claim drawn from the real `SignedInShell` and a fixture instance -- not
 * a gallery of components and not a hand-built layout. It proves what the first screen after
 * sign-in looks like, which is the thing the change was for.
 *
 * Robolectric fixes a window per class, so the phone and the tablet are two classes: one of them
 * would otherwise never be drawn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h800dp-xhdpi")
class ServerFirstNavigationRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun signedIn(instance: FixtureInstance): AppState = runBlocking {
        val state = AppState(RenderHarness.container())
        state.auth.chooseInstance(Endpoint(instance.baseUrl, "fixture-anon"), "Fixture Harmony")
        val result = state.auth.signIn("ada@example.test", "fixture-password")
        assertTrue("the fixture refused the sign-in: $result", result is AuthApi.Result.Ok)
        state.onSignedIn(state.auth.state.value as AuthState.SignedIn)
        state
    }

    private fun draw(state: AppState, theme: VisualThemeSettings = VisualThemeSettings(theme = "dark")) {
        compose.setContent {
            HarmonyTheme(theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ProvideAppState(state) { SignedInShell(state, theme, { _, _ -> }, {}) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun settle(marker: String) = compose.waitUntil(180_000) {
        compose.onAllNodes(hasText(marker, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * Waits until the frame has read the account's servers.
     *
     * The spaces are read by the frame, not by a destination, so they arrive on their own clock:
     * a capture taken as soon as the conversation draws would show a bar with Home and "More" and
     * no servers, which is a picture of the fixture being slow rather than of the app.
     */
    private fun settleSpaces(name: String) = compose.waitUntil(180_000) {
        compose.onAllNodesWithTag("space-server-$name").fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * The phone's first screen: the spaces at the bottom, the conversation above them.
     *
     * The server is one tap away *from here* -- that is the whole claim, and this is the picture
     * of the screen it is one tap from.
     */
    @Test fun thePhoneOpensOnTheConversationWithTheSpacesBelow() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            draw(state)
            settle("Direct message")
            // Wait for the screen, not for the first thing that draws: the spaces are read by
            // the frame and the conversation by the destination, and each arrives on its own
            // clock. A capture taken at "Direct message" showed a bar with Home and "More" and
            // no servers -- a picture of the fixture being slow, not of the app.
            settleSpaces("E2E Server")
            settle("Grace Hopper")
            RenderHarness.run { compose.capture("nav-phone-home") }
            // The frame is the bar, and the conversation is the whole of the rest of the screen.
            assertTrue(
                "the spaces are not in a bar at the bottom",
                compose.onAllNodesWithTag("space-bar").fetchSemanticsNodes().isNotEmpty(),
            )
            assertTrue(
                "a phone drew the wide rail",
                compose.onAllNodesWithTag("space-rail").fetchSemanticsNodes().isEmpty(),
            )
            // Every server the account is in is in the bar, one tap from the conversation.
            compose.onNodeWithTag("space-server-E2E Server").assertExists()
        }
    }

    /**
     * Tapping a server from the conversation shows that server's channels, in one tap.
     *
     * The journey, asserted: the bar is the navigation, so the tap goes straight to the channel
     * list without leaving the destination or opening a drawer.
     */
    @Test fun aServerIsOneTapFromTheConversation() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            draw(state)
            settle("Direct message")
            settleSpaces("E2E Server")
            compose.onNodeWithTag("space-server-E2E Server").performClick()
            compose.waitUntil(180_000) {
                compose.onAllNodesWithTag("channel-list").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            RenderHarness.run { compose.capture("nav-phone-server") }
            // The channels are the server's, and the server's name is in the list's own bar.
            compose.onNodeWithTag("channel-general").assertExists()
            settle("E2E Server")
        }
    }

    /**
     * The map reaches the four rare destinations, and back returns to the conversation.
     *
     * This is the other half of the change: the destinations that are not the conversation are
     * still one tap from anywhere, and they no longer take half the frame to be so.
     */
    @Test fun theMapReachesTheOtherPlaces() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            draw(state)
            settle("Direct message")
            settleSpaces("E2E Server")
            compose.onNodeWithTag("space-more").performClick()
            compose.waitForIdle()
            RenderHarness.run { compose.capture("nav-phone-map") }
            compose.onNodeWithTag("sheet-nav-You").performClick()
            // The account page's list, by tag: its text arrives with the profile, so a marker
            // that is part of the page's own structure is what says the page is there.
            compose.waitUntil(180_000) {
                compose.onAllNodesWithTag("you-settings").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            RenderHarness.run { compose.capture("nav-phone-you") }
            // The conversation is still one tap away, from a screen that is not it.
            compose.onNodeWithTag("space-home").performClick()
            compose.waitUntil(180_000) {
                compose.onAllNodesWithTag("room-list").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }
}

/**
 * The same three screens at a tablet's width: the spaces as a rail with every name visible.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w1000dp-h800dp-xhdpi")
class WideServerFirstNavigationRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun signedIn(instance: FixtureInstance): AppState = runBlocking {
        val state = AppState(RenderHarness.container())
        state.auth.chooseInstance(Endpoint(instance.baseUrl, "fixture-anon"), "Fixture Harmony")
        val result = state.auth.signIn("ada@example.test", "fixture-password")
        assertTrue("the fixture refused the sign-in: $result", result is AuthApi.Result.Ok)
        state.onSignedIn(state.auth.state.value as AuthState.SignedIn)
        state
    }

    @Test fun theWideWindowListsEveryServerByName() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            compose.setContent {
                HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ProvideAppState(state) {
                            SignedInShell(state, VisualThemeSettings(theme = "dark"), { _, _ -> }, {})
                        }
                    }
                }
            }
            compose.waitForIdle()
            // Wait for what the screen is *about*: a conversation row and the frame's server.
            // A capture taken when the list draws is a picture of two spinners.
            compose.waitUntil(180_000) {
                compose.onAllNodesWithTag("space-server-E2E Server").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(hasText("Grace Hopper", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            RenderHarness.run { compose.capture("nav-wide-home") }
            // The rail, not the bar, and the server is there by name.
            assertTrue(
                "a wide window drew the phone's bar",
                compose.onAllNodesWithTag("space-bar").fetchSemanticsNodes().isEmpty(),
            )
            compose.onNodeWithTag("space-rail").assertExists()
            compose.onNodeWithTag("space-server-E2E Server").assertExists()

            // The map, at this width: every server and the four places, on one surface.
            compose.onNodeWithTag("space-more").performClick()
            compose.waitForIdle()
            RenderHarness.run { compose.capture("nav-wide-map") }
        }
    }
}
