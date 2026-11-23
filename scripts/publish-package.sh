#!/usr/bin/env bash
# Pushes a built APK to GitHub Packages, so the build is a package on the repository as
# well as a file on a release.
#
#   bash scripts/publish-package.sh <apk> <version> <commit> [--version-release]
#
# GitHub Packages has no APK registry: the registries it serves are npm, Maven, Gradle,
# NuGet, RubyGems and container images. So the APK is published to the container
# registry (ghcr.io) as an OCI artifact, which is the container registry's own model for
# "a file, with metadata, that is not an image". Nothing about it is a container image:
# the artifact type and the layer's media type both say it is an Android APK, and pulling
# it back with `oras pull` yields the same bytes that were pushed.
#
#   package:  ghcr.io/<owner>/<repo>        (the repository, lowercased)
#   tags:     latest                        the newest build of the default branch
#             <branch>                      the newest build of that branch
#             <version>                     the APK of a version-tag release
#             <sha7>                        this build, immutably
#
# A tag names the newest build that was published under it, and `latest` is only ever
# moved by a default-branch build: a branch build must not become what a downloader gets by
# asking for `latest`. `<sha7>` is the one tag that never moves, so a script can pin a
# build. (A publish is one push, so the tags in it name one build; two publishes racing
# each other are serialised by the job's concurrency group.)
#
# The package is linked to this repository by the `org.opencontainers.image.source`
# annotation, which is what makes it appear on the repository's page and lets the
# package inherit the repository's access permissions. Without it a package is a
# free-standing thing nobody finds.
#
# Needs the `oras` CLI on PATH (the workflow installs it from a pinned action) and, on a
# runner, GITHUB_REPOSITORY (owner/repo), GITHUB_ACTOR and GITHUB_TOKEN. Locally,
# PACKAGE_REGISTRY can point at another registry and ORAS_USERNAME/ORAS_PASSWORD at its
# credentials, which is how this script is checked against a real registry without
# publishing anything.
set -euo pipefail

usage() {
  echo "usage: $0 <apk> <version> <commit> [--version-release]" >&2
  exit 2
}

[ $# -ge 3 ] || usage
apk=$1
version=$2
commit=$3
shift 3
version_release=false
for arg in "$@"; do
  case $arg in
    --version-release) version_release=true ;;
    *) usage ;;
  esac
done

[ -f "$apk" ] || { echo "$0: no APK at $apk" >&2; exit 1; }
if ! printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "$0: version is not x.y.z: $version" >&2
  exit 1
fi
if ! printf '%s\n' "$commit" | grep -Eq '^[0-9a-f]{7,40}$'; then
  echo "$0: commit is not a git sha: $commit" >&2
  exit 1
fi
# The repository is the package's namespace, so it has to be owner/repo. It is lowercased
# because a container registry's names are.
repo=${GITHUB_REPOSITORY:-}
if ! printf '%s\n' "$repo" | grep -Eq '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$'; then
  echo "$0: GITHUB_REPOSITORY is not owner/repo: ${repo:-<unset>}" >&2
  exit 1
fi
repo=$(printf '%s' "$repo" | tr 'A-Z' 'a-z')

registry=${PACKAGE_REGISTRY:-ghcr.io}
package="$registry/$repo"
sha7=${commit:0:7}
source_url="https://github.com/${GITHUB_REPOSITORY:-$repo}"

# oras, from PATH or from ORAS. `command -v` rather than `which`: `which` is not a
# builtin and is missing on some runners.
oras=${ORAS:-oras}
command -v "$oras" > /dev/null 2>&1 || {
  echo "$0: the oras CLI is not installed (see https://oras.land); it publishes the package" >&2
  exit 1
}

# The APK's own name is what a puller gets back, so it is pushed under the name it is
# published as. `oras push` refuses an absolute path (a puller would be handed a path from
# somebody else's filesystem), so the file is staged under its own name in a scratch
# directory and pushed from there by relative name.
file=$(basename "$apk")
stage=$(mktemp -d)
push_log="$stage/push.log"
trap 'rm -rf "$stage"' EXIT
cp -- "$apk" "$stage/$file"
cd "$stage"

# Every build carries the same annotations, so the package page describes the file rather
# than the runner that happened to push it.
annotations=(
  --annotation "org.opencontainers.image.source=$source_url"
  --annotation "org.opencontainers.image.version=$version"
  --annotation "org.opencontainers.image.revision=$commit"
  --annotation "org.opencontainers.image.title=$file"
  --annotation "org.opencontainers.image.description=Brapmony Android client APK $version ($sha7)"
  --annotation "org.opencontainers.image.licenses=GPL-3.0-or-later"
)

# The tags this build is published under: the immutable commit tag, then the version (a
# version-tag release only), then the branch, then `latest` (the default branch only, so
# a branch build never becomes what asking for `latest` gets).
#
# A tag that is not a valid container tag is not skipped: the ref is slugged the way the
# rolling release tag is, so `feature/x` publishes as `feature-x`, which is a name
# somebody can ask for.
slug() {
  printf '%s' "$1" | sed -e 's/[^A-Za-z0-9._-]\+/-/g' -e 's/^[._-]*//' -e 's/[._-]*$//' | cut -c1-128
}

