package lol.mony.harmony.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.components.ServerAvatar
import lol.mony.harmony.ui.theme.HarmonyShape
import lol.mony.harmony.ui.theme.HarmonySize
import lol.mony.harmony.ui.theme.LocalHarmonyColors
import lol.mony.harmony.ui.theme.LocalMotion

/**
 * The app's primary navigation: the spaces, and a way into everything else.
 *
 * This is the redesign's one big claim. A person's day is spent in one server and, at most, a
 * few others; the feed, the drive, the inbox and the account are places they visit. The app
 * used to spend half its navigation on those four and hide the servers in a strip *inside* one
 * destination, so switching server meant opening the list, finding the strip and scrolling it.
 *
 * Here the servers are the navigation. Choosing one is one tap from anywhere -- including from
 * inside a room in a different server, which is the case that matters: an answer that arrives
 * while you are reading somewhere else is one tap away, not five.
 *
 * Two shapes, chosen by the window:
 *
 * - **Compact** (a phone): a bar at the bottom, holding Home, the person's most active servers
 *   and a "More" control. It scrolls when the server list is longer than the bar, and every item
 *   is a full 48dp target.
 * - **Medium and expanded**: a rail at the side with *every* server, each with its name, so a
 *   wide window shows the whole list at once instead of a row of faces that scrolls.
 *
 * The selection is drawn as an Expressive shape *morph*, not a highlight box: the avatar's
 * corners go from a squircle to a rounded square as it becomes current, which is Material 3
 * Expressive's own selected-item language and reads at a glance without a second colour.
 *
 * "More" is not an overflow for the sake of one: the four rare destinations and the whole server
 * list live behind it, so the sheet is the app's map rather than a junk drawer.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SpaceRail(
    spaces: List<Space>,
    layout: NavigationLayout,
    currentSpace: Space?,
    /** Whether a room is open, which decides whether the space is *tapped* or *returned to*. */
    onSpace: (Space) -> Unit,
    onAddServer: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    /** True while a rare destination is on screen, so "More" says it is the current place. */
    moreCurrent: Boolean = false,
    /** The base URL a server icon is resolved against. */
    baseUrl: String? = null,
    safeArea: WindowInsets = WindowInsets.safeDrawing,
) {
    when (layout) {
        NavigationLayout.BottomBar -> SpaceBar(
            spaces = spaces,
            currentSpace = currentSpace,
            onSpace = onSpace,
            onMore = onMore,
            moreCurrent = moreCurrent,
            baseUrl = baseUrl,
            modifier = modifier,
            safeArea = safeArea,
        )
        NavigationLayout.Rail -> SpaceColumn(
            spaces = spaces,
            currentSpace = currentSpace,
            onSpace = onSpace,
            onAddServer = onAddServer,
            onMore = onMore,
            moreCurrent = moreCurrent,
            baseUrl = baseUrl,
            modifier = modifier,
            safeArea = safeArea,
        )
    }
}

/**
 * The phone's bar: Home, the active servers, and More.
 *
 * It holds a fixed number of items rather than scrolling. A bar that scrolls hides its last items
 * behind a gesture nobody makes on a navigation bar, and a bar of fixed items shrinks every one of
 * them until the face is unreadable; so [barCapacity] decides how many fit at this width,
 * [barSpaces] decides *which* ones by attention, and the rest are the "More" sheet's -- which
 * lists every server by name, so nothing is unreachable.
 */
@Composable
private fun SpaceBar(
    spaces: List<Space>,
    currentSpace: Space?,
    onSpace: (Space) -> Unit,
    /**
     * The map's "Add a server" row is the phone's only one: a bar that spent an item on a
     * once-a-month action would cost a server, and adding is a thing a person does from the
     * sheet where the whole list already is.
     */
    onMore: () -> Unit,
    moreCurrent: Boolean,
    baseUrl: String?,
    modifier: Modifier,
    safeArea: WindowInsets,
) {
    val harmony = LocalHarmonyColors.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The bar's items share the width it has, and `barCapacity` decides how many servers it
        // can hold beside Home and "More" -- the arithmetic that is one item too optimistic if a
        // composable does it inline, which is how "More" once landed off the screen.
        val capacity = barCapacity(maxWidth.value.toInt(), spaces.count { !it.isHome })
        val shown = barSpaces(spaces, limit = capacity.servers)
        val perItem = capacity.itemWidth.dp
        // A raised bar, not a flat strip: the spaces sit on their own surface, which is what
        // makes the bar read as the app's frame rather than as the bottom of the content.
        Surface(
            color = harmony.surfaceSidebar,
            modifier = modifier.fillMaxWidth(),
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.fillMaxWidth()
                .windowInsetsPadding(safeArea.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(harmony.border))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)
                        .testTag("space-bar"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Home is pinned rather than scrolled: it is the one space whose absence would
                    // be surprising, and it is the space a person returns to when nothing else is
                    // asking.
                    SpaceTab(
                        space = Space.Home,
                        current = currentSpace?.isHome == true,
                        baseUrl = baseUrl,
                        width = perItem,
                        onClick = { onSpace(Space.Home) },
                    )
                    // A plain row, not a scrolling one: the capacity decided above means the bar
                    // always has room for exactly what it chose to show. The servers it does not
                    // hold are the "More" sheet's, which lists every one by name.
                    shown.filterNot { it.isHome }.forEach { space ->
                        SpaceTab(
                            space = space,
                            current = space.id != null && currentSpace?.id == space.id,
                            baseUrl = baseUrl,
                            width = perItem,
                            onClick = { onSpace(space) },
                        )
                    }
                    // The gap takes whatever the clamped item width left over, so "More" sits at
                    // the far end where it is always in the same place.
                    Spacer(Modifier.weight(1f))
                    MoreTab(current = moreCurrent, width = perItem, onClick = onMore)
                }
            }
        }
    }
}

