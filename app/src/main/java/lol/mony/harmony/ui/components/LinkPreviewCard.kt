package lol.mony.harmony.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import lol.mony.harmony.data.model.LinkPreview
import lol.mony.harmony.data.model.LinkUrl
import lol.mony.harmony.ui.theme.HarmonyShape
import lol.mony.harmony.ui.theme.LocalHarmonyColors

/**
 * A link's preview card, drawn to Harmony's own embed style.
 *
 * The style is not this file's invention: it is `src/assets/embed-previews.css` and
 * `src/components/embeds/LinkEmbedCard.vue` in the reference web client, and the numbers below
 * are that stylesheet's, read at the width this app runs at. At phone width -- the reference's
 * own `@media (max-width: 640px)` branch, which is the only branch a phone ever sees -- the card
 * is:
 *
 * - a **frame**: a 1dp border, the reference's 14px corner radius rounded to the app's shape
 *   scale ([HarmonyShape.small], 12dp) rather than a value invented for one component, filled
 *   with the raised surface;
 * - **a picture beside the text, not above it**: the `--has-image` card keeps a square 80dp
 *   thumbnail on its leading edge (the reference's 100px is its desktop rule) with the site, the
 *   title and the description centred beside it, each clamped to one line;
 * - **no picture** for a card whose page gave none: the site, the title and the description fill
 *   the frame, at the larger type the reference uses for that shape;
 * - **the picture as the content** for a GIF page or a link that answered with media
 *   (`--media`): the image is drawn at its own aspect, up to 320dp tall, with the text beneath;
 * - the site's **favicon on a white chip**, which is the reference's own trick -- a favicon is
 *   usually a dark glyph on transparency and would vanish on the app's dark theme;
 * - the site's **icon where the picture failed**, on a tint of the accent colour, rather than a
 *   hole where the picture should be.
 *
 * What it deliberately does not do is play a video: an `og:video` page is a card whose picture is
 * the video's poster. This app has no player on a message row, and a play control that cannot
 * play would be a control that lies. The parity audit keeps that gap open.
 *
 * @param preview the stored payload.
 * @param baseUrl the instance a relative `image`/`icon` resolves against.
 * @param onOpen what a tap does; the URL it opens is the payload's own destination.
 */
@Composable
fun LinkPreviewCard(
    preview: LinkPreview,
    baseUrl: String?,
    onOpen: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (!preview.isDrawable) return
    val target = preview.target
    val colors = LocalHarmonyColors.current
    val image = cardMedia(baseUrl, preview.image)
    val icon = cardMedia(baseUrl, preview.icon)

    // A picture that will not load must not leave a hole where the card's own text should be --
    // the reference falls back to the site's icon, and so does this. The icon may fail too; then
    // the frame carries a plain link glyph and the text still says where the card goes.
    var pictureFailed by remember(image) { mutableStateOf(false) }
    var iconFailed by remember(icon) { mutableStateOf(false) }
    val showPicture = image != null && !pictureFailed
    val shownIcon = icon?.takeIf { !iconFailed }
    val onPictureFailed: () -> Unit = { pictureFailed = true }
    val onIconFailed: () -> Unit = { iconFailed = true }

    val card: @Composable () -> Unit = {
        Column(
            modifier
                // The reference caps an embed at 480px and, on a phone, takes the width it is
                // given; `widthIn` before `fillMaxWidth` keeps the cap a cap.
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .clip(HarmonyShape.small)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.border, HarmonyShape.small)
                .padding(CARD_PADDING),
        ) {
            when {
                preview.isMediaCard -> MediaBody(preview, image, showPicture, shownIcon, onPictureFailed, onIconFailed)
                preview.hasSideThumbnail ->
                    SideThumbnailBody(preview, image, showPicture, shownIcon, onPictureFailed, onIconFailed)
                else -> TextBody(preview, shownIcon, onIconFailed)
            }
        }
    }

    if (onOpen != null && target.isNotBlank()) {
        Surface(onClick = { onOpen(target) }, color = Color.Transparent, shape = HarmonyShape.small) { card() }
    } else {
        card()
    }
}

