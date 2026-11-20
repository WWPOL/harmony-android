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
import lol.mony.harmony.ui.screens.server.ServerManagementScreen
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
 * Server management as a person sees it, drawn from a fixture instance.
 *
 * Six tabs do not fit a 400dp phone, and the last two -- Invites and Bans -- sat off-screen with
 * nothing to say they existed. This captures the list that replaced them and one section opened
 * from it, so the evidence shows both the shape and that a row leads somewhere.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h900dp-xhdpi")
class ServerManagementPageRenderTest {
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
                    ProvideAppState(state) { ServerManagementScreen(state, SERVER_ID) }
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Overview", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun theSectionList() {
        FixtureInstance().use { instance ->
            instance.start()
            draw(signedIn(instance), VisualThemeSettings(theme = "dark"))
            RenderHarness.run { compose.capture("page-server-dark") }
        }
    }

    @Test fun aSectionOpens() {
        FixtureInstance().use { instance ->
            instance.start()
            draw(signedIn(instance), VisualThemeSettings(theme = "dark"))
            compose.onNodeWithTag("server-members").performClick()
            compose.waitForIdle()
            RenderHarness.run { compose.capture("page-server-members-dark") }
        }
    }

    @Test fun theSectionListInTheLightLook() {
        FixtureInstance().use { instance ->
            instance.start()
            draw(signedIn(instance), VisualThemeSettings(theme = "light"))
            RenderHarness.run { compose.capture("page-server-light") }
        }
    }

    private companion object {
        /** The fixture's one server; the screen reads its sections from it. */
        const val SERVER_ID = "s1"
    }
}
