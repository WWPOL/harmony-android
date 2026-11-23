package lol.mony.harmony.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One row of the instance's `emojis` table: a custom emoji the account can react with.
 *
 * The table is the web client's emoji library (`src/services/emojiService.ts`), and a row is
 * one of three kinds, told apart by [scope]:
 *
 *  - `server`: uploaded to a server, usable in that server's channels;
 *  - `instance`: imported to the instance itself, usable everywhere;
 *  - `user`: this account's own, including the AI-generated ones, usable everywhere.
 *
 * Only the fields the picker needs are decoded; the row carries more (usage counts, the
 * uploader, file sizes) and a field this client does not read is not a reason to fail a page
 * of emoji. [deletedAt] is the lifecycle migration's tombstone: a removed emoji keeps its row
 * and its messages, and leaves the picker.
 */
@Serializable
data class CustomEmoji(
    val id: String,
    val name: String? = null,
    val url: String? = null,
    @SerialName("server_id") val serverId: String? = null,
    val scope: String = "server",
    val domain: String? = null,
    @SerialName("is_ai_generated") val isAiGenerated: Boolean = false,
    @SerialName("deleted_at") val deletedAt: String? = null,
    val metadata: JsonObject? = null,
) {
    /** The `:name:` this emoji is written as, which is what a pill shows when it cannot draw. */
    val shortcode: String get() = ":" + (name ?: id.take(8)) + ":"

    /** True when there is a picture to draw; a row whose file was purged draws its shortcode. */
    val hasImage: Boolean get() = !url.isNullOrBlank()

    /** What a screen reader says, and what a search matches: the name with its words apart. */
    val searchable: String get() = (name ?: "").replace('_', ' ').replace('-', ' ').lowercase()

    /**
     * What the picker's custom-emoji section is headed by.
     *
     * A person's own emoji and the instance's imports are not tied to a server, so they are
     * one section; a server's emoji is headed by the server's name, which the caller knows.
     */
    val isPersonal: Boolean get() = scope == "user" || scope == "instance" || serverId == null
}
