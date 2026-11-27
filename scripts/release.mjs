#!/usr/bin/env node
// Publishes the Android client's APK from CI. Four callers:
//
//   node scripts/release.mjs publish --apk <file> --version <x.y.z> --commit <sha>
//       [--body <markdown>] [--keep <n>] [--attempt <n>] [--dry-run]
//     Publishes one release for one commit of the default branch, tagged
//     `v<x.y.z>-<sha7>` and named for that build (`0.1.0-abc1234`). The release carries the
//     APK and its `.sha256`, and nothing else: no second copy under a stable name, and no
//     other build of the same release. Its body is the changelog of the commits since the
//     previous release, so the Releases page reads as the project's history rather than as
//     a page of instructions that is the same on every release.
//
//     The release is created as a draft and published only once both files are on it, so a
//     failed publish leaves nothing on the Releases page that is not downloadable.
//
//   node scripts/release.mjs tag --tag <vX.Y.Z> [--apk <file>] [--name <title>]
//       [--body <markdown>] [--dry-run]
//     Creates the version release for a tag, or finds the one that exists, and prints its
//     id to GITHUB_OUTPUT. Used by release.yml, which then attaches the APK to it. Its body
//     is the same changelog, from the previous release to the tagged commit.
//
//   node scripts/release.mjs upload --release-id <id> <file>...
//     Attaches files to an existing release, replacing assets of the same name and
//     nothing else.
//
//   node scripts/release.mjs cleanup [--keep-releases <n>] [--keep-artifacts <n>] [--apply]
//     Lists, and with --apply does, what a publish leaves behind that nothing will read
//     again: the APK of a release older than the newest --keep-releases (the release and
//     its changelog stay), Actions artifacts beyond the newest --keep-artifacts, and the
//     rolling releases and tags of the scheme this replaced. It never touches a release
//     that is inside the window, and never a tag that names one.
//     Run by cleanup.yml on a manual dispatch, because deleting a published file is not
//     something a push should do by accident.
//
// What this refuses to do, because a published download link is somebody's:
//
//   * touch a release whose tag is not one it computed itself (`vX.Y.Z` or
//     `vX.Y.Z-<sha7>`);
//   * publish into a release at that tag that carries only somebody else's files;
//   * overwrite an asset this workflow did not upload (ownership is read from the asset's
//     uploader and its name, and only this workflow's own files are ever replaced);
//   * replace a build asset whose bytes differ, which would silently change what an
//     existing name means (the new bytes go under a distinct name instead);
//   * delete a version release, a version tag, or a release inside the kept window.
//
// Environment: GITHUB_TOKEN, GITHUB_REPOSITORY, GITHUB_API_URL, GITHUB_OUTPUT.
//
// A request that gets no usable answer -- a 5xx, a 429, a 408, or a connection that fails or
// closes before the answer -- is sent again, a few times with a growing wait, and says so as
// a warning in the log. GitHub answers a healthy request immediately, so a 5xx from it is
// one request failing rather than a verdict on the request, and losing a whole publish to one
// of them costs a thirty-minute build and leaves the release a commit behind. Every write
// here is written so that sending it twice leaves what one send would: an upload whose first
// attempt landed but whose answer was lost is recognised by GitHub's own digest of the stored
// asset, a create whose first attempt landed is read back by tag, and a delete that finds
// nothing treats that as done.
import { appendFileSync, existsSync, mkdtempSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

// The tags this workflow publishes. A version tag is `vX.Y.Z` and is a deliberate act: a
// commit is bumped to a release line and tagged. A commit tag is `vX.Y.Z-<sha7>` and is
// made by every push to the default branch. They cannot be confused: a version tag has no
// suffix, and a commit tag always has one.
export const VERSION_TAG_RE = /^v\d+\.\d+\.\d+$/;
export const COMMIT_TAG_RE = /^v\d+\.\d+\.\d+-[0-9a-f]{7,40}$/;
export const RELEASE_TAG_RE = /^v\d+\.\d+\.\d+(?:-[0-9a-f]{7,40})?$/;

// The prefix of the releases the scheme this replaced published, one per branch, refreshed
// by every push. Nothing publishes to one any more, so a publish removes them and their
// tags: they are the old files that made a downloader guess which one to take.
export const LEGACY_ROLLING_PREFIX = 'android-rolling-';

// How many releases keep their APK. A release per commit is a changelog, and the changelog
// is worth keeping; the file it carries is not, once it is a month old. Beyond this many,
// the release stays and its assets are deleted, so the page still says what the commit was.
export const DEFAULT_RELEASE_KEEP = 30;
// How many Actions artifacts the cleanup keeps: enough to read back what the last few runs
// built, not the whole history.
export const DEFAULT_ARTIFACT_KEEP = 10;
// How many commits a release's changelog lists before it says how many it left out.
export const DEFAULT_CHANGELOG_LIMIT = 40;

// The account GitHub attributes a workflow's uploads to. An asset uploaded by a workflow
// carries this login, and one somebody attached to the release by hand carries theirs, so
// it is how a file of a workflow's is told from a file of somebody else's. It is not on its
// own proof that this workflow uploaded it -- another workflow in this repository uploads
// with the same account -- so a name this workflow generates has to go with it.
export const BOT_LOGIN = 'github-actions[bot]';
// Releases published before this changed wrote an ownership label into every asset. GitHub
// shows an asset's label **in place of its file name**, so those releases list
// `brapmony-android sha256:…` where the download names should be. Nothing writes a label
// now, and an asset that already carries one is still read as this workflow's so it can be
// pruned with the release it is on.
export const LEGACY_OWNER = 'brapmony-android';

// The asset names this workflow publishes: `Brapmony-<version>...` today, and
// `Brapmony_Android_<version>...` from before the name scheme was flattened. Naming is how
// these are told apart on a release whose assets predate the ownership label, and it is what
// keeps a file another workflow uploaded from being taken for this one's.
export function ownName(name) {
  return /^Brapmony[_-]/.test(name) || name === UPDATE_MANIFEST;
}

// The file the app's update check reads, by name. It is not an APK, so it is not matched by
// the prefix above; it is named here, once, so ownership, pruning and replacement agree.
export const UPDATE_MANIFEST = 'update.json';
export function isOwned(asset) {
  if (typeof asset?.label === 'string' && asset.label.startsWith(`${LEGACY_OWNER} `)) return true;
  return asset?.uploader?.login === BOT_LOGIN && ownName(asset?.name);
}
export function ownsAsset(asset) {
  return isOwned(asset) || ownName(asset.name);
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

export class PublishError extends Error {}

// The tag a commit's release carries. The release line is in it, so a tag says which line
// the build belongs to, and the commit is in it, so two builds of one line cannot collide.
// It is a tag in the `v` namespace, which is what makes it sort and read beside the version
// tags; a version tag has no suffix, so a lookup of one can never find the other.
export function commitTag(version, commit) {
  const line = String(version || '').trim();
  if (!/^\d+\.\d+\.\d+$/.test(line)) throw new PublishError(`--version must be x.y.z, not ${version}`);
  const sha = String(commit || '').trim().toLowerCase();
  if (!/^[0-9a-f]{7,40}$/.test(sha)) throw new PublishError(`--commit must be a commit id, not ${commit}`);
  return `v${line}-${sha.slice(0, 7)}`;
}

// The name of a commit's release: the version the APK reports, which is the release line
// and the commit. The app's own name is not repeated in it: the release is on the app's
// repository, so "Brapmony for Android" in front of every title says nothing the page does
// not already say.
export function releaseTitle(version, commit) {
  const line = String(version || '').trim();
  const sha = String(commit || '').trim().toLowerCase();
  if (!/^\d+\.\d+\.\d+$/.test(line)) throw new PublishError(`--version must be x.y.z, not ${version}`);
  if (!/^[0-9a-f]{7,40}$/.test(sha)) throw new PublishError(`--commit must be a commit id, not ${commit}`);
  return `${line}-${sha.slice(0, 7)}`;
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

// How many times a request that got no usable answer is sent before the publish gives up,
// and how long to wait between attempts. GitHub answers a healthy request immediately, so a
// 5xx from it is a transient failure of one request rather than a verdict on the request:
// publishing an APK is a handful of calls after a thirty-minute build, and losing the whole
// run to one of them costs the build and leaves the release a commit behind.
//
// The wait grows, because a service that is briefly overwhelmed needs the calls to thin out
// rather than to arrive again at once. Fifteen seconds in all, which is nothing beside the
// build and well inside the publish job's own timeout.
export const RETRY_ATTEMPTS = 5;
export const RETRY_BACKOFF_MS = [1000, 2000, 4000, 8000];

// The same, overridable from the environment. The tests set both to nothing so a publish
// that is meant to fail does not sit through the waits; nothing in the workflow does, so
// what runs in CI is what the constants above say.
export function retrySettings(env = process.env) {
  const attempts = Number(env.HARMONY_RETRY_ATTEMPTS);
  // An unset variable is not a backoff of nothing: `''.split(',')` is one empty item, and
  // `Number('')` is 0, so the items are dropped unless they name a number.
  const backoff = String(env.HARMONY_RETRY_BACKOFF_MS ?? '')
    .split(',')
    .map((n) => n.trim())
    .filter((n) => n !== '' && Number.isFinite(Number(n)) && Number(n) >= 0)
    .map(Number);
  return {
    attempts: Number.isInteger(attempts) && attempts > 0 ? attempts : RETRY_ATTEMPTS,
    backoff: backoff.length ? backoff : RETRY_BACKOFF_MS,
  };
}

// Whether a failed request may be sent again. 5xx is the server failing to answer, 429 is
// "too many requests" (which says so with retry-after), and 408 is a request that timed out
// on the way. Everything else in the 4xx range is an answer about the request -- a 401, a
// 404, a 422 -- and sending it again would get the same answer, so it is not retried.
export function isTransientStatus(status) {
  return status >= 500 || status === 429 || status === 408;
}

// The wait a response asks for, when it names one. A number of seconds or an HTTP date, as
// the header allows. Capped so a hostile or mistaken value cannot hold a job until its
// timeout; the job is what a longer wait would eat.
export function retryAfterMs(header, now = Date.now()) {
  if (!header) return null;
  const text = String(header).trim();
  if (/^\d+$/.test(text)) return Math.min(Number(text) * 1000, 60_000);
  const at = Date.parse(text);
  if (Number.isNaN(at)) return null;
  return Math.min(Math.max(at - now, 0), 60_000);
}

export function client({
  token = process.env.GITHUB_TOKEN,
  repository = process.env.GITHUB_REPOSITORY,
  apiUrl = process.env.GITHUB_API_URL || 'https://api.github.com',
  attempts,
  backoff,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  log = () => {},
} = {}) {
  if (!token) throw new PublishError('GITHUB_TOKEN is not set');
  if (!/^[^/]+\/[^/]+$/.test(repository || '')) {
    throw new PublishError(`GITHUB_REPOSITORY is not owner/repo: ${repository}`);
  }
  const settings = retrySettings();
  const tries = Math.max(1, attempts ?? settings.attempts);
  const waits = backoff ?? settings.backoff;
  const waitFor = (attempt) => waits[Math.min(attempt - 1, waits.length - 1)] ?? 0;
  const base = apiUrl.replace(/\/$/, '');
  const repoUrl = `${base}/repos/${repository}`;
  const headers = {
    authorization: `Bearer ${token}`,
    'x-github-api-version': '2022-11-28',
    'user-agent': LEGACY_OWNER,
  };

  // One attempt. A request that cannot be sent at all (a connection refused, a DNS failure,
  // a socket that closed before the answer) is reported as a failure with no status, which
  // is the same thing as far as the caller is concerned: no usable answer.
  async function send(method, url, opts) {
    try {
      return await fetch(url.startsWith('http') ? url : `${repoUrl}${url}`, {
        method,
        headers: { ...headers, accept: opts.accept, ...(opts.contentType ? { 'content-type': opts.contentType } : {}) },
        body: opts.body,
      });
    } catch (error) {
      const failure = new PublishError(`${method} ${url}: ${error.message}`);
      failure.status = null;
      throw failure;
    }
  }

  // Every request goes through here, so a transient failure is retried wherever it happens
  // rather than in one call site. A request that got a 5xx or a network error is sent again
  // after a wait; the answer is returned as it came back otherwise, and a 4xx is never
  // retried. What a retry may do twice is the caller's problem -- each write here is written
  // so that sending it twice leaves the repository in the state one send would have.
  //
  // The answer's body is read here rather than by the caller, so that a body cut off part way
  // through -- a connection that dropped after the headers -- is the same kind of failure as
  // no answer at all, and gets the same retry. What is returned is the status, the headers
  // and the body, not a stream that is still arriving.
  async function request(method, url, opts = {}) {
    const options = { accept: 'application/vnd.github+json', ...opts };
    for (let attempt = 1; ; attempt += 1) {
      const last = attempt >= tries;
      let res;
      try {
        res = await send(method, url, options);
      } catch (error) {
        // A connection that never produced an answer. The same rule as a 5xx, and the same
        // bound: after the last attempt the failure is the caller's to report.
        if (last) throw error;
        const wait = waitFor(attempt);
        log(`::warning::${method} ${url}: ${error.message}; retrying in ${wait} ms (attempt ${attempt} of ${tries})`);
        await sleep(wait);
        continue;
      }
      const transient = isTransientStatus(res.status);
      let body;
      try {
        body = await res.text();
      } catch (error) {
        // Headers arrived and the body did not finish: the same "no usable answer", so the
        // same retry. The status is not known to be transient here, but an answer that was
        // never fully read is not an answer at all.
        if (last) throw new PublishError(`${method} ${url}: the answer was cut off (${error.message})`);
        const wait = waitFor(attempt);
        log(`::warning::${method} ${url}: the answer was cut off (${error.message}); retrying in ${wait} ms (attempt ${attempt} of ${tries})`);
        await sleep(wait);
        continue;
      }
      const answer = {
        ok: res.status >= 200 && res.status < 300,
        status: res.status,
        headers: res.headers,
        text: async () => body,
        json: async () => JSON.parse(body),
      };
      if (!transient || last) return answer;
      // A wait the response asked for wins over the backoff, so a rate limit is respected
      // rather than argued with.
      const asked = retryAfterMs(res.headers.get('retry-after'));
      const wait = asked ?? waitFor(attempt);
      log(`::warning::${method} ${url}: ${res.status}; retrying in ${wait} ms (attempt ${attempt} of ${tries})`);
      await sleep(wait);
    }
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
// paginate. A release is matched by exact tag only, and only ever a tag this workflow
// publishes: `vX.Y.Z` or `vX.Y.Z-<sha7>`.
async function findByTag(gh, tag) {
  if (!RELEASE_TAG_RE.test(String(tag || ''))) {
    throw new PublishError(`refusing to look up ${tag}: not a release tag`);
  }
  return (await gh.paginate('/releases')).find((r) => r.tag_name === tag) || null;
}

async function listAssets(gh, releaseId) {
  return gh.paginate(`/releases/${releaseId}/assets`);
}

// A small helper over git, for the changelog. The publisher reads the commit history of the
// checkout it runs in -- that is where the release notes come from -- and reports a git that
// cannot answer as a publish failure rather than publishing a release with no notes.
export function gitOutput(args, { cwd = process.cwd() } = {}) {
  const run = spawnSync('git', args, { cwd, encoding: 'utf8' });
  if (run.error) throw new PublishError(`git ${args.join(' ')}: ${run.error.message}`);
  return run.status === 0 ? run.stdout : null;
}

// The tag of the release before [commit], which is where a changelog starts. It is found
// with `git describe` on the commit's parent, so it is the nearest release tag behind this
// commit on the history, and never the tag this publish is about to create: a re-run of a
// commit that already has a release therefore reports the same changes the first run did,
// rather than nothing. A commit with no release behind it returns null, and the changelog is
// the commit itself.
//
// A tag that does not match one of the two schemes is skipped rather than named: a release's
// body links to the tag it describes, so a stray tag would be a link to nothing.
export function previousReleaseTag({ commit = 'HEAD', cwd } = {}) {
  const parent = gitOutput(['rev-parse', '--verify', '--quiet', `${commit}^`], { cwd });
  if (!parent || !parent.trim()) return null;
  const described = gitOutput(['describe', '--tags', '--abbrev=0', '--match', 'v[0-9]*', parent.trim()], { cwd });
  if (!described || !described.trim()) return null;
  const tag = described.trim();
  return RELEASE_TAG_RE.test(tag) ? tag : null;
}

function parseEntries(text) {
  return String(text)
    .split('\n')
    .filter((line) => line.includes('\t'))
    .map((line) => {
      const [sha, ...subject] = line.split('\t');
      return { sha: sha.trim(), subject: subject.join('\t').trim() };
    });
}

// The commits since the previous release, newest first. Merge commits are left out: a merge
// commit's subject names the pull request, and the commits it brought in are in the range
// already. [limit] bounds what a release page has to render; the count of what was left out
// is reported beside the entries rather than silently dropped.
//
// A commit with no release behind it is the first one, and its changelog is the history up to
// it -- still bounded, and still counted. The alternative, listing only the commit itself,
// would make the first release's page say less about the project than the hundredth's.
export function changelogEntries({ commit = 'HEAD', from, limit = DEFAULT_CHANGELOG_LIMIT, cwd } = {}) {
  const base = from === undefined ? previousReleaseTag({ commit, cwd }) : from;
  const range = base ? `${base}..${commit}` : commit;
  const out = gitOutput(['log', '--no-merges', '--format=%H%x09%s', `--max-count=${limit}`, range], { cwd });
  if (out === null) {
    throw new PublishError(base ? `cannot read the commits in ${base}..${commit}` : `cannot read the commit ${commit}`);
  }
  const entries = parseEntries(out);
  const counted = gitOutput(['rev-list', '--no-merges', '--count', range], { cwd });
  const total = Number((counted || '').trim()) || entries.length;
  return { previous: base, entries, more: Math.max(0, total - entries.length) };
}

// What a release says. It is the changelog and nothing else: the app is described in the
// repository's README, and how to install an APK is the same on every release, so repeating
// either here would be the same paragraphs on every page and would push the one thing that
// is different about this build -- what changed -- off the screen.
export function releaseBody({ commit, sha256, previous, entries = [], more = 0, repository } = {}) {
  const repo = String(repository || '').trim() || null;
  const short = (sha) => String(sha).slice(0, 7);
  const link = (sha) => (repo ? `[\`${short(sha)}\`](https://github.com/${repo}/commit/${sha})` : `\`${short(sha)}\``);
  const lines = [];
  lines.push(previous ? `## Changes since \`${previous}\`` : '## Changes');
  lines.push('');
  if (!entries.length) lines.push('- No commit subject recorded for this build.');
  for (const entry of entries) lines.push(`- ${entry.subject} (${link(entry.sha)})`);
  if (more > 0) lines.push(`- … and ${more} more commit${more === 1 ? '' : 's'}`);
  lines.push('');
  const footer = [`sha256: \`${sha256}\``];
  if (repo && previous && commit) {
    footer.push(`[Full comparison](https://github.com/${repo}/compare/${previous}...${commit})`);
  }
  lines.push(footer.join(' · '));
  return `${lines.join('\n')}\n`;
}

// The body of the release for [commit]: the changelog since the previous one, with the
// checksum of the file that release will carry.
export function notesFor({ commit = 'HEAD', sha256, repository, limit, cwd } = {}) {
  const { previous, entries, more } = changelogEntries({ commit, limit, cwd });
  return releaseBody({ commit, sha256, previous, entries, more, repository });
}

// The file the app's own update check reads: `update.json`, published beside the APK on
// every release.
//
// The app does not ask GitHub's REST API what the newest release is. That answer is a
// 15-20 KB document, it is rate limited per client address (sixty requests an hour
// unauthenticated, shared by everybody behind one carrier's NAT), and every install in the
// world would be asking it the same question. This is a few hundred bytes of static file
// served from the release's own assets through a CDN that answers `If-None-Match` with a
// 304, and it is identical for every install, so GitHub's cache absorbs the traffic instead
// of its API counting it.
//
// What it says is what an installed app needs to decide whether there is a newer build and
// where its APK is: the package, the versionCode Android compares, the release tag and the
// asset's own name (both of which are path segments of the download URL, so the app checks
// them against the shapes this script can produce), the SHA-256 of the file, its size, and
// the release page for anyone who wants to read what changed. `schema` is the format's own
// version, so a later change to it can be ignored by a build that does not know it rather
// than half-understood.
//
// The version name is the one the APK itself reports (`version-name` below), not a number
// computed a second time here: the app shows it, and a name that disagreed with the file
// would be a second, unchecked claim about what the file is.
export function updateManifest({ pkg, versionName, versionCode, tag, apk, sha256, size, commit, repository }) {
  const line = String(versionName || '').trim();
  if (!line) throw new PublishError('the update manifest needs the version the APK reports');
  if (!Number.isInteger(versionCode) || versionCode <= 0) {
    throw new PublishError(`the update manifest needs a positive versionCode, not ${versionCode}`);
  }
  if (!COMMIT_TAG_RE.test(String(tag || '')) && !VERSION_TAG_RE.test(String(tag || ''))) {
    throw new PublishError(`the update manifest needs a release tag, not ${tag}`);
  }
  if (!ownName(String(apk || ''))) {
    throw new PublishError(`the update manifest needs this workflow's APK name, not ${apk}`);
  }
  if (!/^[0-9a-f]{64}$/.test(String(sha256 || ''))) {
    throw new PublishError('the update manifest needs the APK\'s SHA-256');
  }
  if (!(Number.isFinite(size) && size > 0)) {
    throw new PublishError(`the update manifest needs the APK's size, not ${size}`);
  }
  const repo = String(repository || '').trim() || null;
  return `${JSON.stringify({
    schema: 1,
    package: pkg,
    versionCode,
    versionName: line,
    tag,
    apk,
    sha256: String(sha256).toLowerCase(),
    size,
    notes: repo ? `https://github.com/${repo}/releases/tag/${tag}` : '',
    commit: String(commit || '').toLowerCase(),
  }, null, 2)}\n`;
}

// The manifest, written to a file an upload can send. It is written to a temporary directory
// rather than the checkout: it is build output, not something the tree carries.
export function updateManifestFile(manifest) {
  const dir = mkdtempSync(join(tmpdir(), 'brapmony-'));
  const file = join(dir, 'update.json');
  writeFileSync(file, manifest);
  return file;
}

// The same, for a text asset written rather than copied: the `.sha256` that travels with
// the APK, in the form `sha256sum -c` reads.
export function checksumFile(sha256, name) {
  const dir = mkdtempSync(join(tmpdir(), 'brapmony-'));
  const file = join(dir, `${name}.sha256`);
  writeFileSync(file, `${sha256}  ${name}\n`);
  return file;
}

// GitHub's upload endpoint replaces nothing: a second upload of a name that is already
// there is a 422.
//
// No label is sent. GitHub shows an asset's label **in place of its file name** on the
// release page, so a label here made the release list `brapmony-android sha256:…` where
// `Brapmony-0.1.0-abc1234-debug.apk` should be, and a person looking for the APK found no
// file names at all. The label was only ever this workflow's note to itself about which
// bytes an asset held; GitHub's own `digest` says that, and says it without hiding the name.
//
// What GitHub stored is then compared with what was sent (checkAssetDigest): the digest in
// the response is computed by GitHub over the bytes it holds, so a mismatch means the file
// a downloader would get is not the file this run built.
//
// The upload is the one write here that a retry can duplicate: a request that is sent again
// after a lost answer may find its own first attempt's asset already under the name. So a
// 422 is not immediately a failure -- the release's assets are read back, and if GitHub
// holds these exact bytes under that name (its own digest says so) then the upload landed
// and the answer was lost, which is the outcome the caller wanted. Bytes under the name that
// are not this file are somebody else's and still fail.
async function uploadFile(gh, { releaseId, file, name }) {
  if (!existsSync(file)) throw new PublishError(`no such file: ${file}`);
  const sha256 = sha256File(file);
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
    if (error.status !== 422) throw error;
    const stored = (await listAssets(gh, releaseId)).filter((a) => a.name === name);
    const landed = stored.find((a) => storedBytes(a, sha256, statSync(file).size));
    if (!landed) throw new PublishError(`asset ${name} is already on release ${releaseId}`);
    // The earlier attempt's asset, which holds the bytes this one was uploading.
    return landed;
  }
  checkAssetDigest(asset, sha256, { name });
  return asset;
}

// Adds a file under its own name. A re-run of the same commit with the same bytes is a
// no-op: the asset a link already points at keeps meaning the same thing. A re-run with
// different bytes uploads under a distinct name rather than changing what the existing name
// resolves to, and the `.sha256` beside an APK moves with it.
export async function publishAsset(gh, { releaseId, file, name, attempt = 1 }) {
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
    // build's link keeps resolving to the earlier bytes. The `.r<n>` goes before the
    // extension, so an APK and the checksum beside it stay named for each other.
    const renamed = name.replace(/\.apk(\.sha256)?$/, `.r${attempt}.apk$1`);
    if (renamed === name) {
      throw new PublishError(`asset ${name} is already on release ${releaseId} with different bytes`);
    }
    const asset = await uploadFile(gh, { releaseId, file, name: renamed });
    return { asset, uploaded: true, sha256, name: renamed };
  }
  const asset = await uploadFile(gh, { releaseId, file, name });
  return { asset, uploaded: true, sha256, name };
}

// Publishes the update manifest on a release, replacing the one that is there.
//
// Unlike an APK, which never has its name re-pointed at different bytes, this file *must* be
// replaced: it describes the APK beside it, and a rebuild of the same commit uploads its
// bytes under a new name (`....r2.apk`), so the manifest that names it has to change too. The
// old one is deleted first, because GitHub's upload endpoint will not overwrite a name; that
// is safe only because the release is still a draft at this point, so a failure here leaves
// nothing on the Releases page that a phone could read. A re-run completes the release.
export async function publishUpdateManifest(gh, { releaseId, file }) {
  const sha256 = sha256File(file);
  const size = statSync(file).size;
  const existing = (await listAssets(gh, releaseId)).filter((a) => a.name === UPDATE_MANIFEST);
  const same = existing.find((a) => storedBytes(a, sha256, size));
  if (same) return { asset: same, uploaded: false, sha256, name: UPDATE_MANIFEST };
  for (const asset of existing) {
    if (!isOwned(asset)) {
      throw new PublishError(`asset ${UPDATE_MANIFEST} was not uploaded by this workflow; refusing to replace it`);
    }
    await deleteIfPresent(gh, `/releases/assets/${asset.id}`);
  }
  const asset = await uploadFile(gh, { releaseId, file, name: UPDATE_MANIFEST });
  return { asset, uploaded: true, sha256, name: UPDATE_MANIFEST };
}

// Finds the release for a commit's tag, or creates it as a draft. A release at that tag is
// only ever this workflow's: one that carries files and none of ours is somebody else's, and
// is refused rather than written into. An empty one is this workflow's own failed attempt --
// a create whose upload never happened -- and is finished by this run.
//
// The draft is what makes a failed publish harmless: until both files are on it, the release
// is invisible on the Releases page and to /releases/latest, so nobody is offered a page
// whose download does not exist yet.
export async function ensureCommitRelease(gh, { version, commit, name, body }) {
  const tag = commitTag(version, commit);
  const existing = await findByTag(gh, tag);
  if (existing) {
    const assets = await listAssets(gh, existing.id);
    if (assets.length && !assets.some(ownsAsset)) {
      throw new PublishError(`release ${tag} carries no build of this workflow's; refusing to publish into it`);
    }
    return { id: existing.id, tag, created: false, release: existing };
  }
  // A create whose first attempt landed answers 422 when it is sent again, because the tag
  // is now taken. The release is read back by tag then: if this workflow's release is there,
  // the earlier attempt landed and the answer was lost, which is the outcome asked for.
  let created;
  try {
    created = await gh.json('POST', '/releases', {
      contentType: 'application/json',
      body: JSON.stringify({
        tag_name: tag,
        target_commitish: commit,
        name,
        body,
        draft: true,
        prerelease: false,
      }),
    });
  } catch (error) {
    if (error.status !== 422) throw error;
    const landed = await findByTag(gh, tag);
    if (!landed) throw error;
    created = landed;
  }
  return { id: created.id, tag, created: true, release: created };
}

