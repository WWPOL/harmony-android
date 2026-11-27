package lol.mony.harmony

import lol.mony.harmony.data.model.Channel
import lol.mony.harmony.data.model.Thread
import lol.mony.harmony.ui.AppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The state the channel list and the DM list are drawn from: which threads belong to which
 * channel, and what a cache is allowed to keep.
 *
 * Two bugs sit here. An open thread did not appear in the side channel list at all, because
 * nothing remembered it per channel; and every navigation re-read a list that had not
 * changed, because nothing was kept. A cache must also never outlive the account that filled
 * it, which is the leak these tests pin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppStateCacheTest {
    private fun state(): AppState =
        AppState(lol.mony.harmony.AppContainer(org.robolectric.RuntimeEnvironment.getApplication()))

    private fun thread(id: String, channel: String, name: String = id) =
        Thread(id = id, channelId = channel, name = name, messageCount = 3)

    // ---- threads belong to their channel ---------------------------------------------------

    @Test fun anOpenThreadIsListedUnderItsOwnChannel() {
        val state = state()
        state.rememberThreads("c1", listOf(thread("t1", "c1", "Release planning")))
        state.rememberThreads("c2", listOf(thread("t2", "c2")))

        val byChannel = state.threadsByChannel.value
        assertEquals(listOf("t1"), byChannel.getValue("c1").map { it.id })
        assertEquals(listOf("t2"), byChannel.getValue("c2").map { it.id })
    }

    @Test fun aNewThreadAppearsWithoutWaitingForAReload() {
        val state = state()
        state.rememberThreads("c1", listOf(thread("old", "c1")))
        state.rememberThread(thread("fresh", "c1"))

        assertEquals(listOf("fresh", "old"), state.threadsByChannel.value.getValue("c1").map { it.id })
    }

    @Test fun rememberingTheSameThreadTwiceDoesNotDuplicateIt() {
        val state = state()
        state.rememberThread(thread("t1", "c1"))
        state.rememberThread(thread("t1", "c1"))
        assertEquals(1, state.threadsByChannel.value.getValue("c1").size)
    }

    /**
     * A thread that is not open is not nested under its channel.
     *
     * `rememberThread` is what a room calls the moment it starts or opens a thread, so it is
     * the one place a closed thread could sneak back into the column. The reference's sidebar
     * keeps the open ones only (`activeChannelThreads`), and so does this.
     */
    @Test fun aClosedThreadIsNotNestedUnderItsChannel() {
        val state = state()
        val longAgo = java.time.Instant.now().minusSeconds(3 * 24 * 60 * 60).toString()
        state.rememberThread(Thread(id = "archived", channelId = "c1", archived = true))
        state.rememberThread(Thread(id = "expired", channelId = "c1",
            autoArchiveDuration = 1440, lastActivityAt = longAgo))

        assertTrue("a closed thread was nested under its channel: ${state.threadsByChannel.value["c1"]}",
            state.threadsByChannel.value["c1"].orEmpty().isEmpty())
    }

    /**
     * Answering a thread keeps it in the column, and answering a quiet one brings it back.
     *
     * A thread leaves the column when its own auto-archive clock runs out, and a reply is what
     * moves that clock. Without this the thread a person is reading would vanish from under its
     * channel the moment it aged past its window, which is the reported screen: in a thread,
     * thread active, and no row for it in the list.
     */
    @Test fun replyingToAThreadKeepsItInTheColumn() {
        val state = state()
        val longAgo = java.time.Instant.now().minusSeconds(3 * 24 * 60 * 60).toString()
        val quiet = Thread(id = "quiet", channelId = "c1", name = "Release planning",
            autoArchiveDuration = 1440, lastActivityAt = longAgo)
        state.rememberThreads("c1", listOf(quiet))
        assertTrue("the fixture's thread is not quiet enough to be out of the column",
            state.threadsByChannel.value["c1"].orEmpty().isEmpty())

        val answered = state.noteThreadReply(quiet)

        assertEquals("the reply did not put the thread back under its channel",
            listOf("quiet"), state.threadsByChannel.value.getValue("c1").map { it.id })
        assertTrue("the returned thread still carries its old clock", answered.isActive())
        assertEquals("the room was handed a thread with a different id", "quiet", answered.id)
    }

    // ---- caches ----------------------------------------------------------------------------

    @Test fun aChannelTreeIsKeptSoNavigationDoesNotRefetchIt() {
        val state = state()
        val tree = lol.mony.harmony.data.model.ServerTree(
            server = lol.mony.harmony.data.model.Server(id = "s1", name = "Server"),
            categories = emptyList(),
            channels = listOf(Channel(id = "c1", serverId = "s1", name = "general")),
        )
        state.serverTrees.value = state.serverTrees.value + ("s1" to tree)
        assertEquals("general", state.serverTrees.value.getValue("s1").channels.single().name)
    }

    @Test fun signingOutDropsEveryCachedList() {
        val state = state()
        state.rememberThreads("c1", listOf(thread("t1", "c1")))
        state.channelsByServer.value = mapOf("s1" to listOf(Channel(id = "c1", serverId = "s1", name = "general")))
        state.conversations.value = emptyList()

        state.forgetCachedLists()

        assertTrue(state.threadsByChannel.value.isEmpty())
        assertTrue(state.channelsByServer.value.isEmpty())
        assertTrue(state.serverTrees.value.isEmpty())
        assertNull(state.conversations.value)
    }
}
