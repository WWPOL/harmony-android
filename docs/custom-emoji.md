# Custom emoji drew as a name or an id

A custom emoji did not show. Under a message it read `:blobcat:` at best and
`3f2a9c1e-1111-4222-8333-444455556666` at worst, and a message body with a
custom emoji in it had a gap where the picture belonged.

## What the instance stores

`reactions` names its emoji one of two ways, and the reference client
(`src/services/core/CoreMessageService.ts`'s `toggleReaction`) writes them
strictly:

| what the reaction is | `emoji_id` | `custom_emoji_content` |
| --- | --- | --- |
| an `emojis` row (a server, instance or personal upload) | the row's uuid | null |
| a unicode glyph | null | the glyph |
| a `:name:` or `:name@host:` shortcode | null | the shortcode |
| a bridged Discord emoji | null | `discord:[a:]<name>:<id>` |

A custom emoji is therefore **usually an id with no picture at all** in the row
the client reads. The picture is `emojis.url`, in a table the app never read,
and `emojis` is world-readable (`emojis_select_all`), so one `in.(...)` read
answers for a whole page.

A message part is the other shape: it carries the whole `emojis` row
(`{id, name, url}`), which is why a message body drew its emoji while a reaction
did not.

## The decision: resolve, then draw

1. **Resolve the rows.** `ChatRepository.emojis` reads `id,name,url,domain` for
   the ids a page's reactions and message parts name, and `AppState.emojis`
   keeps them beside `people` and `bots`. It is one request per page, not one
   per pill, and a failed read is swallowed: the pill then falls back to what
   the reaction stores.
2. **One emoji, one identity.** `reactionPills` groups the rows by
   `Reaction.key` -- the row id when there is one, else the stored content -- and
   resolves each group's emoji from the row, falling back to the content:
   a glyph draws as itself, `:name:` as `:name:`, `discord:name:id` as Discord's
   own CDN image for that snowflake (the identifier carries the id in full), and
   a bare name -- what a client before 1.9.4 wrote -- as `:name:`.
3. **One composable draws it.** `EmojiGlyph` draws a picture when there is a
   `url`, a glyph when it wraps a codepoint, and the shortcode when there is
   neither. A picture that **fails to load falls back to the shortcode** rather
   than to an empty box: an `AsyncImage` with no error branch paints nothing,
   which is the second half of this bug.

The label is never an id. `reactionLabel` takes the row's name when it was
read, else the name or glyph the content stores, else `:emoji:` -- and
`Emoji.fromStoredContent` refuses a uuid explicitly, because a uuid is
alphanumeric and hyphenated and a naive "bare name" rule would draw it as
`:<uuid>:`.

**Alternatives rejected.** Drawing the shortcode and calling it done leaves the
bug the person reported: they asked for the emoji, not its name. Fetching the
`emojis` row per pill is one request per reaction per message. Reading the url
off the reaction is not possible -- it is not there.

## The toggle was broken the same way

`react()` wrote the pill's key into `custom_emoji_content` whatever it was. For
a pill whose row stored no content the key *is* the uuid, and the instance's
`check_reaction_emoji` trigger refuses that (`REACTION_EMOJI: <uuid> is no
emoji`, 22023). Removal always filtered `custom_emoji_content`, which is null on
a row that names its emoji by id, so a custom reaction could not be taken back.

`Reaction.toggleEmojiId`/`toggleContent` now split by the reference's rule, and
`addReaction`/`removeReaction` take both and write and filter the one that
applies.

## Where it lives

- `data/model/Message.kt` -- `Emoji.fromStoredContent`, `DiscordEmoji`, `DiscordEmoji.cdnUrl`,
  `isEmojiRowId`, `Reaction.toggleEmojiId`/`toggleContent`,
  `Reaction.remoteEmojiUrl`.
- `data/repo/ChatRepository.kt` -- `emojis`, `emojisNamed`, `addReaction`, `removeReaction`.
- `ui/HarmonyAppState.kt` -- the `emojis` map and its two resolvers.
- `ui/components/AvatarUrl.kt` -- `resolveEmoji`, which names the `emojis`
  bucket for a bare object path.
- `ui/components/EmojiGlyph.kt` -- the one composable that draws an emoji.
- `ui/components/MessageReactions.kt` -- `reactionPills`, the pill, the label.
- `ui/components/MessageContent.kt` -- `EmojiPart` and `textTokens` through
  `EmojiGlyph`.
- `ui/screens/chat/RoomScreen.kt` -- the resolvers on load and on a toggle, and
  the reactor sheet's title.
- `ReactionEmojiTest.kt`, `TextEmojiTest.kt`, `EmojiPillRenderTest.kt`,
  `ReactionGestureTest.kt` -- the regressions. The render test asserts on
  **pixels**: a pill whose picture did not draw fails there even though its node
  exists.

## Three shapes a custom emoji hides in

**Inside a message's text.** This is where most custom emoji are used: a person types `:blobcat:`
into the composer, and the reference draws blobcat's picture for it
(`renderMarkdownToHTML`'s `emoji` token, resolved through `findEmojiByName`). The app drew the
name -- a "long name" where an emoji belonged. `textTokens` splits a text part into literal text
and its shortcodes by the reference's own rules (`src/utils/markdownParser.ts`): `:name:` of
`[a-zA-Z0-9_+~-]` with the `~N` disambiguator, `:discord:[a:]<name>:<snowflake>:`, and a code
span left alone, because `` `:cat:` `` is how a person writes about a shortcode rather than using
one. `ChatRepository.emojisNamed` reads the rows for the names a page uses, once per page, and a
name the instance does not have stays the literal text it was written as -- which is exactly what
the reference's renderer does when its resolver answers nothing.

**A bare storage path.** `emojis.url` written by this instance's own uploader is
a public URL (`create_my_emoji` refuses anything else), but a row imported,
bridged or written by an older client holds the bare object path --
`<id>/<file>.png`. The reference joins exactly that to the `emojis` bucket
(`getEmojiUrl`), so `AvatarUrl.resolveEmoji` does the same. Resolving it as an
avatar would ask the `avatars` bucket for it: a 404, and an emoji that draws
nothing.

**A bridged reaction's metadata.** A Discord-bridged reaction names its emoji in
`custom_emoji_content` and puts the file in `metadata.remote_emoji_url`, kept on
Discord's CDN with its png/gif extension -- so an animated bridged emoji is a
`.gif` that the identifier alone cannot rebuild. `Reaction.remoteEmojiUrl` reads
it, but only on Discord's own hosts (`cdn.discordapp.com`,
`media.discordapp.net`), which is the reference's rule
(`remoteReactionEmojiUrl`): any bot can write reaction metadata, and every
viewer of the message would fetch whatever url it names. A url anywhere else is
not loaded, and the identifier's own `.png` stands.
