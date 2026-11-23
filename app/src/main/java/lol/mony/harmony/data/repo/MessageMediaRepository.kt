package lol.mony.harmony.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import lol.mony.harmony.data.api.ApiException
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.ContentPart

/**
 * Chat attachments: the bytes, the object, and the message part that names it.
 *
 * One upload goes through the same four steps the reference client's
 * `startMessageMediaUpload` does, in this order:
 *
 *  1. **Refuse what cannot be stored.** SVG is refused on the device (it can carry script) and
 *     so is a file over the account's own upload limit, before a byte is sent. Storage enforces
 *     both again; failing here means the person is told in a sentence instead of after a minute
 *     of uploading.
 *  2. **Strip the metadata**, unless they asked to keep it, so a photo does not publish the
 *     coordinates of their home. [MediaMetadata] says what it found, and the composer says what
 *     it did.
 *  3. **Upload** to `<room>/<uploader>/<uuid>.<ext>` in `message_media`, reporting progress and
 *     stopping when the coroutine is cancelled. The bucket's policies authorise the write with
 *     the account's own token; nothing here can widen them.
 *  4. **Sign a URL** for the new part, which older clients render while this one reads `path`.
 *
 * Nothing is left behind on failure: an upload that fails, or an attachment removed after it
 * landed, is deleted from storage so the instance does not accumulate objects no message names.
 */
