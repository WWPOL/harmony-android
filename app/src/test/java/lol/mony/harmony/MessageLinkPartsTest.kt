package lol.mony.harmony

import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.LinkPreview
import lol.mony.harmony.data.model.LinkUrl
import lol.mony.harmony.data.model.MessageTextParts
import lol.mony.harmony.data.model.editTextOf
import lol.mony.harmony.data.model.linkPreviews
import lol.mony.harmony.data.model.forPart
import lol.mony.harmony.data.model.HarmonyJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts a typed message is stored as, and the parts it is edited from.
 *
 * This is the regression for two reports at once: links that could not be tapped and links that
 * got no preview. Both come from the same place -- a sent message was stored as one `text` part
 * with the URL buried inside it -- so what is asserted here is the shape of the row: prose as
 * `text`, each URL as its own `url` part, with the preview flag the sender's spelling asked for.
 *
 * The grammar is the reference client's (`utils/maskedLinks.ts`, `utils/urlSplitting.ts`), and
 * the cases below are the ones it names: a masked link, Discord's `<url>`, a URL with a balanced
 * parenthetical, trailing sentence punctuation, and a URL in a code span.
 */
class MessageLinkPartsTest {

    private fun parts(text: String) = MessageTextParts.parse(text)

    private fun urls(text: String) = parts(text).filter { it.type == "url" }

    @Test fun proseWithNoLinkIsOneTextPart() {
        val parts = parts("just words, no link")
        assertEquals(1, parts.size)
        assertEquals("text", parts.single().type)
        assertEquals("just words, no link", parts.single().text)
    }

    @Test fun aBareUrlBecomesItsOwnPartWithThePreviewOn() {
        val parts = parts("look at https://example.test/post please")
        assertEquals(listOf("text", "url", "text"), parts.map { it.type })
        assertEquals("look at ", parts[0].text)
        assertEquals("https://example.test/post", parts[1].url)
        assertTrue("a typed URL is previewed unless the sender said otherwise", parts[1].preview!!)
        assertEquals(" please", parts[2].text)
    }

    @Test fun aMessageThatIsOnlyALinkIsOneUrlPart() {
        val parts = parts("https://example.test/only")
        assertEquals(1, parts.size)
        assertEquals("url", parts.single().type)
        assertEquals("https://example.test/only", parts.single().url)
    }

    /**
     * Discord's `<url>`: linked, but the sender asked for no preview.
     *
     * The brackets are syntax, not text, so they are not stored -- and `preview = false` is what
     * stops the instance fetching a card and what keeps a reader from seeing one.
     */
    @Test fun anAngleBracketedUrlIsLinkedWithoutAPreview() {
        val part = urls("see <https://example.test/quiet> now").single()
        assertEquals("https://example.test/quiet", part.url)
        assertFalse(part.preview!!)
        assertEquals("see  now", parts("see <https://example.test/quiet> now")
            .filter { it.type == "text" }.joinToString("") { it.text.orEmpty() })
    }

    @Test fun aMaskedLinkKeepsItsLabelAndItsDestination() {
        val parts = parts("read [the post](https://example.test/posts/1) today")
        val link = parts.single { it.type == "url" }
        assertEquals("the post", link.label)
        assertEquals("https://example.test/posts/1", link.url)
        assertTrue(link.preview!!)
        assertEquals("read ", parts.first().text)
        assertEquals(" today", parts.last().text)
    }

    @Test fun aMaskedLinkWithABracketedDestinationHasNoPreview() {
        val link = urls("read [the post](<https://example.test/posts/1>)").single()
        assertEquals("the post", link.label)
        assertEquals("https://example.test/posts/1", link.url)
        assertFalse(link.preview!!)
    }

    /** One level of balanced parentheses is part of the URL, as CommonMark's link grammar allows. */
    @Test fun aParentheticalInThePathStaysInTheLink() {
        val link = urls("see https://en.wikipedia.test/wiki/Foo_(bar) here").single()
        assertEquals("https://en.wikipedia.test/wiki/Foo_(bar)", link.url)
    }

    /** A full stop after a link belongs to the sentence, not to the link. */
    @Test fun sentencePunctuationAfterAUrlIsNotPartOfIt() {
        val link = urls("go to https://example.test/post.").single()
        assertEquals("https://example.test/post", link.url)
        assertEquals(".", parts("go to https://example.test/post.").last().text)
        val asked = urls("is it https://example.test/post?").single()
        assertEquals("https://example.test/post", asked.url)
    }

