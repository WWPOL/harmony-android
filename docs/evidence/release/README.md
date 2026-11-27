# Release evidence: one release per commit, and the page that was there before

## What was wrong, in the repository's own output

`live-release-page-before.png` and `live-release-assets-before.png` are screenshots of
**github.com**, not drawings: `WWPOL/harmony-android/releases/tag/android-rolling-main`, taken
at 420 px wide, the width a phone gives it, on 2026-11-23.

The release exists and is marked Latest, its body says the right commit and checksum, and the
download URL works: fetching it returns 69 196 999 bytes whose SHA-256 is
`aa600d9da917481fcea01e4dbc7211b7e7343d89098a6827b47a1b42d7f8de2d`, the checksum the body
names. So the APK was published the whole time.

What was broken is the list. Every asset was uploaded with a label,
`brapmony-android sha256:<hash>`, and GitHub renders a label **in place of the file name**, so
the page shows five entries reading

```
brapmony-android sha256:baf1c5ee9cd9ff9890c84a6853cb1f233b8ab0dcfd32c5feb497a912573b9dce
brapmony-android sha256:aa600d9da917481fcea01e4dbc7211b7e7343d89098a6827b47a1b42d7f8de2d
…
```

and not one file name. There is no `Brapmony-latest.apk` on the page to tap, which is what
"the apk doesn't seem to be showing up" looks like.

## What the publisher produces now

A release per commit of `main`, named for the build and carrying one APK and its checksum.
`publish-step-run.txt` is the workflow's own `Publish` step, run by `scripts/workflow-steps.mjs`
against the stand-in for the releases API (`scripts/fake-github-api.mjs`), seeded with the state
`main` was in on 2026-11-23, and the release it left behind read back from the API. It is a
transcript, not a drawing:

- the tag is `v0.1.0-f9d8bbf` — the release line and the commit — and the title is
  `0.1.0-f9d8bbf`, so the page names the build rather than a branch;
- the body is the commits since the previous release, one line each, with the APK's SHA-256
  and a link to the whole comparison. No description of the app and no install steps: they are
  the same on every release and are in the README;
- two assets, and both are this build's: `Brapmony-0.1.0-f9d8bbf-debug.apk` and its
  `.sha256`. No stable alias and no older build beside it, so the file on the page is the file
  to download;
- no asset carries a label, so every entry shows its own name;
- the release was created as a draft and made visible only once both files were on it.

The rolling releases and tags of the scheme this replaced — one per branch, ten builds and a
`Brapmony-latest.apk` on each — are deleted by the first publish after this change, and by
`cleanup`. `asset-names-before-and-after.txt` is that removal in one transcript: the page as
the seed had it, then the same page read back after a single publish, with the tags gone.

`live-release-page-before.png` and `live-release-assets-before.png` are screenshots of
**github.com**, not drawings: `WWPOL/harmony-android/releases/tag/android-rolling-main`, taken
at 420 px wide, the width a phone gives it, on 2026-11-23. They are the page that was there
before any of this, kept because it is the thing that was wrong.

## What is not shown here

A screenshot of a release as github.com renders it. It needs the first push to `main` after
this change merges; nothing in this tree can produce it. What can be checked here is checked:
the step run above is the workflow's own step, and the release it produced is read back from
the API rather than described.