/** The card's inner padding: the reference's `.85rem`, on the app's 4dp grid. */
private val CARD_PADDING = 14.dp

/** The gap between a side thumbnail and the text beside it: the reference's `.85rem`. */
private val CARD_GAP = 14.dp

/** The square a side thumbnail takes at phone width: the reference's 80px below 640px. */
private val SIDE_THUMBNAIL = 80.dp

/** The most a media card's picture may take: the reference's `max-height: 320px`. */
private val MEDIA_MAX_HEIGHT = 320.dp

/** The favicon's chip: a 16dp box, 1dp padding and 4dp corners -- the reference's own numbers. */
private val ICON_SIZE = 16.dp
private val ICON_CHIP = 4.dp

/** The site's icon where the picture failed: the reference draws it at 32px on the same chip. */
private val PLACEHOLDER_ICON = 32.dp

/** The card with a square picture on its leading edge: the phone shape of a page with an image. */
@Composable
private fun SideThumbnailBody(
    preview: LinkPreview,
    image: String?,
    showPicture: Boolean,
    icon: String?,
    onPictureFailed: () -> Unit,
    onIconFailed: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(SIDE_THUMBNAIL)
                .clip(HarmonyShape.extraSmall)
                // The reference's `.link-embed-card__media` backing: a 5% lift, so a picture with
                // transparency still reads as a picture rather than as a gap.
                .background(Color.White.copy(alpha = 0.05f))
                .testTag("embed-image"),
            contentAlignment = Alignment.Center,
        ) {
            if (showPicture) {
                AsyncImage(
                    model = pictureRequest(image), contentDescription = null,
                    contentScale = ContentScale.Crop, onError = { onPictureFailed() },
                    modifier = Modifier.size(SIDE_THUMBNAIL),
                )
            } else {
                PlaceholderIcon(icon, size = PLACEHOLDER_ICON, onError = onIconFailed)
            }
        }
        Spacer(Modifier.width(CARD_GAP))
        Column(Modifier.weight(1f)) {
            SiteRow(preview.site, icon, onIconFailed)
            Title(preview.shownTitle, maxLines = 1, size = 15.sp)
            Description(preview.description, maxLines = 1, size = 12.sp)
        }
    }
}

/** The card with no picture: the site, the title and the description, and nothing beside them. */
@Composable
private fun TextBody(preview: LinkPreview, icon: String?, onIconFailed: () -> Unit) {
    // A card whose picture was stored and could not be fetched keeps the reference's placeholder
    // strip above its text, so a page that has a picture still reads as one that has a picture.
    if (!preview.image.isNullOrBlank()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(HarmonyShape.extraSmall)
                .background(
                    Brush.radialGradient(
                        listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), Color.Transparent),
                    ),
                )
                .testTag("embed-placeholder"),
            contentAlignment = Alignment.Center,
        ) {
            PlaceholderIcon(icon, size = PLACEHOLDER_ICON, onError = onIconFailed)
        }
        Spacer(Modifier.height(10.dp))
    }
    SiteRow(preview.site, icon, onIconFailed, size = 13.sp)
    Title(preview.shownTitle, maxLines = 2, size = 16.sp)
    Description(preview.description, maxLines = 3, size = 13.sp)
}

