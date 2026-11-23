# Links and link previews

Two phone reports, one shape: *"I can't click on links sent in a message. Also there are no
embeds on links."* Both came from the same place, and neither was a drawing bug.

## What was wrong

A message's body is a JSON array of parts, and a URL is a part of its own -- `{type: "url",
url, preview, label?}`. This client did not do that, in either direction:

- **On the way out**, `ChatRepository.insertMessage` stored whatever a person typed as one
  `{type: "text"}` part, with the URL buried inside it. The instance's own preview trigger
  (`process_local_link_previews`) loops over `content` and skips every part whose `type` is not
  `url`, so a link sent from this app was never previewed -- not for this client, and not for
  the web client reading the same row. The federation backend's `enrichMessageLinkPreviews`
  has the same filter.
- **On the way in**, a `url` part was drawn as `Text(part.asText)`: the label or the URL, in the
  link colour, with nothing behind it. The row's only gesture is a long press, so a tap on a
  link did nothing at all. And a part that *had* a preview drew the literal sentence
  `Link preview: <url>`: the payload in `messages.metadata.embeds` was never read.

## What it does now

**Sending.** `MessageTextParts.parse` splits typed text into the parts the instance stores,
using the reference client's grammar (`src/utils/maskedLinks.ts`, `src/utils/urlSplitting.ts`):

| Typed | Stored |
| --- | --- |
| `look at https://example.test/post please` | `text`, `url`, `text` |
| `https://example.test/only` | one `url` part |
| a masked link, `read [the post](https://example.test/p)` | `url` with `label` and `preview: true` |
| `see <https://example.test/quiet>` | `url` with `preview: false` (Discord's spelling) |
| `` `https://example.test/x` `` | text: a URL in a code span is quoted, not linked |
| `just words` | one `text` part, exactly as before |

The URL's end is the reference's too: it stops at whitespace, a quote or a bracket, before a
second URL glued to it, and it gives trailing sentence punctuation back to the sentence while
keeping a closing bracket the URL itself opened (`.../wiki/Foo_(bar)`). An edit is stored the
same way, and the edit box is filled from `Message.editText` -- the masked-link spelling, the
angle-bracketed one, or the bare URL -- so a masked link does not lose its label on every edit.

**Reading.** `metadata.embeds` is decoded into `LinkPreview` (`data/model/LinkPreview.kt`) and a
part finds its own card three ways, all of which the reference's `resolveEmbedPayload` honours:
an `embed` part's `previewId`, a `url` part's `embedId`, and the URL the part carries -- in the
spellings the instance files it under, including the trailing-slashless key the database's
`normalize_embed_url` builds. A `url` part with `preview: false` gets no card however the
message is keyed, and a payload with `failed: true` draws nothing (that page could not be read;
the reference stores no card for it).

**Drawing.** A link is a link, drawn the way the reference draws one: an `<a>` *inside* the
sentence, so the words before and after it reflow around it. `LinkedText` builds one `Text` whose
link runs carry a `LinkAnnotation.Url` -- the link colour, underlined, and a tap that opens the
address. Drawing each link as its own box beside the prose (which is what this app did first)
lays the link out as an atomic block, and the sentence then wraps at the link instead of through
it. A masked link draws its label and carries its destination for a screen reader, which is what
the reference's `title`/`aria` pair does.

A card (`LinkPreviewCard`) is Harmony's own embed style, read from the reference's
`src/assets/embed-previews.css` and `src/components/embeds/LinkEmbedCard.vue` rather than
invented here. At phone width -- the reference's `@media (max-width: 640px)` branch, the only one
a phone sees -- that style is:

| The rule | What it draws |
| --- | --- |
| `.provider-embed__frame` | a 1dp border, a 14px radius (rounded to this app's scale, `HarmonyShape.small`), filled with the raised surface, capped at 480dp wide |
| `.link-embed-card--has-image` | an 80dp **square thumbnail on the leading edge** with the site, title and description centred beside it, one line each |
| a card with no picture | the site, the title and the description alone, at the larger type |
| `.link-embed-card--media` | a GIF page or an `og:video` link: the picture at its own aspect, up to 320dp tall, with the text beneath |
| `.link-embed-card__icon` | the favicon on a **white chip** -- a dark glyph on transparency would vanish on this app's dark theme |
| `.link-embed-card__placeholder` | the site's icon on a tint of the accent colour where the picture failed, not a hole |

The whole card is the tap target. The message's own row keeps its long press: a tap on the text
still does nothing, so the action sheet does not fight the link.

A URL inside a `text` part is linked too. Messages written by an older build of this client, or
by any other client, store one text part, and a link a person can read but cannot tap is the bug
this file exists to fix. The tokenizer that already resolved `:shortcode:` now also yields a
`Link` token, sharing `MessageTextParts.bareUrlAt` with the splitter -- so what is linked on
screen is exactly what would have been stored as a `url` part.

**Safety.** A message is written by somebody else, so only `http`, `https`, `mailto` and `tel`
ever reach the platform (`LinkUrl.isOpenable`, the reference's `SAFE_URL_SCHEMES` less `blob:`).
`javascript:`, `data:`, `file:` and `intent:` URLs are still *named* -- a person has to be able
to see what a message says -- but they are drawn muted, are not tagged as links and are not
tappable. A device with nothing that can open a link reports it in the room's banner instead of
swallowing the tap.

## What it does not do

- **No provider players.** A YouTube, Spotify or music link is a card, not an iframe or a
  player, and an `og:video` link draws its poster rather than a play control that could not
  play. A WebView on a message row is a separate piece of work with its own security questions,
  and [parity-audit.md](parity-audit.md) (M05, M07) keeps that gap open.
- **No collapse or pop-out rail.** The reference puts Open, Hide and pop-out buttons on a rail
  beside the frame (`.provider-embed__actions`); here the whole card opens the link and there is
  nothing to collapse, because a phone has no hover to reveal a rail.
- **No outside-link consent.** The reference asks before following a link off the instance, with
  a trusted-sites list (`OutsideLinkDialog.vue`, `services/outsideLinks.ts`); this client opens
  the link directly. U07 and J24 remain open.
- **No suppress-embeds toggle.** `metadata.suppress_embeds` is *honoured* -- a suppressed message
  draws its links and no cards -- but there is no menu action that sets it
  (`set_message_embeds_suppressed`). C06's "embed suppression" stays open.
- **No rich provider cards.** A Harmony post or profile card draws as title, description and
  picture, not as a rendered post with its own reactions and actions. A fediverse or X post's
  thread, quote and gallery are decoded into `raw` and not drawn.
- **No markdown.** Bold, italic, code and spoilers are still not rendered, which is unchanged
  from [message-content.md](message-content.md).

## Where it lives

- `data/model/LinkPreview.kt` -- the payload, `LinkUrl` (what may open, what a preview is keyed
  by), `linkPreviews()` and `forPart()`.
- `data/model/MessageTextParts.kt` -- the splitter, `bareUrlAt`, and `editTextOf`.
- `data/model/Message.kt` -- `Message.previewFor`, `embedsSuppressed`, `editText`, and the wider
  `isEditable` (text and links; a mention, emoji or attachment still cannot be edited).
- `data/repo/ChatRepository.kt` -- a send and an edit store the split parts.
- `ui/components/LinkedText.kt` -- a sentence with its links annotated, and `openWebLink`.
- `ui/components/LinkPreviewCard.kt` -- the card, in Harmony's own embed style.
- `ui/components/MessageContent.kt` -- the `url` branch, the card under the sentence, the `embed`
  block, and the `Link` token in `textTokens`.
- `ui/screens/chat/RoomScreen.kt` -- one `openLink` for the room, its failure banner, and Edit
  reading `editText`.

The regressions are `MessageLinkPartsTest` (the grammar, the round trip, what may open, what a
preview is keyed by), `MessageLinkContractTest` (what a send stores, what a page resolves) and
`MessageLinkRenderTest` (the link and the card draw, both open the URL, a `javascript:` URL is
named and not tappable, a suppressed message has no card, a GIF page draws its picture as the
card). The captures are `docs/evidence/render/message-links.png` (a card with a picture),
`message-embed-text-only.png` (a card without one), `message-embed-media.png` (a media card) and
`message-link-in-text.png` (a link in somebody else's prose).
