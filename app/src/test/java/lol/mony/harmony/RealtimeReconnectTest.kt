package lol.mony.harmony

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.LiveUpdate
import lol.mony.harmony.data.api.Realtime
import lol.mony.harmony.data.api.ReconnectPolicy
import lol.mony.harmony.data.model.HarmonyJson
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The live connection puts itself back.
 *
 * This is the regression for the reported behaviour: a room's socket dropped, the app said so,
 * and nothing happened until the person pressed a button. The tests below drive the real
 * [Realtime] against a real WebSocket server, so what they prove is the socket behaviour rather
 * than a mock of it:
 *
 * - a socket the server closes is replaced by a new one, with no outside help;
 * - the new join carries a *fresh* access token, so a reconnect after a refresh is not refused;
 * - a join the instance refuses is not retried on a timer (that would be a loop against a
 *   refusal), while a clean close is;
 * - a nudge skips the rest of a backoff, which is what the Retry button and a regained network
 *   are for;
 * - a session that ends under the loop ends the loop, rather than reconnecting for ever.
 */
class RealtimeReconnectTest {
    /** Effectively no waiting: the schedule itself is asserted in `ReconnectPolicyTest`. */
    private val noWait = ReconnectPolicy(
        initialDelayMillis = 1, maxDelayMillis = 1, multiplier = 1.0, jitter = 0.0)

    private fun endpoint(server: MockWebServer) =
        Endpoint(server.url("/").toString().trimEnd('/'), "anon")

    /**
     * One socket the test server accepted, seen from the instance's side.
     *
     * It is the `WebSocketListener` handed to `withWebSocketUpgrade`, so it really is the peer
     * the client is talking to: it records every frame the client sent and can answer a join the
     * way Harmony's realtime endpoint does.
     */
    private class Peer(private val upgrades: AtomicInteger) : WebSocketListener() {
        private lateinit var socket: WebSocket
        private val received = CopyOnWriteArrayList<JsonObject>()
        private val joined = CountDownLatch(1)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            upgrades.incrementAndGet()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { HarmonyJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            received += frame
            if (frame["event"]?.jsonPrimitive?.content == "phx_join") joined.countDown()
        }

        /** Waits for the client's join frame. */
        fun awaitJoin(): Boolean = joined.await(20, TimeUnit.SECONDS)

        private fun frame(event: String): JsonObject? =
            received.lastOrNull { it["event"]?.jsonPrimitive?.content == event }

        fun topic(): String? = frame("phx_join")?.get("topic")?.jsonPrimitive?.content

        fun joinToken(): String? = frame("phx_join")
            ?.get("payload")?.jsonObject?.get("access_token")?.jsonPrimitive?.content

        /** Answers the client's join, as the instance's own realtime endpoint does. */
        fun accept() = reply("ok")

        fun refuse() = reply("error")

        /**
         * Drops the TCP connection, the shape of a mobile network going away.
         *
         * A socket that has already failed cannot be cancelled again, and a test that called
         * `cancel` on one would fail on the client's own teardown rather than on what it is
         * asserting, so the failure is swallowed: the drop has happened either way.
         */
        fun abort() { runCatching { socket.cancel() } }

        /** Closes the WebSocket politely, the shape of an instance restarting. */
        fun close() = socket.close(1001, "Going away")

        /** The same, for a socket the client may already have closed. */
        fun closeQuietly() {
            if (this::socket.isInitialized) runCatching { socket.close(1000, null) }
        }

        /** Whether the client has sent a heartbeat yet, which proves the keepalive runs. */
        fun heartbeats(): Int =
            received.count { it["event"]?.jsonPrimitive?.content == "heartbeat" }

        private fun reply(status: String) {
            val join = frame("phx_join") ?: return
            socket.send(buildString {
                append("""{"topic":""").append(quote(join["topic"]?.jsonPrimitive?.content)).append(""",""")
                append(""""event":"phx_reply",""")
                append(""""payload":{"status":""").append(quote(status)).append("""},""")
                append(""""ref":""").append(quote(join["ref"]?.jsonPrimitive?.content)).append("}")
            })
        }

