package lol.mony.harmony.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Run blocking OkHttp off the main thread, preserving coroutine cancellation. */
internal suspend fun <T> networkResult(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error.asSentence())
    }
}

internal data class HttpAnswer(val status: Int, val text: String, val contentRange: String?) {
    val successful: Boolean get() = status in 200..299
}

internal fun OkHttpClient.answer(request: Request): HttpAnswer = newCall(request).execute().use {
    HttpAnswer(it.code, it.body.string(), it.header("Content-Range"))
}

/**
 * A failure as the sentence a person reads, rather than as the message of an exception.
 *
 * OkHttp and the JVM report a network that is not there in the words of a stack trace --
 * `Unable to resolve host "chat.example.test": No address associated with hostname`,
 * `Software caused connection abort`, `failed to connect to /10.0.2.2 (port 54480) from
 * /10.0.2.15 (port 51044) after 20000ms`. Every one of those has been drawn in a banner as
 * though it were the app's own copy, and every one reads as a crash rather than as "you are
 * offline".
 *
 * Mapping them here, where every request already funnels through, is what makes the app stop
 * saying "disconnected" in a machine's words: the callers keep using `error.message`, and what
 * they get is a sentence. Anything that is not a transport failure -- the instance's own refusal
 * ([ApiException]), a decode failure, a check this app itself raised -- keeps its own message,
 * because those are the ones that say something a person can act on.
 */
internal fun Throwable.asSentence(): Throwable = when (this) {
    // The instance answered and said why: its own wording is the most specific thing there is.
    is ApiException -> this
    is UnknownHostException -> Offline("This instance's address could not be looked up. " +
        "Check your connection and that the instance is still online.")
    // `SocketTimeoutException` is an `InterruptedIOException`, and both are an `IOException`,
    // so the specific cases have to come before the general one.
    is SocketTimeoutException -> Offline("The instance did not answer in time. Check your connection.")
    is SSLException -> Offline("A secure connection to the instance could not be made.")
    is ConnectException, is NoRouteToHostException, is PortUnreachableException ->
        Offline("The instance could not be reached. Check your connection.")
    is SocketException, is InterruptedIOException ->
        Offline("The connection to the instance was lost.")
    // Every remaining IOException from OkHttp is the transport: a socket closed under a call, a
    // proxy that hung up, a stream cut short. None of them is a sentence.
    is IOException -> Offline("The connection to the instance was lost.")
    else -> this
}

/**
 * The network was not there, said the way a person reads it.
 *
 * It is an [Exception] rather than a new hierarchy because every caller already catches
 * `Exception` and shows `message`; what changes is only which sentence they find there.
 */
class Offline(message: String) : Exception(message)
