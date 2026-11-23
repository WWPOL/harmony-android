package lol.mony.harmony.data.repo

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.*

/** Channel, DM and thread messages using Harmony's existing tables and RPCs. */
class ChatRepository(private val rest: RestApi) {
    suspend fun channelMessages(endpoint: Endpoint, token: String, channelId: String,
                                before: String? = null, limit: Int = PAGE): Result<List<Message>> = runCatching {
        rest.select(endpoint, token, "messages", filters = buildList {
            add("channel_id" to "eq.$channelId"); add("thread_id" to "is.null")
            add("is_deleted" to "eq.false")
            before?.let { add("created_at" to "lt.$it") }
        }, order = "created_at.desc,id.desc", limit = limit.coerceIn(1, PAGE),
            serializer = ListSerializer(Message.serializer())).getOrThrow().reversed()
    }

    suspend fun directMessages(endpoint: Endpoint, token: String, conversationId: String,
                               before: String? = null, limit: Int = PAGE): Result<List<Message>> = runCatching {
        rest.select(endpoint, token, "messages", filters = buildList {
            add("conversation_id" to "eq.$conversationId"); add("is_deleted" to "eq.false")
            before?.let { add("created_at" to "lt.$it") }
        }, order = "created_at.desc,id.desc", limit = limit.coerceIn(1, PAGE),
            serializer = ListSerializer(Message.serializer())).getOrThrow().reversed()
    }

    /**
     * A page of a thread's messages, oldest-to-newest, ending at the newest.
     *
     * The newest page is what a thread opens on, so the query orders *descending* and takes the
     * first page, exactly as the web client's `ThreadService.getThreadMessages` does. Ordering
     * ascending and taking the first page instead returns the *oldest* messages, which is why a
     * long thread looked like it had stopped receiving replies.
     */
    suspend fun threadMessages(endpoint: Endpoint, token: String, threadId: String,
                               before: String? = null, limit: Int = PAGE): Result<List<Message>> =
        rest.select(endpoint, token, "messages", filters = buildList {
            add("thread_id" to "eq.$threadId"); add("is_deleted" to "eq.false")
            before?.let { add("created_at" to "lt.$it") }
        }, order = "created_at.desc,id.desc", limit = limit.coerceIn(1, PAGE),
            serializer = ListSerializer(Message.serializer())).map { page -> page.reversed() }

    suspend fun sendChannelMessage(endpoint: Endpoint, token: String, channelId: String,
                                   text: String, replyTo: String? = null, threadId: String? = null,
                                   nonce: String? = null, attachments: List<ContentPart> = emptyList()): Result<Message> = runCatching {
        // Fail closed until this client implements Harmony's own encrypted envelope.
        val encryption = rest.rpcValue(endpoint, token, "effective_channel_encryption",
            buildJsonObject { put("p_channel_id", channelId) }.toString(), JsonElement.serializer()).getOrThrow()
        if (requiresEncryption(encryption)) error("This channel requires encryption. Encrypted messaging is not implemented in this client yet; nothing was sent.")
        insertMessage(endpoint, token, text, channelId, null, replyTo, threadId, nonce, attachments)
    }

    suspend fun sendDirectMessage(endpoint: Endpoint, token: String, conversationId: String,
                                  text: String, replyTo: String? = null, threadId: String? = null,
                                  nonce: String? = null, attachments: List<ContentPart> = emptyList()): Result<Message> = runCatching {
        val encryption = rest.select(endpoint, token, "conversation_encryption_settings",
            filters = listOf("conversation_id" to "eq.$conversationId"),
            serializer = ListSerializer(JsonObject.serializer())).getOrThrow()
        if (encryption.any { (it["encryption_enabled"] as? JsonPrimitive)?.booleanOrNull == true }) {
            error("This conversation requires encryption. Encrypted messaging is not implemented in this client yet; nothing was sent.")
        }
        insertMessage(endpoint, token, text, null, conversationId, replyTo, threadId, nonce, attachments)
    }

