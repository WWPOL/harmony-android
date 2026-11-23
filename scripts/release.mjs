#!/usr/bin/env node
// Publishes the Android client's APK from CI. Four callers:
//
//   node scripts/release.mjs rolling --apk <file> --branch <ref> --commit <sha> \
//       --default-branch <ref> [--version <x.y.z>] [--keep <n>] [--body <markdown>] \
//       [--dry-run]
//     Publishes a rolling build of one branch: `Brapmony (rolling) <branch>` tagged
//     `android-rolling-<slug>`. Each run adds its APK under its own name and refreshes
//     the stable `Brapmony-latest.apk` (and its `.sha256`) to the same bytes. The build
//     of the repository's default branch is published as a **release**, so it is what the
//     repository's Releases page and /releases/latest offer; any other branch's is a
//     prerelease, which /releases/latest skips. The release is named for the branch, not
//     for a version: it is a window on a stream of builds, and the version of the build
//     that is on it is in its body beside the commit.
//
//   node scripts/release.mjs tag --tag <vX.Y.Z> [--name <title>] [--body <markdown>]
//     Creates the version release for a tag, or finds the one that exists, and prints
//     its id to GITHUB_OUTPUT. Used by release.yml, which then attaches the APK to it.
//
//   node scripts/release.mjs upload --release-id <id> <file>...
//     Attaches files to an existing release, replacing assets of the same name and
//     nothing else.
//
//   node scripts/release.mjs cleanup --default-branch <ref> [--keep-artifacts <n>] [--apply]
//     Lists, and with --apply deletes, what a rolling workflow left behind that nothing
//     will publish to again: the rolling releases and tags of branches that no longer
//     exist, and Actions artifacts beyond the newest --keep-artifacts. It never touches a
//     version release, a version tag, or the rolling release of a branch that exists.
//     Run by cleanup.yml on a manual dispatch, because deleting a release and a tag is
//     not something a push should do by accident.
//
// What this refuses to do, because a published download link is somebody's:
//
//   * touch a release whose tag is not a rolling tag it computed itself;
//   * publish into a release at that tag that carries none of its own assets (somebody
//     else's release, or an empty one), rather than rewriting what it says;
//   * overwrite an asset this workflow did not upload (ownership is read from the asset's
//     uploader, and only this workflow's own files are ever replaced or pruned);
//   * replace a build asset whose bytes differ, which would silently change what an
//     existing name means (the new bytes go under a distinct name instead);
//   * delete the stable `latest` name before the replacement has been uploaded;
//   * prune when the newest build did not upload;
//   * delete a version release, a version tag, or the rolling release of a live branch.
//
// Retention: a rolling release keeps the newest DEFAULT_KEEP builds of its branch and
// deletes older ones it uploaded itself. The release is a rolling window, not an
// archive: a link to a build older than that window stops resolving. The versioned
// release of a tag is the archive, and nothing here touches it.
//
// Environment: GITHUB_TOKEN, GITHUB_REPOSITORY, GITHUB_API_URL, GITHUB_OUTPUT.
import { appendFileSync, copyFileSync, existsSync, mkdtempSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

// The stable name to bookmark: replaced on every publish, so it always names the newest
// build of the branch. The build's own name is versioned and never changes.
export const LATEST_ASSET = 'Brapmony-latest.apk';
export const LATEST_SHA256_ASSET = 'Brapmony-latest.apk.sha256';
// Names an earlier version of this workflow published under. They are retired on the
// first publish that runs here, so a download does not sit under a name the
// documentation no longer mentions, holding bytes nothing updates. Only assets this
// workflow owns are ever removed.
export const RETIRED_ASSETS = ['Brapmony_Android_latest.apk', 'Brapmony_Android_latest.apk.sha256'];
// The prefix every rolling release tag carries. A version release's tag is `vX.Y.Z`,
// so a lookup can never match one.
export const ROLLING_TAG_PREFIX = 'android-rolling-';
// How many of a branch's own builds a rolling release keeps.
export const DEFAULT_KEEP = 10;
// How many Actions artifacts the cleanup keeps: enough to read back what the last few runs
// built, not the whole history.
export const DEFAULT_ARTIFACT_KEEP = 10;

// The account GitHub attributes a workflow's uploads to. An asset uploaded by a workflow
// carries this login, and one somebody attached to the release by hand carries theirs, so
// it is how a file of a workflow's is told from a file of somebody else's. It is not on its
// own proof that this workflow uploaded it -- another workflow in this repository uploads
// with the same account -- so a name this workflow generates has to go with it.
export const BOT_LOGIN = 'github-actions[bot]';
// Releases published before this changed wrote an ownership label into every asset. GitHub
// shows an asset's label **in place of its file name**, so those releases list
// `brapmony-android sha256:…` where the download names should be: nothing writes a label
// now, and the assets that already carry one are still read as this workflow's so they can
// be replaced and pruned rather than left behind.
export const LEGACY_OWNER = 'brapmony-android';
// The asset names this workflow publishes: `Brapmony-<version>...` today, and
// `Brapmony_Android_<version>...` from before the name scheme was flattened. Naming is how
// these are told apart on a release whose assets predate the ownership label, and it is what
// keeps a file another workflow uploaded from being taken for this one's.
export function ownName(name) {
  return /^Brapmony[_-]/.test(name);
}
export function isOwned(asset) {
  if (typeof asset?.label === 'string' && asset.label.startsWith(`${LEGACY_OWNER} `)) return true;
  return asset?.uploader?.login === BOT_LOGIN && ownName(asset?.name);
}

// Whether the bytes GitHub already holds are the file in hand. GitHub computes a SHA-256
// over every asset it stores and reports it as `digest`, so that is the comparison; the
// byte count is the fallback for an API that does not report a digest (GitHub Enterprise
// before it was added). Both are GitHub's, not this script's claim about itself.
export function storedBytes(asset, sha256, size) {
  const digest = typeof asset?.digest === 'string' ? asset.digest.trim().toLowerCase() : '';
  if (digest) return digest === `sha256:${String(sha256).toLowerCase()}`;
  return Number.isFinite(size) && asset?.size === size;
}

// The asset names this workflow publishes: `Brapmony-<version>...` today, and
// `Brapmony_Android_<version>...` from before the name scheme was flattened. Naming is
// how these are told apart on a release whose assets predate the ownership label.
export function ownsAsset(asset) {
  return isOwned(asset) || ownName(asset.name);
}

export class PublishError extends Error {}

// A git ref is not a valid tag on its own: `feature/x` would make the tag look like a
// path, and a branch named `v1.0.0` would collide with a version release. The prefix
// is what keeps the two apart. The tag is the
// branch with every run of characters outside [A-Za-z0-9._-] replaced by `-`.
export function rollingTag(branch) {
  const name = String(branch || '').trim();
  if (!name) throw new PublishError('--branch is required');
  const slug = name.replace(/[^A-Za-z0-9._-]+/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '');
  if (!slug) throw new PublishError(`branch ${name} has no characters a tag can carry`);
  return `${ROLLING_TAG_PREFIX}${slug.slice(0, 80)}`;
}

export function rollingName(branch) {
  return `Brapmony (rolling) ${String(branch || '').trim()}`;
}

// The name a rolling release carries. It is named for the branch, not for a version: a
// rolling release is a window on a branch, refreshed by every push, so a version in its
// title would be one stale number standing for a stream of different builds -- which is
// what `Brapmony 1.9.6` was, for every commit, because the version line never changed.
// The version of the build that is actually on it is in its body, beside the commit.
// A version release is the one named by a version, and its tag is `vX.Y.Z`.
export function releaseTitle(branch) {
  return rollingName(branch);
}

// Whether a rolling release is a published release or a prerelease. The default branch's
// build is what the repository offers as its release: it is what /releases/latest and the
// Releases box on the repository page point at, and a prerelease is skipped by both, so a
// prerelease there means no release is offered at all. Every other branch stays a
// prerelease -- a branch build is not an offer to anybody.
export function isPublishedRelease(branch, defaultBranch) {
  const b = String(branch || '').trim();
  const d = String(defaultBranch || '').trim();
  if (!b || !d) throw new PublishError('--default-branch is required to decide whether a build is a release');
  return b === d;
}

export function sha256File(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

// GitHub computes a SHA-256 digest of every asset it stores and reports it as
// `sha256:<hex>`. It is the only digest of the stored file this workflow did not compute
// itself, so it is the only independent check that what a downloader will get is the bytes
// that were uploaded: a proxy, a truncated upload or a wrong file shows up here and not in
// the checksum written beside the file.
//
// An API that reports no digest (GitHub Enterprise before it was added, or a stand-in that
// does not model it) leaves nothing to compare, and the upload is taken as it came back.
// A digest that differs is always an error, and is raised before the caller does anything
// else with the asset.
export function checkAssetDigest(asset, sha256, { name = asset?.name } = {}) {
  const reported = typeof asset?.digest === 'string' ? asset.digest.trim().toLowerCase() : '';
  if (!reported) return { checked: false, digest: null };
  const expected = `sha256:${String(sha256).toLowerCase()}`;
  if (reported !== expected) {
    throw new PublishError(
      `GitHub stored ${name} as ${reported}, but the file hashes to ${expected}; refusing to publish it`,
    );
  }
  return { checked: true, digest: reported };
}

export function client({
  token = process.env.GITHUB_TOKEN,
  repository = process.env.GITHUB_REPOSITORY,
  apiUrl = process.env.GITHUB_API_URL || 'https://api.github.com',
} = {}) {
  if (!token) throw new PublishError('GITHUB_TOKEN is not set');
  if (!/^[^/]+\/[^/]+$/.test(repository || '')) {
    throw new PublishError(`GITHUB_REPOSITORY is not owner/repo: ${repository}`);
  }
  const base = apiUrl.replace(/\/$/, '');
  const repoUrl = `${base}/repos/${repository}`;
  const headers = {
    authorization: `Bearer ${token}`,
    'x-github-api-version': '2022-11-28',
    'user-agent': LEGACY_OWNER,
  };

  async function request(method, url, { body, accept = 'application/vnd.github+json', contentType } = {}) {
    return fetch(url.startsWith('http') ? url : `${repoUrl}${url}`, {
      method,
      headers: { ...headers, accept, ...(contentType ? { 'content-type': contentType } : {}) },
      body,
    });
  }

  async function json(method, url, opts = {}) {
    const res = await request(method, url, opts);
    if (!res.ok) {
      const error = new PublishError(`${method} ${url}: ${res.status} ${await res.text()}`);
      error.status = res.status;
      throw error;
    }
    return res.status === 204 ? null : res.json();
  }

  // Every page of a list endpoint, concatenated. `unwrap` names the property a page
  // carries its items under, for the endpoints that answer with an object rather than an
  // array -- `/actions/artifacts` is `{ total_count, artifacts: [...] }`, while
  // `/releases` is the array itself.
  async function paginate(url, { unwrap } = {}) {
    const out = [];
    let next = `${repoUrl}${url}${url.includes('?') ? '&' : '?'}per_page=100`;
    while (next) {
      const res = await request('GET', next);
      if (!res.ok) throw new PublishError(`GET ${next}: ${res.status} ${await res.text()}`);
      const page = await res.json();
      out.push(...(unwrap ? (page?.[unwrap] ?? []) : page));
      next = /<([^>]+)>;\s*rel="next"/.exec(res.headers.get('link') || '')?.[1];
    }
    return out;
  }

  return { request, json, paginate };
}

// Drafts are not addressable by tag and the list is paged, so the list is read through
// paginate. A release is matched by exact tag only, and only ever a rolling tag.
async function findByTag(gh, tag) {
  if (!tag.startsWith(ROLLING_TAG_PREFIX)) {
    throw new PublishError(`refusing to look up ${tag}: not a rolling tag`);
  }
  return (await gh.paginate('/releases')).find((r) => r.tag_name === tag) || null;
}

// What a rolling release of [branch] should say and be: a published release for the
// default branch, a prerelease for any other. [version] is accepted and unused by the
// title: the version of the build on it belongs in the body, where it is the build's,
// rather than in the title, where it would be one number for many builds.
export function rollingState({ branch, defaultBranch, version, body }) {
  const published = isPublishedRelease(branch, defaultBranch);
  return {
    name: releaseTitle(branch, version),
    body: body ?? '',
    draft: false,
    prerelease: !published,
  };
}

// Finds the rolling release for [branch], or creates it. An existing release at that tag
// is only ever this workflow's: one that carries no build of ours is somebody else's, and
// is refused rather than rewritten. Changing the name, body and published state is a
// separate call (updateRollingRelease) made after this run's build has uploaded, so a
// failed publish cannot leave the repository's release saying something it is not.
export async function ensureRollingRelease(gh, { branch, commit, defaultBranch, version, body }) {
  const tag = rollingTag(branch);
  const wanted = rollingState({ branch, defaultBranch, version, body });
  const existing = await findByTag(gh, tag);
  if (existing) {
    const assets = await listAssets(gh, existing.id);
    if (!assets.some(ownsAsset)) {
      throw new PublishError(
        `release ${tag} carries no build of this workflow's; refusing to publish into it`,
      );
    }
    return { id: existing.id, tag, created: false, wanted, current: existing };
  }
  const created = await gh.json('POST', '/releases', {
    contentType: 'application/json',
    body: JSON.stringify({
      tag_name: tag,
      ...wanted,
      ...(commit ? { target_commitish: commit } : {}),
    }),
  });
  return { id: created.id, tag, created: true, wanted, current: null };
}

// Brings an existing rolling release in line with this build: the title and body describe
// the newest build, and a release of the default branch is published while another
// branch's is a prerelease. Only what differs is written, so a re-run of the same commit
// changes nothing.
export async function updateRollingRelease(gh, { id, tag, wanted, current }) {
  if (!current) return { updated: [], prerelease: wanted.prerelease };
  const patch = {};
  if (current.prerelease !== wanted.prerelease) patch.prerelease = wanted.prerelease;
  if (current.name !== wanted.name) patch.name = wanted.name;
  if (wanted.body && current.body !== wanted.body) patch.body = wanted.body;
  const updated = Object.keys(patch);
  if (!updated.length) return { updated, prerelease: wanted.prerelease };
  await gh.json('PATCH', `/releases/${id}`, {
    contentType: 'application/json',
    body: JSON.stringify(patch),
  });
  return { updated, prerelease: wanted.prerelease };
}

// A version release is addressed by its exact tag, and only ever a `vX.Y.Z` one: the
// rolling tags are a different namespace, and this must never find one of those. An
// existing release is returned as it is -- published or draft -- so a re-run of a tag
// attaches to the same release instead of creating a second one.
export async function ensureTagRelease(gh, { tag, name, body }) {
  const version = String(tag || '').replace(/^v/, '');
  if (!/^\d+\.\d+\.\d+$/.test(version)) {
    throw new PublishError(`--tag must be vX.Y.Z, not ${tag}`);
  }
  const existing = (await gh.paginate('/releases')).find((r) => r.tag_name === tag) || null;
  if (existing) {
    if (existing.tag_name.startsWith(ROLLING_TAG_PREFIX)) {
      throw new PublishError(`${tag} is a rolling tag; refusing to treat it as a version release`);
    }
    return { id: existing.id, tag, created: false, draft: Boolean(existing.draft) };
  }
  const created = await gh.json('POST', '/releases', {
    contentType: 'application/json',
    body: JSON.stringify({
      tag_name: tag,
      name: name || `Brapmony ${version}`,
      body: body ?? '',
      draft: false,
      prerelease: false,
    }),
  });
  return { id: created.id, tag, created: true, draft: Boolean(created.draft) };
}

async function listAssets(gh, releaseId) {
  return gh.paginate(`/releases/${releaseId}/assets`);
}

// GitHub's upload endpoint replaces nothing: a second upload of a name that is already
// there is a 422.
//
// No label is sent. GitHub shows an asset's label **in place of its file name** on the
// release page, so a label here made the release list `brapmony-android sha256:…` where
// `Brapmony-latest.apk` should be, and a person looking for the APK found no file names at
// all. The label was only ever this workflow's note to itself about which bytes an asset
// held; GitHub's own `digest` says that, and says it without hiding the name.
//
// What GitHub stored is then compared with what was sent (checkAssetDigest): the digest in
// the response is computed by GitHub over the bytes it holds, so a mismatch means the file
// a downloader would get is not the file this run built.
async function uploadFile(gh, { releaseId, file, name }) {
  if (!existsSync(file)) throw new PublishError(`no such file: ${file}`);
  const release = await gh.json('GET', `/releases/${releaseId}`);
  const base = release.upload_url.replace(/\{.*\}$/, '');
  const query = new URLSearchParams({ name });
  let asset;
  try {
    asset = await gh.json('POST', `${base}?${query}`, {
      body: readFileSync(file),
      contentType: 'application/octet-stream',
    });
  } catch (error) {
    if (error.status === 422) {
      throw new PublishError(`asset ${name} is already on release ${releaseId}`);
    }
    throw error;
  }
  checkAssetDigest(asset, sha256File(file), { name });
  return asset;
}

// Adds this run's build under its own name. A re-run of the same commit with the same
// bytes is a no-op: the asset a link already points at keeps meaning the same thing. A
// re-run with different bytes uploads under a distinct name rather than changing what the
// existing name resolves to.
export async function publishBuild(gh, { releaseId, file, name, attempt = 1 }) {
  const sha256 = sha256File(file);
  const size = statSync(file).size;
  const existing = (await listAssets(gh, releaseId)).filter((a) => a.name === name);
  if (existing.length) {
    const same = existing.find((a) => storedBytes(a, sha256, size));
    if (same) {
      // Already these bytes, by GitHub's own digest of what it stores: nothing to upload.
      return { asset: same, uploaded: false, sha256, name };
    }
    if (!existing.every(isOwned)) {
      throw new PublishError(`asset ${name} was not uploaded by this workflow; refusing to replace it`);
    }
    // Same commit, different bytes: a rebuild. It gets its own name, so the earlier
    // build's link keeps resolving to the earlier bytes.
    const suffix = `.r${attempt}`;
    const renamed = name.replace(/\.apk$/, `${suffix}.apk`);
    const asset = await uploadFile(gh, { releaseId, file, name: renamed });
    return { asset, uploaded: true, sha256, name: renamed };
  }
  const asset = await uploadFile(gh, { releaseId, file, name });
  return { asset, uploaded: true, sha256, name };
}

// Moves the stable name onto [file] without ever leaving the name absent: the new bytes
// are uploaded under a staging name first, then the names are swapped by rename, and only
// then is the previous file deleted. If anything fails before the swap, the previous
// `latest` is still there and still downloadable.
export async function replaceStable(gh, { releaseId, file, name }) {
  const assets = await listAssets(gh, releaseId);
  const current = assets.filter((a) => a.name === name);
  // Already these bytes: leave the asset alone, so a re-run changes nothing and the
  // id a download link resolves through stays the same. What is compared is GitHub's own
  // digest of the stored asset against the local file, so it holds for the checksum file
  // as well as for the APK.
  if (current.length === 1 && storedBytes(current[0], sha256File(file), statSync(file).size)) {
    return { asset: current[0], replaced: 0 };
  }
  for (const a of current) {
    if (!isOwned(a)) {
      throw new PublishError(`asset ${name} was not uploaded by this workflow; refusing to replace it`);
    }
  }
  const staging = `${name}.staging-${process.pid}`;
  const previous = `${name}.previous-${process.pid}`;
  const uploaded = await uploadFile(gh, { releaseId, file, name: staging });
  try {
    const moved = [];
    for (const a of current) {
      moved.push(a.id);
      await gh.json('PATCH', `/releases/assets/${a.id}`, {
        contentType: 'application/json',
        body: JSON.stringify({ name: `${previous}-${a.id}` }),
      });
    }
    await gh.json('PATCH', `/releases/assets/${uploaded.id}`, {
      contentType: 'application/json',
      body: JSON.stringify({ name }),
    });
    // Only now, with the new bytes in place, is the old one removed.
    for (const id of moved) await gh.json('DELETE', `/releases/assets/${id}`);
    return { asset: { ...uploaded, name }, replaced: moved.length };
  } catch (error) {
    // Put the previous file back under the stable name if the swap did not finish.
    for (const a of current) {
      try {
        await gh.json('PATCH', `/releases/assets/${a.id}`, {
          contentType: 'application/json',
          body: JSON.stringify({ name }),
        });
        break;
      } catch {
        /* try the next one; the rest of the error is what matters */
      }
    }
    try {
      await gh.json('DELETE', `/releases/assets/${uploaded.id}`);
    } catch {
      /* the staging asset is harmless and pruned next run */
    }
    throw error;
  }
}

// Clears the ownership label off assets an earlier version of this workflow published.
// GitHub shows a label in place of the file name, so those assets are listed as
// `brapmony-android sha256:…` and a person looking for `Brapmony-latest.apk` finds no such
// name on the page. The label is no longer needed -- ownership is read from the uploader --
// so it is removed, and the read-back says whether it went: an API that ignores an empty
// label leaves the name hidden, and the run says so instead of reporting a page that is
// still unreadable.
export async function clearLegacyLabels(gh, { releaseId, log = () => {} }) {
  const cleared = [];
  const stubborn = [];
  for (const asset of await listAssets(gh, releaseId)) {
    if (typeof asset.label !== 'string' || !asset.label.startsWith(`${LEGACY_OWNER} `)) continue;
    if (!isOwned(asset)) continue;
    await gh.json('PATCH', `/releases/assets/${asset.id}`, {
      contentType: 'application/json',
      body: JSON.stringify({ label: '' }),
    });
    cleared.push(asset.name);
  }
  if (!cleared.length) return { cleared, stubborn };
  // Read back what the page will show, rather than trusting the request to have worked.
  for (const asset of await listAssets(gh, releaseId)) {
    if (cleared.includes(asset.name) && asset.label) stubborn.push(asset.name);
  }
  if (stubborn.length) {
    log(`::warning::the label on ${stubborn.join(', ')} did not clear, so those assets still show a hash where their name should be. They are pruned like any other build.`);
  }
  return { cleared, stubborn };
}

// Deletes assets this workflow published under a name it no longer uses, so a download
// does not stay under a name nothing updates. Only this workflow's own assets are ever
// removed: a file somebody attached by hand is left exactly where it is.
export async function retireAssets(gh, { releaseId, names = RETIRED_ASSETS }) {
  const retired = [];
  for (const asset of await listAssets(gh, releaseId)) {
    if (!names.includes(asset.name)) continue;
    if (!isOwned(asset)) continue;
    await gh.json('DELETE', `/releases/assets/${asset.id}`);
    retired.push(asset.name);
  }
  return retired;
}

// Deletes the oldest of a rolling release's own builds, keeping [keep] newest. Only
// assets this workflow uploaded under a versioned name are candidates: a file somebody
// attached by hand, the stable names, and the checksum are all left alone, and so is
// every other release.
export async function pruneRollingAssets(gh, { releaseId, keep = DEFAULT_KEEP, protect = [] }) {
  if (!(keep > 0)) throw new PublishError(`--keep must be positive: ${keep}`);
  const safe = new Set([LATEST_ASSET, LATEST_SHA256_ASSET, ...RETIRED_ASSETS, ...protect]);
  const assets = await listAssets(gh, releaseId);
  const builds = assets.filter(
    (a) => isOwned(a) && /\.apk$/.test(a.name) && !safe.has(a.name) && !a.name.includes('.staging-'),
  );
  if (builds.length <= keep) return [];
  // Newest first by id: GitHub assigns a higher id to each later upload, and an asset
  // carries no timestamp of its own.
  const oldest = builds.sort((a, b) => b.id - a.id).slice(keep);
  const pruned = [];
  for (const a of oldest) {
    await gh.json('DELETE', `/releases/assets/${a.id}`);
    pruned.push(a.name);
  }
  return pruned;
}

// A rolling tag names the newest build of a branch, so it moves with every publish. The
// release page shows the commit its tag points at, and a tag left where the release was
// first created would show the first build's commit beside assets from the newest one --
// which is exactly what a person checks to know what they are downloading.
//
// Only a rolling tag is ever moved: a version tag is a fixed point, and this refuses to
// touch one. The move is forced, because the old value is the one being replaced.
export async function moveRollingTag(gh, { tag, commit }) {
  if (!tag.startsWith(ROLLING_TAG_PREFIX)) {
    throw new PublishError(`refusing to move ${tag}: not a rolling tag`);
  }
  if (!commit) throw new PublishError('a commit is required to move a rolling tag');
  let current = null;
  try {
    current = await gh.json('GET', `/git/ref/tags/${tag}`);
  } catch (error) {
    // A release created from a branch rather than a tag has no tag ref yet; this makes one.
    if (error.status !== 404) throw error;
  }
  if (current && current.object && current.object.sha === commit) return { moved: false, sha: commit };
  if (current) {
    await gh.json('PATCH', `/git/refs/tags/${tag}`, {
      contentType: 'application/json',
      body: JSON.stringify({ sha: commit, force: true }),
    });
    return { moved: true, sha: commit };
  }
  await gh.json('POST', '/git/refs', {
    contentType: 'application/json',
    body: JSON.stringify({ ref: `refs/tags/${tag}`, sha: commit }),
  });
  return { moved: true, created: true, sha: commit };
}

function required(values, keys) {
  for (const k of keys) if (!values[k]) throw new PublishError(`--${k} is required`);
}

// Every release and tag a rolling workflow left behind that no branch will publish to
// again, and every Actions artifact older than the ones worth keeping.
//
// This exists because the alternatives are worse. A rolling tag is created by the first
// publish of a branch and moved by every one after it; when the branch is deleted or
// renamed, nothing moves the tag again, so it sits in the repository's tag list pointing
// at a commit that no release on it describes any more. The same happens to the release.
// Nothing in the workflow can know a branch is gone -- it only ever runs for the branch it
// is publishing -- so this is a separate, explicit sweep rather than something a push
// does: deleting a release and a tag is not a thing a build should do by accident.
//
// What it will never do, because a published download is somebody's:
//
//   * touch a release or a tag that is not a rolling one (`android-rolling-<branch>`),
//     so a version release and its `vX.Y.Z` tag are never candidates;
//   * touch the rolling release of a branch that still exists, or of [defaultBranch];
//   * delete a tag whose commit the release's assets do not name (see below);
//   * delete anything at all without --apply.
//
// The one judgement it has to make is whether a rolling release is stale. It asks the
// repository which branches exist: a rolling tag for a branch that is not there is stale.
// That is exact, and it needs no timestamp: `gh` reports a deleted branch as absent, and a
// release whose tag names no branch is one nothing will ever update again.
// Whether a rolling tag names a branch that still exists. A slug is lossy -- `feature/x`
// and `feature-x` both become `feature-x` -- so the tag is compared with the slugs of the
// branches that exist rather than parsed back into a branch name, which would guess wrong
// for one of those two.
export function tagNamesLiveBranch(tag, branches) {
  if (!String(tag).startsWith(ROLLING_TAG_PREFIX)) return false;
  const slug = String(tag).slice(ROLLING_TAG_PREFIX.length);
  return [...branches].some((branch) => {
    try {
      return rollingTag(branch).slice(ROLLING_TAG_PREFIX.length) === slug;
    } catch {
      return false;
    }
  });
}

export async function staleRollingReleases(gh, { defaultBranch, branches }) {
  if (!defaultBranch) throw new PublishError('--default-branch is required');
  const live = [defaultBranch, ...branches];
  const releases = await gh.paginate('/releases');
  const stale = [];
  for (const release of releases) {
    const tag = String(release.tag_name || '');
    // A version release's tag is `vX.Y.Z` and never carries the rolling prefix.
    if (!tag.startsWith(ROLLING_TAG_PREFIX)) continue;
    if (tagNamesLiveBranch(tag, live)) continue;
    stale.push({ id: release.id, tag, name: release.name, draft: Boolean(release.draft), assets: (release.assets || []).length });
  }
  return stale;
}

// Rolling tags with no release at all. A release deleted by hand leaves its tag behind,
// and a tag is a page on the repository as much as a release is: it still points at a
// commit, and it still reads as a build of a branch that no longer exists. They are found
// from the tag list rather than from the release list, because there is no release to
// find them from.
export async function orphanRollingTags(gh, { defaultBranch, branches }) {
  if (!defaultBranch) throw new PublishError('--default-branch is required');
  const live = [defaultBranch, ...branches];
  const tags = await gh.paginate('/tags');
  const released = new Set((await gh.paginate('/releases')).map((r) => r.tag_name));
  return tags
    .map((t) => t.name)
    .filter((tag) => String(tag).startsWith(ROLLING_TAG_PREFIX))
    .filter((tag) => !released.has(tag) && !tagNamesLiveBranch(tag, live));
}

// Deletes a rolling tag that has no release. The same refusal as the release path: only a
// rolling tag, never a version tag.
export async function deleteTag(gh, { tag, log = () => {} }) {
  if (!String(tag).startsWith(ROLLING_TAG_PREFIX)) {
    throw new PublishError(`refusing to delete ${tag}: not a rolling tag`);
  }
  try {
    await gh.json('DELETE', `/git/refs/tags/${tag}`);
  } catch (error) {
    if (error.status !== 404) throw error;
  }
  log(`Deleted tag ${tag}`);
  return { tag };
}

// Deletes a stale rolling release and the tag that named it. Only the assets this workflow
// uploaded go with it -- GitHub deletes a release's assets with the release -- and the tag
// is deleted only after the release is gone, so a failure in between leaves a release
// whose tag still exists rather than a tag pointing at nothing.
export async function deleteStaleRelease(gh, { id, tag, log = () => {} }) {
  if (!String(tag).startsWith(ROLLING_TAG_PREFIX)) {
    throw new PublishError(`refusing to delete ${tag}: not a rolling tag`);
  }
  await gh.json('DELETE', `/releases/${id}`);
  log(`Deleted release ${tag} (${id})`);
  // A tag with no release is still a tag on the repository, so it is removed too. A 404
  // means it was never created or is already gone, which is the state wanted.
  try {
    await gh.json('DELETE', `/git/refs/tags/${tag}`);
    log(`Deleted tag ${tag}`);
  } catch (error) {
    if (error.status !== 404) throw error;
  }
  return { id, tag };
}

// Actions artifacts older than the newest [keep] of them. An artifact is the record of one
// run, not a download route: it expires, and the release is what outlives a run. Keeping a
// few is useful for reading back what a recent run built; keeping every one of them for 90
// days is storage bought for nothing.
export async function oldArtifacts(gh, { keep = DEFAULT_ARTIFACT_KEEP } = {}) {
  if (!(keep >= 0)) throw new PublishError(`--keep-artifacts must not be negative: ${keep}`);
  // Unlike `/releases`, this endpoint answers with an object holding the page
  // (`{ total_count, artifacts: [...] }`) rather than the page itself.
  const artifacts = await gh.paginate('/actions/artifacts', { unwrap: 'artifacts' });
  // Newest first by id: GitHub assigns a higher id to each later artifact, and the list is
  // not ordered by anything this can rely on.
  const sorted = artifacts.slice().sort((a, b) => b.id - a.id);
  return sorted.slice(keep).map((a) => ({ id: a.id, name: a.name, size: a.size_in_bytes }));
}

export async function deleteArtifact(gh, { id, name, log = () => {} }) {
  await gh.json('DELETE', `/actions/artifacts/${id}`);
  log(`Deleted artifact ${name} (${id})`);
  return { id, name };
}

export async function main(argv, { gh: ghOverride, log = console.log } = {}) {
  const [command, ...rest] = argv;
  const { values, positionals } = parseArgs({
    args: rest,
    allowPositionals: command === 'upload',
    options: {
      apk: { type: 'string' },
      branch: { type: 'string' },
      commit: { type: 'string' },
      'default-branch': { type: 'string' },
      version: { type: 'string' },
      body: { type: 'string' },
      keep: { type: 'string' },
      attempt: { type: 'string' },
      'release-id': { type: 'string' },
      tag: { type: 'string' },
      name: { type: 'string' },
      'keep-artifacts': { type: 'string' },
      apply: { type: 'boolean', default: false },
      'dry-run': { type: 'boolean', default: false },
    },
  });

  if (command === 'rolling') {
    required(values, ['apk', 'branch', 'default-branch']);
    const tag = rollingTag(values.branch);
    const published = isPublishedRelease(values.branch, values['default-branch']);
    const kind = published ? 'release' : 'prerelease';
    if (values['dry-run']) {
      log(`Would publish ${basename(values.apk)} to ${kind} ${tag}`);
      return { tag, dryRun: true, prerelease: !published };
    }
    const gh = ghOverride ?? client();
    const { id, created, wanted, current } = await ensureRollingRelease(gh, {
      branch: values.branch,
      commit: values.commit,
      defaultBranch: values['default-branch'],
      version: values.version,
      body: values.body ?? '',
    });
    log(`${created ? 'Created' : 'Found'} rolling ${published ? 'release' : 'prerelease'} ${tag} (${id})`);
    const attempt = Number(values.attempt ?? 1) || 1;
    const build = await publishBuild(gh, { releaseId: id, file: values.apk, name: basename(values.apk), attempt });
    log(`${build.uploaded ? 'Uploaded' : 'Kept'} ${build.name} on ${tag} (sha256 ${build.sha256})`);
    // Only now that this build is on the release is what the release says brought up to
    // date: a publish that fails before this point leaves the page describing the build
    // that is actually there.
    const { updated } = await updateRollingRelease(gh, { id, tag, wanted, current });
    if (updated.length) log(`Updated ${tag}: ${updated.join(', ')}`);
    // The stable name is only touched once the run's own asset is safely there, so a
    // failed build upload cannot disturb the build people are already downloading.
    const sha256 = build.sha256;
    await replaceStable(gh, { releaseId: id, file: values.apk, name: LATEST_ASSET });
    log(`Updated ${LATEST_ASSET} on ${tag}`);
    const checksum = stableText(`${sha256}  ${LATEST_ASSET}\n`, LATEST_SHA256_ASSET);
    await replaceStable(gh, { releaseId: id, file: checksum, name: LATEST_SHA256_ASSET });
    log(`Updated ${LATEST_SHA256_ASSET} on ${tag}`);
    // The tag is moved onto this build once everything it names is in place, so the page
    // shows the commit these assets came from rather than the one the release was created
    // at. A publish that failed before this point leaves the previous commit's tag alone.
    let moved = null;
    if (values.commit) {
      const tagMove = await moveRollingTag(gh, { tag, commit: values.commit });
      moved = tagMove.moved ? values.commit : null;
      log(`${tagMove.moved ? 'Moved' : 'Kept'} tag ${tag} at ${values.commit}`);
    }
    const retired = await retireAssets(gh, { releaseId: id });
    if (retired.length) log(`Retired ${retired.join(', ')} from ${tag}`);
    const { cleared } = await clearLegacyLabels(gh, { releaseId: id, log });
    if (cleared.length) log(`Cleared the old label from ${cleared.join(', ')}, so their names show again`);
    const pruned = await pruneRollingAssets(gh, {
      releaseId: id,
      keep: Number(values.keep ?? DEFAULT_KEEP),
      protect: [build.name],
    });
    if (pruned.length) log(`Pruned ${pruned.length} older build(s) from ${tag}: ${pruned.join(', ')}`);
    if (process.env.GITHUB_OUTPUT) {
      appendFileSync(process.env.GITHUB_OUTPUT, `tag=${tag}\nrelease-id=${id}\nsha256=${sha256}\n`);
    }
    return { id, tag, uploaded: build.uploaded ? build.name : null, sha256, pruned, retired, cleared, moved, prerelease: !published };
  }

  if (command === 'tag') {
    required(values, ['tag']);
    if (values['dry-run']) {
      log(`Would create or find the release for ${values.tag}`);
      return { tag: values.tag, dryRun: true };
    }
    const gh = ghOverride ?? client();
    const { id, created } = await ensureTagRelease(gh, {
      tag: values.tag,
      name: values.name,
      body: values.body,
    });
    log(`${created ? 'Created' : 'Found'} release ${values.tag} (${id})`);
    if (process.env.GITHUB_OUTPUT) {
      appendFileSync(process.env.GITHUB_OUTPUT, `id=${id}\ntag=${values.tag}\n`);
    }
    return { id, tag: values.tag, created };
  }

  if (command === 'upload') {
    required(values, ['release-id']);
    if (!positionals.length) throw new PublishError('upload needs at least one file');
    const gh = ghOverride ?? client();
    const releaseId = values['release-id'];
    const assets = await listAssets(gh, releaseId);
    const uploaded = [];
    for (const file of positionals) {
      const name = basename(file);
      const same = assets.filter((a) => a.name === name);
      for (const a of same) {
        // A tag's release can carry this name from an earlier run of this workflow, which
        // is still its own file; anything else is not.
        if (!isOwned(a) && !ownName(a.name)) {
          throw new PublishError(`asset ${name} was not uploaded by this workflow; refusing to replace it`);
        }
        await gh.json('DELETE', `/releases/assets/${a.id}`);
      }
      uploaded.push(await uploadFile(gh, { releaseId, file, name }));
    }
    for (const a of uploaded) log(`Uploaded ${a.name} to release ${releaseId}`);
    return uploaded;
  }

  if (command === 'cleanup') {
    required(values, ['default-branch']);
    const gh = ghOverride ?? client();
    const apply = values['apply'] === true;
    const branches = (await gh.paginate('/branches')).map((b) => b.name);
    const defaultBranch = values['default-branch'];
    const stale = await staleRollingReleases(gh, { defaultBranch, branches });
    const orphans = await orphanRollingTags(gh, { defaultBranch, branches });
    const keepArtifacts = Number(values['keep-artifacts'] ?? DEFAULT_ARTIFACT_KEEP);
    const artifacts = await oldArtifacts(gh, { keep: keepArtifacts });
    const freed = artifacts.reduce((sum, a) => sum + (a.size || 0), 0);
    log(`Branches: ${branches.length ? branches.join(', ') : '(none)'}`);
    log(`Stale rolling releases: ${stale.length ? stale.map((r) => r.tag).join(', ') : '(none)'}`);
    log(`Rolling tags with no release: ${orphans.length ? orphans.join(', ') : '(none)'}`);
    log(`Artifacts beyond the newest ${keepArtifacts}: ${artifacts.length ? `${artifacts.map((a) => a.name).join(', ')} (${(freed / 1e6).toFixed(1)} MB)` : '(none)'}`);
    if (!apply) {
      log('Nothing deleted: pass --apply to delete what is listed above.');
      return { stale, orphans, artifacts, applied: false };
    }
    for (const release of stale) await deleteStaleRelease(gh, { ...release, log });
    for (const tag of orphans) await deleteTag(gh, { tag, log });
    for (const artifact of artifacts) await deleteArtifact(gh, { ...artifact, log });
    return { stale, orphans, artifacts, applied: true };
  }

  throw new PublishError('usage: release.mjs <rolling|tag|upload|cleanup> [options]');
}

// A copy of [file] under one fixed name, so the upload names the asset rather than taking
// the file's basename. Written in the runner's temporary directory, never in the tree.
export function stableCopy(file, name = LATEST_ASSET) {
  const dir = mkdtempSync(join(tmpdir(), 'brapmony-'));
  const copy = join(dir, name);
  copyFileSync(file, copy);
  return copy;
}

// The same, for a text asset written rather than copied.
export function stableText(text, name) {
  const dir = mkdtempSync(join(tmpdir(), 'brapmony-'));
  const file = join(dir, name);
  writeFileSync(file, text);
  return file;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch((err) => {
    console.error(`::error::${err.message}`);
    process.exit(1);
  });
}