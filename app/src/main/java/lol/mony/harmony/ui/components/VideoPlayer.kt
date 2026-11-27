@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package lol.mony.harmony.ui.components

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import lol.mony.harmony.data.model.ContentPart

/**
 * A video attachment, played.
 *
 * A video used to be a card that said `Video: standup.mp4` and could not be watched: the app
 * could upload one and could not play one, which is the worse half of a missing feature. The
 * player is Android's own -- [ExoPlayer] behind a [PlayerView] -- rather than a hand-drawn one,
 * so transport controls, seeking, the scrub bar, captions and the accessibility of all three
 * come from the platform instead of from a second implementation that would be worse at it.
 *
 * **Nothing plays until the person presses play.** A channel of videos that all started on
 * their own would spend the person's data and fill the room with sound they did not ask for;
 * `playWhenReady = false` and the platform's own play button are the whole rule.
 *
 * **The player is released when the row leaves the screen.** An `ExoPlayer` holds a decoder and
 * an audio focus request, and a LazyColumn recycles rows; a player that outlived its row would
 * leak one decoder per video scrolled past. [DisposableEffect] is the lifecycle the row has.
 *
 * A URL that will not open is reported on the tile, not swallowed: the same reason the rest of
 * this app gives for a failed load, so a private object whose signature expired does not look
 * like a video that is merely slow.
 *
 * [compact] is the inline row shape: a phone-width tile with a ceiling on its height. The
 * fullscreen viewer passes `false` so the player takes the window instead of a fixed slice of it.
 */
@Composable
fun VideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    /** What a screen reader says this player is: the file's name, or its alt text. */
    label: String = "Video attachment",
    /** Restarts the player when the attachment changes, so two videos never share one. */
    key: Any? = url,
) {
    val context = LocalContext.current
    var failure by remember(key) { mutableStateOf<String?>(null) }
    var player by remember(key) { mutableStateOf<ExoPlayer?>(null) }

    DisposableEffect(key) {
        onDispose {
            player?.release()
            player = null
        }
    }

    val shape = if (compact) RoundedCornerShape(12.dp) else RoundedCornerShape(0.dp)
    Box(
        modifier
            .then(if (compact) Modifier.widthIn(max = 320.dp) else Modifier.fillMaxWidth())
            .then(if (compact) Modifier.heightIn(min = 160.dp, max = 240.dp) else Modifier.fillMaxWidth())
            .clip(shape)
            .background(Color.Black)
            // The file's own name is what a screen reader says for the player: a video with no
            // visible title of its own is otherwise an unnamed control.
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().testTag("video-player").let {
                // The inline tile keeps a 16:9 box so a row of messages does not jump when a
                // video's real aspect ratio arrives; the fullscreen one fills what it is given.
                if (compact) it.aspectRatio(16f / 9f) else it
            },
            factory = { viewContext ->
                val view = PlayerView(viewContext).apply {
                    useController = true
                    // The controls are the platform's; the tile behind them is the app's own
                    // background, so a video that has not started is not a white rectangle.
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    if (!compact) resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                val created = ExoPlayer.Builder(viewContext).build().apply {
                    // Not autoplay: see the note above. A person taps play.
                    playWhenReady = false
                    setMediaItem(MediaItem.fromUri(url))
                    prepare()
                }
                created.addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        failure = error.message?.takeIf { it.isNotBlank() }
                            ?: "This video could not be played."
                    }
                })
                player = created
                view.player = created
                view
            },
            update = { view -> player?.let { if (view.player !== it) view.player = it } },
            onRelease = { view ->
                view.player = null
                player?.release()
                player = null
            },
        )
        val reason = failure
        if (reason != null) {
            Text(
                reason,
                Modifier.padding(16.dp).testTag("video-failure"),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
            )
        }
        // A new attachment clears the previous one's failure: two videos share this composable
        // when a row is recycled, and a stale "could not be played" over a working player is a
        // lie about the file that is actually there.
        LaunchedEffect(key) { failure = null }
    }
}

/**
 * Whether a part is a video this client should hand to [VideoPlayer].
 *
 * Three ways the instance says so, because three clients write these rows: `file_type` is the
 * canonical one, `mime_type` is what a browser upload fills in, and the file extension is the
 * fallback for a row written before either existed. The same shape as the image test beside it,
 * so a part cannot be an image here and a video there.
 */
fun ContentPart.isVideoPart(): Boolean {
    if (type != "file") return false
    if (fileType == "video") return true
    if (mimeType?.startsWith("video/") == true) return true
    val name = (url ?: path ?: fileName).orEmpty().substringBefore('?').lowercase()
    return VIDEO_EXTENSIONS.any { name.endsWith(it) }
}

/** The extensions a video attachment may carry when the part does not say it is one. */
private val VIDEO_EXTENSIONS = listOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "ogv")
