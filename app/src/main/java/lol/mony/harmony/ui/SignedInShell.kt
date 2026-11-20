package lol.mony.harmony.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import lol.mony.harmony.data.model.NotificationDestination
import lol.mony.harmony.ui.navigation.Destination
import lol.mony.harmony.ui.navigation.HarmonyShell
import lol.mony.harmony.ui.screens.chat.ChatsDestination
import lol.mony.harmony.ui.screens.drive.DriveScreen
import lol.mony.harmony.ui.screens.notifications.NotificationsScreen
import lol.mony.harmony.ui.screens.social.SocialHomeScreen
import lol.mony.harmony.ui.screens.you.YouScreen
import lol.mony.harmony.ui.theme.VisualThemeSettings

/**
 * The signed-in app: five destinations in an adaptive frame, each owning its own content.
 *
 * The frame is [HarmonyShell]'s: a bottom bar on a phone and a rail on anything wider. What this
 * function owns is the *choice* - which destination is current, and what a tapped notification
 * means - and it deliberately owns nothing else. Every screen below it draws its own app bar and
 * its own panes, so a destination can be as different as Chats (list beside room) and Files
 * (a folder tree) without the shell knowing what either one looks like.
 *
 * The choice survives rotation; which room was open does not have to, because it lives in
 * [AppState] and is restored by the destination itself.
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
    var tab by rememberSaveable { mutableStateOf(Destination.Chats.name) }
    val destination = Destination.ofName(tab)

    // A tapped notification (or a deep link) lands here as a destination. It is resolved once,
    // then cleared, so backing out of the room does not immediately reopen it.
    val pending by state.pendingDestination.collectAsState()
    LaunchedEffect(pending) {
        val wanted = pending ?: return@LaunchedEffect
        state.pendingDestination.value = null
        when (wanted) {
            is NotificationDestination.Conversation -> {
                tab = Destination.Chats.name
                state.showingDms.value = true
                state.openChannelId.value = null
                state.openConversationId.value = wanted.conversationId
                state.focusMessageId.value = wanted.messageId
            }
            is NotificationDestination.Channel -> {
                tab = Destination.Chats.name
                state.showingDms.value = false
                wanted.serverId?.let { state.selectedServerId.value = it }
                state.openConversationId.value = null
                state.openChannelId.value = wanted.channelId
                state.focusMessageId.value = wanted.messageId
            }
            is NotificationDestination.Thread -> {
                tab = Destination.Chats.name
                state.showingDms.value = false
                wanted.serverId?.let { state.selectedServerId.value = it }
                state.openConversationId.value = null
                wanted.channelId?.let { state.openChannelId.value = it }
                state.openThreadId.value = wanted.threadId
                state.focusMessageId.value = wanted.messageId
            }
            NotificationDestination.FollowRequests -> tab = Destination.Social.name
            NotificationDestination.Security -> tab = Destination.You.name
            is NotificationDestination.Post -> tab = Destination.Social.name
            is NotificationDestination.Profile -> tab = Destination.Chats.name
            NotificationDestination.Chats, is NotificationDestination.Unavailable ->
                tab = Destination.Chats.name
        }
    }

    val unread by state.unread.collectAsState()
    val chats = unread.conversations.values.sum() + unread.channels.values.sum()
    val mentions = unread.mentions.values.sum()

    HarmonyShell(
        destination = destination,
        onDestination = { tab = it.name },
        chats = chats,
        mentions = mentions,
    ) { layout ->
        when (destination) {
            Destination.Chats -> ChatsDestination(state, appearance, layout)
            Destination.Social -> SocialHomeScreen(state)
            Destination.Files -> DriveScreen(state, onBack = { tab = Destination.You.name })
            Destination.Alerts -> NotificationsScreen(state)
            Destination.You -> YouScreen(
                state, appearance, onAppearanceChange, onSignOut,
                dynamicColor = dynamicColor,
                onDynamicColorChange = onDynamicColorChange,
            )
        }
    }
}

/** The signed-in content at its natural size; used by tests that do not exercise the frame. */
@Composable
internal fun SignedInContent(state: AppState, appearance: VisualThemeSettings) {
    ChatsDestination(state, appearance, lol.mony.harmony.ui.navigation.NavigationLayout.BottomBar)
}
