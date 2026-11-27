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
 * Two rules keep the two apart, and each is load-bearing:
 *
 * - **Only the end-wards direction is claimed.** A drag the other way -- or a mostly vertical
 *   one, which is the message list's scroll -- is left entirely alone, so whatever child owns it
 *   still receives every event. The reply is the other way, so the two can never be one drag.
 * - **The drag is claimed in the [PointerEventPass.Initial] pass.** A pane that waited for the
 *   main pass would lose the gesture to the row under the finger: the row's own
 *   `detectHorizontalDragGestures` does not care which way the finger went, so it takes any
 *   horizontal drag past slop and merely clamps its travel at zero.
 *
 * The sign comes from [LocalLayoutDirection], as it does for the reply, so a mirrored layout
 * starts the gesture at its own start edge and moves it the other way.
 *
 * **It claims the whole pane, not a strip at its edge.** It used to need the finger to land
 * within 24dp of the start edge, and that is why the gesture was reported as not working: 24dp
 * is inside the band Android's own back gesture owns on a gesture-navigation phone, so the
 * system took almost every drag the app was waiting for, and the sliver left over was narrower
 * than a thumb can reliably land in. A three-button device had no such competitor and still made
 * a person aim at a 24dp target to go back -- while the same drag anywhere else on the screen
 * did nothing at all, because the message row had consumed it and clamped it to zero.
 *
 * The direction is what separates this from the reply, and direction is a property of the drag
 * rather than of where it began, so the whole message area can belong to it. A drag out of the
 * system's own edge still goes to the system, which closes the room the same way: both routes
 * end in the same place, which is the point.
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
    /**
     * How much of the pane's bottom is not the pane's to claim, in pixels.
     *
     * The composer is there, and a text field's own drag is how a person selects what they
     * wrote. A pane that claimed every rightward drag in the room would turn "select this word"
     * into "leave the channel", which is a worse bug than the one this gesture fixes. The
     * caller measures the composer and passes its height; the message area above it is claimed
     * whole, which is the part a person actually swipes across.
     */
    excludedBottom: () -> Float = { 0f },
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
    // `toPx()` is the dp-to-pixel factor, so these are pixels and pixels per second.
    val flingDistancePx = with(density) { FlingDistance.toPx() }
    val flingVelocityPx = with(density) { FlingVelocity.toPx() }
    // The exclusion is read through a lambda, so a caller that measures its composer later does
    // not have to restart the gesture detector to tell it the height.
    val excluded by rememberUpdatedState(excludedBottom)
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
            // Outside the layer, so the size this reads is the pane's own rather than the one it
            // has been dragged to.
            .pointerInput(towardsEnd, flingDistancePx, flingVelocityPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    if (closed) return@awaitEachGesture
                    settling?.cancel()
                    val paneWidth = size.width.toFloat()
                    // The composer keeps its own horizontal drags.
                    val floor = size.height - excluded()
                    if (down.position.y > floor) return@awaitEachGesture

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