    /**
     * Inserts one message, carrying the client nonce in `metadata` as the web client does.
     *
     * The nonce is what lets a retry be recognised as the same message: the reference's outbox
     * reads `metadata.client_nonce` back to match an acknowledged row to the row it drew while
     * the send was in flight.
     *
     * [attachments] are the `file` parts of an upload that has already finished. The instance's
     * own guard refuses a part whose `path` is not an object of this message's room, so a wrong
     * room is an error here rather than an attachment nobody can open later. A message may carry
     * text, attachments, or both -- but not neither.
     */
    private suspend fun insertMessage(endpoint: Endpoint, token: String, text: String,
                                      channelId: String?, conversationId: String?, replyTo: String?,
                                      threadId: String?, nonce: String?,
                                      attachments: List<ContentPart> = emptyList()): Message {
        val files = attachments.filter { it.type == "file" }
        require(text.isNotBlank() || files.isNotEmpty()) { "Write a message first." }
        require(files.size <= MessageMedia.MAX_ATTACHMENTS) {
            "A message may carry at most " + MessageMedia.MAX_ATTACHMENTS + " attachments."
        }
        if (files.isNotEmpty()) {
            val room = MessageMedia.room(channelId, conversationId)
                ?: error("Attachments need a channel or conversation.")
            for (part in files) {
                require(MessageMedia.isPathInRoom(part.path, room)) {
                    "An attachment names an object outside this " + (if (conversationId != null) "conversation" else "channel") + "."
                }
            }
        }
        // Postgres text cannot hold a NUL, and the instance answers such a send with
        // `unsupported Unicode escape sequence` rather than storing it.
        require('\u0000' !in text) { "That message contains a character the instance cannot store. Remove it and send again." }
        require(text.length <= ABSOLUTE_MAX_LENGTH) {
            "This message is longer than the instance allows. Shorten it and send again."
        }
        val profileId = ProfileRepository(rest).mine(endpoint, token)?.profile?.id ?: error("Your profile could not be loaded. Nothing was sent.")
        val body = buildJsonObject {
            put("user_id", profileId)
            channelId?.let { put("channel_id", it) }; conversationId?.let { put("conversation_id", it) }
            replyTo?.let { put("reply_to", it) }; threadId?.let { put("thread_id", it) }
            nonce?.let { value ->
                // `created_via` is a reserved key the instance accepts only as the web
                // client's own value, so this client says exactly that; anything else is
                // refused with 42501 and the send is lost.
                put("metadata", buildJsonObject {
                    put("created_via", "harmony_client")
                    put("client_nonce", value)
                })
            }
            put("content", buildJsonArray {
                if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
                for (part in files) add(HarmonyJson.encodeToJsonElement(ContentPart.serializer(), part))
            })
        }
        val stored = rest.insert(endpoint, token, "messages", body.toString(),
            serializer = ListSerializer(Message.serializer())).getOrThrow()
        stored.firstOrNull()?.let { return it }
        // PostgREST can acknowledge a write with 201 and no representation. That is not a
        // failure and must not be reported as one: the row is there, and the nonce the send
        // carried is how to find it. A send without a nonce has nothing to look it up by, so
        // only then is an empty answer the client's problem.
        if (nonce != null) {
            findByNonce(endpoint, token, channelId, conversationId, nonce)?.let { return it }
        }
        error("The instance did not accept this message. It may have been blocked by moderation.")
    }

