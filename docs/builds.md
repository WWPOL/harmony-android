# Builds

How the app is built and where its APKs are published: every-push artifacts, a rolling
prerelease per branch, the APK on each version tag, what is signed with what, and which build
updates which.

## What each push publishes

[`android.yml`](https://github.com/WWPOL/harmony-android/blob/main/.github/workflows/android.yml)
runs on **every push to any branch**, whatever the push changed, and on pull requests that
touch the app. A push is not filtered by path: a branch build is what somebody downloads to
try the app, and a commit that changed nothing still has to produce the APK of that commit
rather than leave an older one standing.

| Where | What | Lifetime |
| --- | --- | --- |
| Actions artifact `android-apk-<run id>-<attempt>` | The debug APK, its `.sha256` and a `.txt` naming the commit, version, versionCode and signing | 90 days, never overwritten |
| Prerelease `android-rolling-<branch>` | This run's APK under its own name, plus `Brapmony_Android_latest.apk` and that file's `.sha256` | The release stays; builds older than the newest ten of that branch are pruned |
| The run's summary | Links to both | With the run |

Each run's APK is also named after its version and commit
(`Brapmony_Android_V1.9.6_debug-<branch>-<sha7>.apk`), so a link to a particular build keeps
working after later pushes. `Brapmony_Android_latest.apk` is the stable name to bookmark: it
is replaced on every push to that branch, from the same bytes as that run's own APK.

A rolling release is a **prerelease**, so `releases/latest` skips it. A branch build is never
offered to anybody as a release.

The download link the run prints is the tag-specific one,
`https://github.com/<owner>/<repo>/releases/download/android-rolling-<branch>/Brapmony_Android_latest.apk`.
It has to be tag-specific: `/releases/latest/download/...` resolves only to the newest
**published** release, and a rolling release is a prerelease, so that path would never reach
it. The publish job then fetches its own link back over the same path a browser uses and fails
unless what arrives has this app's package, the versionCode that was just built, the checksum
the build reported, and a signing certificate. A link that is not independently downloadable
is a broken link, and the run says so instead of publishing a summary that looks fine.

Publishing needs no secret of its own: the APK is test-signed, and the workflow's
`GITHUB_TOKEN` writes the prerelease. Whether two runs' APKs install over each other depends
on a stable test key, which is optional; see [Signing](#signing) and
[Which build installs over which](#which-build-installs-over-which).

## What a tag publishes

A version tag (`vX.Y.Z`) runs
[`release.yml`](https://github.com/WWPOL/harmony-android/blob/main/.github/workflows/release.yml),
which stamps `VERSION` from the tag, builds the release variant, and attaches the APK to the
release for that tag:

| Signing configured | Asset |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` and its three companions | `Brapmony_Android_VX.Y.Z.apk` |
| Not configured | `Brapmony_Android_VX.Y.Z_debug.apk` |

Both are accompanied by a `.sha256`. The job creates the tag's release if it does not exist
and finds it if it does, so re-running a tag attaches to the same release instead of creating
a second one, and it replaces only the two names it owns. It never touches another release.

## Signing

| Build | Key | Installs over |
| --- | --- | --- |
| Debug, every push, stable test key configured | The repository's test key, from a secret | Any other build this workflow signed with that key |
| Debug, every push, no test key | A key generated for this checkout | Nothing signed by another run |
| Release from a tag, keystore secret configured | The project's release key | The previous release-signed build |
| Release from a tag, no keystore secret, stable test key | The repository's test key | Any other build signed with that key |
| Release from a tag, neither secret | A key generated for that run | Nothing signed by another run |

An APK is named for the key it carries: `_debug` means test-signed. It installs, and it is
what to try a branch build with, but Android refuses to install it over a release-signed app
(a signature mismatch), and it cannot be published to a store.

**A generated key does not survive the runner.** The build uses the conventional debug key in
`.gradle/`, which a fresh checkout does not have and `createLocalDebugKeystore` generates. A
generated key has a random certificate, so two CI runs, or a CI run and a local build, sign
with different keys and each APK refuses to install over the other. To make APKs that install
over one another, configure one throwaway test key as repository secrets:

| Secret | Contents |
| --- | --- |
| `ANDROID_TEST_KEYSTORE_BASE64` | `base64 -w 0 test.keystore` |
| `ANDROID_TEST_KEYSTORE_PASSWORD` | Its store password |
| `ANDROID_TEST_KEY_ALIAS` | The key's alias |
| `ANDROID_TEST_KEY_PASSWORD` | That key's password |

Each signing step checks the keystore before the build uses it: that it decodes, that the
password opens it, and that the alias names a key inside it. A wrong alias therefore fails
before the build starts instead of as a Gradle signing error after it.

The certificate's SHA-256 fingerprint goes in the repository variable
`ANDROID_TEST_KEY_SHA256`, as `keytool -list -v` prints it (uppercase, colon-separated; the
workflow normalises it). It is a digest, not the key. Generate the key once, offline, and keep
the file out of the repository:

```sh
keytool -genkeypair -keystore test.keystore -storepass "$PASS" -keypass "$PASS" \
  -alias brapmony-test -keytype RSA -keysize 2048 -validity 10000 \
  -dname "CN=Brapmony Test, O=Brapmony, C=US"
```

It is a **test** key, not a release key: it never signs a release-signed install, and the APK
stays named `_debug`. Without the secrets CI generates nothing to keep and stores no key; each
run makes its own, and the run's summary and warning say that its APK will not install over
another run's. The build reads the certificate's fingerprint out of the finished APK and fails
when a configured key is not the one that signed it, so a name cannot claim a key the file
does not carry, and a subject like `CN=Android Debug` is never mistaken for identity.

The repository contains no signing key and no secret of its own: the test key lives in
repository secrets, never in the tree. Nothing here generates, guesses or stores a release key,
and no release APK pretends to be signed with one.

**What is configured today:** the repository carries the test key secrets, so every CI build is
signed with that one stable key rather than a key generated per checkout. Its certificate is
`CN=Harmony CI, O=Harmony, C=US`, SHA-256
`2E:F4:6C:81:35:76:BB:AF:CE:34:91:9F:0E:EE:75:A9:F4:2D:1B:9E:94:53:A9:B8:45:43:13:CE:A3:4C:4F:75`,
read out of the APK that run published — downloaded again from the rolling prerelease and
inspected with `apksigner`, not copied from a log. That is the stability baseline.

The pin is proved to be in place by deduction from the run, not by reading its log (the log
endpoint needs a token this session did not have). Its `Configure the test signing key` step
succeeded and its `Note the test signing key` step was skipped, so the keystore secret was
present. Its `Check the APK and write its checksum` step then passed, and at that commit that
step exited non-zero both when `ANDROID_TEST_KEY_SHA256` was unset and when the fingerprint
differed from it. So the variable is set, and it names exactly this certificate. A run that
signed with a different key would have failed there and published nothing.

It is the *new* key, and it is the baseline from here on. An APK signed by an older key does
not install over one signed by this one, or the other way round: the switch to this key was a
one-time break in the upgrade path, accepted when the CI pipeline was set up. What the check
guarantees is that the key does not change again silently — every later run is compared with
the fingerprint above, and one that differs fails instead of publishing.

A run with neither `ANDROID_KEYSTORE_BASE64` nor `ANDROID_TEST_KEYSTORE_BASE64` configured
still builds and publishes, but signs with a key of its own, so its APKs are not updates of
anything else — not even of the previous run's. The run's log says so. That is not this
repository's state: the test key is configured.

## What a publish will not do

A published download link is somebody's, so the publishing script refuses to do things that
would quietly change what one means. Each row is a test in `scripts/release.test.mjs`:

| Situation | What happens |
| --- | --- |
| The release for the computed rolling tag is not a prerelease | Refused; nothing is written |
| A tag that is not `vX.Y.Z` is asked to make a version release | Refused, so a rolling tag can never become one |
| An asset was not uploaded by this workflow | Never replaced or pruned; refused |
| A re-run of the same commit, same bytes | Nothing changes, not even an asset id |
| A re-run of the same commit, different bytes | The new bytes get their own name; the old name keeps the old bytes |
| The run's own upload fails | Nothing else is touched; the stable name still points at the last good build |
| The stable name's upload fails | The previous stable build is still there and still downloadable |
| An older build is pruned | Only builds this workflow uploaded, keeping the newest ten |

Ownership is recorded in each asset's label (`brapmony-android sha256:<hash>`), so a file
somebody attached by hand is not mistaken for this workflow's. The swap of the stable name
goes through a staging asset and a rename, and the previous file is deleted only after the new
one is in place.

### Why ten builds

A rolling release is a window on a branch, not an archive: it keeps the newest ten builds of
its branch and deletes older ones it uploaded itself. Without a bound, every push to a
long-lived branch would add an asset for ever and the release page would grow without limit.
Ten is roughly a day of pushes on an active branch, and the links people use — `latest`, and
the newest few commits — are inside it. A link to a build older than that stops resolving; the
version-tag release is where a build is kept, and nothing here touches it.

## Which build installs over which

Android installs an APK over an installed app when three things hold: the same package name, a
certificate that matches the installed app's, and a versionCode that is not lower. A
**certificate** is not a subject: two keys can both say `CN=Android Debug` and be different
keys. What decides is the certificate's SHA-256 digest, and that is what this workflow records
and compares.

| From | To | Installs in place |
| --- | --- | --- |
| Debug build, run A | Debug build, run B, same stable test key | Yes, if versionCode does not go down |
| Debug build, run A | Debug build, run B, no stable key | **No**: each run generates a key with a new certificate |
| Debug build, test-signed | Release build of a tag, test-signed | Yes, if versionCode does not go down: same package, same test key |
| Debug build, test-signed | Release build of a tag, release-signed | **No**: different certificates |
| Release build, vX | Release build, vY (X < Y) | Yes, when both carry the same release key |
| Test-signed release build | Release-signed release build | **No**: different certificates |

Every build carries the **same package**, `io.github.wwpol.brapmony`. There is one app, so an
installed build is updated rather than a second copy appearing beside it. What decides whether
Android accepts the update is the certificate and the versionCode, never the build type: a
debug APK signed with the repository's test key installs over a tag's test-signed APK and vice
versa, and neither installs over a release-signed one.

`upgrade-test.sh` checks this on an emulator, with two consecutive builds of the debug package:

```sh
./upgrade-test.sh prepare /tmp/upgrade                     # build both, check package/cert/versionCode
./upgrade-test.sh run /tmp/upgrade emulator-5554           # install in place, verify data
```

`prepare` builds the version in `VERSION` and then the same tree with the patch number raised
by one, so the second APK is what a later release would produce. It fails unless the two have
the same package, the same certificate SHA-256 and an increasing versionCode. `run` installs
the first, leaves a marker in the app's own data directory, installs the second with `-r` (no
uninstall), and fails unless the marker is still there, the installed versionCode is the
second build's, and the app did not crash. It touches an emulator only; a person's phone is
never used.

### What has and has not been observed

On this project's emulator, with a stable test key configured, two consecutive builds were
installed one over the other: versionCode 1009006 then 1009007, same certificate SHA-256, the
marker in the app's data unchanged, and no crash in logcat. That run used the debug package as
it was then, `io.github.wwpol.brapmony.debug`; every build is the single package
`io.github.wwpol.brapmony` now, and the mechanism the run proves is the same one.

A person's own phone has **not** been tested, by design. Whether a CI APK installs over an app
already on somebody's device therefore depends on the certificate that app was signed with: it
works when the installed build came from the same stable test key, and fails with a signature
mismatch when it did not. No uninstall or data wipe is offered as a workaround; if the
certificates differ, the APK is a different app to Android.

## Version and upgrades

`app/build.gradle.kts` reads the repository's `VERSION` file, which the release workflow
stamps from the tag. `versionName` is that version and `versionCode` is
`X * 1_000_000 + Y * 1_000 + Z`, so a later release always carries a higher versionCode than
an earlier one and no counter is maintained by hand. A higher versionCode is necessary but not
sufficient: Android also requires the same signing key, so an install is only ever upgraded in
place by a build signed with the key the installed one carries (see [Signing](#signing)).

The debug variant appends `-debug` to the version name, so the About screen and the APK's
file name say which build is installed. The package is the same in every build type: a debug
build and a release build of one version are the same app to Android, and only the certificate
decides whether one installs over the other.

## Working on the workflows

Three Node scripts exist for checking the workflows without a runner:

| Script | What it does |
| --- | --- |
| `scripts/release.test.mjs` | Runs the publishing script against an in-process stand-in for the GitHub releases API |
| `scripts/workflow.test.mjs` | Parses both workflows and asserts the properties that make publishing safe |
| `scripts/workflow-steps.mjs` | Runs one job's `run:` steps with `bash -eo pipefail`, as GitHub does, on this machine |

The first two run in CI with the unit tests:

```sh
npm ci
node --test scripts/*.test.mjs
```

The publisher tests cover what a published download depends on: one prerelease per branch, the
previous builds kept, only the stable names replaced, a re-run that changes nothing, a rebuild
that gets its own name, a foreign asset that is never overwritten, a failed upload that leaves
the last good build downloadable, retention bounded to this workflow's own builds, a version
release left entirely alone, and a tag release created once and found again on a re-run.

The workflow tests assert that every action is pinned to a commit SHA, that
`pull_request_target` is not used, that `contents: write` appears only on the publishing jobs,
that the branch publisher runs only for a push to this repository, that a push has no path
filter, that the stable test key is configured from a secret and its absence reported, that
the tag job stamps `VERSION` from the tag and attaches by release id, that the APK namer is
called with a profile it accepts and really renames the file, that no step pipes `aapt2` or
`apksigner` into `head` (which fails the step with SIGPIPE under `pipefail`), and that the
repository holds the Android app and nothing else.

To run a step as GitHub would, after a local build:

```sh
ANDROID_HOME=/path/to/sdk \
  node scripts/workflow-steps.mjs .github/workflows/android.yml build \
  --step 'Check the APK and write its checksum' --set 'path=<the named apk>'
```

Without `--step`, the whole job runs and each step's outputs feed the next, which is how to
check the signing, naming and digest steps together. The job's `env:` and each step's `if:`
are evaluated — comparisons joined by `&&` and `||`, which is the form the signing steps use —
and a step's `GITHUB_OUTPUT` becomes `steps.<id>.outputs.<name>` for the steps after it, so a
job's steps chain the way they do on a runner:

```sh
ANDROID_HOME=/path/to/sdk \
  node scripts/workflow-steps.mjs .github/workflows/android.yml build \
  --set "secrets.ANDROID_TEST_KEYSTORE_BASE64=$(base64 -w 0 test.keystore)" \
  --set 'secrets.ANDROID_TEST_KEYSTORE_PASSWORD=<store password>' \
  --set 'secrets.ANDROID_TEST_KEY_ALIAS=<alias>' \
  --set 'secrets.ANDROID_TEST_KEY_PASSWORD=<key password>' \
  --set 'vars.ANDROID_TEST_KEY_SHA256=<the key’s SHA-256, as keytool -list -v prints it>'
```

`scripts/fake-github-api.mjs` is the same stand-in as a process, for running the publish step
itself:

```sh
GITHUB_REPOSITORY=owner/repo node scripts/fake-github-api.mjs 8099 &
node scripts/workflow-steps.mjs .github/workflows/android.yml publish \
  --step 'Publish' --cwd <a directory holding android-apk/<the apk>> \
  --set 'GITHUB_TOKEN=t' --set 'GITHUB_API_URL=http://127.0.0.1:8099' \
  --set 'github.ref_name=main' --set 'needs.build.outputs.sha256=<sha256sum of the apk>'
```

A change to either workflow should keep those tests passing and their assertions honest: if an
assertion stops describing what the workflow needs, change the workflow or the assertion
deliberately, not by loosening it until it passes.

## What is not verified here

The workflows are checked by parsing them and by running their shell steps on a machine, and
the build job has been run end to end locally with the stable test key configured: the APK is
built, signed with that key, named, and its certificate read back and compared with the key's
fingerprint.

**One real run has now completed.** [Run 44533075269](https://github.com/WWPOL/harmony-android/actions/runs/44533075269)
(a push of `task/verify-apk-ci-publish-flow-3e8cphaa` at `fbd613f`) succeeded on a GitHub
runner: the build job produced and uploaded the `android-apk-<run id>-<attempt>` artifact, and
the publish job created the rolling prerelease, uploaded this run's APK, moved
`Brapmony_Android_latest.apk` and its `.sha256` onto those bytes, fetched the download back and
checked it, and printed the links. So the runner's environment — the `sdkmanager` package
names, the `aapt2` and `apksigner` paths, `upload-artifact` and `download-artifact`, and the
token's permission to create a release — is demonstrated, not assumed.

That run carried both fixes in this change, so it proves more than the environment. Its build
job called `scripts/name-artifact.sh debug "$1"` (the call `main` still gets wrong, see below),
and its publish job read the badging and the certificate into files instead of piping `aapt2`
and `apksigner` into `head`. Both steps passed on the runner: `Name the APK` and
`Check the published download`. The SIGPIPE failures and the namer's `Android` argument were
real, and this run is the evidence that they are gone rather than merely argued away.

**`main` failed the same way before this change.** [Run 44533004623](https://github.com/WWPOL/harmony-android/actions/runs/44533004623),
the newest push to `main` (`618008b`), failed at `Name the APK` with
`bash scripts/name-artifact.sh Android debug "$1"`, and skipped the digest, the artifact and
the publish. That is what this change fixes: the build was green up to the naming step and
nothing was published.

That has one consequence worth stating plainly, because the README's download link depends on
it: at the time of writing the only rolling prerelease was `android-rolling-task-verify-apk-ci-publish-flow-3e8cphaa`,
from the branch run above, and `android-rolling-main` did not exist, so
`.../releases/download/android-rolling-main/Brapmony_Android_latest.apk` answered 404. It
starts working with the first push to `main` that gets past the namer — that is, the merge of
this change, which is itself such a push. The link was never wrong; nothing had published
`main` yet.

A version tag's release (`release.yml`) has still not been run on a runner: no `vX.Y.Z` tag has
been pushed, so its steps are exercised locally only. That now includes the tag build's
test-signing step, which the local step runner could not evaluate before the fix described
above: it refused the step's two-part `if:` rather than running it.

The certificate check needs **both** the secret and the repository variable. With
`ANDROID_TEST_KEYSTORE_BASE64` set but `ANDROID_TEST_KEY_SHA256` unset, the run fails and says
so, rather than signing with the stable key and proving nothing about which key it was. The
variable is the independent expectation the APK is checked against, so it is never read out of
the keystore the same run configured: a check whose expectation came from the thing it checks
would pass any key.
