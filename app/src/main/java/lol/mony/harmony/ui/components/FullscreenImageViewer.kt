package lol.mony.harmony.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

/** How far a picture may be enlarged: past this a photo is a field of pixels. */
const val MAX_IMAGE_ZOOM = 5f

/**
 * A picture, full screen, with the zoom and pan a person expects from tapping one.
 *
 * Tapping an image used to open a bottom sheet with a 360dp copy of it in it, which is smaller
 * than the picture already drawn in the row and cannot be looked at more closely. This is the
 * gesture the reference client has instead: the picture fills the window over a black scrim,
 * pinch enlarges it, a double tap toggles between fit and 2.5x, and a drag pans while it is
 * enlarged. A tap that does not move closes it, as does back and the close button.
 *
 * Three rules keep the zoom from becoming a trap, which is what a naive
 * `detectTransformGestures` produces:
 *
 * - **The pan is clamped to the picture's own overflow.** A picture dragged past its edge
 *   shows black beyond it and cannot be brought back without knowing which way to drag.
 * - **Zooming back out to fit recentres.** At 1x there is nothing to pan, so the offset is
 *   zeroed rather than left where the last drag put it.
 * - **There is a button for both zoom directions.** A pinch is the fast way and not the only
 *   way: a person using a screen reader, or with one hand, or on a device whose touch is
 *   unreliable, has the same two actions as buttons in the bar. That is the same rule the
 *   reaction gestures follow in the message row.
 *
 * [onDismiss] is the only way out, so a caller decides what closing means; [onOpen] is offered
 * for a picture with a file behind it, and is null when there is nothing to open.
 */
@Composable
fun FullscreenImageViewer(
    url: String,
    description: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** A second line under the picture: the file's size, its kind, or nothing. */
    supporting: String? = null,
    /** What "Open" does: the browser, a share sheet, a download. Null draws no such button. */
    onOpen: (() -> Unit)? = null,
    openLabel: String = "Open",
) {
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    // The picture's own size, which is what the pan has to be clamped to. Measured rather than
    // assumed: a portrait photo and a wide screenshot overflow by different amounts.
    var boxWidth by remember(url) { mutableFloatStateOf(0f) }
    var boxHeight by remember(url) { mutableFloatStateOf(0f) }

    /** Keeps the picture's edges from being dragged inside its own frame. */
    fun clamp(candidate: Offset, atScale: Float): Offset {
        if (atScale <= 1f) return Offset.Zero
        val maxX = boxWidth * (atScale - 1f) / 2f
        val maxY = boxHeight * (atScale - 1f) / 2f
        return Offset(
            candidate.x.coerceIn(-maxX.coerceAtLeast(0f), maxX.coerceAtLeast(0f)),
            candidate.y.coerceIn(-maxY.coerceAtLeast(0f), maxY.coerceAtLeast(0f)),
        )
    }

    fun zoomTo(target: Float) {
        scale = target.coerceIn(1f, MAX_IMAGE_ZOOM)
        offset = clamp(offset, scale)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag("image-viewer"),
        ) {
            // The picture, over the whole window, behind the two bars. It is the layer that
            // owns the gestures, so a pinch on the picture is a zoom and a pinch on the bar is
            // nothing at all.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(url) {
                        boxWidth = size.width.toFloat()
                        boxHeight = size.height.toFloat()
                    }
                    // Two detectors on one node is the documented shape for a zoomable picture:
                    // each sees the whole stream, and each acts on the gestures it owns. The tap
                    // detector waits out the double-tap window before deciding a tap was single,
                    // which is what lets one tap close and two taps zoom; the transform detector
                    // takes the pinch and the pan and never claims a press that does not move.
                    .pointerInput(url) {
                        detectTapGestures(
                            onTap = { onDismiss() },
                            onDoubleTap = { position ->
                                // Toggling about the tap's own position is what makes a double
                                // tap on a face look at that face: the zoom is about the point
                                // the finger landed, not about the centre of the window.
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                    offset = clamp(
                                        Offset(
                                            (boxWidth / 2f - position.x) * (scale - 1f),
                                            (boxHeight / 2f - position.y) * (scale - 1f),
                                        ),
                                        scale,
                                    )
                                }
                            },
                        )
                    }
                    .pointerInput(url) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val next = (scale * gestureZoom).coerceIn(1f, MAX_IMAGE_ZOOM)
                            scale = next
                            offset = clamp(offset + pan, next)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = description ?: "Attachment",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .testTag("image-viewer-picture"),
                )
            }
            // The top bar: close, the name, and the two zoom buttons. It sits over the picture
            // rather than above it so the picture gets the whole window when it is not needed.
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("image-viewer-close")) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        description ?: "Attachment",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!supporting.isNullOrBlank()) Text(
                        supporting,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                    )
                }
                // The pinch's two directions, as buttons. A zoom control that only exists as a
                // gesture is a control half the people who need it cannot reach.
                IconButton(
                    onClick = { zoomTo(scale / 1.5f) },
                    enabled = scale > 1f,
                    modifier = Modifier.testTag("image-viewer-zoom-out"),
                ) { Icon(Icons.Default.ZoomOut, "Zoom out", tint = if (scale > 1f) Color.White else Color.White.copy(alpha = 0.4f)) }
                IconButton(
                    onClick = { zoomTo(scale * 1.5f) },
                    enabled = scale < MAX_IMAGE_ZOOM,
                    modifier = Modifier.testTag("image-viewer-zoom-in"),
                ) { Icon(Icons.Default.ZoomIn, "Zoom in", tint = if (scale < MAX_IMAGE_ZOOM) Color.White else Color.White.copy(alpha = 0.4f)) }
            }
            if (onOpen != null) {
                Surface(
                    onClick = onOpen,
                    color = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .testTag("image-viewer-open"),
                ) {
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Text(openLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            // The zoom level, said in words, so a screenshot and a test both have something to
            // read: a picture at 250% looks much like one at 240%, and neither says which.
            if (scale > 1f) Text(
                "%.0f%%".format(scale * 100f),
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("image-viewer-zoom-level")
                    .semantics { contentDescription = "Zoom " + "%.0f%%".format(scale * 100f) },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}
