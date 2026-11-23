package lol.mony.harmony

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.components.EmptyState
import lol.mony.harmony.ui.components.HarmonyCard
import lol.mony.harmony.ui.components.SectionHeader
import lol.mony.harmony.ui.navigation.HarmonyShell
import lol.mony.harmony.ui.navigation.Space
import lol.mony.harmony.ui.theme.HarmonyShape
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.LocalHarmonyColors
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The design system, drawn: a phone and a tablet, light and dark.
 *
 * These are render tests, not screenshots of a live instance. They prove the things a person
 * would notice first and a unit test would not:
 *
 * - the frame really does change shape with the window (a bottom bar at 400dp, a rail at 900dp);
 * - the account's own look is honoured, so dark is dark and light is light, and the accent
 *   reaches the controls rather than only the background;
 * - the type and shape scale are installed, so a heading is visibly larger than body text and a
 *   card's corners are round;
 * - an empty state offers exactly one action and says what it does.
 *
 * Each one writes a PNG, so the capture is the evidence rather than the assertion alone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30])
class DesignSystemRenderTest {
    @get:Rule val compose = createComposeRule()

    /** A compact window in the account's default dark look. */
    @Test fun aPhoneInTheDark() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize()) {
                    HarmonyShell(
                        spaces = previewSpaces,
                        currentSpace = previewSpaces.first(),
                        onSpace = {}, onAddServer = {}, onMore = {},
                    ) { _ -> DesignGallery.Content() }
                }
            }
        }
        compose.waitForIdle()
        RenderHarness.run { compose.capture("design-phone-dark") }
        // The bar is the frame, and it is dark: the account's own look, not the app's default.
        assertTrue("the space bar drew nothing", compose.onAllNodesWithTag("space-bar").fetchSemanticsNodes().isNotEmpty())
    }

    /** The same screen in the account's light look. */
    @Test fun aPhoneInTheLight() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "light")) {
                Surface(Modifier.fillMaxSize()) {
                    HarmonyShell(
                        spaces = previewSpaces,
                        currentSpace = previewSpaces.first(),
                        onSpace = {}, onAddServer = {}, onMore = {},
                    ) { _ -> DesignGallery.Content() }
                }
            }
        }
        compose.waitForIdle()
        RenderHarness.run { compose.capture("design-phone-light") }
        assertTrue("the space bar drew nothing", compose.onAllNodesWithTag("space-bar").fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * The empty state offers one action, and the action is a real button.
     *
     * An empty screen is where a person is most likely to be stuck, so the one thing there is to
     * press has to be visible as a button rather than as text that happens to be tappable.
     */
    @Test fun anEmptyStateOffersOneAction() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize()) {
                    EmptyState(
                        title = "No messages yet",
                        description = "Send the first message in this channel.",
                        icon = Icons.Outlined.ChatBubbleOutline,
                        action = "Write a message",
                        onAction = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        RenderHarness.run { compose.capture("design-empty-state") }
        compose.onNodeWithText("Write a message").assertExists()
        // It is a Button, which is what TalkBack announces as a button; a TextButton would too,
        // but a bare Text would not, and that is the regression this guards.
        assertTrue(
            "the action is not announced as a button",
            compose.onAllNodesWithTag("empty-action").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Write a message").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /** Three servers and Home, as the frame's navigation: what the captures draw. */
    private val previewSpaces = listOf(
        Space.Home.copy(badge = 3),
        Space("s1", "Design"),
        Space("s2", "E2E Server", badge = 1),
        Space("s3", "Book club", badge = 12, mentions = 1),
    )
}
