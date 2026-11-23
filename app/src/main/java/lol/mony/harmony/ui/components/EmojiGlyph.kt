package lol.mony.harmony.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import lol.mony.harmony.data.model.Emoji

/**
 * One emoji, drawn the same way everywhere it appears: in a message body, on a reaction pill,
 * in the reactor list.
 *
 * The order is the reference client's: the emoji's **picture** when the row has a file, its own
 * **glyph** when it wraps a unicode codepoint, and its **shortcode** when there is neither.
 *
 * The one thing this composable exists to prevent is drawing nothing. An `AsyncImage` with no
 * error branch paints an empty box when its url does not resolve -- a purged file, an instance
 * that moved, a bridged emoji whose CDN is blocked -- and an emoji that draws nothing reads as a
 * bug, or worse, as a gap in the sentence. So a failed load falls back to the shortcode in place.
 *
 * A reaction whose emoji row this client could not read is passed here as the shortcode it
 * stores, so it draws `:blobcat:` rather than the uuid the row is keyed by. An id is never a
 * thing to show a person.
 *
 * @param emoji the emoji to draw, or null when the caller has nothing to draw but [label].
 * @param label the shortcode to say and to fall back to; also the content description.
 * @param size the box the picture occupies; a glyph or shortcode is scaled to fit it.
 * @param baseUrl the instance a relative `url` resolves against.
 * @param decorative true when an enclosing control already says what this emoji is -- a reaction
 *   pill names its emoji, its count and whose it is in one description -- so the picture here is
 *   not announced a second time.
 * @param textStyle the style a glyph or a shortcode is drawn in; the caller passes the style of
 *   the text the emoji sits in, so a fallback does not look like a smaller font in a sentence.
 */
@Composable
fun EmojiGlyph(
    emoji: Emoji?,
    label: String,
    size: Dp,
    modifier: Modifier = Modifier,
    baseUrl: String? = null,
    decorative: Boolean = false,
    textStyle: TextStyle? = null,
) {
    val shortcode = label.ifBlank { emoji?.shortcode ?: ":emoji:" }
    // An emoji's bare path names the `emojis` bucket, not the `avatars` one, so it resolves
    // through its own helper; a URL that already names its scheme passes through unchanged.
    val url = AvatarUrl.resolveEmoji(baseUrl, emoji?.url)
    // A picture that failed is not retried on every recomposition: the same url would fail the
    // same way, and the fallback is what the person should see from then on.
    var failed by remember(url) { mutableStateOf(false) }
    val described = if (decorative) null else shortcode

    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            url != null && !failed -> AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                contentDescription = described,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.size(size),
            )
            emoji?.glyph != null -> Text(
                emoji.glyph!!,
                style = textStyle ?: TextStyle.Default,
                fontSize = (size.value * 0.86f).sp,
                modifier = if (described == null) Modifier else Modifier.semantics { contentDescription = described },
            )
            else -> Text(
                shortcode,
                style = textStyle ?: MaterialTheme.typography.labelMedium,
                fontSize = textStyle?.fontSize?.takeIf { it != TextUnit.Unspecified } ?: (size.value * 0.6f).sp,
                maxLines = 1,
                modifier = if (described == null) Modifier else Modifier.semantics { contentDescription = described },
            )
        }
    }
}

/**
 * An emoji as text: the glyph when it wraps one, else the shortcode.
 *
 * This is what a composer, a clipboard copy or a notification writes; it is deliberately not what
 * a message body draws, which is [EmojiGlyph].
 */
internal fun Emoji.asPlainText(): String = glyph ?: shortcode

/**
 * What a custom emoji is called in a sentence a screen reader says: `:name:`.
 *
 * The reference's `customEmojiLabel` strips the colons a stored name may already carry, so a row
 * holding `:blobcat:` and one holding `blobcat` both read `:blobcat:`.
 */
internal fun customEmojiLabel(name: String?): String {
    val bare = name?.trim()?.trim(':')?.takeIf { it.isNotEmpty() } ?: return ""
    return ":$bare:"
}
