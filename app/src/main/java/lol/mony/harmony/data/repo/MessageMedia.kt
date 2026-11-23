package lol.mony.harmony.data.repo

import lol.mony.harmony.data.model.ContentPart
import java.util.UUID

/**
 * Chat attachments in the instance's private `message_media` bucket.
 *
 * The object name is `<room>/<uploader auth uid>/<uuid>.<ext>`, where the room is
 * `c/<channel id>` for a channel or thread message and `d/<conversation id>` for a direct
 * message. That shape is not this client's invention: the bucket's own row-level policies read
 * the third folder as the uploader and the first two as the room, so an object stored under any
 * other name is refused at upload and invisible to the people in the room afterwards.
 *
 * A message names an attachment twice. `path` is the object name, which is the truth and is what
 * a reader signs; `url` is a signed URL valid for seven days from upload, carried for clients
 * that predate `path`. This client writes both and renders `path` first, because the URL in an
 * old message has expired while the object has not.
 */
object MessageMedia {
    /** The bucket every chat attachment lives in. */
    const val BUCKET = "message_media"

    /** Lifetime of a URL signed for viewing; the reference's `VIEW_URL_TTL_SECONDS`. */
    const val VIEW_URL_TTL_SECONDS = 6 * 60 * 60

    /** Lifetime of the URL a new message part carries; the reference's compat window. */
    const val COMPAT_URL_TTL_SECONDS = 7 * 24 * 60 * 60

    /** storage-js's `DEFAULT_FILE_OPTIONS.cacheControl`. */
    const val CACHE_CONTROL = "3600"

    /** The most attachments one message may carry, as the reference's default limit says. */
    const val MAX_ATTACHMENTS = 10

    /** An image a person may pick but never upload: SVG can carry script. */
    const val SVG_REFUSAL =
        "SVG files are not allowed (they can contain embedded scripts). Convert it to PNG or WebP first."

