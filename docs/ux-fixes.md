# System bars, the collapsing rail, reaction gestures, server branding, repeat notifications

Reports from using the built APK, and what each one turned out to be. The first four are recorded
together because three of them share a cause worth naming: **the app drew itself as if the
phone's window were the whole screen and as if every stored image were an absolute URL.** The
next two were found by using that same build: a preferences file that bricked the app, and a
notification the tray would not stop repeating. The last four are the same kind of report from
the same habit: a keyboard-sized deadspace under the composer, a message body that selected itself
whenever it was pressed, a channel that had threads told it had none, and a reactor list that named
nobody. Three more came after them: a thread that was not drawn the way Discord draws one, a thread
being read that had no row in the column at all, and a red count badge on every unread message that
left nothing for a ping.

## 1. The rail sat under the status bar and the cutout

`MainActivity` calls `enableEdgeToEdge()`, so the Compose root starts at y=0 and the system
bars are drawn *over* it. `Shell`'s rail was a plain `fillMaxHeight()` column with a
`navigationBarsPadding()` at the bottom, which handled the navigation bar and nothing at the
top. On a phone with a status bar and a camera cutout, `Chats` -- the first destination --
was behind the clock.

**Fix.** The rail pads itself by the safe drawing area on its own side:

```kotlin
.windowInsetsPadding(safeArea.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
```

`safeArea` is a parameter defaulting to `WindowInsets.safeDrawing`, so production uses the real
insets and a test can pass a synthetic notch and assert exact pixel bounds.

**Alternatives rejected.** `statusBarsPadding()` alone handles the clock but not a cutout;
padding only the first child fixes `Chats` and leaves the last destination under the
navigation bar; letting each screen pad itself leaves the rail -- which is outside every
screen -- unpadded, which is the bug.

## 2. Choosing a category or server left the rail open

The rail was a permanent 76dp column at every width. Tapping a destination changed the middle
column while the rail kept a fifth of a phone screen, so the thing just chosen was squeezed
into the rest.

**Fix.** The rail is a drawer: `SignedInShell` owns `railOpen`, and choosing a destination or a
server closes it. Closed, a single handle in the top-start corner -- itself inside the status
bar and cutout inset -- brings it back. Back closes it before it leaves the screen. The rule
lives in one place, `ShellNavigation`, rather than being a boolean each call site remembers:

```kotlin
object ShellNavigation {
    fun railOpenAfterChoosing(): Boolean = false
    fun railOpenAfterArriving(): Boolean = false
}
```

A server tile also clears the open room, so choosing a server cannot land on a channel left
open in a different server.

**Alternatives rejected.** A `ModalNavigationDrawer` is the Material answer, but this rail is
a permanent column on a tablet and a drawer on a phone, and the drawer's scrim and swipe
gestures would fight the message row's own horizontal reply drag. Hiding the rail only below a
width breakpoint would have left the phone case reported here unfixed at any width a tablet
also uses.

## 3. Reaction tap and long press were inverted

The pill was:

```kotlin
.combinedClickable(onClick = { onShowReactors(emoji) }, onLongClick = { onReact(emoji) })
```

so a tap opened the reactor list and a long press added or removed the reaction -- the
opposite of the reference client and of every messaging app. Two further faults came out
while fixing it:

- The toggle matched on `reaction.emoji` (the *display* label) and wrote that back. For a
  custom emoji the label was the literal string `:custom:`, so two different server emoji were
  one pill and a tap could remove the wrong one.
- `reactionLabel` drew `:custom:` for every custom emoji, so they were indistinguishable on
  the row as well.

