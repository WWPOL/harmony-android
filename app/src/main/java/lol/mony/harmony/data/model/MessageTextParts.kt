package lol.mony.harmony.data.model

/**
 * Turning what a person typed into the parts the instance stores.
 *
 * A message body is a JSON array of parts, and a URL is a part of its own -- `{type:"url", url,
 * preview}` -- not text that happens to contain one. That is the reference client's contract
 * (`utils/unifiedContentProcessing.ts`): it splits typed prose into `text` and `url` parts before
 * the row is written. Two things follow from the shape, and both were broken here:
 *
 * - **The instance only previews `url` parts.** Its own trigger `process_local_link_previews`
 *   loops over `content` and skips anything whose `type` is not `url`, so a link stored inside a
 *   `text` part never gets a card -- not for this client and not for the web client that reads
 *   the same message.
 * - **Every client draws a `url` part as a link.** A row whose whole body is one `text` part is
 *   a row in which no client can make the link tappable without re-parsing prose.
 *
 * The grammar below is the reference's `maskedLinks` and `urlSplitting`, and it is deliberately
 * not a general markdown parser: this client still does not render markdown, and the parts it
 * writes have to be the parts it can draw.
 */
object MessageTextParts {
    /**
     * A masked link: `[label](https://host/path)`, or `[label](<https://host/path>)` for one that
     * must not be previewed (Discord's `<…>` spelling).
     *
     * The destination may hold one level of balanced parentheses, so a Wikipedia article whose
     * title has a parenthetical is a link rather than a link that stops at the `(`.
     */
    private val MASKED_LINK = Regex(
        """\[([^\[\]\n]+)]\((?:<(https?://[^\s<>]+)>|(https?://(?:[^\s()<>]|\([^\s()<>]*\))+))\)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A bare URL in prose: everything up to whitespace, a quote or a bracket, stopping before a
     * second URL glued to it (`…a.pnghttps://…b.png`, which a bridge sometimes writes).
     */
    private val BARE_URL = Regex("""https?://[^\s<>"']+?(?=https?://|\s|$|>)""", RegexOption.IGNORE_CASE)

    /** The punctuation a sentence leaves stuck to the end of a URL. */
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}\">'"

    /**
     * The bare URL that starts at [at] in [text], or null when none does.
     *
     * Shared by the splitter and the renderer, so the text a person typed and the parts it
     * becomes agree on where a URL ends -- and so a message *stored* as one text part (an older
     * message of this client's own, or one another client wrote) still draws its links.
     *
     * The end is the reference's: whitespace, a quote or a bracket, or the start of a second URL
     * glued to the first. Trailing sentence punctuation is given back to the sentence, but a
     * closing bracket the URL itself opened is kept, so an article whose title has a
     * parenthetical is one link rather than a link and a stray `)`.
     */
    fun bareUrlAt(text: String, at: Int): String? {
        if (at < 0 || at >= text.length) return null
        val match = BARE_URL.find(text, at) ?: return null
        if (match.range.first != at) return null
        var end = match.range.last + 1
        while (end > at) {
            val last = text[end - 1]
            if (last !in TRAILING_PUNCTUATION) break
            // A bracket the URL opened itself is part of it: `.../Foo_(bar)`.
            if (last == ')' && text.substring(at, end).count { it == '(' } >= text.substring(at, end).count { it == ')' }) break
            end--
        }
        return if (end > at) text.substring(at, end) else null
    }

    /** Fenced blocks and backtick spans: a URL inside one is code, not a link. */
    private val CODE = Regex("""```[\s\S]*?```|`[^`\n]+`""")