class MessageMediaRepository(
    private val rest: RestApi,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** A file chosen in the composer, before it is uploaded. */
    data class Picked(
        val name: String,
        val mimeType: String?,
        val size: Long,
        val bytes: ByteArray,
    )

    /** What the upload did to the file, so the composer can say so truthfully. */
    data class Prepared(val bytes: ByteArray, val details: MediaMetadata.Details, val stripped: Boolean)

    /** One stored attachment: the object, the URL a message carries, and what it is. */
    data class Uploaded(
        val path: String,
        val url: String?,
        val fileName: String?,
        val mimeType: String?,
        val size: Long,
        val altText: String? = null,
        val reused: Boolean = false,
    ) {
        /** The `file` part a message carries for this object. */
        fun part(): ContentPart = MessageMedia.filePart(
            path = path, url = url, fileName = fileName, mimeType = mimeType, size = size, altText = altText,
        )
    }

    /**
     * The account's own upload limit in bytes, or null when the instance does not say.
     *
     * A server from before the limit existed has no such function; that is not an error, and the
     * upload is then governed by the bucket's own limit, which storage enforces.
     */
    suspend fun uploadLimit(endpoint: Endpoint, token: String): Long? = io {
        signing {
            rest.rpcValue(endpoint, token, "get_my_upload_limit", serializer = JsonElement.serializer())
                .getOrThrow()
        }?.let { element -> (element as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }?.takeIf { it > 0 }
    }

    /**
     * Refuses a file before it is uploaded: an SVG, or one over the account's limit.
     *
     * The size is checked against the bytes as they will be sent, which for a stripped photo is
     * the smaller number, so a photo that only fits once its metadata is gone is not refused.
     */
    suspend fun refuseReason(endpoint: Endpoint, token: String, picked: Picked): String? {
        if (MessageMedia.isSvg(picked.name, picked.mimeType)) return MessageMedia.SVG_REFUSAL
        if (picked.bytes.isEmpty()) return "That file is empty."
        val limit = uploadLimit(endpoint, token) ?: return null
        return if (picked.bytes.size > limit) MessageMedia.overLimit(picked.name, picked.bytes.size.toLong(), limit) else null
    }

    /** Strips the metadata off [picked], reporting what was found and whether anything changed. */
    suspend fun prepare(picked: Picked, keepMetadata: Boolean): Prepared = withContext(dispatcher) {
        val plan = MediaMetadata.strip(picked.bytes, keep = keepMetadata)
        Prepared(plan.bytes, plan.details, stripped = plan.changed && !keepMetadata)
    }

    /**
     * Uploads [picked] into [room] and answers the part that names it.
     *
     * [onProgress] is called with a fraction from 0 to 1. The call is cancellable: cancelling it
     * stops the request, and the object is deleted if it had already landed, so a cancelled
     * attachment leaves nothing behind.
     */
    suspend fun upload(
        endpoint: Endpoint,
        token: String,
        uploaderAuthId: String,
        room: String,
        picked: Picked,
        altText: String? = null,
        keepMetadata: Boolean = false,
        onProgress: ((Float) -> Unit)? = null,
    ): Uploaded {
        val prepared = prepare(picked, keepMetadata)
        val contentType = MessageMedia.contentTypeOf(picked.mimeType, picked.name)
        val path = MessageMedia.path(room, uploaderAuthId, picked.name, contentType)
        var landed = false
        try {
            onProgress?.invoke(0f)
            rest.uploadObject(
                endpoint = endpoint, token = token, bucket = MessageMedia.BUCKET, path = path,
                bytes = prepared.bytes, contentType = contentType, cacheControl = MessageMedia.CACHE_CONTROL,
                onProgress = { written, total ->
                    if (total > 0) onProgress?.invoke((written.toDouble() / total).toFloat().coerceIn(0f, 1f))
                },
            )
            landed = true
            val url = signOne(endpoint, token, path, MessageMedia.COMPAT_URL_TTL_SECONDS)
            onProgress?.invoke(1f)
            return Uploaded(
                path = path, url = url, fileName = picked.name,
                mimeType = MessageMedia.guessMimeType(picked.name) ?: picked.mimeType ?: contentType,
                size = prepared.bytes.size.toLong(), altText = altText,
            )
        } catch (cancelled: CancellationException) {
            // The coroutine is already cancelled, so an ordinary suspend call here would throw
            // before it reached the network. The cleanup has to outlive the cancellation.
            if (landed) withContext(NonCancellable) { discard(endpoint, token, listOf(path)) }
            throw cancelled
        } catch (error: Exception) {
            // A refusal storage answered after the bytes were sent can leave a partial object.
            if (landed) discard(endpoint, token, listOf(path))
            throw explainUploadRefusal(error, picked)
        }
    }

    /** Signs one object for viewing, or null when this account may not read it. */
    suspend fun signOne(endpoint: Endpoint, token: String, path: String, expiresIn: Int): String? =
        signing { rest.signObjects(endpoint, token, MessageMedia.BUCKET, listOf(path), expiresIn)[path] }

    /**
     * Signs a batch of objects for viewing.
     *
     * A path the account may not read is absent from the answer rather than an error, so one
     * gone or unreadable attachment cannot stop the rest of a channel from drawing.
     */
    suspend fun sign(endpoint: Endpoint, token: String, paths: Collection<String>,
                     expiresIn: Int = MessageMedia.VIEW_URL_TTL_SECONDS): Map<String, String> = io {
        val wanted = paths.filter(MessageMedia::isPath).distinct()
        if (wanted.isEmpty()) return@io emptyMap()
        wanted.chunked(SIGN_BATCH).flatMap { chunk ->
            signing { rest.signObjects(endpoint, token, MessageMedia.BUCKET, chunk, expiresIn) }
                .orEmpty().toList()
        }.toMap()
    }

    /** Deletes objects no message names. A refusal is not worth failing a send over. */
    suspend fun discard(endpoint: Endpoint, token: String, paths: Collection<String>) {
        val wanted = paths.filter(MessageMedia::isPath).distinct()
        if (wanted.isEmpty()) return
        signing { withContext(dispatcher) { rest.deleteObjects(endpoint, token, MessageMedia.BUCKET, wanted) } }
    }

    /**
     * Runs a best-effort call, letting cancellation through.
     *
     * `runCatching` catches `CancellationException` too, so a cancelled upload inside it reads
     * as a *successful* one -- the attachment was marked ready and the object it stored was
     * never deleted. Every best-effort call in this class goes through here instead.
     */
    private suspend fun <T> signing(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        null
    }

    /**
     * Turns storage's own refusal into a sentence a person can act on.
     *
     * Storage answers a quota or blocked-hash refusal as a generic row-level-security error,
     * which reads like a bug. The two cases that have a real explanation are named.
     */
    private suspend fun explainUploadRefusal(error: Exception, picked: Picked): Exception {
        val status = (error as? ApiException)?.status ?: 0
        if (status !in 400..499) return error
        val text = error.message.orEmpty()
        if (text.contains("upload_limit", ignoreCase = true) || text.contains("exceeded", ignoreCase = true)) {
            return IllegalArgumentException("That file is larger than this account may upload.")
        }
        if (text.contains("drive", ignoreCase = true) || text.contains("quota", ignoreCase = true) ||
            text.contains("full", ignoreCase = true)
        ) {
            return IllegalArgumentException("This account's file storage is full. Free space in Drive and try again.")
        }
        return IllegalArgumentException(
            "The instance refused this file" + (picked.name.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                ". It may be a blocked file or a full account; nothing was attached.",
        )
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(dispatcher) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    companion object {
        /** Objects per signing request; the reference's `createSignedUrls` batch size. */
        const val SIGN_BATCH = 100
    }
}

/** True when a stored part names a private object this client can sign. */
fun ContentPart.hasPrivateMedia(): Boolean = type == "file" && MessageMedia.isPath(path)

/** The object names a page of messages names, so one signing call covers the whole page. */
fun privateMediaPaths(messages: Collection<lol.mony.harmony.data.model.Message>): List<String> =
    messages.flatMap { message -> message.content.filter(ContentPart::hasPrivateMedia).mapNotNull { it.path } }
        .distinct()
