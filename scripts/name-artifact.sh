#!/usr/bin/env bash
# Names a CI build output, so a dev build and a release share one scheme:
#
#   bash scripts/name-artifact.sh [--release] <release|debug> [file]
#
# With [file], renames it in place to <stem>.<ext>, prints the new path and appends
# `path=<new path>` to GITHUB_OUTPUT when that is set. Without it, prints <stem>.
#
#   branch build:  Brapmony-<version>-<sha7>-debug.apk
#   --release:     Brapmony-<version>.apk            (or -debug, test-signed)
#
# The name is the version the APK itself reports, not a number computed a second time
# here: APP_VERSION_NAME is the build's versionName (Gradle's `<VERSION>-<sha7>` for a
# branch build, the plain `<VERSION>` for a tag), and the workflow checks it against the
# finished APK's manifest before this runs. A file name that disagreed with the version
# inside the file would be a second, unchecked claim about what the file is.
#
# `-debug` is a signing statement, not only a build type: it says the APK carries the
# repository's test key, so Android refuses it as an update of a release-signed install. A
# debug variant's version name already ends in `-debug`, so it is not appended twice.
#
# The branch is not in the name. It is in the tag of the release the file is published
# to (`v<version>-<sha7>` for a commit, `v<version>` for a tag), which is where a
# downloader looks for it, and a branch name can carry characters and length a file name
# should not.
#
# <profile>  `release` for a release-signed build, `debug` for a test-signed one.
# APP_VERSION_NAME  the version name to use. Without it, the root VERSION file, with the
#                   first 7 characters of GITHUB_SHA appended unless --release is given.
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

if [ -n "${APP_VERSION_NAME:-}" ]; then
  version=$APP_VERSION_NAME
else
  version=$(tr -d '[:space:]' < VERSION)
  if ! printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "$0: VERSION is not x.y.z: $version" >&2
    exit 1
  fi
  # A tag's release names the version and nothing else: it is the archive, one file per
  # version. A branch build is one of many of the same version, so it carries the commit.
  if [ "$channel" != release ]; then
    if [ -z "${GITHUB_SHA:-}" ]; then
      echo "$0: GITHUB_SHA must be set when APP_VERSION_NAME is not" >&2
      exit 1
    fi
    version="${version}-${GITHUB_SHA:0:7}"
  fi
fi

# `-debug` says how the file is signed. The debug variant's own version name already ends
# in it, so the suffix is added only when the name does not carry it.
stem="Brapmony-${version}"
case $profile in
  release) ;;
  debug)
    case $stem in
      *-debug) ;;
      *) stem="${stem}-debug" ;;
    esac
    ;;
esac

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
