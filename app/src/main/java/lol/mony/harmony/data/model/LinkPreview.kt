package lol.mony.harmony.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A link's preview card, as the instance stores it in `messages.metadata.embeds`.
 *
 * The reference web client's `EmbedPayload` (`src/types/chat.ts`) is the contract: the database
 * trigger `process_local_link_previews` builds a card for a post on the instance itself, and the
 * federation backend's `LinkPreviewService` writes one for every other URL through
 * `update_message_embeds`. The card is keyed by the URL the message's `url` part carries -- or,
 * for a local post, by that URL with its trailing slashes removed -- and the payload names the
 * same URL back in [url]/[normalizedUrl].
 *
 * Everything here is optional because every provider fills in a different subset: a generic page
 * has a title and a description, a GIF page is media only, a music link has a player and a
 * thumbnail, and a page that could not be read has [failed]. A field this build does not draw
 * (an X post's own thread, a fediverse post's attachments) is still kept in [raw] rather than
 * dropped, so a later change can read it without the client having thrown it away.
 */
data class LinkPreview(
    val url: String = "",
    val normalizedUrl: String = "",
    /** `generic`, `youtube`, `spotify`, `harmony-post`, `x`, ... -- what draws the card. */
    val provider: String = "generic",
    val title: String? = null,
    val description: String? = null,
    /** The site's own name for itself ("YouTube"), which heads the card. */
    val siteName: String? = null,
    /** The card's picture, an absolute URL or one relative to the instance. */
    val image: String? = null,
    /** The site's favicon, shown beside the site's name. */
    val icon: String? = null,
    /** The colour a social author posts in, `#RRGGBB`; a generic card draws no tint of its own. */
    val color: String? = null,
    /**
     * The provider's own embed markup (`oEmbed` HTML).
     *
     * This client runs none of it -- there is no WebView on a message row -- but the field is
     * still read, because it is what the reference's `isEmptyPreview` counts as content: a page
     * whose preview is HTML only is a card upstream, and drawing nothing here would be a card
     * missing for no reason a reader can see.
     */
    val html: String? = null,
    /** The preview is media only (a GIF page): the picture is the content, not a card. */
    val mediaOnly: Boolean = false,
    /** A playable video's own file (`og:video`), when the page named one. */
    val videoUrl: String? = null,
    /** The provider's player page, which this client names rather than embedding. */
    val playerUrl: String? = null,
    /** The page could not be read: the instance stores no card, and this draws none. */
    val failed: Boolean = false,
    /** Where the link led, when it was a short link: the card opens this. */
    val canonicalUrl: String? = null,
    /** The payload exactly as stored, so nothing a later change needs was lost here. */
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    /** The address a tap opens: the resolved destination, else the URL that was previewed. */
    val target: String
        get() = canonicalUrl?.takeIf { it.isNotBlank() } ?: url.takeIf { it.isNotBlank() }
            ?: normalizedUrl

    /**
     * What heads the card: the site's own name, else its host, else the provider.
     *
     * The reference's `displaySiteName` does exactly this, because a generic page usually has no
     * `og:site_name` and a card headed "generic" tells a person nothing about where the link goes.
     */
    val site: String
        get() = siteName?.takeIf { it.isNotBlank() }
            ?: LinkUrl.host(url)?.removePrefix("www.")
            ?: provider

    /** True when there is anything worth drawing; a failed or empty payload draws nothing. */
    val isDrawable: Boolean get() = !isEmptyPreview

    /**
     * True when the payload has nothing of its own to show, which is the reference's
     * `isEmptyPreview` (`src/utils/embedPreview.ts`) read in this client's terms.
     *
     * A page that could not be read is not a card, and neither is a *generic* payload that says
     * nothing beyond the URL it was built from -- including one stored before `failed` existed,
     * whose title is the URL and whose description held the fetch error. A known provider always
     * shows, because the provider is itself what a reader is being shown: a YouTube or Spotify
     * payload with no title of its own is still a player upstream, not an empty box.
     */
    val isEmptyPreview: Boolean
        get() {
            if (failed) return true
            if (provider != GENERIC_PROVIDER) return false
            if (!image.isNullOrBlank() || !videoUrl.isNullOrBlank() || !html.isNullOrBlank()) return false
            val heading = title?.trim().orEmpty()
            return heading.isEmpty() || heading == url || heading == normalizedUrl
        }

    /**
     * What a card draws in place of a thumbnail, decided the way the reference's
     * `linkPreviewMedia` decides it.
     *
     * A GIF page, or a link that answered with a video file, is media: the picture (or the
     * player) *is* the content, so it is drawn at its own size with the text under it rather than
     * as a cropped square beside it. The decision is taken from the file's extension as well as
     * from `mediaOnly`, because previews are stored in the message and rows written before the
     * backend set the flag still have to draw as media.
     *
     * A Wikimedia page is the one exception: those projects keep still photographs as `.gif`, so
     * a `.gif` preview there is an ordinary card's thumbnail and not a GIF page. Without this a
     * Wikipedia link draws as a full-width animation frame.
     */
    val mediaKind: MediaKind
        get() {
            if (isWikimediaPage) return MediaKind.NONE
            if (mediaOnly && !videoUrl.isNullOrBlank()) return MediaKind.VIDEO
            val picture = image?.trim().orEmpty()
            if (picture.isEmpty()) return MediaKind.NONE
            if (MEDIA_FILE_VIDEO.containsMatchIn(picture)) return MediaKind.VIDEO
            if (mediaOnly || MEDIA_FILE_IMAGE.containsMatchIn(picture)) return MediaKind.IMAGE
            return MediaKind.NONE
        }

    /** True when the media is the card, not a thumbnail beside its text. */
    val isMediaCard: Boolean get() = mediaKind != MediaKind.NONE

    /** True when the card is a small square picture with the text beside it (the phone layout). */
    val hasSideThumbnail: Boolean get() = !isMediaCard && !image.isNullOrBlank()

    /**
     * True when this preview is a Wikimedia project's page.
     *
     * The same host list as the backend's `WIKIMEDIA_HOST` and the reference's `isWikimediaPage`:
     * the projects themselves and every subdomain of them, and nothing that merely has
     * `wikipedia` in its name.
     */
    val isWikimediaPage: Boolean get() = isWikimediaHost(LinkUrl.host(url) ?: LinkUrl.host(normalizedUrl))

    /** The title to draw, falling back to the URL as the reference's card does. */
    val shownTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: url.takeIf { it.isNotBlank() } ?: normalizedUrl

    /** What a card's media slot draws: nothing, a picture, or a video the page offered. */
    enum class MediaKind { NONE, IMAGE, VIDEO }

    companion object {
        /** The provider the instance uses for a page with no provider of its own. */
        const val GENERIC_PROVIDER = "generic"

        /** A media file a preview's `image` names: the picture is a video, so it plays or says so. */
        private val MEDIA_FILE_VIDEO = Regex("""\.(mp4|webm|mov)(\?|#|$)""", RegexOption.IGNORE_CASE)

        /** A media file that reads as a GIF page's content rather than as a thumbnail. */
        private val MEDIA_FILE_IMAGE = Regex("""\.gif(\?|#|$)""", RegexOption.IGNORE_CASE)
    }
}

