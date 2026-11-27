# Redesign evidence: the pages, drawn from a fixture instance

These are the app's **own screens**, with rows in them. Each one renders the real composable --
`YouScreen`, `NotificationsScreen`, `SocialHomeScreen`, `DriveScreen`, `ServerManagementScreen`,
`ChatsDestination` -- against a fixture Harmony instance (`app/src/test/.../FixtureInstance.kt`)
that answers the PostgREST and RPC reads those screens make. So a capture is a page: an account
settings list, an inbox with day groups, a feed of cards, a file browser with a quota, a server's
management sections.

The first round of this work shipped a gallery of tokens instead, because every real screen needs
an instance and there was none. That was the wrong evidence: a picture of a radius proves a radius
is round, not that a settings page is a settings page. The fixture is what fixed it.

Every test waits for **text only that screen's own repository can produce** -- a display name, a
file name, a notification actor -- rather than for the app bar, which draws before the data
arrives. A capture therefore cannot be of a spinner, and several were discarded during this work
precisely because they were: a Drive capture showed its retry banner, and two Social captures
showed an empty feed, until the fixture answered the right endpoints.

## The pages

Compact captures are 400x900dp, expanded are 1000x800dp, both at xhdpi.

| File | What it is |
|---|---|
| `page-you-dark.png`, `page-you-light.png` | The account screen: the identity at the top, then grouped settings rows. Five tabs became this. |
| `page-you-appearance-dark.png` | A row opened: the appearance section, with the live preview and the account's own look. |
| `page-you-version-dark.png` | The same list at the About rows, where the build's own version name and versionCode are shown. |
| `page-you-expanded-dark.png` | The same page at 1000dp. |
| `you-updates-dark.png`, `you-updates-off-light.png` | You → Updates, drawn from a `MockWebServer` answering the manifest a release publishes: the switch, when the check last ran, and the build it found with its size and its download. The second is the same section with the check off. |
| `update-available-dark.png`, `update-available-light.png` | The notice an installed app shows when a release lands: the build, the APK's name and size, "What changed" when the release published a page, and one **Download** that opens the APK itself. |
| `page-alerts-dark.png`, `page-alerts-light.png` | The notification inbox: an app bar whose supporting line says what is waiting, one segmented filter, day groups, unread rows toned. |
| `page-alerts-expanded-dark.png` | The inbox at 1000dp. |
| `page-social-dark.png`, `page-social-light.png` | The feed: a scrollable primary tab strip for the timeline, and each post as a card. |
| `page-social-timelines-dark.png` | The same feed, with the tab strip in view. |
| `page-drive-dark.png`, `page-drive-light.png` | The file browser: quota in the app bar, one filter row, files with size, kind and owner. |
| `page-drive-types-dark.png` | The type filter opened: every type, with a tick on the current one. |
| `page-server-dark.png`, `page-server-light.png` | Server management: six tabs became six rows that each say what is inside. |
| `page-server-members-dark.png` | A section opened from that list, with the app bar naming it and back returning. |
| `page-signin-dark.png`, `page-signin-light.png` | The signed-out page: a band naming the instance and the one rare action on it, then the form, then the next step as its own row. |
| `page-signin-error-dark.png` | A refused sign-in: the instance's sentence on an error surface above the button, not a line of red text under the password field. |
| `page-signin-expanded-dark.png` | The same page at 1000dp: the band is edge to edge, its contents and the form share one centred reading column, so no field is a thousand pixels wide. |
| `page-instance-picker-dark.png`, `page-instance-picker-light.png` | The first screen after installing: the instance picker, now the signed-out page's sibling. A band carries the wordmark and the question, the address field sits in the reading column, and Connect is the only filled thing. |
| `page-instance-picker-error-dark.png` | A refused address: the reason on an `errorContainer` surface between the field and the button, not as red text under the field where it reads as that field's supporting text. |
| `page-instance-picker-expanded-dark.png` | The picker at 1000dp: the band edge to edge, the form inside the same centred 760dp column the sign-in form uses. |
| `actions-sheet-dark.png`, `actions-sheet-light.png` | The message-actions sheet: a titled list of actions, each with a glyph and, where it helps, a line saying what it does. The quick reactions stay a row above it. |
| `page-voice-lobby-dark.png` | A voice channel before joining: who is already in the channel is the body of the page, because they are the reason to join, with the one filled button and what will be published. |
| `page-voice-lobby-light.png` | The same lobby in the light palette. |
| `page-voice-unavailable-dark.png` | The same lobby on an instance with no voice server: the reason, and no button to press. |
| `page-voice-roster-dark.png` | The roster inside a call: speaking is a ring around the face and a tinted row, and mute and camera are badges on the face. |
| `page-voice-controls-dark.png` | The call's control bar: an "on" toggle is a filled circle, an "off" one is an outlined circle, and Leave is pinned outside the scroll in error colour. |
| `page-voice-controls-light.png` | The same bar in the light palette, with the toggles in the other state: the outline is what has to survive a light theme, and this is where that is checked. |
| `page-voice-ended-dark.png` | A call the server ended, with the reason and both things a person can do. |

## The spaces as navigation

These six are the redesign's central claim, drawn from the **real `SignedInShell`** against the
fixture instance -- not a gallery and not a hand-built layout. They are what a person sees after
signing in.

| File | What it is |
|---|---|
| `nav-phone-home.png` | A 400dp phone, signed in: the direct messages, with the spaces in a bar at the bottom -- Home, the server, and "More". The server is one tap from here. |
| `nav-phone-server.png` | The same phone after one tap on the server: its channels, with its own face and name in the list's bar. One tap, no drawer and no destination change. |
| `nav-phone-map.png` | The "More" sheet: every server with its unread count, then the four rare destinations under "Elsewhere", each with a line saying what is inside. |
| `nav-phone-you.png` | The account page, reached from that sheet -- one tap from the conversation, and the spaces are still at the bottom. |
| `nav-wide-home.png` | The same screen at 1000dp: the spaces are a rail that lists every server *by name*, beside the conversation. |
| `nav-wide-map.png` | The map at that width: every server and all four places on one surface. |

`ServerFirstNavigationRenderTest` and `WideServerFirstNavigationRenderTest` produce them and
assert the claim as they go: the bar at 400dp and the rail at 1000dp, a server reachable from the
conversation in one tap, and the map reaching the four destinations.

The design-system captures (`design-phone-{dark,light}.png`, `design-tablet-custom.png`,
`design-expanded-chats-{dark,light}.png`) now draw the same frame with fixture spaces, so they
show the new navigation in both palettes and at both widths.

## What is not here

- **A populated room on a device.** Needs a live instance. The room's own render is in
  [../render/chats.png](../render/chats.png), and the frame's device capture is
  `device-chats-compact-dark.png` in this folder.
- **The instance picker on a device.** `before-instance-picker-device.png` is a real device
  screenshot of the picker as it was, kept because it is the clearest statement of what changed: a
  centred column of a wordmark, a heading, a paragraph, a field and a button. The redesigned
  picker's captures are JVM renders (`page-instance-picker-*`), because this host's emulator
  cannot boot a guest.
- **Touch, IME, predictive back, rotation and motion.** A still picture cannot show them;
  [verification.md](../../verification.md) says what would.
- **The expanded Chats three-pane layout.** `design-expanded-chats-{dark,light}.png` draws the
  frame and the design system at that width, and `ExpandedChatsRenderTest` measures the panes;
  a device capture was attempted and the emulator's system server died during the install.