        private fun quote(value: String?): String =
            "\"" + (value ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    /** The sockets the server accepted, in order, so a test can drive each attempt. */
    private class Peers {
        private val accepted = CopyOnWriteArrayList<Peer>()
        val upgrades = AtomicInteger()

        /** Queues one upgrade; the next socket the client opens becomes the next peer. */
        fun enqueue(server: MockWebServer, count: Int = 1) = repeat(count) {
            val peer = Peer(upgrades)
            accepted += peer
            server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        }

        /** Waits for peer [index] to send its join frame. */
        fun awaitJoin(index: Int): Peer {
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                val peer = accepted.getOrNull(index)
                if (peer != null && peer.awaitJoin()) return peer
                Thread.sleep(20)
            }
            throw AssertionError("${opened()} socket(s) opened, ${accepted.size} queued; " +
                "peer #${index + 1} never joined")
        }

        /** How many sockets the client actually opened, which is not how many were queued. */
        fun opened(): Int = upgrades.get()

        /**
         * Closes every socket the server still holds.
         *
         * MockWebServer refuses to shut down while one is open, and the client's own close is
         * asynchronous, so the server's side is closed here rather than hoping.
         */
        fun closeAll() {
            for (peer in accepted) peer.closeQuietly()
        }
    }

    /** Collects a room's updates on a thread of its own, so the test may block while it waits. */
    private fun kotlinx.coroutines.CoroutineScope.collectInto(
        updates: MutableList<LiveUpdate>,
        policy: ReconnectPolicy = noWait,
        token: suspend () -> String?,
        server: MockWebServer,
        roomId: String,
        direct: Boolean = false,
        retryNow: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow(),
    ) = launch(Dispatchers.IO) {
        Realtime(OkHttpClient(), policy)
            .room(endpoint(server), token, roomId, direct, retryNow)
            .collect { updates += it }
    }

    /** Waits until [condition] holds, failing with what the updates were when it never does. */
    private fun await(
        what: String,
        updates: List<LiveUpdate>,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("$what never happened; the updates were $updates")
    }

    /**
     * The reported bug: the server closes the socket and the room must come back by itself.
     *
     * Nothing in this test presses anything or re-subscribes. The only actor after the close is
     * the loop inside [Realtime].
     */
    @Test fun aClosedSocketIsReplacedWithoutAnybodyAsking() = runBlocking {
        MockWebServer().use { server ->
            val peers = Peers().apply { enqueue(server, 3) }
            val updates = CopyOnWriteArrayList<LiveUpdate>()
            val collector = collectInto(updates, token = { "token-1" }, server = server, roomId = "room-1")
            try {
                val first = peers.awaitJoin(0)
                first.accept()
                await("the first join was not reported", updates) {
                    updates.any { it == LiveUpdate.Connected }
                }

                // The instance drops the connection -- a restart, a proxy, a handover.
                first.close()

                // Nobody asks for anything; the app reconnects on its own.
                val second = peers.awaitJoin(1)
                assertEquals("the reconnect did not re-join the same topic",
                    "realtime:channel-messages-room-1", second.topic())
                second.accept()
                await("the reconnect was never reported as connected", updates) {
                    updates.count { it == LiveUpdate.Connected } >= 2
                }
            } finally {
                collector.cancel()
                peers.closeAll()
                collector.join()
            }
        }
    }

    /**
     * A reconnect must not reuse the token the room was opened with.
     *
     * A session's access token expires, and the app refreshes it while the room is open. A
     * reconnect that replayed the original token would be refused by the instance -- which is
     * exactly the case where the person would otherwise have to close and reopen the room.
     */
    @Test fun aReconnectJoinsWithTheTokenThatIsValidNow() = runBlocking {
        MockWebServer().use { server ->
            val peers = Peers().apply { enqueue(server, 2) }
            val updates = CopyOnWriteArrayList<LiveUpdate>()
            val tokens = AtomicInteger()
            val collector = collectInto(updates, token = { "token-" + tokens.incrementAndGet() },
                server = server, roomId = "9", direct = true)
            try {
                val first = peers.awaitJoin(0)
                first.accept()
                first.close()
                val second = peers.awaitJoin(1)
                assertEquals("token-1", first.joinToken())
                assertEquals("the reconnect replayed the first attempt's token",
                    "token-2", second.joinToken())
                assertEquals("realtime:dm-conversation-9", second.topic())
                assertNotEquals(first.joinToken(), second.joinToken())
            } finally {
                collector.cancel()
                peers.closeAll()
                collector.join()
            }
        }
    }