    suspend fun editMessage(endpoint: Endpoint, token: String, messageId: String, text: String): Result<Message> = runCatching {
        require(text.isNotBlank()) { "A message cannot be empty." }
        require(text.length <= ABSOLUTE_MAX_LENGTH) { "This message is longer than the instance allows. Shorten it and save again." }
        val original = rest.select(endpoint, token, "messages", filters = listOf("id" to "eq.$messageId"),
            serializer = ListSerializer(Message.serializer())).getOrThrow().firstOrNull()
            ?: error("This message is no longer available.")
        require(!original.encrypted && original.content.all { it.type == "text" }) {
            "Editing encrypted or structured/media messages is not implemented; nothing was changed."
        }
        rest.update(endpoint, token, "messages", listOf("id" to "eq.$messageId"),
            buildJsonObject {
                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
            }.toString(), serializer = ListSerializer(Message.serializer())).getOrThrow().firstOrNull()
            ?: error("This message could not be edited. Check your permissions.")
    }

    suspend fun deleteMessage(endpoint: Endpoint, token: String, messageId: String): Result<Unit> = runCatching {
        val changed = rest.update(endpoint, token, "messages", listOf("id" to "eq.$messageId"),
            buildJsonObject {
                put("is_deleted", true)
                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "[deleted]") }) })
            }.toString(), serializer = ListSerializer(Message.serializer())).getOrThrow()
        check(changed.isNotEmpty()) { "This message could not be deleted. Check your permissions." }
    }

    suspend fun pin(endpoint: Endpoint, token: String, messageId: String, pinned: Boolean): Result<Unit> =
        rest.rpcUnit(endpoint, token, if (pinned) "pin_message" else "unpin_message",
            buildJsonObject { put("p_message_id", messageId) }.toString())

    suspend fun pinned(endpoint: Endpoint, token: String, channelId: String): Result<List<Message>> = rest.select(
        endpoint, token, "messages", filters = listOf("channel_id" to "eq.$channelId", "is_pinned" to "eq.true"),
        order = "pinned_at.desc", limit = PAGE, serializer = ListSerializer(Message.serializer()))

    suspend fun threads(endpoint: Endpoint, token: String, channelId: String): Result<List<Thread>> = rest.select(
        endpoint, token, "threads", filters = listOf("channel_id" to "eq.$channelId"),
        order = "last_message_at.desc.nullslast,created_at.desc", limit = PAGE, serializer = ListSerializer(Thread.serializer()))

    suspend fun thread(endpoint: Endpoint, token: String, threadId: String): Result<Thread?> = runCatching {
        rest.select(endpoint, token, "threads", filters = listOf("id" to "eq.$threadId"),
            serializer = ListSerializer(Thread.serializer())).getOrThrow().firstOrNull()
    }

    suspend fun createThread(endpoint: Endpoint, token: String, channelId: String, parentMessageId: String,
                             name: String? = null, serverId: String? = null): Result<Thread> = runCatching {
        val id = rest.rpcValue(endpoint, token, "create_thread", buildJsonObject {
            put("p_message_id", parentMessageId); put("p_name", name?.takeIf { it.isNotBlank() } ?: "Discussion")
            put("p_auto_archive_duration", 1440)
        }.toString(), String.serializer()).getOrThrow()
        thread(endpoint, token, id).getOrThrow() ?: error("The new thread could not be loaded.")
    }

    /**
     * The row a previous send of [nonce] already stored, if any.
     *
     * The instance does not enforce uniqueness on `metadata.client_nonce`, so a retry that just
     * sends again can produce a second message. The reference's outbox checks the nonce before
     * retrying (`useOutbox.ts`), and so does this: a retry that already landed adopts the row
     * the instance holds instead of posting a duplicate.
     */
    suspend fun findByNonce(endpoint: Endpoint, token: String, channelId: String?, conversationId: String?,
                            nonce: String): Message? = runCatching {
        val filters = buildList {
            add("metadata->>client_nonce" to "eq.$nonce")
            channelId?.let { add("channel_id" to "eq.$it") }
            conversationId?.let { add("conversation_id" to "eq.$it") }
        }
        rest.select(endpoint, token, "messages", filters = filters, limit = 1,
            serializer = ListSerializer(Message.serializer())).getOrThrow().firstOrNull()
    }.getOrNull()

    /** One message by id, for a notification that names a message outside the loaded page. */
    suspend fun messageById(endpoint: Endpoint, token: String, messageId: String): Message? =
        rest.select(endpoint, token, "messages", filters = listOf("id" to "eq.$messageId", "is_deleted" to "eq.false"),
            limit = 1, serializer = ListSerializer(Message.serializer())).getOrNull()?.firstOrNull()

    suspend fun reactionsFor(endpoint: Endpoint, token: String, messageIds: Collection<String>): Result<Map<String, List<Reaction>>> = runCatching {
        if (messageIds.isEmpty()) return@runCatching emptyMap()
        rest.select(endpoint, token, "reactions", filters = listOf("message_id" to "in.(${messageIds.take(PAGE).joinToString(",")})"),
            serializer = ListSerializer(Reaction.serializer())).getOrThrow().groupBy { it.messageId }
    }

    /**
     * The custom emoji this account can react with: the room's server's own, the instance's
     * imports, and the account's personal ones.
     *
     * The filter is PostgREST's `or`, and it is deliberately *wider* than the room: the
     * instance and personal emoji are usable in any room, and a server's emoji are the ones a
     * person reaches for in that server. Narrowing it to the server alone would hide a
     * person's own emoji from their DMs, which is where they most want them.
     *
     * A row that was removed keeps its id for the messages already carrying it
     * (`deleted_at`, the lifecycle migration) but is no longer offered, and neither is one
     * whose file was purged: the reference filters the same two ways.
     */
    suspend fun emojiLibrary(endpoint: Endpoint, token: String, serverId: String? = null): Result<List<CustomEmoji>> = runCatching {
        val filters = buildList {
            add("deleted_at" to "is.null")
            add("name" to "not.is.null")
            if (serverId != null) {
                add("or" to "(scope.eq.instance,scope.eq.user,server_id.eq.$serverId)")
            } else {
                add("or" to "(scope.eq.instance,scope.eq.user)")
            }
        }
        rest.select(endpoint, token, "emojis", filters = filters, order = "name.asc", limit = EMOJI_PAGE,
            serializer = ListSerializer(CustomEmoji.serializer())).getOrThrow()
            .filter { it.hasImage && !it.name.isNullOrBlank() }
    }

    /**
     * The `emojis` rows a set of ids names, so a custom emoji can be drawn as its picture.
     *
     * A reaction row carries `emoji_id`, and a message part carries the emoji's whole row; the
     * id alone is a uuid, which is why a client that draws only what the reaction stores shows
     * an id where a picture belongs. `emojis` is world-readable (`emojis_select_all`), so one
     * `in.(...)` read answers for a whole page. A removed emoji keeps its row and its file, and
     * is still what old content shows, so `deleted_at` is not filtered here.
     */
    suspend fun emojis(endpoint: Endpoint, token: String, ids: Collection<String>): Map<String, Emoji> {
        val unique = ids.filter { isEmojiRowId(it) }.distinct()
        if (unique.isEmpty()) return emptyMap()
        return rest.select(endpoint, token, "emojis", EMOJI_COLUMNS,
            listOf("id" to "in.(${unique.take(PAGE).joinToString(",")})"),
            serializer = ListSerializer(Emoji.serializer()),
        ).getOrThrow().mapNotNull { row -> row.id?.let { it to row } }.toMap()
    }

    /**
     * The `emojis` rows a set of `:shortcodes:` names, so text that uses one draws its picture.
     *
     * A message's text is where a custom emoji is used most: a person types `:blobcat:` and the
     * reference draws blobcat's picture for it. Only the names the instance actually has come
     * back; the rest stay the text they were written as, which is what the reference's own
     * renderer does with a name its resolver cannot answer.
     */
    suspend fun emojisNamed(endpoint: Endpoint, token: String, names: Collection<String>): Map<String, Emoji> {
        val unique = names.map { it.substringBefore('~').trim().trim(':') }
            .filter { EMOJI_NAME.matches(it) }.distinct()
        if (unique.isEmpty()) return emptyMap()
        return rest.select(endpoint, token, "emojis", EMOJI_COLUMNS,
            listOf("name" to "in.(${unique.take(PAGE).joinToString(",")})"),
            serializer = ListSerializer(Emoji.serializer()),
        ).getOrThrow().mapNotNull { row -> row.id?.let { it to row } }.toMap()
    }

    /**
     * Adds this account's reaction with one emoji to a message, as the reference client writes it.
     *
     * [emojiId] names an `emojis` row and goes in `emoji_id`; [content] is a unicode glyph, a
     * shortcode or a bridged `discord:` identifier and goes in `custom_emoji_content`. Exactly one
     * of the two is set, which is the shape the instance's `check_reaction_emoji` trigger accepts.
     */
    suspend fun addReaction(endpoint: Endpoint, token: String, messageId: String, userId: String,
                            emojiId: String?, content: String?): Result<Unit> = runCatching {
        require(emojiId != null || content != null) { "A reaction names an emoji." }
        rest.insert(endpoint, token, "reactions", buildJsonObject {
            put("message_id", messageId); put("user_id", userId)
            emojiId?.let { put("emoji_id", it) }
            content?.let { put("custom_emoji_content", it) }
        }.toString(), serializer = ListSerializer(Reaction.serializer())).getOrThrow()
    }.map { Unit }.recoverCatching { error ->
        throw readableReactionFailure(error)
    }

    /**
     * Removes this account's reaction, filtering on the column that reaction was written to.
     *
     * The old delete always filtered `custom_emoji_content`, which is null on a row that names its
     * emoji by id, so `eq.<glyph>` matched nothing and the reaction could not be taken back.
     */
    suspend fun removeReaction(endpoint: Endpoint, token: String, messageId: String, userId: String,
                               emojiId: String?, content: String?): Result<Unit> {
        val filters = buildList {
            add("message_id" to "eq.$messageId"); add("user_id" to "eq.$userId")
            if (emojiId != null) add("emoji_id" to "eq.$emojiId")
            else if (content != null) add("custom_emoji_content" to "eq.$content")
        }
        if (filters.size == 2) return Result.failure(IllegalArgumentException("A reaction names an emoji."))
        return rest.delete(endpoint, token, "reactions", filters)
    }

    /**
     * The instance's refusal of a reaction as a sentence a person can act on.
     *
     * Every reason below is the database's own wording, raised by a trigger; the reference client
     * maps the same codes to its own strings. Anything else keeps its own message rather than
     * being replaced by a generic one.
     */
    private fun readableReactionFailure(error: Throwable): Throwable {
        val message = error.message.orEmpty()
        val known = when {
            message.contains("Maximum emoji reactions", ignoreCase = true) ->
                "You have reached the 20-reaction limit on this message."
            message.contains("USE_EXTERNAL_EMOJIS") ->
                "That emoji belongs to another server, and you cannot use it here."
            message.contains("ADD_REACTIONS") ->
                "You cannot add reactions in this channel."
            message.contains("REACTION_EMOJI") ->
                "That emoji is no longer available."
            else -> null
        }
        return known?.let { IllegalArgumentException(it) } ?: error
    }

    suspend fun conversations(endpoint: Endpoint, token: String, profileId: String,
                              profiles: ProfileRepository): Result<List<DirectConversation>> = runCatching {
        val mine = rest.select(endpoint, token, "conversation_participants",
            filters = listOf("user_id" to "eq.$profileId", "left_at" to "is.null", "hidden_at" to "is.null"),
            limit = PAGE, serializer = ListSerializer(ConversationParticipant.serializer())).getOrThrow()
        if (mine.isEmpty()) return@runCatching emptyList()
        val ids = mine.map { it.conversationId }.distinct()
        val rows = rest.select(endpoint, token, "conversations", filters = listOf("id" to "in.(${ids.joinToString(",")})", "is_active" to "eq.true"),
            order = "updated_at.desc", limit = PAGE, serializer = ListSerializer(Conversation.serializer())).getOrThrow()
        val participants = rest.select(endpoint, token, "conversation_participants",
            filters = listOf("conversation_id" to "in.(${ids.joinToString(",")})", "left_at" to "is.null"),
            serializer = ListSerializer(ConversationParticipant.serializer())).getOrThrow()
        val people = profiles.byIds(endpoint, token, participants.map { it.userId }.distinct())
        rows.map { conversation -> DirectConversation(conversation,
            participants.filter { it.conversationId == conversation.id && it.userId != profileId }.mapNotNull { people[it.userId] }) }
    }

    suspend fun conversation(endpoint: Endpoint, token: String, conversationId: String,
                             profileId: String, profiles: ProfileRepository): DirectConversation? =
        conversations(endpoint, token, profileId, profiles).getOrThrow().find { it.conversation.id == conversationId }

    suspend fun openDirectConversation(endpoint: Endpoint, token: String, myProfileId: String, otherProfileId: String): Result<String> =
        rest.rpcValue(endpoint, token, "create_or_get_direct_conversation", buildJsonObject {
            put("user1_uuid", myProfileId); put("user2_uuid", otherProfileId)
        }.toString(), String.serializer())

    suspend fun unreadCounts(endpoint: Endpoint, token: String): List<UnreadCountRow> = rest.rpc(
        endpoint, token, "get_unread_counts", serializer = ListSerializer(UnreadCountRow.serializer())).getOrThrow()

    suspend fun markChannelRead(endpoint: Endpoint, token: String, channelId: String, messageId: String?) =
        rest.rpcUnit(endpoint, token, "mark_channel_as_read", buildJsonObject {
            put("p_channel_id", channelId); put("p_message_id", messageId?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()).getOrThrow()

    suspend fun markConversationRead(endpoint: Endpoint, token: String, conversationId: String, messageId: String?) =
        rest.rpcUnit(endpoint, token, "mark_conversation_as_read", buildJsonObject {
            put("p_conversation_id", conversationId); put("p_message_id", messageId?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()).getOrThrow()

    suspend fun search(endpoint: Endpoint, token: String, query: String, limit: Int = 25,
                       channelId: String? = null): Result<List<Message>> = rest.rpc(endpoint, token, "search_messages", buildJsonObject {
        put("p_query", query); put("p_limit", limit.coerceIn(1, PAGE)); channelId?.let { put("p_channel_id", it) }
    }.toString(), ListSerializer(Message.serializer()))

    companion object {
        const val PAGE = 50

        /** How many custom emoji one read of the library asks for. */
        const val EMOJI_PAGE = 1000

        /** The default `max_message_length`, and the ceiling the instance clamps it to. */
        const val DEFAULT_MAX_LENGTH = 2000
        const val ABSOLUTE_MAX_LENGTH = 50000
    }
}

internal fun requiresEncryption(element: JsonElement): Boolean = when (element) {
    is JsonArray -> element.any(::requiresEncryption)
    is JsonObject -> (element["messages_encrypted"] as? JsonPrimitive)?.booleanOrNull == true
    is JsonPrimitive -> element.booleanOrNull == true
    else -> false
}

internal object JsonPrimitiveText { fun quote(value: String): String = JsonPrimitive(value).toString() }
internal fun nowIso(): String = java.time.Instant.now().toString()

/** The columns of an `emojis` row this client draws, so a wide read cannot break a page. */
private const val EMOJI_COLUMNS = "id,name,url,domain"

/** An `emojis.name`: the instance allows `_~-` up to 64, without the colons a shortcode has. */
private val EMOJI_NAME = Regex("^[A-Za-z0-9_+~\\-]{1,64}$")
