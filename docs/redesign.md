# The Material 3 Expressive redesign

This is what the app looks like now, why, and what was verified. It is a design document, not a
claim of parity: [parity-audit.md](parity-audit.md) is where unimplemented capabilities are
recorded, and nothing here moves an entry in it.

## What was wrong

The baseline was a working Compose client with a shell that fought the person using it.

**One rail did two jobs, and then five destinations did three.** A 76dp column carried the five
destinations *and* every server, then collapsed to a menu button. Choosing a server hid the
destinations; choosing a destination hid the servers. The first redesign split the two apart --
but it split them the wrong way round: the five destinations became the frame, and the servers
became a strip *inside* one of them. So the thing a person uses constantly was the thing hidden,
and the thing they visit now and then took half the navigation.

**One column at every width.** The DM list, a server's channel list and the room were three
mutually exclusive states of the same column. Opening a room *replaced* the list, so getting to
another channel meant going back first. A 1024dp window drew the same thing a 320dp phone did.

**Every screen chose its own chrome.** Five top bars with different action counts, two kinds of
empty state, a generic corner FAB on Social, a spinner with a label under it here and beside it
there, `statusBarsPadding` on some screens and not others. That is how an app acquires five
margins and two ideas of what a heading is.

**Nothing knew about the window, motion or the wallpaper.** No width breakpoints, no reduced
motion beyond a preference that nothing read, no dynamic colour.

## The frame

`ui/navigation/HarmonyShell.kt` draws the app's frame from the window's width, and the frame's
navigation is the **spaces**: Home and every server the account is in.

### Why the servers, and not five destinations

The previous frame had five equal destinations -- Chats, Social, Files, Alerts, You -- in a bar
or a rail, and the servers were a strip *inside* Chats. That shape says the app is five things
used equally, which is not how it is used: a person spends nearly all of their time inside one
server, hops between two or three, and visits the feed, the drive, the inbox and their account
now and then.

So the servers are the navigation. Choosing one is one tap from **anywhere**, including while a
room is open in a different server -- which is the case the old shape made expensive. An answer
that arrives while you are reading somewhere else used to cost leaving the room, opening the
list, and finding the strip; it is now the same single tap as changing channel.

| Width | Frame | Why |
|---|---|---|
| under 600dp | a bar at the bottom | a thumb reaches the bottom of a phone; the top is where the content's own controls go |
| 600dp and wider | a rail at the side | a pointer and a two-handed grip both find it, and a wide window has the width to list every server *by name* |

**The phone's bar** (`SpaceBar`) holds Home, the three servers most worth reaching, and a "More"
control. It is ordered by *attention*, not recency: unread mentions first, then any unread, then
the rest (`barSpaces`). A server with six unread messages should not sit behind one that was read
this morning. The bar scrolls rather than compressing, so every item keeps its real size and its
name; a bar of eight squeezed monograms is a memory test, and there is room for one short line
under each face.

**The wide rail** (`SpaceColumn`) lists *every* server, by name, with its unread count, plus
"Add a server" and "More". The names are the point: a 76dp column of monograms is a phone pattern
a tablet does not need to repeat, and with the width to spell them a server is found by reading
rather than by remembering which initials were which.

