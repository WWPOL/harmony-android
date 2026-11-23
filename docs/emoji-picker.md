# The emoji picker

Reacting to a message used to offer five faces and nothing else: the actions sheet
drew `listOf("👍", "❤️", "😂", "🎉", "👀")` and that row *was* the whole of
reacting. This is the picker those five now lead to, and what each piece of it is
for.

## Where it is

- `ui/components/EmojiPicker.kt` -- the picker, the quick row, and the sheet body.
- `data/model/EmojiCatalog.kt` -- the emoji table, generated. **Do not edit it by
  hand**: `scripts/emoji/gen_emoji.py` writes it.
- `data/model/CustomEmoji.kt` -- one `emojis` row.
- `data/repo/ChatRepository.kt` -- `emojiLibrary`, and the two reaction writes.
- `data/session/SessionStore.kt` -- the per-account recents.

## The table, and why it is generated

The emoji, their names, their order and their keywords come from Unicode's own
data: `emoji-test.txt` (Unicode 16.0) for the emoji, and CLDR's English
annotations for the words people search by. Both are Unicode data files under the
Unicode licence, not another client's source, and the generator is in this
repository:

```sh
curl -sO https://unicode.org/Public/16.0.0/ucd/emoji/emoji-test.txt
curl -sO https://raw.githubusercontent.com/unicode-org/cldr-json/main/cldr-json/cldr-annotations-full/annotations/en/annotations.xml
python3 scripts/emoji/gen_emoji.py emoji-test.txt annotations.xml \
  > app/src/main/java/lol/mony/harmony/data/model/EmojiCatalog.kt
```

The table holds **1906 base emoji**: Unicode's fully-qualified set with the
five-tone variants folded back into their base. Two consequences are worth
knowing:

- **A skin tone is a modifier, not an entry.** `EmojiEntry.withTone` inserts the
  tone after each tone-able codepoint and drops a variation selector before it,
  which is how Unicode spells the toned form. A base is marked tone-able only when
  that rule reproduces *every* toned form Unicode lists for it -- 299 bases -- so
  the picker cannot offer a tone that draws something else. The seven multi-person
  couples, where each person takes their own tone, are correctly not tone-able.
- **Search uses CLDR's keywords, not a hand-written alias list.** "lol", "rofl",
  "+1", "hello" and "party" are CLDR keywords, so they find the face a person means
  without anyone maintaining a slang table. Flags carry no annotation -- CLDR
  derives them from region names -- so a flag is searched by the name the data file
  gives it ("flag: Japan").

`EmojiCatalogTest` is the contract: the size, that every row parses, that the tone
rule lands on a real glyph, and that the words people actually type find something.

## The picker

- **Search** spans every category and both kinds of emoji, so a person does not
  have to know which tab an emoji is in. The ranking is by kind of match -- the
  name is the word, a word of the name is the word, a keyword is the word, then
  prefixes and substrings -- and ties keep Unicode's order, so the list does not
  reshuffle between keystrokes.
- **Categories** are Unicode's own nine groups, as tabs carrying a glyph of each.
  The strip scrolls to the chosen tab, because it is wider than a phone.
- **Tones** are one row above the tabs: the grid shows each emoji once, and the
  chosen tone changes what a tone-able one inserts.
- **Recent** is the first row, per account, on this device. It is *not* the
  instance's `emoji_favorites`: favourites are deliberate and shared with the web
  client, while "what I used a minute ago" is what this device saw. A custom emoji
  is remembered as `id:<uuid>` so a rename does not lose it; one the library no
  longer holds is dropped rather than drawn as a blank cell. The list's rules --
  newest first, no duplicates, capped at `SessionStore.RECENT_EMOJI` -- are pure
  functions on the stored list (`addRecentEmoji`, `parseRecentEmoji`), so they are
  tested without a DataStore, whose preferences file is a process-wide singleton.
- **Custom emoji** have a tab of their own, and appear in a search beside the
  Unicode ones. The library read is the room's server plus the instance's imports
  plus this account's own -- the three kinds of `emojis` row a person can react
  with -- filtered to rows that are not removed and have a file to draw.
- **The 20-reaction limit** is the instance's own (`check_message_emoji_reaction_limit`),
  so it is stated above the grid before a pick and the grid stops taking them.

## How a reaction is written

The instance has two columns and a trigger that checks each
(`check_reaction_emoji`):

| Pick | Column | Why |
| --- | --- | --- |
| A unicode glyph | `custom_emoji_content` | It is a codepoint, and the trigger checks it is a well-formed emoji |
| A custom emoji | `emoji_id` | It is a foreign key, and the trigger checks the row exists and is not removed |

Removal filters **the column it wrote**. The old code filtered both by content,
which matched nothing for a custom emoji: the pill could not be taken back, and
the tap reported success. `ReactionContractTest` pins all of it, including the
sentences the database's refusals become.

The pill draws that row back. `MessageReactions` takes the page's `emojis` rows
by id and a custom pill whose row it holds draws the emoji's own picture instead
of its shortcode, so a reaction picked from the server's emoji looks like the
emoji and not like text someone typed (`ReactionGestureTest`, `EmojiPillRenderTest`).
A pill whose row this client has not read keeps its shortcode rather than
disappearing. The rows a pick names are resolved by `AppState.resolveReactionEmojis`
after every write, so a pick from the picker is drawn as its picture on the next
frame.

## What this does not do

- No GIFs, stickers or install packs, and no emoji upload/management screens.
- No favourites (`emoji_favorites`) and no per-emoji info card.
- No quick-react on a double tap of a message; the quick row is still in the
  actions sheet.
- The captures in `docs/evidence/render/emoji-picker-*.png` come from Robolectric
  drawing the real composables, so they show what the picker draws and not what a
  device draws. Robolectric's bundled fonts have gaps: `U+1F617` draws as a box
  there and as its emoji on a device.
