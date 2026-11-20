package lol.mony.harmony.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.theme.HarmonySize
import lol.mony.harmony.ui.theme.LocalHarmonyColors

/**
 * The signed-in app's frame: a navigation bar on a phone, a rail on anything wider.
 *
 * The old shell put five destinations *and* the server list into one 76dp column that collapsed
 * to a menu button, which meant that choosing a server hid the destinations and choosing a
 * destination hid the servers. Two different things were sharing one control, and every choice
 * cost the person the other one.
 *
 * Here the destinations are the frame and never move; the server and channel list is a pane
 * *inside* the Chats destination, where it belongs, and it is what collapses when a room opens.
 * That is the whole information-architecture change: navigation is the frame, content is the
 * pane, and neither can hide the other.
 *
 * The layout follows the window, not the device:
 *
 * - **Compact** (a phone upright, under 600dp): a [NavigationBar] at the bottom, where a
 *   thumb reaches. The content owns the whole top of the screen, including the app bar.
 * - **Medium and expanded** (a phone on its side, a foldable unfolded, a tablet): a
 *   [NavigationRail] at the side, where a pointer and a two-handed grip both find it and where a
 *   bottom bar would waste the height a wide window has most of.
 *
 * The rail and the bar are Material's own components, so selection, ripple, focus and the
 * TalkBack role come from the platform instead of being re-implemented; what this file owns is
 * *when* each is used and what each item says.
 */
@Composable
fun HarmonyShell(
    destination: Destination,
    onDestination: (Destination) -> Unit,
    /**
     * Unread counts per destination. Chats and Alerts are the two that can carry one; the rest
     * have no meaning for a badge and draw none.
     */
    chats: Int = 0,
    mentions: Int = 0,
    /**
     * The area the system bars and a display cutout occupy. Defaults to the real safe drawing
     * area; a test passes a synthetic one so the layout can be asserted at a known size without
     * a device that has a notch.
     */
    safeArea: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable (NavigationLayout) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthDp = maxWidth.value.toInt()
        val layout = navigationLayoutFor(widthDp)
        // The destinations need the same width to choose their own pane count; measuring it once
        // here is what keeps the frame and the content from disagreeing about the window.
        CompositionLocalProvider(LocalWindowWidth provides widthDp) {
            when (layout) {
                NavigationLayout.BottomBar -> Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.weight(1f).fillMaxWidth()
                            // The content is inset from the status bar, the cutout and the horizontal
                            // edges; the bar below deals with the navigation bar. This is the one
                            // place the top inset is applied, so a destination cannot forget it and
                            // two of them cannot pad it twice.
                            .windowInsetsPadding(
                                safeArea.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                                ),
                            ),
                    ) { content(layout) }
                    DestinationBar(destination, onDestination, chats, mentions, safeArea)
                }
                NavigationLayout.Rail -> Row(Modifier.fillMaxSize()) {
                    DestinationRail(destination, onDestination, chats, mentions, safeArea)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        // The rail already dealt with the start side and the top; the content is
                        // inset from the cutout on its own side, the status bar above it and the
                        // navigation bar below it.
                        Box(
                            Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(
                                    safeArea.only(
                                        WindowInsetsSides.End + WindowInsetsSides.Top + WindowInsetsSides.Bottom,
                                    ),
                                ),
                        ) { content(layout) }
                    }
                }
            }
        }
    }
}

/**
 * The bottom bar.
 *
 * It carries the navigation bar inset itself, so the app draws edge to edge and the bar's own
 * background reaches the bottom of the screen while its items stay above the gesture area. That
 * is the difference between a bar that looks like it belongs to the device and one that floats
 * above a black strip.
 */
@Composable
private fun DestinationBar(
    destination: Destination,
    onDestination: (Destination) -> Unit,
    chats: Int,
    mentions: Int,
    safeArea: WindowInsets,
) {
    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(safeArea.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .testTag("nav-bar"),
        containerColor = LocalHarmonyColors.current.surfaceSidebar,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Destination.entries.forEach { item ->
            val badge = badgeFor(item, chats, mentions)
            NavigationBarItem(
                selected = destination == item,
                onClick = { onDestination(item) },
                icon = { DestinationIcon(item, badge, selected = destination == item) },
                label = { Text(item.label) },
                modifier = Modifier
                    .testTag(item.tag)
                    .semantics { contentDescription = badge?.spoken(item.label) ?: item.label },
            )
        }
    }
}

/**
 * The rail.
 *
 * It is inside the status bar and the cutout inset, because the app draws edge to edge: a rail
 * that started at y=0 put its first destination under the clock and the camera hole, which is a
 * bug that was reported from a phone and is now a test.
 */
@Composable
private fun DestinationRail(
    destination: Destination,
    onDestination: (Destination) -> Unit,
    chats: Int,
    mentions: Int,
    safeArea: WindowInsets,
) {
    NavigationRail(
        modifier = Modifier
            .fillMaxHeight()
            .windowInsetsPadding(safeArea.only(WindowInsetsSides.Start + WindowInsetsSides.Top + WindowInsetsSides.Bottom))
            .testTag("rail"),
        containerColor = LocalHarmonyColors.current.surfaceSidebar,
        header = { Box(Modifier.height(12.dp)) },
    ) {
        Destination.entries.forEach { item ->
            val badge = badgeFor(item, chats, mentions)
            NavigationRailItem(
                selected = destination == item,
                onClick = { onDestination(item) },
                icon = { DestinationIcon(item, badge, selected = destination == item) },
                label = { Text(item.label) },
                modifier = Modifier
                    .testTag(item.tag)
                    .semantics { contentDescription = badge?.spoken(item.label) ?: item.label },
            )
        }
    }
}

@Composable
private fun DestinationIcon(item: Destination, badge: DestinationBadge?, selected: Boolean) {
    val icon = Icon(item.icon, contentDescription = null, Modifier.size(24.dp))
    if (badge == null) {
        icon
    } else {
        BadgedBox(badge = {
            Badge(containerColor = if (badge.mentions > 0) LocalHarmonyColors.current.badge
                else MaterialTheme.colorScheme.onSurfaceVariant) {
                Text(badge.text)
            }
        }) { icon }
    }
}

/** The badge a destination shows, or null when it has nothing to say. */
internal fun badgeFor(item: Destination, chats: Int, mentions: Int): DestinationBadge? = when (item) {
    Destination.Chats -> if (chats > 0) DestinationBadge(chats, mentions) else null
    Destination.Alerts -> if (mentions > 0) DestinationBadge(mentions, mentions) else null
    else -> null
}

/** The icons, so [Destination] can be a plain enum in a file that draws nothing. */
internal val Destination.icon: androidx.compose.ui.graphics.vector.ImageVector
    get() = when (this) {
        Destination.Chats -> Icons.Outlined.ChatBubbleOutline
        Destination.Social -> Icons.Outlined.Public
        Destination.Files -> Icons.Outlined.FolderOpen
        Destination.Alerts -> Icons.Outlined.Notifications
        Destination.You -> Icons.Outlined.PersonOutline
    }
