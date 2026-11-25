package lol.mony.harmony.ui.screens.chat

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import lol.mony.harmony.ui.theme.LocalMotion
import kotlin.math.abs

/** How far from the start edge a finger may land and still be making this gesture. */
private val StartEdgeBand = 24.dp

/** How much of the pane has to be dragged away before letting go finishes the move. */
private const val RevealFraction = 0.3f

/** A flick opens the list from this far out, short of [RevealFraction]. */
private val FlingDistance = 64.dp

/** How fast a flick has to be, in dp per second, to count as one. */
private val FlingVelocity = 500.dp

/** How long the pane takes to finish a move: back over the list, or away from it. */
private const val SettleMillis = 180

/**
 * A pane that follows a drag towards the end edge when the drag begins at the start edge, and
 * closes itself -- uncovering the list of the server it belongs to -- once the drag is far
 * enough to mean it.
 *
 * This is the gesture the phone's one-pane Chats layout was missing. A room covers the list, so
 * the list was reachable only by the room's back arrow or the system's back; the direction those
 * gestures live in -- out of the start edge, towards the end -- was owned by nobody. It is the
 * half of the horizontal axis the reply does not have: [SwipeToReplyRow] travels *away* from the
 * start edge, this travels *towards* it, so a row that answers a message and a pane that returns
 * to the list can never be the same drag.
 *
 * Three rules keep the two apart, and each is load-bearing:
 *
 * - **The gesture starts at the start edge.** [StartEdgeBand] is a narrow strip, not the pane, so
 *   a drag from the middle of a message is not a navigation, and the reply stays reachable from
 *   anywhere on the row.
 * - **Only the end-wards direction is claimed.** A drag the other way -- or a mostly vertical
 *   one, which is the message list's scroll -- is left entirely alone, so whatever child owns it
 *   still receives every event.
 * - **The drag is claimed in the [PointerEventPass.Initial] pass.** A pane that waited for the
 *   main pass would lose the gesture to the row under the finger: the row's own
 *   `detectHorizontalDragGestures` does not care which way the finger went, so it takes any
 *   horizontal drag past slop and merely clamps its travel at zero.
 *
 * The sign comes from [LocalLayoutDirection], as it does for the reply, so a mirrored layout
 * starts the gesture at its own start edge and moves it the other way.
 *
 * A phone in gesture navigation hands this strip to the system's back gesture first; that closes
 * the room too, so both routes end in the same place. This one is what makes the same move
 * reachable on a three-button device, from a drag that begins just inside the edge, and with the
 * pane following the finger rather than jumping.
 *
 * [onClose] is called once the pane has finished sliding away, so the room closes with its own
 * content drawn rather than as a blank surface, and the list underneath is uncovered by the
 * movement the person started. A drag let go short of the threshold is undone here instead, by
 * sliding the pane back over the list.
 */
@Composable
internal fun SwipeToChannelList(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // How far the pane has moved towards the end edge, in pixels: 0 while it covers the list.
    // It lives with the composition, so a room opened again starts over the list.
    var travel by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val motion = LocalMotion.current
    val scope = rememberCoroutineScope()
    // Towards the end edge: positive (rightwards) in a left-to-right layout, negative in a
    // right-to-left one, where the start edge is on the right.
    val towardsEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val bandPx = with(density) { StartEdgeBand.toPx() }
    // `toPx()` is the dp-to-pixel factor, so these are pixels and pixels per second.
    val flingDistancePx = with(density) { FlingDistance.toPx() }
    val flingVelocityPx = with(density) { FlingVelocity.toPx() }
    // The gesture block outlives a recomposition, so it must not keep the callback it was first
    // composed with.
    val close by rememberUpdatedState(onClose)
    // The settle in flight: the slide back, or the slide out that closes the room. A new touch
    // takes the pane over rather than waiting it out, so the gesture is never briefly deaf.
    var settling by remember { mutableStateOf<Job?>(null) }
    // True once a drag has asked for the room to close. A finger landing while the dismissal
    // runs must not drag a pane that is already on its way out.
    var closed by remember { mutableStateOf(false) }

    Box(
        modifier
            .fillMaxSize()
            .testTag("room-swipe")
            // Outside the layer, so the strip this reads is the pane's own edge rather than the
            // edge it has been dragged to.
            .pointerInput(towardsEnd, bandPx, flingDistancePx, flingVelocityPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    if (closed) return@awaitEachGesture
                    settling?.cancel()
                    val paneWidth = size.width.toFloat()
                    // The start edge, whichever side of a mirrored layout that is.
                    val atStartEdge =
                        if (towardsEnd > 0f) down.position.x <= bandPx
                        else down.position.x >= paneWidth - bandPx
                    if (!atStartEdge) return@awaitEachGesture

                    val slop = viewConfiguration.touchSlop
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var travelled = 0f
                    var vertical = 0f
                    var claimed = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = change.positionChange()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        travelled += delta.x * towardsEnd
                        vertical += delta.y
                        if (!claimed) {
                            when {
                                // The other way is the message row's reply, not this.
                                travelled < -slop -> break
                                // Up and down is the message list's own scroll.
                                abs(vertical) > abs(travelled) && abs(vertical) > slop -> break
                                travelled > slop -> claimed = true
                            }
                        }
                        if (claimed) {
                            // Claimed in the initial pass, so no child ever sees a drag this
                            // pane is drawing. The slop the finger spent being recognised does
                            // not move the pane: it starts from the edge the person aimed at.
                            change.consume()
                            travel = (travelled - slop).coerceIn(0f, paneWidth)
                        }
                        if (!change.pressed) break
                    }

                    if (!claimed) return@awaitEachGesture
                    val dragged = travelled - slop
                    val velocity = tracker.calculateVelocity().x * towardsEnd
                    val open = dragged >= paneWidth * RevealFraction ||
                        (dragged >= flingDistancePx && velocity >= flingVelocityPx)
                    // The pane finishes the move the finger started, and only then does the room
                    // close: a release past the threshold completes the gesture instead of
                    // snapping the pane back and sliding it out a second time.
                    settling = scope.launch {
                        animate(
                            initialValue = travel,
                            targetValue = if (open) paneWidth else 0f,
                            animationSpec = motion.spatial(SettleMillis),
                        ) { value, _ -> travel = value }
                        if (open) {
                            closed = true
                            close()
                        }
                    }
                }
            }
            // Read at draw time rather than recomposing: a drag is a per-frame value.
            .graphicsLayer { translationX = towardsEnd * travel },
    ) { content() }
}