**Fix.** `onClick` toggles, `onLongClick` lists. `combinedClickable` does not fire `onClick`
after a long press, which is what "avoid triggering the tap action after a long press" asks
for; the pill also fires its own haptic so the press is felt before the sheet appears. A
`Reaction` now has one identity, `key` (the custom emoji's row id, else the stored content),
used by the pill, the toggle and the reactor filter, and `content` is what is written back so
joining an existing pill sends the content that pill already carries. The label draws the
real shortcode.

Both gestures are also exposed as accessibility actions, so a screen-reader user is not
required to hold a finger still.

## 4. A server's cover and icon never drew

`servers.icon` and `servers.banner` hold bare object paths (`<server-id>/banner-<ms>.png`),
which the uploader writes after normalising an absolute URL back to a path. This client joined
them to **`server-icons`** and **`server-banners`**. The instance's buckets are
**`server_icons`** and **`server_banners`**, so every relative branding path was a 404 and the
header drew a name with no picture.

**Fix.** `AvatarUrl.resolveServerIcon` and `resolveServerBanner` name the right bucket each,
and `ServerBranding` is the one composable that draws a server's cover, icon and name -- used
by the channel header and by the tests. A server with no branding gets its monogram and no
reserved strip, rather than a band of nothing above the channel list.

## 5. A preferences file that could not be decoded bricked the app

Found by the emulator run of the four reports above, not by reasoning: the real APK launched to
**"Could not load this / Unable to parse preferences proto. / Try again"**, and *Try again*
re-read the same broken file. `SessionStore`'s DataStore had no corruption handler, so one
truncated write — a kill or crash in the middle of `edit` — made every read throw, and nothing
in the app could clear it. The store now replaces an undecodable file with an empty one; the
cost is a sign-in, and what it buys is an app that starts. The reasoning and the alternatives
are in [preferences-recovery.md](preferences-recovery.md).

## 6. A sign-in notification kept coming back after it was dismissed

The report: **a "New sign-in to your account" notice the person had already viewed days ago kept
reappearing.** It was not one notification surviving a restart; it was one unread row being
posted again, and alerting again, on every poll.

`NotificationCoordinator.poll()` read the newest unread rows and posted **every one of them**:

```kotlin
for (notification in unread) HarmonyNotifications.post(context, notification)
```

`seen` was maintained but used only to decide what to *withdraw*. So a row that stayed unread on
the server -- and dismissing a notification does not mark its row read -- was posted again every
thirty seconds, for as long as the row was unread. Three separate faults made one report:

1. **Every poll re-posted the whole unread page.** There was no memory of what had already been
   shown, so the tray could not tell news from history.
2. **Nothing recorded what existed before this device started watching.** A fresh install, or an
   update that cleared the process, posted the entire unread backlog at once -- including the
   sign-in notice from days earlier.
3. **Tapping a notification never marked it read.** The deep link navigated, and the row stayed
   unread, so the next poll called it new. The inbox screen marked a row clicked when it was
   opened *there*; the tap path did not.

There was a fourth, smaller one in the same code: `cancel` built its tag from the row's **type**
(`"harmony:" + notification.type`), and a withdrawal made with only an id built `"harmony:"`,
which matches nothing that was posted. A row read on another device was therefore never taken
out of the tray.

**Fix.** Delivery is now a decision with a memory, and the rule is a pure function,
`NotificationDelivery.plan`, so every case is testable without a device:

- **`DeliveryRecord`**, kept per instance and profile in `PreferencesDeliveryRecords`, holds the
  rows already dealt with and the account's **cutoff** -- the moment this device started watching.
  A row at or before the cutoff is history and is never delivered; a row already in the record is
  never delivered twice, however long it stays unread.
- The **first poll primes the account** and delivers nothing. That is what stops an install or an
  update replaying the backlog.
- A row read, expired, or gone from a page that holds the whole inbox **withdraws** its tray entry
  and is forgotten. A page that was cut short withdraws only what it can see has been read,
  because an old row can simply have been pushed off the page by newer ones.
- **Tapping a notification marks it read and clicked** (`NotificationCoordinator.opened`, called
  from `MainActivity.handle`), so a notice someone opened stops being new on the server too.
- The tag is now the constant `"harmony"`, so a withdrawal needs only the id.
- One poll runs at a time (`pollMutex`), so two interleaved polls cannot each deliver the rows the
  other missed.

The record is deliberately **not** the inbox's read state. A person can dismiss a notification
without the row becoming read, and that dismissal has to be final; the record is the device's own
memory of what it has already said, and it survives a restart, a sign-out and an APK update.

**Alternatives rejected.** Marking the row read when a notification is posted would make
"unread" mean "not yet seen on any device", which is not what the inbox means and would empty the
inbox of everything the tray ever showed. Keeping the memory in the process (the old `seen`) is
what the bug already was: a restart forgot it. Posting only rows newer than the newest seen row's
timestamp fails on an instance whose clock or row order differs, and on two rows created in the
same second; the cutoff is a moment, and an id that has been dealt with is remembered as such.

## 7. A keyboard-sized deadspace under the composer, and a composer squeezed out of the room

The report came with three screenshots and two complaints that turned out to be one bug:
**"a huge deadspace where I think the ui thinks the keyboard is"** below the composer, and a
**multi-line message being hard to use** -- worse once a reply banner or a connection banner was
on screen, until the message list itself was gone.

The cause was one inset counted twice and one bar drawn where nothing could see it.

`WindowInsets.safeDrawing` is not "the system bars". It is `systemBars ∪ ime ∪ displayCutout`:
the keyboard is folded into the same bottom edge as the navigation bar. `HarmonyShell` handed the
whole safe drawing area to the bottom bar (`SpaceRail`), so while a field had focus:

1. **The bar padded itself by the keyboard's height** and drew a surface that tall. Every pixel of
   it was behind the keyboard -- the deadspace, which also swallowed the space bar's taps.
2. **The room above lost that height**, because the bar was a sibling in the frame's `Column`.
3. **The room then padded for the keyboard again**, with its own `imePadding()`, inside the space
   the frame had already given it.

On a 640dp-tall phone with a 300dp keyboard that leaves roughly 150dp for the app bar, a reply
banner and a composer. Adding a connection banner left a sliver, which is the third screenshot.

**Fix.** The frame measures the keyboard once and owns it, and no destination does:

- The keyboard is taken *out* of the bar's insets (`safeArea.exclude(ime)`), so a bar that is
  drawn is never padded for a keyboard that is covering it.
- The bar **stands down while the keyboard is up**. A bar behind the keyboard is not navigation:
  it cannot be seen and cannot be tapped, and its height was the room's.
- The content is inset by the bars *plus* the keyboard, exactly once, in both layouts. The
  keyboard becomes a parameter (`ime`) defaulting to `WindowInsets.ime`, the way `safeArea` is, so
  a test can pass a synthetic keyboard and assert bounds without an input method.
- `RoomScreen` drops its own `imePadding()`.

The composer's own half: it now grows from one line to `composerMaxLines` (seven) and then scrolls
inside itself, bounded at `COMPOSER_MAX_FRACTION` (a third) of the room's measured height. Before,
it was capped at five lines with no ceiling relative to the room, so a pasted draft took the
message list's height -- and with the keyboard and two banners also competing, the list went to
nothing.

**Alternatives rejected.** Keeping the bar and letting it *not* draw while still occupying its
height fixes the deadspace but not the squeeze: the room would still be short by a keyboard it
already has. Padding the room with `consumeWindowInsets` instead of measuring the frame would work
in this one layout and leave every future destination to remember the rule -- which is exactly how
this bug arose. Hiding only the bar's *padding* and keeping the bar visible behind the keyboard
would leave an untappable control on screen.

## 8. Text selected itself whenever a message was pressed

The report was one line: **text should not be selectable so easily.** It was not a rare accident.
`MessageContent` wrapped every message body in a `SelectionContainer`, and a selection container
claims the first long press for itself -- which is the *same* gesture a message row uses to open
its actions sheet. So one press did both things at once, and a double tap selected a word where a
messaging app does nothing:

- A long press on a body painted a word selection -- highlight, two handles, and on API 28+ the
  magnifier -- *and* opened the actions sheet behind it.
- A double tap selected the word under the finger, with no other effect.
- The same container was around a social post's body and a profile's bio, where a long press
  selected a word and nothing else happened at all.

Measured, not guessed: `MessageSelectionTest` injects the touches and reads the pixels. On the
tree before this change a long press changed **1594 pixels** of a one-line row, and a double tap
changed the same 1594. That the measurement can still see a selection at all is re-proven on
every run: one case draws the row with the container back and requires it to paint one, so the
zero above cannot be the zero of a test that sees nothing.

**Fix.** The body is drawn as text, not as a selection surface. Selection is now *asked for*:

- `MessageContent` no longer wraps anything in a `SelectionContainer`; the only one left in the
  app is inside the sheet the actions open (below).
- The message actions sheet keeps **Copy text** (the whole body, as before) and gains **Select
  text**, which opens a sheet with that message's body in a `SelectionContainer` and says "Hold a
  word, then drag the handles." So partial copy is still possible -- one deliberate tap further
  away than the row's own gesture, which is the whole point.
- A social post's body and a profile's bio are plain text. A post's overflow menu gains **Copy
  text**, because a post body is a tap target for its hashtags and mentions and cannot also be a
  selection surface.
- Inputs keep their selection: the composer and the search field are text fields, and selecting
  inside what you are typing is what selection is for.

**Alternatives rejected.** Making the row's long press win the race by consuming the event first
would have left the double tap and the drag-to-select still selecting, and it would have depended
on pointer-pass ordering between two composables that each think they own the press -- the kind of
fragile ordering that broke the reaction pill's tap and long press (report 3). Turning selection
off globally with `DisableSelection` at the app root would also take it from the text fields, which
need it. Keeping the container and making the *actions* the second step instead (a tap on the row
opens the sheet, a long press selects) inverts the gesture every messaging app has and the rest of
this app already documents: long-press a row for its actions.

## 9. A channel with threads said it had none

A person opened a channel's Threads sheet and was told, in the app's own words, that there were
none: *"No open threads. Long-press a message to start one."* The channel had threads. The
instance still had them, and the reference web client's own Threads list drew them.

**Cause.** The sheet filtered the instance's answer with `Thread.isActive()` and then reported
emptiness:

```kotlin
val active = listed.filter { it.isActive() }
if (active.isEmpty()) item { Text("No open threads. Long-press a message to start one.") }
```

`isActive()` is the reference's **sidebar** rule (`activeChannelThreads`: not archived, and not
past `isThreadExpired`). The reference applies it to the channel list and nowhere else: its
Threads list reads `channelThreads(id, { includeArchived: true })`, which filters nothing but the
channel (`AllThreadsModal.vue`). So this client took the narrower of the two rules and used it to
answer the broader question, and the answer it gave was wrong in two ordinary cases:

