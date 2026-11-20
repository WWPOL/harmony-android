package lol.mony.harmony.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.components.ServerAvatar
import lol.mony.harmony.ui.navigation.NavigationLayout
import lol.mony.harmony.ui.theme.HarmonyShape
import lol.mony.harmony.ui.theme.HarmonySize
import lol.mony.harmony.ui.theme.LocalHarmonyColors

/**
 * The spaces a person moves between: their direct messages, and each server they are in.
 *
 * This is the app's answer to the old left rail, which stacked the five destinations and the
 * server list in one 76dp column. Two things were wrong with that. The destinations and the
 * servers are different *kinds* of thing, so one control that showed both made every choice
 * cost the other. And a 76dp column of 56dp tiles is a phone pattern that a tablet wastes: the
 * space beside a wide window is where a channel list belongs, not where a row of avatars does.
 *
 * So the spaces are a strip here: horizontal on a phone, where it sits under the app bar and
 * scrolls sideways; a column on a wide window, where the whole list is visible at once. The
 * channel list and the room then take the rest of the width, side by side when there is room.
 *
 * [onSpaceChosen] is called after the state has been changed, so the shell can put the choice
 * where the person can see it. The strip itself does not know whether it is a row or a column.
 */
@Composable
fun SpaceSwitcher(
    state: AppState,
    layout: NavigationLayout,
    modifier: Modifier = Modifier,
    onSpaceChosen: () -> Unit = {},
) {
    val servers by state.serversByMembership.collectAsState()
    val selected = state.selectedServerId.collectAsState().value
    val showingDms = state.showingDms.collectAsState().value
    val unread by state.unread.collectAsState()
    val channelsByServer by state.channelsByServer.collectAsState()
    val baseUrl = state.baseUrl()
    val revision by state.serversRevision.collectAsState()

    // The switcher owns its own read: it is the only place that needs the whole server list,
    // and a screen that forgot to load it would show an empty switcher rather than an error.
    LaunchedEffect(state.auth.state.collectAsState().value, revision) {
        if (state.serversByMembership.value.isEmpty() || revision > 0) state.loadServers()
    }

    // Home is a space of its own: it is where the direct messages live, and it is what a
    // person comes back to when they are not reading a server.
    val homeUnread = unread.conversations.values.sum()

    val chooseHome: () -> Unit = {
        state.selectedServerId.value = null
        state.showingDms.value = true
        state.openChannelId.value = null
        state.openConversationId.value = null
        onSpaceChosen()
    }
    val chooseServer: (String) -> Unit = { id ->
        state.selectedServerId.value = id
        state.showingDms.value = false
        state.openChannelId.value = null
        state.openConversationId.value = null
        onSpaceChosen()
    }

    when (layout) {
        NavigationLayout.BottomBar -> LazyRow(
            modifier.fillMaxWidth().testTag("space-switcher"),
            contentPadding = PaddingValues(horizontal = HarmonySize.gutter, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "home") {
                SpaceChip(
                    label = "Home",
                    selected = showingDms || selected == null,
                    badge = homeUnread,
                    onClick = chooseHome,
                    testTag = "rail-home",
                ) { HomeGlyph(showingDms || selected == null) }
            }
            items(servers, key = { it.server.id }) { entry ->
                val channelIds = channelsByServer[entry.server.id].orEmpty().map { it.id }
                SpaceChip(
                    label = entry.server.name,
                    selected = !showingDms && selected == entry.server.id,
                    badge = unread.serverTotal(channelIds),
                    mentions = unread.mentionsIn(channelIds),
                    onClick = { chooseServer(entry.server.id) },
                    testTag = "rail-server-${entry.server.name}",
                ) {
                    ServerAvatar(
                        name = entry.server.name,
                        url = entry.server.icon,
                        selected = !showingDms && selected == entry.server.id,
                        size = 28.dp,
                        baseUrl = baseUrl,
                    )
                }
            }
            item(key = "add") {
                AddSpaceChip(onClick = { state.showCreateServer.value = true })
            }
        }
        NavigationLayout.Rail -> Column(
            modifier
                .widthIn(min = 96.dp, max = 260.dp)
                .fillMaxHeight()
                .background(LocalHarmonyColors.current.surfaceSidebar)
                .padding(vertical = 12.dp)
                .testTag("space-switcher"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SpaceRow(
                label = "Home",
                selected = showingDms || selected == null,
                badge = homeUnread,
                onClick = chooseHome,
                testTag = "rail-home",
            ) { HomeGlyph(showingDms || selected == null) }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).height(1.dp)
                .fillMaxWidth().background(LocalHarmonyColors.current.border))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(servers, key = { it.server.id }) { entry ->
                    val channelIds = channelsByServer[entry.server.id].orEmpty().map { it.id }
                    SpaceRow(
                        label = entry.server.name,
                        selected = !showingDms && selected == entry.server.id,
                        badge = unread.serverTotal(channelIds),
                        mentions = unread.mentionsIn(channelIds),
                        onClick = { chooseServer(entry.server.id) },
                        testTag = "rail-server-${entry.server.name}",
                    ) {
                        ServerAvatar(
                            name = entry.server.name,
                            url = entry.server.icon,
                            selected = !showingDms && selected == entry.server.id,
                            size = 28.dp,
                            baseUrl = baseUrl,
                        )
                    }
                }
                item(key = "add") {
                    AddSpaceRow(onClick = { state.showCreateServer.value = true })
                }
            }
        }
    }
}

