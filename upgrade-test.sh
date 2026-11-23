#!/usr/bin/env bash
# Two consecutive CI APKs, installed one over the other on an emulator, with the
# app's own stored data kept between them.
#
# This is the check that a downloadable build can replace the build somebody already
# has: same package, same signing certificate (by SHA-256 fingerprint, not by
# subject), and a higher versionCode. It runs against an emulator only; a person's
# phone is never touched.
#
#   ./upgrade-test.sh prepare <out-dir>
#   ./upgrade-test.sh run <out-dir> [serial]
#
# prepare builds the tree twice: once as it is, then once with the build number raised
# by one (-PharmonyVersionCode), which is what a later commit produces. The version name
# stays the commit's, because it is the same commit: what a later commit changes is the
# number Android compares. Both are signed with whatever test-keystore.properties
# configures (or the generated debug key) and named as the workflow names them.
set -euo pipefail
MODE=${1:?usage: upgrade-test.sh <prepare|run> <out-dir> [serial]}
OUT=${2:?usage: upgrade-test.sh <prepare|run> <out-dir> [serial]}
SERIAL=${3:-emulator-5554}
ROOT=$(cd "$(dirname "$0")" && pwd)
HERE=$ROOT
OUT=$(mkdir -p "$OUT" && cd "$OUT" && pwd)
ADB=$ANDROID_HOME/platform-tools/adb
D() { "$ADB" -s "$SERIAL" "$@"; }
APKTOOLS=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)
PACKAGE=io.github.wwpol.brapmony

version_code() { "${APKTOOLS}aapt2" dump badging "$1" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1; }
package_name() { "${APKTOOLS}aapt2" dump badging "$1" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1; }
certificate() { "${APKTOOLS}apksigner" verify --print-certs "$1" | sed -n 's/^.*certificate SHA-256 digest: //p' | head -1; }

describe() {
  local apk=$1 label=$2
  {
    echo "$label"
    echo "  file: $(basename "$apk")"
    echo "  $("${APKTOOLS}aapt2" dump badging "$apk" | head -1)"
    echo "  certificate SHA-256: $(certificate "$apk")"
    echo "  sha256: $(sha256sum "$apk" | cut -d' ' -f1)"
  } | tee "$OUT/$label.txt"
}

if [ "$MODE" = prepare ]; then
  version=$(tr -d '[:space:]' < "$ROOT/VERSION")
  sha=$(git rev-parse --short=7 HEAD)
  # The number Gradle derives for this commit, and the one a later commit would give it.
  # Read from git the same way the build reads it, so the two agree.
  code=$((2000000 + $(git rev-list --count HEAD)))
  next_code=$((code + 1))
  # Both builds report the same version name, because they are the same commit: what
  # Android compares to decide an upgrade is the versionCode, and that is what differs.
  cd "$HERE"
  echo "### first build: $version-$sha, versionCode $code"
  ./gradlew :app:assembleDebug --console=plain --max-workers=2 > "$OUT/build-1.log" 2>&1
  cp app/build/outputs/apk/debug/app-debug.apk "$OUT/Brapmony-$version-$sha-code$code-debug.apk"
  echo "### second build: $version-$sha, versionCode $next_code"
  ./gradlew :app:assembleDebug -PharmonyVersionCode="$next_code" --console=plain --max-workers=2 > "$OUT/build-2.log" 2>&1
  cp app/build/outputs/apk/debug/app-debug.apk "$OUT/Brapmony-$version-$sha-code$next_code-debug.apk"
  first="$OUT/Brapmony-$version-$sha-code$code-debug.apk"
  second="$OUT/Brapmony-$version-$sha-code$next_code-debug.apk"
  describe "$first" first
  describe "$second" second
  first_pkg=$(package_name "$first"); second_pkg=$(package_name "$second")
  first_cert=$(certificate "$first"); second_cert=$(certificate "$second")
  first_code=$(version_code "$first"); second_code=$(version_code "$second")
  [ "$first_pkg" = "$second_pkg" ] || { echo "::error::different packages"; exit 1; }
  [ "$first_cert" = "$second_cert" ] || { echo "::error::different certificates: $first_cert and $second_cert"; exit 1; }
  [ "$second_code" -gt "$first_code" ] || { echo "::error::versionCode did not increase"; exit 1; }
  echo "### same package $first_pkg, same certificate, versionCode $first_code -> $second_code"
  echo "### prepare OK"
  exit 0
