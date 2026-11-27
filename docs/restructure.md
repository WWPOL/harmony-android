# Restructuring the repository to hold the Android app alone

## The problem

This repository was a Harmony deployment: a Vue web client, a Tauri desktop and Android app, a
Node federation backend, a Discord bridge gateway, the Supabase schema, the self-host installer
and its docs. The native Android client was added to it as a subdirectory, `android-native/`,
with its own Gradle project, its own workflows and its own docs page under `docs/build/`.

Two things followed, and both are what this change fixes.

1. **The repository held code we did not write.** Everything except `android-native/`,
   `e2e/android-native/`, the five `scripts/android-native-*` files and
   `docs/build/native-android*` is upstream's: the web client, the server, the desktop shell,
   the schema, the installer. Keeping it means redistributing it, and it means every clone,
   every CI run and every reader pays for code that has nothing to do with the app.
2. **The app was a guest in its own repository.** Its build lived one directory down, its
   licence came from the parent project's AGPL-with-additional-terms, and its README described
   a server with an experimental client attached. Nothing about the tree said "this is an
   Android app".

## What the repository is now

The Android app, at the top level, under the GPL v3. Nothing else.

```
app/                 the application module: Kotlin, Compose, resources, tests
gradle/              the version catalogue and the wrapper
build.gradle.kts     the root build
settings.gradle.kts  the module list
gradlew, gradlew.bat
gradle.properties
VERSION              the version, stamped from a tag at release
demo.sh              build, install on an emulator, open
doctor.sh            diagnose and repair a launch that dies before the app
upgrade-test.sh      prove two builds install one over the other, keeping data
e2e/                 emulator journeys, pixel/UI helpers, the stateful fuzzer
scripts/             the release tooling and its tests
docs/                the design, the builds, the evidence
.github/             two workflows, issue and pull request templates
```

### What moved where

| Was | Is | Why |
| --- | --- | --- |
| `android-native/app/` | `app/` | The app is the repository; it is not one module among many |
| `android-native/*.kts`, `gradle/`, `gradlew*` | the same, at the root | One Gradle project, at the root, as every Android app has |
| `android-native/{demo,doctor,upgrade-test}.sh` | the root | They are the app's own tools |
| `scripts/android-native-release.mjs` | `scripts/release.mjs` | There is only one app to release, so the qualifier says nothing |
| `scripts/android-native-{workflow,doctor}.test.mjs` | `scripts/workflow.test.mjs`, `scripts/doctor.test.mjs` | The same |
| `scripts/android-native-steps.mjs` | `scripts/workflow-steps.mjs` | It runs a workflow's shell steps; it is not Android-specific |
| `scripts/android-native-fake-api.mjs` | `scripts/fake-github-api.mjs` | It stands in for the GitHub releases API |
| `scripts/name-artifact.sh` | the same, rewritten | It named five platforms' artifacts for one release; now it names one APK |
| `e2e/android-native/*` | `e2e/*` | The only e2e here is the app's |
| `docs/build/native-android.md` | `docs/design.md` | The app's design |
| `docs/build/native-android-builds.md` | `docs/builds.md` | How the app is built and published |
| `docs/build/native-android-parity.md` | `docs/parity-audit.md` | The gap audit |
| `docs/build/native-android-parity-matrix.md` | `docs/parity-matrix.md` | The screen-by-screen record |
| `docs/build/native-android-verification.md` | `docs/verification.md` | How verification is done |
| `docs/build/native-android-{message-content,ux-fixes,preferences-recovery,launch-data-directory}.md` | `docs/` | Four records of specific faults and fixes |
| `docs/build/evidence/android-native-*/` | `docs/evidence/{doctor,emulator,render,ux}/` | The captures, named for what they show |
| `.github/workflows/android-native.yml` | `.github/workflows/android.yml` | The app's build |
| `.github/workflows/release.yml` | rewritten | Tag → the APK on that tag's release |
| `.github/workflows/{ci,tauri,images,schema-drift}.yml` | deleted | They built and shipped the server, the web client and the desktop app |

### What was deleted, and why

All of it was upstream's, none of it was needed to build, test or run the app, and each
directory carried its own licences and its own reasons to keep it:

