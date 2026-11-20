package lol.mony.harmony.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The five places the app has, as one list.
 *
 * They are *places*, not screens: each one owns a stack of its own (a server, a channel, a
 * post, a folder), so the navigation bar is stable while the thing on top of it changes. That
 * is the difference from the old shell, which mixed the places and the server list into one
 * column and had to hide it to make room for the thing it had just selected.
 *
 * The order is the order of a day: what is happening now, then the wider instance, then what
 * you own, then what happened to you, then you.
 */
enum class Destination(
    val label: String,
    val icon: ImageVector,
    /** The test tag the journeys and the shell tests address this destination by. */
    val tag: String,
) {
    Chats("Chats", Icons.Outlined.ChatBubbleOutline, "nav-Chats"),
    Social("Social", Icons.Outlined.Public, "nav-Social"),
    Files("Files", Icons.Outlined.FolderOpen, "nav-Files"),
    Alerts("Alerts", Icons.Outlined.Notifications, "nav-Alerts"),
    You("You", Icons.Outlined.PersonOutline, "nav-You"),
    ;

    companion object {
        fun ofName(name: String?): Destination =
            entries.firstOrNull { it.name == name } ?: Chats
    }
}

/** A destination's unread badge, as the navigation bar draws it. */
data class DestinationBadge(val count: Int, val mentions: Int = 0) {
    /** What the badge says: a cap, so a large count cannot widen the bar. */
    val text: String get() = if (count > 99) "99+" else count.toString()

    /** What TalkBack says instead of the bare number. */
    fun spoken(label: String): String = when {
        mentions > 0 && count > mentions -> "$label, $count unread, $mentions mentioning you"
        mentions > 0 -> "$label, $mentions mentioning you"
        count == 1 -> "$label, 1 unread"
        else -> "$label, $count unread"
    }
}

/**
 * Where the navigation lives at a given window width.
 *
 * The breakpoints are Material's: a compact window (a phone held upright) gets a bottom bar,
 * because the thumb reaches the bottom and the top is where the content's own controls go. A
 * medium or expanded window (a phone on its side, a foldable unfolded, a tablet) gets a rail at
 * the side, where a pointer and a two-handed grip both find it and where a bottom bar would
 * waste the height a wide window has most of.
 */
enum class NavigationLayout { BottomBar, Rail }

fun navigationLayoutFor(widthDp: Int): NavigationLayout =
    if (widthDp >= 600) NavigationLayout.Rail else NavigationLayout.BottomBar

/**
 * Whether the app shows one column or a list beside its detail.
 *
 * At 600dp and up a list and the thing chosen from it fit side by side, which is the whole
 * point of a wide window: choosing a channel must not replace the list you chose it from. Below
 * that, the detail covers the list and back returns to it.
 */
fun showsTwoPanesAt(widthDp: Int): Boolean = widthDp >= 600

/**
 * Whether Chats has room for the space switcher *beside* the list as well.
 *
 * This is the difference between two panes and three, and it needs its own breakpoint. The
 * switcher column is up to 260dp and the list is 340dp, so a 600dp window that drew all three
 * would leave the room a strip of pixels -- which is what a 640dp capture showed before this
 * rule existed. Material's expanded class starts at 840dp, and that is where a third pane
 * actually pays for itself.
 *
 * Below it the space switcher is the horizontal strip above the list, the same one a phone
 * uses: one more row, no loss of width for reading.
 */
fun showsSpaceColumnAt(widthDp: Int): Boolean = widthDp >= 840

/**
 * The window's width, in dp, as the frame measured it.
 *
 * [HarmonyShell] measures the window once and provides it here, so a destination can choose its
 * own pane count without measuring again and without disagreeing with the frame about how wide
 * the window is. The default is a phone, which is what a composable drawn outside a shell -- a
 * preview, a test of one pane -- should assume.
 */
val LocalWindowWidth = compositionLocalOf { 400 }
