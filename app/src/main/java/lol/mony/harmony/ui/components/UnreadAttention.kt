package lol.mony.harmony.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.theme.HarmonyShape
import lol.mony.harmony.ui.theme.LocalHarmonyColors

/**
 * How this app says "there is something for you", in two strengths.
 *
 * The two are deliberately not the same picture, because they are not the same news:
 *
 * - **Unread** -- a room has messages this account has not read. It is drawn as the room's
 *   *name*, brighter and heavier than a name with nothing behind it: in the dark look it is the
 *   near-white `onSurface` against the dim `onSurfaceVariant`, and in the light look it is the
 *   full-strength ink against the muted one. No number, no badge, no colour. A number here was
 *   the mistake this file exists to undo: a count of messages is a quantity nobody acts on, and a
 *   rail of red counts is a rail with no signal left in it.
 * - **A ping** -- somebody said this account's name, or sent it a direct message. That is one
 *   person asking one person for an answer, and it gets the red badge and its count. Red is
 *   spent on nothing else, which is what makes it worth looking at.
 *
 * Both surfaces are here, in one file, so a list that draws an unread count cannot invent a
 * third way of drawing it: [UnreadName] for the name, [PingBadge] for the count.
 */

/**
 * The colour a name is drawn in: [unread] brightens it, everything else leaves it alone.
 *
 * [muted] is what a quiet name looks like on the surface being drawn -- `onSurfaceVariant` in the
 * rail and the channel column, and the same role under a tinted selected row. Passing it in
 * rather than assuming it is what lets a selected row keep its own foreground while an unread
 * neighbour is still visibly brighter than a read one.
 */
@Composable
fun unreadNameColor(unread: Boolean, muted: Color): Color =
    if (unread) MaterialTheme.colorScheme.onSurface else muted

/**
 * The weight an unread name is drawn at; a read name keeps the row's own weight.
 *
 * [read] is the weight the row already uses for a quiet name, because the bar's labels are
 * Medium where the rail's are Normal, and a rule that hard-coded one of them would change the
 * quiet rows of the other surface.
 */
fun unreadNameWeight(unread: Boolean, read: FontWeight = FontWeight.Normal): FontWeight =
    if (unread) FontWeight.SemiBold else read

/**
 * A ping, as the rail, the bar, the sheet and the two room lists draw it.
 *
 * A red pill with the count, because a ping is a *number of people* waiting on an answer rather
 * than a quantity of text: two mentions are two messages somebody expects a reply to. The count
 * caps at `99+` so the pill cannot grow wider than the face it sits on.
 *
 * [size] is the pill's minimum height. It is a parameter because the same badge sits on a 26dp
 * face in the phone's bar and beside a full-width row in the channel column, and the stock
 * Material badge's 16dp floor is most of the first and lost in the second.
 */
@Composable
fun PingBadge(
    count: Int,
    modifier: Modifier = Modifier,
    minHeight: Dp = 15.dp,
    /** What a screen reader says; the number alone does not say what it counts. */
    description: String = if (count == 1) "1 ping" else "$count pings",
) {
    val harmony = LocalHarmonyColors.current
    Surface(
        color = harmony.badge,
        contentColor = harmony.onBadge,
        shape = HarmonyShape.pill,
        modifier = modifier
            .heightIn(min = minHeight)
            .testTag("ping-badge")
            .semantics { contentDescription = description },
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
