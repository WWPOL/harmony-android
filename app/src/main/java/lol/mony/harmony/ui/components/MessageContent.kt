package lol.mony.harmony.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.Emoji
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.repo.MessageMedia
import lol.mony.harmony.ui.theme.LocalSizes

/**
 * A message's body: every part the instance can store, drawn the way the web client's
 * `UnifiedMessageContent.vue` draws it, rather than only the text.
 *
 * An emoji part is an image, not a shortcode -- `EmojiContent.emoji` carries the `emojis` row's
 * `url`, and that is what a person sees. When the row has no image (a native unicode emoji, or a
 * bridged emoji whose file was purged) the shortcode shows instead, so nothing disappears.
 * Mentions, channel references and hashtags read as they do on the web. Anything this build does
 * not draw yet says so on its own line; it never throws.
 */
@Composable
fun MessageContent(
    message: Message,
    fontSize: Double,
    modifier: Modifier = Modifier,
    encryptedNotice: String? = null,
    /** The instance an attachment's or emoji's relative URL resolves against. */
    baseUrl: String? = null,
    /**
     * The URL a private attachment's `path` resolves to, when the caller has signed it.
     *
     * A `message_media` part carries an object name that resolves for nobody without a token, so
     * a part with a `path` draws from this and never from the `url` it also carries: that URL
     * expired seven days after the upload. Null means "not signed yet, or not readable", which
     * draws a placeholder rather than a broken picture.
     */
    signedUrl: ((String) -> String?)? = null,
    /** What tapping a non-image attachment does; a picture is opened the same way when given. */
    onOpenAttachment: ((ContentPart) -> Unit)? = null,
    /** The `emojis` rows the room resolved, so an id-only emoji part still draws its picture. */
    emojis: Map<String, Emoji> = emptyMap(),
) {
    val sizes = LocalSizes.current
    val style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize.sp)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val color = if (message.isSystem) muted else MaterialTheme.colorScheme.onSurface

    if (encryptedNotice != null) {
        SelectionContainer { Text(encryptedNotice, style = style, color = muted, modifier = modifier) }
        return
    }
    if (message.content.isEmpty()) {
        SelectionContainer { Text("This message has no content.", style = style, color = muted, modifier = modifier) }
        return
    }

    // Text and the inline parts between it share one flow, so `shipping :blobcat: today` is
    // one sentence with the emoji in it, as the web client draws it. Only a part that stands on
    // its own -- an attachment, a sticker, a poll, a system event -- breaks the line.
    Column(modifier = modifier) {
        for (run in runs(message.content)) {
            if (run.block) {
                BlockPart(run.parts.first(), style, muted, baseUrl, signedUrl, onOpenAttachment)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    for (part in run.parts) InlinePart(message.id, part, style, color, sizes.messageSp, muted, baseUrl, emojis)
                }
            }
        }
    }
}

private class Run(val parts: List<ContentPart>, val block: Boolean)

/** Groups the body into flowing runs and the parts that stand alone. */
private fun runs(content: List<ContentPart>): List<Run> {
    val out = mutableListOf<Run>()
    val flowing = mutableListOf<ContentPart>()
    fun flush() { if (flowing.isNotEmpty()) { out += Run(flowing.toList(), false); flowing.clear() } }
    for (part in content) {
        if (part.isBlock) { flush(); out += Run(listOf(part), true) } else flowing += part
    }
    flush()
    return out
}

/** An emoji image, a mention or a hashtag: drawn inside the line it belongs to. */
@Composable
private fun InlinePart(messageId: String, part: ContentPart, style: androidx.compose.ui.text.TextStyle,
                       color: androidx.compose.ui.graphics.Color, messageSp: Double,
                       muted: androidx.compose.ui.graphics.Color, baseUrl: String?,
                       emojis: Map<String, Emoji>) {
    when (part.type) {
        // Text is not drawn as one run: the reference resolves `:shortcode:` inside it to the
        // emoji's picture (`renderMarkdownToHTML`'s `emoji` token), so a message someone typed a
        // server emoji into draws the emoji rather than its name.
        "text" -> TextWithEmoji(part.text.orEmpty(), style, color, messageSp, baseUrl, emojis,
            Modifier.testTag("message-" + messageId))
        "emoji" -> EmojiPart(part, messageSp, baseUrl = baseUrl, emojis = emojis)
        "mention" -> Text(buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) {
                append("@" + (part.username ?: part.displayName ?: "someone"))
            }
        }, style = style)
        "role_mention" -> Text(buildAnnotatedString {
            val tint = part.roleColor?.let(::parseHexColor) ?: MaterialTheme.colorScheme.primary
            withStyle(SpanStyle(color = tint, fontWeight = FontWeight.Medium)) {
                append("@" + (part.roleName ?: part.roleId ?: "role"))
            }
        }, style = style)
        "channel_mention" -> Text("#" + (part.name ?: "channel"), style = style,
            color = MaterialTheme.colorScheme.primary)
        "hashtag" -> Text("#" + (part.name ?: "tag"), style = style, color = MaterialTheme.colorScheme.primary)
        // A masked link reads as its label and links nowhere by itself; the text is what
        // the sender wrote, which is what the web shows too.
        "url" -> Text(part.asText, style = style, color = MaterialTheme.colorScheme.primary)
        else -> Text(part.asText, style = style, color = color)
    }
}

