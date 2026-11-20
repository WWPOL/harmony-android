package lol.mony.harmony

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import lol.mony.harmony.RenderHarness.capture
import lol.mony.harmony.data.model.Channel
import lol.mony.harmony.data.model.Profile
import lol.mony.harmony.data.model.VoiceConfig
import lol.mony.harmony.data.model.VoiceParticipant
import lol.mony.harmony.data.voice.CallControls as Controls
import lol.mony.harmony.data.voice.CallParticipant
import lol.mony.harmony.data.voice.CallState
import lol.mony.harmony.data.voice.SpatialAudioSettings
import lol.mony.harmony.ui.components.SectionHeader
import lol.mony.harmony.ui.screens.chat.CallControls
import lol.mony.harmony.ui.screens.chat.CallEnded
import lol.mony.harmony.ui.screens.chat.CallParticipantRow
import lol.mony.harmony.ui.screens.chat.JoinLobby
import lol.mony.harmony.ui.screens.chat.rosterSummary
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
 * The voice channel's surfaces, drawn from the screen's own composables.
 *
 * The call itself needs a LiveKit room and an audio device, which a JVM test cannot have. What it
 * *can* have is the three things a person actually looks at: the lobby before joining, the roster
 * while in a call, and the control bar. Those are plain composables over plain data, so they are
 * `internal` and drawn here.
 *
 * The screen used to be a centred paragraph and a Join button over a list of names, with the
 * controls as six bare icon buttons in a row. The captures are the argument that it is now a
 * page: the people already in the channel are the reason to join, so they are the lobby's body.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h900dp-xhdpi")
class VoicePageRenderTest {
    @get:Rule val compose = createComposeRule()

    private val channel = Channel(
        id = "c-voice", serverId = "s1", name = "Design standup", type = 1,
    )

    /** The two people the fixture's roster names, so the lobby draws names and not "Someone". */
    private val people = mapOf(
        "p2" to Profile(id = "p2", username = "grace", displayName = "Grace Hopper"),
        "p3" to Profile(id = "p3", username = "alan", displayName = "Alan Turing"),
    )

    private fun participant(userId: String, muted: Boolean = false, video: Boolean = false) = VoiceParticipant(
        channelId = channel.id, serverId = "s1", userId = userId,
        isMuted = muted, isVideoEnabled = video, joinedAt = "2026-11-20T18:00:00Z",
    )

    private fun draw(theme: String = "dark", content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = theme)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The lobby, with people already in the channel.
     *
     * This is the state that matters: an empty lobby is a prompt, a populated one is a decision.
     */
    @Test fun theLobbyWithPeopleAlreadyInIt() {
        draw {
            JoinLobby(
                channel = channel,
                config = VoiceConfig(enabled = true, mode = "livekit"),
                loaded = true,
                roster = listOf(participant("p2"), participant("p3", muted = true)),
                people = people,
                baseUrl = "https://chat.example.test",
                microphoneGranted = true,
                onJoin = {},
            )
        }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Join the call", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        RenderHarness.run { compose.capture("page-voice-lobby-dark") }
    }

    /** The lobby on an instance with no voice server: the reason, and no button to press. */
    @Test fun theLobbyWhenThisInstanceHasNoVoice() {
        draw {
            JoinLobby(
                channel = channel,
                config = VoiceConfig(enabled = false, mode = "livekit"),
                loaded = true,
                roster = emptyList(),
                people = people,
                baseUrl = "https://chat.example.test",
                microphoneGranted = false,
                onJoin = {},
            )
        }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("no voice server", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "an instance with no voice must not offer a Join button",
            compose.onAllNodes(hasText("Join the call", substring = true)).fetchSemanticsNodes().isEmpty(),
        )
        RenderHarness.run { compose.capture("page-voice-unavailable-dark") }
    }

