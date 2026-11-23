package lol.mony.harmony.ui.screens.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** How far a row has to travel before letting go counts as a reply. */
private val ReplyThreshold = 64.dp

/**
 * A row that replies when it is dragged away from the screen's start edge.
 *
 * The direction is the one thing here that has to be right, and it was wrong: the reply used to
 * travel *towards* the end of the screen, which is the direction a navigation drawer opens in.
 * Two gestures cannot own one direction, and the drawer's is the older and the more expected
 * one, so the reply now travels the other way -- leftwards on a left-to-right screen.
 * [LocalLayoutDirection] decides the sign rather than a constant, so a mirrored layout gets a
 * mirrored gesture and the feedback moves with it.
 *
 * The drag is deliberately horizontal-only (`detectHorizontalDragGestures`), so the vertical
 * scroll of the list is never stolen from it. The row slides with the finger while the reply
 * arrow fades in behind it, on the edge the row uncovers. The same reply is reachable from the
 * actions sheet and from a custom accessibility action, so a person who cannot perform a drag is
 * not locked out of it.
 *
 * [key] restarts the gesture state when the row it draws is replaced (a room's rows are keyed by
 * the instance's message id), and [modifier] is where a caller puts its own background and tag.
 */
@Composable
internal fun SwipeToReplyRow(
    key: Any,
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // How far along the gesture the finger has gone, in pixels: 0 until it moves in the reply
    // direction, and never past half the row, so a row cannot be dragged off its own line.
    var travel by remember(key) { mutableStateOf(0f) }
    val density = LocalDensity.current
    val thresholdPx = with(density) { ReplyThreshold.toPx() }
    // Away from the start edge: negative (leftwards) in a left-to-right layout, positive in a
    // right-to-left one. A drawer opens the other way.
    val awayFromStart = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    val animated by animateFloatAsState(travel, label = "reply-travel")
    val haptics = LocalHapticFeedback.current
    // The gesture block outlives a recomposition, so it must not keep the callback it was first
    // composed with: the row's `onReply` is a new lambda every time the room repaints.
    val reply by rememberUpdatedState(onReply)

    Box(modifier.fillMaxWidth()) {
        // The feedback sits under the row, at the edge the row uncovers. `CenterEnd` and
        // `padding(end = ...)` are themselves direction-aware, so a mirrored layout puts the
        // arrow on the other side without a second rule.
        if (travel > 0f) {
            Box(
                Modifier.matchParentSize().padding(end = 12.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = "Reply",
                    modifier = Modifier
                        .size(20.dp)
                        .alpha((travel / thresholdPx).coerceIn(0f, 1f))
                        .testTag("reply-feedback"),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = awayFromStart * animated }
                // The direction and the threshold are part of the key: a pointer handler is not
                // restarted by a recomposition, so a mirrored layout or a changed density would
                // otherwise keep reading the values it was first given.
                .pointerInput(key, awayFromStart, thresholdPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { travel = 0f },
                        onHorizontalDrag = { _, amount ->
                            travel = (travel + amount * awayFromStart)
                                .coerceIn(0f, size.width * 0.5f)
                        },
                        onDragEnd = {
                            if (travel >= thresholdPx) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                reply()
                            }
                            travel = 0f
                        },
                        onDragCancel = { travel = 0f },
                    )
                },
        ) { content() }
    }
}
