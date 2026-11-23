package lol.mony.harmony.data.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import lol.mony.harmony.data.model.HarmonyJson
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the live connection is doing, as a room sees it.
 *
 * [Reconnecting] is a state of its own rather than an [Unavailable] with a retry button: the app
 * puts the connection back by itself, and the banner is there to say so, not to ask the person
 * to do it.
 */
sealed interface LiveUpdate {
    /** The room's topic is joined and the instance's own broadcasts are arriving. */
    data object Connected : LiveUpdate
    /** Something the room shows may have changed; the room re-reads it. */
    data object Changed : LiveUpdate
    /**
     * The connection dropped and the app is putting it back.
     *
     * [attempt] counts from 1, [reason] is the sentence shown to the person, and [retryInMillis]
     * is how long the app will wait before trying again. A banner does not need the last one,
     * but a test -- and anyone reading a log -- does.
     */
    data class Reconnecting(val attempt: Int, val reason: String, val retryInMillis: Long) : LiveUpdate
    /**
     * The instance will not accept this connection again until something changes: the join was
     * refused, or the session ended. Retrying on a timer would be a loop against a refusal, so
     * this ends the flow instead.
     */
    data class Unavailable(val reason: String) : LiveUpdate
}

/** Why one attempt at a live connection ended. */
internal sealed interface AttemptEnd {
    /** The socket closed, failed or went silent; the app should try again. */
    data class Dropped(val reason: String) : AttemptEnd
    /** The instance refused the join; the same request cannot succeed by being repeated. */
    data class Refused(val reason: String) : AttemptEnd
}

/** One attempt's outcome: whether the room was ever joined, and how it ended. */
internal data class AttemptResult(val joined: Boolean, val end: AttemptEnd)

/**
 * Private Harmony message broadcasts, not public/unfiltered postgres subscriptions.
 *
 * A room's flow is a supervised loop over single-attempt sockets. Each attempt joins the room's
 * topic, reports what it receives, and -- when the socket dies -- says why and waits before
 * trying again. Nothing outside has to notice a drop, and nothing outside has to ask for the
 * connection back: a phone that loses Wi-Fi in a lift and regains it has its room live again
 * without the person touching anything.
 *
 * The three things that used to make a drop permanent are each handled here:
 *
 * - **Nothing retried.** A failed socket completed the flow. Now the loop waits
 *   [ReconnectPolicy.delayFor] and opens a new one, for as long as the room is on screen.
 * - **A stale token.** The join frame carried the access token as it was when the room opened,
 *   so a reconnect after a refresh would be refused. [token] is asked for on every attempt.
 * - **A dead socket nobody noticed.** The heartbeat's `send` return value was ignored, so a
 *   socket the OS had killed was written to for ever. It is checked now, so a connection that
 *   has gone silent is noticed in seconds rather than at the next tap.
 */
