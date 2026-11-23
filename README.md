[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

# Harmony Android
Native Android client for [Harmony](https://mony.lol). 

# Table of Contents
- [Overview](#overview)
- [Download](#download)
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

# Download

Every push to `main` publishes an installable APK as **the repository's release**. That is
the only place it is published, and the only route you need:

**[Download the latest APK](https://github.com/WWPOL/harmony-android/releases/download/android-rolling-main/Brapmony-latest.apk)**
· **[the release](https://github.com/WWPOL/harmony-android/releases/latest)**

1. Open that link on the Android device and tap the downloaded file, or run
   `adb install -r Brapmony-latest.apk`.
2. Allow installing from the browser or file manager when Android asks. The APK is
   **test-signed**, not store-signed, so it installs over a build that was signed with the same
   test key and not over one that was not; if Android refuses with a signature mismatch, uninstall
   the older copy once and install again.
3. The build needs Android 8.0 (API 26) or newer.

The file names say what the file is:

| Name | What it is |
| --- | --- |
| `Brapmony-latest.apk` | The newest build of `main`. The stable name to bookmark. |
| `Brapmony-1.9.6-606ab10-debug.apk` | One build: the version, the commit, and `-debug` for the test key it is signed with. |
| `Brapmony-1.9.6.apk` | A version-tag release, signed with the release key. `-debug` when it is test-signed instead. |

Each build is kept under its own name beside `Brapmony-latest.apk`, with a `.sha256` beside
it, so a particular build stays downloadable after later pushes. Version releases (`vX.Y.Z`)
are cut from tags and appear as full releases. A build of any other branch is a prerelease
and is not offered as the repository's release. [docs/builds.md](docs/builds.md) describes
exactly what is published where and which build installs over which.

> The rolling release is a window on `main`, not an archive: it keeps the newest ten builds
> of the branch and drops older ones. The version release of a tag is the archive.

**What you downloaded, and which commit it came from.** Every APK published here carries a
signed **build provenance attestation**. GitHub signs a statement that this exact file was
produced by this repository's workflow at a particular commit, so the file can be checked
without trusting the download page:

```sh
gh attestation verify Brapmony-latest.apk --repo WWPOL/harmony-android
```

To pin it to a workflow and a commit as well:

```sh
gh attestation verify Brapmony-latest.apk --repo WWPOL/harmony-android \
  --signer-workflow WWPOL/harmony-android/.github/workflows/android.yml \
  --source-digest <the commit>
```

GitHub also computes a digest of every release asset it stores, which is a check of the
stored bytes that does not come from this build at all.

There is deliberately **no container package**. The APK is a file on a release, which is what
a phone can download and install; pushing it to a container registry made a `docker pull`-style
package appear on the repository for something that is not an image and that no phone can
fetch, so that is gone.

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
| The release page as it was, and the asset names the fix produces | [docs/evidence/release/](docs/evidence/release/) |
| How the repository was restructured to hold the app alone | [docs/restructure.md](docs/restructure.md) |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security reports: [SECURITY.md](SECURITY.md).

# License

Copyright (C) 2026-present the WWPOL contributors. This program is free software under the
[GNU General Public License, version 3 or later](LICENSE); there are no additional terms.

The app links against AndroidX, Kotlin, OkHttp, Coil and LiveKit, all under the Apache
License 2.0. See [COPYRIGHT](COPYRIGHT).