/** A GIF page, or a link that answered with media: the picture is the content, the text follows. */
@Composable
private fun MediaBody(
    preview: LinkPreview,
    image: String?,
    showPicture: Boolean,
    icon: String?,
    onPictureFailed: () -> Unit,
    onIconFailed: () -> Unit,
) {
    if (showPicture) {
        AsyncImage(
            model = pictureRequest(image),
            // The picture is the content, so it carries the page's own words rather than being
            // decorative -- which is what the reference's media card does with its alt text.
            contentDescription = preview.title?.takeIf { it.isNotBlank() } ?: preview.site,
            contentScale = ContentScale.Fit,
            onError = { onPictureFailed() },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = MEDIA_MAX_HEIGHT)
                .clip(HarmonyShape.extraSmall)
                .testTag("embed-image"),
        )
        Spacer(Modifier.height(10.dp))
    }
    // A video's poster is drawn as a picture and no player is offered: this app cannot play one
    // here, and the audit says so rather than a play control that does nothing.
    SiteRow(preview.site, icon, onIconFailed, size = 12.sp)
    Title(preview.shownTitle, maxLines = 2, size = 15.sp)
    Description(preview.description, maxLines = 2, size = 12.sp)
}

/** The site's name behind its favicon: the card's first line, as the reference draws it. */
@Composable
private fun SiteRow(
    site: String,
    icon: String?,
    onIconFailed: () -> Unit,
    size: TextUnit = 12.sp,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            IconChip(icon, onError = onIconFailed)
        } else {
            Icon(
                Icons.Outlined.Link, null, Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            // Not upper-cased: the reference upper-cases only its `--compact` and `--thumbnail`
            // variants, and neither of those shapes is drawn here.
            site,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = size),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("embed-site"),
        )
    }
}

/** The page's own title, or its address when the page named none. */
@Composable
private fun Title(text: String, maxLines: Int, size: TextUnit) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.copy(fontSize = size),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp).testTag("embed-title"),
    )
}

/** The page's own summary under its title; absent when the page gave none. */
@Composable
private fun Description(text: String?, maxLines: Int, size: TextUnit) {
    val shown = text?.takeIf { it.isNotBlank() } ?: return
    Text(
        shown,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = size, lineHeight = (size.value * 1.4f).sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp).testTag("embed-description"),
    )
}

/**
 * A favicon on the reference's white chip.
 *
 * The chip is not decoration: a favicon is usually a dark glyph on transparency (the stylesheet
 * names GitHub's octocat), and without a light backing it disappears on the app's dark theme,
 * which is its default.
 */
@Composable
private fun IconChip(icon: String, onError: () -> Unit) {
    Box(
        Modifier
            .size(ICON_SIZE)
            .clip(RoundedCornerShape(ICON_CHIP))
            .background(Color.White)
            .padding(1.dp),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = icon, contentDescription = null, contentScale = ContentScale.Fit,
            onError = { onError() }, modifier = Modifier.size(ICON_SIZE),
        )
    }
}

/** The site's icon on the placeholder's chip, or the plain glyph when the page has none. */
@Composable
private fun PlaceholderIcon(icon: String?, size: androidx.compose.ui.unit.Dp, onError: () -> Unit) {
    if (icon != null) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = icon, contentDescription = null, contentScale = ContentScale.Fit,
                onError = { onError() }, modifier = Modifier.size(size),
            )
        }
    } else {
        Icon(
            Icons.Outlined.Link, null, Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** An image request with the crossfade every other picture in the app uses. */
@Composable
private fun pictureRequest(url: String?): ImageRequest =
    ImageRequest.Builder(LocalContext.current.applicationContext)
        .data(url).crossfade(true).build()

/**
 * The URL a card's picture or icon is fetched from, or null when it is not something to fetch.
 *
 * A payload's `image` is written by the instance's backend, so it is usually absolute; a card the
 * database's own trigger built names a storage path on the instance. What is never fetched is a
 * scheme that is not an image at all: a `javascript:` or `intent:` value in a page's `og:image`
 * must not reach an image loader, however it got into the payload. A local `file:`/`content:`
 * URL is kept, which is what the render tests point a card at.
 */
internal fun cardMedia(baseUrl: String?, value: String?): String? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (LinkUrl.isHttp(raw)) return raw
    val scheme = LinkUrl.scheme(raw)
    if (scheme != null) return raw.takeIf { scheme == "file" || scheme == "content" }
    val base = baseUrl?.trimEnd('/') ?: return null
    return if (raw.startsWith("/")) base + raw else base + "/" + raw
}