`src/` (the Vue web client), `public/` (its assets, fonts, emoji, sounds), `src-tauri/` (the
desktop shell and the Tauri Android app), `federation-backend/`, `bot-gateway/`,
`bot-plugins/`, `bull-board/`, `db_schema/`, `dev/`, `self-host/`, `supabase/`, `tests/`,
`webrtc/`, `e2e/`'s server-side files, the upstream `scripts/`, the VitePress site under
`docs/`, and the top-level configuration that only the web client read (`vite.config.ts`, the
`tsconfig*`, `vitest*`, `playwright.config.ts`, `package-lock.json` as it was, `.env*`,
`.eslintrc.cjs`, `.prettierrc.json`, `.nvmrc`'s original content, `index.html`,
`docker-compose*.yml`, `.dockerignore`, `env.d.ts`).

The prose went with them: `CHANGELOG.md` (the server's history), `ROADMAP.md`, `BUGS.md`,
`BENCHMARKING.md`, `FEDERATION.md`, `CLAUDE.md` (upstream-fork contributor notes) and
`TRADEMARK.md`.

## The licence: GPL v3, and only GPL v3

The old tree was AGPL v3 **with additional terms** under §7: a mandatory "Powered by Harmony"
attribution link and a trademark policy. Those terms belonged to the upstream project, and they
described assets that are no longer here (`public/icon_3d.webp`, `src-tauri/icons/`, the KLIPY
marks). They also cannot simply be carried across: this app is not the AGPL program they were
written for, and a §7 attribution to a repository that no longer contains the program would be
a claim that is not true.

So:

- **`LICENSE`** is the canonical GPL v3 text, unmodified, as the FSF publishes it.
- **`COPYRIGHT`** is rewritten: the app's own copyright statement, the GPL's standard notice,
  and an honest list of what the app links against (AndroidX, Kotlin, OkHttp, Coil, LiveKit,
  the Gradle wrapper -- all Apache 2.0, resolved by Gradle rather than vendored).
- **`LICENSE-ADDITIONAL-TERMS.md`** and **`TRADEMARK.md`** are deleted. There are no additional
  terms.
- **`app/src/main/assets/legal/`** carries `LICENSE` and `COPYRIGHT` into the APK, so an
  installed copy travels with its licence.
- **`package.json`** says `GPL-3.0-or-later`.

The one asset that had to go with the licence change is the bundled typeface: the app shipped
NoRe Sans Pixel Pro (`nore_regular.ttf`, `nore_bold.ttf`), which upstream's `COPYRIGHT` credits
to the upstream author, not to us. Under a GPL-only repository we cannot keep a font whose
terms are somebody else's and which the licence statement no longer covers, so it is removed
and the "Pixel" font option with it. The appearance setting the account stores is still read
and still written; a name the APK does not carry now draws in the device's own face, and the
appearance panel says so instead of offering a face it cannot show.

## One package for every build

The debug build used to carry an `applicationIdSuffix` of `.debug`, so
`io.github.wwpol.brapmony.debug` and `io.github.wwpol.brapmony` were two different apps to
Android. That had one thing going for it -- a test build and a release build could sit side by
side -- and several against it:

- **It was a second app nobody asked for.** Somebody who installed a branch build to try the
  client then had to uninstall it before installing a release, because Android saw two apps.
  The versionCode was designed so a later build updates an earlier one, and the package name
  defeated that.
- **It split every name in two.** Logs, `doctor.sh`, `upgrade-test.sh`, the emulator journey,
  the release workflow's own package check and the documentation each had to say which of the
  two they meant, and a mistake in one of them was a silent check of the wrong app.
- **It hid the real rule.** Whether Android accepts an update is decided by the signing
  certificate and the versionCode, not by the build type. Two packages made it look as though
  the build type were the deciding factor.

So there is one package, `io.github.wwpol.brapmony`, in every build type. The debug variant
still appends `-debug` to the version name, which is what tells a person which build they have.
A test-signed build installs over another test-signed build and not over a release-signed one;
that is the certificate doing its job, and [builds.md](builds.md) now says so instead of
explaining two packages.

The records of runs that really happened keep the name those runs used -- a device log cannot
be rewritten -- with a line saying that every build is one package now.

## The README and the docs

The README is the app's: what it does, what it does not do yet, how to build it, how to run it
on an emulator, where the documentation is, and the licence. It no longer mentions a server
project, self-hosting, a federation backend, a Tauri desktop build or a second Android client,
because none of those are here.

The documentation keeps everything that is still true and drops the rest. Two documents needed
more than a rename:

- **`docs/parity-audit.md`** measured the app against the reference Harmony client and quoted
  the reference's own paths (`src/components/...`, `src-tauri/...`) in nearly every row. Those
  paths are now labelled as the reference project's, with a paragraph at the top saying that
  project is not in this repository and why its paths still appear; this repository's own paths
  are `app/src/main/...`. The audit's substance -- 166 frozen rows, 26 journeys, every one of
  them `U` -- is unchanged, because the gaps are unchanged.
- **`docs/builds.md`** described two Android apps shipping from one release and named assets by
  the other one's scheme. It now describes one app: a rolling release of the default branch, a
  prerelease of any other, and the APK on each version tag. (The rolling release has since been
  replaced by a release per commit; see [Builds](builds.md).)

## The workflows

Two, where there were six.

**`android.yml`** builds the app on every push to every branch and on pull requests that touch
it, runs the unit tests and lint, names the APK, checks the package, version and signing
certificate against the file itself, uploads it as an immutable per-run artifact, and publishes
the newest build of each branch -- as the repository's release for the default branch, as a
prerelease for any other. (That was the shape at the time; a later change publishes one release
per commit of the default branch and nothing for other branches. See
[Builds](builds.md).) The trust split is unchanged and is what the tests assert: the build
job holds no write permission, and the publishing job has `contents: write` and runs only on a
push to this repository. It published a container package as well until that was removed: an
APK is not an image, and a package is not a download a phone can use.

**`release.yml`** runs on a `v*` tag. It stamps `VERSION` from the tag, builds the release
variant, names the APK for the signing state it actually carries, checks the package, version
and certificate, creates or finds the release for that tag, and attaches the APK and its
checksum. It replaces only the two names it owns.

Two things in the old workflows existed only because the Tauri Android app shipped from the same
release, and are gone: the `--native` flag that kept the two APKs' names apart, and the
`native-android` job's dependence on a draft release another job created. In their place is a
`tag` command in `scripts/release.mjs`, which creates or finds the release for a `vX.Y.Z` tag and
refuses anything else -- so a rolling tag can never be adopted as a version release. That
command has its own tests.

## The e2e scripts and the deleted test stack

The journeys used to read accounts and a URL from `e2e/fixture.json`, written by
`e2e/stack.sh` from the disposable Docker stack that lived in this repository. Both are gone:
the stack was a copy of the server's test harness, and the app does not need it to be tested.

`e2e/journey.py`, `e2e/device-journey.sh` and `e2e/fuzz/stateful.py` now take the instance,
the account and the anon key from flags or environment variables (`NATIVE_INSTANCE_URL`,
`NATIVE_EMAIL`, `NATIVE_PASSWORD`, `NATIVE_ANON_KEY`, and `--fixture` for the fuzzer). They run
against any instance with throwaway accounts, including the one the tests already point at.

## Alternatives considered

**Keep the server and delete only the desktop and web app.** Rejected: the federation backend,
the schema and the bot gateway are as much a server as the web client is, and the request was
that the repository hold no code that was not written for the app.

**Keep the app under `android-native/` and delete everything else.** Rejected: it leaves the
repository's root as a directory wrapper around the actual project, which is the second half of
the request. A repository whose top level is one subdirectory and a README is not restructured.

**Keep AGPL v3 and drop the additional terms.** Considered, because it is the smaller change.
Rejected: the additional terms were the upstream project's §7 terms, and this app is not that
program. A GPLv3-or-later licence is what the remaining code can be offered under cleanly, and
it is what the request asked for.

**Keep the font and note its provenance.** Rejected for the same reason: under a GPL-only
repository, a bundled typeface whose terms are the upstream author's is a licence statement
this repository cannot make. The alternative -- shipping a face and documenting a restriction on
it -- would make the licence claim false in the one place a user looks.

**Rewrite the parity audit without the reference's paths.** Rejected: the paths are how each row
can be checked, and deleting them would leave 166 assertions with nothing behind them. They are
labelled instead.

## What this does not change

- **The app's screens.** No layout, navigation or visual design is touched. The Kotlin changes
  are the font fallback, the two attribution links and their wording, two comments naming a
  moved document, and the call-service fixes described below.
- **The gaps.** Everything the parity audit lists as missing is still missing. The audit rows
  were corrected where they had gone stale against the code -- they described the frozen
  reference commit, not this tree -- but no row was promoted to verified.

### Three defects found while auditing the tree, and fixed

Moving every file meant reading every file, and three of them were wrong in ways that had
nothing to do with the restructure. They are fixed here because leaving them would have meant
committing a tree I knew to be broken.

1. **`voice/CallService.kt` was never started.** It was declared in the manifest, and its own
   KDoc said it was "started when a call joins and stopped when it leaves", but no code called
   `CallService.start`: the microphone would be muted or stopped as soon as the app left the
   foreground. The call screen now starts it on join, restarts it when a capture is gained
   mid-call (the microphone granted after a refusal, the camera, a screen share), and stops it
   on every leave path, including a call the server ended.
2. **It claimed foreground types it did not hold.** It asked for `microphone|camera|
   mediaProjection` unconditionally. From Android 14 that is a `SecurityException`, and it
   would have fired in exactly the case the app supports on purpose: a person who refused the
   microphone and stayed to listen. It now claims only the types whose permission the app
   actually holds, and a call holding none claims none. The type constants are API 29/30
   (`mediaProjection` 29, `microphone` and `camera` 30), so each is named only from its own
   release -- and, importantly, "no type to name" is kept distinct from "nothing to capture":
   on API 26-28 a microphone call names no type and still holds a foreground service, which is
   what the split into `claimedTypes` and `holdsCapture` is for. `CallServiceTypesTest` pins
   both halves with ten cases, including the API 28 and 29 releases.
3. **`VoiceRepository` ran blocking OkHttp on the caller's thread.** Its `config` and `token`
   calls used `runCatching`, not the `networkResult` helper that `RestApi` and `AuthApi` use to
   confine blocking work to `Dispatchers.IO`; the caller is the call screen's `rememberCoroutineScope`,
   which is Main. Both now go through `networkResult`, so the network cannot block the frame
   that draws the call screen.

The first two are why the audit's B06 row and the `Voice/video` section are rewritten rather
than deleted: the transport is real, and it now has a foreground service that actually runs.

## How this was checked

- `./gradlew :app:assembleDebug :app:testDebugUnitTest` from the new root: `BUILD SUCCESSFUL`,
  **334 tests, 0 failures, 0 errors, 24 skipped**. The skips are the opt-in suites that need a
  live instance or a real screen; they skip loudly rather than pass quietly.
- `./gradlew :app:lintDebug`: `BUILD SUCCESSFUL` with 27 warnings and no errors. The warnings
  are dependency-upgrade notices, `minSdk 26` making some `SDK_INT` checks obsolete, and
  `mutableStateOf` hints for primitives -- none of them in code this change wrote.
- The built APK, read back rather than assumed: `package: name='io.github.wwpol.brapmony'
  versionCode='1009006' versionName='1.9.6-debug'`, one package for both build types,
  `assets/legal/{LICENSE,COPYRIGHT}` inside it, `foregroundServiceType=0xe0` on
  `.voice.CallService`, and no `nore_` font.
- `npm test` -- the release tooling, the workflows, `doctor.sh` and the claim assertions:
  46 tests pass.
- A new assertion in `scripts/workflow.test.mjs` that the repository holds the app and nothing
  else: neither workflow names a server, web or desktop path, runs the web app's scripts, or
  calls its tooling, and the module the build declares is `:app` beside `settings.gradle.kts`.
- A link check over the Markdown: 44 relative links in 19 files, all resolving.
- A grep over the whole tree for the old layout and the old licence: `android-native/`,
  `docs/build/`, `e2e/stack.sh`, `AGPL`, `LICENSE-ADDITIONAL-TERMS`, `TRADEMARK`.