/** The Wikimedia projects' own domains, as the backend's `WIKIMEDIA_HOST` spells them. */
private val WIKIMEDIA_HOST = Regex(
    """(^|\.)(wikipedia|wikimedia|wiktionary|wikiquote|wikibooks|wikisource|wikivoyage|wikinews|wikiversity|wikidata)\.org$""",
)

/** True when [host] is one of the Wikimedia projects, which keep still photos as GIF. */
internal fun isWikimediaHost(host: String?): Boolean {
    val value = host?.trim()?.lowercase() ?: return false
    return WIKIMEDIA_HOST.containsMatchIn(value)
}


/**
 * URL rules shared by the message body, the composer's splitter and the card.
 *
 * The reference has three of these and they are not interchangeable: `sanitizeUrl` decides what
 * may be a link at all, `normalizeEmbedUrl` is the key a stored preview is filed under, and the
 * card's host is what heads it. Keeping them in one place is what stops a link that draws from
 * being a link that cannot be opened.
 */
object LinkUrl {
    /** The schemes a tap may hand to the platform: the reference's safe set, less `blob:`. */
    private val OPENABLE = setOf("http", "https", "mailto", "tel")

    /** `scheme:` at the start of a URL, without the `//` an authority scheme carries. */
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

    /** The scheme of [url] in lower case, or null when it names none. */
    fun scheme(url: String?): String? =
        url?.trim()?.let { SCHEME.find(it)?.groupValues?.get(1)?.lowercase() }

