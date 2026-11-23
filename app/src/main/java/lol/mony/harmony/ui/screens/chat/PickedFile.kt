package lol.mony.harmony.ui.screens.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lol.mony.harmony.data.repo.MessageMedia
import lol.mony.harmony.data.repo.MessageMediaRepository
import java.io.ByteArrayOutputStream

/**
 * Reading what a person picked into the bytes an upload sends.
 *
 * Android's picker hands back a `content:` URI, not a file: the name, the type and the size live
 * in the provider's own metadata, and the bytes have to be read through the resolver while the
 * grant is still valid. Reading here -- once, on the IO dispatcher, into one array -- is what
 * lets the composer show a preview, strip the metadata and upload the *same* bytes the person
 * chose. A second read could see a different file.
 *
 * The size is capped before the read: a provider can name a two-gigabyte file, and reading it
 * into memory to discover it is too large would kill the app rather than refuse the file.
 */
object PickedFile {
    /** The largest file this client reads into memory; storage's own limit is far below it. */
    const val MAX_BYTES = 100L * 1024 * 1024

    data class Read(val name: String, val mimeType: String?, val size: Long, val bytes: ByteArray)

    /**
     * Reads [uri] into memory.
     *
     * A file over [MAX_BYTES] is refused by name rather than by an out-of-memory crash, and a
     * URI the grant no longer covers is reported as the expired permission it is.
     */
    suspend fun read(context: Context, uri: Uri): Result<Read> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val type = resolver.getType(uri)
            var name: String? = null
            var declared = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declared = cursor.getLong(sizeIndex)
                    }
                }
            require(declared <= MAX_BYTES) {
                "\"" + (name ?: "That file") + "\" is larger than this app can attach. Pick a smaller file."
            }
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream(if (declared in 1..MAX_BYTES) declared.toInt() else 64 * 1024)
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) {
                        "\"" + (name ?: "That file") + "\" is larger than this app can attach. Pick a smaller file."
                    }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            } ?: error("That file could not be opened. Pick it again.")
            require(bytes.isNotEmpty()) { "That file is empty." }
            val resolvedName = name?.takeIf { it.isNotBlank() } ?: "attachment." + MessageMedia.extension(null, type)
            Read(
                name = resolvedName,
                mimeType = type ?: MessageMedia.guessMimeType(resolvedName),
                size = bytes.size.toLong(),
                bytes = bytes,
            )
        }
    }

    /** The picked file as the repository's own shape. */
    fun asPicked(read: Read): MessageMediaRepository.Picked =
        MessageMediaRepository.Picked(read.name, read.mimeType, read.size, read.bytes)
}