class Realtime(
    private val client: OkHttpClient,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
) {
    /**
     * The live updates for one room.
     *
     * [token] is called before every attempt, so a reconnect always carries a valid access
     * token; it returns null when the account is no longer signed in, which ends the flow
     * because there is nothing left to reconnect with.
     *
     * [retryNow] is a nudge from outside the loop -- the device regaining a network, or the
     * person pressing Retry -- that skips the rest of a wait. It is a `Flow` because both of
     * those happen more than once in a session.
     */
    fun room(
        endpoint: Endpoint,
        token: suspend () -> String?,
        roomId: String,
        direct: Boolean,
        retryNow: Flow<Unit> = emptyFlow(),
    ): Flow<LiveUpdate> = callbackFlow {
        val name = if (direct) "dm-conversation-$roomId" else "channel-messages-$roomId"
        val topic = "realtime:$name"
        val url = (endpoint.baseUrl.trimEnd('/') + "/realtime/v1/websocket").toHttpUrl().newBuilder()
            .addQueryParameter("apikey", endpoint.anonKey).addQueryParameter("vsn", "1.0.0").build()
        // A nudge that arrives while a socket is up must not be replayed at the next drop, so
        // the channel is conflated and drained on every successful join.
        val nudges = Channel<Unit>(Channel.CONFLATED)
        val nudgeWatcher = launch { retryNow.collect { nudges.trySend(Unit) } }
        val emit: (LiveUpdate) -> Unit = { trySend(it) }

        /** Waits [millis], or less when something nudges the loop to try now. */
        suspend fun waitBeforeRetry(millis: Long) {
            if (millis <= 0L) return
            withTimeoutOrNull(millis) { nudges.receive() }
        }

        val loop = launch {
            var attempt = 0
            while (true) {
                val accessToken = token()
                if (accessToken == null) {
                    // Signed out under us: the room cannot be re-joined, and pretending to keep
                    // trying would spin against a session that no longer exists.
                    emit(LiveUpdate.Unavailable("Your session ended. Sign in again."))
                    close()
                    return@launch
                }
                val result = runAttempt(url, topic, accessToken, emit)
                if (result.joined) {
                    // The room was live, so the next drop starts the schedule over instead of
                    // inheriting a count from an outage that is already over.
                    attempt = 0
                    // A nudge that arrived while the socket was up is spent: it asked for a
                    // connection that has since been made, and replaying it would open a socket
                    // for nothing.
                    while (nudges.tryReceive().isSuccess) { /* drained */ }
                }
                when (val end = result.end) {
                    is AttemptEnd.Refused -> {
                        emit(LiveUpdate.Unavailable(end.reason))
                        close()
                        return@launch
                    }
                    is AttemptEnd.Dropped -> {
                        attempt++
                        val wait = policy.delayFor(attempt)
                        emit(LiveUpdate.Reconnecting(attempt, end.reason, wait))
                        waitBeforeRetry(wait)
                    }
                }
            }
        }

        awaitClose { nudgeWatcher.cancel(); loop.cancel() }
    }

    /**
     * One socket, from open to close.
     *
     * It never throws: a socket that fails to open, a join the instance refuses, and a heartbeat
     * that cannot be written all come back as an [AttemptResult].
     */
    private suspend fun runAttempt(
        url: HttpUrl,
        topic: String,
        accessToken: String,
        emit: (LiveUpdate) -> Unit,
    ): AttemptResult = coroutineScope {
        val ended = CompletableDeferred<AttemptEnd>()
        // Completes when the topic is joined, which is what the watchdog below is waiting for.
        // A connection that has joined is healthy however long it stays up; only an attempt
        // that never joins is the watchdog's business.
        val joined = CompletableDeferred<Unit>()
        val refs = AtomicInteger()
        fun frame(topic: String, event: String, payload: JsonObject): String = buildJsonObject {
            put("topic", topic); put("event", event); put("payload", payload)
            put("ref", refs.incrementAndGet().toString())
        }.toString()
        fun finish(end: AttemptEnd) { ended.complete(end) }

        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(frame(topic, "phx_join", buildJsonObject {
                        put("access_token", accessToken)
                        put("config", buildJsonObject {
                            put("private", true)
                            put("broadcast", buildJsonObject { put("ack", false); put("self", false) })
                            put("presence", buildJsonObject { put("key", "") })
                            put("postgres_changes", JsonArray(emptyList()))
                        })
                    }))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { HarmonyJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    val event = message["event"]?.jsonPrimitive?.content
                    val payload = message["payload"] as? JsonObject ?: return
                    if (event == "phx_reply" && message["topic"]?.jsonPrimitive?.content == topic) {
                        if (payload["status"]?.jsonPrimitive?.content == "ok") {
                            joined.complete(Unit)
                            emit(LiveUpdate.Connected)
                        } else {
                            finish(AttemptEnd.Refused(
                                "The instance would not open live updates for this room. Pull to refresh."))
                        }
                    }
                    if (event == "broadcast" && payload["event"]?.jsonPrimitive?.content in
                        setOf("message_event", "reaction_event", "poll_event")) emit(LiveUpdate.Changed)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    response?.close()
                    finish(AttemptEnd.Dropped(reasonFor(t)))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // A close is not a refusal to reconnect: the instance restarting, or a
                    // network going away, both look like this and both deserve a retry.
                    finish(AttemptEnd.Dropped("The live connection closed."))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finish(AttemptEnd.Dropped("The live connection closed."))
                }
            },
        )

        // The heartbeat is also the liveness check: a socket that cannot take a frame is gone,
        // and waiting for the OS to say so can take minutes. The socket is cancelled as well as
        // reported, because a socket that cannot be written to is not one worth keeping -- and
        // one left open holds a radio wakeup for nothing.
        val heartbeat = launch {
            while (true) {
                delay(HEARTBEAT_MILLIS)
                if (!socket.send(frame("phoenix", "heartbeat", JsonObject(emptyMap())))) {
                    finish(AttemptEnd.Dropped("The live connection stopped answering."))
                    socket.cancel()
                    return@launch
                }
            }
        }

        // An attempt that never *joins* is as dead as one that closed: OkHttp's own connect
        // timeout covers the TCP part, and this covers an upgrade that hangs or a join that is
        // never answered. It must not outlive the join, or it would kill a healthy connection
        // twenty seconds in and turn every room into a reconnect loop.
        val watchdog = launch {
            delay(CONNECT_TIMEOUT_MILLIS)
            if (!joined.isCompleted) {
                finish(AttemptEnd.Dropped("The instance did not answer in time."))
                socket.cancel()
            }
        }

        try {
            val end = ended.await()
            AttemptResult(joined.isCompleted, end)
        } finally {
            // Every exit path releases the socket: a cancelled flow must not leave a live
            // WebSocket behind, or a room left on screen and closed again would leak one per
            // visit and keep a radio awake for nothing.
            heartbeat.cancel()
            watchdog.cancel()
            runCatching { socket.close(1000, "Reconnecting") }
        }
    }

    /** A sentence for the banner that says what happened, without a stack trace's noise. */
    private fun reasonFor(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "The instance could not be found."
        is java.net.SocketTimeoutException -> "The instance did not answer in time."
        is javax.net.ssl.SSLException -> "A secure connection could not be made."
        else -> "The live connection dropped."
    }

    private companion object {
        /** Phoenix's own heartbeat interval, in milliseconds. */
        const val HEARTBEAT_MILLIS = 25_000L
        /** How long a socket may take to open and join before it is treated as dead. */
        const val CONNECT_TIMEOUT_MILLIS = 20_000L
    }
}