@Composable
private fun HomeGlyph(selected: Boolean) {
    Icon(
        Icons.Filled.Home,
        contentDescription = null,
        Modifier.size(20.dp),
        tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A space as a chip on a phone: icon, name, unread count. The pill is the touch target. */
@Composable
private fun SpaceChip(
    label: String,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    testTag: String,
    mentions: Int = 0,
    glyph: @Composable () -> Unit,
) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(
        onClick = onClick,
        shape = HarmonyShape.pill,
        color = container,
        modifier = Modifier
            .testTag(testTag)
            .semantics { contentDescription = if (badge > 0) "$label, $badge unread" else label }
            .heightIn(min = HarmonySize.minTouchTarget),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            glyph()
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            if (badge > 0) {
                Badge(containerColor = if (mentions > 0) LocalHarmonyColors.current.badge
                else MaterialTheme.colorScheme.primary) {
                    Text(if (badge > 99) "99+" else "$badge")
                }
            }
        }
    }
}

/** A space as a row on a wide window: the whole name is visible, so no truncation is needed. */
@Composable
private fun SpaceRow(
    label: String,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    testTag: String,
    mentions: Int = 0,
    glyph: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .heightIn(min = HarmonySize.minTouchTarget)
            .testTag(testTag)
            .semantics { contentDescription = if (badge > 0) "$label, $badge unread" else label },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            glyph()
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (badge > 0) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (badge > 0) {
                Badge(containerColor = if (mentions > 0) LocalHarmonyColors.current.badge
                else MaterialTheme.colorScheme.primary) {
                    Text(if (badge > 99) "99+" else "$badge")
                }
            }
        }
    }
}

@Composable
private fun AddSpaceChip(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = HarmonyShape.pill,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.heightIn(min = HarmonySize.minTouchTarget).testTag("rail-add-server"),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Add", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddSpaceRow(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            .heightIn(min = HarmonySize.minTouchTarget).testTag("rail-add-server"),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Add a server", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The app bar of the room list: what space this is, and what can be done to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsAppBar(
    title: String,
    subtitle: String?,
    onRefresh: () -> Unit,
    onNewMessage: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) {
                    Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        actions = {
            actions()
            // Refresh and "new message" are frequent enough to keep; everything else is one
            // tap away instead of five icons competing with the title.
            if (onNewMessage != null) {
                IconButton(onClick = onNewMessage, modifier = Modifier.testTag("new-message")) {
                    Icon(Icons.Outlined.Edit, "New message")
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Refresh") },
                        leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                        onClick = { menu = false; onRefresh() },
                    )
                }
            }
        },
    )
}

private fun AppState.baseUrl(): String? =
    (auth.state.value as? lol.mony.harmony.data.session.AuthState.SignedIn)?.endpoint?.baseUrl
