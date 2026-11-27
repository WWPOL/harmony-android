package lol.mony.harmony.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import lol.mony.harmony.data.model.NotificationDestination
import kotlinx.coroutines.launch
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.navigation.Destination
import lol.mony.harmony.ui.navigation.HarmonyShell
import lol.mony.harmony.ui.navigation.MoreSheet
import lol.mony.harmony.ui.navigation.Space
import lol.mony.harmony.ui.navigation.isCurrentSpace
import lol.mony.harmony.ui.screens.chat.ChatsDestination
import lol.mony.harmony.ui.screens.drive.DriveScreen
import lol.mony.harmony.ui.screens.notifications.NotificationsScreen
import lol.mony.harmony.ui.screens.social.SocialHomeScreen
import lol.mony.harmony.ui.screens.you.YouScreen
import lol.mony.harmony.ui.theme.VisualThemeSettings

/**
 * The signed-in app: the spaces as navigation, one content surface, and the app's map behind
 * "More".
 *
 * What this function owns is the *choice* -- which space is current, which rare destination is
 * on top of it, and what a tapped notification means -- and it deliberately owns nothing else.
 * Every screen below it draws its own app bar and its own panes, so a destination can be as
 * different as Chats (a channel list beside a room) and Files (a folder tree) without the frame
 * knowing what either one looks like.
 *
 * The current *space* survives rotation, because it is what the app is mostly about; the open
 * room does not have to, because it lives in [AppState] and is restored by the destination.
 */
