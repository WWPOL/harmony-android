package lol.mony.harmony.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.components.HarmonyDivider
import lol.mony.harmony.ui.navigation.LocalWindowWidth
import lol.mony.harmony.ui.navigation.NavigationLayout
import lol.mony.harmony.ui.navigation.showsTwoPanesAt
import lol.mony.harmony.ui.theme.HarmonySize
import lol.mony.harmony.ui.theme.LocalHarmonyColors
import lol.mony.harmony.ui.theme.LocalMotion
import lol.mony.harmony.ui.theme.VisualThemeSettings

/**
 * Chats: the conversation, as an adaptive list-detail.
 *
 * This is the surface the app is for, and it is now the *whole* of the primary surface rather
 * than one of five equal destinations. The servers are the frame's navigation
 * ([lol.mony.harmony.ui.navigation.SpaceRail]); what is left here is the two panes a person
 * actually moves between -- a list of rooms and the room they are reading.
 *
 * - **Compact** (a phone): one pane at a time, which is the only honest thing to do with 400dp.
 *   Choosing a channel slides the room in over the list and the back gesture returns; the room's
 *   own back arrow says the same thing, and so does a drag out of the start edge
 *   ([SwipeToChannelList]), so a person who does not know either gesture is not stuck.
 *   Switching server is still one tap, because the bar at the bottom is the frame and it does
 *   not go away when a room opens.
 * - **Medium and expanded**: the list keeps its width and the room takes the rest, so choosing a
 *   channel is a selection rather than a navigation, and the unread channels stay visible while
 *   reading. That is what a wide window is for.
 *
 * The space switcher that used to sit *inside* this destination is gone. It was a second
 * navigation inside the content, competing with the frame's own; the frame now carries the
 * spaces, so there is exactly one place a server is chosen and it is the same place at every
 * width and on every screen.
 */
@Composable
fun ChatsDestination(
    state: AppState,
    appearance: VisualThemeSettings,
    layout: NavigationLayout,
    widthDp: Int = LocalWindowWidth.current,
) {
    val channelId by state.openChannelId.collectAsState()
    val conversationId by state.openConversationId.collectAsState()
    val motion = LocalMotion.current
    val open = channelId ?: conversationId
    val twoPanes = showsTwoPanesAt(widthDp)

    // Back closes the open room before it leaves the destination, on both layouts. On a wide
    // window the room is a pane, so closing it is still what back means.
    BackHandler(enabled = open != null) {
        state.openChannelId.value = null
        state.openConversationId.value = null
    }

    if (twoPanes) {
        Row(Modifier.fillMaxSize()) {
            Surface(
                Modifier.width(HarmonySize.paneListWidth).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) { RoomList(state, appearance) }
            HarmonyDivider(Modifier.fillMaxHeight().width(1.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (open == null) {
                    NoRoomChosen()
                } else {
                    RoomPane(state, channelId, conversationId, appearance, onBack = {
                        state.openChannelId.value = null
                        state.openConversationId.value = null
                    })
                }
            }
        }
    } else {
        val closeRoom = {
            state.openChannelId.value = null
            state.openConversationId.value = null
        }
        Box(Modifier.fillMaxSize()) {
            RoomList(state, appearance)
            // The room covers the list rather than replacing it, so returning is a reverse
            // animation of the same travel instead of a jump cut.
            AnimatedVisibility(
                visible = open != null,
                enter = if (motion.still) fadeIn() else slideInHorizontally { it } + fadeIn(),
                exit = if (motion.still) fadeOut() else slideOutHorizontally { it } + fadeOut(),
            ) {
                // A drag out of the start edge is the same move as the back arrow, made with a
                // thumb: it slides the room off the list of the server it belongs to. Only the
                // one-pane layout has a list hidden behind the room to reveal, which is why the
                // gesture lives here and not around both layouts.
                SwipeToChannelList(onClose = closeRoom) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        RoomPane(state, channelId, conversationId, appearance, onBack = closeRoom)
                    }
                }
            }
        }
    }
}

/**
 * The room itself, with the thread it may have opened.
 *
 * It is one composable for both layouts: a room does not draw differently in a pane, it only
 * has less width, and the message list already wraps.
 */
@Composable
private fun RoomPane(
    state: AppState,
    channelId: String?,
    conversationId: String?,
    appearance: VisualThemeSettings,
    onBack: () -> Unit,
) {
    val id = channelId ?: conversationId ?: return
    RoomScreen(
        state = state,
        roomId = id,
        direct = channelId == null,
        appearance = appearance,
        onBack = onBack,
    )
}

/** The list of rooms: direct messages, or the chosen server's channels. */
@Composable
private fun RoomList(
    state: AppState,
    appearance: VisualThemeSettings,
) {
    val serverId by state.selectedServerId.collectAsState()
    val showingDms by state.showingDms.collectAsState()
    Box(Modifier.fillMaxSize().testTag("room-list")) {
        if (showingDms || serverId == null) {
            DirectMessages(state, Modifier.fillMaxSize())
        } else {
            ChannelList(state, serverId!!, Modifier.fillMaxSize())
        }
    }
}

/**
 * What a wide window shows before a room is chosen.
 *
 * It is not an error and not an empty state: the list beside it is full of rooms, so this pane
 * only has to say what to do. The tint is the app's own sidebar tone, which makes the pane read
 * as part of the frame rather than as a failure.
 */
@Composable
private fun NoRoomChosen() {
    Surface(Modifier.fillMaxSize(), color = LocalHarmonyColors.current.surfaceSidebar) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Text(
                    "Choose a conversation",
                    style = MaterialTheme.typography.titleMedium,
                )
                androidx.compose.material3.Text(
                    "Your messages open here, beside the list.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
