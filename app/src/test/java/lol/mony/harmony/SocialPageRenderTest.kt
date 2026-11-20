package lol.mony.harmony

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import lol.mony.harmony.RenderHarness.capture
import lol.mony.harmony.data.api.AuthApi
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.ProvideAppState
import lol.mony.harmony.ui.screens.social.SocialHomeScreen
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
 * The social feed as a person sees it, drawn from a fixture instance.
 *
 * The timeline used to be five `FilterChip`s in a horizontally scrolling row -- a filter you
 * might not have found, whose selected item has to be found by eye. It is a `PrimaryTabRow` now,
 * and each post is a card rather than a strip between dividers. This captures both.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h900dp-xhdpi")
class SocialPageRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun signedIn(instance: FixtureInstance): AppState = runBlocking {
        val state = AppState(RenderHarness.container())
        state.auth.chooseInstance(Endpoint(instance.baseUrl, "fixture-anon"), "Fixture Harmony")
        val result = state.auth.signIn("ada@example.test", "fixture-password")
        assertTrue("the fixture refused the sign-in: $result", result is AuthApi.Result.Ok)
        state.onSignedIn(state.auth.state.value as AuthState.SignedIn)
        state
    }

    private fun draw(state: AppState, theme: VisualThemeSettings) {
        compose.setContent {
            HarmonyTheme(theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ProvideAppState(state) { SocialHomeScreen(state) }
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Shipped the new frame", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun theFeed() {
        FixtureInstance().use { instance ->
            instance.start()
            draw(signedIn(instance), VisualThemeSettings(theme = "dark"))
            RenderHarness.run { compose.capture("page-social-dark") }
        }
    }

    @Test fun theTimelineTabs() {
        FixtureInstance().use { instance ->
            instance.start()
            draw(signedIn(instance), VisualThemeSettings(theme = "dark"))
            // The timeline is a stated choice now: the tab strip carries all five and the
            // selected one has an indicator, so switching is a visible action.
            compose.onNodeWithTag("social-timelines").assertExists()
            RenderHarness.run { compose.capture("page-social-timelines-dark") }
        }
    }

    @Test fun theFeedInTheLightLook() {
        FixtureInstance().use { instance ->
            instance.start()
            draw(signedIn(instance), VisualThemeSettings(theme = "light"))
            RenderHarness.run { compose.capture("page-social-light") }
        }
    }
}
