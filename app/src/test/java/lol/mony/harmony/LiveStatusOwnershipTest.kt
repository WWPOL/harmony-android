package lol.mony.harmony

import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.LiveStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whose connection the banner is talking about.
 *
 * `AppState.live` is shared by every room, so a status outlives the room that wrote it unless
 * something takes it back. Nothing did: a channel closed while its socket was down left
 * "The live connection dropped. Reconnecting." in the state, and the *next* channel opened
 * under the previous channel's banner until its own socket had joined. On a phone where the
 * network is flaky, that is a strip over every channel the person opens, saying the app is
 * reconnecting to something it is already connected to.
 *
 * These tests pin the two rules that fix it: a room that is opening takes the banner over and
 * starts from "nothing to say yet", and a status from any other room is dropped rather than
 * drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LiveStatusOwnershipTest {
    /** A signed-out container is enough: nothing here touches the network. */
    private fun state(): AppState = RenderHarness.fresh()

    private val dropped = LiveStatus.Reconnecting(2, "The live connection dropped.")

    @Test fun theRoomThatIsOpenOwnsTheBanner() {
        val state = state()
        state.ownLive("channel-a")
        state.markLive("channel-a", dropped)
        assertEquals("the owning room's status was dropped", dropped, state.live.value)
    }

    /**
     * The reported bug: the next room must not open under the last one's banner.
     */
    @Test fun aNewRoomDoesNotInheritTheLastRoomsReconnect() {
        val state = state()
        state.ownLive("channel-a")
        state.markLive("channel-a", dropped)
        state.releaseLive("channel-a")

        state.ownLive("channel-b")
        assertTrue("the new room opened under the old room's banner: ${state.live.value}",
            state.live.value is LiveStatus.Connecting)
    }

    /**
     * A socket that is still winding down must not write over the room that replaced it.
     *
     * Closing a room cancels its flow, but the last event can already be in flight; a status
     * that arrives after the next room has taken over belongs to nobody.
     */
    @Test fun aStatusFromARoomThatIsGoneIsDropped() {
        val state = state()
        state.ownLive("channel-a")
        state.releaseLive("channel-a")
        state.ownLive("channel-b")
        state.markLive("channel-b", LiveStatus.Connected)

        // The closed room's socket reports its drop late.
        state.markLive("channel-a", dropped)

        assertEquals("a closed room's status was drawn", LiveStatus.Connected, state.live.value)
    }

    /** And a room that is closed says nothing at all, rather than keeping its own last word. */
    @Test fun closingARoomClearsWhatItSaid() {
        val state = state()
        state.ownLive("channel-a")
        state.markLive("channel-a", dropped)
        state.releaseLive("channel-a")
        assertTrue("a closed room left its banner up: ${state.live.value}",
            state.live.value is LiveStatus.Connecting)
    }

    /**
     * A room that is released after another has taken over does not clear the new one.
     *
     * `LaunchedEffect(roomId)` cancels the old collector and starts the new one, and the two
     * can interleave: the old room's `releaseLive` may run after the new room's `ownLive`.
     */
    @Test fun aLateReleaseDoesNotClearTheRoomThatReplacedIt() {
        val state = state()
        state.ownLive("channel-a")
        state.ownLive("channel-b")
        state.markLive("channel-b", LiveStatus.Connected)

        state.releaseLive("channel-a")

        assertEquals("a stale release cleared the live room's status",
            LiveStatus.Connected, state.live.value)
        assertFalse("the banner claims a reconnect while connected", state.isReconnecting)
    }

    /** The convenience the banner reads stays in step with the status it summarises. */
    @Test fun isReconnectingTracksTheStatus() {
        val state = state()
        state.ownLive("channel-a")
        state.markLive("channel-a", LiveStatus.Connected)
        assertFalse(state.isReconnecting)
        state.markLive("channel-a", dropped)
        assertTrue(state.isReconnecting)
        state.markLive("channel-a", LiveStatus.Connecting)
        assertFalse(state.isReconnecting)
    }
}
