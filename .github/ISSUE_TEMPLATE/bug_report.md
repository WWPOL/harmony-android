---
name: Bug report
about: Something in the app does not work
title: '[BUG] '
labels: bug
assignees: ''
---

## What happened

A clear description of the bug, and what you expected instead.

## Steps to reproduce

1. Open '...'
2. Tap '...'
3. Scroll to '...'
4. See the problem

## App and device

- **App version**: (the account screen shows it; `adb shell dumpsys package
  io.github.wwpol.brapmony | grep versionName` prints it for a debug build)
- **Where the build came from**: a release of `main` (give the file name it was downloaded
  as, e.g. `Brapmony-0.1.0-606ab10-debug.apk`), a version-tag release, a CI artifact of a
  branch, or a local `./gradlew :app:assembleDebug`
- **Android version and device**: e.g. Android 14, Pixel 7; or the emulator image
- **Instance**: the Harmony instance's address, if the bug depends on it

## Logs

`adb logcat -d -b crash` around the failure, if the app crashed. Redact tokens, passwords and
anything from your session file.