- a thread the instance had archived -- by the sweep, or by a moderator -- was dropped;
- a thread whose own auto-archive clock had run out was dropped, and this client is what sets
  that clock: `createThread` asks for `p_auto_archive_duration = 1440`, so **every thread it
  creates leaves the sheet 24 hours after its last reply**, whether or not the instance has swept
  it yet.

A channel whose threads were all older than that showed the empty sentence, and the person had no
way to reach them from the app at all.

**Fix.** The two rules are now named and kept apart. `Thread.state(now)` answers `Active`,
`Archived` or `Expired`, and `isActive()` is defined as `state == Active`, so the sidebar's rule
is unchanged and stated once. The Threads sheet lists **every** thread the instance returned, as
the reference's list does, and marks the closed ones with the reason instead of hiding them:

```
Threads
  Release planning     4 messages
  Frame sketches       2 messages · Idle past its window of 1 day
  Buried thread        9 messages · Archived                      (archive glyph)
```

The empty state is drawn only when the instance returned no threads, and it now says "No threads
yet", because that is the claim it is entitled to make. The window named is the thread's own
`auto_archive_duration` (1 day, 2 hours, 90 minutes), not a fixed "24 hours", since a thread
created with another window would make that sentence false.

The sidebar is deliberately untouched: `AppState.rememberThreads` still keeps only the open
threads, which is what the reference's channel list does. A test pins the pair, so a later change
to the sheet cannot quietly reopen the sidebar's list either.

**Alternatives rejected.** Dropping the expiry rule everywhere would have put closed threads in
the sidebar, which the reference does not do and which is the bug an earlier round fixed. Keeping
the sheet's filter and only rewording the sentence ("No open threads" instead of "No threads")
would have been honest but useless: the threads would still have been unreachable. Removing
`auto_archive_duration` from `createThread` would have made this client's threads never expire,
but the instance's own sweep and every other client still read that column, so the threads would
still have been archived by the instance a day later and still needed to be listed.

## 10. Every reactor was called "Someone"

The report: **tap a reaction pill and every person listed has reacted as "Someone"**, whoever
they are. The list was drawn from a lookup nothing had filled:

```kotlin
val person = reaction.userId?.let { people[it] }
val name = person?.shownName ?: reaction.userId?.let { "Account " + it.take(8) } ?: "Someone"
```

`people` is filled by `AppState.resolveAuthors`, which is called with **messages** -- and a person
who only reacted never wrote one. So a reactor was resolved only if they happened to have posted
on the page that was loaded, and everybody else fell through to the placeholder. Three shapes of
row produced it, and the second and third produced it always:

1. **A local account** whose profile had not been read: `people[userId]` was null, and the list
   drew `Account 3f2a9c1e` -- or "Someone" where the id was missing.
2. **A bot** (`bot_id`, no `user_id`): the list read only `user_id`, so the row had no reactor at
   all.
3. **A Discord person a bridge relayed** (`metadata.discord_user`, no `user_id`): likewise. The
   bridge writes the person's name and picture into the reaction row itself, because there is no
   local profile to look up, and nothing read that either.

**Fix.** Reactor identity is now resolved and drawn in three layers, mirroring the reference
client's `toReactionUser`:

- **`ReactionActor`** (`data/model/ReactionActor.kt`) is who a reaction is from, read in the
  reference's order: `metadata.discord_user` first, then `bot_id` when there is no `user_id`,
  then `user_id`. The order is load-bearing: a bridged row usually carries the bridge's own
  `bot_id` too, so reading the bot first would attribute every Discord person's reaction to the
  bridge.