// Makes the release visible, once what it names is on it. Only what differs is written, so
// a re-run of a published release changes nothing. The body is written again because the
// release may have been created by an earlier attempt of this same commit, whose notes were
// composed before the build it is publishing existed.
export async function publishRelease(gh, { id, name, body }) {
  const current = await gh.json('GET', `/releases/${id}`);
  const patch = {};
  if (current.draft !== false) patch.draft = false;
  if (name && current.name !== name) patch.name = name;
  if (body && current.body !== body) patch.body = body;
  const updated = Object.keys(patch);
  if (!updated.length) return { updated };
  await gh.json('PATCH', `/releases/${id}`, {
    contentType: 'application/json',
    body: JSON.stringify(patch),
  });
  return { updated };
}

// A version release is addressed by its exact tag, and only ever a `vX.Y.Z` one: the commit
// tags are a different namespace, and this must never find one of those. An existing
// release is returned as it is -- published or draft -- so a re-run of a tag attaches to the
// same release instead of creating a second one.
//
// It is created as a draft, like a commit's release, and made visible by the upload that
// puts the APK on it: a tag whose build fails therefore leaves no page offering a download
// that is not there.
export async function ensureTagRelease(gh, { tag, name, body }) {
  const text = String(tag || '');
  if (!VERSION_TAG_RE.test(text)) {
    throw new PublishError(`--tag must be vX.Y.Z, not ${tag}`);
  }
  const version = text.replace(/^v/, '');
  const existing = await findByTag(gh, text);
  if (existing) {
    return { id: existing.id, tag: text, created: false, draft: Boolean(existing.draft) };
  }
  // As above: a create sent again after a lost answer answers 422, and the release the
  // first attempt made is read back by tag rather than reported as a failure.
  try {
    const created = await gh.json('POST', '/releases', {
      contentType: 'application/json',
      body: JSON.stringify({
        tag_name: text,
        name: name || version,
        body: body ?? '',
        draft: true,
        prerelease: false,
      }),
    });
    return { id: created.id, tag: text, created: true, draft: Boolean(created.draft) };
  } catch (error) {
    if (error.status !== 422) throw error;
    const landed = await findByTag(gh, text);
    if (!landed) throw error;
    return { id: landed.id, tag: text, created: true, draft: Boolean(landed.draft) };
  }
}

