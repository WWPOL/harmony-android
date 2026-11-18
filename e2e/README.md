# Driving the app on a device

The tests under `app/src/test/` run on the JVM and need no device. These scripts drive the
built APK on a real screen, and they are the only things here that do.

A device here means an **emulator**. Never a person's phone: installing on one replaces an app
somebody is using, and driving it takes their screen.

| File | What it does |
| --- | --- |
| `journey.py` | Signs in, opens a server and a channel, sends a message, checks the instance stored it, and visits each top-level destination. Writes a screenshot and a UI dump per step. |
| `device-journey.sh` | Boots an AVD, installs, and taps through the same journey, recording `uiautomator dump` after each tap instead of pictures. For a host whose emulator cannot produce a framebuffer. |
| `uixml.py` | Reads a UI dump: lists the nodes, or finds one by name and prints its centre, so a shell script can tap it. |
| `px.py` | Says what a capture actually is -- a real picture, or the uniform black PNG an emulator without working GL produces. |
| `grpcshot.py` | Reads the emulator's framebuffer over its gRPC endpoint. |
| `fuzz/` | Hypothesis stateful testing of the chat contracts against a real instance. See [fuzz/README.md](fuzz/README.md). |

## Before either journey

Build the debug APK, and have an instance with throwaway accounts on it:

```sh
./gradlew :app:assembleDebug
export NATIVE_INSTANCE_URL=http://localhost:54480   # the instance the app and the check talk to
export NATIVE_EMAIL=...                             # a throwaway account on it
export NATIVE_PASSWORD=...                          # never logged, never committed
```

`journey.py` reads the instance's own rows after the send, with the account's own token, so a
screen that drew a message the server never stored fails the journey. Pass `--server` and
`--channel` to name what to open; without them it opens the first server's `general` channel.

```sh
python3 e2e/journey.py \
  --serial emulator-5554 --output "$TMPDIR/brapmony-evidence"
```

Set `--adb` to an SDK adb executable if it is not on `PATH`, and `--adb-host`/`--adb-port` for
a non-default adb server. It installs the APK from `app/build/outputs/apk/debug/` unless
`--apk` names another.

`device-journey.sh` is the same journey for a host whose emulator cannot render into
`screencap`:

```sh
bash e2e/device-journey.sh "$TMPDIR/brapmony-evidence"
```

It needs `ANDROID_HOME`, an `aosp30` AVD, the debug APK already built, and the same three
environment variables.

## What the evidence means

A passing journey is an **initial smoke journey**, not a claim of feature parity. Captured
screens still need a person or a model to look at them; a UI dump proves a node was on screen,
not that the screen was right. [docs/verification.md](../docs/verification.md) says what has
been inspected and what remains unverified, and
[docs/parity-audit.md](../docs/parity-audit.md) names every gap.

Screens and logs from earlier runs are kept under [docs/evidence/](../docs/evidence/).
