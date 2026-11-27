package lol.mony.harmony

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.LiveStatus
import lol.mony.harmony.ui.screens.chat.ConnectionBanner
import lol.mony.harmony.ui.screens.chat.RECONNECT_GRACE_MILLIS
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a dropped connection looks like, and what the app is doing about it.
 *
 * The banner is the whole of the reported problem's visible half. Before this change it said
 * "Live connection unavailable. Refresh to update." and offered a Retry button: it put the work
 * of reconnecting on the person, and nothing happened until they pressed it. The state it draws
 * now says the app is reconnecting by itself, and its button only skips the wait.
 *
 * These are render tests: they prove what the composable draws and what its button is called,
 * and they capture a PNG so the pixels are the evidence rather than the assertion alone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h800dp-xhdpi")
class ReconnectBannerRenderTest {
    @get:Rule val compose = createComposeRule()

    /**
     * A reconnecting banner says so, counts the attempts, and offers to skip the wait.
     *
     * "Retry now" rather than "Retry" is the wording the state requires: the app is already
     * retrying, and the button only makes the next attempt happen sooner.
     */
    @Test fun aReconnectingBannerSaysTheAppIsPuttingItBack() {
        var pressed = 0
        // An outage is reported only once it has outlasted the grace period; this test is about
        // what it says when it is reported, so the clock is advanced past that first.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Text("A room, with its connection down", Modifier.padding(16.dp))
                        ConnectionBanner(
                            LiveStatus.Reconnecting(2, "The live connection dropped."),
                        ) { pressed++ }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(RECONNECT_GRACE_MILLIS + 100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        RenderHarness.run { compose.capture("connection-reconnecting") }

        compose.onNodeWithTag("connection-banner").assertExists()
        compose.onNodeWithText("Reconnecting (attempt 2)", substring = true).assertExists()
        compose.onNodeWithText("Retry now").assertExists()
        compose.onNodeWithTag("connection-retry").performClick()
        assertEquals("the banner's button did nothing", 1, pressed)
    }

    /** The first attempt's wording does not claim a count it has not reached. */
    @Test fun theFirstAttemptDoesNotInventARetryCount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize()) {
                    ConnectionBanner(
                        LiveStatus.Reconnecting(1, "The live connection closed."),
                    ) { }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(RECONNECT_GRACE_MILLIS + 100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("attempt 1", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Reconnecting", substring = true).assertExists()
    }

    /**
     * A refusal is a different thing: no retry is coming, and the banner says so.
     *
     * Drawing this as "reconnecting" would be a lie the app then acted on by never retrying.
     */
    @Test fun aRefusedConnectionIsNotDrawnAsAReconnect() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize()) {
                    ConnectionBanner(
                        LiveStatus.Unavailable(
                            "The instance would not open live updates for this room. Pull to refresh."),
                    ) { }
                }
            }
        }
        compose.waitForIdle()
        RenderHarness.run { compose.capture("connection-unavailable") }
        compose.onNodeWithText("Pull to refresh", substring = true).assertExists()
        compose.onNodeWithText("Reconnecting", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("connection-retry").assertExists()
    }

    /** A connected room draws no strip at all: a healthy connection is not news. */
    @Test fun aConnectedRoomDrawsNothing() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize()) { ConnectionBanner(LiveStatus.Connected) { } }
            }
        }
        compose.waitForIdle()
        assertTrue("a connected room drew a banner",
            compose.onAllNodesWithTag("connection-banner").fetchSemanticsNodes().isEmpty())
    }

    /** And neither does the first attempt, before it has answered. */
    @Test fun theFirstAttemptDrawsNothing() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize()) { ConnectionBanner(LiveStatus.Connecting) { } }
            }
        }
        compose.waitForIdle()
        assertTrue("the first attempt drew a banner",
            compose.onAllNodesWithTag("connection-banner").fetchSemanticsNodes().isEmpty())
    }

    /**
     * The reported bug: a drop that heals itself is not worth a strip.
     *
     * A socket drops for reasons that heal in a second -- a Wi-Fi handover, a radio waking, an
     * instance restarting behind a load balancer -- and the reconnect loop puts it back without
     * the person noticing anything but the banner appearing and leaving again. Drawing that is
     * what made the app feel like it was permanently disconnecting.
     */
    @Test fun aBlipThatHealsBeforeTheGracePeriodDrawsNothing() {
        val status = androidx.compose.runtime.mutableStateOf<LiveStatus>(
            LiveStatus.Reconnecting(1, "The live connection dropped."))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize()) { ConnectionBanner(status.value) { } }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        // Inside the grace period: still a blip nobody should be told about.
        compose.mainClock.advanceTimeBy(RECONNECT_GRACE_MILLIS / 2)
        assertTrue("a blip drew a banner",
            compose.onAllNodesWithTag("connection-banner").fetchSemanticsNodes().isEmpty())

        // The loop got the room back before the grace period ran out.
        status.value = LiveStatus.Connected
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue("a healed blip left a banner behind",
            compose.onAllNodesWithTag("connection-banner").fetchSemanticsNodes().isEmpty())
    }

    /**
     * An outage that lasts is reported, and says what the app is doing about it.
     *
     * The grace period must not become a way of hiding a real disconnect: this is the other half
     * of the same rule.
     */
    @Test fun anOutageThatOutlastsTheGracePeriodIsReported() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize()) {
                    ConnectionBanner(LiveStatus.Reconnecting(1, "The live connection dropped.")) { }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("the banner was drawn before the grace period ran out",
            compose.onAllNodesWithTag("connection-banner").fetchSemanticsNodes().isEmpty())
        compose.mainClock.advanceTimeBy(RECONNECT_GRACE_MILLIS + 100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("connection-banner").assertExists()
        compose.onNodeWithText("Reconnecting", substring = true).assertExists()
        compose.onNodeWithText("Retry now").assertExists()
    }

    /**
     * A refusal is drawn at once: it is not a blip, and waiting would only delay the one thing
     * the person can act on.
     */
    @Test fun aRefusalIsDrawnWithoutWaiting() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize()) {
                    ConnectionBanner(LiveStatus.Unavailable("The instance would not open live updates.")) { }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("connection-banner").assertExists()
    }
}
