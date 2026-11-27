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
import lol.mony.harmony.data.model.LinkPreview
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.data.model.MessageTextParts
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
 *
 * **It is not selectable, deliberately.** A body used to be wrapped in a `SelectionContainer`,
 * and on a phone that made the words fight the row for the same gesture: a long press both
 * selected a word and opened the message actions, and a double tap selected a word where a
 * messaging app has nothing. The row's long press belongs to the row ([MessageRow] opens the
 * actions with it), so selection here is reached on purpose instead -- the actions sheet's
 * "Select text" draws the same body in a container ([SelectTextSheetContent]), and "Copy text"
 * copies all of it. See [lol.mony.harmony.ui.screens.chat.MessageRow] and docs/ux-fixes.md.
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
    /**
     * What tapping a link does. Null opens it with the platform's own handler.
     *
     * A message is written by somebody else, so the URL that reaches this is already known to be
     * http(s) or a mail/tel address ([lol.mony.harmony.data.model.LinkUrl.isOpenable]); anything
     * else is drawn as text and is not tappable at all.
     */
    onOpenLink: ((String) -> Unit)? = null,
) {
    val sizes = LocalSizes.current
    val style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize.sp)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val color = if (message.isSystem) muted else MaterialTheme.colorScheme.onSurface

    if (encryptedNotice != null) {
        Text(encryptedNotice, style = style, color = muted, modifier = modifier)
        return
    }
    if (message.content.isEmpty()) {
        Text("This message has no content.", style = style, color = muted, modifier = modifier)
        return
    }

    // A link opens with the platform's own handler unless the caller has its own idea (a test,
    // or a screen that wants to report that nothing can open it). Resolved once here so the
    // parts do not each have to hold a platform lookup.
    val platform = androidx.compose.ui.platform.LocalUriHandler.current
    val open: (String) -> Unit = onOpenLink ?: { url -> runCatching { platform.openUri(url) } }

    // Text and the inline parts between it share one flow, so `shipping :blobcat: today` is
    // one sentence with the emoji in it, as the web client draws it. Only a part that stands on
    // its own -- an attachment, a sticker, a poll, a system event, a link preview card -- breaks
    // the line.
    Column(modifier = modifier) {
        for (run in runs(message.content)) {
            if (run.block) {
                BlockPart(run.parts.first(), style, muted, baseUrl, signedUrl, onOpenAttachment,
                    preview = message.previewFor(run.parts.first()), onOpenLink = open)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    for (part in run.parts) {
                        InlinePart(message.id, part, style, color, sizes.messageSp, muted, baseUrl, emojis,
                            preview = message.previewFor(part), onOpenLink = open)
                    }
                }
                // A link's card is not part of the sentence: it stands under it, one per link,
                // the way the reference's `ProviderEmbedSwitch` sits below the line that names
                // the URL. Drawn here rather than in the flow so a card never wraps mid-sentence.
                for (part in run.parts) {
                    val preview = message.previewFor(part)?.takeIf { it.isDrawable } ?: continue
                    LinkPreviewCard(preview, baseUrl, open,
                        modifier = Modifier.padding(top = 4.dp).testTag("embed-card-" + message.id))
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

/** An emoji image, a mention, a hashtag or a link: drawn inside the line it belongs to. */
@Composable
private fun InlinePart(messageId: String, part: ContentPart, style: androidx.compose.ui.text.TextStyle,
                       color: androidx.compose.ui.graphics.Color, messageSp: Double,
                       muted: androidx.compose.ui.graphics.Color, baseUrl: String?,
                       emojis: Map<String, Emoji>, preview: LinkPreview?, onOpenLink: (String) -> Unit) {
    when (part.type) {
        // Text is not drawn as one run: the reference resolves `:shortcode:` inside it to the
        // emoji's picture (`renderMarkdownToHTML`'s `emoji` token), so a message someone typed a
        // server emoji into draws the emoji rather than its name.
        "text" -> TextWithEmoji(part.text.orEmpty(), style, color, messageSp, baseUrl, emojis,
            Modifier.testTag("message-" + messageId), onOpenLink)
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
        // A link is a link: the label a sender masked it with, else the URL itself, and a tap
        // opens it. What used to be here was the same text with nothing behind it, so a link a
        // person could read was a link they could not follow.
        //
        // It is drawn through [LinkedText] -- one `Text` whose whole content is annotated as a
        // link -- rather than as a box around the words, because that is the shape the reference
        // gives it: an `<a>` in the sentence, in the link colour and underlined. A box would
        // break the line at the link rather than let the sentence run through it.
        //
        // When the message carries a card for this URL the card follows the sentence as a
        // block, so the sentence keeps a reference to what it is about and draws it muted --
        // otherwise the same address is picked out twice, once as a link and once as a title.
        // The tag is the same either way: a reader, and a test, should not have to know whether
        // the instance happened to store a preview for this link.
        "url" -> {
            val url = part.url.orEmpty()
            val label = part.label?.takeIf { it.isNotBlank() }
            LinkedText(
                text = label ?: url,
                style = style,
                color = if (preview?.isDrawable == true) muted else MaterialTheme.colorScheme.onSurface,
                target = url,
                onLink = onOpenLink,
                linkTag = "link-" + messageId,
            )
        }
        else -> Text(part.asText, style = style, color = color)
    }
}

/**
 * One piece of a text part: literal text, a `:shortcode:` the instance may have an emoji for, or
 * a bare URL.
 *
 * The reference's parser produces the first two, and its renderer draws an `emoji` token as the
 * emoji's image when it can resolve the name, else as the literal `:name:` it was written as.
 * The third is this client's addition: a URL inside a `text` part still has to be a link. A
 * message written by this client stores its URLs as `url` parts, but one written by an older
 * build of it, or by any other client, may not -- and a link a person can read but cannot tap is
 * the bug this file exists to fix.
 */
internal sealed interface TextToken {
    data class Literal(val text: String) : TextToken
    data class Shortcode(val name: String) : TextToken
    data class Link(val url: String) : TextToken
}

/**
 * Splits a text part into literal text, the shortcodes in it, and the URLs in it.
 *
 * The rules are the reference's (`src/utils/markdownParser.ts`): a shortcode is `:name:` of
 * `[a-zA-Z0-9_+~-]` (the `~N` suffix disambiguates two emoji of one name), a bridged emoji is
 * `:discord:[a:]<name>:<snowflake>:`, and a code span is left alone -- `` `:cat:` `` is how a
 * person writes about a shortcode rather than using one, and a URL in backticks is quoted rather
 * than linked. `:` alone, or an empty pair of colons, is text.
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
        // A code span runs to its closing backtick, and nothing inside it is an emoji or a link.
        if (text[index] == '`') {
            val close = text.indexOf('`', index + 1)
            index = if (close > index) close + 1 else index + 1
            continue
        }
        // A URL runs to the end the splitter gives it, so what is linked here is exactly what
        // would have been stored as a `url` part had the message been sent from this build.
        val link = if (text[index] == 'h' || text[index] == 'H') {
            MessageTextParts.bareUrlAt(text, index)
        } else null
        if (link != null) {
            flushLiteral(index)
            out += TextToken.Link(link)
            index += link.length
            literalStart = index
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
    onOpenLink: (String) -> Unit,
) {
    val tokens = remember(text) { textTokens(text) }
    // A part with no shortcode in it -- most of them -- is one Text, with any URL in it as an
    // annotation on the sentence: that is what makes the words before and after a link reflow
    // around it, as the reference's `<a>` does. A part with neither a shortcode nor a link is
    // the plain Text it always was, so the common case costs nothing.
    if (tokens.none { it is TextToken.Shortcode }) {
        // A single link in the prose is tagged by its address, so a test can name the node it is
        // in without knowing where in the sentence the sender put it.
        val onlyLink = remember(text) { linkSpans(text).singleOrNull()?.url }
        LinkedText(text, style, color, modifier, onLink = onOpenLink,
            linkTag = onlyLink?.let { "text-link-" + it })
        return
    }
    FlowRow(modifier = modifier, itemVerticalAlignment = Alignment.CenterVertically) {
        for (token in tokens) when (token) {
            is TextToken.Literal -> LinkedText(token.text, style, color, onLink = onOpenLink)
            // A URL in the prose of a message somebody else stored: the same link a `url`
            // part draws, because a person reading a sentence does not know or care which
            // part kind the client that wrote it chose.
            is TextToken.Link -> LinkedText(token.url, style, color, target = token.url, onLink = onOpenLink)
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
    preview: LinkPreview?,
    onOpenLink: (String) -> Unit,
) {
    val caption = MaterialTheme.typography.bodySmall.copy(color = muted)
    // A card is a box, not a row of glyphs: it draws its own padding and its own tap target,
    // so it is not wrapped in the row every other block sits in.
    if (part.type == "embed") {
        // The instance stores an embed part for a preview it has, and this client draws that
        // preview. An embed whose payload this client did not get -- a message read before the
        // backend wrote it, or one a bot wrote itself -- says what it points at rather than
        // drawing nothing, which is what the old sentence did for *every* embed.
        val drawable = preview?.takeIf { it.isDrawable }
        if (drawable != null) {
            LinkPreviewCard(drawable, baseUrl, onOpenLink,
                modifier = Modifier.padding(vertical = 4.dp).testTag("embed-card-" + part.previewId))
        } else {
            // No payload to draw: name what the part points at, in the muted colour, and leave it
            // tappable -- the address is the only thing there is to act on.
            LinkedText(
                text = part.url.orEmpty(), style = style, color = muted,
                modifier = Modifier.padding(vertical = 2.dp).testTag("embed"),
                target = part.url.orEmpty(), onLink = onOpenLink,
            )
        }
        return
    }
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
    // A video is played, not named. It used to fall through to the card below -- "Video:
    // standup.mp4" with a play glyph on it -- which is a label for a thing the app could not do.
    // The player is Android's own, inline in the row, and it waits for a tap to play.
    if (url != null && part.isVideoPart()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            VideoPlayer(
                url = url,
                label = alt ?: part.fileName ?: "Video attachment",
                key = part.path ?: part.url ?: url,
                modifier = Modifier.testTag("attachment-video"),
            )
            val caption = alt ?: part.fileName
            if (caption != null) Text(
                caption,
                style = MaterialTheme.typography.bodySmall.copy(color = muted),
                modifier = Modifier.widthIn(max = 320.dp).padding(top = 2.dp),
            )
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
