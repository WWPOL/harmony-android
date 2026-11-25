# The release page, the version it claimed, and what is left to sweep

## What was wrong

Three separate things, which looked like one because they all showed up on the Releases
page.

**The version never moved.** `VERSION` said `1.9.6` in the initial commit and nothing ever
changed it (`git log --follow -- VERSION` shows one change: the commit that added it). The
value was inherited from the project this app was split out of, whose `package.json` is
`1.9.7` and whose tags run `v1.9.6`, `v1.9.5`, `v1.9.4`, … So every build of every commit
reported `versionName 1.9.6`, `versionCode 1009006`.

**Every commit was therefore "1.9.6".** `app/build.gradle.kts` read `VERSION` for both
`versionName` and `versionCode` (`maj*1e6 + min*1e3 + pat`), and `scripts/name-artifact.sh`
read it again for the file name. One constant, three places, no commit in it. Only the
`-<sha7>` in the file name told two builds apart.

**The rolling release was titled after that constant.** `releaseTitle()` made it
`Brapmony 1.9.6` for every branch and every commit, and the README's download table named
`Brapmony-1.9.6-606ab10-debug.apk` as its example, which was the same constant leaking into
the documentation.

**And nothing was ever tagged**, so no version release existed to be the named one. `git tag -l`
is empty, and `api.github.com/repos/WWPOL/harmony-android/releases` answered `[]`: the rolling
release had been deleted, leaving its tag and the other branch's behind.

## What the fix does

See [docs/builds.md](../../builds.md) for the whole of it. In short:

- `VERSION` is `0.1.0`, the release line, bumped on purpose. It is not per commit.
- A build is its commit: `versionName = <VERSION>-<sha7>[-debug]`, `versionCode = 2000000 +
  git rev-list --count HEAD`. Verified on the built APK:
  `versionCode='2000014' versionName='0.1.0-049bcbb-debug'` (14 commits, base 2 000 000).
- A tag build reports the plain release line: `versionCode='2000014' versionName='0.1.0'`,
  from `-PharmonyReleaseBuild`.
- The rolling release is `Brapmony for Android — newest build of main`, with the build's
  version, versionCode, commit and checksum in its body.
- `node scripts/release.mjs cleanup` (and the `Clean up releases and builds` workflow, a
  manual dispatch) removes stale rolling releases, orphan rolling tags, and old artifacts.

## The remote state, as it was, and how to sweep it

Read from `api.github.com` on 2026-11-23, before the fix was pushed:

```
releases:  0
tags:      android-rolling-main                                   -> 049bcbb
           android-rolling-task-verify-apk-ci-publish-flow-3e8cphaa -> fbd613f
artifacts: Brapmony-1.9.6-049bcbb-debug      2026-11-23
           Brapmony-1.9.6-e5b7aec-debug      2026-11-23
           Brapmony-1.9.6-85332c5-debug      2026-11-20
           android-apk-44820612102-1          2026-11-20
           android-apk-44533075269-1          2026-11-19
           native-android-apk-44255375851-1   2026-11-18
```

Both tags name branches that still exist: `/branches` lists `main` and
`task/verify-apk-ci-publish-flow-3e8cphaa`, and the second tag's slug is that branch's. So the
sweep keeps both, and deletes only the artifacts. Deleting the task branch first would make
its tag an orphan, and the next sweep would remove it -- which is what the orphan path is for.
The two `android-apk-<run>-1` and `native-android-apk-<run>-1` artifacts are from before the
artifact name scheme, and nothing reads them.

The sweep is one dispatch once this is merged:

```
Actions -> Clean up releases and builds -> Run workflow
  apply:           true
  keep-artifacts:  3
```

or, with a token, the same thing from a shell:

```sh
GITHUB_TOKEN=… GITHUB_REPOSITORY=WWPOL/harmony-android \
  node scripts/release.mjs cleanup --default-branch main --keep-artifacts 3            # lists
GITHUB_TOKEN=… GITHUB_REPOSITORY=WWPOL/harmony-android \
  node scripts/release.mjs cleanup --default-branch main --keep-artifacts 3 --apply    # deletes
```

**This has not been run against the live repository.** No token was available here, so what
the remote holds after the merge is what the first push and the dispatch produce; the state
above is what it held before. What *is* checked is the command's own behaviour against a
stand-in for the API, including that it refuses a version tag and leaves a live branch's
release alone (`scripts/release.test.mjs`).

After the first push to `main` with this change, the rolling release will exist again, as
`Brapmony for Android — newest build of main`, holding `Brapmony-0.1.0-<sha7>-debug.apk` and
`Brapmony-latest.apk`. Its old title, `Brapmony 1.9.6`, is what the body and title are
rewritten away from on that first publish.
