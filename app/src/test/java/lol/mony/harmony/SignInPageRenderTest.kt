package lol.mony.harmony

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import lol.mony.harmony.RenderHarness.capture
import lol.mony.harmony.data.api.AuthApi
import lol.mony.harmony.ui.screens.signin.AuthFormScreen
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
 * The signed-out page, which is the first thing anyone sees of this app.
 *
 * It was a centred column of a wordmark, two fields and five text links stacked under them, with
 * the instance's name in grey underneath the logo. That is the shape a form takes when nothing has
 * decided what it is for. The page is now a branded band that names the instance and offers the
 * one rare action on it, then the form, then the next step -- so the captures below show the
 * information architecture rather than a set of field styles.
 *
 * These are the screen's own composables, drawn against no instance: a sign-in form is the one
 * surface in the app that does not need one, which is why it can be captured directly.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h900dp-xhdpi")
class SignInPageRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun draw(theme: VisualThemeSettings, onSignIn: suspend (String, String) -> AuthApi.Result<Unit>) {
        compose.setContent {
            HarmonyTheme(theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AuthFormScreen(
                        instanceName = "Harmony",
                        instanceDomain = "chat.example.test",
                        onSignIn = onSignIn,
                        onRegister = { _, _, _ -> AuthApi.Result.Failed("Registration is closed") },
                        onForgotPassword = { AuthApi.Result.Ok(Unit) },
                        onChangeInstance = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Use another instance", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun theSignInPage() {
        draw(VisualThemeSettings(theme = "dark")) { _, _ -> AuthApi.Result.Failed("not used") }
        RenderHarness.run { compose.capture("page-signin-dark") }
    }

    @Test fun theSignInPageInTheLightLook() {
        draw(VisualThemeSettings(theme = "light")) { _, _ -> AuthApi.Result.Failed("not used") }
        RenderHarness.run { compose.capture("page-signin-light") }
    }

    /**
     * A refusal is a surface above the button, not a line of red text.
     *
     * The old form printed the instance's sentence in `bodySmall` immediately under the password
     * field, where it read as that field's supporting text. This asserts the sentence is on screen
     * after a refused attempt, so the capture is of a real failure rather than of a form that
     * happens to look fine.
     */
    @Test fun aRefusedSignInIsASurface() {
        draw(VisualThemeSettings(theme = "dark")) { _, _ ->
            AuthApi.Result.Failed("Invalid login credentials")
        }
        compose.onNodeWithTag("signin-email").performTextInput("ada@example.test")
        compose.onNodeWithTag("signin-password").performTextInput("wrong-password")
        compose.onNodeWithTag("signin-submit").performClick()
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Invalid login credentials", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        RenderHarness.run { compose.capture("page-signin-error-dark") }
    }

    /** The next step is a row with a button on it, not the fourth of five stacked links. */
    @Test fun joiningIsReachableAsItsOwnRow() {
        draw(VisualThemeSettings(theme = "dark")) { _, _ -> AuthApi.Result.Failed("not used") }
        compose.onNodeWithText("Join").performClick()
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Create an account", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "the Join row must lead to the registration form",
            compose.onAllNodes(hasText("Create an account", substring = true)).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}

/**
 * The signed-out page in a wide window.
 *
 * A form is the one layout where a wide window is actively worse: a field that spans a tablet is
 * a field whose label sits a hand's width from its content. The band that names the instance stays
 * edge to edge, and the form inside it keeps the readable column every other page uses. This is
 * the same `HarmonySize.readingMaxWidth` cap, asserted at the width where it matters.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w1000dp-h800dp-xhdpi")
class WideSignInPageRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theFormKeepsAReadableColumn() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AuthFormScreen(
                        instanceName = "Harmony",
                        instanceDomain = "chat.example.test",
                        onSignIn = { _, _ -> AuthApi.Result.Failed("not used") },
                        onRegister = { _, _, _ -> AuthApi.Result.Failed("Registration is closed") },
                        onForgotPassword = { AuthApi.Result.Ok(Unit) },
                        onChangeInstance = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Use another instance", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        // The email field is the widest control on the page, so it is the one that would be
        // stretched. The cap is 760dp; allow the form's own 28dp gutters inside that.
        val bounds = compose.onNodeWithTag("signin-email").getUnclippedBoundsInRoot()
        val width = bounds.right - bounds.left
        assertTrue(
            "the email field is ${width.value}dp wide, which is past the readable column",
            width.value <= 760f,
        )
        RenderHarness.run { compose.capture("page-signin-expanded-dark") }
    }
}