    /**
     * True when a tap on [url] may be handed to the platform.
     *
     * A message is written by somebody else, so `javascript:`, `data:` and an `intent:` URL must
     * never reach `Intent`/`Uri`. This is the reference's `SAFE_URL_SCHEMES` check, which every
     * link in its body passes through before it becomes an `href`.
     */
    fun isOpenable(url: String?): Boolean {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val scheme = scheme(raw) ?: return false
        if (scheme !in OPENABLE) return false
        val rest = raw.substringAfter(':', "")
        return when (scheme) {
            // A scheme-only string (`https:`) is not a link, and a bare path has no scheme.
            "mailto", "tel" -> rest.isNotBlank()
            else -> rest.startsWith("//") && rest.length > 2
        }
    }

    /** True when [url] is an absolute http(s) URL, the only kind a preview is stored for. */
    fun isHttp(url: String?): Boolean = scheme(url) == "http" || scheme(url) == "https"

    /** The host of [url] in lower case, or null when it has none. */
    fun host(url: String?): String? {
        val raw = url?.trim()?.takeIf { isHttp(it) } ?: return null
        val authority = raw.substringAfter("//", "").substringBefore('/').substringBefore('?')
            .substringBefore('#').substringAfter('@')
        // A port is not part of the name a person reads.
        val host = authority.substringBefore(':').trim().trimEnd('.').lowercase()
        return host.takeIf { it.isNotEmpty() }
    }

    /**
     * The key a stored preview is filed under, as the reference's `normalizeEmbedUrl` builds it.
     *
     * A scheme-less `example.test/x` is read as https, the host and scheme are lower-cased, a
     * default port is dropped and the URL parser's own spelling is used -- which adds the `/` a
     * bare host needs. A trailing slash is *kept* here: this is the reference's display key. The
     * database's own key is [storedKey], which strips it.
     */
    fun normalize(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withScheme = if (SCHEME.containsMatchIn(raw)) raw else "https://" + raw
        if (!isHttp(withScheme)) return null
        // OkHttp's parser is the one the rest of the app already resolves URLs with
        // (`AvatarUrl`), and it spells a URL the way the browser does: a lower-case scheme and
        // host, no default port, and the `/` a bare host needs.
        return withScheme.toHttpUrlOrNull()?.toString()
    }

    /**
     * The key the instance's own preview trigger files a local post under: the URL with its
     * trailing slashes removed (`normalize_embed_url`).
     */
    fun storedKey(url: String?): String? = url?.trim()?.takeIf { it.isNotEmpty() }?.trimEnd('/')

    /** The spellings a stored preview for [url] may be filed under, most specific first. */
    fun keys(url: String?): List<String> {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return listOfNotNull(raw, storedKey(raw), normalize(raw)).distinct()
    }
}

/**
 * The previews a message carries, keyed the way the instance stored them.
 *
 * `messages.metadata` is a `jsonb` column the instance writes and a client reads; the previews
 * live under its `embeds` key. A message with no metadata, or metadata whose `embeds` is not an
 * object (an older row, a bot's own payload), has no previews rather than an error.
 */
fun JsonElement?.linkPreviews(): Map<String, LinkPreview> {
    val embeds = (this as? JsonObject)?.get("embeds") as? JsonObject ?: return emptyMap()
    val out = LinkedHashMap<String, LinkPreview>(embeds.size)
    for ((key, element) in embeds) {
        val payload = element as? JsonObject ?: continue
        out[key] = LinkPreviewSerializer.fromJson(payload)
    }
    return out
}

