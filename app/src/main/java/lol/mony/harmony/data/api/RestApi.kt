package lol.mony.harmony.data.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import lol.mony.harmony.data.model.HarmonyJson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * PostgREST, the instance's table and function API, under `/rest/v1`.
 *
 * Filters are the query parameters PostgREST itself defines (`eq.`, `in.`, `is.null`, `order`,
 * `select`, `limit`, `offset`), passed through as the web client's Supabase calls produce them,
 * so an endpoint behaves the same for both clients.
 */
class RestApi(
    private val client: OkHttpClient,
    private val json: Json = HarmonyJson,
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Read a list of rows. */
    suspend fun <T> select(
        endpoint: Endpoint,
        token: String,
        table: String,
        columns: String = "*",
        filters: List<Pair<String, String>> = emptyList(),
        order: String? = null,
        limit: Int? = null,
        offset: Int? = null,
        serializer: KSerializer<List<T>>,
    ): Result<List<T>> {
        val url = buildUrl(endpoint, table, columns, filters, order, limit, offset)
        val request = authorized(endpoint, token, url).get().build()
        return execute(request) { json.decodeFromString(serializer, it) }
    }

    /** Read at most one row; PostgREST's `Accept: application/vnd.pgrst.object+json`. */
    suspend fun <T> selectSingle(
        endpoint: Endpoint,
        token: String,
        table: String,
        columns: String = "*",
        filters: List<Pair<String, String>> = emptyList(),
        serializer: KSerializer<T>,
    ): Result<T?> {
        val url = buildUrl(endpoint, table, columns, filters, order = null, limit = 1, offset = null)
        val request = authorized(endpoint, token, url)
            .header("Accept", "application/vnd.pgrst.object+json")
            .get()
            .build()
        return executeOptional(request) { json.decodeFromString(serializer, it) }
    }

    /** Insert one or more rows and read what the server stored. */
    suspend fun <T> insert(
        endpoint: Endpoint,
        token: String,
        table: String,
        rows: String,
        returning: String = "*",
        serializer: KSerializer<List<T>>,
    ): Result<List<T>> {
        val url = buildUrl(endpoint, table, returning, emptyList(), order = null, limit = null, offset = null)
        val request = authorized(endpoint, token, url)
            .header("Prefer", "return=representation")
            .post(rows.toRequestBody(jsonMedia))
            .build()
        return execute(request) { json.decodeFromString(serializer, it) }
    }

    /** Patch rows matching `filters` and read what they became. */
    suspend fun <T> update(
        endpoint: Endpoint,
        token: String,
        table: String,
        filters: List<Pair<String, String>>,
        patch: String,
        returning: String = "*",
        serializer: KSerializer<List<T>>,
    ): Result<List<T>> {
        val url = buildUrl(endpoint, table, returning, filters, order = null, limit = null, offset = null)
        val request = authorized(endpoint, token, url)
            .header("Prefer", "return=representation")
            .patch(patch.toRequestBody(jsonMedia))
            .build()
        return execute(request) { json.decodeFromString(serializer, it) }
    }

    /** Delete rows matching `filters`. */
    suspend fun delete(
        endpoint: Endpoint,
        token: String,
        table: String,
        filters: List<Pair<String, String>>,
    ): Result<Unit> {
        val url = buildUrl(endpoint, table, "*", filters, order = null, limit = null, offset = null)
        val request = authorized(endpoint, token, url).delete().build()
        return execute(request) { }
    }

    /** Call a Postgres function. A table-valued function is read as a list of rows. */
    suspend fun <T> rpc(
        endpoint: Endpoint,
        token: String,
        function: String,
        args: String = "{}",
        serializer: KSerializer<List<T>>,
    ): Result<List<T>> {
        val url = (endpoint.restUrl + "/rpc/" + function).toHttpUrl()
        val request = authorized(endpoint, token, url)
            .header("Prefer", "return=representation")
            .post(args.toRequestBody(jsonMedia))
            .build()
        return execute(request) { json.decodeFromString(serializer, it) }
    }

    /** Call a function whose answer this app ignores beyond success or failure. */
    suspend fun rpcUnit(
        endpoint: Endpoint,
        token: String,
        function: String,
        args: String = "{}",
    ): Result<Unit> {
        val url = (endpoint.restUrl + "/rpc/" + function).toHttpUrl()
        val request = authorized(endpoint, token, url)
            .post(args.toRequestBody(jsonMedia))
            .build()
        return execute(request) { }
    }

    /** Count rows matching `filters`, via PostgREST's `Prefer: count=exact` header. */
    suspend fun count(
        endpoint: Endpoint,
        token: String,
        table: String,
        filters: List<Pair<String, String>> = emptyList(),
    ): Result<Int> {
        val url = buildUrl(endpoint, table, "id", filters, order = null, limit = 1, offset = null)
        val request = authorized(endpoint, token, url)
            .header("Prefer", "count=exact")
            .header("Range-Unit", "items")
            .header("Range", "0-0")
            .get()
            .build()
        return networkResult {
            val answer = client.answer(request)
            if (!answer.successful) throw ApiException(explain(answer.text, answer.status), answer.status)
            answer.contentRange?.substringAfter('/')?.toIntOrNull()
                ?: throw ApiException("The instance did not return an exact count.")
        }
    }

    private fun buildUrl(
        endpoint: Endpoint,
        table: String,
        columns: String,
        filters: List<Pair<String, String>>,
        order: String?,
        limit: Int?,
        offset: Int?,
    ): HttpUrl {
        val builder = (endpoint.restUrl + "/" + table).toHttpUrl().newBuilder()
        builder.addQueryParameter("select", columns)
        for ((key, value) in filters) builder.addQueryParameter(key, value)
        if (order != null) builder.addQueryParameter("order", order)
        if (limit != null) builder.addQueryParameter("limit", limit.toString())
        if (offset != null) builder.addQueryParameter("offset", offset.toString())
        return builder.build()
    }

    private fun authorized(endpoint: Endpoint, token: String, url: HttpUrl): Request.Builder =
        Request.Builder()
            .url(url)
            .header("apikey", endpoint.anonKey)
            .header("Authorization", "Bearer $token")

    private suspend fun <T> execute(request: Request, parse: (String) -> T): Result<T> = networkResult {
        val answer = client.answer(request)
        if (!answer.successful) throw ApiException(explain(answer.text, answer.status), answer.status)
        // DELETE and void RPCs legitimately answer 204 with an empty body.
        parse(answer.text)
    }

    private suspend fun <T> executeOptional(request: Request, parse: (String) -> T): Result<T?> = networkResult {
        val answer = client.answer(request)
        if (answer.status == 406 && answer.text.contains("PGRST116") &&
            answer.text.contains("0 rows")) return@networkResult null
        if (!answer.successful) throw ApiException(explain(answer.text, answer.status), answer.status)
        parse(answer.text)
    }

    /** Scalar or object-valued RPC, such as create_thread's UUID response. */
    suspend fun <T> rpcValue(endpoint: Endpoint, token: String, function: String,
                             args: String = "{}", serializer: KSerializer<T>): Result<T> {
        val request = authorized(endpoint, token, (endpoint.restUrl + "/rpc/" + function).toHttpUrl())
            .post(args.toRequestBody(jsonMedia)).build()
        return execute(request) { json.decodeFromString(serializer, it) }
    }

    /**
     * Uploads one object to a storage bucket, as supabase-js's `storage.upload` does.
     *
     * The bucket's own policies authorise the write with the same token, so nothing here can
     * widen what an account may store. `upsert=false` matches the web client: an object is
     * written under a fresh name rather than replacing another account's file.
     *
     * [onProgress] is told how many bytes have been written and how many there are, which is
     * what a composer's progress bar draws. The call is cancellable: cancelling the coroutine
     * cancels the HTTP request, so a removed attachment stops sending instead of running on in
     * the background.
     */
    suspend fun uploadObject(
        endpoint: Endpoint,
        token: String,
        bucket: String,
        path: String,
        bytes: ByteArray,
        contentType: String,
        cacheControl: String = "31536000",
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        val url = (endpoint.baseUrl.trimEnd('/') + "/storage/v1/object/$bucket/" + path.trimStart('/')).toHttpUrl()
        val body = if (onProgress == null) {
            bytes.toRequestBody(contentType.toMediaType())
        } else {
            ProgressRequestBody(bytes, contentType.toMediaType(), onProgress)
        }
        val request = Request.Builder()
            .url(url)
            .header("apikey", endpoint.anonKey)
            .header("Authorization", "Bearer $token")
            .header("x-upsert", "false")
            .header("cache-control", cacheControl)
            .post(body)
            .build()
        val answer = cancellableAnswer(request)
        if (!answer.successful) throw ApiException(explain(answer.text, answer.status), answer.status)
    }

    /**
     * Signs objects of a private bucket, as supabase-js's `createSignedUrls` does.
     *
     * The answer names each object it could sign; a path this account may not read is simply
     * absent, so a caller shows a placeholder for it instead of a broken picture. The signed URL
     * is resolved against the instance's own storage route, so a compromised or misconfigured
     * server cannot point the client at another host.
     */
    suspend fun signObjects(
        endpoint: Endpoint,
        token: String,
        bucket: String,
        paths: Collection<String>,
        expiresIn: Int,
    ): Map<String, String> {
        val unique = paths.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return emptyMap()
        val url = (endpoint.baseUrl.trimEnd('/') + "/storage/v1/object/sign/$bucket").toHttpUrl()
        val body = buildJsonObject {
            put("expiresIn", expiresIn)
            put("paths", kotlinx.serialization.json.JsonArray(unique.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }.toString()
        val request = authorized(endpoint, token, url)
            .post(body.toRequestBody(jsonMedia))
            .build()
        val answer = networkResult { client.answer(request) }.getOrThrow()
        if (!answer.successful) throw ApiException(explain(answer.text, answer.status), answer.status)
        val base = (endpoint.baseUrl.trimEnd('/') + "/storage/v1/").toHttpUrl()
        val rows = json.parseToJsonElement(answer.text) as? kotlinx.serialization.json.JsonArray ?: return emptyMap()
        return buildMap {
            for (row in rows) {
                val entry = row as? JsonObject ?: continue
                val path = (entry["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
                if (entry["error"] != null && entry["error"].toString() != "null") continue
                val signed = (entry["signedURL"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: (entry["signedUrl"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: continue
                val resolved = runCatching {
                    // storage-js resolves `/object/sign/...` against `/storage/v1`, not the origin.
                    base.resolve(if (signed.startsWith("/object/")) signed.drop(1) else signed)
                }.getOrNull() ?: continue
                if (resolved.scheme != base.scheme || resolved.host != base.host || resolved.port != base.port ||
                    resolved.username.isNotEmpty() || resolved.password.isNotEmpty()) continue
                put(path, resolved.toString())
            }
        }
    }

    /** Removes objects from a bucket, as supabase-js's `storage.remove` does. */
    suspend fun deleteObjects(
        endpoint: Endpoint,
        token: String,
        bucket: String,
        paths: Collection<String>,
    ) {
        val unique = paths.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return
        val url = (endpoint.baseUrl.trimEnd('/') + "/storage/v1/object/$bucket").toHttpUrl()
        val body = buildJsonObject {
            put("prefixes", kotlinx.serialization.json.JsonArray(unique.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }.toString()
        val request = authorized(endpoint, token, url).delete(body.toRequestBody(jsonMedia)).build()
        val answer = networkResult { client.answer(request) }.getOrThrow()
        if (!answer.successful) throw ApiException(explain(answer.text, answer.status), answer.status)
    }

    /**
     * Runs a request off the main thread, with the coroutine's cancellation wired to the call.
     *
     * [networkResult] is enough for a short read whose only cost is waiting; an upload can take
     * a minute and has to stop the moment the person removes the attachment, so the call is
     * enqueued and cancelled with its coroutine instead of blocking a dispatcher thread.
     */
    private suspend fun cancellableAnswer(request: Request): HttpAnswer =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: java.io.IOException) {
                    if (!continuation.isActive) return
                    continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    val answer = try {
                        response.use { HttpAnswer(it.code, it.body.string(), it.header("Content-Range")) }
                    } catch (error: java.io.IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                        return
                    }
                    if (continuation.isActive) continuation.resume(answer)
                }
            })
        }

    /** A request body that reports how much of it has been written. */
    private class ProgressRequestBody(
        private val bytes: ByteArray,
        private val contentType: okhttp3.MediaType,
        private val onProgress: (Long, Long) -> Unit,
    ) : okhttp3.RequestBody() {
        override fun contentType(): okhttp3.MediaType = contentType
        override fun contentLength(): Long = bytes.size.toLong()

        override fun writeTo(sink: okio.BufferedSink) {
            var written = 0
            while (written < bytes.size) {
                val count = minOf(CHUNK, bytes.size - written)
                sink.write(bytes, written, count)
                written += count
                onProgress(written.toLong(), bytes.size.toLong())
            }
        }

        private companion object { const val CHUNK = 64 * 1024 }
    }

    /** PostgREST errors carry `message`, `details` and `hint`; show the most specific sentence. */
    private fun explain(text: String, status: Int): String {
        val body = runCatching { json.decodeFromString(JsonObject.serializer(), text) }.getOrNull()
        val message = body?.get("message")?.toString()?.trim('"')
        val details = body?.get("details")?.toString()?.trim('"')
        return when {
            message.isNullOrBlank() -> "The instance answered $status."
            details.isNullOrBlank() || details == "null" -> message
            else -> "$message ($details)"
        }
    }

    /** Parses an arbitrary JSON answer for callers that only need one field. */
    fun jsonOf(text: String): JsonElement = json.parseToJsonElement(text)

    fun objectOf(text: String): JsonObject = json.parseToJsonElement(text).jsonObject
}

class ApiException(message: String, val status: Int = 0) : Exception(message)
