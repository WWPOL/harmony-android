package lol.mony.harmony.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.Bot
import lol.mony.harmony.data.model.Profile
import lol.mony.harmony.data.model.Reaction
import lol.mony.harmony.data.model.ReactionActor
import lol.mony.harmony.data.model.reactionActors

/**
 * The people who reacted with one emoji, as named rows.
 *
 * This is the surface the report was about: every row read "Someone". The name comes from
 * [ReactionActor], which reads the three shapes a reaction stores its reactor in -- a local
 * profile, a `bots` row, or a bridged Discord person in the row's own metadata -- instead of
 * reading `user_id` alone and putting a placeholder where the name belongs.
 *
 * A plain [Column] rather than a lazy one: an instance refuses more than twenty reactions on a
 * message, so the list is short, and a lazy list inside a sheet's column would make the sheet
 * fill the screen instead of wrapping the people it names.
 *
 * A row with a profile opens that person, the same tap a message author's picture has; a bot and
 * a bridged person have no local profile and are not tappable.
 *
 * @param baseUrl the instance a relative avatar path resolves against.
 */
@Composable
fun ReactorList(
    reactions: List<Reaction>,
    people: Map<String, Profile>,
    bots: Map<String, Bot>,
    myId: String?,
    baseUrl: String?,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actors = reactionActors(reactions, people, bots, myId)
    if (actors.isEmpty()) {
        Text("Nobody has reacted with this yet.", modifier = modifier, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(modifier.testTag("reactor-list")) {
        for (actor in actors) ReactorRow(actor, baseUrl, onOpenProfile)
    }
}

/** One reactor: picture, name, handle, and whether it is a bot, a bridged person or this account. */
@Composable
private fun ReactorRow(actor: ReactionActor, baseUrl: String?, onOpenProfile: (String) -> Unit) {
    val profileId = actor.profileId
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (profileId != null) {
                    Modifier.combinedClickable(onClick = { onOpenProfile(profileId) })
                } else {
                    Modifier
                },
            )
            .testTag("reactor-" + actor.key)
            .padding(vertical = 6.dp),
    ) {
        Avatar(actor.name, actor.avatarPath, size = 32.dp, baseUrl = baseUrl)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(actor.name, style = MaterialTheme.typography.bodyLarge)
                if (actor.isBot) {
                    Text("BOT", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (actor.isBridged) {
                    Text("bridged", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (actor.isYou) {
                    Text("you", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            actor.handle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