- **`AppState.resolveReactionActors`** reads the profiles and bots a page's reactions name, in one
  `profiles` read and one `bots` read per page rather than one per pill -- the same shape as
  `resolveAuthors`, which now shares its body. A bridged row is skipped: its name travels with it,
  and a lookup could only come back empty. It runs wherever the reactions are read, both when a
  page loads and after a reaction is toggled.
- **`ReactorList`** (`ui/components/ReactorList.kt`) draws each reactor with their picture, name,
  `@handle`, and a `BOT`/`bridged`/`you` marker, and opens that person's profile on a tap. A row
  whose profile could not be read says **"Unknown account"** rather than a name it does not have:
  `reactions.user_id` cascades with its profile, so an unread row is a failed read, not a deleted
  account, and guessing "Deleted account" would be a claim the client cannot make.

**Alternatives rejected.** Keeping the list and resolving lazily per row would issue one read per
reactor per pill, which is the N+1 the page-level read exists to avoid. Drawing the id
(`Account 3f2a9c1e`) is what the app already did for an unresolved profile and is unreadable on a
phone. Naming a bridged person by the bridge's bot row -- which is what reading `bot_id` first
would do -- is worse than "Someone", because it is confidently wrong.

## 11. The space bar under a channel, and seven more from one report

A single session with the built APK turned up seven things that are the same shape: the app drew
a control that was not about the screen it was on. Each is below with what it was and what it is
now. The design pass that produced them is recorded in
[.agents/skills/ui-ux-pro-max](../.agents/skills/ui-ux-pro-max) -- see "The design skill" at the
end of this section for why it lives there and not in `~/.claude`.

### 11a. The bottom bar was drawn under a room

**The report:** "the bottom bar showing home and your servers is a bit unnecessary feeling when
you're viewing a channel."

It was. The bar's job is choosing a *space* -- Home or a server -- and a room is not a space: it
is the thing inside one. Drawn under a room it cost the message list about 64dp of the height a
phone has least of, for a control whose whole purpose is navigating *away* from what is being
read.

**Fix.** `HarmonyShell` takes `hideBar`, and `SignedInShell` sets it while a room is open. On a
compact window the bar is not composed at all, and the content takes back the bottom inset the
bar used to own -- so the room is not merely taller, it reaches the navigation bar rather than
stopping above it. A room is left the three ways a messaging app leaves one: the back arrow, the
system's back gesture, or a drag out of the start edge (11f). The bar returns with the list it
belongs to.

A wide window keeps its rail. A rail is a column *beside* the content, so it costs the list no
height and is where switching server while reading is one tap; the report was about a phone's
bottom bar and a change that removed the rail as well would take away the navigation the wide
layout is built around. `RoomKeepsTheSpaceRailTest` is the guard.

**Alternatives rejected.** Keeping the bar and making it shorter fixes nothing: the complaint is
that it is not about the screen, not that it is tall. Hiding it only while the keyboard is up
would leave the reading case -- the one reported -- unchanged. Auto-hiding on scroll is worse
than either, because a navigation bar that slides away while a thumb is moving is a control a
person has to catch.

### 11b. Videos had no player

**The report:** "videos do not have a built in player."

A video attachment drew a card reading `Video: standup.mp4` with a play glyph on it, and tapping
it opened a bottom sheet offering the file to the browser. The app could *send* a video and could
not *watch* one.

**Fix.** `ui/components/VideoPlayer.kt`: Android's own media stack -- `ExoPlayer` behind the
platform's `PlayerView` -- in the message row. Transport controls, seeking, the scrub bar and
their accessibility come from the platform rather than from a second implementation that would
be worse at all four. `playWhenReady = false` is deliberate: a channel of videos that all started
on their own would spend the person's data and fill the room with sound nobody asked for. The
player is released by `DisposableEffect` when its row is recycled, so scrolling past a video does
not leak a decoder per video.

A video opened from the attachment sheet gets the same player in a full-window dialog.

**Dependency.** `androidx.media3:media3-exoplayer` and `media3-ui` 1.11.1, pinned in
`gradle/libs.versions.toml`. They are the only new dependencies this change adds.

**Alternatives rejected.** A WebView around an HTML `<video>` would bring a browser engine in for
one element and lose the platform's media session, PiP and captions. `MediaPlayer` plus a
hand-drawn control bar is the same work as `PlayerView` done worse. Autoplay is the reference
client's behaviour for a *focused* video only, and there is no focused-video state here.

### 11c. A picture opened into something smaller than itself

**The report:** "when you click an image it should open a fullscreen viewer where you can zoom in
and such."

It opened a `ModalBottomSheet` with a 360dp copy of a picture that was already drawn in the row
at up to 280dp. The viewer was smaller than the thing it was viewing and could not be looked at
more closely.

**Fix.** `ui/components/FullscreenImageViewer.kt`: a full-window `Dialog` over a black scrim.
Pinch enlarges, a double tap toggles fit/2.5x about the point the finger landed, a drag pans, a
tap or the close button closes it, and back closes it. Three rules keep the zoom from becoming a
trap: the pan is clamped to the picture's own overflow, zooming back to fit recentres, and the
zoom has **buttons** as well as gestures -- a pinch is the fast way and not the only way, which
is the same rule the reaction gestures follow. The zoom level is drawn in words, so a screenshot
and a screen reader both have something to say.

**Alternatives rejected.** A third-party zoom library for one screen is a dependency for a
`graphicsLayer` and two gesture detectors. Zooming the picture *inside* the message row would
fight the list's vertical scroll.

### 11d. A channel reopened a month back

**The report:** "the app does not seem to remember where you were most recently in a channel and
will often start you scrolled up a month before the most recent messages, even if you've already
scrolled all the way down before."

Two faults, and both had to go:

1. The room scrolled to `room.rows.lastIndex` every time it composed, where `room` came from the
   `RoomCache` -- the *first page* of history that was loaded. A channel whose first page was a
   month old therefore opened a month back, and the refresh that followed appended the newest
   messages *below* where the list already was. Scrolling to the end of the cache is not scrolling
   to the end of the channel.
2. Nothing remembered where the person had got to, so even a correct end-of-list was the end of a
   list that started at the top of the first page.

