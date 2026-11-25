# Builds

How the app is built, what its APKs are called, and where each one is published.

Every APK is published as a release asset, and nowhere else.

## Versions: a release line, and a build that is its commit

There are two numbers, and they answer two different questions.

**`VERSION` is the release line** — `0.1.0` today. It is a number a person reads, changed on
purpose when the app reaches a milestone, and the name of a release when the commit carrying
it is tagged `v0.1.0`. It is *not* bumped per commit and does not identify a build.

**A build is identified by its commit.** `app/build.gradle.kts` reads the commit from git:

| | Branch build (every push) | Version build (`vX.Y.Z` tag) |
| --- | --- | --- |
| `versionName` | `<VERSION>-<sha7>-debug` — e.g. `0.1.0-606ab10-debug` | `<VERSION>` — e.g. `0.1.0` (`-debug` if test-signed) |
| `versionCode` | `2_000_000` + the number of commits reachable from it | The same number |

So two commits never report the same version, and a later commit always reports a higher
`versionCode` than an earlier one, which is what Android compares before it installs one build
over another. Nobody maintains a counter: the commit count only grows along a branch, and every
build of one commit gets the same number. CI has to fetch the whole history for that to hold
(`fetch-depth: 0`), and a checkout without one fails the version step rather than reporting `1`.

The `2_000_000` base is the one number chosen by hand, and it is chosen once: the previous
scheme numbered a release `maj*1e6 + min*1e3 + pat`, so `1.9.6` was `1_009_006` and no `1.x`
release could exceed `1_999_999`. Starting above that is what keeps this change from being a
downgrade Android refuses.

**What this replaced, and why.** `VERSION` said `1.9.6` from the first commit of this
repository, and nothing ever changed it: it was the value inherited from the project this app
was split out of. Every build of every commit therefore reported `versionName 1.9.6` and
`versionCode 1009006`, the rolling release was titled `Brapmony 1.9.6` for all of them, and
only the commit in the file name said which build was which. A version that never moves is not
a version; it is a constant, and it made the Releases page describe a stream of different
builds with one stale number. [The evidence note](evidence/release/versioning-and-cleanup.md)
has the history, the remote state it left behind, and the sweep that clears it.

`-PharmonyVersionCode` overrides the number, which `upgrade-test.sh` uses to build one tree
twice with a higher code. `-PharmonyReleaseBuild` makes the version name the plain release line,
which `release.yml` sets: a tag's APK is the release, not a build of a branch.

The version is on the account screen, in the About section, read from `BuildConfig`: it is
what to quote in a bug report.

## Names

One scheme, everywhere: `<app>-<version>[-<signing>].apk`, where `<version>` is the version
name the APK itself reports. The signing state is last, the way Gradle names a variant, and
`-debug` is a signing statement rather than only a build type: it says the APK carries the
repository's test key.

| Build | Name |
| --- | --- |
| Push to a branch | `Brapmony-0.1.0-606ab10-debug.apk` |
| Version tag, release-signed | `Brapmony-0.1.0.apk` |
| Version tag, test-signed | `Brapmony-0.1.0-debug.apk` |
| The stable name to bookmark | `Brapmony-latest.apk` (and `.sha256`) |
| Actions artifact | The APK's own name, `-attempt-<n>` on a re-run |

The name is passed to `scripts/name-artifact.sh` as the version name the build produced
(`APP_VERSION_NAME`), not derived a second time inside the script, so the file name and the
manifest inside the file cannot disagree. The workflow checks the finished APK's manifest
against the version it reports before it names the file, and the publish job checks the
downloaded copy again.

The branch is not in the file name: it is in the tag of the release the file is published to
(`android-rolling-<branch>`), which is where a downloader looks for it, and a branch name can
carry characters and a length a file name should not.

An earlier version of this workflow published `Brapmony_Android_V1.9.6_debug-<branch>-<sha7>.apk`
and `Brapmony_Android_latest.apk`. Those names are retired on the first publish that runs
here: the assets are deleted if they carry this workflow's ownership label, so a download does
not stay under a name nothing updates. A file somebody attached by hand is never touched.

## What each push publishes