    /**
     * The roster inside a call, with one person speaking.
     *
     * Speaking is drawn as a ring and a tinted row rather than a dot, so the capture is the
     * assertion: a row that stopped marking the speaker would look different here.
     */
    @Test fun theRosterMarksWhoIsSpeaking() {
        draw {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(8.dp)) {
                SectionHeader("In the call", supporting = "3 people")
                CallParticipantRow(
                    participant = CallParticipant(identity = "p2", name = "Grace Hopper", isSpeaking = true),
                    person = people["p2"], baseUrl = "https://chat.example.test", mix = null,
                )
                CallParticipantRow(
                    participant = CallParticipant(identity = "p3", name = "Alan Turing", isMuted = true),
                    person = people["p3"], baseUrl = "https://chat.example.test", mix = null,
                )
                CallParticipantRow(
                    participant = CallParticipant(identity = "me", name = "Ada Lovelace", isLocal = true),
                    person = null, baseUrl = "https://chat.example.test", mix = null,
                )
            }
        }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Ada Lovelace", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        RenderHarness.run { compose.capture("page-voice-roster-dark") }
    }

    /** The control bar, with the microphone off and the camera on. */
    @Test fun theControlBar() {
        draw {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                CallControls(
                    controls = Controls(microphoneOn = false, cameraOn = true, speakerOn = true),
                    microphoneGranted = true,
                    onMicrophone = {}, onDeafen = {}, onCamera = {}, onShareScreen = {},
                    onSpeaker = {}, onLeave = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Leave", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        RenderHarness.run { compose.capture("page-voice-controls-dark") }
    }

    /** A call the server ended: the reason, and both things a person can do about it. */
    @Test fun aCallThatEnded() {
        draw { CallEnded(reason = "The channel was deleted.", onRejoin = {}, onLeave = {}) }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("The call ended", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        RenderHarness.run { compose.capture("page-voice-ended-dark") }
    }

    /**
     * The same three surfaces in the light palette.
     *
     * The control bar is the reason this exists: its "off" state is an outline whose contrast
     * depends on the theme's `outline` role, and a tone that reads on a dark bar can vanish on a
     * light one. Drawing it in both palettes is how that gets caught before it ships.
     */
    @Test fun theControlBarInTheLightPalette() {
        draw(theme = "light") {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                CallControls(
                    controls = Controls(microphoneOn = true, cameraOn = false, speakerOn = false),
                    microphoneGranted = true,
                    onMicrophone = {}, onDeafen = {}, onCamera = {}, onShareScreen = {},
                    onSpeaker = {}, onLeave = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Leave", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        RenderHarness.run { compose.capture("page-voice-controls-light") }
    }

    @Test fun theLobbyInTheLightPalette() {
        draw(theme = "light") {
            JoinLobby(
                channel = channel,
                config = VoiceConfig(enabled = true, mode = "livekit"),
                loaded = true,
                roster = listOf(participant("p2"), participant("p3", muted = true)),
                people = people,
                baseUrl = "https://chat.example.test",
                microphoneGranted = false,
                onJoin = {},
            )
        }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Join the call", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        RenderHarness.run { compose.capture("page-voice-lobby-light") }
    }

    /**
     * The bar's toggles each fit without clipping at a 400dp phone.
     *
     * Five 48dp targets plus Leave overran this width when the labels were spaced 20dp apart,
     * and the last toggle was drawn half under the pinned Leave button. The capture is how that
     * was seen; this is how it stays fixed. Every toggle must be reachable and at least a touch
     * target wide.
     */
    @Test fun everyToggleFitsOnAPhone() {
        draw {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                CallControls(
                    controls = Controls(microphoneOn = false, cameraOn = false, speakerOn = false),
                    microphoneGranted = true,
                    onMicrophone = {}, onDeafen = {}, onCamera = {}, onShareScreen = {},
                    onSpeaker = {}, onLeave = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Leave", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        val tags = listOf("call-mic", "call-deafen", "call-camera", "call-screen", "call-speaker", "call-leave")
        val screenWidth = compose.onAllNodes(androidx.compose.ui.test.isRoot())
            .fetchSemanticsNodes().first().size.width.toFloat()
        for (tag in tags) {
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            val bounds = node.boundsInRoot
            assertTrue(
                "$tag starts off the left edge of the bar: ${bounds.left}",
                bounds.left >= 0f,
            )
            assertTrue(
                "$tag runs past the right edge of the bar: ${bounds.right} > $screenWidth",
                bounds.right <= screenWidth + 0.5f,
            )
            assertTrue(
                "$tag is smaller than a touch target: ${bounds.width}dp wide",
                bounds.width >= 48f,
            )
        }
        // Leave is the last thing on the bar and must not be overlapped by a scrolling toggle.
        val leave = compose.onNodeWithTag("call-leave").fetchSemanticsNode().boundsInRoot
        val speaker = compose.onNodeWithTag("call-speaker").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the last toggle ($speaker) overlaps Leave ($leave)",
            speaker.right <= leave.left + 0.5f,
        )
    }

    /**
     * The bar's second line counts people, and never says "1 people".
     *
     * It is the only text on the screen that is generated from a count, so it is the one place a
     * plural can go wrong silently.
     */
    @Test fun theCallSummaryCountsPeople() {
        fun connected(vararg others: String) = CallState.Connected(
            roomName = "channel-c-voice",
            participants = listOf(CallParticipant(identity = "me", name = "Me", isLocal = true)) +
                others.map { CallParticipant(identity = it, name = it) },
        )
        assertEquals("You are the only one here", rosterSummary(connected(), "me"))
        assertEquals("1 other person in the call", rosterSummary(connected("a"), "me"))
        assertEquals("3 people in the call", rosterSummary(connected("a", "b", "c"), "me"))
        assertEquals("Voice channel", rosterSummary(null, "me"))
    }
}