    /**
     * The parts a typed body is stored as: its prose as `text`, its links as `url`.
     *
     * A body with no link in it is one `text` part, exactly as before -- so this changes nothing
     * about the ordinary message, and an ordinary message keeps the single part its own tests
     * assert. A body that is only a link is one `url` part, with no empty text beside it.
     */
    fun parse(text: String): List<ContentPart> {
        if (text.isEmpty()) return emptyList()
        val codeRanges = CODE.findAll(text).map { it.range.first until it.range.last + 1 }.toList()
        fun inCode(at: Int) = codeRanges.any { at in it }

        val links = MASKED_LINK.findAll(text).filter { !inCode(it.range.first) }.toList()
        if (links.isEmpty()) return bareUrls(text, codeRanges)

        val parts = mutableListOf<ContentPart>()
        var last = 0
        for (link in links) {
            if (link.range.first > last) {
                parts += bareUrls(text.substring(last, link.range.first), emptyList())
            }
            val label = link.groupValues[1].trim()
            val bracketed = link.groupValues[2]
            val url = bracketed.ifBlank { link.groupValues[3] }
            if (label.isEmpty() || url.isEmpty()) {
                // Not a masked link after all: keep the text as it was typed.
                parts += bareUrls(link.value, emptyList())
            } else {
                parts += ContentPart(
                    type = "url",
                    url = url,
                    preview = bracketed.isEmpty(),
                    label = label,
                )
            }
            last = link.range.last + 1
        }
        if (last < text.length) parts += bareUrls(text.substring(last), emptyList())
        return parts.flatMap { part ->
            // A text slice that held nothing but the punctuation between two links is not a part.
            if (part.type == "text" && part.text.isNullOrEmpty()) emptyList() else listOf(part)
        }
    }

    /** [text] split at its bare URLs, leaving everything inside [codeRanges] alone. */
    private fun bareUrls(text: String, codeRanges: List<IntRange>): List<ContentPart> {
        if (text.isEmpty()) return emptyList()
        val parts = mutableListOf<ContentPart>()
        var last = 0
        for (match in BARE_URL.findAll(text)) {
            val at = match.range.first
            if (codeRanges.any { at in it }) continue
            // Discord's `<https://…>` links without previewing: the brackets are not stored.
            val bracketed = at > 0 && text[at - 1] == '<'
            val url = bareUrlAt(text, at) ?: continue
            val end = at + url.length
            val start = if (bracketed) at - 1 else at
            val segmentEnd = if (bracketed && end < text.length && text[end] == '>') end + 1 else end
            if (start > last) parts += ContentPart.text(text.substring(last, start))
            parts += ContentPart(type = "url", url = url, preview = !bracketed)
            last = segmentEnd
        }
        if (last < text.length) parts += ContentPart.text(text.substring(last))
        return parts.ifEmpty { listOf(ContentPart.text(text)) }
    }

    /**
     * The text a body is edited as: its prose, with each link written the way it was typed.
     *
     * Editing rewrites the whole body, so a link that went in as a part has to come back out as
     * source that parses to the same part -- `[label](url)` when it has a label, `<url>` when the
     * sender suppressed its preview, and the bare URL otherwise. Anything else would silently
     * turn every edited message's links into plain text.
     */
    fun source(content: List<ContentPart>): String = editTextOf(content)
}

/**
 * The source text a body is edited as, or written back as: see [MessageTextParts.source].
 *
 * A top-level function so a caller that has only the parts (a composer restoring a draft, a test
 * that builds them by hand) does not have to reach through the object.
 */
fun editTextOf(content: List<ContentPart>): String = content.joinToString("") { part ->
    when (part.type) {
        "text" -> part.text.orEmpty()
        "url" -> {
            val url = part.url.orEmpty()
            val label = part.label?.takeIf { it.isNotBlank() && !it.contains('[') && !it.contains(']') }
            when {
                label != null -> "[" + label + "](" + if (part.preview == false) "<" + url + ">" else url + ")"
                part.preview == false -> "<" + url + ">"
                else -> url
            }
        }
        // A part this client cannot write as text is dropped rather than guessed at: the caller
        // only edits a body whose parts are text and links (`Message.isEditable`).
        else -> ""
    }
}