/**
 * One piece of a text part: literal text, or a `:shortcode:` the instance may have an emoji for.
 *
 * The reference's parser produces exactly these two, and its renderer draws an `emoji` token as
 * the emoji's image when it can resolve the name, else as the literal `:name:` it was written as.
 */
internal sealed interface TextToken {
    data class Literal(val text: String) : TextToken
    data class Shortcode(val name: String) : TextToken
}

/**
 * Splits a text part into literal text and the shortcodes in it.
 *
 * The rules are the reference's (`src/utils/markdownParser.ts`): a shortcode is `:name:` of
 * `[a-zA-Z0-9_+~-]` (the `~N` suffix disambiguates two emoji of one name), a bridged emoji is
 * `:discord:[a:]<name>:<snowflake>:`, and a code span is left alone -- `` `:cat:` `` is how a
 * person writes about a shortcode rather than using one. `:` alone, or an empty pair of colons,
 * is text.
 */
internal fun textTokens(text: String): List<TextToken> {
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<TextToken>()
    var index = 0
    var literalStart = 0
    fun flushLiteral(end: Int) {
        if (end > literalStart) out += TextToken.Literal(text.substring(literalStart, end))
    }
    while (index < text.length) {
        // A code span runs to its closing backtick, and nothing inside it is an emoji.
        if (text[index] == '`') {
            val close = text.indexOf('`', index + 1)
            index = if (close > index) close + 1 else index + 1
            continue
        }
        if (text[index] != ':') { index++; continue }
        val match = SHORTCODE_IN_TEXT.find(text, index)
        if (match == null || match.range.first != index) { index++; continue }
        flushLiteral(index)
        out += TextToken.Shortcode(match.groupValues[1])
        index = match.range.last + 1
        literalStart = index
    }
    flushLiteral(text.length)
    return out
}

/** `:name:` or `:discord:[a:]<name>:<snowflake>:`, the two shapes the reference tokenises. */
private val SHORTCODE_IN_TEXT =
    Regex(":(discord:(?:a:)?[A-Za-z0-9_]{1,64}:[0-9]{5,25}|[A-Za-z0-9_+~\\-]{1,64}):")

/**
 * A text part, with the shortcodes in it drawn as the emoji they name.
 *
 * The reference draws `:blobcat:` inside a message as blobcat's picture, and leaves it as the
 * literal text when it cannot resolve the name -- which is why a person sees `:long_name:` where
 * an emoji belongs on an instance whose library this client has not read. Here the name is looked
 * up in [emojis] by name; a name nothing answers to stays exactly the text it was written as.
 */
