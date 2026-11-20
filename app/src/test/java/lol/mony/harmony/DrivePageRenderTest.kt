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
import lol.mony.harmony.ui.screens.drive.DriveScreen
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
 * The Drive browser as a person sees it, drawn from a fixture instance.
 *
 * The page used to open with four bands: a heading row, a quota block, a location row and a
 * horizontally scrolling strip of six `FilterChip`s -- so on a phone the type filter's last
 * choices were off-screen and the row read as an unordered set of tags. This captures the page
 * that replaced them: one app bar carrying the quota, and one filter row with the type as a menu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h900dp-xhdpi")
class DrivePageRenderTest {
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
                    ProvideAppState(state) { DriveScreen(state, onBack = {}) }
                }
            }
        }
        compose.waitForIdle()
        // Wait for a file's name, not for the app bar. The bar draws before the page's own
        // repository answers, so a wait on "Files" succeeds while the list is still a spinner --
        // which is exactly what the first version of this test captured.
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("design-notes.md", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun theBrowser() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            // Prove the rows reach the screen's own repository before capturing: a capture of an
            // empty list would look like a design decision rather than a fixture that answered
            // nothing, and the error would be a decoding detail nobody sees on screen.
            val session = state.auth.state.value as AuthState.SignedIn
            val token = runBlocking { state.auth.freshToken() } ?: error("no token")
            val page = runBlocking {
                lol.mony.harmony.data.repo.DriveRepository(state.container.rest)
                    .files(session.endpoint, token, lol.mony.harmony.data.model.DriveQuery())
            }
            assertTrue("the fixture's Drive rows did not decode: $page", page.isSuccess)
            assertTrue(
                "the fixture's Drive list is empty: ${page.getOrNull()?.entries}",
                page.getOrThrow().entries.isNotEmpty(),
            )
            draw(state, VisualThemeSettings(theme = "dark"))
            RenderHarness.run { compose.capture("page-drive-dark") }
        }
    }

    /** The type filter is one control, and opening it lists every type with a tick on the chosen one. */
    @Test fun theTypeMenu() {
        FixtureInstance().use { instance ->
            instance.start()
            draw(signedIn(instance), VisualThemeSettings(theme = "dark"))
            compose.onNodeWithTag("drive-kind").performClick()
            compose.waitForIdle()
            RenderHarness.run { compose.capture("page-drive-types-dark") }
        }
    }

    @Test fun theBrowserInTheLightLook() {
        FixtureInstance().use { instance ->
            instance.start()
            draw(signedIn(instance), VisualThemeSettings(theme = "light"))
            RenderHarness.run { compose.capture("page-drive-light") }
        }
    }
}
