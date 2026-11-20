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
 * The account page as a person sees it, drawn from a fixture instance.
 *
 * Two captures, because this screen changed shape rather than only colour: the settings list a
 * person lands on, and one section opened from it. A capture of the list alone would not prove
 * that a row opens anything, and a capture of a section alone would not show the information
 * architecture that replaced the five-tab row.
 *
 * The rows come from [FixtureInstance], so this needs no server and the same rows appear every
 * run. The waits are on text that only appears once the page's own repository has answered, so a
 * capture can never be of a spinner.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h900dp-xhdpi")
class YouPageRenderTest {
    @get:Rule val compose = createComposeRule()

    /** Signs in against the fixture, with the same calls the app makes against a real instance. */
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
                    ProvideAppState(state) {
                        YouScreen(state, theme, { _, _ -> }, {})
                    }
                }
            }
        }
        compose.waitForIdle()
        // Wait for the bio, not for the section header: the header is static and draws before the
        // profile repository answers, so a capture could otherwise catch the page mid-load with a
        // spinner under the name. The bio exists only once the account has been fetched.
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Mathematics, engines", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun theSettingsList() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            draw(state, VisualThemeSettings(theme = "dark"))
            // The page must be fully loaded, not caught between the name and the profile: a
            // spinner under a resolved name is what the first capture of this page showed.
            assertTrue(
                "the account page was captured before its profile arrived",
                compose.onAllNodes(hasText("Loading your account", substring = true)).fetchSemanticsNodes().isEmpty(),
            )
            RenderHarness.run { compose.capture("page-you-dark") }
            assertTrue(
                "the fixture answered nothing for the account page: ${instance.unhandled()}",
                instance.unhandled().isEmpty(),
            )
        }
    }

    /**
     * A row opens its section, and the section is a page with a title and a way back.
     *
     * The tap is on the row's tag rather than on its text, because the text is what a person
     * reads and the tag is what the test addresses; a row that stopped being tappable would fail
     * here rather than silently staying a label.
     */
    @Test fun aRowOpensItsSection() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            draw(state, VisualThemeSettings(theme = "dark"))
            compose.onNodeWithTag("you-appearance").performClick()
            compose.waitForIdle()
            compose.waitUntil(timeoutMillis = 60_000) {
                compose.onAllNodes(hasText("Your look", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            RenderHarness.run { compose.capture("page-you-appearance-dark") }
        }
    }

    @Test fun theSettingsListInTheLightLook() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            draw(state, VisualThemeSettings(theme = "light"))
            RenderHarness.run { compose.capture("page-you-light") }
        }
    }
}
