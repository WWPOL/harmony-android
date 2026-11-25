# Render evidence: the composables, drawn

These PNGs are the app's **own composables**, drawn by Robolectric's native
graphics on the JVM and captured with `captureToImage()`. Each one signed in to
a real Harmony instance as a throwaway account first, so the rows come from
PostgREST, and each waited for text that only appears once that screen's
repositories had answered -- a capture can never show a spinner in place of the
screen.

They are **not device screenshots**. The emulator on this host cannot produce
one: every capture path returns black, for the reasons in
[verification.md](../../verification.md). What these show is what the
composables draw and how the theme applies; what they cannot show is touch, IME,
rotation, system appearance or device rendering.

The emoji captures carry one more caveat of the same kind: Robolectric draws with
the fonts bundled in its `android-all` jar, and that set has gaps. A glyph the JVM
runner has no font for -- `U+1F617` (:kissing face:) in the Smileys capture -- draws
as a box there and as its emoji on a device. The picker's own tests do not depend
on the glyphs drawing: they assert the tree and the value each cell hands over.

## The files

- `instance-picker.png` -- the signed-out entry: wordmark, instance field,
  Connect.
- `chats.png` -- Chats in the default dark look: the server rail, the DM list,
  the bottom navigation bar.
- `chats-light.png`, `chats-midnight.png` -- the same screen under Harmony's
  other two presets.
- `chats-custom.png` -- a custom account look: violet primary over a warm dark
  background.
- `social.png`, `notifications.png` -- the Social feed and the notification
  inbox, each with its own data.
- `you.png`, `you-appearance.png` -- the account screen, and the appearance
  panel with its four themes and live preview.
- `drive.png` -- the Drive browser with quota, filters and its empty state.
- `server-management*.png` -- server management across its Overview, Channels,
  Members, Roles, Invites and Bans tabs.
- `message-parts.png` -- one channel history whose parts are the shapes the
  instance stores: text and an emoji object in one sentence, a mention, a
  channel reference, a unicode emoji, an unresolved shortcode, and a poll.
- `message-emoji-image.png` -- `shipping [image] today`: the custom emoji drawn
  as its own picture, inline in the sentence, from a `file://` PNG so the
  assertion is on pixels rather than on the alt text.
- `message-emoji-pill.png` -- `shipping [image] today` drawn by
  `EmojiPillRenderTest`: the emoji is a picture between the two words, not a
  shortcode and not an id.
- `message-text-emoji.png` -- `shipping [image] today`, where the message's own
  text is `shipping :blobcat: today`: a shortcode typed into a message draws
  that emoji's picture. Typing the shortcode is how a custom emoji is used in
  text, beside the picker, and the bug drew the name here.
- `reaction-emoji-image.png` -- a reaction pill whose emoji names an `emojis`
  row: the row's picture with its count. Before
  [custom-emoji.md](../../custom-emoji.md) this pill drew the row's **uuid**,
  because `reactions` carries the id and the app never read the row.
- `reaction-emoji-fallback.png` -- a pill whose `emojis` row was not read:
  `:blobcat:` and the count. The emoji is named, never the id it is keyed by.
- `message-emoji-fallback.png` -- an emoji whose file cannot load draws its
  shortcode in place. An `AsyncImage` with no error branch leaves a hole, which
  is the other half of the same bug.
- `message-links.png` -- a message with a `url` part the instance previewed:
  the link on the sentence's line and its card beneath. The card is Harmony's
  own embed style, not this app's invention -- an 80dp **square thumbnail on
  the leading edge** with the site, the title and the description beside it,
  inside a hairline frame. Tapping either opens the URL
  (`MessageLinkRenderTest`), which is what the app could not do before
  [links-and-embeds.md](../../links-and-embeds.md).
- `message-link-in-text.png` -- a message stored as one `text` part, the shape
  an older build of this client and every other client writes: the URL in the
  prose is still a link -- an annotation *inside* the sentence, so the words
  around it wrap through it -- and a tap opens it.
- `message-embed-text-only.png` -- a card with no picture: the site, the title
  and the description are the card, at the larger type the reference uses for
  that shape, rather than a hole where a thumbnail would have been.
- `message-embed-media.png` -- a GIF page, whose picture *is* the content: drawn
  at its own aspect with the text beneath it, and the site's dark favicon on the
  white chip that keeps it visible on the dark theme.
- `thread-sheet-open.png`, `thread-opened.png` -- the room's Threads sheet, and
  the thread it opens, which is the transition that used to crash the app.
