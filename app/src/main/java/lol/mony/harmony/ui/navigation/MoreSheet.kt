package lol.mony.harmony.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import lol.mony.harmony.ui.components.HarmonyDivider
import lol.mony.harmony.ui.components.PingBadge
import lol.mony.harmony.ui.components.ServerAvatar
import lol.mony.harmony.ui.components.unreadNameColor
import lol.mony.harmony.ui.components.unreadNameWeight
import lol.mony.harmony.ui.theme.HarmonySize

/**
 * The app's map: every space, and the four places that are not the conversation.
 *
 * This is what the "More" control opens. It is deliberately *not* an overflow menu. The app has
 * two kinds of navigation and one of them -- the servers -- is used constantly, while the other
 * is visited now and then; a sheet is the honest way to show both at once without pretending
 * they are the same weight.
 *
 * It is also the only place a person can *find* a server they are not thinking about: the phone's
 * bar holds three, and this lists every one by name with its unread count, which is what makes a
 * bar of three safe.
 *
 * Choosing a space closes the sheet and shows that space's channels. Choosing a destination
 * closes it and shows that page. Both are one tap from here, and the sheet is one tap from
 * anywhere in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSheet(
    spaces: List<Space>,
    currentSpace: Space?,
    currentDestination: Destination?,
    onSpace: (Space) -> Unit,
    onDestination: (Destination) -> Unit,
    onAddServer: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close() { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = HarmonySize.gutter)) {
            Text(
                "Places",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(start = HarmonySize.gutterLarge, end = HarmonySize.gutterLarge, bottom = 4.dp)
                    .semantics { heading() },
            )
            Text(
                "Your servers, and everything else in Harmony.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = HarmonySize.gutterLarge, end = HarmonySize.gutterLarge, bottom = 8.dp),
            )
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(spaces, key = { it.id ?: "home" }) { space ->
                    val current = if (space.isHome) currentSpace?.isHome == true
                    else currentSpace?.id == space.id
                    SheetRow(
                        title = space.name,
                        supporting = when {
                            // Home's pings are its unread direct messages, so a count of
                            // "pinging you" would be a count of the same messages twice.
                            space.isHome && space.unread == 1 -> "1 unread direct message"
                            space.isHome && space.unread > 1 -> "${space.unread} unread direct messages"
                            space.isHome -> "Your direct messages"
                            space.pings > 0 && space.unread > space.pings ->
                                "${space.pings} pinging you, ${space.unread} unread"
                            space.pings == 1 -> "1 pinging you"
                            space.pings > 1 -> "${space.pings} pinging you"
                            space.unread > 0 -> "${space.unread} unread"
                            else -> null
                        },
                        pings = space.pings,
                        unread = space.hasUnread,
                        current = current,
                        tag = "sheet-" + spaceTag(space),
                        leading = {
                            if (space.isHome) {
                                HomeGlyph(current)
                            } else {
                                ServerAvatar(space.name, space.icon, size = 28.dp, selected = current)
                            }
                        },
                        onClick = { onSpace(space); close() },
                    )
                }
                item(key = "add") {
                    SheetRow(
                        title = "Add a server",
                        supporting = "Create one, or join with an invite",
                        current = false,
                        tag = "sheet-add-server",
                        leading = {
                            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = { onAddServer(); close() },
                    )
                }
                item(key = "elsewhere") {
                    HarmonyDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        "Elsewhere",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = HarmonySize.gutterLarge, top = 4.dp, bottom = 4.dp)
                            .semantics { heading() },
                    )
                }
                items(Destination.entries, key = { it.name }) { destination ->
                    SheetRow(
                        title = destination.label,
                        supporting = destination.supporting,
                        current = currentDestination == destination,
                        tag = "sheet-" + destination.tag,
                        leading = {
                            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                Icon(destination.icon, contentDescription = null, Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = { onDestination(destination); close() },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeGlyph(current: Boolean) {
    Box(
        Modifier.size(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Home,
            contentDescription = null,
            Modifier.size(20.dp),
            tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One row of the map: what it is, what is inside, and what is asking for attention.
 *
 * Attention is drawn the way every other list in the app draws it: a row with unread messages is
 * written in the bright foreground, and the red badge is reserved for a ping. The supporting line
 * still says "6 unread" in words, because this sheet is where somebody decides *which* server to
 * open and a count in a sentence is the useful form of that -- it is the badge that is not a
 * count of messages.
 *
 * The current place is a tinted row rather than a tick, because the row is also the thing that
 * closes the sheet -- a tick would say "this is chosen" and a person would still wonder whether
 * tapping it did anything.
 */
@Composable
private fun SheetRow(
    title: String,
    supporting: String?,
    current: Boolean,
    tag: String,
    pings: Int = 0,
    unread: Boolean = false,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .heightIn(min = HarmonySize.minTouchTarget)
            .testTag(tag)
            .semantics {
                contentDescription = buildString {
                    append(title)
                    if (supporting != null) append(", ").append(supporting)
                    if (current) append(", current")
                }
            },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            leading()
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = unreadNameWeight(unread || current),
                    color = if (current) MaterialTheme.colorScheme.onSecondaryContainer
                    else unreadNameColor(unread, MaterialTheme.colorScheme.onSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (pings > 0) PingBadge(count = pings)
        }
    }
}
