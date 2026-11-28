package lol.mony.harmony.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The four places that are *not* the conversation the app is mostly for.
 *
 * The app used to present five equal destinations -- Chats, Social, Files, Alerts, You -- in its
 * primary navigation. That is the wrong shape for how the app is actually used: a person spends
 * nearly all of their time inside one server, reading and writing, and hops between two or three
 * servers they belong to. The feed, the drive, the inbox and the account are places they visit,
 * not places they live.
 *
 * So the primary navigation is the **spaces** (Home and the servers, see [Space]), and these
 * four are one control away -- a sheet with every space and these four behind it. Nothing is
 * lost: each is still a single tap from anywhere. What changes is that they no longer take half
 * the frame away from the thing the app is for.
 *
 * [Chats] is not here: it *is* the primary surface, not an item in it.
 */
enum class Destination(
    val label: String,
    val icon: ImageVector,
    /** The test tag the journeys and the shell tests address this destination by. */
    val tag: String,
    /** The line the "more" sheet puts under the name, so a row says what is inside it. */
    val supporting: String,
) {
    Social("Social", Icons.Outlined.Public, "nav-Social", "Your feed, profiles and lists"),
    Files("Files", Icons.Outlined.FolderOpen, "nav-Files", "Files you keep and share"),
    Alerts("Alerts", Icons.Outlined.Notifications, "nav-Alerts", "Mentions, replies and follows"),
    You("You", Icons.Outlined.PersonOutline, "nav-You", "Your profile and appearance"),
    ;

    companion object {
        /** The destination a stored name names, or [Social] when it names none of them. */
        fun ofName(name: String?): Destination? = entries.firstOrNull { it.name == name }
    }
}

/**
 * One place a person can be: their direct messages, or one server.
 *
 * This is the app's primary axis. A space is not a destination the frame switches to and away
 * from -- it is the *thing being read*, and the frame keeps every one of them a tap away, at
 * every width, including while a room is open. That is the whole redesign: a person who is
 * reading a channel and wants to answer a message in another server does not have to leave the
 * room they are in to get there.
 *
 * [id] is the server's id, or null for Home (the direct messages). A server's icon may be null,
 * in which case the space draws its monogram.
 *
 * The two counts are the two kinds of attention, and they are drawn differently on purpose:
 *
 * - [unread] is how many messages in this space are unread. It is drawn as the space's **name,
 *   brighter** -- see [lol.mony.harmony.ui.components.unreadNameColor] -- and never as a number.
 * - [pings] is how many of those are direct pings: a mention of this account in a channel, or an
 *   unread direct message in Home. That is the only thing the red badge is spent on.
 */
data class Space(
    val id: String?,
    val name: String,
    val icon: String? = null,
    val unread: Int = 0,
    val pings: Int = 0,
) {
    val isHome: Boolean get() = id == null

    /** Whether anything at all is waiting here, which is what brightens the name. */
    val hasUnread: Boolean get() = unread > 0

    /**
     * What the red badge says a screen reader, which is not just its number.
     *
     * A badge is a count of *people waiting*, and what a person is waiting for differs by space:
     * a mention in a server's channel, or an unread direct message in Home. The number alone
     * would make TalkBack read "3" beside "Design" and leave the reason out.
     */
    fun pingDescription(): String = if (isHome) {
        if (pings == 1) "1 unread direct message" else "$pings unread direct messages"
    } else {
        if (pings == 1) "1 mention" else "$pings mentions"
    }

    /** What TalkBack says instead of a bare number. */
    fun spoken(): String = when {
        pings > 0 && unread > pings -> "$name, $unread unread, $pings pinging you"
        pings > 0 -> "$name, $pings pinging you"
        unread == 1 -> "$name, 1 unread"
        unread > 1 -> "$name, $unread unread"
        else -> name
    }

    companion object {
        /** Home: the direct messages, which is a space like any other. */
        val Home = Space(id = null, name = "Home")
    }
}

/**
 * Where the primary navigation lives at a given window width.
 *
 * The breakpoints are Material's: a compact window (a phone held upright) gets a bar at the
 * bottom, because the thumb reaches the bottom and the top is where the content's own controls
 * go; a medium or expanded window (a phone on its side, a foldable unfolded, a tablet) gets a
 * rail at the side, where a pointer and a two-handed grip both find it and where a bottom bar
 * would waste the height a wide window has most of.
 */
enum class NavigationLayout { BottomBar, Rail }

fun navigationLayoutFor(widthDp: Int): NavigationLayout =
    if (widthDp >= 600) NavigationLayout.Rail else NavigationLayout.BottomBar

