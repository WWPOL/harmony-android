# Contributing

Thanks for wanting to help. This repository is the Android client: Kotlin and Jetpack Compose,
the Gradle build, the emulator journeys and the release tooling. It is a client -- the Harmony
server it talks to lives in a different project, and nothing of that server belongs here.

## Getting set up

You need JDK 21, the Android command-line tools, and Node 24 for the release tooling:

```sh
sdkmanager 'platform-tools' 'platforms;android-37.0' 'build-tools;37.0.0'
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk21

npm ci
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
node --test scripts/*.test.mjs
```

The first Gradle run downloads the wrapper and the dependencies, so it takes a while. After
that, `:app:testDebugUnitTest` alone is a few seconds and is what to run while working.

To see the app on a screen, an emulator is required -- never a phone somebody is using:

```sh
./demo.sh                       # build, install, open; see README.md
```

## What a change has to hold to

- **The app is the repository.** A change that adds server code, a web client or a desktop
  build belongs in another project. `scripts/workflow.test.mjs` asserts this.
- **GPL v3 or later.** By opening a pull request you agree your contribution is licensed under
  the same terms as the project. Do not add code you cannot license that way.
- **No third-party source.** Dependencies are resolved by Gradle, not vendored here. If a
  dependency's licence is not GPL-compatible, it cannot be linked in.
- **A test that would fail without the fix.** A UI change gets a Robolectric render or
  interaction test; a repository change gets a `MockWebServer` test; a release-tooling change
  gets a `node --test` case.
- **Honest docs.** If something is not implemented or not verified, say so in
  [docs/parity-audit.md](docs/parity-audit.md) rather than leaving a control that looks like it
  works.

## Layout

```
app/src/main/java/lol/mony/harmony/
  AppContainer.kt   the dependency graph
  data/api/         PostgREST and auth transport
  data/model/       the wire shapes
  data/repo/        one repository per surface
  data/session/     the stored session and its token
  notifications/    channels, the coordinator and deep-link targets
  ui/components/    shared drawing
  ui/screens/       one package per destination
  ui/theme/         the account's own appearance
app/src/test/       JVM and Robolectric tests, mirroring those packages
e2e/                emulator journeys and the stateful fuzzer
scripts/            the release tooling and its tests
docs/               the design, the builds, and the evidence
```

## Commits

[Conventional Commits](https://www.conventionalcommits.org/), scoped by area:

```
feat(chat): keep a draft when the room closes
fix(appearance): draw the account's accent on the rail
docs(builds): say what a test-signed APK installs over
test(drive): prove a restricted link refuses an outsider
chore(release): name the APK after the version it reports
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `chore`. A commit that
changes what a person sees says so in its subject.

## Pull requests

1. Branch from the default branch.
2. Keep the change to one thing. A feature and the refactor it needed can be two commits, but
   an unrelated cleanup makes the review harder and is usually its own pull request.
3. Say what changed, why, and how you checked it. If you could not check something, say that
   instead of leaving it implied.
4. A UI change gets a screenshot or a capture from the render tests. "It compiles" is not a
   check for a screen.
5. `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` and
   `node --test scripts/*.test.mjs` pass.

## Reporting a bug

Include the app's version (the account screen shows it; `adb shell dumpsys package
io.github.wwpol.brapmony | grep versionName` prints it), the Android version and device,
what you did, what you expected, and what happened. A logcat excerpt around the failure helps
more than a description of it. Do not paste tokens, passwords or a session file.

Security issues do not go in a public issue: see [SECURITY.md](SECURITY.md).

## Licence

Copyright (C) 2024-present the Brapmony contributors. GNU GPL v3 or later; see
[COPYRIGHT](COPYRIGHT) and [LICENSE](LICENSE).
