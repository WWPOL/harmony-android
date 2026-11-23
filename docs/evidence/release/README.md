# Release evidence: the page that hid the APK, and the names that replace it

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

## What the fixed publisher produces

`asset-names-before-and-after.txt` is a run of `scripts/release.mjs` against the stand-in for
the releases API (`scripts/fake-github-api.mjs`), seeded with that release **asset for asset
and label for label, read from `api.github.com`**. The top is what the page shows today; the
bottom is what one publish produces. It is a transcript, not a drawing:

- no asset carries a label any more, so every entry shows its own name;
- the assets that already carried one have it cleared, and their names appear again;
- `Brapmony-latest.apk` is on the page, under its own name.

`publish-step-run.txt` is the workflow's own `Publish` step, run by `scripts/workflow-steps.mjs`
with the real 69 MB APK, exactly as the job runs it. Reading the release back afterwards gives

```
Brapmony-1.9.6-e5b7aec-debug.apk     label=""  uploader=github-actions[bot]
Brapmony-latest.apk                  label=""  uploader=github-actions[bot]
Brapmony-latest.apk.sha256           label=""  uploader=github-actions[bot]
tag android-rolling-main -> e5b7aec558f4ee52d8cb6a9850750d64751484d2
```

— every name visible, and the tag naming the commit that was built rather than the commit the
release was first created at.

## What is not shown here

A screenshot of the fixed page on github.com. It needs the first push to `main` after this
change merges; nothing in this tree can produce it. What can be checked here is checked, and
the run itself reads the asset list back after clearing the old labels, so a page whose names
are still hidden is reported in the log instead of being described as fixed.