fi
if [ "$MODE" = run ]; then
  # The two files `prepare` wrote, oldest build number first. Both report the same
  # version name -- they are the same commit -- so the order comes from the versionCode in
  # their names, which is the number Android compares.
  first=$(ls "$OUT"/Brapmony-*-code*-debug.apk | sort -V | head -1)
  second=$(ls "$OUT"/Brapmony-*-code*-debug.apk | sort -V | tail -1)
  second_code=$(version_code "$second")
  D wait-for-device
  echo "### installing the first build"
  D uninstall "$PACKAGE" > /dev/null 2>&1 || true
  D install "$first" | tail -1
  D shell am start -n "$PACKAGE/lol.mony.harmony.MainActivity" > /dev/null 2>&1 || true
  sleep 25
  # The data directory exists only once the app has run once, and a first start on a cold
  # emulator can take a while; wait for it rather than racing it.
  for i in $(seq 1 24); do
    D shell "run-as $PACKAGE ls data" > /dev/null 2>&1 && break
    sleep 5
  done
  # `adb shell` splits its arguments, so the script is one quoted word.
  D shell "run-as $PACKAGE mkdir -p files" > /dev/null 2>&1 || true
  D shell "run-as $PACKAGE sh -c 'echo upgrade-probe > files/upgrade-probe.txt'"
  D shell "run-as $PACKAGE ls -l files shared_prefs 2>/dev/null" > "$OUT/data-before.txt" 2>&1 || true
  probe_before=$(D shell "run-as $PACKAGE cat files/upgrade-probe.txt" 2>/dev/null | tr -d '\r')
  code_before=$(D shell dumpsys package "$PACKAGE" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
  echo "  probe before: ${probe_before:-<absent>}"; echo "  versionCode before: ${code_before:-?}"
  echo "### installing the second build over it, with no uninstall"
  D install -r "$second" | tail -1
  sleep 15
  D shell am start -n "$PACKAGE/lol.mony.harmony.MainActivity" > /dev/null 2>&1 || true
  sleep 20
  D shell "run-as $PACKAGE ls -l files shared_prefs 2>/dev/null" > "$OUT/data-after.txt" 2>&1 || true
  probe_after=$(D shell "run-as $PACKAGE cat files/upgrade-probe.txt" 2>/dev/null | tr -d '\r')
  code_after=$(D shell dumpsys package "$PACKAGE" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
  echo "  probe after: ${probe_after:-<absent>}"; echo "  versionCode after: ${code_after:-?}"
  status=0
  if [ -z "$probe_after" ] || [ "$probe_after" != "$probe_before" ]; then echo "::error::the app's data did not survive the upgrade"; status=1; fi
  if [ "${code_after:-0}" != "$second_code" ]; then echo "::error::installed versionCode ${code_after:-?}, expected $second_code"; status=1; fi
  D logcat -d -b crash 2>/dev/null | tail -40 > "$OUT/crash.txt"
  if grep -q 'FATAL EXCEPTION' "$OUT/crash.txt"; then echo '::error::the app crashed'; cat "$OUT/crash.txt"; status=1; fi
  D shell dumpsys package "$PACKAGE" > "$OUT/package-after.txt" 2>&1 || true
  if [ $status -eq 0 ]; then echo '### upgrade in place: OK'; else echo '### upgrade in place: FAILED'; fi
  exit $status
fi
echo "usage: upgrade-test.sh <prepare|run> <out-dir> [serial]" >&2
exit 2