/**
 * One space in the phone's bar: a face, its name under it, and its unread count on the face.
 *
 * The name is the point. A bar of bare monograms is a memory test -- a person has to remember
 * that "ES" is the server they are looking for -- and there is room for one short line. The
 * name is truncated with an ellipsis rather than wrapped, so every item in the bar is the same
 * height and the bar does not reflow when a server is renamed.
 *
 * The selected item is drawn as Material 3 Expressive draws one: the face's corners morph to a
 * fuller, rounder square and the label goes bold, with no second colour band. A person who has
 * asked for less motion gets the end state without the travel.
 */
@Composable
private fun SpaceTab(
    space: Space,
    current: Boolean,
    baseUrl: String?,
    width: Dp,
    onClick: () -> Unit,
) {
    val motion = LocalMotion.current
    val shape = selectedShape(current, motion.still)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier
            .testTag(spaceTag(space))
            .semantics { contentDescription = space.spoken() }
            .width(width)
            .heightIn(min = HarmonySize.minTouchTarget),
    ) {
        Column(
            Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box {
                SpaceFace(space = space, baseUrl = baseUrl, shape = shape, size = 26.dp, current = current)
                if (space.badge > 0) {
                    CountBadge(
                        count = space.badge,
                        urgent = space.mentions > 0,
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp),
                    )
                }
            }
            Text(
                space.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (current || space.badge > 0) FontWeight.SemiBold else FontWeight.Medium,
                color = if (current) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The wide window's rail: every space, by name, plus More.
 *
 * The names are the point. A 76dp column of monograms is a phone pattern that a tablet does not
 * need to repeat; with the width to spell them, a server is found by reading rather than by
 * remembering which initials were which.
 */
@Composable
private fun SpaceColumn(
    spaces: List<Space>,
    currentSpace: Space?,
    onSpace: (Space) -> Unit,
    onAddServer: () -> Unit,
    onMore: () -> Unit,
    moreCurrent: Boolean,
    baseUrl: String?,
    modifier: Modifier,
    safeArea: WindowInsets,
) {
    val harmony = LocalHarmonyColors.current
    Surface(color = harmony.surfaceSidebar, modifier = modifier.fillMaxHeight()) {
        Column(
            Modifier
                .width(spaceRailWidth)
                .fillMaxHeight()
                .windowInsetsPadding(safeArea.only(WindowInsetsSides.Start + WindowInsetsSides.Top + WindowInsetsSides.Bottom))
                .padding(vertical = 12.dp)
                .testTag("space-rail"),
        ) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(spaces, key = { it.id ?: "home" }) { space ->
                    SpaceRow(
                        space = space,
                        current = if (space.isHome) currentSpace?.isHome == true else currentSpace?.id == space.id,
                        baseUrl = baseUrl,
                        onClick = { onSpace(space) },
                    )
                }
                item(key = "add") {
                    RailAction(
                        label = "Add a server",
                        tag = "space-add-server",
                        onClick = onAddServer,
                    ) { tint ->
                        Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp), tint = tint)
                    }
                }
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).height(1.dp).fillMaxWidth()
                .background(harmony.border))
            RailAction(
                label = "More",
                tag = "space-more",
                current = moreCurrent,
                onClick = onMore,
            ) { tint ->
                Icon(Icons.Outlined.Apps, contentDescription = null, Modifier.size(20.dp), tint = tint)
            }
        }
    }
}

/**
 * An unread count, as the rail and the bar draw it.
 *
 * Smaller than Material's [Badge] on purpose: a badge sits on a 26dp face, and the stock badge's
 * 16dp floor is most of the face. The count is still a count -- it is not a dot -- because "how
 * much is waiting" is the thing a person compares across spaces.
 */