**Fix.** `SessionStore.lastReadMessage(room)` / `saveLastReadMessage(room, id)`: the id of the row
at the top of the channel when it was left, keyed by account and instance like the drafts. An id
and not an index, because messages arrive while a channel is closed -- the index that was the top
is a different message by the time the channel reopens, while the id still names the same one. A
channel left at its end stores **no** anchor, so the ordinary case -- read the newest, close it --
opens at the newest next time rather than stranding the person a page back as soon as something
arrives.

The room reads the anchor once per opening, scrolls to it, and writes it back when the list
settles (not on every frame of a fling: a fling crosses dozens of rows and each would be a
DataStore write).

**Alternatives rejected.** Storing the index would be wrong for the reason above. Storing the
pixel offset is wrong as soon as a font size or a window width changes. Remembering the *newest*
message and always opening at it is what the app effectively did, and it is the bug.

### 11e. A reply did not say what it answered

**The report:** "make it so the reply ui shows what it is replying to with a brief preview just
like discord."

The composer's banner said `Replying to Noah: the first sixty characters…` in one grey line, and
the message row drew the bare word **Reply**. Neither named the thing being answered in a shape a
person recognises, and the banner could not answer the question it raised -- "which message is
this?".

**Fix.** `ui/components/ReplyPreview.kt`, two composables:

- `ReplyPreview` above the composer: a card with the accent bar, the author's name in the accent
  colour, a muted snippet of the gist, a glyph naming the attachment kind when there is one, and
  a tap that scrolls to the original. The dismiss is its own 48dp target so a thumb reaching for
  "cancel" never jumps the list instead.
- `ReplyReference` in the row: the same information at the size of one line, above the author's
  name, which is where it already was -- it now says *what* rather than *that*.

A reply whose original is older than the loaded history says "Reply" alone, which is what it drew
for every reply before. A profile that has not been looked up reads as a deleted account, so the
caller passes the name it already resolved for the original's own row rather than resolving it a
second time.

`replySnippet` reads the message's **text parts**, not `plainText`: `plainText` renders every
attachment as this app's own placeholder (`[video: attachment]`), and a quote that repeated those
would be a quote of the app's vocabulary rather than of the message. A message with no words is
named by its kind -- Photo, Video, Sticker, Poll, Link.

**Alternatives rejected.** Reusing the bottom sheet's action list as the quote is a sheet where a
line is needed. Drawing the original's whole body above the composer would push the field off a
phone with the keyboard up.

### 11f. The swipe to the channel list did not fire

**The report:** "the swipe left to right gesture does not open the channel list."

`SwipeToChannelList` existed and was wired around the room. It only claimed a drag whose finger
landed within **24dp of the start edge**, and that is the explanation for the report:

- 24dp is inside the band Android's own back gesture owns on a gesture-navigation phone, so the
  system took almost every drag the app was waiting for.
- What was left over was a sliver narrower than a thumb can reliably land in, on the one screen
  where a person is most likely to be swiping.
- A three-button device had no such competitor and still made a person aim at a 24dp target to go
  back, while the same drag anywhere else did nothing at all -- the message row had consumed it.

**Fix.** The pane claims the whole room and decides by **direction**, which is a property of the
drag rather than of where it began. The reply travels away from the start edge and this travels
towards it, so the two can never be one drag; a mostly vertical drag is still the list's scroll.
The composer is measured by the room and handed to the gesture as the one band it must not claim,
because a horizontal drag in a text field is how a person selects what they wrote.

A drag out of the system's own edge still goes to the system, which closes the room the same way:
both routes end in the same place, which is the point.

### 11g. The voice channel was see-through

**The report:** "the voice channel ui background is transparent so it looks really odd overlapping
with the different ui elements."

`ChannelList` draws `VoiceChannelScreen` *inside its own column*, and the screen drew no
background of its own. The channel list, the app bar and the frame's space bar all showed through
it, and the call's controls floated over a list of channel names.

**Fix.** The screen is an opaque `Surface` of its own, `fillMaxSize`, tagged `voice-screen`. A call
is a place, not a sheet: it gets a background and covers what is under it.

### The design skill, and why it is in `.agents`

The pass above was made with **ui-ux-pro-max** (MIT, `nextlevelbuilder/ui-ux-pro-max-skill`),
installed at [.agents/skills/ui-ux-pro-max](../.agents/skills/ui-ux-pro-max). It carries 141 UX
guidelines with rationale, 194 product palettes, 51 active styles, 74 font pairings, motion data,
and stack tables -- `jetpack-compose` among them -- searchable offline with
`scripts/search.py`, so a query answers from the repository rather than from the model's memory.

`.agents/skills` and not `.claude/skills` on purpose. `.claude` is one client's directory; the
`.agents` layout is the one Antigravity, the skills CLI and this harness all read, so the skill is
there for whichever agent works in this repository next rather than only for the one that
installed it. A user-level copy at `~/.agents/skills` is the other half of that idea and is where
it belongs for work outside this repository; it is not what is committed here, because a
repository cannot own a person's home directory.

The skill's own `scripts/search.py` needs Python 3 and nothing else. Two searches drove this
change and both are recorded above: the video rule is the skill's "auto-play video" guideline
(*prefer click-to-play; provide pause*), and the viewer's zoom buttons are its "dragging and
swipe-only interactions have button/keyboard alternatives" checklist item. Its
`references/pro-rules.md` pre-delivery checklist is what caught the missing 48dp dismiss target
and the scroll content hidden behind a fixed bar.

## 12. A thread being read had no row in the channel column

**The report:** "if you are in a thread and the thread is considered active then it should show up
in the channel list indented under the channel it is in."

The column already nested open threads, and a test pinned that. What it pinned was the *state*:
`AppState.rememberThreads` filtered the instance's answer to the open threads and filed them per
channel. What nothing pinned was the way the screen got there, and two ordinary paths never
reached it.

**Cause.** Three holes, all of them between a thread and the column:

- The threads were read **only on the branch that fetched the server tree**. The column shows a
  cached tree at once and returns, so from the second visit on it drew whatever the last full
  fetch had filed -- and a thread started after that fetch was never in it.