    /** A URL in backticks is quoted, not linked -- the reference leaves code spans alone. */
    @Test fun aUrlInACodeSpanStaysText() {
        val parts = parts("write `https://example.test/x` to mention it")
        assertEquals(1, parts.size)
        assertEquals("text", parts.single().type)
        assertTrue(parts.single().text!!.contains("https://example.test/x"))
    }

    @Test fun twoLinksInOneSentenceAreTwoParts() {
        val parts = parts("a https://one.test/x b https://two.test/y c")
        assertEquals(listOf("text", "url", "text", "url", "text"), parts.map { it.type })
        assertEquals(listOf("https://one.test/x", "https://two.test/y"), urls("a https://one.test/x b https://two.test/y c").map { it.url })
    }

    @Test fun aMailAddressIsNotSplitIntoALinkPart() {
        // Only http(s) is a stored link: a `mailto:` part is not something the instance previews
        // or another client's composer writes, and a body split at one would not round-trip.
        assertEquals(listOf("text"), parts("write to someone@example.test").map { it.type })
    }

    /**
     * Editing a message with a link in it has to come back as the same parts.
     *
     * The box is filled from [editTextOf] and saved through the same splitter, so a masked link
     * that came back as its bare URL would lose its label on every edit.
     */
    @Test fun aBodyRoundTripsThroughItsEditableText() {
        val cases = listOf(
            "read [the post](https://example.test/posts/1) today",
            "look at https://example.test/post please",
            "see <https://example.test/quiet> now",
            "https://example.test/only",
            "just words",
        )
        for (body in cases) {
            val stored = parts(body)
            val edited = editTextOf(stored)
            assertEquals("the source of $body", body, edited)
            assertEquals("the parts of $body", stored, parts(edited))
        }
    }

    @Test fun aLinkPartWithoutALabelIsEditedAsItsBareUrl() {
        val source = editTextOf(listOf(ContentPart(type = "url", url = "https://example.test/x", preview = true)))
        assertEquals("https://example.test/x", source)
    }

    @Test fun aSuppressedLinkPartIsEditedInAngleBrackets() {
        val source = editTextOf(listOf(ContentPart(type = "url", url = "https://example.test/x", preview = false)))
        assertEquals("<https://example.test/x>", source)
    }

    @Test fun anEmptyBodyHasNoParts() {
        assertTrue(parts("").isEmpty())
    }

    // ---- what a tap may open, and what a stored preview is filed under ----

