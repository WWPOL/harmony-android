package lol.mony.harmony

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.runBlocking
import lol.mony.harmony.RenderHarness.capture
import lol.mony.harmony.data.api.AuthApi
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.ProvideAppState
import lol.mony.harmony.ui.screens.you.YouScreen
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
 * The redesigned pages at 1000dp, where a tablet or an unfolded phone would draw them.
 *
 * The task asked for compact *and* expanded captures, and a page that only fits a phone has not
 * been designed for the window it runs in. This draws the account page and the inbox at the
 * expanded width, from the same fixture, so the two widths can be compared side by side.
 *
 * Robolectric fixes a window per class, so this is its own class rather than a parameter.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w1000dp-h800dp-xhdpi")
class ExpandedPagesRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun signedIn(instance: FixtureInstance): AppState = runBlocking {
        val state = AppState(RenderHarness.container())
        state.auth.chooseInstance(Endpoint(instance.baseUrl, "fixture-anon"), "Fixture Harmony")
        val result = state.auth.signIn("ada@example.test", "fixture-password")
        assertTrue("the fixture refused the sign-in: $result", result is AuthApi.Result.Ok)
        state.onSignedIn(state.auth.state.value as AuthState.SignedIn)
        state
    }

    @Test fun theAccountPageAtExpandedWidth() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            compose.setContent {
                HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ProvideAppState(state) {
                            YouScreen(state, VisualThemeSettings(theme = "dark"), { _, _ -> }, {})
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.waitUntil(timeoutMillis = 60_000) {
                compose.onAllNodes(hasText("Account", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            RenderHarness.run { compose.capture("page-you-expanded-dark") }
        }
    }

    @Test fun theInboxAtExpandedWidth() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            compose.setContent {
                HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ProvideAppState(state) {
                            lol.mony.harmony.ui.screens.notifications.NotificationsScreen(state)
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.waitUntil(timeoutMillis = 60_000) {
                compose.onAllNodes(hasText("Grace Hopper", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            RenderHarness.run { compose.capture("page-alerts-expanded-dark") }
        }
    }
}
