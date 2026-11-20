package lol.mony.harmony

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import lol.mony.harmony.ui.navigation.Destination
import lol.mony.harmony.ui.navigation.HarmonyShell
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
 * A wide window, drawn.
 *
 * Robolectric fixes the window's size per test class, so the expanded layout is its own class:
 * at 900dp the frame has to be a rail and the content has to be beside it, and at 400dp it has
 * to be a bottom bar. Keeping them together would mean one of the two never being drawn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w900dp-h700dp-xhdpi")
class WideWindowRenderTest {
    @get:Rule val compose = createComposeRule()

    /**
     * A wide window in the account's own custom accent.
     *
     * The accent is chosen by the test, so what the capture proves is that a person's own accent
     * reaches the frame and the controls rather than only the background.
     */
    @Test fun aTabletWithACustomAccent() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "custom", customThemeMode = "dark",
                customPrimaryColor = "#A855F7", customAccentColor = "#A855F7")) {
                Surface(Modifier.fillMaxSize()) {
                    HarmonyShell(Destination.Chats, {}, chats = 3, mentions = 1) { _ ->
                        DesignGallery.Content()
                    }
                }
            }
        }
        compose.waitForIdle()
        RenderHarness.run { compose.capture("design-tablet-custom") }
        assertTrue("the rail drew nothing",
            compose.onAllNodesWithTag("rail").fetchSemanticsNodes().isNotEmpty())
        assertTrue("a wide window also drew a bottom bar",
            compose.onAllNodesWithTag("nav-bar").fetchSemanticsNodes().isEmpty())
        // Every destination is in the rail, and each is a real target.
        for (label in listOf("Chats", "Social", "Files", "Alerts", "You")) {
            val nodes = compose.onAllNodesWithTag("nav-$label").fetchSemanticsNodes()
            assertTrue("$label is missing from the rail", nodes.isNotEmpty())
            assertTrue("$label is ${nodes.first().size.height}px tall",
                nodes.first().size.height > 0f)
        }
    }
}