tags=("$sha7")
if [ "$version_release" = true ]; then
  # A version release is tagged by version. Its ref is the tag (`v1.9.6`), which would be
  # the same build under a second name nobody asked for.
  tags+=("$version")
else
  if [ -n "${GITHUB_REF_NAME:-}" ]; then
    tags+=("$(slug "$GITHUB_REF_NAME")")
  fi
  default_branch=${PACKAGE_DEFAULT_BRANCH:-}
  if [ -n "$default_branch" ] && [ "${GITHUB_REF_NAME:-}" = "$default_branch" ]; then
    tags+=(latest)
  fi
fi
# A tag that came out empty (a ref of nothing but punctuation) is not a tag at all.
kept=()
for tag in "${tags[@]}"; do
  [ -n "$tag" ] && kept+=("$tag")
done
tags=("${kept[@]}")
[ ${#tags[@]} -gt 0 ] || { echo "$0: no tag to publish under" >&2; exit 1; }

# `oras` talks HTTPS unless told otherwise. A registry named in PACKAGE_REGISTRY that is
# not ghcr.io is reached over plain HTTP, which is how this script is checked against a
# registry on this machine: ghcr.io is never reached that way, and neither is anything
# else whose name does not say it is local.
plain=()
case $registry in
  localhost:* | 127.0.0.1:* | '[::1]':*) plain=(--plain-http) ;;
esac

if [ -n "${GITHUB_TOKEN:-}" ]; then
  printf '%s' "$GITHUB_TOKEN" | "$oras" login "$registry" \
    --username "${GITHUB_ACTOR:-github-actions}" --password-stdin "${plain[@]}"
elif [ -n "${ORAS_PASSWORD:-}" ]; then
  printf '%s' "$ORAS_PASSWORD" | "$oras" login "$registry" \
    --username "${ORAS_USERNAME:-user}" --password-stdin "${plain[@]}"
fi

# One push, every tag: oras accepts a comma-separated tag list after the repository, and
# pushing once means the manifest and its blob are uploaded once however many tags name
# them.
#
# The push prints the digest of the manifest it created. That digest is what the package
# *is* -- the tags are names that point at it -- and it is what a provenance attestation of
# the package is made about, so it is written to GITHUB_OUTPUT for the step that attests.
"$oras" push "$package:$(IFS=,; printf '%s' "${tags[*]}")" \
  --artifact-type application/vnd.brapmony.android.apk \
  "${annotations[@]}" \
  "${plain[@]}" \
  --no-tty \
  "$file:application/vnd.android.package-archive" | tee "$push_log"

# `oras` reports the manifest it pushed on a line of its own ("Digest: sha256:..."), and
# that is the only place it is printed. Without it there is nothing to attest, so its
# absence fails the publish rather than silently skipping the attestation.
manifest_digest=$(sed -n 's/^Digest: \(sha256:[0-9a-f]\{64\}\)$/\1/p' "$push_log" | tail -1)
if [ -z "$manifest_digest" ]; then
  echo "$0: oras did not report the digest of the manifest it pushed" >&2
  exit 1
fi
echo "published $file to $package as: ${tags[*]} (manifest $manifest_digest)"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  printf 'digest=%s\n' "$manifest_digest" >>"$GITHUB_OUTPUT"
  printf 'image=%s\n' "$package" >>"$GITHUB_OUTPUT"
  printf 'tags=%s\n' "$(IFS=,; printf '%s' "${tags[*]}")" >>"$GITHUB_OUTPUT"
fi

# Whether the package can actually be fetched is not something to assume. GitHub creates a
# first-published package **private** whatever the repository's visibility is, and no REST
# endpoint changes that; only a package admin, in the package's settings, can. A private
# package still appears under the repository's Packages, so the push succeeding says
# nothing about whether a downloader can pull it. This asks the registry anonymously,
# with a registry config of its own so the credentials the push used are not in it, and
# says which of the two it is. It never fails the run: the package was published, and the
# release is the route that always works.
anonymous=()
case $registry in
  localhost:* | 127.0.0.1:* | '[::1]':*) anonymous=(--plain-http) ;;
esac
anonymous+=(--registry-config "$stage/registry-config.json")
: > "$stage/registry-config.json"
if "$oras" manifest fetch "${anonymous[@]}" "$package:${tags[0]}" > /dev/null 2>&1; then
  visibility=public
  echo "the package is public: it pulls anonymously"
else
  visibility=private
  echo "::warning::The package is private, so pulling it needs a token and a person cannot download it anonymously. GitHub creates a first-published package private; make it public once under https://github.com/$repo/pkgs/container/${repo#*/}/settings (Danger Zone, Change visibility). The release is unaffected."
fi
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "- Pushed the APK to GitHub Packages: [\`$package\`](https://github.com/$repo/pkgs/container/${repo#*/}) (tags ${tags[*]}), **$visibility**."
  } >> "$GITHUB_STEP_SUMMARY"
fi
exit 0
