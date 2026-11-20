#!/usr/bin/env bash
# Names a CI build output, so a dev build and a release share one scheme:
#
#   bash scripts/name-artifact.sh [--release] <release|debug> [file]
#
# With [file], renames it in place to <stem>.<ext>, prints the new path and appends
# `path=<new path>` to GITHUB_OUTPUT when that is set. Without it, prints <stem>.
#
#   branch build:  Brapmony-<version>-<sha7>[-debug].apk
#   --release:     Brapmony-<version>[-debug].apk
#
# The version is the root VERSION file, which the release workflow stamps from the tag;
# the short commit is what tells two builds of one version apart; and `-debug` is a
# signing statement, not only a build type: it says the APK carries the repository's
# test key, so Android refuses it as an update of a release-signed install. A name
# therefore reads as what the file is, in the order Gradle names a variant:
# `<app>-<version>-<build>[-<signing>]`.
#
# The branch is not in the name. It is in the tag of the release the file is published
# to (`android-rolling-<branch>`), which is where a downloader looks for it, and a
# branch name can carry characters and length a file name should not.
#
# <profile>  `release` for a release-signed build, `debug` for a test-signed one.
# <sha7>     first 7 characters of GITHUB_SHA.
#
# Runs from the root of the tree being built. Bash 3.2 and BSD userland (macOS
# runners) compatible.
set -euo pipefail

usage() {
  echo "usage: $0 [--release] <release|debug> [file]" >&2
  exit 2
}

channel=dev
while :; do
  case "${1:-}" in
    --release) channel=release; shift ;;
    *) break ;;
  esac
done
[ $# -eq 1 ] || [ $# -eq 2 ] || usage
profile=$1
file=${2:-}

case $profile in
  release | debug) ;;
  *) usage ;;
esac
if [ -n "$file" ] && [ ! -f "$file" ]; then
  echo "$0: no build output at $file" >&2
  exit 1
fi

version=$(tr -d '[:space:]' < VERSION)
if ! printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "$0: VERSION is not x.y.z: $version" >&2
  exit 1
fi

# A tag's release names the version and nothing else: it is the archive, one file per
# version. A branch build is one of many of the same version, so it carries the commit.
stem="Brapmony-${version}"
if [ "$channel" != release ]; then
  if [ -z "${GITHUB_SHA:-}" ]; then
    echo "$0: GITHUB_SHA must be set" >&2
    exit 1
  fi
  stem="${stem}-${GITHUB_SHA:0:7}"
fi
[ "$profile" = release ] || stem="${stem}-debug"

if [ -z "$file" ]; then
  printf '%s\n' "$stem"
  exit 0
fi

dest="$(dirname "$file")/$stem.${file##*.}"
[ "$file" -ef "$dest" ] || mv -- "$file" "$dest"

printf '%s\n' "$dest"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  printf 'path=%s\n' "$dest" >>"$GITHUB_OUTPUT"
  printf 'stem=%s\n' "$stem" >>"$GITHUB_OUTPUT"
  # The Actions artifact is named after the APK, so what a run uploads and what a release
  # publishes are called the same thing. A re-run of the same commit would otherwise put
  # its own bytes under the name the first attempt used, so the attempt number is added
  # when there is more than one.
  attempt=${GITHUB_RUN_ATTEMPT:-1}
  case $attempt in
    '' | 1) artifact=$stem ;;
    *) artifact="$stem-attempt-$attempt" ;;
  esac
  printf 'artifact=%s\n' "$artifact" >>"$GITHUB_OUTPUT"
fi
exit 0
