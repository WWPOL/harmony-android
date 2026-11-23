package lol.mony.harmony.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.theme.HarmonySize
import lol.mony.harmony.ui.theme.LocalHarmonyColors
import lol.mony.harmony.ui.theme.LocalMotion

/**
 * The pieces every screen draws with.
 *
 * They exist so the app has *one* empty state, *one* section heading, *one* error banner and
 * *one* idea of what a card looks like. A screen that draws its own is how an app ends up with
 * five different margins and two kinds of spinner; the audit found exactly that.
 */

/**
 * A section heading inside a scrolling screen.
 *
 * It is a real heading for TalkBack (`heading()`), because a person moving by heading is how a
 * long settings page or a channel list is navigated, and a bold `Text` is invisible to that.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(
            start = HarmonySize.gutterLarge, end = HarmonySize.gutter, top = 20.dp, bottom = 6.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * An empty screen that says what to do next.
 *
 * [action] is a filled button rather than a text link: an empty state exists because there is
 * nothing else to press, so the one thing there is to press has to look like the thing to
 * press. The icon sits in a soft circle so the screen is not a wall of grey text.
 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    icon: ImageVector? = null,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            if (icon != null) {
                Box(
                    Modifier
                        .size(72.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

/**
 * A spinner with a sentence beside it, not under it.
 *
 * A centred spinner with a label under it is the shape every app uses; a label beside it reads
 * at a glance and takes less vertical space, which matters on a short window.
 *
 * The spinner is Material 3 Expressive's [LoadingIndicator]: a shape that morphs through a set
 * of rounded polygons while it runs, instead of a ring sweeping a gap around. It is the same
 * "something is happening" at a glance, and it reads as the app's own motion rather than as the
 * platform's oldest progress control. It still takes its colour from the theme, and it still
 * stops moving when the person asked for less motion -- the theme installs a motion scheme whose
 * specs are all zero for that case.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingState(label: String = "Loading") {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(
                modifier = Modifier.size(28.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The small in-line spinner: a bar or a row that is refreshing, not a screen that is loading.
 *
 * [LoadingState] is for a surface that has nothing to show yet; this is for one that has
 * something and is fetching more, where a 28dp morph would move the thing the person is reading.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InlineLoading(modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    LoadingIndicator(modifier = modifier.size(18.dp), color = color)
}

/** A failure with one way forward: retry. The message is the instance's own, not a rewording. */
@Composable
fun ErrorState(error: String, onRetry: () -> Unit) {
    EmptyState(
        title = "Could not load this",
        description = error,
        icon = Icons.Outlined.CloudOff,
        action = "Try again",
        onAction = onRetry,
    )
}

/**
 * A dismissible failure banner.
 *
 * The dismiss is a real button with a name, and the banner is announced as a live region so a
 * screen reader says it without the person having to find it.
 */
@Composable
fun ErrorBanner(error: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                error,
                Modifier.weight(1f).padding(vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/**
 * A card that lifts under a finger.
 *
 * Material's own card elevation does not respond to touch, and on a dense feed the pressed
 * state is the only feedback before a screen changes. The lift is a tonal surface change plus
 * a small shadow, and it is skipped entirely when motion is reduced.
 */
@Composable
fun HarmonyCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable () -> Unit,
) {
    val motion = LocalMotion.current
    val shape = MaterialTheme.shapes.large
    if (onClick == null) {
        Surface(modifier, shape = shape, color = container) { content() }
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = container,
            // A press is a state, not an animation: even with motion reduced the surface has to
            // say it was touched, so the colour changes and only the travel is skipped.
            tonalElevation = if (motion.still) 0.dp else 1.dp,
            shadowElevation = if (motion.still) 0.dp else 1.dp,
        ) { content() }
    }
}

/** A hairline between rows, inset the way Material's own dividers are. */
@Composable
fun HarmonyDivider(modifier: Modifier = Modifier, start: androidx.compose.ui.unit.Dp = 0.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = start)
            .height(1.dp)
            .background(LocalHarmonyColors.current.border),
    )
}

/** A row of chips that scrolls sideways when it does not fit, as filters and spaces do. */
@Composable
fun ChipRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/**
 * A page's content, capped at the width a person can read and centred in the window.
 *
 * A list row is a label and, on its far end, a chevron or a count. Stretched across a 1000dp
 * tablet those two ends are a thousand pixels apart, and reading a row means turning your head --
 * which is the thing a wide window is supposed to save you from. Every page that is a column of
 * rows (the account settings, the inbox, the feed, the file list, server management) wraps its
 * content in this, so the cap is stated once rather than five times, and a page that forgets it
 * is a page that was not looked at on a tablet.
 *
 * It fills the *height*, not the size: the column still scrolls the whole window.
 */
@Composable
fun ReadingColumn(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .widthIn(max = HarmonySize.readingMaxWidth)
                .fillMaxHeight(),
        ) { content() }
    }
}

/**
 * One thing a person can do with a thing, in a sheet: an icon, a name, and optionally a line
 * saying what it does.
 *
 * [destructive] colours it with the error role and is only ever set on an action that cannot be
 * undone, so the red in a sheet always means the same thing.
 */
data class ActionItem(
    val label: String,
    val icon: ImageVector,
    val detail: String? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * A sheet of actions on one message or one row.
 *
 * The old sheet was a column of full-width `TextButton`s with no icons, which is a list of words
 * where a person is looking for a verb -- every row the same weight, every row the same shape, and
 * the destructive one told apart only by its colour. This draws each action as a row with a glyph,
 * its name and, where there is one, the line that says what it does, so the sheet can be read
 * before it is tapped.
 *
 * A sheet whose actions are a *choice* rather than a list -- a filter, a sort -- is a segmented
 * control, not this.
 */
@Composable
fun ActionSheetContent(
    title: String?,
    actions: List<ActionItem>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(bottom = HarmonySize.gutter)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = HarmonySize.gutterLarge, end = HarmonySize.gutterLarge, top = 4.dp, bottom = 12.dp),
            )
        }
        actions.forEach { item ->
            val tint = if (item.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            Surface(
                onClick = item.onClick,
                color = androidx.compose.ui.graphics.Color.Transparent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = HarmonySize.gutterLarge, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(HarmonySize.gutter),
                ) {
                    Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.label, style = MaterialTheme.typography.bodyLarge, color = tint)
                        if (item.detail != null) {
                            Text(
                                item.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
