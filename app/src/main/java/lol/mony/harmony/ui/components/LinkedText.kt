package lol.mony.harmony.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import lol.mony.harmony.data.model.LinkUrl
import lol.mony.harmony.data.model.MessageTextParts

/**
 * Opens [url] in whatever the device uses for it, and says whether it did.
 *
 * A message is written by other people, so only a scheme this app will hand to the platform ever
 * reaches it ([LinkUrl.isOpenable]): `javascript:`, `data:` and `intent:` are how a link in
 * somebody else's message becomes something running on this device. A URL the system cannot open
 * -- no browser installed, an app that refuses -- is reported rather than thrown, so one bad link
 * does not take the room down with it.
 */
internal fun openWebLink(uriHandler: UriHandler, url: String?): Boolean {
    val target = url?.trim()?.takeIf { LinkUrl.isOpenable(it) } ?: return false
    return runCatching { uriHandler.openUri(target); true }.getOrDefault(false)
}

/**
 * [text] drawn as one paragraph, with every link in it a real link.
 *
 * This is the shape the reference gives a link: an `<a>` inside the sentence, so the words before
 * and after it reflow around it and the line breaks fall where the text puts them. Drawing the
 * links as separate composables in a flow -- which is what this app did first -- lays each link
 * out as an atomic box, so a sentence wraps at the link rather than through it.
 *
 * The colour is the theme's link colour and the underline is the reference's `:hover` affordance
 * made permanent, because a phone has no hover to reveal it. A masked link reads as its label and
 * still announces its destination, which is what a screen reader needs when the label is prose.
 *
 * Nothing is added when [text] holds no link, so the common case is the plain [Text] it always
 * was: one node, one selection, no annotations.
 */
@Composable
internal fun LinkedText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    /** A destination the whole of [text] points at, which is how a masked link draws. */
    target: String? = null,
    onLink: ((String) -> Unit)? = null,
    /**
     * The tag that marks this as a *link*, applied only when it really is one.
     *
     * A tag on something that cannot be tapped would let a test -- and a reader of one -- believe
     * a `javascript:` URL is a link. The text is always in the tree; only the link is tagged.
     */
    linkTag: String? = null,
) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val spans = remember(text, target) { linkSpans(text, target).filter { LinkUrl.isOpenable(it.url) } }
    // A URL this app will not hand to the platform is still *named* -- a person has to be able to
    // see what a message says -- but it is not a link and it is not drawn as one: no colour, no
    // underline, nothing to tap. That is the reference's `url-link--unsafe`.
    if (spans.isEmpty()) {
        Text(text, style = style, color = color, modifier = modifier)
        return
    }
    val annotated = buildAnnotatedString {
        var at = 0
        for (span in spans) {
            if (span.start > at) append(text.substring(at, span.start))
            val shown = span.label?.takeIf { it.isNotBlank() } ?: span.url
            withLink(
                LinkAnnotation.Url(
                    url = span.url,
                    styles = TextLinkStyles(
                        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    ),
                    linkInteractionListener = { openWebLink(uriHandler, span.url); onLink?.invoke(span.url) },
                ),
            ) { append(shown) }
            at = span.end
        }
        if (at < text.length) append(text.substring(at))
    }
    // Two tags on one node would be one tag: `testTag` keeps the first it is given, so the
    // caller's modifier goes on the box and the link's own tag on the text inside it.
    if (linkTag != null) {
        Box(modifier) { Text(annotated, style = style, color = color, modifier = Modifier.testTag(linkTag)) }
    } else {
        Text(annotated, style = style, color = color, modifier = modifier)
    }
}

/** One link inside [text], as offsets into it, with the label the sender masked it with. */
internal data class LinkSpan(val start: Int, val end: Int, val url: String, val label: String?)

/**
 * Where the links are in [text], so the same split the sender's client used is the one drawn.
 *
 * The rules are the shared splitter's ([MessageTextParts.bareUrlAt]), so a URL this draws is
 * exactly a URL a send would have stored as its own part -- and a message stored by another
 * client, or by an older build of this one, still draws its links.
 *
 * [target] makes the whole string one link: that is a masked link, whose label is prose and whose
 * address is not on screen at all.
 */
internal fun linkSpans(text: String, target: String? = null): List<LinkSpan> {
    if (target != null) {
        return if (text.isEmpty()) emptyList() else listOf(LinkSpan(0, text.length, target, text))
    }
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<LinkSpan>()
    var at = 0
    while (at < text.length) {
        // A backtick span is quoted, not linked: a URL inside one is something a person wrote
        // about rather than something to follow.
        if (text[at] == '`') {
            val close = text.indexOf('`', at + 1)
            at = if (close > at) close + 1 else at + 1
            continue
        }
        if (text[at] == 'h' || text[at] == 'H') {
            val url = MessageTextParts.bareUrlAt(text, at)
            if (url != null) {
                out += LinkSpan(at, at + url.length, url, null)
                at += url.length
                continue
            }
        }
        at++
    }
    return out
}