- A thread was remembered in the column by **one** path: the Threads sheet's own read
  (`state.rememberThreads(roomId, …)`). Starting a thread from a message, opening one from the
  column, and opening one from a notification all left the column untouched. Since the room's
  overflow sheet is the least-used way into a thread, the common case was the broken one: in a
  thread, thread active, no row under its channel.
- `AppState.rememberThread` -- the call the room made -- did **not** apply the column's rule. It
  put the thread in the list unconditionally, so a closed thread that somebody opened to read
  would have been nested as though it were open, which is the bug an earlier round fixed.

**Fix.** The thread is now reported by the one place that knows it is being read, and the rule
that decides whether it belongs in the column is applied there.

- `RoomScreen` watches the thread it is drawing and calls `AppState.rememberThread` for it, so
  every way in -- a message's **Start thread**, the sheet, the column, a notification -- ends in
  the same call.
- `AppState.rememberThread` filters by `Thread.isActive()`, the column's own rule, and sorts by
  last activity, exactly as `rememberThreads` does. A closed thread opened to read stays out.
- Answering a thread calls `AppState.noteThreadReply`, which moves the thread's own clock and
  files it: a reply is what keeps a thread open, and what brings back one that had gone quiet
  past its auto-archive window. Without it, a thread a person is answering would drop out of the
  column under them.
- `ChannelList` reads a channel's threads in an effect of its own, keyed on the tree it is
  drawing, so a cached column reads them too. The server tree stays cached; only the threads are
  re-read, because they are the part that changes while a person is in the app.
- The nesting is the list's own order: the thread's row follows its channel's row. How it is
  drawn was reported separately and settled in
  [§13](#13-a-thread-in-the-channel-column-was-not-drawn-the-way-discord-draws-one).

```
Text channels
  # general        Everything and nothing
    └ Release planning
  # design         Type, colour and motion
```

**Alternatives rejected.** Reading the whole server tree on every return to the column would keep
the threads current and also re-fetch the categories and channels on every step out of a room,
which is what made navigation slow before the cache. Re-reading every channel's threads from the
room would be one request per channel per reply. Drawing the threads flat, with the channel's name
in their supporting line, would list a thread under no channel at all, which is the opposite of
what was asked.

## 13. A thread in the channel column was not drawn the way Discord draws one

**The report, twice.** First: "the indented threads look horrible in the channel list. it should be
like Discord where a thread indented under a channel is presented just like a channel. no special
ui." That was answered by drawing a thread as a flat copy of a channel's row, at the channel's own
inset with a `Forum` glyph and no indent at all. The second report is what that got wrong: "nope
not what I wanted. it should be indented still. and the style looks slightly off. Find what the
channel list looks like on discord when a thread is there."

**What Discord actually draws**, measured from its own sidebar rather than remembered. A channel
is a glyph in the gutter and a name in the name column. A thread under it is a row with **no glyph
of its own** and its name in the channel's **name column** -- indented by exactly the width of the
channel's glyph and its gap -- and the two are joined by a **branch** in the gutter: a hairline
hanging from the channel's glyph, turning at a rounded corner into each thread's row at that row's
vertical middle, and running on to the next thread when there is one. The last thread's branch is
a `└` with nothing below it, so the group ends where it ends. The thread's name is the channel's
name: same type, same weight, same colour. Discord's line is a solid 2px token; the arm stops
short of the name.

**Cause of the second report.** The first fix read "no special ui" as "no nesting drawn at all",
so it deleted the indent along with the rail. That left a thread looking like a channel that had
lost its glyph, with nothing saying which channel it belonged to but its position in the list --
which is a different row from a channel's, not the same one, and it is not what Discord does.

**Fix.** Two row composables, and the difference between them is the one Discord draws:

- `ChannelRow` is a channel: a glyph in the gutter, its name in the name column, its description
  and its badge.
- `ThreadRow` is the same Material `ListItem`, so the container colour, the paddings, the headline
  type, the row height and the ripple are the channel's own -- that is what "the style looks off"
  was about in the first attempt, which drew the thread in a bare `Row`. Its leading slot holds an
  empty 24dp spacer, so its name lands exactly where the channel's name does: 56dp in. It has no
  supporting line and no glyph.
- The branch is painted over the thread's row (`drawThreadBranch`, in `drawWithContent`, because a
  `ListItem` paints its own container colour inside `drawBehind`): a spine at the glyph's centre,
  a quarter-circle turn of 5dp into the row's middle, an arm that stops 46dp in, and the spine
  continued to the row's bottom for every thread but the last. The colour is `borderStrong`, so it
  reads as a hairline in the dark and in the light theme alike.

```
Text channels
  # general          Everything and nothing
    ├ Release planning
    └ Copy review
  # design           Type, colour and motion
```

The reply count is not lost: the room's own **Threads** sheet lists every thread of the channel
with its message count, and the room's header names the thread and the channel it is in. It was
not doing any work in the column, where the only question is which threads are open.

**Alternatives rejected.** No indent at all -- the first attempt, and the report that sent it back.
An indent with no branch reads as a narrower row and does not say which channel it belongs to;
that is why Discord draws the branch, and why this does. A `Forum` glyph on the thread row is a
second glyph in a column that already has the channel's, and Discord's thread row has none.

## 14. A red count on every unread message, and nothing left for a ping

**The report.** *"Don't use the red badge w number ux to indicate unread messages. you can just use
the brighter white text for that. the red badge ux is for direct pings."*

**What it was.** Every surface that knew a count drew it. The phone's bar put a badge on the
space's face, the wide rail put one at the row's end, the "More" sheet put one after the name, the
channel column put one on the channel's row, and the direct-message list put one on the
conversation's row. The colour carried one distinction -- red when the space had *mentions*, the
theme's primary blue otherwise -- so the rail was a column of counts in which six unread messages
in a quiet server shouted as loudly as the one message that named you. Red meant "unread, unless
some of it was a mention", which is the opposite of the rule the report asks for: red is for the
ping, and everything else has a quieter way to be said.

