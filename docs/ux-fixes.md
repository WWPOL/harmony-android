# System bars, the collapsing rail, reaction gestures, server branding, repeat notifications

Reports from using the built APK, and what each one turned out to be. The first four are recorded
together because three of them share a cause worth naming: **the app drew itself as if the
phone's window were the whole screen and as if every stored image were an absolute URL.** The
last two were found by using that same build: a preferences file that bricked the app, and a
notification the tray would not stop repeating.

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

## How this is verified, and what is not

- **JVM render and interaction tests** (`ShellRailTest`, `ReactionGestureTest`,
  `ServerBrandingRenderTest`) draw the real composables under Robolectric's native graphics,
  inject real touches, and assert pixels and node bounds. Each was mutation-checked: reverting
  the inset padding, the collapse rule, the gesture mapping or the bucket names fails them.
- **Notification delivery** is pinned twice. `NotificationDeliveryTest` covers the rule as a pure
  function: a fresh install delivers nothing, a days-old unread row is never delivered, a row is
  delivered once, a row read elsewhere withdraws its entry, and an unreadable timestamp is not
  news. `NotificationCoordinatorTest` drives the real coordinator against `FixtureInstance` and
  reads the tray back from Robolectric's `NotificationManager`, so "not posted" means the system
  would not have shown it. Both were mutation-checked: restoring the old "post every unread row"
  rule fails seven of the fifteen.
- **The branding test fetches over HTTP** from a local server that 404s the hyphenated bucket
  names, so a wrong bucket is a missing picture rather than a passing string comparison.
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
