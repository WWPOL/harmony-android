package lol.mony.harmony.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity

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
 *
 * The keyboard is measured here too, and it is not the frame's chrome. `WindowInsets.safeDrawing`
 * folds the IME into the same four sides as the status bar and the navigation bar, so a frame
 * that pads itself with it while a field has focus pads itself for a keyboard that is already
 * covering it. That is what the bottom bar did: its bottom padding became the keyboard's height,
 * the bar grew to ~300dp of which every pixel was behind the keyboard, and the room above it lost
 * that height -- so the composer was squeezed up under the app bar with a blank strip below it,
 * and a reply banner or a connection banner squeezed the message list out of sight entirely.
 *
 * The keyboard is therefore taken out of the frame's own insets and given to the content, once,
 * while it is up; the bottom bar stands down for it, because a bar behind the keyboard is not
 * navigation -- it cannot be seen and cannot be tapped. A destination never pads for the IME
 * itself, which is what kept this bug alive: the room padded for the keyboard and the frame
 * padded for it again, and neither knew about the other.
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
    /**
     * The area the keyboard occupies. Defaults to the real IME; a test passes a synthetic one so
     * the composer's place above a keyboard can be asserted without an input method. It is
     * subtracted from [safeArea] rather than added to it, so a caller that leaves this empty
     * (and a test that does) gets exactly the behaviour a phone without a keyboard has.
     */
    ime: WindowInsets = WindowInsets.ime,
    /** True while a rare destination is on screen, so "More" shows that it is the current place. */
    moreCurrent: Boolean = false,
    /** The instance's base URL, so a server's icon resolves the way the rest of the app does. */
    baseUrl: String? = null,
    content: @Composable (NavigationLayout) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthDp = maxWidth.value.toInt()
        val layout = navigationLayoutFor(widthDp)
        // The keyboard, read once. Reading the inset's own state here is what makes the bar leave
        // and the content resize when the keyboard animates in and out.
        val keyboard = ime.getBottom(LocalDensity.current) > 0
        // The bars and the cutout, with the keyboard's height taken out of them: while the
        // keyboard is up it covers the navigation bar, and the safe drawing area reports the
        // larger of the two.
        val bars = safeArea.exclude(ime)
        // What the content is inset by: the bars, plus the keyboard while it is up. The content
        // owns the bottom edge when the bar is not there to own it.
        val contentInsets = if (keyboard) bars.union(ime) else bars
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
                            // forget it and two of them cannot pad it twice. With the keyboard
                            // up, the bottom edge is the keyboard's and the content is inset by
                            // it here, so no destination has to know the keyboard exists.
                            .windowInsetsPadding(
                                if (keyboard) {
                                    contentInsets.only(
                                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal +
                                            WindowInsetsSides.Bottom,
                                    )
                                } else {
                                    bars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                                },
                            ),
                    ) { content(layout) }
                    // The bar stands down while the keyboard is up. It used to stay and pad
                    // itself by the keyboard, which drew a blank strip the height of the keyboard
                    // behind it and took that height from the room.
                    if (!keyboard) {
                        SpaceRail(
                            spaces = spaces,
                            layout = layout,
                            currentSpace = currentSpace,
                            onSpace = onSpace,
                            onAddServer = onAddServer,
                            onMore = onMore,
                            moreCurrent = moreCurrent,
                            baseUrl = baseUrl,
                            safeArea = bars,
                        )
                    }
                }
                NavigationLayout.Rail -> Row(Modifier.fillMaxSize()) {
                    // The rail is at the side, where the keyboard never reaches it, so it stays.
                    SpaceRail(
                        spaces = spaces,
                        layout = layout,
                        currentSpace = currentSpace,
                        onSpace = onSpace,
                        onAddServer = onAddServer,
                        onMore = onMore,
                        moreCurrent = moreCurrent,
                        baseUrl = baseUrl,
                        safeArea = bars,
                    )
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        // The rail already dealt with the start side and the top; the content is
                        // inset from the cutout on its own side, the status bar above it, the
                        // navigation bar -- or the keyboard -- below it.
                        Box(
                            Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(
                                    contentInsets.only(
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
