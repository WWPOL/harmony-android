package lol.mony.harmony.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.ui.theme.HarmonySize

/**
 * What a message is answering, drawn above the composer while a reply is being written.
 *
 * The old banner said `Replying to Noah: the first sixty characters…` as one grey line with a
 * close button. That is a sentence about the reply rather than a picture of it, and it is
 * indistinguishable from the message below it: a person writing an answer wants to see the
 * thing they are answering, with its author, its shape and -- when it is a picture or a video --
 * what kind of thing it is.
 *
 * So this is the reference client's shape: a small card with the author's name in their colour,
 * a muted snippet of the body, and a glyph saying which kind of attachment it carries when it
 * carries one. The card is tappable, which scrolls the channel to the message being answered --
 * the case that made the old banner insufficient, because "which message is this?" is the
 * question it could not answer. The close button is a separate target so a thumb reaching for
 * "cancel" never jumps the list instead.
 *
 * [onJump] is null while the original is not in the loaded history; the card is then a label
 * and not a control, rather than a tap that does nothing.
 */
@Composable
fun ReplyPreview(
    author: String,
    message: Message,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onJump: (() -> Unit)? = null,
) {
    val kind = attachmentKind(message)
    val snippet = replySnippet(message, kind)
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .testTag("reply-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The accent bar is the whole visual grammar of a quoted block: it says "this is not a
        // message, it is about one" without a second surface colour.
        Box(
            Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(
            Modifier
                .weight(1f)
                .then(if (onJump != null) Modifier.clickable(onClick = onJump) else Modifier)
                .testTag("reply-preview"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // A pin in the corner of the quote is how the reference says "this message is
                // pinned"; the name alone would not.
                if (message.isPinned) Icon(
                    Icons.Default.PushPin, "Pinned", Modifier.size(11.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    author,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("reply-preview-author"),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (kind != null) Icon(
                    kind.first,
                    kind.second,
                    Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("reply-preview-snippet"),
                )
            }
        }
        // The dismiss target is its own button and its own 48dp: the card beside it is the jump.
        IconButton(onClick = onDismiss, modifier = Modifier.testTag("reply-cancel")) {
            Icon(Icons.Default.Close, "Cancel reply", Modifier.size(18.dp))
        }
    }
}

/**
 * The glyph and name for the kind of attachment a message carries, or null when it has none.
 *
 * It is what makes a reply to a picture recognisable as a reply to a picture: the snippet for
 * such a message is the word "Photo", and the glyph is what a person actually reads at a glance.
 */
internal fun attachmentKind(message: Message): Pair<androidx.compose.ui.graphics.vector.ImageVector, String>? {
    val files = message.content.filter { it.type == "file" }
    if (files.isEmpty()) return null
    return when {
        files.any { it.isVideoPart() } -> Icons.Default.Movie to "Video"
        files.any { it.fileType == "image" || it.mimeType?.startsWith("image/") == true } ->
            Icons.Default.Image to "Photo"
        files.any { it.fileType == "audio" || it.mimeType?.startsWith("audio/") == true } ->
            Icons.Default.MusicNote to "Audio"
        else -> null
    }
}

/**
 * One line that says what a message is, for the quote above the composer.
 *
 * The words come first, then what kind of thing the message carries. `plainText` is not used for
 * the second case, because it renders every attachment as a placeholder -- a photo reads as
 * `[image: attachment]` and a video as `[video: attachment]` -- and a quote that repeated those
 * would be a quote of this app's own vocabulary rather than of the message. Only the parts that
 * are actually words are joined, and a message with no words is named by its kind.
 *
 * The cap is the quote's width, not a character count: the `Text` truncates with an ellipsis, so
 * a message with one very long word still fills the line rather than being cut at an arbitrary
 * place.
 */
internal fun replySnippet(message: Message, kind: Pair<androidx.compose.ui.graphics.vector.ImageVector, String>?): String {
    // The words only: an attachment, a sticker, a poll and a system event all have a rendering
    // in `plainText` that is a placeholder, not something the sender typed.
    val text = message.content.filter { it.isInline && it.type != "emoji" }
        .joinToString(" ") { it.text.orEmpty() }
        .trim()
        .replace(Regex("\\s+"), " ")
    if (text.isNotEmpty()) return text
    if (kind != null) return kind.second
    if (message.content.any { it.type == "sticker" }) return "Sticker"
    if (message.content.any { it.type == "poll" }) return "Poll"
    if (message.content.any { it.type == "embed" }) return "Link"
    return "Message"
}

/**
 * The message list's own reply reference: the line a message that answers something draws above
 * its author.
 *
 * It is deliberately shorter than [ReplyPreview] -- there is no dismiss button, because there is
 * nothing to cancel, and the bar is inline with the text rather than a card. What it gains over
 * the bare word "Reply" it used to draw is the author and the gist, which is what a person
 * reading a channel of answers needs in order to know what was answered. The full quote is one
 * tap away ([onJump]), which scrolls to the original.
 *
 * A message whose original is not in the loaded history says so with the id's absence rather
 * than with a blank line: [author] and [snippet] are null then, and the reference draws "Reply"
 * alone -- the same shape the old one always had.
 */
@Composable
fun ReplyReference(
    author: String?,
    snippet: String?,
    onJump: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .padding(bottom = 2.dp)
            .testTag("reply-reference"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A left rule and a rounded corner: the reference's quote bar, at the size of one line.
        Box(
            Modifier
                .width(2.dp)
                .height(14.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.width(6.dp))
        val text = if (author != null && snippet != null) {
            buildString {
                append(author)
                append("  ")
                append(snippet)
            }
        } else "Reply"
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .then(if (onJump != null) Modifier.clickable(onClick = onJump) else Modifier),
        )
    }
}

/** The height a reply preview reserves, so the message list's own inset can account for it. */
val ReplyPreviewHeight = 46.dp

/** A part is a picture this client can show in the viewer rather than a file it can only name. */
internal fun ContentPart.isImagePart(): Boolean =
    type == "file" && (
        fileType == "image" || mimeType?.startsWith("image/") == true ||
            listOf("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp")
                .any { ext -> (url ?: path).orEmpty().substringBefore('?').endsWith(ext, ignoreCase = true) }
        )

/** Kept for callers that want the shape above without the icon; see [attachmentKind]. */
internal val ReplyCardMinHeight = HarmonySize.minTouchTarget