- `threads-sheet.png` -- the same sheet from `ThreadsSheetTest`, drawn offline against
  `FixtureInstance`, with a channel's three threads in their three states: one open, one whose
  own auto-archive window has run out, and one the instance archived. All three are listed and
  the closed ones say why. Before
  [the fix in ux-fixes.md](../../ux-fixes.md#7-a-channel-with-threads-said-it-had-none) the
  sheet filtered the last two out and told the person the channel had none.
- `emoji-picker-dark.png`, `emoji-picker-light.png` -- the emoji picker, which
  replaced a row of five hardcoded faces: the search box, the account's recents,
  the skin-tone row, the category tabs, the server's custom emoji and the whole
  Smileys category.
- `emoji-picker-empty.png` -- the same picker for an account that has not used it
  yet: no recents, and every emoji still there.
- `emoji-picker-limit.png` -- the 20-reaction limit, stated above the grid
  instead of arriving as a database error after the tap.
- `emoji-picker-smileys.png`, `emoji-picker-people.png`, `emoji-picker-food.png`,
  `emoji-picker-symbols.png`, `emoji-picker-custom.png` -- one capture per
  category, and the tab holding the instance's own emoji, so a category that drew
  nothing would be visible here.
- `emoji-picker-search.png` -- a search for "lol": Unicode's names and CLDR's
  keywords both match, so a face is found by the word a person types.
- `quick-reactions.png` -- the quick row above the actions sheet, with the "+"
  cell that opens the picker.
- `attachment-composer.png` -- the composer's attachment strip with three files:
  one still uploading (45%, with its bar), one whose photo details were removed
  ("location removed", with the keep switch), and one the instance's upload limit
  refused, in its own numbers. Each row previews the bytes that will be sent --
  the pictures are the test's own JPEGs, decoded by the app's image pipeline, so
  a preview that failed to draw would fail the test rather than look like a grey
  box here. The line under them says what to do about the refused file, because
  Send waits for every file; the location warning appears only when the bytes
  being sent still carry a position.
- `attachment-message.png` -- a message whose attachment is a `message_media`
  object: the picture is drawn from the URL its `path` was signed to, not from
  the seven-day `url` the part also carries. Before that URL arrives the same
  row says it is loading rather than showing a broken picture.
- `pinned-sheet.png`, `actions-sheet.png` -- the pinned-messages sheet and the
  message-actions sheet, both of which read the same cleared nullable.
- `reply-reference-above-username.png` -- one row that answers an earlier message:
  the `Reply` reference first, then the author's name and time, then the body.
  Unlike the captures above it needs no instance -- `ReplyReferencePlacementTest`
  builds the row from a `Message` -- and it measures the drawn order as well as
  drawing it, so a reference put back between the name and the body fails the test.
- `connection-reconnecting.png` -- a room whose live connection dropped: the banner
  says the app is putting it back, counts the attempt, and offers to skip the wait.
  `connection-unavailable.png` is the other banner state, an instance that refused
  the join, where no retry on a timer can help.
- `swipe-to-reply-direction.png` -- a message row held mid-drag: it has moved
  away from the start edge and the reply arrow is on the trailing edge it
  uncovered. This one needs no instance -- `SwipeToReplyTest` drives the
  gesture composable directly, which is the point of the capture: the direction
  is what it records. A row dragged the other way would put the arrow on the
  leading edge, and the same test asserts where it lands.
- `swipe-to-channel-list.png` -- a room held mid-drag the *other* way: the pane
  has moved off the start edge and the server's channel list is uncovered
  underneath it. `SwipeToChannelListTest` writes it and also injects the drags
  that must not do this -- a leftward one (the reply), a vertical one (the
  list's scroll) and one that does not start at the edge -- so the picture and
  the assertions are the two halves of one claim about direction. It needs no
  instance either.
- `keyboard-composer-before-*.png`, `keyboard-composer-after-*.png` -- a room
  with the keyboard up, drawn before the frame counted the IME twice and after
  it stopped. The keyboard is a **synthetic** 300dp strip drawn at the bottom:
  Robolectric runs no input method, so the strip is what the system keyboard
  would cover, and it is painted pink so the space it owns is unmistakable.
  `before-single` and `after-single` are one line typed; `before-banners` and
  `after-banners` add the reply and connection banners of the reported
  screenshot. In the "before" pair the space bar is squeezed into a strip above
  the keyboard and the message list loses that height; in the "after" pair the
  bar has stood down and the list keeps it. `after-multiline` is a four-line
  draft, where the field is taller and the buttons stay on its last line, and
  `after-keyboard-down` is the same room with no keyboard, where the bar is back.
  These are the pictures behind `KeyboardInsetTest`; `ComposerKeyboardRenderTest`
  draws them, and the "before" case is the old arithmetic expressed through the
  frame's own parameters rather than a mutation of the source.

## The account's own look

`chats-account-appearance.png` and `chats-account-default.png` are the same
screen under two looks. The first is whatever the signed-in account stores in
`profiles.appearance_settings`; the second is the app's default. The account was
set, over PostgREST, to a custom light look with a crimson accent (`#B91C1C`),
17sp text and compact messages -- the same JSON the web client writes -- and the
app rendered it.

That pair is the evidence for the requirement that the client respect Harmony's
appearance settings: `AccountAppearanceRenderTest` captures both and asserts the
pixels differ, so a screen that ignored the stored setting could not pass.