/**
 * Whether the app shows one column or a list beside its detail.
 *
 * At 600dp and up a channel list and the room chosen from it fit side by side, which is the
 * whole point of a wide window: choosing a channel must not replace the list you chose it from.
 * Below that, the room covers the list and back returns to it.
 */
fun showsTwoPanesAt(widthDp: Int): Boolean = widthDp >= 600

/**
 * The window's width, in dp, as the frame measured it.
 *
 * [HarmonyShell] measures the window once and provides it here, so a destination can choose its
 * own pane count without measuring again and without disagreeing with the frame about how wide
 * the window is. The default is a phone, which is what a composable drawn outside a shell -- a
 * preview, a test of one pane -- should assume.
 */
val LocalWindowWidth = compositionLocalOf { 400 }

/**
 * How many spaces the bottom bar holds beside Home and the "more" control.
 *
 * A phone's bar is the app's fastest surface, and a bar that scrolls is a bar whose last items
 * are never seen: five items at 360dp is about 68dp each, which is already the floor for a
 * touch target with a face on it. So the bar holds the person's three most recently used
 * servers -- which is the "few common servers" the app is built around -- and the sheet behind
 * "More" holds every one of them, with its name and its unread count.
 */
const val SPACES_IN_BAR = 3

/**
 * How many servers the phone's bar can hold at a given width, and how wide each item is.
 *
 * The bar has to hold Home, the servers, and "More" -- the control that reaches everything the
 * bar does not. A bar whose last item is pushed off the screen is a bar with a bug, and the
 * arithmetic is easy to get wrong by exactly one item, so it lives here as a rule a test can
 * pin rather than as a division inside a composable.
 *
 * The item width is what is left after the bar's own padding, divided by the number of items,
 * clamped to a range a face and a short name both fit in. When the clamp would make the items
 * too narrow to hold everything, the bar holds fewer servers -- "More" is not optional, because
 * it is the only way to the servers that did not fit.
 */
data class BarCapacity(val itemWidth: Int, val servers: Int)

fun barCapacity(widthDp: Int, serverCount: Int): BarCapacity {
    // The bar's horizontal padding (6dp each side) and one 2dp gap between items.
    val usable = (widthDp - 12).coerceAtLeast(0)
    fun itemsAt(width: Int) = usable / width
    // Start with the narrowest item: how many items fit at all?
    val mostItems = itemsAt(MIN_BAR_ITEM_DP).coerceAtLeast(2) // Home and More, at minimum
    val serversThatFit = (mostItems - 2).coerceIn(0, serverCount)
    val items = serversThatFit + 2
    val width = (usable / items.coerceAtLeast(1)).coerceIn(MIN_BAR_ITEM_DP, MAX_BAR_ITEM_DP)
    return BarCapacity(width, serversThatFit)
}

/** The narrowest one item in the phone's bar may get: a 26dp face and a truncated name. */
const val MIN_BAR_ITEM_DP = 52

/** The widest it gets before the extra space goes to the gap: a whole short server name. */
const val MAX_BAR_ITEM_DP = 88

/**
 * The spaces the bottom bar shows, most useful first.
 *
 * Ordering is the interesting part. Recency alone puts a server the person read once this
 * morning above the one with six unread messages, which is backwards: a space that is asking
 * for attention should be reachable without hunting for it. So the bar is ordered by
 * *attention* first -- pings, then any unread, then recency -- and the rest of the person's
 * servers are one tap away in the sheet.
 *
 * Home is always first and always present: it is where direct messages live, and it is the one
 * space whose absence would be surprising.
 */
fun barSpaces(spaces: List<Space>, limit: Int = SPACES_IN_BAR): List<Space> {
    val home = spaces.firstOrNull { it.isHome } ?: Space.Home
    val rest = spaces.filterNot { it.isHome }
        // `sortedByDescending` is stable, so spaces that tie keep the recency order they came in.
        .sortedByDescending { it.pings * 1_000_000 + it.unread * 1_000 }
    return listOf(home) + rest.take(limit.coerceAtLeast(0))
}

/**
 * The servers the sheet lists, in the order a person expects to find them.
 *
 * Every server is there, with unread ones first, because the sheet is where somebody looks for
 * the server they are *not* thinking about.
 */
fun sheetSpaces(spaces: List<Space>): List<Space> =
    spaces.filterNot { it.isHome }.sortedByDescending { it.pings * 1_000_000 + it.unread * 1_000 }

/**
 * Whether a space is the one being shown.
 *
 * [showingDms] is the app's own flag for Home; a server is current when the app is not showing
 * Home and this is the selected server.
 */
fun isCurrentSpace(space: Space, selectedServerId: String?, showingDms: Boolean): Boolean =
    if (space.isHome) showingDms || selectedServerId == null else !showingDms && space.id == selectedServerId
