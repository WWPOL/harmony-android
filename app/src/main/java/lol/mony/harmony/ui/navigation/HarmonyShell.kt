package lol.mony.harmony.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

/**
 * The signed-in app's frame: the spaces as navigation, and one content pane.
 *
 * The frame this replaced had five equal destinations -- Chats, Social, Files, Alerts, You -- in
 * a bar or a rail, and the servers were a strip *inside* Chats. That shape says the app is five
 * things used equally, which it is not: a person spends almost all of their time in one server,
 * moves between two or three, and visits the feed, the drive, the inbox and their account now
 * and then.
 *
 * So the frame's navigation is [SpaceRail]: Home and the servers, with their unread counts, at
 * every width and on every screen. Choosing a server is one tap *while a room is open in another
 * one*, which is the case the old shape made expensive -- an answer that arrives in a different
 * server used to cost leaving the room, opening the list and finding the strip.
 *
 * The four rare destinations live behind the rail's "More" control, in a sheet that is the
 * app's whole map: every server with its name and count, and the four places beside them.
 *
 * The layout follows the window, not the device:
 *
 * - **Compact** (a phone upright, under 600dp): the spaces are a bar at the bottom, where a
 *   thumb reaches. The content owns the whole top of the screen, including the app bar.
 * - **Medium and expanded** (a phone on its side, a foldable unfolded, a tablet): a rail at the
 *   side, where a pointer and a two-handed grip both find it and where a bottom bar would waste
 *   the height a wide window has most of. The rail lists every server by name.
 *
 * The insets are the frame's, once, for the whole content. The app draws edge to edge, so a
 * destination's first row starts at y=0 unless something pads it; a device screenshot showed the
 * old space strip's "Home" pill with the clock drawn over it, because the strip was the first row
 * and nothing had inset it. Padding each destination's first child is how an app acquires two
 * margins and a screen that forgets. The frame now pads the content box by the status bar, the
 * cutout and the horizontal edges, in both layouts, and each destination starts from a surface
 * that is already inside the safe area. A rail or a bar pads itself for the edges it owns.
 */
@Composable
fun HarmonyShell(
    /** Every space: Home and the servers, in the order the person should find them. */
    spaces: List<Space>,
    /** The space whose content is showing; null while a rare destination is on screen. */
    currentSpace: Space?,
    onSpace: (Space) -> Unit,
    onAddServer: () -> Unit,
    onMore: () -> Unit,
    /**
     * The area the system bars and a display cutout occupy. Defaults to the real safe drawing
     * area; a test passes a synthetic one so the layout can be asserted at a known size without
     * a device that has a notch.
     */
    safeArea: WindowInsets = WindowInsets.safeDrawing,
    /** True while a rare destination is on screen, so "More" shows that it is the current place. */
    moreCurrent: Boolean = false,
    /** The instance's base URL, so a server's icon resolves the way the rest of the app does. */
    baseUrl: String? = null,
    content: @Composable (NavigationLayout) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthDp = maxWidth.value.toInt()
        val layout = navigationLayoutFor(widthDp)
        // The content needs the same width to choose its own pane count; measuring it once here
        // is what keeps the frame and the content from disagreeing about the window.
        CompositionLocalProvider(LocalWindowWidth provides widthDp) {
            when (layout) {
                NavigationLayout.BottomBar -> Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.weight(1f).fillMaxWidth()
                            // The content is inset from the status bar, the cutout and the
                            // horizontal edges; the bar below deals with the navigation bar. This
                            // is the one place the top inset is applied, so a destination cannot
                            // forget it and two of them cannot pad it twice.
                            .windowInsetsPadding(
                                safeArea.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                                ),
                            ),
                    ) { content(layout) }
                    SpaceRail(
                        spaces = spaces,
                        layout = layout,
                        currentSpace = currentSpace,
                        onSpace = onSpace,
                        onAddServer = onAddServer,
                        onMore = onMore,
                        moreCurrent = moreCurrent,
                        baseUrl = baseUrl,
                        safeArea = safeArea,
                    )
                }
                NavigationLayout.Rail -> Row(Modifier.fillMaxSize()) {
                    SpaceRail(
                        spaces = spaces,
                        layout = layout,
                        currentSpace = currentSpace,
                        onSpace = onSpace,
                        onAddServer = onAddServer,
                        onMore = onMore,
                        moreCurrent = moreCurrent,
                        baseUrl = baseUrl,
                        safeArea = safeArea,
                    )
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
 * The four rare destinations, as the "More" sheet lists them.
 *
 * They are rows with a glyph, a name and a line saying what is inside, not a row of tabs: the
 * sheet is read once and tapped once, and a name alone ("Files") does not say whether the thing
 * behind it is the instance's drive or a file picker.
 */
fun moreDestinations(): List<Destination> = Destination.entries