// Brings an existing release's notes up to date, so a re-run of a tag -- or a release found
// by a later attempt -- says what the commits since the previous release were rather than
// what they were when it was first created.
export async function updateReleaseNotes(gh, { id, body }) {
  if (!body) return { updated: false };
  const current = await gh.json('GET', `/releases/${id}`);
  if (current.body === body) return { updated: false };
  await gh.json('PATCH', `/releases/${id}`, {
    contentType: 'application/json',
    body: JSON.stringify({ body }),
  });
  return { updated: true };
}

// The commit releases this workflow publishes, newest first. A version release is not one of
// them: it is the archive, cut deliberately, and its files stay. A draft is not one either --
// it is a publish that did not finish, and the release a later attempt completes.
//
// A release carries its assets in the list answer (GitHub caps that at 30, and a release here
// holds two), so pruning needs no request per release.
function ourCommitReleases(releases) {
  return releases
    .filter((r) => !r.draft && COMMIT_TAG_RE.test(String(r.tag_name || '')))
    .sort((a, b) => b.id - a.id);
}

// Deletes the APK of every release beyond the newest [keep]. The release itself stays, with
// its body: the changelog is the record, and the file it carries is one build of one commit,
// which is reproducible from the commit and is not worth keeping for ever. Only assets this
// workflow uploaded are deleted; a file somebody attached by hand is left where it is.
export async function pruneOldReleaseAssets(gh, { keep = DEFAULT_RELEASE_KEEP, protect = [] } = {}) {
  if (!(keep >= 1)) throw new PublishError(`--keep must be at least 1: ${keep}`);
  const kept = new Set(protect);
  const pruned = [];
  for (const release of ourCommitReleases(await gh.paginate('/releases')).slice(keep)) {
    if (kept.has(release.id)) continue;
    for (const asset of release.assets || []) {
      if (!isOwned(asset)) continue;
      await deleteIfPresent(gh, `/releases/assets/${asset.id}`);
      pruned.push(asset.name);
    }
  }
  return pruned;
}

