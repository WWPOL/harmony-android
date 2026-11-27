# The app's design

## What this app is

A Kotlin/Jetpack Compose client for Harmony instances. It is not a WebView around the web
client: Android draws the UI, and the app talks to an instance's own auth, REST, storage and
realtime endpoints. It ships none of that server -- the instance is somebody else's
deployment, chosen by the person using the app.

**Work in progress.** This document explains the intended architecture. A feature is not
implemented or verified because it is named below: emulator interaction, the instance's own
rows, and screenshots have to substantiate it. A capability the app does not have must be
reported as missing, never represented by a success-shaped control.

## Build setup

AGP 9.5.1, Gradle wrapper 9.9.1, built-in Kotlin (AGP 9), Kotlin Compose/serialization
compiler plugins 2.4.21 and Compose BOM 2026.09.00, all resolved from the official
Google/Maven repositories. There is no `org.jetbrains.kotlin.android` plugin: AGP 9 supplies
Kotlin itself. The project compiles and targets SDK 37 with minimum SDK 26; current AndroidX
libraries require compilation against SDK 37. The software-emulator validation uses API 30,
so SDK 37 behaviour changes remain explicitly unverified.

JDK 21 runs the build. Local SDK paths and generated outputs are ignored by Git. Release
signing credentials must never be committed. Every build type carries the one application id,
`io.github.wwpol.brapmony`; a debug build adds only `-debug` to the version name, so it is an
update of a release build rather than a second app beside it.

## Data and security boundaries

- GoTrue authenticates the user. PostgREST requests carry the instance's public anon key and
  that user's access token, never an admin or service-role key.
- Instance selection discovers the instance's published config. Production networking
  requires HTTPS; cleartext loopback is for local emulator testing only.
- Token refresh is serialized; persisted secrets use Android-backed protection, are excluded
  from backup, and are cleared on sign-out. All network I/O runs off the UI thread.
- Profile IDs and auth user IDs may differ. Membership and message ownership follow the
  actual schema, never an assumption that the two ids are interchangeable.
- Row-level security is authoritative. The app surfaces permission failures and never
  reports a write the instance rejected as a success.
- History is paginated. People and membership details are batch loaded, not fetched per
  message or per server on the startup path.
- Encrypted-channel sends must never silently downgrade to plaintext. Interoperability with
  the reference client needs the same Megolm protocol, recovery and cross-device sharing
  flows; that work is not done (see [parity-audit.md](parity-audit.md)).

The source separates `data/api` (PostgREST and auth transport), `data/model` (the wire
shapes), `data/repo` (one repository per surface), `data/session` (the stored session and its
token) and `ui`. `AppContainer` assembles the dependencies explicitly, Compose state observes
repository results, and every screen preserves failure, loading and retry states.

### The live connection reconnects by itself

A room's realtime socket is a supervised loop, not a single attempt: when it drops, the app
opens another one. Nobody is asked to press anything, because "the connection came back" is not
something a person should have to arrange.

- `data/api/Realtime.kt` runs the loop. Each attempt joins the room's topic, and a socket that
  closes, fails or goes silent ends only that attempt. Between attempts `ReconnectPolicy` waits
  1 s, then 2, 4, 8, 16 and 30 s, staying there; a join that succeeds starts the schedule over.
  Jitter shortens each wait by up to 20%, so many clients dropped by one outage do not all come
  back in the same millisecond.
- The access token is read again before every attempt (`AuthRepository.freshToken`), so a
  reconnect after a refresh joins with the token that is valid now rather than the one the room
  was opened with.
- The heartbeat's `send` result is checked and OkHttp's own ping notices a connection that has
  gone silent, so a socket the OS killed is replaced in seconds instead of being written to for
  ever.
- `data/api/NetworkMonitor.kt` reports the device regaining a network, and that -- like the
  banner's own button -- only *shortens* a wait. The backoff alone is enough; these are the
  accelerators.
- A join the instance **refuses** (the account lost access to the room, the session ended) ends
  the loop instead of being retried on a timer, which would be a loop against a refusal.

`ui/HarmonyAppState.kt` draws this as `LiveStatus.Reconnecting(attempt, reason)`: a banner that
says the app is reconnecting and offers to skip the wait, and nothing at all while connected.
The socket is the only writer of that state; a failed REST read says a page did not load, not
that the connection is down.

Three rules keep that banner from becoming the noise the app used to be:

- **It belongs to the room that is open.** `AppState.ownLive`/`releaseLive` are called by the
  room as it opens and closes, and a status from any other room is dropped. Before this, a
  channel closed while its socket was down left its "Reconnecting" in the shared state, and the
  next channel opened under the previous channel's banner.
- **A blip that heals is not news.** `ConnectionBanner` holds the reconnect back for
  `RECONNECT_GRACE_MILLIS` (3 s). A handover or a radio waking is back inside that, and draws
  nothing at all; an outage that lasts is reported, and its attempt count then updates in place.
  A refusal is drawn at once, because waiting would only delay the one thing a person can act
  on.
