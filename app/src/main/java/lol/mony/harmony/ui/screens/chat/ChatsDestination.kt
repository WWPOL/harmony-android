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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.components.HarmonyDivider
import lol.mony.harmony.ui.navigation.LocalWindowWidth
import lol.mony.harmony.ui.navigation.NavigationLayout
import lol.mony.harmony.ui.navigation.showsSpaceColumnAt
import lol.mony.harmony.ui.navigation.showsTwoPanesAt
import lol.mony.harmony.ui.theme.HarmonySize
import lol.mony.harmony.ui.theme.LocalHarmonyColors
import lol.mony.harmony.ui.theme.LocalMotion
import lol.mony.harmony.ui.theme.VisualThemeSettings

/**
 * Chats, as an adaptive list-detail.
 *
 * This is the screen the redesign is mostly about. The old workspace was one column with three
 * mutually exclusive states: the DM list, a server's channel list, or a room. Opening a room
 * *replaced* the list, and getting back to another channel meant going back first; on a tablet
 * the whole width was spent on whichever one was current.
 *
 * Here the list and the room are two panes:
 *
 * - **Compact** (a phone): one pane at a time, which is the only honest thing to do with 400dp.
 *   Choosing a channel slides the room in over the list and the back gesture returns; the room's
 *   own back arrow says the same thing, so a person who does not know the gesture is not stuck.
 * - **Medium and expanded**: the list keeps its width and the room takes the rest, so choosing a
 *   channel is a selection rather than a navigation, and the unread channels stay visible while
 *   reading. That is what a wide window is for.
 *
 * The space switcher (Home and each server) is inside this destination, not in the app's
 * navigation: a server is *content* you are reading, not a place the app has.
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
    // Two decisions, not one. A rail means there is room for a list beside its detail; a third
    // pane (the space switcher as a column) needs an expanded window, or the room is squeezed to
    // a strip. See `showsSpaceColumnAt`.
    val twoPanes = showsTwoPanesAt(widthDp)
    val spaceColumn = showsSpaceColumnAt(widthDp)
    val spaceLayout = if (spaceColumn) NavigationLayout.Rail else NavigationLayout.BottomBar

    // Back closes the open room before it leaves the destination, on both layouts. On a wide
    // window the room is a pane, so closing it is still what back means.
    BackHandler(enabled = open != null) {
        state.openChannelId.value = null
        state.openConversationId.value = null
    }

    if (twoPanes) {
        Row(Modifier.fillMaxSize()) {
            if (spaceColumn) {
                // The switcher is a column here, so the whole server list is visible at once
                // and a server name is never truncated to fit a 76dp tile.
                SpaceSwitcher(state, spaceLayout, onSpaceChosen = {})
                HarmonyDivider(Modifier.fillMaxHeight().width(1.dp))
            }
            Surface(
                Modifier.width(HarmonySize.paneListWidth).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) { RoomList(state, appearance, switcherAbove = !spaceColumn, spaceLayout) }
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
        Box(Modifier.fillMaxSize()) {
            RoomList(state, appearance, switcherAbove = true, spaceLayout)
            // The room covers the list rather than replacing it, so returning is a reverse
            // animation of the same travel instead of a jump cut.
            AnimatedVisibility(
                visible = open != null,
                enter = if (motion.still) fadeIn() else slideInHorizontally { it } + fadeIn(),
                exit = if (motion.still) fadeOut() else slideOutHorizontally { it } + fadeOut(),
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RoomPane(state, channelId, conversationId, appearance, onBack = {
                        state.openChannelId.value = null
                        state.openConversationId.value = null
                    })
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
    switcherAbove: Boolean = false,
    spaceLayout: NavigationLayout = NavigationLayout.BottomBar,
) {
    val serverId by state.selectedServerId.collectAsState()
    val showingDms by state.showingDms.collectAsState()
    Column(Modifier.fillMaxSize().testTag("room-list")) {
        // The strip form sits above the list; the column form is a pane of its own and is drawn
        // by the destination instead. `spaceLayout` says which, so the two cannot disagree.
        if (switcherAbove) SpaceSwitcher(state, spaceLayout, onSpaceChosen = {})
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showingDms || serverId == null) {
                DirectMessages(state, Modifier.fillMaxSize())
            } else {
                ChannelList(state, serverId!!, Modifier.fillMaxSize())
            }
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
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
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