@Composable
fun SignedInShell(
    state: AppState,
    appearance: VisualThemeSettings,
    onAppearanceChange: (VisualThemeSettings, Boolean) -> Unit,
    onSignOut: () -> Unit,
    dynamicColor: Boolean = false,
    onDynamicColorChange: (Boolean) -> Unit = {},
) {
    // The rare destination on top of the conversation, or null while the conversation is what
    // is showing. Stored by name so it survives rotation.
    var away by rememberSaveable { mutableStateOf<String?>(null) }
    val destination = Destination.ofName(away)
    var more by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val servers by state.serversByMembership.collectAsState()
    val channelsByServer by state.channelsByServer.collectAsState()
    val unread by state.unread.collectAsState()
    val selectedServerId by state.selectedServerId.collectAsState()
    val showingDms by state.showingDms.collectAsState()
    val openChannelId by state.openChannelId.collectAsState()
    val openConversationId by state.openConversationId.collectAsState()
    // Whether a room is open, which is what the bar stands down for on a phone.
    val roomOpen = openChannelId != null || openConversationId != null
    val signedIn = state.auth.state.collectAsState().value as? AuthState.SignedIn
    val baseUrl = signedIn?.endpoint?.baseUrl

    // The spaces are the server list, with the counts the rail draws. Home is first and always
    // present: it is where the direct messages live, and its absence would be surprising.
    val spaces = listOf(
        Space(
            id = null,
            name = "Home",
            badge = unread.conversations.values.sum(),
            mentions = 0,
        ),
    ) + servers.map { entry ->
        val channelIds = channelsByServer[entry.server.id].orEmpty().map { it.id }
        Space(
            id = entry.server.id,
            name = entry.server.name,
            icon = entry.server.icon,
            badge = unread.serverTotal(channelIds),
            mentions = unread.mentionsIn(channelIds),
        )
    }
    val currentSpace = spaces.firstOrNull { isCurrentSpace(it, selectedServerId, showingDms) }

    // The frame owns the server read: it is the only place that needs the whole list, and a
    // screen that forgot to load it would show an empty rail rather than an error.
    val revision by state.serversRevision.collectAsState()
    var restored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(signedIn?.profile?.id, revision) {
        if (signedIn == null) return@LaunchedEffect
        if (state.serversByMembership.value.isEmpty() || revision > 0) state.loadServers()
    }

    // Open where the person left off. A server is the thing they were reading, and an app that
    // opened on Home every launch made them find it again; the id is checked against the list
    // that came back, so a server they have left cannot be reopened.
    LaunchedEffect(signedIn?.profile?.id, servers.size) {
        if (restored || signedIn == null || servers.isEmpty()) return@LaunchedEffect
        restored = true
        val remembered = runCatching { state.sessionStore.lastSpace() }.getOrNull() ?: return@LaunchedEffect
        if (remembered.isEmpty()) return@LaunchedEffect
        if (servers.none { it.server.id == remembered }) return@LaunchedEffect
        state.selectedServerId.value = remembered
        state.showingDms.value = false
    }

    // Choosing a space is choosing a conversation: the rare destination, if one is open, is
    // closed, and the open room goes with it. That is the one-tap hop the frame exists for.
    val chooseSpace: (Space) -> Unit = { space ->
        away = null
        state.openChannelId.value = null
        state.openConversationId.value = null
        if (space.isHome) {
            state.selectedServerId.value = null
            state.showingDms.value = true
        } else {
            state.selectedServerId.value = space.id
            state.showingDms.value = false
        }
        scope.launch { runCatching { state.sessionStore.saveLastSpace(space.id) } }
    }

    // A tapped notification (or a deep link) lands here as a destination. It is resolved once,
    // then cleared, so backing out of the room does not immediately reopen it.
    val pending by state.pendingDestination.collectAsState()
    LaunchedEffect(pending) {
        val wanted = pending ?: return@LaunchedEffect
        state.pendingDestination.value = null
        when (wanted) {
            is NotificationDestination.Conversation -> {
                away = null
                state.showingDms.value = true
                state.selectedServerId.value = null
                state.openChannelId.value = null
                state.openConversationId.value = wanted.conversationId
                state.focusMessageId.value = wanted.messageId
            }
            is NotificationDestination.Channel -> {
                away = null
                state.showingDms.value = false
                wanted.serverId?.let { state.selectedServerId.value = it }
                state.openConversationId.value = null
                state.openChannelId.value = wanted.channelId
                state.focusMessageId.value = wanted.messageId
            }
            is NotificationDestination.Thread -> {
                away = null
                state.showingDms.value = false
                wanted.serverId?.let { state.selectedServerId.value = it }
                state.openConversationId.value = null
                wanted.channelId?.let { state.openChannelId.value = it }
                state.openThreadId.value = wanted.threadId
                state.focusMessageId.value = wanted.messageId
            }
            NotificationDestination.FollowRequests -> away = Destination.Social.name
            NotificationDestination.Security -> away = Destination.You.name
            is NotificationDestination.Post -> away = Destination.Social.name
            is NotificationDestination.Profile -> away = null
            NotificationDestination.Chats, is NotificationDestination.Unavailable -> away = null
        }
    }

    HarmonyShell(
        spaces = spaces,
        currentSpace = if (destination == null) currentSpace else null,
        onSpace = chooseSpace,
        onAddServer = { state.showCreateServer.value = true },
        onMore = { more = true },
        moreCurrent = destination != null || more,
        // A room is not a place to switch spaces from: the bar hides while one is open on a
        // phone and comes back with the list. A wide window keeps its rail, which is beside the
        // content rather than under it.
        hideBar = destination == null && roomOpen,
        baseUrl = baseUrl,
    ) { layout ->
        // Back leaves a rare destination for the conversation before it leaves the app, which is
        // what a person who tapped a row in the sheet expects. It is the frame's rule, not each
        // page's: a page that also handles back (Files climbing a folder, Social returning to
        // the feed) sees it first, because its own handler is inside the content.
        BackHandler(enabled = destination != null) { away = null }
        when (destination) {
            null -> ChatsDestination(state, appearance, layout)
            Destination.Social -> SocialHomeScreen(state)
            Destination.Files -> DriveScreen(state, onBack = { away = null })
            Destination.Alerts -> NotificationsScreen(state)
            Destination.You -> YouScreen(
                state, appearance, onAppearanceChange, onSignOut,
                dynamicColor = dynamicColor,
                onDynamicColorChange = onDynamicColorChange,
            )
        }
    }

    if (more) {
        MoreSheet(
            spaces = spaces,
            currentSpace = if (destination == null) currentSpace else null,
            currentDestination = destination,
            onSpace = chooseSpace,
            onDestination = { away = it.name },
            onAddServer = { state.showCreateServer.value = true },
            onDismiss = { more = false },
        )
    }
}

/** The signed-in content at its natural size; used by tests that do not exercise the frame. */
@Composable
internal fun SignedInContent(state: AppState, appearance: VisualThemeSettings) {
    ChatsDestination(state, appearance, lol.mony.harmony.ui.navigation.NavigationLayout.BottomBar)
}
