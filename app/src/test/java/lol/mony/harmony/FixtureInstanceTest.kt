package lol.mony.harmony

import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.runBlocking
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.AuthApi
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.screens.you.YouScreen
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A page drawn from a fixture instance, the way the app draws it from a real one.
 *
 * The point of this file is the pattern, not the one test in it: a [FixtureInstance] answers the
 * PostgREST reads a screen makes, the screen draws its own composables from those rows, and the
 * capture is of a **page**. The first round of evidence shipped a gallery of tokens instead,
 * because there was no instance to draw from -- and a gallery cannot show whether a settings page
 * is a settings page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h900dp-xhdpi")
class FixtureInstanceTest {
    @get:Rule val compose = createComposeRule()

    /** Signs in against the fixture, the same calls the app makes against a real instance. */
    private fun signedIn(instance: FixtureInstance): AppState = runBlocking {
        val state = AppState(RenderHarness.container())
        val endpoint = Endpoint(instance.baseUrl, "fixture-anon")
        state.auth.chooseInstance(endpoint, "Fixture Harmony")
        val result = state.auth.signIn("ada@example.test", "fixture-password")
        assertTrue("the fixture refused the sign-in: $result", result is AuthApi.Result.Ok)
        state.onSignedIn(state.auth.state.value as AuthState.SignedIn)
        state
    }

    /**
     * The fixture is enough to draw the account page with the account's own name on it.
     *
     * If the fixture answered nothing, the page would show its loading or error state and the
     * wait below would time out -- which is the assertion: a capture can never be of a spinner.
     */
    @Test fun theFixtureDrawsTheAccountPage() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            assumeTrue("no fixture session", state.auth.state.value is AuthState.SignedIn)
            compose.setContent {
                lol.mony.harmony.ui.theme.HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                    lol.mony.harmony.ui.ProvideAppState(state) {
                        YouScreen(state, VisualThemeSettings(theme = "dark"), { _, _ -> }, {})
                    }
                }
            }
            compose.waitForIdle()
            compose.waitUntil(timeoutMillis = 60_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText("Ada Lovelace", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(
                "the fixture answered nothing: ${instance.unhandled()}",
                instance.unhandled().isEmpty(),
            )
        }
    }
}
