# Release evidence: what the pages will show

Two drawings of the pages this change publishes, made from the **real output of the
publish step** run locally against the stand-in for the releases API, with a real 67 MB
APK from `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. The asset
names, the size, the version, the versionCode, the commit and the checksum are what that
run printed; the layout is GitHub's.

They are **drawings, not screenshots of github.com**. Nothing is published there yet — the
first push to `main` after this change merges is what will make these pages real, and it
is the one thing that cannot be done from here. What they show is what the workflow
produces: the names, the release's title and body, and the package's tags and metadata.

- `release-page.png` — the release of `main`: titled `Brapmony 1.9.6`, marked Latest,
  carrying `Brapmony-latest.apk`, its `.sha256` and the build of the commit.
- `package-page.png` — the same APK as a container package: `oras pull
  ghcr.io/wwpol/harmony-android:latest`, the artifact type, the source repository, the
  version, the revision, and the four tags.

One detail on the package drawing is the *expected* state rather than an observed one:
**Public**. GitHub creates a first-published package private, so the first run will report
it as private and warn, with the settings page to visit; it becomes Public when somebody
makes it so. The step prints which of the two it is, so the run's own summary is the record
rather than this drawing.
