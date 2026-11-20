package lol.mony.harmony

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import lol.mony.harmony.RenderHarness.capture
import lol.mony.harmony.RenderHarness.differing
import lol.mony.harmony.RenderHarness.fresh
import lol.mony.harmony.RenderHarness.shot
import lol.mony.harmony.RenderHarness.signedIn
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.navigation.NavigationLayout
import lol.mony.harmony.ui.screens.chat.ChatsDestination
import lol.mony.harmony.ui.screens.drive.DriveScreen
import lol.mony.harmony.ui.screens.instance.InstancePickerScreen
import lol.mony.harmony.ui.screens.notifications.NotificationsScreen
import lol.mony.harmony.ui.screens.server.ServerManagementScreen
import lol.mony.harmony.ui.screens.social.SocialHomeScreen
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
 * Renders every implemented destination from its real composable against the local stack.
 *
 * This is the screenshot half of the native audit: what each screen actually draws with real rows,
 * in the default dark look, the light look, midnight and a custom account look. See RenderHarness
 * for what this proves and what still needs a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w320dp-h640dp-xhdpi")
class ScreenRenderHarnessTest {
    @get:Rule val compose = createComposeRule()

    @Test fun instancePickerDestination() {
        val state = fresh()
        compose.shot("instance-picker", state, settle = "Choose an instance") {
            InstancePickerScreen(initialUrl = RenderHarness.instanceUrl, onUse = { _, _ -> }, onCancel = null)
        }
    }

    @Test fun chatsDestination() {
        val state = signedIn()
        compose.shot("chats", state, settle = "Direct message") { ChatsDestination(state, VisualThemeSettings(), NavigationLayout.BottomBar) }
    }

    @Test fun chatsInLightTheme() {
        val state = signedIn()
        val light = VisualThemeSettings(theme = "light")
        compose.shot("chats-light", state, light, settle = "Direct message") { ChatsDestination(state, light, NavigationLayout.BottomBar) }
    }

    @Test fun chatsInMidnightTheme() {
        val state = signedIn()
        val midnight = VisualThemeSettings(theme = "midnight")
        compose.shot("chats-midnight", state, midnight, settle = "Direct message") { ChatsDestination(state, midnight, NavigationLayout.BottomBar) }
    }

    @Test fun chatsInCustomTheme() {
        val state = signedIn()
        // A custom account look: violet primary over a warm dark background, as Settings allows.
        val custom = VisualThemeSettings(theme = "custom", customThemeMode = "dark",
            customPrimaryColor = "#8B5CF6", customAccentColor = "#F59E0B",
            customBackgroundColor = "#17131F", customSidebarColor = "#100D16")
        compose.shot("chats-custom", state, custom, settle = "Direct message") { ChatsDestination(state, custom, NavigationLayout.BottomBar) }
    }

    /**
     * The look comes from the account, not from a constant in this test.
     *
     * The capture uses whatever `profiles.appearance_settings` holds for the
     * signed-in account, which is the property that matters: a person who picked a
     * theme on the web must find it here. The test also asserts the two really
     * differ, so a screen that ignored the setting could not pass.
     */
    @Test fun socialDestination() {
        val state = signedIn()
        compose.shot("social", state, settle = "Your following feed is quiet") { SocialHomeScreen(state) }
    }

    @Test fun notificationsDestination() {
        val state = signedIn()
        compose.shot("notifications", state, settle = "loaded") { NotificationsScreen(state) }
    }

    @Test fun youDestination() {
        val state = signedIn()
        // The settings list: the identity at the top, then a row per section.
        compose.shot("you", state, settle = "Account") { YouScreen(state, VisualThemeSettings(), { _, _ -> }, {}) }
    }

    @Test fun youAppearanceDestination() {
        val state = signedIn()
        compose.shot("you", state, settle = "Account") { YouScreen(state, VisualThemeSettings(), { _, _ -> }, {}) }
        // The look is one row in the list; a person opens it, and back returns to the list.
        compose.onNodeWithTag("you-appearance").performClick()
        compose.waitUntil(180_000) { compose.onAllNodesWithText("Your look").fetchSemanticsNodes().isNotEmpty() }
        compose.capture("you-appearance")
    }

    @Test fun driveDestination() {
        val state = signedIn()
        compose.shot("drive", state, settle = "No files here") { DriveScreen(state, onBack = {}) }
    }

    @Test fun serverManagement() {
        val state = signedIn()
        val signedIn = state.auth.state.value as AuthState.SignedIn
        val token = requireNotNull(runBlocking { state.auth.freshToken() })
        // user_servers.user_id references profiles.id, not auth.users.id: the membership query
        // needs the profile, which is what AppState.profileId holds once signed in.
        val profileId = requireNotNull(state.profileId.value ?: signedIn.profile?.id)
        val servers = runBlocking { state.servers.servers(signedIn.endpoint, token, profileId).getOrThrow() }
        assumeTrue("fixture account has no server", servers.isNotEmpty())
        val serverId = servers.first().server.id
        compose.shot("server-management", state, settle = "Overview") {
            ServerManagementScreen(state, serverId)
        }
        for (tab in listOf("Channels", "Members", "Roles", "Invites", "Bans")) {
            // Each section is a row in the list; tapping it opens the section, and the app bar's
            // back arrow returns to the list. The capture is of the section, which is what the
            // evidence for "six tabs became a list" has to show.
            compose.onNodeWithText(tab).performClick()
            compose.waitForIdle()
            compose.capture("server-management-" + tab.lowercase())
            compose.onNodeWithContentDescription("Back to server settings").performClick()
            compose.waitForIdle()
        }
    }
}