    /**
     * A refused join ends the attempt to connect; it is not a reason to loop.
     *
     * Retrying a refusal on a timer is the shape of a bug that looks like diligence: a room the
     * account may not read would re-join every few seconds for as long as it was open. The app
     * says so instead, and the banner's own action is a refresh, not a retry.
     */
    @Test fun aRefusedJoinIsReportedRatherThanRetried() = runBlocking {
        MockWebServer().use { server ->
            val peers = Peers().apply { enqueue(server, 1) }
            val updates = CopyOnWriteArrayList<LiveUpdate>()
            val collector = collectInto(updates, token = { "token" }, server = server, roomId = "room-2")
            try {
                peers.awaitJoin(0).refuse()
                await("the refusal was not reported", updates) { updates.any { it is LiveUpdate.Unavailable } }
                // Give a retry every chance to happen, then assert that none did.
                Thread.sleep(750)
                assertEquals("a refused join was retried", 1, peers.opened())
                assertTrue("the refusal did not say what happened",
                    (updates.first { it is LiveUpdate.Unavailable } as LiveUpdate.Unavailable)
                        .reason.contains("would not open live updates"))
            } finally {
                collector.cancel()
                peers.closeAll()
                collector.join()
            }
        }
    }

    /**
     * A nudge skips the wait.
     *
     * This is what the banner's "Retry now" and the connectivity callback both do, and it has to
     * work on a wait that would otherwise last the full backoff.
     */
    @Test fun aNudgeSkipsTheRestOfTheBackoff() = runBlocking {
        MockWebServer().use { server ->
            val peers = Peers().apply { enqueue(server, 2) }
            val updates = CopyOnWriteArrayList<LiveUpdate>()
            // Half a minute between attempts, so only a nudge can produce a second socket in the
            // time this test allows.
            val slow = ReconnectPolicy(initialDelayMillis = 30_000, maxDelayMillis = 30_000, jitter = 0.0)
            val nudges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val collector = collectInto(updates, slow, { "token" }, server, "room-3", retryNow = nudges)
            try {
                val first = peers.awaitJoin(0)
                first.accept()
                first.close()
                await("the drop was not reported", updates) { updates.any { it is LiveUpdate.Reconnecting } }
                Thread.sleep(200)
                assertEquals("a second socket opened before any nudge", 1, peers.opened())
                nudges.emit(Unit)
                assertEquals("realtime:channel-messages-room-3", peers.awaitJoin(1).topic())
            } finally {
                collector.cancel()
                peers.closeAll()
                collector.join()
            }
        }
    }

    /**
     * A session that ends while the loop is waiting stops the loop.
     *
     * A room left open on a signed-out account must not keep reconnecting with a token that no
     * longer exists: the flow ends, and says why.
     */
    @Test fun aSignedOutSessionEndsTheLoopRatherThanRetryingForever() = runBlocking {
        MockWebServer().use { server ->
            val peers = Peers().apply { enqueue(server, 3) }
            val updates = CopyOnWriteArrayList<LiveUpdate>()
            val attempts = AtomicInteger()
            val collector = collectInto(updates, token = {
                // The first attempt has a session; by the second it has ended.
                if (attempts.incrementAndGet() == 1) "token" else null
            }, server = server, roomId = "room-4")
            try {
                val first = peers.awaitJoin(0)
                first.accept()
                first.close()
                await("the ended session was not reported", updates) {
                    updates.any { it is LiveUpdate.Unavailable }
                }
                assertEquals("the loop opened a socket with no session", 1, peers.opened())
                assertTrue((updates.first { it is LiveUpdate.Unavailable } as LiveUpdate.Unavailable)
                    .reason.contains("session ended"))
            } finally {
                collector.cancel()
                peers.closeAll()
                collector.join()
            }
        }
    }

    /**
     * A connection that cannot even be opened is retried, not given up on.
     *
     * This is the shape of a network that is gone rather than an instance that answered: the
     * upgrade fails at the transport. The loop must treat it like any other drop and try again,
     * which is what makes a room survive a phone leaving Wi-Fi.
     */
    @Test fun aFailedUpgradeIsRetriedRatherThanEndingTheRoom() = runBlocking {
        MockWebServer().use { server ->
            // The first attempt gets no upgrade at all: a gateway error, as an instance behind a
            // proxy that has lost its upstream answers.
            server.enqueue(MockResponse().setResponseCode(502))
            val peers = Peers().apply { enqueue(server, 1) }
            val updates = CopyOnWriteArrayList<LiveUpdate>()
            val collector = collectInto(updates, token = { "token" }, server = server, roomId = "room-5")
            try {
                await("the failed upgrade was never reported", updates) {
                    updates.any { it is LiveUpdate.Reconnecting }
                }
                val second = peers.awaitJoin(0)
                assertEquals("realtime:channel-messages-room-5", second.topic())
            } finally {
                collector.cancel()
                peers.closeAll()
                collector.join()
            }
        }
    }
}
