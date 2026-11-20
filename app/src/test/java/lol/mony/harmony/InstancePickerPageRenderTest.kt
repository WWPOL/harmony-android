package lol.mony.harmony

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import lol.mony.harmony.RenderHarness.capture
import lol.mony.harmony.ui.screens.instance.InstancePickerPage
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
 * The instance picker, drawn as the page it is.
 *
 * This is the first screen a person sees after installing the app, and it was the last screen
 * still shaped like the old app: a centred column of a wordmark, a heading, a paragraph, a field
 * and a button. It is now the signed-out page's sibling -- a tinted band carrying the wordmark and
 * the question, the input in the app's reading column, a refusal on an `errorContainer` surface.
 *
 * `InstancePickerPage` takes its state from the caller so the refused state can be drawn here: it
 * is otherwise reachable only by typing a bad address and waiting for a network call.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h900dp-xhdpi")
class InstancePickerPageRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun draw(theme: String = "dark", content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = theme)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.waitForIdle()
    }

    @Test fun thePickerAskingForAnAddress() {
        draw {
            InstancePickerPage(
                url = "",
                busy = false,
                error = null,
                onUrlChange = {},
                onConnect = {},
                onCancel = null,
            )
        }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Choose an instance", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        RenderHarness.run { compose.capture("page-instance-picker-dark") }
    }

    @Test fun thePickerInTheLightPalette() {
        draw(theme = "light") {
            InstancePickerPage(
                url = "",
                busy = false,
                error = null,
                onUrlChange = {},
                onConnect = {},
                onCancel = null,
            )
        }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Choose an instance", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        RenderHarness.run { compose.capture("page-instance-picker-light") }
    }

    /**
     * A refused address: the instance's answer on an error surface above the button.
     *
     * The old page drew this as `bodySmall` red text directly under the field, where it read as
     * that field's supporting text rather than as the reason the connection failed.
     */
    @Test fun thePickerRefusingAnAddress() {
        draw {
            InstancePickerPage(
                url = "http://har.mony.lol",
                busy = false,
                error = "Use HTTPS to protect your account.",
                onUrlChange = {},
                onConnect = {},
                onCancel = null,
            )
        }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Use HTTPS", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        RenderHarness.run { compose.capture("page-instance-picker-error-dark") }
    }

    /**
     * A refusal is on the page, and Connect stays a full touch target.
     *
     * Both are the sort of thing a layout change loses silently: an error that only exists in the
     * model, or a button shrunk to fit a message.
     */
    @Test fun aRefusalIsDrawnAndConnectStaysTappable() {
        draw {
            InstancePickerPage(
                url = "har.mony.lol",
                busy = false,
                error = "Could not connect. Check the address and your network connection.",
                onUrlChange = {},
                onConnect = {},
                onCancel = null,
            )
        }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Could not connect", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "the refusal must be its own node, tagged for a test to find",
            compose.onAllNodes(hasText("Could not connect", substring = true)).fetchSemanticsNodes().isNotEmpty(),
        )
        val connect = compose.onNodeWithTag("instance-connect").fetchSemanticsNode().boundsInRoot
        assertTrue("Connect is ${connect.height}dp tall, under a touch target", connect.height >= 48f)
        val field = compose.onNodeWithTag("instance-url").fetchSemanticsNode().boundsInRoot
        assertTrue("the address field is ${field.height}dp tall, under a touch target", field.height >= 48f)
    }
}

/**
 * The picker at 1000dp, where the field would otherwise be a thousand pixels wide.
 *
 * The band is edge to edge and the form keeps the app's 760dp reading column, which is the same
 * rule the wide sign-in page follows -- the two signed-out pages have to agree about what a wide
 * window means, or moving between them looks like moving between two apps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w1000dp-h800dp-xhdpi")
class WideInstancePickerPageRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theFieldKeepsAReadableColumn() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    InstancePickerPage(
                        url = "har.mony.lol",
                        busy = false,
                        error = null,
                        onUrlChange = {},
                        onConnect = {},
                        onCancel = null,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Choose an instance", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        val bounds = compose.onNodeWithTag("instance-url").getUnclippedBoundsInRoot()
        val width = bounds.right - bounds.left
        assertTrue(
            "the address field is ${width.value}dp wide, which is past the readable column",
            width.value <= 760f,
        )
        RenderHarness.run { compose.capture("page-instance-picker-expanded-dark") }
    }
}