- **A transport failure is a sentence, not an exception's message.** `data/api/Transport.kt`
  maps every `IOException` to `Offline` with copy a person can read, so a screen that shows
  `error.message` no longer prints `Unable to resolve host "…": No address associated with
  hostname`. The instance's own refusals (`ApiException`) and this app's own checks keep their
  wording, because those say something actionable.
- **A refresh that fails over rows already on screen says nothing.** The connection strip above
  it is the place for "the connection was lost"; repeating it in the room's own error banner is
  what showed two disconnected notices at once.

```
app/src/main/java/lol/mony/harmony/
  AppContainer.kt   the dependency graph, built once per process
  data/api/         PostgREST and auth transport, realtime
  data/model/       the wire shapes, one file per table family
  data/repo/        one repository per surface
  data/session/     the stored session and its token
  data/voice/       the LiveKit engine and spatial audio
  notifications/    channels, the coordinator and the deep-link targets
  ui/components/    shared drawing: avatars, message content, reactions, the rail
  ui/screens/       one package per destination
  ui/theme/         the account's own appearance
app/src/test/       JVM and Robolectric tests, mirroring those packages
```

## Android interpretation of Harmony

Material 3 Expressive: its top bars, navigation bar and rail, modal sheets, text fields,
switches, ripples, `LoadingIndicator` and system back, installed through
`MaterialExpressiveTheme` so the platform's own motion scheme is the app's. The frame follows the
window: a bar at the bottom of a phone, a rail at the side of anything 600dp or wider. The
navigation is the **spaces** -- Home and every server -- because that is where nearly all of a
session is spent; the feed, the drive, the inbox and the account are one tap away behind the
rail's "More" control instead of taking half the frame. The channel list is a pane inside the
Chats destination, and wide windows show it beside the room it selects. Messages stay left aligned and chronological rather than being wrapped in
generic chat bubbles: Harmony's message model is not a bubble model.

[redesign.md](redesign.md) is the design document: the frame -- the **spaces** as the app's
navigation, with Home and every server one tap away at any width -- the Chats list-detail, the
"More" sheet that holds the four rare destinations, the type, shape and motion system, the two
colour sources, what was verified and what is still limited.

Palette anchors are the instance look's sky blue `#0EA5E9`, secondary `#38BDF8`, dark surface
`#1A1A1E`, sidebar `#17181A`, text `#F2F3F5` and presence green `#43B581`. Typography is
Android's own face, scaled from the account's message size and zoom against one role scale.
Rounded controls and native selection affordances provide the Android identity; colour,
hierarchy, the server rail and the chat composition carry the instance's identity. The app
does not replace that identity, and does not choose wallpaper colours over an explicit theme:
"wallpaper colors" is an explicit, device-local choice, and the branded palette is what it falls
back to.

### Appearance compatibility

`profiles.appearance_settings` is the compatibility contract: the app loads the account's
theme, custom colours, lightness/chroma, font name, message size, zoom, compact mode,
contrast, timestamps, 24-hour time, motion and accessibility preferences, and preserves
unknown keys on save. CSS skins cannot be executed as native UI, so their supported semantic
settings are mapped natively and unsupported visual effects are identified honestly rather
than approximated silently.

The reference web client's per-device `appearance-sync` decision distinguishes account look
from device-only look. Saving shared message and accessibility preferences while a device
keeps its own look must preserve the account's look keys. Local persistence is scoped by
account and instance, so one person's look cannot leak into another's.

The app carries no third-party typeface: a font name the account stores that the APK does not
have is drawn in the device's own face, and the appearance panel says so.

OKLCH colour utilities allow theme generation without hard-coded RGB approximations. Native
palette role mapping is an interpretation, not a claim of pixel equality with CSS. Contrast,
light/dark/custom themes, large fonts and narrow screens are verified with actual screenshots.

## The work, in order

1. Core: instance picker, sign-in/register/reset, session restoration and MFA; the app shell;
   servers/channels and DMs; paginated history; sends, edits, deletes, replies, threads,
   reactions, pins, search, unread/read markers, live updates and drafts.
2. Social: home/local/federated timelines; post/reply/poll/media composition; favourite,
   boost, reactions, bookmark; profiles, follows/requests, lists, hashtags, explore;
   notification actions and read state.
3. Account and servers: profile and per-server profile edits; appearance and notification
   settings; privacy, security, sessions, language; server/channel/category creation, invites,
   members, roles/permissions, moderation and server settings.
4. Platform: attachments/storage/media; Drive; encryption/recovery/device approval;
   voice/video/LiveKit; foreground service and capture; push/deep links/share/downloads.

Independent areas can be implemented in parallel worktrees and integrated into the shared app
shell. Core API and model changes, and navigation integration, are sequential to avoid
incompatible abstractions.

## Verification strategy

Compile, lint and unit tests plus emulator-driven UI interaction. For each feature, record the
preconditions, the actions, the instance's read-back, the expected error cases and where the
screenshot is. A clickable destination or a UI hierarchy dump is not visual verification;
inspect rendered screenshots, not black framebuffer captures.

Test both members and non-members, account switching, offline and expired sessions,
rotation/back, keyboard resize, light/dark/custom/high-contrast and large text. Store complete
logs of each build and test invocation and cite them accurately. Do not claim parity until
[parity-audit.md](parity-audit.md) has no unimplemented or unverified entry.