    private val UUID_PATTERN = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )

    /**
     * The room prefix an attachment belongs in, or null when there is no valid room.
     *
     * A direct message wins over a channel when both are given, which is how the reference's
     * `mediaRoom` reads a target; the send itself never carries both.
     */
    fun room(channelId: String?, conversationId: String?): String? = when {
        conversationId != null -> conversationId.trim().takeIf(UUID_PATTERN::matches)?.let { "d/" + it.lowercase() }
        channelId != null -> channelId.trim().takeIf(UUID_PATTERN::matches)?.let { "c/" + it.lowercase() }
        else -> null
    }

    /** The room prefix of an object name (`c/<id>` or `d/<id>`), or null for anything else. */
    fun roomOf(path: String?): String? {
        val value = path ?: return null
        val kind = value.substringBefore('/', "")
        val id = value.substringAfter('/', "").substringBefore('/')
        if ((kind != "c" && kind != "d") || !UUID_PATTERN.matches(id)) return null
        return "$kind/${id.lowercase()}"
    }

    /**
     * The object name for a new upload: `<room>/<uploader>/<uuid>.<ext>`.
     *
     * A fresh name per upload is deliberate. The bucket's policy refuses `upsert`, and two people
     * sending the same picture must not collide on one object either.
     */
    fun path(room: String, uploaderAuthId: String, fileName: String?, mimeType: String? = null): String {
        require(roomOf(room) == room.lowercase()) { "Attachments need a channel or conversation" }
        require(UUID_PATTERN.matches(uploaderAuthId)) { "Your account id is not available. Sign in again." }
        return room.lowercase() + "/" + uploaderAuthId.lowercase() + "/" +
            java.util.UUID.randomUUID().toString() + "." + extension(fileName, mimeType)
    }

    /** A `message_media` object name: a room object, or one of the author's own Drive uploads. */
    fun isPath(path: String?): Boolean {
        val value = path ?: return false
        if (roomOf(value) != null) return true
        val segments = value.split('/')
        return segments.size == 3 && segments[0] == "u" && UUID_PATTERN.matches(segments[1]) &&
            segments[2].isNotEmpty() && segments[2] !in setOf(".", "..") &&
            segments[2].none { it == '\\' || it == '?' || it == '#' }
    }

    /** True when [path] names an object of [room] itself, which is what the instance's guard checks. */
    fun isPathInRoom(path: String?, room: String?): Boolean =
        path != null && room != null && path.lowercase().startsWith(room.lowercase() + "/")

    /**
     * The kind of media a MIME type is, in the reference's four words.
     *
     * A part's `fileType` decides how every client draws it -- an image inline, a video or a
     * document as a card -- so it is derived from the type and never guessed from the name.
     */
    fun fileTypeOf(mimeType: String?): String = when {
        mimeType == null -> "file"
        mimeType.startsWith("image/", ignoreCase = true) -> "image"
        mimeType.startsWith("video/", ignoreCase = true) -> "video"
        mimeType.startsWith("audio/", ignoreCase = true) -> "audio"
        else -> "file"
    }

    /** The stored content type: the picked one, else a guess from the name, else a byte stream. */
    fun contentTypeOf(mimeType: String?, fileName: String?): String =
        mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: guessMimeType(fileName)
            ?: "application/octet-stream"

    /** A best-effort type from the extension, for a picker that reported none. */
    fun guessMimeType(fileName: String?): String? = when (extension(fileName, null)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "heic", "heif" -> "image/heic"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "zip" -> "application/zip"
        else -> null
    }

    /**
     * The object's extension: the name's own when it is short and alphanumeric, else the type's,
     * else `bin`. The extension is part of the object name only, so it never decides how a part
     * is drawn.
     */
    fun extension(fileName: String?, mimeType: String?): String {
        val name = fileName.orEmpty()
        val fromName = name.substringAfterLast('.', "").lowercase()
        if (fromName.length in 1..8 && fromName.all { it in 'a'..'z' || it in '0'..'9' } && fromName != name.lowercase()) {
            return fromName
        }
        return when (mimeType?.substringAfter('/', "")?.lowercase()) {
            "png" -> "png"
            "jpeg", "jpg" -> "jpg"
            "gif" -> "gif"
            "webp" -> "webp"
            "avif" -> "avif"
            "mp4" -> "mp4"
            "quicktime" -> "mov"
            "webm" -> "webm"
            "mpeg" -> "mp3"
            "pdf" -> "pdf"
            "plain" -> "txt"
            "zip" -> "zip"
            else -> "bin"
        }
    }

    /**
     * The `file` part a message carries for one uploaded object.
     *
     * Every field here is read by another client: `path` is what it signs, `url` is what a client
     * that predates `path` renders, and the rest are the card's own words.
     */
    fun filePart(
        path: String,
        url: String?,
        fileName: String?,
        mimeType: String?,
        size: Long?,
        altText: String? = null,
        fileType: String = fileTypeOf(mimeType),
    ): ContentPart = ContentPart(
        type = "file",
        url = url,
        path = path,
        fileType = fileType,
        fileName = fileName,
        fileSize = size,
        mimeType = mimeType,
        altText = altText?.trim()?.takeIf { it.isNotEmpty() },
    )

    /** True when a picked file is one the bucket's policies and this client both refuse. */
    fun isSvg(fileName: String?, mimeType: String?): Boolean =
        mimeType?.equals("image/svg+xml", ignoreCase = true) == true ||
            fileName?.lowercase()?.endsWith(".svg") == true

    /** A byte count as a person reads it, as the reference's `formatBytes` writes it. */
    fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "0 MB"
        bytes >= 1024L * 1024 -> {
            val mb = bytes / (1024.0 * 1024.0)
            if (mb < 10) String.format(java.util.Locale.ROOT, "%.1f", mb).removeSuffix(".0") + " MB"
            else Math.round(mb).toString() + " MB"
        }
        else -> maxOf(1, Math.round(bytes / 1024.0)).toString() + " KB"
    }

    /** What to say about a file over the account's own upload limit. */
    fun overLimit(name: String?, size: Long, limit: Long): String =
        (name?.takeIf { it.isNotBlank() }?.let { "\"$it\" is " } ?: "That file is ") +
            formatBytes(size) + ", over this account's " + formatBytes(limit) + " upload limit."
}