// The rolling releases and tags of the scheme this replaced: one release per branch,
// refreshed by every push, keeping ten builds and a `Brapmony-latest.apk` beside them. It is
// what made a release page list files nothing updated and titles that named a branch rather
// than a build, so nothing publishes to one any more and the first publish after this change
// removes them.
//
// Deleting a release leaves its git tag, so the tag list is swept too: a rolling tag is a
// page on the repository in its own right, and it is the one that made the tag list read as
// a list of branches. Only a tag with the rolling prefix is ever deleted: a version tag and
// a commit tag are what the releases above are addressed by.
export async function retireRollingScheme(gh, { log = () => {} } = {}) {
  const retired = [];
  for (const release of await gh.paginate('/releases')) {
    const tag = String(release.tag_name || '');
    if (!tag.startsWith(LEGACY_ROLLING_PREFIX)) continue;
    await deleteIfPresent(gh, `/releases/${release.id}`);
    log(`Deleted the old rolling release ${tag}`);
    retired.push(tag);
  }
  for (const tag of await gh.paginate('/tags')) {
    const name = String(tag.name || '');
    if (!name.startsWith(LEGACY_ROLLING_PREFIX)) continue;
    await deleteIfPresent(gh, `/git/refs/tags/${name}`);
    log(`Deleted the old rolling tag ${name}`);
    retired.push(name);
  }
  return retired;
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
  await deleteIfPresent(gh, `/actions/artifacts/${id}`);
  log(`Deleted artifact ${name} (${id})`);
  return { id, name };
}

