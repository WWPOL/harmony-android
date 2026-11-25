package lol.mony.harmony.data.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Who a reaction row is from, and what the reactor list shows for them.
 *
 * A reaction names its reactor one of three ways, and the reference client resolves them in a
 * fixed order (`toReactionUser`, `src/utils/reactionUsers.ts`):
 *
 *  1. `metadata.discord_user` -- a Discord person a bridge relays, whose own name and picture
 *     travel with the reaction because there is no local profile to look up.
 *  2. `bot_id` -- a local `bots` row, and only when there is no `user_id` as well.
 *  3. `user_id` -- a `profiles` row, which is the ordinary case.
 *
 * Reading only `user_id` is what made every bridged and bot reaction nameless: those rows carry
 * no `user_id` at all, so the list fell through to its "Someone" placeholder for every one of
 * them, which is the bug this type closes.
 *
 * A reaction is deleted with its profile (`reactions.user_id ... ON DELETE CASCADE`), so a
 * `user_id` that does not resolve was simply not read yet -- a network failure, not a deleted
 * account. It says that plainly instead of guessing a name.
 */
data class ReactionActor(
    /** Stable within one pill: two rows from the same person are one line. */
    val key: String,
    val name: String,
    /** The account behind the reaction, for a profile tap; null for a bot or a bridged person. */
    val profileId: String? = null,
    val botId: String? = null,
    /** `@name`, or `@name@domain` for a remote account. */
    val handle: String? = null,
    /** The avatar exactly as the row stores it: absolute, instance-relative, or a storage path. */
    val avatarPath: String? = null,
    val isBot: Boolean = false,
    /** True for a Discord person a bridge relayed, which the row marks with its bridge. */
    val isBridged: Boolean = false,
    /** True when this account is the signed-in one. */
    val isYou: Boolean = false,
) {
    /** True when there is a profile to open; a bot and a bridged person have none. */
    val isPerson: Boolean get() = profileId != null
}

/** The Discord person a bridged reaction names in `reactions.metadata.discord_user`. */
val Reaction.discordUser: JsonObject?
    get() = (metadata as? JsonObject)?.let { it["discord_user"] as? JsonObject }

/**
 * The actor this reaction row names, as the reactor list draws it.
 *
 * The order is the reference client's, and it is load-bearing rather than incidental: a bridged
 * row usually carries a `bot_id` too (the bridge's own bot), so reading `bot_id` first would
 * attribute every Discord person's reaction to the bridge.
 */
fun Reaction.actor(
    people: Map<String, Profile>,
    bots: Map<String, Bot> = emptyMap(),
    myId: String? = null,
): ReactionActor {
    val discord = discordUser
    if (discord != null) {
        val username = discord.text("username")
        // A bridged row that names no id still has its own reaction id, which is unique within
        // the pill; falling back to the emoji's key would make two such rows one line.
        val discordId = discord.text("id") ?: discord.text("user_id") ?: id.orEmpty()
        return ReactionActor(
            key = "discord:$discordId",
            name = discord.text("display_name") ?: username ?: "Discord user",
            handle = username?.let { "@$it" },
            avatarPath = discord.text("avatar_url"),
            isBridged = true,
        )
    }
    if (botId != null && userId == null) {
        val bot = bots[botId]
        return ReactionActor(
            key = "bot:$botId",
            name = bot?.shownName ?: "Bot",
            botId = botId,
            handle = bot?.username?.takeIf { it.isNotBlank() }?.let { "@$it" },
            avatarPath = bot?.avatarUrl,
            isBot = true,
        )
    }
    if (userId != null) {
        val profile = people[userId]
        return ReactionActor(
            key = "user:$userId",
            name = profile?.shownName ?: "Unknown account",
            profileId = userId,
            handle = profile?.handle(),
            avatarPath = profile?.avatarUrl,
            isYou = myId != null && userId == myId,
        )
    }
    // The instance's own `reactions_has_author` requires one of the two, so this is a row this
    // build cannot read rather than a person it cannot name.
    return ReactionActor(key = "reaction:${id.orEmpty()}", name = "Unknown account")
}

/**
 * The reactor list for one pill: one line per reaction row, named by [Reaction.actor].
 *
 * A person who reacted with two different emoji is two lines, one under each pill; the sheet
 * only ever asks for one pill's rows.
 */
fun reactionActors(
    reactions: List<Reaction>,
    people: Map<String, Profile>,
    bots: Map<String, Bot> = emptyMap(),
    myId: String? = null,
): List<ReactionActor> = reactions.map { it.actor(people, bots, myId) }

/** A string field of a JSON object, or null when it is absent, null or blank. */
private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?.content?.takeIf { it.isNotBlank() }
