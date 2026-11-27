package lol.mony.harmony

import kotlinx.coroutines.runBlocking
import lol.mony.harmony.data.api.ApiException
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.Offline
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.api.asSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * A transport failure as the sentence a person reads.
 *
 * The reported bug is "disconnected toasts": notices in the app's own chrome that said the
 * connection had gone, repeatedly, for things that were not an outage. Half of that was where
 * they were drawn (see `ReconnectBannerRenderTest` and the room's own banner rules); the other
 * half is what they *said*. Every screen in this app shows `error.message`, so whatever a
 * failed request put there was drawn as the app's copy -- and what OkHttp puts there is a stack
 * trace's first line:
 *
 * ```
 * Unable to resolve host "chat.example.test": No address associated with hostname
 * Software caused connection abort
 * failed to connect to /10.0.2.2 (port 54480) from /10.0.2.15 (port 51044) after 20000ms
 * ```
 *
 * None of those is a sentence, and all of them read as a crash. These tests pin that each of
 * them becomes one, and -- just as importantly -- that the failures which *do* carry something a
 * person can act on are left exactly as the instance worded them.
 */
class TransportSentenceTest {
    private fun sentence(error: Throwable): String =
        (error.asSentence() as Exception).message.orEmpty()

    /** The one that was reported: a host that does not resolve. */
    @Test fun aHostThatDoesNotResolveIsASentenceAboutTheConnection() {
        val said = sentence(UnknownHostException("chat.example.test"))
        assertTrue("said: $said", said.contains("could not be looked up"))
        assertTrue("said: $said", said.contains("connection"))
        assertTrue("the raw exception leaked: $said",
            !said.contains("chat.example.test") && !said.contains("No address associated"))
    }

    /** A network that is there but reaches nothing, which is what a phone out of range gets. */
    @Test fun aConnectThatCannotBeMadeIsASentenceAboutTheConnection() {
        for (error in listOf<Throwable>(
            ConnectException("failed to connect to /10.0.2.2 (port 54480) from /10.0.2.15 (port 51044) after 20000ms"),
            NoRouteToHostException("No route to host"),
            PortUnreachableException("Destination unreachable"),
        )) {
            val said = sentence(error)
            assertTrue("${error.javaClass.simpleName} said: $said", said.contains("could not be reached"))
            assertTrue("the raw exception leaked: $said", !said.contains("10.0.2.2") && !said.contains("route"))
        }
    }

    /** A socket closed under a call -- the shape of a handover or a radio going away. */
    @Test fun aSocketLostUnderACallIsASentenceAboutTheConnection() {
        for (error in listOf<Throwable>(
            SocketException("Software caused connection abort"),
            InterruptedIOException("timeout"),
            IOException("unexpected end of stream"),
            IOException("Socket closed"),
        )) {
            val said = sentence(error)
            assertTrue("${error.javaClass.simpleName} said: $said",
                said.contains("connection") && said.contains("lost"))
            assertTrue("the raw exception leaked: $said",
                !said.contains("Software caused") && !said.contains("Socket closed"))
        }
    }

    /** A read that timed out is the instance not answering, not a lost connection. */
    @Test fun aTimeoutSaysTheInstanceDidNotAnswer() {
        val said = sentence(SocketTimeoutException("timeout"))
        assertTrue("said: $said", said.contains("did not answer"))
    }

    /**
     * `SocketTimeoutException` is an `InterruptedIOException` and an `IOException`, so a mapping
     * that tested the general case first would swallow it. This pins the order.
     */
    @Test fun theSpecificCasesWinOverTheGeneralOnes() {
        assertTrue(sentence(SocketTimeoutException("timeout")).contains("did not answer"))
        assertTrue(sentence(SocketTimeoutException("timeout")) !=
            sentence(SocketException("Software caused connection abort")))
    }

    /** A TLS failure is its own sentence, because "check your connection" would be wrong. */
    @Test fun aSecureConnectionThatCannotBeMadeSaysSo() {
        val said = sentence(SSLHandshakeException("java.security.cert.CertPathValidatorException"))
        assertTrue("said: $said", said.contains("secure connection"))
        assertTrue("the raw exception leaked: $said", !said.contains("CertPath"))
    }

    /**
     * The instance's own refusal is the most specific thing there is, and is left alone.
     *
     * "Permission denied (row-level security policy)" is what a person needs to read; replacing
     * it with "the connection was lost" would be a lie and would hide the actual problem.
     */
    @Test fun theInstancesOwnRefusalKeepsItsOwnWording() {
        val refusal = ApiException("Permission denied", 403)
        assertSame("an API refusal was rewritten", refusal, refusal.asSentence())
        assertEquals("Permission denied", sentence(refusal))
    }

    /**
     * And so is anything this app raised itself, because those carry an instruction.
     *
     * `error("Your session ended. Sign in again.")` is a check in this codebase, not a transport
     * failure, and a generic "connection lost" would lose the one thing it says to do.
     */
    @Test fun aSentenceThisAppWroteIsNotRewritten() {
        val ours = IllegalStateException("Your session ended. Sign in again.")
        assertSame("an app-authored sentence was rewritten", ours, ours.asSentence())
        assertEquals("Your session ended. Sign in again.", sentence(ours))
    }

    /** The mapped failure is recognisable, so a screen can treat it differently from a refusal. */
    @Test fun aTransportFailureIsMarkedAsOffline() {
        assertTrue(UnknownHostException("x").asSentence() is Offline)
        assertTrue(IOException("x").asSentence() is Offline)
        assertTrue(ApiException("Permission denied", 403).asSentence() !is Offline)
    }

    /**
     * The mapping is in the transport, not only in a helper a caller has to remember.
     *
     * Every screen shows `error.message` from a `Result`, so what matters is what a real request
     * puts there. This drives `RestApi` against a server that is not there, which is the reported
     * situation: a phone whose instance cannot be reached.
     */
    @Test fun aRealRequestAgainstAnUnreachableInstanceCarriesASentence() = runBlocking {
        // A port nothing listens on, closed as the test starts so the connection is refused.
        val dead = java.net.ServerSocket(0).use { it.localPort }
        val endpoint = Endpoint("http://127.0.0.1:" + dead, "anon")
        val failure = RestApi(OkHttpClient()).select(
            endpoint, "jwt", "messages",
            serializer = kotlinx.serialization.builtins.ListSerializer(
                lol.mony.harmony.data.model.Message.serializer()),
        ).exceptionOrNull()

        assertTrue("the request did not fail", failure != null)
        assertTrue("a transport failure was not mapped: $failure", failure is Offline)
        val said = failure!!.message.orEmpty()
        assertTrue("the raw exception leaked: $said",
            !said.contains("Failed to connect") && !said.contains("127.0.0.1") &&
                !said.contains("java.net"))
        assertTrue("said: $said", said.contains("connection"))
    }
}