function required(values, keys) {
  for (const k of keys) if (!values[k]) throw new PublishError(`--${k} is required`);
}

// A delete is the one write that a retry cannot duplicate, so a 404 is the state it was
// after rather than a failure: the thing is not there. A 404 is not retried by the client
// (it is an answer, not a transient failure), but a delete sent again after a lost answer
// finds its first attempt had already removed the thing, and that is the same outcome.
export async function deleteIfPresent(gh, path) {
  try {
    await gh.json('DELETE', path);
    return true;
  } catch (error) {
    if (error.status === 404) return false;
    throw error;
  }
}

// The client is built from the environment by default; a test passes its own, which is how
// the publishing rules above are checked against a stand-in for the API without a token.
export async function main(argv, { gh: ghOverride, log = console.log } = {}) {
  const [command, ...rest] = argv;
  const { values, positionals } = parseArgs({
    args: rest,
    allowPositionals: command === 'upload',
    options: {
      apk: { type: 'string' },
      version: { type: 'string' },
      commit: { type: 'string' },
      package: { type: 'string' },
      'version-name': { type: 'string' },
      'version-code': { type: 'string' },
      body: { type: 'string' },
      keep: { type: 'string' },
      attempt: { type: 'string' },
      'release-id': { type: 'string' },
      tag: { type: 'string' },
      name: { type: 'string' },
      'keep-artifacts': { type: 'string' },
      'keep-releases': { type: 'string' },
      apply: { type: 'boolean', default: false },
      'dry-run': { type: 'boolean', default: false },
    },
  });

  if (command === 'publish') {
    // The package and the two numbers are what the app's update check compares, and they are
    // passed rather than derived here: the workflow reads them back out of the finished APK
    // and has already refused a build that disagrees with them, so this is the same claim
    // the file makes about itself rather than a second one computed beside it.
    required(values, ['apk', 'version', 'commit', 'package', 'version-name', 'version-code']);
    const version = values.version;
    const commit = values.commit;
    const tag = commitTag(version, commit);
    const name = releaseTitle(version, commit);
    if (!existsSync(values.apk)) throw new PublishError(`no such file: ${values.apk}`);
    if (values['dry-run']) {
      log(`Would publish ${basename(values.apk)} as ${name} (${tag})`);
      return { tag, dryRun: true };
    }
    const sha256 = sha256File(values.apk);
    // The notes are the commits since the previous release, read from this checkout. A
    // checkout that cannot answer fails the publish rather than publishing a release with no
    // notes: what changed is the one thing that is different about this release.
    const body = values.body ?? notesFor({ commit, sha256, repository: process.env.GITHUB_REPOSITORY });
    const keep = Number(values.keep ?? DEFAULT_RELEASE_KEEP);
    if (!(keep >= 1)) throw new PublishError(`--keep must be at least 1: ${keep}`);
    const gh = ghOverride ?? client({ log });
    const { id, created } = await ensureCommitRelease(gh, { version, commit, name, body });
    log(`${created ? 'Created' : 'Found'} release ${tag} (${id})`);
    const attempt = Number(values.attempt ?? 1) || 1;
    const build = await publishAsset(gh, { releaseId: id, file: values.apk, name: basename(values.apk), attempt });
    log(`${build.uploaded ? 'Uploaded' : 'Kept'} ${build.name} (sha256 ${build.sha256})`);
    // The checksum names the asset that is really on the release, which is the file's own
    // name unless a rebuild gave it another one.
    const checksum = checksumFile(build.sha256, build.name);
    const sum = await publishAsset(gh, { releaseId: id, file: checksum, name: basename(checksum) });
    log(`${sum.uploaded ? 'Uploaded' : 'Kept'} ${sum.name}`);
    // The app's own update check reads this, from `/releases/latest/download/update.json`.
    // It names the APK that is really on the release, which is the file's own name unless a
    // rebuild gave it another one, and the version name the APK reports -- so a phone is
    // offered the build that is here rather than one Gradle was asked to produce.
    const manifest = updateManifest({
      pkg: values.package,
      versionName: values['version-name'],
      versionCode: Number(values['version-code']),
      tag,
      apk: build.name,
      sha256: build.sha256,
      size: statSync(values.apk).size,
      commit,
      repository: process.env.GITHUB_REPOSITORY,
    });
    const manifestPath = updateManifestFile(manifest);
    const published = await publishUpdateManifest(gh, { releaseId: id, file: manifestPath });
    log(`${published.uploaded ? 'Uploaded' : 'Kept'} update.json (versionCode ${JSON.parse(manifest).versionCode})`);
    // Only now that the files are on it is the release made visible: a publish that fails
    // before this point leaves a draft, not a page whose download does not exist.
    const { updated } = await publishRelease(gh, { id, name, body });
    if (updated.length) log(`Published ${tag}: ${updated.join(', ')}`);
    // Nothing older is touched until this release is complete, so a failure above leaves
    // the previous releases exactly as they were.
    const pruned = await pruneOldReleaseAssets(gh, { keep });
    if (pruned.length) log(`Deleted ${pruned.length} old release asset(s): ${pruned.join(', ')}`);
    const retired = await retireRollingScheme(gh, { log });
    if (process.env.GITHUB_OUTPUT) {
      appendFileSync(
        process.env.GITHUB_OUTPUT,
        `tag=${tag}\nrelease-id=${id}\nsha256=${build.sha256}\nname=${build.name}\n`,
      );
    }
    return { id, tag, uploaded: build.uploaded ? build.name : null, sha256: build.sha256, pruned, retired };
  }

  // Writes the update manifest for an APK this workflow built and has already checked, and
  // prints its path. It is a separate command because a tag's release uploads its files by
  // name rather than through `publish`: the format lives in one place either way.
  if (command === 'manifest') {
    required(values, ['apk', 'tag', 'package', 'version-name', 'version-code']);
    if (!existsSync(values.apk)) throw new PublishError(`no such file: ${values.apk}`);
    const manifest = updateManifest({
      pkg: values.package,
      versionName: values['version-name'],
      versionCode: Number(values['version-code']),
      tag: values.tag,
      apk: basename(values.apk),
      sha256: sha256File(values.apk),
      size: statSync(values.apk).size,
      commit: values.commit ?? '',
      repository: process.env.GITHUB_REPOSITORY,
    });
    // Written to a temporary directory and printed: the workflow uploads it by the name the
    // path ends in, and nothing in the checkout is build output.
    const file = updateManifestFile(manifest);
    log(file);
    if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `path=${file}\n`);
    return { path: file, manifest };
  }

  if (command === 'tag') {
    required(values, ['tag']);
    const tag = values.tag;
    const version = String(tag).replace(/^v/, '');
    const commit = values.commit ?? String(gitOutput(['rev-parse', 'HEAD']) || '').trim();
    if (values['dry-run']) {
      log(`Would create or find the release for ${tag}`);
      return { tag, dryRun: true };
    }
    const sha256 = values.apk ? sha256File(values.apk) : undefined;
    const body = values.body ?? notesFor({ commit, sha256, repository: process.env.GITHUB_REPOSITORY });
    const gh = ghOverride ?? client({ log });
    const { id, created } = await ensureTagRelease(gh, { tag, name: values.name || version, body });
    // A release found from an earlier attempt gets this run's notes: they name the commits
    // since the previous release, which is not what a body written by hand says.
    if (!created) await updateReleaseNotes(gh, { id, body });
    log(`${created ? 'Created' : 'Found'} release ${tag} (${id})`);
    if (process.env.GITHUB_OUTPUT) {
      appendFileSync(process.env.GITHUB_OUTPUT, `id=${id}\ntag=${tag}\n`);
    }
    return { id, tag, created };
  }

  // Makes a release a tag's job created as a draft visible, once its files are on it. The
  // upload is a separate command because the APK is named and checked between the two.
  if (command === 'publish-release') {
    required(values, ['release-id']);
    if (values['dry-run']) {
      log(`Would publish release ${values['release-id']}`);
      return { id: values['release-id'], dryRun: true };
    }
    const gh = ghOverride ?? client({ log });
    const { updated } = await publishRelease(gh, { id: values['release-id'], body: values.body });
    log(`Published release ${values['release-id']}${updated.length ? `: ${updated.join(', ')}` : ''}`);
    return { id: values['release-id'], updated };
  }

  if (command === 'upload') {
    required(values, ['release-id']);
    if (!positionals.length) throw new PublishError('upload needs at least one file');
    const gh = ghOverride ?? client({ log });
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
        await deleteIfPresent(gh, `/releases/assets/${a.id}`);
      }
      uploaded.push(await uploadFile(gh, { releaseId, file, name }));
    }
    for (const a of uploaded) log(`Uploaded ${a.name} to release ${releaseId}`);
    return uploaded;
  }

  if (command === 'cleanup') {
    const gh = ghOverride ?? client({ log });
    const apply = values['apply'] === true;
    const keepReleases = Number(values['keep-releases'] ?? DEFAULT_RELEASE_KEEP);
    const keepArtifacts = Number(values['keep-artifacts'] ?? DEFAULT_ARTIFACT_KEEP);
    const releases = ourCommitReleases(await gh.paginate('/releases'));
    const old = releases.slice(keepReleases).map((r) => ({ id: r.id, tag: r.tag_name, assets: (r.assets || []).length }));
    const rolling = (await gh.paginate('/releases'))
      .map((r) => String(r.tag_name || ''))
      .filter((tag) => tag.startsWith(LEGACY_ROLLING_PREFIX));
    const artifacts = await oldArtifacts(gh, { keep: keepArtifacts });
    const freed = artifacts.reduce((sum, a) => sum + (a.size || 0), 0);
    log(`Releases inside the newest ${keepReleases}: ${Math.min(releases.length, keepReleases)} of ${releases.length}`);
    log(`Releases beyond it, whose APK would go: ${old.length ? old.map((r) => r.tag).join(', ') : '(none)'}`);
    log(`Old rolling releases to remove: ${rolling.length ? rolling.join(', ') : '(none)'}`);
    log(`Artifacts beyond the newest ${keepArtifacts}: ${artifacts.length ? `${artifacts.map((a) => a.name).join(', ')} (${(freed / 1e6).toFixed(1)} MB)` : '(none)'}`);
    if (!apply) {
      log('Nothing deleted: pass --apply to delete what is listed above.');
      return { releases: old, rolling, artifacts, applied: false };
    }
    const pruned = await pruneOldReleaseAssets(gh, { keep: keepReleases });
    if (pruned.length) log(`Deleted ${pruned.length} release asset(s): ${pruned.join(', ')}`);
    const retired = await retireRollingScheme(gh, { log });
    for (const artifact of artifacts) await deleteArtifact(gh, { ...artifact, log });
    return { releases: old, rolling, retired, pruned, artifacts, applied: true };
  }

  throw new PublishError('usage: release.mjs <publish|tag|upload|publish-release|cleanup> [options]');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch((err) => {
    console.error(`::error::${err.message}`);
    process.exit(1);
  });
}