[`android.yml`](https://github.com/WWPOL/harmony-android/blob/main/.github/workflows/android.yml)
runs on **every push to any branch**, whatever the push changed, and on pull requests that
touch the app. A push is not filtered by path: a branch build is what somebody downloads to
try the app, and a commit that changed nothing still has to produce the APK of that commit
rather than leave an older one standing.

| Where | What | Lifetime |
| --- | --- | --- |
| Actions artifact `Brapmony-<version>-<sha7>-debug` | The debug APK, its `.sha256` and a `.txt` naming the commit, version, versionCode and signing | 30 days, never overwritten |
| Release `android-rolling-main` (the default branch) | This run's APK under its own name, plus `Brapmony-latest.apk` and that file's `.sha256` | The release stays; builds older than the newest ten of that branch are pruned |
| Prerelease `android-rolling-<other branch>` | The same, for a branch that is not the default | The same |
| Attestation, for the APK | A signed build-provenance statement naming this repository, this workflow and this commit | As long as the repository keeps it |
| The run's summary | Links to all of it | With the run |

Each run's APK is named after the version it reports (`Brapmony-0.1.0-606ab10-debug.apk`),
so a link to a particular build keeps working after later pushes. `Brapmony-latest.apk` is the
stable name to bookmark: it is replaced on every push to that branch, from the same bytes as
that run's own APK.

**The default branch's build is a release.** It is what the repository's Releases box and
`/releases/latest` offer. Its release is named for the branch — `Brapmony for Android — newest
build of main` — and its body carries that build's version, versionCode, commit and checksum,
refreshed only after the upload succeeds.

### Why one release per branch, and not one per commit

A rolling release is a **window on a branch**, refreshed by every push. It holds the newest ten
builds of `main`, each under its own name, plus the stable `Brapmony-latest.apk`.

A release per commit would make the Releases page a commit log: dozens of entries a day, each
one a copy of the previous one plus a commit, and no entry meaning more than the one after it.
GitHub's Releases page is the front door of the repository, and what it should say is "here is
the app" — which is what one rolling release says — rather than "here are the last four hundred
commits". A build that is worth keeping is a **version release**: push a `vX.Y.Z` tag, and that
commit's APK is attached to a release named for the version, with nothing on top of it. That is
the archive; the rolling release is the window.

The title is not a version, either. It was `Brapmony 1.9.6` for every build of every branch,
because `VERSION` was one number nothing bumped, so the title was a version that described no
particular build and every build at once. A version belongs to a build (in the body, beside its
commit) or to a tag release (in its title), not to a release whose contents change daily.

Every other branch's build is a **prerelease**, which `releases/latest` skips: a branch build
is not an offer to anybody. A rolling release that carries none of this workflow's own assets
is refused rather than rewritten, whatever its tag.

The download link the run prints is the tag-specific one,
`https://github.com/<owner>/<repo>/releases/download/android-rolling-<branch>/Brapmony-latest.apk`.
It is tag-specific rather than `/releases/latest/download/...` so that a link to a branch that
is not the default still resolves, and so that the link keeps meaning the branch it names. The
publish job then fetches its own link back over the same path a browser uses and fails unless
what arrives has this app's package, the versionCode that was just built, the checksum the
build reported, and a signing certificate. A link that is not independently downloadable is a
broken link, and the run says so instead of publishing a summary that looks fine.

Publishing needs no secret of its own: the APK is test-signed, and the workflow's
`GITHUB_TOKEN` writes the release. Whether two runs' APKs install over each
other depends on a stable test key, which is optional; see [Signing](#signing) and
[Which build installs over which](#which-build-installs-over-which).

## There is no package

The APK was also pushed to the container registry (`ghcr.io`) as an OCI artifact, which made
it a package on the repository beside the release. That is gone, for three reasons:

- **It is not a container image.** GitHub Packages serves no APK registry, so the only way to
  put an APK there was to pretend it was an OCI artifact. The repository then advertised
  `docker pull ghcr.io/<owner>/<repo>` on its page, for an Android app.
- **A phone cannot use it.** The release's download URL is a tap on a phone and an install.
  A package needs `oras pull` or `docker pull` and a token, and it is not a file Android can
  open.
- **It was a second place to look.** Two published copies of one file is a second thing that
  can disagree with the first, and a person who found the package page instead of the
  release had no way to install what they found.

The release is the one published copy. `packages: write` and `artifact-metadata: write` are
granted to no job in either workflow, and `npm test` asserts that.

A package published before this change is still on the repository until somebody deletes it:
the registry has no API for that from here, so it is one action in the package's settings
page (Package settings → Delete this package). Nothing pushes to it any more.

## Every asset shows its own name

GitHub shows a release asset's **label in place of its file name**. Every asset this
workflow used to upload carried `label=brapmony-android sha256:<hash>`, which was meant to
record which bytes the file held — and which turned the release page into a list of

```
brapmony-android sha256:baf1c5ee9cd9ff9890c84a6853cb1f233b8ab0dcfd32c5feb497a912573b9dce
brapmony-android sha256:aa600d9da917481fcea01e4dbc7211b7e7343d89098a6827b47a1b42d7f8de2d
…
```

with no `Brapmony-latest.apk` anywhere on it. The download URL worked the whole time, which
is why nothing failed: what was broken was the page, not the file. So:

- **No upload carries a label.** `scripts/release.mjs` sends the name and nothing else.
- **Ownership is read from the uploader and the name.** GitHub attributes a workflow's
  uploads to `github-actions[bot]`, and a file somebody attaches by hand carries their
  account. Because every workflow in this repository uploads as that same bot, the name has
  to be one this workflow generates as well; only then is a file replaced or pruned. A file
  attached by hand, or uploaded by another workflow, is left exactly where it is.
- **"The same bytes" is GitHub's own digest.** Every asset's `digest` field is GitHub's
  SHA-256 of what it stored; that is compared with the file in hand, and the byte count is
  the fallback where an API reports no digest. A re-run that finds different bytes under its
  name publishes them under their own name rather than reporting success.
- **The labels already written are cleared.** The first publish after this change sends an
  empty label for every asset that carries the old one and reads the list back: a label that
  did not clear is reported as a warning naming the asset, so a page whose names are still
  hidden is never described as fixed.

The release's tag also moves with each publish, so the commit the page names is the commit
the assets were built from rather than the one the release was first created at.

## Provenance: what was built, from which commit

GitHub can sign a statement about an artifact -- a *build provenance attestation* -- and keep
it against the repository. The statement says which workflow, in which repository, at which
commit produced these exact bytes, and it is signed with a short-lived certificate that
GitHub's OIDC identity minted for that one run. It is not a checksum on a release page: the
signature is verifiable by anybody, without trusting this workflow or this repository.

Every build this repository publishes is attested, in the job that publishes it:

| Subject | What it is about | Where it is stored |
| --- | --- | --- |
| The APK file | The bytes on the release and the artifact | The repository's attestations, by the file's digest |

The attestation is created with
[`actions/attest`](https://github.com/actions/attest) (pinned by commit), which needs
`id-token: write` to mint the signing certificate and `attestations: write` to store the
statement. Both are granted to the publishing job alone -- never to the build job, which a
pull request's code runs: a pull request must not be able to obtain a signed statement that
GitHub's workflow produced its code.

A person who downloaded an APK checks it with the GitHub CLI:

```sh
gh attestation verify Brapmony-latest.apk --repo WWPOL/harmony-android
```

That answers two questions at once: the file is the one GitHub signed for this repository,
and the workflow that built it was `android.yml` (or `release.yml`) at a commit. To pin it
further, name the workflow and the commit:

```sh
gh attestation verify Brapmony-latest.apk \
  --repo WWPOL/harmony-android \
  --signer-workflow WWPOL/harmony-android/.github/workflows/android.yml \
  --source-digest "$(git rev-parse HEAD)"
```

The workflows check their own work the same way before they finish: after publishing, they
run exactly those commands against what they just pushed, with `--signer-workflow`,
`--source-digest` and `--deny-self-hosted-runners`. A run whose attestation cannot be read
back and verified fails there instead of printing a summary that claims provenance. The
attestations API is eventually consistent, so each check is retried a few times; a statement
that names another repository, workflow or commit fails all the same.

### What GitHub itself computes

Two things are recorded by GitHub rather than by this workflow, and both are used:

- **A digest of every release asset.** GitHub computes a SHA-256 over each asset as it stores
  it, exposes it as `digest` in the REST API, and shows it on the release page.
  `scripts/release.mjs` compares it with the file it uploaded, so a truncated upload, a
  proxy that rewrote the body, or a wrong file is caught at publish time -- by something
  other than the checksum this workflow wrote itself. The comparison is skipped when an API
  reports no digest (an older GitHub Enterprise); a mismatch always fails the publish.
- **A digest of the artifact the publish job downloads.** `actions/download-artifact`
  verifies what it fetched against the digest recorded when the build uploaded it, and the
  job sets `digest-mismatch: error`, so a transfer that changed anything fails before
  anything is published.

Both are also why the release's own `.sha256` is not the whole story: it is this workflow's
claim, and the API's `digest` and the attestation are GitHub's.

### What GitHub offers that this workflow does not use, and why

- **Immutable releases.** GitHub can make a release and its assets immutable, and then signs
  a release attestation of its own (`gh release verify`, `gh release verify-asset`). It is a
  repository setting, and it is **not** compatible with what a rolling release is: an
  immutable release cannot have assets added or renamed, which is exactly how
  `Brapmony-latest.apk` is moved onto each new build and how the ten-build window is pruned.
  Enabling it would fail every publish. A version tag's release could be immutable, but the
  setting is per repository, not per release, so it is off. What it would add -- a signed
  statement that these assets belong to that tag -- the provenance attestation already says,
  and says it about the file a phone installs.
- **Actions artifacts as a download route.** The artifact is kept because it is the record of
  what a run built, with the `.sha256` and the metadata file beside it, and because the
  publish job reads it back instead of rebuilding. It is not a route to offer people: it
  expires after 30 days and needs a GitHub login to download. The release is the route that
  outlives a run.
- **A cache of the Gradle build between runs.** It would make a build faster, and it is a
  separate change with its own risk (a stale cache producing a different APK from the same
  commit, which the per-commit artifact name would then not be able to tell apart). The
  provenance attestation would still bind whatever was built to its commit, so it is safe to
  add later, but it is not part of this.

## What a tag publishes

A version tag (`vX.Y.Z`) runs
[`release.yml`](https://github.com/WWPOL/harmony-android/blob/main/.github/workflows/release.yml),
which checks that the tagged commit's `VERSION` is the tag's number, builds the release variant
with that release line as its version name, and attaches the APK to the release for that tag.
A tag whose commit says a different version is refused, so a release cannot be published under
a number the tree does not carry.

| Signing configured | Asset |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` and its three companions | `Brapmony-X.Y.Z.apk` |
| Not configured | `Brapmony-X.Y.Z-debug.apk` |

Both are accompanied by a `.sha256`. The job creates the tag's release if it does not exist
and finds it if it does, so re-running a tag attaches to the same release instead of creating
a second one, and it replaces only the two names it owns. It never touches another release.
The release's body names the commit the tag resolved to, with a link to it, and how to
verify the asset. The APK is attested in the job that publishes it, and the attestation is
verified before the run finishes (see
[Provenance](#provenance-what-was-built-from-which-commit)). Nothing is pushed to a
container registry for a version tag either.

## Signing

| Build | Key | Installs over |
| --- | --- | --- |
| Debug, every push, stable test key configured | The repository's test key, from a secret | Any other build this workflow signed with that key |
| Debug, every push, no test key | A key generated for this checkout | Nothing signed by another run |
| Release from a tag, keystore secret configured | The project's release key | The previous release-signed build |
| Release from a tag, no keystore secret, stable test key | The repository's test key | Any other build signed with that key |
| Release from a tag, neither secret | A key generated for that run | Nothing signed by another run |

An APK is named for the key it carries: `-debug` means test-signed. It installs, and it is
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
stays named `-debug`. Without the secrets CI generates nothing to keep and stores no key; each
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
read out of the APK that run published — downloaded again from the rolling release and
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
| The release for the computed rolling tag carries none of this workflow's own assets | Refused; nothing is written |
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

Android installs a build over an installed app when the package is the same, the signing
certificate matches, and the `versionCode` is not lower. The scheme above gives the third one
for free: the code is the commit count, so a later commit is always higher and a build of the
same commit is always equal. A higher code is necessary but not sufficient — the certificate
is the other half, so an install is only ever upgraded in place by a build signed with the key
the installed one carries (see [Signing](#signing)).

The debug variant appends `-debug` to the version name, so the About row and the APK's file
name say which build is installed. The package is the same in every build type: a debug build
and a release build of one commit are the same app to Android, and only the certificate decides
whether one installs over the other.

## Cutting a release

A version release is a tag. The version line is bumped in a commit, and the tag goes on that
commit:

```sh
printf '%s\n' 0.2.0 > VERSION          # the release line, bumped on purpose
git commit -am 'chore(release): 0.2.0'
git tag v0.2.0
git push origin main --follow-tags      # the tag runs release.yml
```

`release.yml` refuses a tag whose commit's `VERSION` is not the tag's number, so a release
cannot be published under a version the tree does not carry. It builds the release variant,
names the APK `Brapmony-0.2.0.apk` (`-debug` when test-signed), attaches it and its `.sha256`
to the release for the tag, and attests it. Re-running the tag finds the same release and
replaces only the names it owns.

No tag has been pushed yet, so `0.1.0` is the release line with no release under it: what
exists today is the rolling release of `main`.

## Cleaning up what nothing publishes to any more

A rolling tag is created by the first publish of a branch and moved by every one after it.
When a branch is deleted or renamed, nothing moves its tag again: the tag stays in the
repository's tag list, and its release stays on the Releases page, pointing at a build no
branch will ever refresh. A workflow cannot notice — it only ever runs for the branch it is
publishing — so the sweep is separate and explicit:

```sh
node scripts/release.mjs cleanup --default-branch main              # lists
node scripts/release.mjs cleanup --default-branch main --apply      # deletes
```

or **Actions → Clean up releases and builds → Run workflow**, where `apply` is a checkbox that
is off by default. It removes the rolling releases and tags of branches that no longer exist,
a rolling tag whose release was deleted by hand (a tag is a page on the repository too, and it
can only be found from the tag list), and Actions artifacts beyond the newest ten. It never
touches a version release, a version tag, the rolling release of a branch that exists, or the
default branch's release; each of those is a test in `scripts/release.test.mjs`.

The list, without the deletion, is also what to read when the Releases page has something on it
that should not be there.

## Working on the workflows

Three Node scripts exist for checking the workflows without a runner:

| Script | What it does |
| --- | --- |
| `scripts/release.test.mjs` | Runs the publishing and cleanup commands against an in-process stand-in for the GitHub releases API |
| `scripts/workflow.test.mjs` | Parses all three workflows and asserts the properties that make publishing and deleting safe |
| `scripts/workflow-steps.mjs` | Runs one job's `run:` steps with `bash -eo pipefail`, as GitHub does, on this machine |

The first two run in CI with the unit tests:

```sh
npm ci
node --test scripts/*.test.mjs
```

The publisher tests cover what a published download depends on: one release per branch, the
previous builds kept, only the stable names replaced, the retired names retired and nothing
else deleted, a re-run that changes nothing, a rebuild that gets its own name, a foreign asset
that is never overwritten, a release that carries none of this workflow's assets refused, a
failed publish that leaves the last good build downloadable and the page describing it,
retention bounded to this workflow's own builds, a version release left entirely alone, a
default branch's release moved out of the prereleases, another branch's left a prerelease, a
tag release created once and found again on a re-run, a stored asset whose digest is not the
file that was uploaded given its own name rather than reported as kept, an asset that carries
a label (which would hide its name on the page) cleared and a label that will not clear
reported, a rolling tag moved onto each build and a version tag refused, and an API that
reports no digest not turned into a failure.

The workflow tests assert that every action is pinned to a commit SHA, that
`pull_request_target` is not used, that `contents: write`, `id-token: write` and
`attestations: write` appear only on the publishing jobs and never on the build job a pull
request runs, that `packages: write` and `artifact-metadata: write` appear nowhere, that the
branch publisher runs only for a push to this repository, that a push has no path filter, that
the stable test key is configured from a secret and its absence reported, that the tag job
refuses a tag whose commit's `VERSION` is another number and attaches by release id, that the
build number's base is the same in Gradle and in both workflows, that the cleanup workflow is
dispatch-only and lists before it deletes, that the APK namer is called with a
profile it accepts and really renames the file to the name the scheme says, that a re-run's
artifact does not land on the first attempt's name, that no upload carries a label and the old
ones are cleared, that the publish moves the rolling tag onto the commit it built, that no
step pipes `aapt2` or `apksigner` into `head` (which fails the step with SIGPIPE under
`pipefail`), that each publishing job attests the APK (by path) and reads the attestation back
with `gh attestation verify` pinned to the repository, the signer workflow and the commit,
that the download the publish job reads is checked against the digest GitHub recorded
(`digest-mismatch: error`), and that the repository holds the Android app and nothing else.

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
itself. It models GitHub's per-asset `digest`, so a publish run against it exercises the
comparison rather than skipping it:

```sh
GITHUB_REPOSITORY=owner/repo node scripts/fake-github-api.mjs 8099 &
node scripts/workflow-steps.mjs .github/workflows/android.yml publish \
  --step 'Publish' --cwd <a directory holding android-apk/<the apk>> \
  --set 'secrets.GITHUB_TOKEN=t' --set 'GITHUB_API_URL=http://127.0.0.1:8099' \
  --set 'github.ref_name=main' --set 'needs.build.outputs.sha256=<sha256sum of the apk>'
```

The two steps that need a real GitHub are the ones this cannot reproduce: `actions/attest`
signs through GitHub's OIDC endpoint, and `gh attestation verify` reads the attestations API.
The verification step itself — the retries, the flags, and failing the job when nothing
verifies — is runnable here with a stand-in on `PATH` that records its arguments, which is how
its two outcomes were checked.

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
runner: the build job produced and uploaded its artifact, and the publish job created the
rolling release, uploaded this run's APK, moved the stable APK name and its `.sha256` onto
those bytes, fetched the download back and checked it, and printed the links. So the runner's
environment — the `sdkmanager` package names, the `aapt2` and `apksigner` paths,
`upload-artifact` and `download-artifact`, and the token's permission to create a release — is
demonstrated, not assumed. That run predates the name scheme described here, so it proves the
mechanism, not the current names.

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

**What the repository actually offered.** `main`'s rolling release existed after the merge of
that run, but it was a **prerelease**, so `api.github.com/repos/WWPOL/harmony-android/releases/latest`
answered 404 and the repository page showed no Releases at all; only the tag-specific download
URL resolved. That was fixed: the default branch's build is a release, so the Releases box and
`/releases/latest` reach it.

**What it offered after that was unreadable.** The release existed, the download URL worked,
and the page listed `brapmony-android sha256:…` five times and not one file name, because
every asset carried a label and GitHub shows the label instead of the name. A person looking
for `Brapmony-latest.apk` on that page found nothing to tap. That is what this change fixes;
[Every asset shows its own name](#every-asset-shows-its-own-name) says how. The screenshot of
the page as it was is in
[`docs/evidence/release/`](evidence/release/), beside the asset list the fixed publisher
produces.

**What has been checked here, and what has not.** The naming, the published/prerelease
decision and the retirement of the old names are checked by `npm test` against an in-process
stand-in for the releases API, and the first push after this change is reproduced exactly:
the test seeds the stand-in with the release `main` actually had — `Brapmony_Android_latest.apk`,
its `.sha256`, and `Brapmony_Android_V1.9.6_debug-main-606ab10.apk`, read from the GitHub API
— publishes one build, and asserts that it becomes a release named `Brapmony for Android —
newest build of main`, with the new names, the old stable names retired, and the old build
left for pruning.

The `Publish` step is also run as the workflow runs it, with `scripts/workflow-steps.mjs`
against `scripts/fake-github-api.mjs`, and the release it produces is read back: every asset
name present, no label on any of them, and the tag pointing at the commit that was published.
The APK used for the step runs is a real one, from
`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`, which was
`BUILD SUCCESSFUL`.

What has **not** been exercised is GitHub itself: that its API clears a label on a `PATCH`
(the run reads the list back and warns if it does not), and the re-download of the published
link, which needs a real release to exist. The workflow is written so that a failure in
either fails the run or is reported, and never publishes a summary that looks fine.

**Provenance has not run on a runner either.** The attestation steps are pinned actions and
their inputs are asserted by `npm test`, and the verification step's two outcomes — a check
that succeeds after a retry, and one that fails the job when nothing verifies — were run
locally with a stand-in `gh` that records its arguments. What that cannot show is the parts
only GitHub can do: minting the OIDC signing certificate, storing the attestation, and the
attestations API answering `gh attestation verify`. The first push to `main` after this
merges is what produces the first real attestation; until then, treat the provenance section
as the mechanism, checked where it can be, rather than as an observed one.

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
