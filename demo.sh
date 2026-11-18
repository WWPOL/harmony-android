#!/usr/bin/env bash
# Build a debug APK, install it on an attached emulator and open it.
#
#   ./demo.sh
#
# Points the build at the instance on the emulator's host gateway. Override with
# NATIVE_INSTANCE_URL and NATIVE_ANON_KEY, or leave both unset for a build that starts
# on the instance picker instead. ANDROID_SERIAL selects a device when several are
# attached; ADB_SERVER_SOCKET does the same for a non-default adb server.
#
# Use a disposable account. This client is not parity-complete; see README.md.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"
elif [ -n "$SDK" ] && [ -x "$SDK/platform-tools/adb" ]; then ADB="$SDK/platform-tools/adb"
else printf 'Install Android platform-tools and set ANDROID_HOME first.\n' >&2; exit 1; fi
"$ADB" get-state >/dev/null
cd "$ROOT"
./gradlew :app:assembleDebug --console=plain \
    -PharmonyInstanceUrl="${NATIVE_INSTANCE_URL:-http://10.0.2.2:54480}" \
    -PharmonyAnonKey="${NATIVE_ANON_KEY:-local-e2e}"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n io.github.wwpol.brapmony/lol.mony.harmony.MainActivity
printf '\nOpened. Sign in with an account on that instance.\n'
printf 'This client is not parity-complete. See docs/parity-audit.md.\n'
