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
import lol.mony.harmony.ui.screens.notifications.NotificationsScreen
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
 * The notification inbox as a person sees it, drawn from a fixture instance.
 *
 * The inbox used to be a bare heading row, a `TabRow` of three filters and a `FilterChip` floating
 * under it. This captures the page that replaced them, with the fixture's four rows -- two unread,
 * on two days -- so the grouping and the header's unread count are both visible rather than
 * described.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h900dp-xhdpi")
class AlertsPageRenderTest {
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
                    ProvideAppState(state) { NotificationsScreen(state) }
                }
            }
        }
        compose.waitForIdle()
        // Wait for a row, not for the bar: the bar draws before the repository answers, and a
        // capture of it alone would be a capture of a screen that had not loaded.
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Grace Hopper", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun theInbox() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            draw(state, VisualThemeSettings(theme = "dark"))
            // A follow's preview is the actor's handle, so the row's separate handle line would
            // print it twice. The capture caught that; this keeps it caught.
            val handle = compose.onAllNodes(hasText("@katherine")).fetchSemanticsNodes()
            assertTrue("the follow row should show its actor's handle once, not ${handle.size} times", handle.size == 1)
            RenderHarness.run { compose.capture("page-alerts-dark") }
        }
    }

    @Test fun theInboxInTheLightLook() {
        FixtureInstance().use { instance ->
            instance.start()
            val state = signedIn(instance)
            draw(state, VisualThemeSettings(theme = "light"))
            RenderHarness.run { compose.capture("page-alerts-light") }
        }
    }
}