**The rule, and where it lives.** `ui/components/UnreadAttention.kt` is the one place both halves
are defined, so a list cannot invent a third way of drawing a count:

- `unreadNameColor(unread, muted)` / `unreadNameWeight(unread)` -- **unread is the name**: the
  bright `onSurface` against the muted role the row would otherwise use, and SemiBold against the
  row's own quiet weight. No number, no badge, no colour.
- `PingBadge(count)` -- **a ping is the red badge**, `HarmonyColors.badge`, capped at `99+`, with a
  spoken description ("2 mentions", "3 unread direct messages").

`Space` was renamed with it: `badge`/`mentions` became `unread`/`pings`. The old names were the
confusion in code form -- a "badge" that was sometimes not a badge, and a "mention" that was
really "the red subset" -- and every call site now has to say which of the two it means.

What each surface draws:

| Surface | Unread | A ping |
|---|---|---|
| Phone bar tile (`SpaceTab`) | bright, heavy name under the face | red badge on the face |
| Wide rail row (`SpaceRow`) | bright, heavy name | red badge at the row's end |
| "More" sheet (`SheetRow`) | bright name, "6 unread" in words | red badge, "2 pinging you" in words |
| Channel column (`ChannelRow`) | bright, heavy name | red badge when the channel has mentions |
| Direct messages (a row) | bright, heavy name | red badge -- a direct message *is* a ping |

Home's counts are the direct messages: its `unread` is how many unread conversations it holds and
its `pings` is the same number, because a direct message is one person addressing this account and
there is no reading it "later" in a list of a hundred. A server's `pings` is its channels'
`unread_mentions`, which is the column `get_unread_counts` already returned and the app already
stored -- nothing new is read from the instance for this.

**A bug this found.** The phone's bar pinned the literal `Space.Home` rather than the caller's Home,
so Home's counts never reached the bar at all: on a phone, four unread direct messages drew
nothing. The new test's badge count is what caught it (`expected:<2> but was:<1>`), and the fix is
the bar drawing the Home it was given.

**Alternatives rejected.** Keeping the count badge and colouring it grey when nothing pinged: a
grey count is still a number to read on every row, and the report is about the number, not the
colour. A dot instead of a count for unread: a dot says "something" where the brighter name already
says it, and it costs the same corner of the face the badge needs for the case that matters. Making
the channel column's mention badge a different shape from the rail's: one picture for one meaning is
the point of the shared composable.

**What it is verified by.** `UnreadAttentionTest` (six cases, no instance needed for the four that
draw):

- `onlyAPingingSpaceGetsABadge` -- the bar draws exactly the two badges of the four spaces that
  ping, and no unread count is drawn as text. Mutation-checked: drawing a badge for every unread
  space again fails it with `expected:<2> but was:<3>`.
- `anUnreadNameIsBrighterThanAReadOne` -- the contrast of each name against its own row's
  background, measured from each tile's own capture. Mutation-checked: making `unreadNameColor`
  return the muted role fails it with `an unread name (0.672) is not brighter than a read one
  (0.665)`; the real rule reads `0.901` against `0.665`.
- `theBadgeIsThePingRed` -- the pill's own fill, sampled above and beside the digit. Mutation-checked:
  drawing it in `MaterialTheme.colorScheme.primary` -- the colour the old unread badge used --
  fails it with `the ping badge's fill is rgb(14,165,233), not red`.
- `anUnreadChannelIsBrightAndOnlyAMentionGetsABadge` -- the real `ChannelList` against
  `FixtureInstance`, fed the same `UnreadCountRow`s the app fills its badges from: one badge for the
  channel with mentions, none for the channel with only unread, and the unread channel's name
  brighter than the read one's, each measured on its own row's capture.
- `anUnreadDirectMessageIsAPing` -- the real `DirectMessages` list: an unread conversation keeps its
  badge.
- `spokenSaysUnreadAndPingsApart` -- "Busy, 12 unread" and "Pinged, 3 unread, 1 pinging you" are
  different sentences, so a screen reader says which is which.

The captures are `unread-space-bar.png`, `unread-channel-column.png` and
`unread-direct-messages.png` in [evidence/render](evidence/render/README.md).

**What these cannot prove.** A Robolectric capture is a drawing of the composables, not a device:
it cannot prove how the two greys read on a cheap panel in sunlight, and it says nothing about the
badge's animation when a count changes. That needs the APK on an emulator, and is not claimed here.

## How this is verified, and what is not

- **JVM render and interaction tests** (`ShellRailTest`, `ReactionGestureTest`,
  `ServerBrandingRenderTest`) draw the real composables under Robolectric's native graphics,
  inject real touches, and assert pixels and node bounds. Each was mutation-checked: reverting
  the inset padding, the collapse rule, the gesture mapping or the bucket names fails them.
- **This change's seven fixes are pinned by eight test classes**, all of which run in CI with
  no instance: `RoomHidesTheSpaceBarTest` and `RoomKeepsTheSpaceRailTest` (the bar is gone under a
  room and the rail is not), `TheRoomReachesTheBottomTest` (the room ends at the window's own
  bottom, so the bar's inset went to the room and not nowhere), `ReadAnchorTest` (an anchor
  survives a restart, is per room and per account, and is cleared when a channel was left at its
  end), `ReplyPreviewRenderTest` / `ReplyReferenceAuthorTest` (the quote names the author and the
  gist; a quote of a picture says Photo; a reference with no loaded original says "Reply"),
  `AttachmentKindTest` (a video is a video by type, by mime and by extension, and is never handed
  to the picture viewer), `VideoAttachmentRenderTest` (a video draws a player and not the old
  `Video: name` card) and `FullscreenImageViewerTest` (the viewer fills the window, the zoom
  buttons change the level it announces, and a tap or the close button dismisses it).
  `SwipeToChannelListTest` covers the gesture itself and `SwipeBackToChannelListJourneyTest` the
  gesture wired into a real destination.
- **The captures** are in [evidence/ux-report-2](evidence/ux-report-2/README.md): the reply quote,
  the reply reference in a row, the full-window viewer at 150%, and the video player in a row.
  Its README says which test made each and what it does not prove.