@Composable
private fun TextWithEmoji(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    messageSp: Double,
    baseUrl: String?,
    emojis: Map<String, Emoji>,
    modifier: Modifier = Modifier,
) {
    val tokens = remember(text) { textTokens(text) }
    // A part with no shortcode in it -- most of them -- is one Text, so the common case costs
    // nothing and keeps the selection and wrapping behaviour it always had.
    if (tokens.none { it is TextToken.Shortcode }) {
        SelectionContainer { Text(text, style = style, color = color, modifier = modifier) }
        return
    }
    SelectionContainer {
        FlowRow(modifier = modifier, itemVerticalAlignment = Alignment.CenterVertically) {
            for (token in tokens) when (token) {
                is TextToken.Literal -> Text(token.text, style = style, color = color)
                is TextToken.Shortcode -> {
                    val emoji = emojiNamed(emojis, token.name)
                    if (emoji == null) {
                        // What the reference renders when its resolver has nothing: the text as
                        // written, so a `:name:` an instance does not have is not a hole.
                        Text(":" + token.name + ":", style = style, color = color)
                    } else {
                        EmojiGlyph(
                            emoji = emoji,
                            label = ":" + token.name + ":",
                            size = (messageSp + 6.0).dp,
                            baseUrl = baseUrl,
                            textStyle = style,
                            modifier = Modifier.testTag("text-emoji-" + token.name),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The emoji [name] names, from the rows the room resolved: by name, else by a bridged identifier.
 *
 * A row's name is what a shortcode is written from, and two rows may share one -- a server emoji
 * and a personal one -- so the first is taken rather than none. `~N` is the reference's
 * disambiguator and is dropped here for the same reason.
 */
internal fun emojiNamed(emojis: Map<String, Emoji>, name: String): Emoji? {
    val bare = name.substringBefore('~')
    emojis.values.firstOrNull { it.name?.trim()?.trim(':')?.equals(bare, ignoreCase = true) == true }
        ?.let { return it }
    return Emoji.fromStoredContent(bare).takeIf { it.hasImage }
}

/**
 * One emoji: its image when the row has a file, its own glyph when it wraps a unicode codepoint,
 * and its shortcode when there is neither.
 *
 * The description is the `:name:` shortcode, which is what a screen reader says and what the web
 * puts in the image's `alt`. A part whose row is not in the message (an id-only part, or one a
 * bridged client wrote) is filled in from [emojis], which the room resolves for the page.
 */
@Composable
fun EmojiPart(part: ContentPart, messageSp: Double, modifier: Modifier = Modifier,
              baseUrl: String? = null, emojis: Map<String, Emoji> = emptyMap()) {
    val declared = part.emoji
    val row = declared?.id?.let { emojis[it] }
    // The part is the truth about which emoji it is; the row fills in a picture the part lacks.
    val emoji = when {
        declared == null -> row
        row == null -> declared
        else -> declared.copy(
            name = declared.name ?: row.name,
            url = declared.url ?: row.url,
            content = declared.content ?: row.content,
        )
    }
    val label = emoji?.let { if (it.hasImage) customEmojiLabel(it.name).ifBlank { ":emoji:" } else it.shortcode }
        ?: ":emoji:"
    // The tag is on every branch so a test -- and a screenshot reader -- finds the same
    // node whichever way this particular emoji draws.
    val tag = "emoji-" + (emoji?.name ?: label.trim(':'))
    EmojiGlyph(
        emoji = emoji,
        label = label,
        size = (messageSp + 6.0).dp,
        baseUrl = baseUrl,
        // A fallback reads at the sentence's own size, not as a caption under it.
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = (messageSp + 1.0).sp),
        modifier = modifier.testTag(tag),
    )
}

/** A part that stands on its own line: an attachment, a sticker, a poll, a system event. */
@Composable
private fun BlockPart(
    part: ContentPart,
    style: androidx.compose.ui.text.TextStyle,
    muted: androidx.compose.ui.graphics.Color,
    baseUrl: String?,
    signedUrl: ((String) -> String?)?,
    onOpenAttachment: ((ContentPart) -> Unit)?,
) {
    val caption = MaterialTheme.typography.bodySmall.copy(color = muted)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        when (part.type) {
            "file" -> FilePart(part, style, muted, baseUrl, signedUrl, onOpenAttachment)
            "sticker" -> {
                if (!part.url.isNullOrBlank()) {
                    AsyncImage(model = part.url, contentDescription = part.description ?: part.name ?: "Sticker",
                        contentScale = ContentScale.Fit, modifier = Modifier.size(96.dp))
                } else Text(":" + (part.name ?: "sticker") + ":", style = style, color = muted)
            }
            "poll" -> Text("Poll: " + (part.question ?: ""), style = style, color = muted)
            "system" -> Text(part.asText, style = style, color = muted)
            // A link preview is a card on the web; here it names what it points at, so a
            // person can tell there is one without the client pretending to draw it.
            "embed" -> Text("Link preview: " + (part.url ?: part.provider ?: "link"),
                style = style, color = muted, modifier = Modifier.testTag("embed"))
            else -> Text("This message contains " + part.type + " content this client cannot draw yet.",
                style = caption)
        }
    }
}

/**
 * An attachment: the picture itself when it is an image, a labelled card for anything else.
 *
 * The web client draws images inline, sized to a maximum, with the alt text beneath; a video
 * or a document gets a card naming it. Announcing every attachment as a sentence -- which this
 * client used to do -- makes a channel of pictures unreadable.
 *
 * A private attachment (`path` set) draws from [signedUrl] and never from its own `url`, which
 * expired seven days after it was uploaded. While that URL is missing the picture is a
 * placeholder that says it is loading or unreadable, which is what a person needs to know: a
 * blank box is indistinguishable from a channel of nothing.
 */
@Composable
private fun FilePart(
    part: ContentPart,
    style: androidx.compose.ui.text.TextStyle,
    muted: androidx.compose.ui.graphics.Color,
    baseUrl: String?,
    signedUrl: ((String) -> String?)?,
    onOpenAttachment: ((ContentPart) -> Unit)?,
) {
    val isPrivate = MessageMedia.isPath(part.path)
    val url = if (isPrivate) part.path?.let { path -> signedUrl?.invoke(path) }
              else AvatarUrl.resolve(baseUrl, part.url)
    val isImage = part.fileType == "image" || part.mimeType?.startsWith("image/") == true ||
        part.url?.let { path -> IMAGE_EXTENSIONS.any { ext -> path.substringBefore('?').endsWith(ext, ignoreCase = true) } } == true
    val alt = part.altText?.takeIf { it.isNotBlank() }
    val open = onOpenAttachment?.let { callback -> { callback(part) } }

    if (isPrivate && url == null) {
        // Not signed yet, or the instance will not sign it for this account: say which, without
        // pretending the file is a picture that failed to load.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp), tint = muted)
            Spacer(Modifier.width(6.dp))
            Text(
                (part.fileName ?: alt ?: "Attachment") + " · " +
                    if (part.fileType == "image" || part.fileType == "video") "loading picture…" else "private file",
                style = MaterialTheme.typography.bodySmall, color = muted,
                modifier = Modifier.testTag("attachment-private-" + (part.path ?: "")),
            )
        }
        return
    }

    if (url != null && isImage) {
        val picture: @Composable () -> Unit = {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                contentDescription = alt ?: part.fileName ?: "Image attachment",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .heightIn(max = 320.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .testTag("attachment-image"),
            )
        }
        Column(Modifier.padding(vertical = 4.dp)) {
            if (open != null) {
                Surface(onClick = open, color = androidx.compose.ui.graphics.Color.Transparent, shape = MaterialTheme.shapes.medium) {
                    picture()
                }
            } else picture()
            if (alt != null) Text(alt, style = MaterialTheme.typography.bodySmall.copy(color = muted),
                modifier = Modifier.widthIn(max = 280.dp).padding(top = 2.dp))
        }
        return
    }
    val label = part.fileName ?: alt ?: (part.fileType ?: "file")
    val kind = when {
        part.fileType == "video" || part.mimeType?.startsWith("video/") == true -> "Video"
        part.fileType == "audio" || part.mimeType?.startsWith("audio/") == true -> "Audio"
        else -> "File"
    }
    val card: @Composable (Modifier) -> Unit = { modifier ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(vertical = 2.dp)) {
            Icon(
                when (kind) {
                    "Video" -> Icons.Default.PlayCircle
                    "Audio" -> Icons.Default.Audiotrack
                    else -> Icons.Default.AttachFile
                },
                contentDescription = null, tint = muted, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(kind + ": " + label, style = style, color = muted,
                modifier = Modifier.testTag("attachment-" + label))
            part.fileSize?.let { Text("  " + humanSize(it), style = MaterialTheme.typography.labelSmall, color = muted) }
        }
    }
    if (open != null) Surface(onClick = open, color = androidx.compose.ui.graphics.Color.Transparent) { card(Modifier) }
    else card(Modifier)
}

/** The extensions an image attachment may carry when the part does not say it is one. */
private val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp")

/** A byte count as a person reads it. */
internal fun humanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** `#RRGGBB` or `#RGB` as a role colour; anything else leaves the caller's own colour in place. */
internal fun parseHexColor(value: String): androidx.compose.ui.graphics.Color? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 && hex.length != 3) return null
    val full = if (hex.length == 3) hex.map { "" + it + it }.joinToString("") else hex
    val rgb = full.toLongOrNull(16) ?: return null
    return androidx.compose.ui.graphics.Color(0xFF000000 or rgb)
}