    @Test fun onlyTheSchemesTheReferenceAllowsAreOpenable() {
        assertTrue(LinkUrl.isOpenable("https://example.test/x"))
        assertTrue(LinkUrl.isOpenable("http://example.test/x"))
        assertTrue(LinkUrl.isOpenable("mailto:someone@example.test"))
        assertTrue(LinkUrl.isOpenable("tel:+15550100"))
        // A message is written by somebody else: none of these may reach the platform.
        assertFalse(LinkUrl.isOpenable("javascript:alert(1)"))
        assertFalse(LinkUrl.isOpenable("data:text/html,<script>"))
        assertFalse(LinkUrl.isOpenable("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(LinkUrl.isOpenable("file:///etc/passwd"))
        assertFalse(LinkUrl.isOpenable("https:"))
        assertFalse(LinkUrl.isOpenable("//example.test/x"))
        assertFalse(LinkUrl.isOpenable(null))
    }

    @Test fun aStoredPreviewIsFoundByTheUrlItsPartCarries() {
        val embeds = HarmonyJson.parseToJsonElement(
            """{"embeds":{"https://example.test/post":{"url":"https://example.test/post",
                 "provider":"generic","title":"A post","siteName":"example.test"}}}""",
        ) as JsonObject
        val previews = embeds.linkPreviews()
        assertEquals(1, previews.size)
        val part = ContentPart(type = "url", url = "https://example.test/post", preview = true)
        assertEquals("A post", previews.forPart(part)!!.title)
        assertEquals("example.test", previews.forPart(part)!!.site)
    }

    /** The instance files a local post's preview under the URL with its trailing slashes gone. */
    @Test fun aPreviewFiledUnderATrailingSlashlessUrlIsStillFound() {
        val embeds = HarmonyJson.parseToJsonElement(
            """{"embeds":{"https://example.test/posts/1":{"url":"https://example.test/posts/1/",
                 "provider":"harmony-post","title":"Hello"}}}""",
        ) as JsonObject
        val previews = embeds.linkPreviews()
        val part = ContentPart(type = "url", url = "https://example.test/posts/1/", preview = true)
        assertEquals("Hello", previews.forPart(part)!!.title)
    }

    @Test fun anEmbedPartFindsItsPreviewById() {
        val embeds = HarmonyJson.parseToJsonElement(
            """{"embeds":{"pv1":{"url":"https://example.test/x","provider":"youtube","title":"A video"}}}""",
        ) as JsonObject
        val previews = embeds.linkPreviews()
        val part = ContentPart(type = "embed", url = "https://example.test/x", provider = "youtube", previewId = "pv1")
        assertEquals("A video", previews.forPart(part)!!.title)
    }

    /** `<url>` means no preview, however the message is keyed. */
    @Test fun aUrlPartWithNoPreviewGetsNoCard() {
        val embeds = HarmonyJson.parseToJsonElement(
            """{"embeds":{"https://example.test/x":{"url":"https://example.test/x","title":"A post"}}}""",
        ) as JsonObject
        val previews = embeds.linkPreviews()
        assertNull(previews.forPart(ContentPart(type = "url", url = "https://example.test/x", preview = false)))
    }

    /**
     * The web client fills `embedId` in from the URL (`ensureMessageEmbeds`), so a row it wrote
     * names its card twice: by the id and by the URL. Either has to find it.
     */
    @Test fun aUrlPartFindsItsPreviewByEmbedIdToo() {
        val embeds = HarmonyJson.parseToJsonElement(
            """{"embeds":{"https://example.test/x":{"url":"https://example.test/x","title":"By id"}}}""",
        ) as JsonObject
        val previews = embeds.linkPreviews()
        val part = ContentPart(type = "url", url = "https://example.test/elsewhere",
            preview = true, embedId = "https://example.test/x")
        assertEquals("By id", previews.forPart(part)!!.title)
    }

    @Test fun aFailedPreviewDrawsNothing() {
        val embeds = HarmonyJson.parseToJsonElement(
            """{"embeds":{"https://example.test/x":{"url":"https://example.test/x","failed":true,"title":"x"}}}""",
        ) as JsonObject
        val preview = embeds.linkPreviews().values.single()
        assertFalse("a page that could not be read is not a card", preview.isDrawable)
    }

    /**
     * A known provider is content even when the page named no title of its own.
     *
     * This is the reference's `isEmptyPreview` (`src/utils/embedPreview.ts`): the generic branch
     * is the only one that asks for a title, because a YouTube or Spotify payload *is* a player
     * upstream -- its provider is what the reader is being shown. Reading the rule as "no title,
     * no card" would drop every one of those previews.
     */
    @Test fun aKnownProviderIsACardEvenWithNoTitleOfItsOwn() {
        val embeds = HarmonyJson.parseToJsonElement(
            """{"embeds":{"https://youtube.test/watch?v=1":{"url":"https://youtube.test/watch?v=1",
                "provider":"youtube","player":{"url":"https://youtube.test/embed/1"}}}}""",
        ) as JsonObject
        val preview = embeds.linkPreviews().values.single()
        assertTrue("a provider card needs no title of its own", preview.isDrawable)
        assertEquals("https://youtube.test/watch?v=1", preview.shownTitle)
    }

    /**
     * A generic payload that says nothing but its own URL is not a card.
     *
     * A preview stored before `failed` existed carries the fetch error as its description and
     * the URL as its title; drawing that is drawing an empty box with the address in it.
     */
    @Test fun aGenericPreviewThatOnlyRepeatsItsUrlDrawsNothing() {
        val embeds = HarmonyJson.parseToJsonElement(
            """{"embeds":{"https://slow.test/":{"url":"https://slow.test/","normalizedUrl":"https://slow.test/",
                "provider":"generic","title":"https://slow.test/","description":"Blocked protocol: http:"}}}""",
        ) as JsonObject
        assertFalse(embeds.linkPreviews().values.single().isDrawable)
    }

    /**
     * A payload carrying provider HTML is content, even with no image, video or title.
     *
     * The reference's `isEmptyPreview` asks `payload.html` before it asks for a title. This client
     * runs none of that markup -- there is no WebView on a message row -- but the card still has
     * to appear, or a page that upstream draws a preview for draws nothing here at all.
     */
    @Test fun aPayloadWithProviderHtmlIsACard() {
        val embeds = HarmonyJson.parseToJsonElement(
            """{"embeds":{"https://example.test/x":{"url":"https://example.test/x","provider":"generic",
                "html":"<blockquote class=\"x\">a quote</blockquote>"}}}""",
        ) as JsonObject
        assertTrue(embeds.linkPreviews().values.single().isDrawable)
    }

    /**
     * A Wikipedia article is a card with a picture, not a GIF page.
     *
     * Wikimedia keeps still photographs as `.gif`, so the backend's "a `.gif` image means a GIF
     * page" heuristic marks a Wikipedia preview media-only. Drawn literally, an article's lead
     * photograph becomes a full-bleed 320dp animation frame with no title. The reference guards
     * it with `isWikimediaPage` (`src/utils/gifLinks.ts`), and so does this.
     */
    @Test fun aWikimediaPreviewStaysACardEvenWhenItIsMarkedMediaOnly() {
        val embeds = HarmonyJson.parseToJsonElement(
            """{"embeds":{"https://en.wikipedia.test/wiki/X":{"url":"https://en.wikipedia.org/wiki/X",
                "provider":"generic","title":"An article","mediaOnly":true,
                "image":"https://upload.wikimedia.org/x.gif"}}}""",
        ) as JsonObject
        val preview = embeds.linkPreviews().values.single()
        assertEquals(LinkPreview.MediaKind.NONE, preview.mediaKind)
        assertFalse("an article is not a media card", preview.isMediaCard)
        assertTrue("its picture is the card's thumbnail", preview.hasSideThumbnail)
    }

    /** A GIF page's picture *is* the content, so the card is the picture and its text follows. */
    @Test fun aGifPageIsAMediaCard() {
        val embeds = HarmonyJson.parseToJsonElement(
            """{"embeds":{"https://tenor.test/view/cat":{"url":"https://tenor.test/view/cat",
                "provider":"generic","title":"cat","mediaOnly":true,
                "image":"https://media.tenor.test/x.gif"}}}""",
        ) as JsonObject
        val preview = embeds.linkPreviews().values.single()
        assertEquals(LinkPreview.MediaKind.IMAGE, preview.mediaKind)
        assertTrue(preview.isMediaCard)
        assertFalse("the picture is the content, so it is not a thumbnail", preview.hasSideThumbnail)
    }

    /** A `.gif` image on an ordinary page is still a media card, even without the flag. */
    @Test fun aGifImageMakesAMediaCardWithoutTheFlag() {
        val embeds = HarmonyJson.parseToJsonElement(
            """{"embeds":{"https://example.test/dance":{"url":"https://example.test/dance",
                "title":"dance","image":"https://example.test/dance.gif"}}}""",
        ) as JsonObject
        assertEquals(LinkPreview.MediaKind.IMAGE, embeds.linkPreviews().values.single().mediaKind)
    }

    /** An ordinary page's picture is a thumbnail beside its text. */
    @Test fun anOrdinaryPictureIsASideThumbnail() {
        val embeds = HarmonyJson.parseToJsonElement(
            """{"embeds":{"https://example.test/post":{"url":"https://example.test/post","title":"A post",
                "image":"https://example.test/lead.jpg"}}}""",
        ) as JsonObject
        val preview = embeds.linkPreviews().values.single()
        assertEquals(LinkPreview.MediaKind.NONE, preview.mediaKind)
        assertTrue(preview.hasSideThumbnail)
    }

    /** A card with no picture at all draws its text alone. */
    @Test fun aCardWithNoPictureIsTextOnly() {
        val embeds = HarmonyJson.parseToJsonElement(
            """{"embeds":{"https://example.test/post":{"url":"https://example.test/post","title":"A post"}}}""",
        ) as JsonObject
        val preview = embeds.linkPreviews().values.single()
        assertFalse(preview.isMediaCard)
        assertFalse(preview.hasSideThumbnail)
    }

    @Test fun aMessageWithNoMetadataHasNoPreviews() {
        assertTrue(((null as JsonObject?)).linkPreviews().isEmpty())
    }

    @Test fun theCardIsHeadedByTheSiteNameElseTheHost() {
        val named = HarmonyJson.parseToJsonElement(
            """{"embeds":{"https://www.example.test/x":{"url":"https://www.example.test/x","siteName":"Example"}}}""",
        ) as JsonObject
        assertEquals("Example", named.linkPreviews().values.single().site)
        val unnamed = HarmonyJson.parseToJsonElement(
            """{"embeds":{"https://www.example.test/x":{"url":"https://www.example.test/x"}}}""",
        ) as JsonObject
        assertEquals("example.test", unnamed.linkPreviews().values.single().site)
    }
}
