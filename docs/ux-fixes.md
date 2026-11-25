# System bars, the collapsing rail, reaction gestures, server branding, repeat notifications

Reports from using the built APK, and what each one turned out to be. The first four are recorded
together because three of them share a cause worth naming: **the app drew itself as if the
phone's window were the whole screen and as if every stored image were an absolute URL.** The
next two were found by using that same build: a preferences file that bricked the app, and a
notification the tray would not stop repeating. The last four are the same kind of report from
the same habit: a keyboard-sized deadspace under the composer, a message body that selected itself
whenever it was pressed, a channel that had threads told it had none, and a reactor list that named
nobody.

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

## How this is verified, and what is not

- **JVM render and interaction tests** (`ShellRailTest`, `ReactionGestureTest`,
  `ServerBrandingRenderTest`) draw the real composables under Robolectric's native graphics,
  inject real touches, and assert pixels and node bounds. Each was mutation-checked: reverting
  the inset padding, the collapse rule, the gesture mapping or the bucket names fails them.
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
- **The Threads sheet** is pinned offline, so the check runs in CI rather than skipping without an
  instance: `ThreadsSheetTest` drives the real `RoomScreen` against `FixtureInstance`, whose
  `threads` read answers with one thread in each state, and requires all three on screen with the
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