- **What these cannot prove.** The video player is asserted as *present and shaped*, not as
  *playing*: a JVM test has no decoder and no network, so the tile is drawn with a file that does
  not exist and the capture shows the platform's own play button and scrub bar over it. Playback,
  seeking and audio focus need the APK on a device, and are not claimed here.
- **The keyboard's place is pinned by `KeyboardInsetTest`**, which passes a synthetic 300dp IME
  on a 640dp screen and asserts the content's real bounds: that it ends at the keyboard's top
  edge rather than above it, that the space bar is not drawn behind the keyboard and comes back
  when it goes, that the composer sits at the room's bottom with the list above it, and that the
  list still has height with a reply banner and a connection banner also on screen. It was
  mutation-checked: restoring the old arithmetic -- the full safe drawing area given to the bar
  and the bar kept while the keyboard is up -- fails three of its five cases, and the failure
  reads `the content ends at y=1102.0, not at the keyboard's top edge y=680.0`.
  `ComposerKeyboardRenderTest` draws the same cases as pictures, before and after
  (`docs/evidence/render/keyboard-composer-*.png`); the "before" half is the old arithmetic
  expressed through the frame's own parameters, so the two halves cannot drift apart.
- **A message's words are not selectable, and the test measures the paint.** `MessageSelectionTest`
  injects a long press, a double tap and a drag at SDK 27 -- no selection magnifier there, so a
  selection is only paint -- and compares the row's pixels before and after. Restoring one
  `SelectionContainer` fails two of its six cases with
  `a long press must not paint a text selection, but 1594 pixels changed`; the same press opens
  the actions, a leftward drag still replies and leaves no selection behind, and a link in the
  body still opens. Two cases keep the measurement honest rather than merely green: the same row
  wrapped in a `SelectionContainer` still paints one, and the **Select text** sheet -- the one
  place selection is meant -- still paints one too, so a change that removed selection everywhere
  would fail instead of passing. The pictures are in
  [evidence/render](evidence/render/README.md): `message-long-press-no-selection.png` and
  `message-double-tap-no-selection.png` (the row, pressed), `message-selection-before.png` (the
  same press with the container back, which is what the report saw),
  `message-select-text-sheet.png` (the deliberate way to select), `social-post-long-press-no-selection.png`
  and `social-post-menu-copy-text.png` (a post's body is not selectable either, and its menu is
  where the copy went), and `actions-sheet-dark.png`/`actions-sheet-light.png`, which now carry
  **Select text** under **Copy text**.
- **Notification delivery** is pinned twice. `NotificationDeliveryTest` covers the rule as a pure
  function: a fresh install delivers nothing, a days-old unread row is never delivered, a row is
  delivered once, a row read elsewhere withdraws its entry, and an unreadable timestamp is not
  news. `NotificationCoordinatorTest` drives the real coordinator against `FixtureInstance` and
  reads the tray back from Robolectric's `NotificationManager`, so "not posted" means the system
  would not have shown it. Both were mutation-checked: restoring the old "post every unread row"
  rule fails seven of the fifteen.
- **The branding test fetches over HTTP** from a local server that 404s the hyphenated bucket
  names, so a wrong bucket is a missing picture rather than a passing string comparison.
- **A thread being read is pinned where the screen is**, by `ChannelThreadsTest`: it draws the
  real `ChannelList` against `FixtureInstance` and measures the thread's row against its
  channel's -- the full width, its name at the channel's own name's x, one level in from the row's
  edge, the row between that channel and the next one, no reply count beside it -- and then reads
  the paint: every pixel the thread's row draws in the gutter is on the branch's spine or on its
  arm, and the arm is there at the row's middle. So a list that nests the wrong thread, nests it
  under the wrong channel, drops the indent, gives the row a glyph of its own, draws no branch or
  drops it altogether fails. A second case drives the room: the thread's id is put where a
  notification puts it, the real `RoomScreen` opens it, and the column's state has to gain the
  row. Mutation-checked by removing the room's report, which fails it with "the thread being read
  is not nested under its channel"; `AppStateCacheTest.aClosedThreadIsNotNestedUnderItsChannel`
  fails when `rememberThread`'s filter is dropped, and `replyingToAThreadKeepsItInTheColumn`
  covers the reply's clock. `docs/evidence/render/channel-threads.png` is the capture.
- **The Threads sheet** is pinned offline, so the check runs in CI rather than skipping without an
  instance: `ThreadsSheetTest` drives the real `RoomScreen` against `FixtureInstance`, whose
  `threads` read answers with threads in three states, and requires every one on screen with the
  closed ones marked and the empty sentence gone. It was mutation-checked: restoring the
  `filter { it.isActive() }` the sheet used to apply fails two of its three tests, the first with
  "the sheet dropped the thread \"Frame sketches\"". `VoiceAndRoutingTest` pins the three states
  and the label; `docs/evidence/render/threads-sheet.png` is the capture.
- **Emulator evidence** is two runs, and they are worth different things.
  `docs/evidence/emulator/` is the `aosp_atd` run: the real debug APK against a stub instance,
  driven with `adb shell input`, recording the accessibility tree and the rail's measured
  bounds. That image cannot take a screenshot, so it is trees and measurements.
  `docs/evidence/ux/` holds what the `ui30small` run measured — the real
  insets the app is given, a 48 px status bar and a 96 px emulated cutout — and the tree of a
  separate failure it found: a truncated preferences file that bricked the app. That run was
  blocked before sign-in (first by `SystemUI`'s own ANR dialog, then by the AOSP keyboard's
  contacts prompt), so it does **not** hold the rail's bounds under those insets; the JVM test
  is what pins the rail. The directory's README says which file is which.
- **Not verified:** a physical phone. Every run is on an emulator under TCG, at one density
  and one screen size; rotation, gesture navigation and a real device's particular cutout shape
  are untested.
- **Not fixed:** the cover renders at 96dp with `ContentScale.Crop`, but the app does not ask
  the instance's image proxy for a cover-sized variant the way the reference client does, so a
  large upload is downloaded whole and scaled on the device. That is a bandwidth cost, not a
  missing picture.