@Composable
private fun CountBadge(count: Int, urgent: Boolean, modifier: Modifier = Modifier) {
    Surface(
        color = if (urgent) LocalHarmonyColors.current.badge else MaterialTheme.colorScheme.primary,
        contentColor = if (urgent) LocalHarmonyColors.current.onBadge else MaterialTheme.colorScheme.onPrimary,
        shape = HarmonyShape.pill,
        modifier = modifier.heightIn(min = 15.dp),
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** The width the wide rail takes: enough for a name and a badge, not a pane. */
val spaceRailWidth: Dp = 248.dp

/**
 * One space as a row in the wide rail: face, name, unread.
 *
 * A row rather than a tile because there is width to use: a person scanning a tablet's rail is
 * looking for a *name*, and an unread count is a number they compare across rows.
 */
@Composable
private fun SpaceRow(
    space: Space,
    current: Boolean,
    baseUrl: String?,
    onClick: () -> Unit,
) {
    val motion = LocalMotion.current
    val shape = selectedShape(current, motion.still)
    val container by animateColorAsState(
        targetValue = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = motion.effectsSpec(220),
        label = "space-row",
    )
    val content = if (current) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .heightIn(min = HarmonySize.minTouchTarget)
            .testTag(spaceTag(space))
            .semantics { contentDescription = space.spoken() },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SpaceFace(space = space, baseUrl = baseUrl, shape = shape, size = 28.dp, current = current)
            Text(
                space.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (current || space.badge > 0) FontWeight.SemiBold else FontWeight.Normal,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (space.badge > 0) CountBadge(count = space.badge, urgent = space.mentions > 0)
        }
    }
}

/**
 * The face of a space: its picture, its monogram, or Home's own glyph.
 *
 * The shape is passed in rather than decided here, so the bar and the rail agree about what a
 * current space looks like and only the sizes differ. Home is a *filled* tile rather than a
 * glyph inside a tinted circle: the circle inside a selected tile drew as two concentric rings,
 * which read as a radio button in a bar of server faces.
 */
@Composable
private fun SpaceFace(
    space: Space,
    baseUrl: String?,
    shape: RoundedCornerShape,
    size: Dp,
    current: Boolean,
) {
    if (space.isHome) {
        val container = if (current) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh
        Box(
            Modifier.size(size).background(container, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Home,
                contentDescription = null,
                Modifier.size(size * 0.6f),
                tint = if (current) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    ServerAvatar(
        name = space.name,
        url = space.icon,
        selected = current,
        size = size,
        baseUrl = baseUrl,
        shape = shape,
    )
}

/**
 * A space's corner radius, as the selection morphs it.
 *
 * Expressive's selected-item language is a shape change, not a colour change: the face goes
 * from a soft squircle to a rounder, fuller form. A person who has asked for less motion gets
 * the end state without the travel -- [still] picks the radius, and the caller's animation is a
 * zero-duration tween.
 */
@Composable
private fun selectedShape(current: Boolean, still: Boolean): RoundedCornerShape {
    val radius by animateDpAsState(
        targetValue = if (current) 12.dp else 20.dp,
        animationSpec = LocalMotion.current.spatialSpec(if (still) 0 else 240),
        label = "space-shape",
    )
    return RoundedCornerShape(radius)
}

/** The "More" control: every space and the four rare destinations, behind one tap. */
@Composable
private fun MoreTab(current: Boolean, width: Dp, onClick: () -> Unit) {
    val container by animateColorAsState(
        targetValue = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = LocalMotion.current.effectsSpec(220),
        label = "more-tab",
    )
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = container,
        modifier = Modifier
            .width(width)
            .heightIn(min = HarmonySize.minTouchTarget)
            .testTag("space-more")
            .semantics { contentDescription = "More places" },
    ) {
        Column(
            Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Apps,
                    contentDescription = null,
                    Modifier.size(22.dp),
                    tint = if (current) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "More",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = if (current) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** A row action in the wide rail: an icon, a name, and the same target height as a space. */
@Composable
private fun RailAction(
    label: String,
    tag: String,
    current: Boolean = false,
    onClick: () -> Unit,
    glyph: @Composable (Color) -> Unit,
) {
    val container by animateColorAsState(
        targetValue = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = LocalMotion.current.effectsSpec(220),
        label = "rail-action",
    )
    val tint = if (current) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .heightIn(min = HarmonySize.minTouchTarget)
            .testTag(tag)
            .semantics { contentDescription = label },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { glyph(tint) }
            Text(label, style = MaterialTheme.typography.titleSmall, color = tint, maxLines = 1)
        }
    }
}

/** The tag a space answers to: stable per server, and readable in a failure message. */
internal fun spaceTag(space: Space): String =
    if (space.isHome) "space-home" else "space-server-${space.name}"