/**
 * The preview that belongs to [part], if the message carries one.
 *
 * Three ways the instance ties a card to a part, all of which the reference's
 * `resolveEmbedPayload` honours: an `embed` part names its payload in `previewId`, a `url` part
 * names it in `embedId` (which the web client fills in from the URL), and everything else is
 * keyed by the URL the part carries. A `url` part whose sender asked for no preview (`<url>`)
 * gets none, however the message is keyed.
 */
fun Map<String, LinkPreview>.forPart(part: ContentPart): LinkPreview? {
    if (isEmpty()) return null
    val byId = when (part.type) {
        "embed" -> part.previewId
        "url" -> part.embedId
        else -> null
    }
    if (byId != null) this[byId]?.let { return it }
    if (part.type == "url" && part.preview == false) return null
    for (key in LinkUrl.keys(part.url)) this[key]?.let { return it }
    // A stored payload names its own URL; the key is not always the spelling the part carries.
    return values.firstOrNull { candidate ->
        LinkUrl.keys(candidate.url).any { it in LinkUrl.keys(part.url) } ||
            LinkUrl.keys(candidate.normalizedUrl).any { it in LinkUrl.keys(part.url) }
    }
}

internal object LinkPreviewSerializer : KSerializer<LinkPreview> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("LinkPreview")

    override fun deserialize(decoder: Decoder): LinkPreview {
        val input = decoder as? JsonDecoder ?: return LinkPreview(url = decoder.decodeString())
        return when (val element = input.decodeJsonElement()) {
            is JsonObject -> fromJson(element)
            is JsonPrimitive -> LinkPreview(url = element.contentOrNull.orEmpty())
            else -> LinkPreview()
        }
    }

    override fun serialize(encoder: Encoder, value: LinkPreview) {
        val output = encoder as? JsonEncoder ?: run {
            encoder.encodeString(value.url); return
        }
        output.encodeJsonElement(if (value.raw.isNotEmpty()) value.raw else toJson(value))
    }

    fun fromJson(payload: JsonObject): LinkPreview = LinkPreview(
        url = payload.stringOrNull("url").orEmpty(),
        normalizedUrl = payload.stringOrNull("normalizedUrl") ?: payload.stringOrNull("normalized_url").orEmpty(),
        provider = payload.stringOrNull("provider") ?: "generic",
        title = payload.stringOrNull("title"),
        description = payload.stringOrNull("description"),
        siteName = payload.stringOrNull("siteName") ?: payload.stringOrNull("site_name"),
        image = payload.stringOrNull("image"),
        icon = payload.stringOrNull("icon"),
        color = payload.stringOrNull("color"),
        html = payload.stringOrNull("html"),
        mediaOnly = payload.booleanOrNull("mediaOnly") ?: payload.booleanOrNull("media_only") ?: false,
        videoUrl = (payload["video"] as? JsonObject)?.stringOrNull("url"),
        playerUrl = (payload["player"] as? JsonObject)?.stringOrNull("url"),
        failed = payload.booleanOrNull("failed") ?: false,
        canonicalUrl = payload.stringOrNull("canonicalUrl") ?: payload.stringOrNull("canonical_url"),
        raw = payload,
    )

    fun toJson(value: LinkPreview): JsonObject = buildJsonObject {
        put("url", value.url)
        put("normalizedUrl", value.normalizedUrl)
        put("provider", value.provider)
        value.title?.let { put("title", it) }
        value.description?.let { put("description", it) }
        value.siteName?.let { put("siteName", it) }
        value.image?.let { put("image", it) }
        value.icon?.let { put("icon", it) }
        value.color?.let { put("color", it) }
        value.html?.let { put("html", it) }
        if (value.mediaOnly) put("mediaOnly", true)
        value.videoUrl?.let { url -> put("video", buildJsonObject { put("url", url) }) }
        value.playerUrl?.let { url -> put("player", buildJsonObject { put("url", url) }) }
        if (value.failed) put("failed", true)
        value.canonicalUrl?.let { put("canonicalUrl", it) }
    }
}
