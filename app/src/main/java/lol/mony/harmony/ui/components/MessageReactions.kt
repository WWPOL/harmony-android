package lol.mony.harmony.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.Emoji
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.model.Reaction

/**
 * One pill under a message: which emoji it is, what it draws, and how many people used it.
 *
 * This is computed rather than drawn so that the identity, the picture and the label can be
 * asserted without a screen. The bug this closes was a pill whose label *was* the emoji's row id:
 * `reactions.emoji_id` is a uuid, and the old pill drew the reaction's stored content as text, so
 * a custom emoji whose row had no content showed `3f2a…` under the message.
 *
 * @param key the identity a tap toggles, and the one the reactor list filters by: the emoji's row
 *   id when there is one, else the content the row stores. Two people reacting with the same emoji
 *   are one pill; two different server emoji are never one pill.
 * @param emoji what to draw. Its `url` is the picture, its `content` a unicode glyph, its `name`
 *   the shortcode. A pill whose `emojis` row was not read carries the name or glyph the reaction
 *   stores instead, and never the row id.
 * @param label the shortcode as text (`:blobcat:`, `👍`), which is what a screen reader says and
 *   what a pill falls back to when its picture cannot load.
 */
data class ReactionPill(
    val key: String,
    val emoji: Emoji,
    val label: String,
    val count: Int,
    val mine: Boolean,
    val reaction: Reaction,
)

/**
 * The pills a message's reaction rows make, in the order the instance returned them.
 *
 * Rows are grouped by [Reaction.key]. A group's emoji is resolved from the `emojis` row the
 * reaction names ([emojis] keyed by id), falling back to what the reaction itself stores:
 *
 * - a unicode glyph draws as itself;
 * - `:name:` and `:name@host:` draw `:name:`;
 * - `discord:<name>:<id>` draws Discord's own image for that snowflake, which the identifier
 *   carries in full;
 * - a bare name (what a client before 1.9.4 wrote) draws `:name:`;
 * - an id with no row read yet draws `:emoji:` and is re-resolved when the row arrives.
 */
fun reactionPills(reactions: List<Reaction>, emojis: Map<String, Emoji>, myId: String?): List<ReactionPill> =
    reactions.groupBy { it.key }.map { (key, group) ->
        val first = group.first()
        val row = first.emojiId?.let { emojis[it] }
        val stored = Emoji.fromStoredContent(first.customEmojiContent)
        // A bridged reaction keeps its picture in the row's metadata rather than in an `emojis`
        // row, so it is read here before the stored content is asked for one.
        val bridged = first.remoteEmojiUrl?.let { stored.copy(url = stored.url ?: it) } ?: stored
        // The row is the truth; the stored content fills what it lacks, which is how a purged
        // emoji keeps its name and a bridged one keeps its picture.
        val emoji = when {
            row == null -> bridged
            else -> row.copy(
                name = row.name ?: bridged.name,
                content = row.content ?: bridged.content,
                url = row.url ?: bridged.url,
            )
        }
        ReactionPill(
            key = key,
            emoji = emoji,
            label = reactionLabel(first, row),
            count = group.size,
            mine = myId != null && group.any { it.userId == myId },
            reaction = first,
        )
    }

/**
 * The reaction pills under a message.
 *
 * A tap toggles this account's own reaction with that emoji, which is what a messaging app
 * does and what the web client's pill does: the same gesture that added it takes it away. A
 * long press opens the list of who reacted with it. The two must not both fire, so the long
 * press is consumed: `combinedClickable` reports `onClick` only for a press that was not held
 * past the long-press timeout, and the pill keeps its own haptic so the press is felt before
 * the sheet appears.
 *
 * The list is drawn with `FlowRow` rather than a `Row` so a message with twenty pills wraps
 * instead of running off the screen edge.
 *
 * @param emojis the `emojis` rows resolved for this page, keyed by id, so a custom emoji draws
 *   its picture rather than its name.
 * @param baseUrl the instance a relative emoji `url` resolves against.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MessageReactions(
    message: Message,
    reactions: List<Reaction>,
    myId: String,
    onToggle: (String) -> Unit,
    onShowReactors: (String) -> Unit,
    emojis: Map<String, Emoji> = emptyMap(),
    baseUrl: String? = null,
) {
    val pills = reactionPills(reactions, emojis, myId)
    FlowRow(modifier = Modifier.testTag("reactions-" + message.id), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (pill in pills) ReactionPillView(pill, baseUrl, onToggle, onShowReactors)
    }
}

/** One pill: its emoji, its count, and the two gestures, drawn from a resolved [ReactionPill]. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ReactionPillView(
    pill: ReactionPill,
    baseUrl: String?,
    onToggle: (String) -> Unit,
    onShowReactors: (String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val spoken = pillSpoken(pill)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (pill.mine) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .combinedClickable(
                // A tap is the toggle, and only a tap: `combinedClickable` does not
                // call this after a long press, so the sheet is not dismissed by the
                // finger coming up.
                onClick = { onToggle(pill.key) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onShowReactors(pill.key)
                },
            )
            .testTag("reaction-" + pill.key)
            .semantics {
                contentDescription = spoken
                // The same two actions are reachable without a gesture, so a person
                // using a screen reader is not required to hold a finger still.
                customActions = listOf(
                    CustomAccessibilityAction(if (pill.mine) "Remove your reaction" else "Add your reaction") {
                        onToggle(pill.key); true
                    },
                    CustomAccessibilityAction("See who reacted") { onShowReactors(pill.key); true },
                )
            },
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The pill's own tag names the emoji it drew, so a test -- and a screenshot reader --
            // can tell an image from a shortcode without reading the pixels.
            EmojiGlyph(
                emoji = pill.emoji,
                label = pill.label,
                size = 18.dp,
                baseUrl = baseUrl,
                decorative = true,
                modifier = Modifier.testTag("reaction-emoji-" + pill.key),
            )
            Spacer(Modifier.width(4.dp))
            // The count is already in the pill's own description; announcing it twice is noise.
            Text(pill.count.toString(), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

/**
 * What a pill shows for its emoji: the custom emoji's own shortcode, else the glyph.
 *
 * This used to read `:custom:` for every custom emoji, which made two different server emoji
 * indistinguishable on the row -- and it is what let the old tap handler remove the wrong
 * pill, because it compared this label. Then it read the row id, which is worse: a person was
 * shown a uuid. The stored content already carries the shortcode (`:cat:`, `:cat@host:`,
 * `discord:cat:id`), so that is what is drawn when the `emojis` row was not read, and the row's
 * own name when it was.
 */
internal fun reactionLabel(reaction: Reaction, row: Emoji? = null): String {
    val name = row?.name?.takeIf { it.isNotBlank() }
    if (name != null) return customEmojiLabel(name).ifBlank { ":emoji:" }
    val stored = Emoji.fromStoredContent(reaction.customEmojiContent)
    stored.glyph?.let { return it }
    stored.name?.takeIf { it.isNotBlank() }?.let { return customEmojiLabel(it) }
    // Nothing readable is stored: the row id is not a thing to show, so say what it is.
    return ":emoji:"
}

/** What a pill says to a screen reader: the emoji, the count, and whether it is this account's. */
internal fun pillSpoken(pill: ReactionPill): String =
    pill.label + ", " + (if (pill.count == 1) "1 reaction" else "${pill.count} reactions") +
        (if (pill.mine) ", including yours" else "")