**The selection is a shape, not a highlight.** A space's face morphs its corner radius as it
becomes current (Material 3 Expressive's selected-item language), and the label goes bold. There
is no second colour band and no tick, because the shape and the weight already say it.

### The "More" sheet is the app's map

The four rare destinations -- Social, Files, Alerts, You -- live behind the rail's "More"
control, in `MoreSheet.kt`. It is deliberately **not** an overflow menu: it lists every server
with its name and unread count, then the four places with a line saying what is inside each one.
It is the only place a person can find a server they are not thinking about, which is what makes
a bar of three safe, and it is one tap from anywhere.

Nothing is lost against the old five-item bar: each of the four is still a single tap from
anywhere, and the server list is *better* off, because it is no longer a strip inside one
destination. Back from a rare destination returns to the conversation, which is the frame's rule
rather than each page's.

### The insets

The insets are the frame's, once, for the whole content. The app draws edge to edge, so a
destination's first row starts at y=0 unless something pads it; a device screenshot showed the
old space strip's "Home" pill with the clock drawn over it, because the strip was the first row
and nothing had inset it. Padding each destination's first child is how an app acquires two
margins and a screen that forgets. The frame pads the content box by the status bar, the cutout
and the horizontal edges, in both layouts, and each destination starts from a surface that is
already inside the safe area. The bar and the rail pad themselves for the edges they own.

The destinations are the frame and never move. Each one owns its own content, so a destination
can be as different as Chats (a list beside a room) and Files (a folder tree) without the frame
knowing what either looks like. The frame also provides the width it measured
(`LocalWindowWidth`), so a destination can choose its own pane count from the same number the
frame used.

### It opens where you left off

The server a person was last reading is remembered per account and instance
(`SessionStore.lastSpace`) and reopened on the next launch, checked against the server list that
comes back, so a server they have left cannot be reopened. An app that opened on Home every
launch made a person find their server again every time.

## Chats

`ui/screens/chat/ChatsDestination.kt` is the conversation itself, and it is now the *whole* of
the primary surface rather than one of five equal destinations.

The space switcher that used to sit inside this destination is gone. It was a second navigation
inside the content, competing with the frame's own; the frame now carries the spaces, so there is
exactly one place a server is chosen and it is the same place at every width and on every screen.

The channel list and the room are **two panes when there is room**:

- **Compact** (under 600dp): one pane at a time. Choosing a channel slides the room in over the
  list and back returns; the room's own back arrow says the same thing as the gesture, so a
  person who does not know the gesture is not stuck. Switching server is still one tap, because
  the bar at the bottom is the frame and it does not go away when a room opens.
- **Medium and expanded** (600dp and up): the list keeps its width and the room takes the rest.
  Choosing a channel is a *selection*, not a navigation, and the unread channels stay visible
  while reading. That is what a wide window is for.

The channel list's app bar carries the server's own face and name and its member count, so the
choice the rail made is confirmed in the same picture the rail drew. The direct-message list's
bar says how many conversations it holds, and "new message" is a filled tonal button rather than
a third grey icon in a row of them.

The room itself gained two things. Its app bar keeps the channel name and search, and moves
refresh, pins and threads into an overflow instead of five icons competing with the title. Its
composer is one docked surface with a filled circular send, because a text field with a button
beside it reads as two controls that happen to be adjacent.

**Swipe to reply travels away from the start edge.** It used to travel towards the end edge,
which is the direction a navigation drawer opens in: the gesture that should answer a message
was the gesture that pulls a drawer open. `SwipeToReplyRow` (`ui/screens/chat/SwipeToReply.kt`)
now owns the direction in one place -- the sign comes from `LocalLayoutDirection`, so a mirrored
layout mirrors the gesture too -- and the reply arrow draws on the edge the row uncovers, not
the one it slides over. The drag stays horizontal-only, so the message list's vertical scroll is
never taken from it, and the reply remains reachable from the actions sheet and from a custom
accessibility action.

**A drag the other way, from the start edge, is how the room gives the list back.** A phone
shows one pane at a time, so the channel list of the server a room belongs to was reachable only
by the room's back arrow or the system's back -- the gesture a person's thumb tries first had
nowhere to go. `SwipeToChannelList` (`ui/screens/chat/SwipeToChannelList.kt`) now wraps the room
in the one-pane layout, and it owns the half of the horizontal axis the reply does not: the reply
travels away from the start edge, this travels towards it. Three rules keep the two from being one
gesture. The drag has to begin in a 24dp strip at the start edge, so a row dragged the wrong way
in the middle of a message is still just a row. Only the end-wards direction is claimed, so a
leftward drag from that same strip is still a reply and a vertical one is still the list's
scroll. And the pane claims the drag in the `Initial` pointer pass, because a message row's
`detectHorizontalDragGestures` does not care which way the finger went and would otherwise take
the gesture first. The pane follows the finger, and a release past a third of its width -- or a
flick from 64dp at 500dp/s -- closes the room, which uncovers the list by the movement the person
started rather than by a second, unrelated slide. A release short of that slides the pane back.
The direction comes from `LocalLayoutDirection`, as the reply's does, so a mirrored layout starts
at its own start edge. On a phone in gesture navigation the system's back gesture owns that strip
first and closes the same room, so both routes end where the thumb expected; this one is what
makes the move reachable on a three-button device, and what lets the pane move with the finger
instead of jumping.

## The other pages

The frame and Chats were the first round's work, and they left the rest of the app looking like
the app it replaced. Four screens changed shape since, and each change is about *what a person can
see and reach*, not about a radius.

**You: five tabs became one settings page.** Profile, Appearance, Privacy, Notifications and
Security were a `ScrollableTabRow`. On a 400dp phone the last two sat off-screen with nothing to
say they existed, and a person could not see what the screen could do without swiping through it.
The identity is now the top of the page -- a 64dp face, the name at `headlineSmall`, the handle,
the pronouns, the bio -- because who you are signed in as is the first thing this screen should
answer. Under it, grouped rows: an icon, a title, **a line saying what is inside**, and a chevron.
A row opens one section; the app bar takes the section's name and back returns to the list. The
settings themselves are the same composables.

**Alerts: two controls became one.** A bare heading row, a `TabRow` of three filters and a
`FilterChip` floating under it made two controls out of one decision, and the heading was a `Text`
rather than a bar. It is one app bar whose supporting line says what is waiting ("2 unread", "All
caught up"), and one row holding a single-choice segmented control for the filter with the unread
toggle beside it. The "N loaded" counter is gone: it counted pages, which is the app's business.

**Social: a chip strip became a tab strip, and posts became cards.** The timeline was five
`FilterChip`s in a horizontally scrolling row -- a filter you might not have found, whose selected
item had to be found by eye. The timeline is what the feed *is*, so it is a scrollable primary tab
row under the app bar: a stated choice with an indicator, scrolling rather than splitting the
width, because five labels divided across 400dp clip to "Followi" and "Fediver". Each post sits on
its own raised surface with the shape ladder's large radius; the previous full-bleed dividers read
as one long column of text with lines in it.

**Drive: four bands became one bar and one filter row.** The type filter was six `FilterChip`s in a
scrolling strip, so the last choices were off-screen and the row read as an unordered set of tags.
The quota moved into the app bar ("1.1 MiB of 5.0 GiB used") with the progress bar as the only
thing that needs the width, and the type is a menu: a stated choice, every type listed when
opened, with a tick on the current one.

**Server management: six tabs became a list.** Overview, Channels, Members, Roles, Invites and Bans
in a `ScrollableTabRow` meant Invites and Bans were off-screen on a phone. They are rows now, each
saying what it holds, and a row opens one section. The section pages are unchanged.

**Sign-in: a form became a page that answers a question.** The signed-out screen was a centred
column of a wordmark, two fields and five text links stacked under them, with the instance's name
in grey underneath the logo -- the shape a form takes when nothing has decided what it is for. The
screen exists to answer one question, *which server am I joining*, so that answer is now the
largest thing on it: a `primaryContainer` band carrying the wordmark, the instance's name at
`headlineMedium` and its domain. The one rare action, using another instance, belongs on that band
rather than being the fifth of five links at the bottom. Below it: the form, a refusal as an
`errorContainer` surface above the button (it used to be `bodySmall` red text directly under the
password field, where it read as that field's supporting text), and the next step as its own row --
"New here? / Create an account on this instance" with a Join button -- instead of a fourth link in
a stack. The GPL notice is a caption, which is what it is.

**The message-actions sheet: a column of buttons became a list of verbs.** A long press on a
message opened a `ModalBottomSheet` of full-width `TextButton`s: every row the same weight, no
glyphs, and Delete told apart only by being red. `ActionSheetContent` draws each action as a row
with an icon, its name and -- where it is not obvious -- a line saying what it does ("Replies
collect away from the channel", "This cannot be undone"), under a title naming the message, so the
sheet can be read before it is tapped. The quick reactions stay a row of faces above it, because a
reaction is the one action taken without reading anything. The sheet is one of the two places the
app's per-message capability lives; the other is the message row's own long press, unchanged.

**The emoji picker: five faces became every emoji.** The quick row was the whole of reacting --
`listOf("👍", "❤️", "😂", "🎉", "👀")` in the sheet's body, so a reaction outside those five could
not be made at all. Its last cell is now a "+" that opens `EmojiPicker`: Unicode's 1906 base emoji
in their own categories, searchable by Unicode's names and by CLDR's keywords (which is why "lol",
"rofl", "+1" and "hello" find the face a person means), with a skin-tone row that changes what a
tone-able emoji inserts rather than listing every toned variant six times, the picks this account
made last on this device, and the instance's server/instance/personal custom emoji. The five faces
stay where they were: the common reaction is still one tap from the long press, and the picker is
for the other 1900.

**The instance picker: the last screen shaped like the old app.** It is the first thing a person
sees after installing, and it was a centred column -- a wordmark, a heading, a paragraph, a field,
a button -- with the paragraph doing the work the page's structure should have. It is now the
signed-out page's sibling: the same `primaryContainer` band carrying the wordmark and the question
the page asks, the address field inside the app's 760dp reading column, a refusal on an
`errorContainer` surface rather than as `bodySmall` red text directly under the field, and Connect
as the only filled thing. The two signed-out pages have to agree about what a wide window means,
or moving between them looks like moving between two apps, so `WideInstancePickerPageRenderTest`
asserts the same field-width cap `WideSignInPageRenderTest` does on the other.

**Voice: a paragraph over a name list became a room with three states.** The screen had one
layout for everything it could be doing: a centred icon, the channel's name, a sentence explaining
why you could not join, a list of names, and a row of six bare icon buttons at the bottom. That
layout answered "why can't I join" and nothing else. It is now three surfaces that each answer the
question their moment actually asks.

*The lobby* answers "is anyone in there, and will they hear me". The people already in the channel
are the reason to join, so they are the body of the page under a section header that counts them,
not a footnote under a button; the reason *not* to join -- no voice server, peer-to-peer only,
still checking -- is the sentence at the top, and the filled Join button is drawn only when
joining is possible, so an instance without voice shows the reason and no button to press. What
will be published is stated before it is published: a person without microphone access is told
they will join listen-only rather than discovering it mid-conversation.

*The call* answers "who is talking". Speaking is a ring around the face and a tinted row rather
than a dot in a corner, because the eye finds a person, not a pixel; mute and camera are badges on
the face, where the person already is. When nobody has a camera on, the roster is the whole screen
instead of a short list under an empty stage.

*The control bar* answers "what is this call doing, and how do I leave". An "on" toggle is a filled
`primaryContainer` circle and an "off" one is an outlined circle -- not a tonal one, because this
theme steps each surface tier by about 2% lightness and a filled "off" button two tiers above its
own bar measured as nothing in the capture. Leave is pinned outside the scrolling group in error
colour: it is the one control reached for in a hurry and the only one here that pressing again
does not undo. Five 48dp targets plus Leave do not fit a 400dp phone at a readable label size, so
the toggles scroll and the labels say what pressing does ("Mute", not "Microphone").

*A call the server ended* is a state of the call, not a message from the app: the reason is drawn
on a surface with both things a person can do about it, instead of a sentence and a Rejoin button
with leaving left to the system back gesture.

## The design system

`ui/theme/HarmonyDesign.kt` and `ui/components/Expressive.kt`.

**Type.** One scale, in Material's roles. The account's `fontSize` and `zoomLevel` are applied as
a ratio *against* the scale rather than replacing it, so a person who asks for larger messages
still gets a heading that reads as a heading. The face is the device's own: the APK ships no
typeface, which is what keeps the app correct for every script the system font covers.

**Shape.** A ladder from 8dp to 32dp plus a pill and a sheet shape. A channel row, a card, a
sheet and the composer are four different rounds, which is what makes a dense list still read as
a hierarchy.

**Components.** One empty state (with one filled button, because an empty screen is where a
person is most likely to be stuck), one section header (a real `heading()` for TalkBack), one
card, one divider, one spinner, one error banner. A screen that draws its own is the regression.

**Motion.** `MotionPreferences` is a policy, not a duration. The account's `reduceMotion`
preference and the system's `ANIMATOR_DURATION_SCALE` are both read, and either one collapses
travel, overshoot and springs to a short fade. A screen asks `still` and never has to remember
which switch applied.

One trap worth recording: this theme maps `surfaceContainerLow` to the plain surface tone, so a
card drawn with that role is invisible against the page. The post card uses
`LocalHarmonyColors.surfaceRaised` for exactly that reason -- the first capture of the feed showed
cards that were only distinguishable by their gaps.

## Colour

Two sources, and the person chooses between them.

- **The account's look** is the default, derived in OKLCH from `profiles.appearance_settings` the
  way the web client derives its CSS variables. A theme chosen on the web is recognisable here.
  An explicit theme choice always uses this: the app never puts wallpaper colours over a look
  somebody picked.
- **Wallpaper colours** ("Material You") is an explicit, device-local choice on Android 12+. The
  app takes the platform's tonal palettes and keeps its own layout, type and shapes, so it looks
  like one app instead of a Material scheme bolted under Harmony's surfaces. On anything older the
  switch is disabled and the panel says why, rather than offering a control that would do nothing.

The choice is stored with the device preferences (`SessionStore.dynamicColorFlow`), **not** in
`profiles.appearance_settings`. That object is a contract this app shares with the web client, and
a native-only key written into it would either be dropped by the other client or preserved
forever as a mystery field. A Material You choice belongs to the device that has the wallpaper.

## Verified

`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` — 380 tests, 0 failures, 24
skipped (the skips are the render and journey tests that need a live Harmony instance; they skip
with an assumption rather than passing silently), the APK builds, and lint reports no errors (26
warnings, all pre-existing: newer dependency versions, notification API suggestions and one debug
preview).

The redesign's own tests:

- `SpaceRailTest` — the frame's width rule; every space inside the status bar and the cutout and
  at least 48dp tall; a tap on a server reaching the caller and the frame following it; a badge's
  spoken wording; the bar's *capacity* rule, that Home, the servers and "More" all fit at every
  phone width (this is a bug that shipped in this change: the item width was the bar divided by
  the spaces, which pushed "More" off the screen); the order of attention; and the pane-count
  rule, that a list gets a room beside it from 600dp.
- `ServerFirstNavigationRenderTest` and `WideServerFirstNavigationRenderTest` — the real
  `SignedInShell` against the fixture instance: the phone's bar and the wide rail, a server
  reached from the conversation in one tap, the map reaching the four destinations, and back
  returning to the conversation. They produce `nav-*.png`.
- `ChatsInsetTest` and `WideChatsInsetTest` — the bar (or the rail, at a wide width) and the
  content are inside the status bar and the cutout at both widths. This is the bug a device
  capture caught: a strip was drawn under the clock, because nothing had inset the content.
- `ExpandedChatsRenderTest` — 1000dp: the rail, the list and the room, with the room measured as
  the widest pane; captured in the dark and light looks.
- `MediumChatsRenderTest` — 640dp: the room is still a reading column, not the strip of pixels the
  first version gave it.
- `YouPageRenderTest` — the settings list and a section opened from it, drawn from the fixture
  instance; the tap is on the row's tag, so a row that stopped being tappable fails.
- `AlertsPageRenderTest`, `SocialPageRenderTest`, `DrivePageRenderTest`,
  `ServerManagementPageRenderTest`, `ExpandedPagesRenderTest` — each redesigned page with its own
  rows, at 400dp and (for You and Alerts) at 1000dp, in both palettes.
- `SignInPageRenderTest` — the signed-out page in both looks, a refused attempt as an error
  surface, and the Join row reaching the registration form. The journey test that walks sign-in to
  register to reset now scrolls to each control, because at 320dp the next step is below the fold.
- `WideSignInPageRenderTest` — the same page at 1000dp, asserting the email field stays inside the
  760dp reading column rather than stretching across the window.
- `InstancePickerPageRenderTest` and `WideInstancePickerPageRenderTest` — the first screen after
  installing, in both palettes, with a refused address on its error surface, and at 1000dp with
  the same field-width cap as sign-in. The refusal state is drawn from `InstancePickerPage`, which
  takes its state from the caller; otherwise it would only be reachable by typing a bad address
  and waiting for a network call, which is not a capture anybody can take.
- `ActionSheetRenderTest` — the message-actions sheet in both looks, drawn from
  `ActionSheetContent`. The `ModalBottomSheet` around it is `ThreadSheetJourneyTest`'s, which
  needs a live instance.
- `VoicePageRenderTest` — the lobby with people in it and on an instance with no voice server, the
  roster with one person speaking and one muted, the control bar in both looks, and a call the
  server ended. It also measures the bar: every toggle and Leave must be inside the window and at
  least 48dp wide, and the last toggle must not overlap the pinned Leave, because that is the
  defect the capture showed and a screenshot cannot keep it fixed.
- `FixtureInstanceTest` — the fixture is enough to sign in and draw a page, and every request it
  answered is one it knew: a screen that starts reading something new fails here.
- `DesignSystemRenderTest` — a compact window in the account's dark and light looks, an empty
  state, each written as a PNG.
- `WideWindowRenderTest` — a 900dp window in a custom accent, proving the frame is a rail there and
  that no bottom bar is drawn.

Captures are in [evidence/redesign/](evidence/redesign/), with a README naming each. Thirty-four
of the forty-two are **pages and flows** drawn from the fixture instance or from the screen's own
composable with real rows in them; two are real device screenshots from `emulator-5580`; the other
six are render tests of the design system and the frame. Between them they prove what each page
draws, which accessibility nodes it exposes, and the pane geometry at 320, 400, 640, 900 and
1000dp. They cannot prove touch, IME, predictive back, rotation or performance on a device — see
[verification.md](verification.md) for what needs the emulator.

## Still limited

- **No live-instance capture.** The 24 skipped tests are exactly the ones that draw the real
  screens from a live instance's own rows. The page captures use `FixtureInstance` instead, which
  answers the same PostgREST and RPC reads offline, so a page is drawn with real rows -- but a
  populated channel, a live room and a real sign-in are still not among the pictures.
- **No expanded capture from the device.** The compact layout is a real screenshot; the expanded
  one is a JVM render, because this host's emulator runs the guest CPU under TCG and its system
  server died during the wide-window install. The layout is measured at 640 and 1000dp by tests,
  which is what the device capture would have shown.
- **Predictive back** is not wired to an animation yet. Back closes a room and returns to the list,
  which is correct, but the system's back-preview is not drawn.
- **Some Expressive components are still unreachable.** material3 1.5.0 (pinned ahead of the
  Compose BOM, which still manages the rest) makes `MaterialExpressiveTheme`, `MotionScheme`,
  `LoadingIndicator`, `MaterialShapes`, `ShortNavigationBar`, `ToggleButton` and `ButtonGroup`
  public, and the app uses the theme, the motion scheme and the loading indicator. The *flexible*
  app bars (`LargeFlexibleTopAppBar` and its siblings) and `FloatingToolbar` are still `internal`
  to Kotlin in 1.5.0, so the app keeps `TopAppBar`; when a stable release exposes them,
  `HarmonyTheme` and the app bars are the places to change.
- **The voice screen's video stage has no capture.** `CallStage` draws a shared screen and camera
  tiles only from a live `VideoTrack`, which needs a LiveKit room. Its roster, which is what a call
  without video shows, is captured; the tiles are not.
- **The actions sheet is captured as its body, not inside its `ModalBottomSheet`.** The sheet's
  container is the platform's; `ActionSheetRenderTest` draws `ActionSheetContent`, and the sheet
  around it is exercised only by `ThreadSheetJourneyTest`, which needs a live instance.
- **Large text and RTL** are laid out with `start`/`end` and wrapping text throughout, but neither
  has been captured in this change.
- **The design preview is a debug-only screen.** `DesignPreviewActivity` draws the frame with
  fixture rows so the redesign can be seen without an instance. It is in `src/debug`, so a release
  APK neither contains it nor can start it.
