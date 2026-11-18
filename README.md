[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

# Harmony Android
Native Android client for [Harmony](https://mony.lol). 

# Table of Contents
- [Overview](#overview)
- [Development](#development)
- [License](#license)

# Overview
Written in Kotlin using Jetpack Compose. 

- **Package:** `io.github.wwpol.brapmony`
- **Android Target:** 8.0 (API 26); compile and target SDK 37

Supports:

- Servers, channels, threads and direct messages, with paging and optimistic sends
- Message parts: text, mentions, emoji, images, links, attachments and stickers
- Reactions, edits, deletes, pins, polls, and a thread sheet
- Server management: channels, roles, members, invites and bans
- Social feeds (following, local), profiles, follow/block lists, and posting
- The Drive file browser, with ownership and sharing
- Notifications: a local inbox, channels per kind of activity, and notification taps that
  open the screen they name
- Account settings, and the account's own appearance: theme, accent, density, text size
- Voice and video channels over LiveKit

**Not finished.** This is not yet a parity-complete client: end-to-end encryption and key
recovery, push notifications, and several media and administration flows are missing.
[docs/parity-audit.md](docs/parity-audit.md) names every gap, and
[docs/parity-matrix.md](docs/parity-matrix.md) tracks each screen against the reference web
client. Do not treat a green build as a claim of parity.

# Development
## Build

JDK 21 and the Android command-line tools are all that is needed beyond the wrapper:

```sh
sdkmanager 'platform-tools' 'platforms;android-37.0' 'build-tools;37.0.0'
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk21

./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`. AGP 9.5.1 supplies
built-in Kotlin, so the project deliberately does **not** apply `org.jetbrains.kotlin.android`.
The wrapper pins Gradle 9.9.1 and verifies its official SHA-256.

No signing keys, SDK installations, credentials or generated APKs belong in Git. Release
builds are unsigned unless signing is configured externally; see
[docs/builds.md](docs/builds.md).

## Test

Unit, model and MockWebServer tests run on the JVM and need no device:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Some suites draw real composables through Robolectric's native graphics, so a screen's pixels
can be captured and compared without an emulator. Anything that needs a real screen runs on an
**emulator**, never on a person's phone: installing on a phone replaces an app somebody is
using, and driving it takes their screen.

The tooling that publishes APKs has its own tests, and the documentation's links are checked,
which needs Node 24:

```sh
npm ci
npm test
```

## Run it

`demo.sh` builds a debug APK, installs it on an attached emulator and opens it:

```sh
export ANDROID_HOME=/path/to/android-sdk
./demo.sh
```

It points the build at a Harmony instance on the emulator's host gateway
(`http://10.0.2.2:54480` by default; override with `NATIVE_INSTANCE_URL` and
`NATIVE_ANON_KEY`). Leave those unset for a build that starts on the instance picker instead.
Use a disposable account: this preview is not parity-complete, and password reset needs an
SMTP provider the instance may not have.

`doctor.sh` diagnoses a launch that aborts before any app code runs -- the platform's failure
to mount the app's device-protected data directory -- and repairs it in place without losing
data:

```sh
./doctor.sh --serial emulator-5554
./doctor.sh --serial emulator-5554 --repair --apk app/build/outputs/apk/debug/app-debug.apk
```

`upgrade-test.sh` proves that two builds install one over the other with the app's data kept.
See [docs/builds.md](docs/builds.md#signing) for what each signing configuration means.

## Documentation

| Topic | Document |
| --- | --- |
| Architecture, appearance compatibility, completion criteria | [docs/design.md](docs/design.md) |
| How builds are published, signed and updated | [docs/builds.md](docs/builds.md) |
| What is verified, and what is not | [docs/verification.md](docs/verification.md) |
| Gap audit against the reference client | [docs/parity-audit.md](docs/parity-audit.md) |
| Screen-by-screen parity matrix | [docs/parity-matrix.md](docs/parity-matrix.md) |
| Message body decoding and drawing | [docs/message-content.md](docs/message-content.md) |
| Captured screens and logs | [docs/evidence/](docs/evidence/) |
| How the repository was restructured to hold the app alone | [docs/restructure.md](docs/restructure.md) |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security reports: [SECURITY.md](SECURITY.md).

# License

Copyright (C) 2026-present the WWPOL contributors. This program is free software under the
[GNU General Public License, version 3 or later](LICENSE); there are no additional terms.

The app links against AndroidX, Kotlin, OkHttp, Coil and LiveKit, all under the Apache
License 2.0. See [COPYRIGHT](COPYRIGHT).
