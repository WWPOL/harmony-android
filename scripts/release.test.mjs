// node --test scripts/release.test.mjs
//
// Runs scripts/release.mjs against an in-process stand-in for the GitHub
// releases REST API. The properties checked here are the ones that protect a published
// download: which releases and assets a publish may touch, that a re-run is safe, that a
// collision or a failed upload cannot destroy the build somebody is downloading, and that
// retention only ever removes this workflow's own older builds.
import { after, test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile, spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

import {
  COMMIT_TAG_RE,
  DEFAULT_ARTIFACT_KEEP,
  DEFAULT_CHANGELOG_LIMIT,
  DEFAULT_RELEASE_KEEP,
  LEGACY_ROLLING_PREFIX,
  VERSION_TAG_RE,
  BOT_LOGIN,
  LEGACY_OWNER,
  RETRY_ATTEMPTS,
  RETRY_BACKOFF_MS,
  changelogEntries,
  client,
  commitTag,
  deleteArtifact,
  ensureCommitRelease,
  ensureTagRelease,
  isTransientStatus,
  notesFor,
  oldArtifacts,
  previousReleaseTag,
  pruneOldReleaseAssets,
  publishAsset,
  publishRelease,
  releaseBody,
  releaseTitle,
  retireRollingScheme,
  retryAfterMs,
  retrySettings,
} from './release.mjs';

const run = promisify(execFile);
const here = dirname(fileURLToPath(import.meta.url));
const OWNER_REPO = 'acme/harmony-android';
const sha = (buf) => createHash('sha256').update(buf).digest('hex');

// Release REST subset: list/create/get releases, list assets, upload with a name, rename
// (PATCH) and delete assets, and the tag refs a rolling tag is moved with. Two items per
// page, so pagination is exercised. failUploads makes an upload whose name matches fail
// with a 500, which is how a network or quota failure during a publish is reproduced.
// Every stand-in server this file starts. `fetch` keeps its connections alive, so a plain
// `server.close()` would wait for a socket nobody is going to close; the suite closes them
// itself, and each server is unref'd so a test that forgets cannot hold the process open.
const servers = [];
after(async () => {
  for (const server of servers) {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  }
});

const fails = (matcher, name) =>
  typeof matcher === 'string' ? name.includes(matcher) : !!matcher && matcher.test(name);

async function fakeGitHub({ releases: seed = [], failUploads = null, corruptUploads = null } = {}) {
  const releases = structuredClone(seed);
  // Tag refs, by tag name: what a rolling tag points at. Seeded from a release's own tag
  // so a release that exists has the ref it would have on GitHub.
  const refs = new Map();
  for (const r of releases) if (r.tag_name) refs.set(r.tag_name, r.target_commitish || 'f'.repeat(40));
  const calls = [];
  // `digests: false` makes the stand-in answer without a digest, the way an API that does
  // not model the field does; a test then sees that a publish still works.
  const state = {
    failUploads, corruptUploads, digests: true, uploader: null, ignoresLabel: false,
    branches: [], artifacts: [],
    // Transient failures, keyed by `${METHOD} ${path}` or a RegExp over that key, with the
    // number of attempts still to fail. `transient` answers the failure without doing the
    // request; `lostAnswers` does the request and then answers the failure anyway, which is
    // what a proxy or a dropped connection does to an upload that landed; `cutOff` answers
    // the headers and then drops the connection, so the body never finishes arriving.
    transient: new Map(),
    lostAnswers: new Map(),
    cutOff: new Map(),
  };
  // The failure a request is owed, if any, and whether it is owed before or after the
  // request is served. A count of 1 fails one attempt and then lets the next through, which
  // is what a retry is for.
  const owed = (kind, key) => {
    for (const [pattern, left] of state[kind]) {
      const matches = typeof pattern === 'string' ? pattern === key : pattern.test(key);
      if (!matches || left <= 0) continue;
      state[kind].set(pattern, left - 1);
      return true;
    }
    return false;
  };
  let nextId = 900;
  const server = http.createServer(async (req, res) => {
    const base = `http://127.0.0.1:${server.address().port}`;
    const url = new URL(req.url, base);
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const body = Buffer.concat(chunks);
    const key = `${req.method} ${url.pathname}`;
    calls.push({ method: req.method, path: url.pathname, name: url.searchParams.get('name'), label: url.searchParams.get('label') });
    // A request that never reaches the handler: the server failing to answer it at all.
    if (owed('transient', key)) {
      res.writeHead(500, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ message: 'Server Error' }));
    }
    // The headers arrive and the body never finishes: a connection that dropped part way
    // through the answer. The promise `res.text()` then rejects, which is the same "no usable
    // answer" as no connection at all.
    if (owed('cutOff', key)) {
      res.writeHead(200, { 'content-type': 'application/json', 'content-length': '4096' });
      res.write('{"id":1,');
      return res.destroy();
    }
    const send = (statusCode, data, headers = {}) => {
      // The request was served, and the answer is lost on the way back. The next attempt
      // finds whatever this one did, which is the case a retry has to be safe for.
      if (owed('lostAnswers', key)) {
        res.writeHead(500, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ message: 'Server Error' }));
      }
      res.writeHead(statusCode, { 'content-type': 'application/json', ...headers });
      res.end(data === undefined ? '' : JSON.stringify(data));
    };
    const page = (items) => {
      const n = Number(url.searchParams.get('page') || 1);
      const slice = items.slice((n - 1) * 2, n * 2);
      const headers = {};
      if (n * 2 < items.length) {
        const next = new URL(url);
        next.searchParams.set('page', String(n + 1));
        headers.link = `<${next}>; rel="next"`;
      }
      send(200, slice, headers);
    };
    const match = (re) => re.exec(url.pathname);
    if (req.method === 'GET' && url.pathname === `/repos/${OWNER_REPO}/releases`) return page(releases);
    if (req.method === 'POST' && url.pathname === `/repos/${OWNER_REPO}/releases`) {
      const payload = JSON.parse(body.toString());
      // A tag names at most one release, so a second create for a tag that has one is a 422
      // rather than a second release, as GitHub answers it. This is the answer a retried
      // create gets when its first attempt landed and the answer was lost.
      if (releases.some((r) => r.tag_name === payload.tag_name)) {
        return send(422, { message: 'Validation Failed', errors: [{ code: 'already_exists' }] });
      }
      const id = nextId++;
      const release = {
        id,
        tag_name: payload.tag_name,
        name: payload.name,
        body: payload.body,
        draft: !!payload.draft,
        prerelease: !!payload.prerelease,
        assets: [],
        upload_url: `${base}/repos/${OWNER_REPO}/releases/${id}/assets{?name,label}`,
      };
      releases.push(release);
      return send(201, release);
    }
    let m = match(new RegExp(`^/repos/${OWNER_REPO}/releases/(\\d+)$`));
    if (m && req.method === 'GET') {
      const release = releases.find((r) => r.id === Number(m[1]));
      return release ? send(200, release) : send(404, { message: 'Not Found' });
    }
    if (m && req.method === 'PATCH') {
      const release = releases.find((r) => r.id === Number(m[1]));
      if (!release) return send(404, { message: 'Not Found' });
      Object.assign(release, JSON.parse(body.toString()));
      return send(200, release);
    }
    // Deleting a release, which `cleanup` does for a rolling release whose branch is gone.
    // Its assets go with it, as they do on GitHub.
    if (m && req.method === 'DELETE') {
      const id = Number(m[1]);
      const before = releases.length;
      const kept = releases.filter((r) => r.id !== id);
      if (kept.length === before) return send(404, { message: 'Not Found' });
      releases.splice(0, releases.length, ...kept);
      return send(204);
    }
    m = match(new RegExp(`^/repos/${OWNER_REPO}/releases/(\\d+)/assets$`));
    if (m && req.method === 'GET') {
      const release = releases.find((r) => r.id === Number(m[1]));
      return release ? page(release.assets) : send(404, { message: 'Not Found' });
    }
    if (m && req.method === 'POST') {
      const release = releases.find((r) => r.id === Number(m[1]));
      if (!release) return send(404, { message: 'Not Found' });
      const name = url.searchParams.get('name');
      if (fails(state.failUploads, name)) return send(500, { message: 'upload failed' });
      if (release.assets.some((a) => a.name === name)) {
        return send(422, { message: 'Validation Failed', errors: [{ code: 'already_exists' }] });
      }
      const asset = {
        id: nextId++,
        name,
        // No label: this workflow does not send one, because GitHub shows a label in place
        // of the file name. An asset uploaded through this API is attributed to the account
        // the token belongs to, which is what ownership is read from; `state.uploader`
        // makes an upload somebody else's, the way a file attached by hand is.
        label: url.searchParams.get('label') || '',
        uploader: state.uploader ?? { login: BOT_LOGIN, type: 'Bot' },
        size: body.length,
        state: 'uploaded',
        // GitHub computes a SHA-256 over every asset it stores and reports it; the
        // publisher compares it with the file it uploaded. `corruptUploads` stores bytes
        // other than the ones sent, which is what a truncated or rewritten upload looks
        // like from the publisher's side.
        digest: `sha256:${sha(fails(state.corruptUploads, name) ? Buffer.from('not the bytes that were sent') : body)}`,
        content: body.toString('base64'),
        browser_download_url: `${base}/${OWNER_REPO}/releases/download/${release.tag_name}/${name}`,
      };
      release.assets.push(asset);
      // The stand-in can answer without the digest field, as an API that predates it does.
      if (!state.digests) {
        const { digest, ...rest } = asset;
        return send(201, rest);
      }
      return send(201, asset);
    }
    m = match(new RegExp(`^/repos/${OWNER_REPO}/releases/assets/(\\d+)$`));
    if (m && req.method === 'PATCH') {
      const payload = JSON.parse(body.toString());
      for (const r of releases) {
        const a = r.assets.find((x) => x.id === Number(m[1]));
        if (a) {
          if (payload.name !== undefined) {
            if (r.assets.some((x) => x.name === payload.name && x.id !== a.id)) {
              return send(422, { message: 'Validation Failed', errors: [{ code: 'already_exists' }] });
            }
            a.name = payload.name;
          }
          // GitHub takes an empty label as "clear it", which is how the assets an earlier
          // version labelled stop hiding their names. `state.ignoresLabel` models an API
          // that leaves the label where it was, which the publisher must report.
          if (payload.label !== undefined && !state.ignoresLabel) a.label = payload.label;
          return send(200, a);
        }
      }
      return send(404, { message: 'Not Found' });
    }
    if (m && req.method === 'DELETE') {
      for (const r of releases) r.assets = r.assets.filter((a) => a.id !== Number(m[1]));
      return send(204);
    }
    // The tag endpoints the publisher moves a rolling tag with. A ref is
    // `refs/tags/<name>`; the object it points at is the commit. A release created from a
    // branch has no tag ref, which is a 404 here as it is on GitHub.
    m = match(/^\/repos\/[^/]+\/[^/]+\/git\/ref\/tags\/(.+)$/);
    if (m && req.method === 'GET') {
      const ref = refs.get(decodeURIComponent(m[1]));
      return ref
        ? send(200, { ref: `refs/tags/${decodeURIComponent(m[1])}`, object: { type: 'commit', sha: ref } })
        : send(404, { message: 'Not Found' });
    }
    m = match(/^\/repos\/[^/]+\/[^/]+\/git\/refs\/tags\/(.+)$/);
    if (m && req.method === 'PATCH') {
      const name = decodeURIComponent(m[1]);
      const payload = JSON.parse(body.toString());
      if (!refs.has(name)) return send(422, { message: 'Reference does not exist' });
      if (!payload.force && refs.get(name) !== payload.sha) {
        return send(422, { message: 'Update is not a fast forward' });
      }
      refs.set(name, payload.sha);
      return send(200, { ref: `refs/tags/${name}`, object: { type: 'commit', sha: payload.sha } });
    }
    if (url.pathname.endsWith('/git/refs') && req.method === 'POST') {
      const payload = JSON.parse(body.toString());
      const name = String(payload.ref || '').replace(/^refs\/tags\//, '');
      if (!name) return send(422, { message: 'ref is required' });
      if (refs.has(name)) return send(422, { message: 'Reference already exists' });
      refs.set(name, payload.sha);
      return send(201, { ref: `refs/tags/${name}`, object: { type: 'commit', sha: payload.sha } });
    }
    // Deleting a tag, which `cleanup` does after it deletes the release that named it.
    m = match(/^\/repos\/[^/]+\/[^/]+\/git\/refs\/tags\/(.+)$/);
    if (m && req.method === 'DELETE') {
      const name = decodeURIComponent(m[1]);
      if (!refs.has(name)) return send(404, { message: 'Not Found' });
      refs.delete(name);
      return send(204);
    }
    // The branch list, which `cleanup` reads to decide whether a rolling release's branch
    // still exists. Seeded per test; empty by default, so nothing is assumed to exist.
    if (req.method === 'GET' && url.pathname === `/repos/${OWNER_REPO}/branches`) {
      return page(state.branches.map((name) => ({ name, commit: { sha: 'f'.repeat(40) } })));
    }
    // The tag list, which `cleanup` reads to find rolling tags a hand-deleted release left
    // behind. It is the refs the stand-in holds, so a tag created or deleted here is in it.
    if (req.method === 'GET' && url.pathname === `/repos/${OWNER_REPO}/tags`) {
      return page([...refs.keys()].map((name) => ({ name, commit: { sha: refs.get(name) } })));
    }
    // Actions artifacts, which `cleanup` prunes to the newest few.
    if (req.method === 'GET' && url.pathname === `/repos/${OWNER_REPO}/actions/artifacts`) {
      // The real endpoint pages an object holding the page, not the page itself.
      const n = Number(url.searchParams.get('page') || 1);
      const slice = state.artifacts.slice((n - 1) * 2, n * 2);
      const headers = {};
      if (n * 2 < state.artifacts.length) {
        const next = new URL(url);
        next.searchParams.set('page', String(n + 1));
        headers.link = `<${next}>; rel="next"`;
      }
      return send(200, { total_count: state.artifacts.length, artifacts: slice }, headers);
    }
    m = match(new RegExp(`^/repos/${OWNER_REPO}/actions/artifacts/(\\d+)$`));
    if (m && req.method === 'DELETE') {
      const id = Number(m[1]);
      const before = state.artifacts.length;
      state.artifacts = state.artifacts.filter((a) => a.id !== id);
      return state.artifacts.length === before ? send(404, { message: 'Not Found' }) : send(204);
    }
    return send(404, { message: `unhandled ${req.method} ${url.pathname}` });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  server.unref();
  servers.push(server);
  const url = `http://127.0.0.1:${server.address().port}`;
  // A seeded release's upload_url names this server, as GitHub's would name its own.
  for (const r of releases) r.upload_url = `${url}/repos/${OWNER_REPO}/releases/${r.id}/assets{?name,label}`;
  return {
    url,
    releases,
    refs,
    calls,
    state,
    ...client({ token: 'test-token', repository: OWNER_REPO, apiUrl: url }),
    close: async () => {
      server.closeAllConnections();
      await new Promise((resolve) => server.close(resolve));
    },
  };
}

// The commit's release, created the way a publish creates it, for a test that then works on
// the release directly.
async function seedRelease(gh) {
  return ensureCommitRelease(gh, {
    version: '1.9.6', commit: 'abc1234', name: releaseTitle('1.9.6', 'abc1234'), body: '',
  });
}

// A release a publish made visible: seeded releases are drafts until something publishes
// them, which is what the publisher does once the files are on.
async function visible(gh, id) {
  return publishRelease(gh, { id });
}

// A published version release with assets, which a commit's publish may not touch.
function versionRelease() {
  return {
    id: 41,
    tag_name: 'v1.9.6',
    name: 'Brapmony 1.9.6',
    draft: false,
    prerelease: false,
    assets: [
      { id: 4101, name: 'Other_Tool_V1.9.6.apk', label: '', size: 12, state: 'uploaded', browser_download_url: 'x' },
      { id: 4102, name: 'Brapmony-1.9.6.apk', label: '', size: 13, state: 'uploaded', uploader: { login: BOT_LOGIN }, browser_download_url: 'y' },
      // An asset from before the name scheme changed. It is still this workflow's, and on
      // a version release it is left exactly where it is.
      { id: 4104, name: 'Brapmony_Android_V1.9.6.apk', label: '', size: 14, state: 'uploaded', uploader: { login: BOT_LOGIN }, browser_download_url: 'w' },
      { id: 4103, name: 'latest.json', label: '', size: 34, state: 'uploaded', browser_download_url: 'z' },
    ],
    upload_url: '',
  };
}

// The rolling releases of the scheme this replaced: one per branch, with a stable name
// beside the builds. They are what a publish removes.
function rollingRelease(tag, { id = 70, prerelease = true } = {}) {
  return {
    id,
    tag_name: `${LEGACY_ROLLING_PREFIX}${tag}`,
    name: `Brapmony for Android \u2014 newest build of ${tag}`,
    body: 'an older body',
    draft: false,
    prerelease,
    assets: [
      { id: id * 10 + 1, name: 'Brapmony-1.9.6-abc1234-debug.apk', label: '', size: 9, state: 'uploaded', uploader: { login: BOT_LOGIN } },
      { id: id * 10 + 2, name: 'Brapmony-latest.apk', label: '', size: 9, state: 'uploaded', uploader: { login: BOT_LOGIN } },
    ],
    upload_url: '',
  };
}

function workspace(commit = 'abc1234', bytes = null) {
  const dir = mkdtempSync(join(tmpdir(), 'brapmony-test-'));
  const apk = join(dir, `Brapmony-1.9.6-${commit}-debug.apk`);
  writeFileSync(apk, bytes ?? Buffer.from(`PK apk bytes for ${commit}`));
  return { dir, apk, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

// The publisher retries a request that got no usable answer, waiting longer each time, which
// is fifteen seconds of waiting for a request that fails every attempt. What these tests
// check is which requests are retried and what a retry does, not how long it waits, so the
// wait is nothing here. It is set in this process as well as passed to the child, because
// some tests call the publisher's functions directly; the one test that checks the shipped
// waits passes an empty environment, so it sees them. Nothing in the workflow sets either
// variable, so the run in CI waits as the constants say.
process.env.HARMONY_RETRY_BACKOFF_MS = '0';
const FAST_RETRIES = { HARMONY_RETRY_BACKOFF_MS: '0', HARMONY_RETRY_ATTEMPTS: String(RETRY_ATTEMPTS) };

// A publish of [args], with the arguments the workflow always passes filled in unless the
// caller names them. `--version` is the release line, and `--commit` the build.
async function publish(args, { gh: existing, releases, failUploads, corruptUploads } = {}) {
  const gh = existing ?? (await fakeGitHub({ releases, failUploads, corruptUploads }));
  // A reused server may be told to fail this publish's uploads.
  if (existing && failUploads !== undefined) existing.state.failUploads = failUploads;
  if (existing && corruptUploads !== undefined) existing.state.corruptUploads = corruptUploads;
  const out = join(mkdtempSync(join(tmpdir(), 'brapmony-out-')), 'output');
  writeFileSync(out, '');
  const full = [...args];
  if (!full.includes('--version')) full.push('--version', '1.9.6');
  // The notes are composed from git; a test names them, so what a release says does not
  // depend on the checkout the tests run in.
  if (!full.includes('--body')) full.push('--body', 'the changes of this build');
  const { stdout } = await run(process.execPath, [join(here, 'release.mjs'), 'publish', ...full], {
    env: {
      ...process.env,
      ...FAST_RETRIES,
      GITHUB_TOKEN: 'test-token',
      GITHUB_REPOSITORY: OWNER_REPO,
      GITHUB_API_URL: gh.url,
      GITHUB_OUTPUT: out,
    },
  });
  return { gh, stdout, output: readFileSync(out, 'utf8') };
}

// A release is a commit of the default branch: `v<version>-<sha7>`, named for the build and
// not for a branch. A version tag has no suffix, so the two cannot collide, and a version
// release can never be found by the commit path.
test('a commit tag and its release name are derived from the version and the commit', () => {
  assert.equal(commitTag('1.9.6', 'abc1234def'), 'v1.9.6-abc1234');
  assert.equal(releaseTitle('1.9.6', 'abc1234def'), '1.9.6-abc1234');
  // Seven characters of the commit, as the APK's own version name carries.
  assert.equal(releaseTitle('0.1.0', 'FBD613F0000'), '0.1.0-fbd613f');
  assert.ok(COMMIT_TAG_RE.test(commitTag('1.9.6', 'abc1234')));
  assert.ok(!VERSION_TAG_RE.test(commitTag('1.9.6', 'abc1234')), 'a commit tag is never a version tag');
  assert.ok(!COMMIT_TAG_RE.test('v1.9.6'), 'and a version tag is never a commit tag');
  assert.throws(() => commitTag('1.9', 'abc1234'), /--version must be x\.y\.z/);
  assert.throws(() => commitTag('1.9.6', 'main'), /--commit must be a commit id/);
  assert.throws(() => commitTag('1.9.6', ''), /--commit must be a commit id/);
});

// The body of a release is the changelog and nothing else. What the app is, how to install
// an APK, and which Android versions it runs on are the same on every release, so repeating
// them here would be the same paragraphs on every page and would push the one thing that
// differs -- what changed -- off the screen.
test('a release body is the changelog, not a page of instructions', () => {
  const body = releaseBody({
    commit: 'abc1234def',
    sha256: 'a'.repeat(64),
    previous: 'v1.9.5',
    entries: [
      { sha: 'abc1234def', subject: 'feat(chat): keep a draft when the room closes' },
      { sha: 'def5678abc', subject: 'fix(appearance): draw the account\u2019s accent on the rail' },
    ],
    repository: OWNER_REPO,
  });
  assert.match(body, /^## Changes since `v1\.9\.5`/);
  assert.match(body, /^- feat\(chat\): keep a draft when the room closes \(\[`abc1234`\]/m);
  assert.match(body, /- fix\(appearance\)/);
  assert.match(body, new RegExp(`sha256: \`${'a'.repeat(64)}\``));
  assert.match(body, new RegExp(`compare/v1\\.9\\.5\\.\\.\\.abc1234def`), 'a link to the whole diff');
  // Nothing that is the same on every release.
  for (const noise of [/install/i, /download/i, /Android 8\.0/, /API 26/, /adb /, /Harmony\)/, /native Android client/i]) {
    assert.ok(!noise.test(body), `the body repeats ${noise}`);
  }
  // A first release has no previous tag to compare with, and says what changed all the same.
  const first = releaseBody({ commit: 'abc1234', sha256: 'b'.repeat(64), entries: [{ sha: 'abc1234', subject: 'the first commit' }], repository: OWNER_REPO });
  assert.match(first, /^## Changes\n/);
  assert.match(first, /- the first commit/);
  assert.ok(!/compare/.test(first), 'there is nothing to compare with');
  // A release with more commits than it lists says how many it left out.
  const many = releaseBody({ commit: 'abc1234', sha256: 'c'.repeat(64), previous: 'v1.9.5', entries: [{ sha: 'abc1234', subject: 'one' }], more: 12 });
  assert.match(many, /and 12 more commits/);
});

// The changelog is the commits since the previous release, read from the checkout's own
// history. It runs against a scratch repository, so what it reports is a real git history
// rather than a stub.
test('the changelog is the commits since the previous release tag', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'brapmony-git-'));
  const git = (...args) => {
    const r = spawnSync('git', args, { cwd: dir, encoding: 'utf8' });
    assert.equal(r.status, 0, `git ${args.join(' ')}: ${r.stderr}`);
    return r.stdout.trim();
  };
  try {
    git('init', '-q', '-b', 'main');
    git('config', 'user.email', 'test@example.com');
    git('config', 'user.name', 'Test');
    const commit = (subject) => {
      writeFileSync(join(dir, 'file.txt'), `${subject}\n`, { flag: 'a' });
      git('add', 'file.txt');
      git('commit', '-q', '-m', subject);
      return git('rev-parse', 'HEAD');
    };
    const first = commit('feat: the first thing');
    // No release behind this commit yet, so the changelog is the commit itself.
    const beforeAny = changelogEntries({ cwd: dir });
    assert.equal(beforeAny.previous, null);
    assert.deepEqual(beforeAny.entries.map((e) => e.subject), ['feat: the first thing']);
    git('tag', 'v0.1.0', first);
    const second = commit('fix: the second thing');
    commit('docs: the third thing');
    // The changelog starts at the previous release, and is newest first.
    const notes = changelogEntries({ cwd: dir });
    assert.equal(notes.previous, 'v0.1.0');
    assert.deepEqual(notes.entries.map((e) => e.subject), ['docs: the third thing', 'fix: the second thing']);
    assert.deepEqual(notes.entries.map((e) => e.sha.length), [40, 40]);
    // A re-run of a commit that already has a release reports the same changes as the
    // first run: the previous release is the one behind the commit, never the commit's own
    // tag, so its changelog is not empty.
    git('tag', 'v0.1.0-def5678', git('rev-parse', 'HEAD'));
    const rerun = changelogEntries({ cwd: dir });
    assert.equal(rerun.previous, 'v0.1.0', 'the release behind the commit, not the one on it');
    assert.deepEqual(rerun.entries.map((e) => e.subject), ['docs: the third thing', 'fix: the second thing']);
    // A merge commit names the pull request; the commits it brought in are in the range.
    git('checkout', '-q', '-b', 'side', second);
    writeFileSync(join(dir, 'side.txt'), 'a branch\'s work\n');
    git('add', 'side.txt');
    git('commit', '-q', '-m', "feat: a branch's work");
    git('checkout', '-q', 'main');
    git('merge', '-q', '--no-ff', '-m', 'Merge branch side', 'side');
    const merged = changelogEntries({ cwd: dir, from: 'v0.1.0' });
    assert.ok(!merged.entries.some((e) => e.subject.startsWith('Merge ')), 'a merge commit is not a changelog line');
    assert.ok(merged.entries.some((e) => e.subject === 'feat: a branch\'s work'), 'the work it merged is');
    // The limit bounds what a page renders, and what is left out is counted rather than lost.
    const limited = changelogEntries({ cwd: dir, from: 'v0.1.0', limit: 1 });
    assert.equal(limited.entries.length, 1);
    assert.ok(limited.more > 0, 'the commits beyond the limit are counted');
    assert.equal(limited.entries.length + limited.more, merged.entries.length);
    // And the whole body composes from it: the release behind the commit is the one the
    // merge brought in, and the comparison link names it.
    const body = notesFor({ cwd: dir, commit: 'HEAD', sha256: 'd'.repeat(64), repository: OWNER_REPO });
    assert.match(body, /^## Changes since `v0\.1\.0-def5678`/);
    assert.match(body, /- feat: a branch's work/);
    assert.match(body, /compare\/v0\.1\.0-def5678\.\.\.HEAD/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('a publish creates one release for its commit, holding only that build', async () => {
  const ws = workspace();
  try {
    const { gh, stdout, output } = await publish(['--apk', ws.apk, '--commit', 'abc1234']);
    assert.match(stdout, /Created release v1\.9\.6-abc1234/);
    assert.equal(gh.releases.length, 1);
    const release = gh.releases[0];
    assert.equal(release.tag_name, 'v1.9.6-abc1234');
    assert.equal(release.name, '1.9.6-abc1234', 'named for the build, not for a branch');
    assert.equal(release.draft, false, 'and made visible once its files are on it');
    assert.equal(release.prerelease, false);
    assert.equal(release.body, 'the changes of this build');
    // One APK and the checksum beside it. Nothing else: no second copy of the same bytes
    // under a stable name, and no build of another commit.
    const names = release.assets.map((a) => a.name).sort();
    assert.deepEqual(names, ['Brapmony-1.9.6-abc1234-debug.apk', 'Brapmony-1.9.6-abc1234-debug.apk.sha256']);
    // No asset carries a label: GitHub shows a label in place of the file name, so a
    // labelled asset is one a person cannot find by its name on the release page.
    for (const a of release.assets) {
      assert.equal(a.label, '', `${a.name} must have no label, or its name is hidden`);
      assert.equal(a.uploader.login, BOT_LOGIN, 'every upload is attributed to the workflow');
    }
    const expected = sha(readFileSync(ws.apk));
    const checksum = release.assets.find((a) => a.name.endsWith('.sha256'));
    assert.equal(
      Buffer.from(checksum.content, 'base64').toString('utf8'),
      `${expected}  Brapmony-1.9.6-abc1234-debug.apk\n`,
      'the checksum names the file it is beside, so sha256sum -c finds it',
    );
    assert.match(output, /tag=v1\.9\.6-abc1234/);
    assert.match(output, /release-id=\d+/);
    assert.match(output, new RegExp(`sha256=${expected}`));
    assert.match(output, /name=Brapmony-1\.9\.6-abc1234-debug\.apk/);
  } finally {
    await ws.cleanup();
  }
});

test('each commit gets its own release, and one commit keeps one', async () => {
  const first = workspace('abc1234');
  const second = workspace('def5678');
  try {
    const firstRun = await publish(['--apk', first.apk, '--commit', 'abc1234']);
    const again = await publish(['--apk', second.apk, '--commit', 'def5678'], { gh: firstRun.gh });
    assert.match(again.stdout, /Created release v1\.9\.6-def5678/);
    assert.deepEqual(again.gh.releases.map((r) => r.tag_name).sort(), ['v1.9.6-abc1234', 'v1.9.6-def5678']);
    // Neither release carries the other's build: a release is one commit's.
    const older = again.gh.releases.find((r) => r.tag_name === 'v1.9.6-abc1234');
    const newer = again.gh.releases.find((r) => r.tag_name === 'v1.9.6-def5678');
    assert.deepEqual(older.assets.map((a) => a.name).sort(),
      ['Brapmony-1.9.6-abc1234-debug.apk', 'Brapmony-1.9.6-abc1234-debug.apk.sha256']);
    assert.deepEqual(newer.assets.map((a) => a.name).sort(),
      ['Brapmony-1.9.6-def5678-debug.apk', 'Brapmony-1.9.6-def5678-debug.apk.sha256']);
  } finally {
    await first.cleanup();
    await second.cleanup();
  }
});

test('a re-run of the same commit with the same bytes changes nothing', async () => {
  const ws = workspace();
  try {
    const firstRun = await publish(['--apk', ws.apk, '--commit', 'abc1234']);
    const before = firstRun.gh.releases[0].assets.map((a) => `${a.name}:${a.id}`).sort();
    const rerun = await publish(['--apk', ws.apk, '--commit', 'abc1234'], { gh: firstRun.gh });
    assert.match(rerun.stdout, /Kept Brapmony-1\.9\.6-abc1234-debug\.apk/);
    const after = rerun.gh.releases[0].assets.map((a) => `${a.name}:${a.id}`).sort();
    assert.deepEqual(after, before, 'a re-run neither re-uploads nor renames anything');
    assert.equal(rerun.gh.releases.length, 1, 'and creates no second release');
  } finally {
    await ws.cleanup();
  }
});

test('a rebuild of the same commit gets its own name, leaving the earlier bytes where they were', async () => {
  const first = workspace('abc1234', Buffer.from('first build of abc1234'));
  const rebuilt = workspace('abc1234', Buffer.from('second build of abc1234'));
  try {
    const firstRun = await publish(['--apk', first.apk, '--commit', 'abc1234']);
    const before = firstRun.gh.releases[0].assets.find((a) => a.name.endsWith('-abc1234-debug.apk'));
    const again = await publish(['--apk', rebuilt.apk, '--commit', 'abc1234', '--attempt', '2'], { gh: firstRun.gh });
    const names = again.gh.releases[0].assets.map((a) => a.name);
    assert.ok(names.includes('Brapmony-1.9.6-abc1234-debug.apk'), 'the first name is unchanged');
    assert.ok(
      names.includes('Brapmony-1.9.6-abc1234-debug.r2.apk'),
      `the rebuilt bytes have their own name: ${names.join(', ')}`,
    );
    const after = again.gh.releases[0].assets.find((a) => a.name.endsWith('-abc1234-debug.apk'));
    assert.equal(after.id, before.id, 'the earlier asset was not replaced');
    assert.equal(after.content, before.content, 'the earlier bytes are what they were');
    // The checksum is written for the file that is really on the release, so the pair a
    // downloader gets still matches.
    const sums = again.gh.releases[0].assets
      .filter((a) => a.name.endsWith('.sha256'))
      .map((a) => Buffer.from(a.content, 'base64').toString('utf8'));
    assert.ok(
      sums.some((line) => line.includes('Brapmony-1.9.6-abc1234-debug.r2.apk')),
      `the rebuild has its own checksum: ${sums.join(' | ')}`,
    );
  } finally {
    await first.cleanup();
    await rebuilt.cleanup();
  }
});

test('an asset this workflow did not upload is never replaced', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub();
    const { id } = await seedRelease(gh);
    const name = 'Brapmony-1.9.6-abc1234-debug.apk';
    // Somebody attached a file under this build's name by hand: it carries their account,
    // not the workflow's.
    gh.state.uploader = { login: 'a-person', type: 'User' };
    await gh.json('POST', `/releases/${id}/assets?name=${encodeURIComponent(name)}`, {
      body: Buffer.from('hand made'),
      contentType: 'application/octet-stream',
    });
    gh.state.uploader = null;
    await assert.rejects(
      publishAsset(gh, { releaseId: id, file: ws.apk, name }),
      /was not uploaded by this workflow; refusing to replace it/,
    );
    const asset = gh.releases[0].assets.find((a) => a.name === name);
    assert.equal(Buffer.from(asset.content, 'base64').toString('utf8'), 'hand made');
    await gh.close();
  } finally {
    await ws.cleanup();
  }
});

test('a release at the commit tag that carries no build of ours is refused', async () => {
  const ws = workspace();
  try {
    await assert.rejects(
      publish(['--apk', ws.apk, '--commit', 'abc1234'], {
        releases: [{
          id: 55,
          tag_name: 'v1.9.6-abc1234',
          name: 'somebody else\'s release',
          draft: false,
          prerelease: false,
          assets: [
            { id: 5501, name: 'Their_App.apk', label: '', size: 9, state: 'uploaded' },
            { id: 5502, name: 'notes.txt', label: '', size: 9, state: 'uploaded' },
          ],
        }],
      }),
      /carries no build of this workflow's; refusing to publish into it/,
    );
  } finally {
    await ws.cleanup();
  }
});

// The draft is what makes a failed publish harmless: the release exists, but it is not on
// the Releases page and /releases/latest does not offer it, so nobody is sent to a page
// whose download is not there.
test('a publish that fails before its files are on the release leaves it a draft', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub();
    gh.state.failUploads = /abc1234/;
    await assert.rejects(publish(['--apk', ws.apk, '--commit', 'abc1234'], { gh }), /upload failed/);
    assert.equal(gh.releases.length, 1, 'the release was created, so the retry has something to finish');
    assert.equal(gh.releases[0].draft, true, 'and it is not visible to a downloader');
    assert.deepEqual(gh.releases[0].assets, [], 'with no file on it');
    // The next attempt of the same commit finishes the release the first one started.
    gh.state.failUploads = null;
    const done = await publish(['--apk', ws.apk, '--commit', 'abc1234'], { gh });
    assert.match(done.stdout, /Found release v1\.9\.6-abc1234/);
    assert.equal(gh.releases.length, 1, 'not a second release');
    assert.equal(gh.releases[0].draft, false);
    assert.equal(gh.releases[0].assets.length, 2);
  } finally {
    await ws.cleanup();
  }
});

// A release is published only after both files are on it, so a failure between the APK and
// its checksum cannot leave a release that offers a download with no checksum beside it.
test('a release is not made visible until its checksum is on it too', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub();
    gh.state.failUploads = /\.sha256/;
    await assert.rejects(publish(['--apk', ws.apk, '--commit', 'abc1234'], { gh }), /upload failed/);
    assert.equal(gh.releases[0].draft, true, 'the release is still a draft');
    assert.deepEqual(gh.releases[0].assets.map((a) => a.name), ['Brapmony-1.9.6-abc1234-debug.apk']);
  } finally {
    await ws.cleanup();
  }
});

// The failure this exists for: a run whose build succeeded and whose publish died on one
// `500` from the upload endpoint. GitHub answers a healthy request immediately, so a 5xx is
// one request failing rather than a verdict on the request, and a publish that gives up on
// the first of them loses a thirty-minute build over nothing.
test('a request that fails transiently is sent again, and the publish goes through', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub();
    const { id } = await seedRelease(gh);
    const name = 'Brapmony-1.9.6-abc1234-debug.apk';
    // The first upload attempt is answered 500 before the request is served, as a request
    // that never got a usable answer. The second is served.
    gh.state.transient.set(`POST /repos/${OWNER_REPO}/releases/${id}/assets`, 1);
    const build = await publishAsset(gh, { releaseId: id, file: ws.apk, name });
    assert.equal(build.uploaded, true);
    assert.equal(build.name, name);
    const uploads = gh.calls.filter((c) => c.method === 'POST' && c.name === name);
    assert.equal(uploads.length, 2, 'the request was sent twice, not once');
    const asset = gh.releases[0].assets.filter((a) => a.name === name);
    assert.equal(asset.length, 1, 'and only one asset is on the release, not two');
    assert.equal(asset[0].digest, `sha256:${sha(readFileSync(ws.apk))}`);
    await gh.close();
  } finally {
    await ws.cleanup();
  }
});

// The dangerous half of a retry: the request landed and the answer was lost on the way back,
// which is what a proxy or a dropped connection does to an upload. Sending it again must not
// leave two assets, and must not fail the publish on its own 422 -- the release already
// holds exactly the bytes this attempt was uploading, and GitHub's own digest says so.
test('an upload whose answer was lost is not uploaded twice', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub();
    const { id } = await seedRelease(gh);
    const name = 'Brapmony-1.9.6-abc1234-debug.apk';
    // Served, then answered 500. The retry finds the name taken by its own first attempt.
    gh.state.lostAnswers.set(`POST /repos/${OWNER_REPO}/releases/${id}/assets`, 1);
    const build = await publishAsset(gh, { releaseId: id, file: ws.apk, name });
    assert.equal(build.name, name);
    const assets = gh.releases[0].assets.filter((a) => a.name === name);
    assert.equal(assets.length, 1, 'the lost answer did not leave a second asset behind');
    assert.equal(assets[0].digest, `sha256:${sha(readFileSync(ws.apk))}`, 'and the one asset holds the build');
    assert.equal(build.uploaded, true, 'the build is reported as published');
    await gh.close();
  } finally {
    await ws.cleanup();
  }
});

// A retry is bounded: a service that is failing for every attempt must end the publish
// rather than loop until the job times out. What is checked is that it fails with the
// server's own answer, after the attempts it was given.
test('a request that keeps failing ends the publish after a bounded number of attempts', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub();
    const { id } = await seedRelease(gh);
    const name = 'Brapmony-1.9.6-abc1234-debug.apk';
    gh.state.transient.set(`POST /repos/${OWNER_REPO}/releases/${id}/assets`, RETRY_ATTEMPTS);
    await assert.rejects(
      publishAsset(gh, { releaseId: id, file: ws.apk, name }),
      /500/,
    );
    const uploads = gh.calls.filter((c) => c.method === 'POST' && c.name === name);
    assert.equal(uploads.length, RETRY_ATTEMPTS, 'exactly the attempts it was given, and no more');
    assert.ok(!gh.releases[0].assets.some((a) => a.name === name), 'nothing was published');
    await gh.close();
  } finally {
    await ws.cleanup();
  }
});

// A 4xx is an answer about the request, not a failure to answer it: sending it again would
// get the same answer, so it is not retried. Checked on the client itself, because that is
// where the decision is made.
test('a 4xx is an answer, and is not sent again', async () => {
  const gh = await fakeGitHub();
  try {
    const before = gh.calls.length;
    await assert.rejects(gh.json('GET', '/releases/999999'), /404/);
    assert.equal(gh.calls.length - before, 1, 'a 404 was answered once, not five times');
    // And a 422, which is the answer an upload of a taken name gets.
    const dir = mkdtempSync(join(tmpdir(), 'brapmony-422-'));
    const file = join(dir, 'Brapmony-1.9.6-abc1234-debug.apk');
    writeFileSync(file, Buffer.from('bytes'));
    const { id } = await seedRelease(gh);
    gh.state.uploader = { login: 'a-person', type: 'User' };
    await gh.json('POST', `/releases/${id}/assets?name=Brapmony-1.9.6-abc1234-debug.apk`, {
      body: Buffer.from('not ours'),
      contentType: 'application/octet-stream',
    });
    gh.state.uploader = null;
    const attempts = gh.calls.length;
    await assert.rejects(
      publishAsset(gh, { releaseId: id, file, name: 'Brapmony-1.9.6-abc1234-debug.apk' }),
      /was not uploaded by this workflow; refusing to replace it/,
    );
    const uploads = gh.calls.slice(attempts).filter((c) => c.method === 'POST' && c.name);
    assert.equal(uploads.length, 0, 'the refusal was read off the asset list, and nothing was uploaded over it');
    rmSync(dir, { recursive: true, force: true });
  } finally {
    await gh.close();
  }
});

// An answer that arrives as headers and then stops: a connection that dropped part way
// through the body. Nothing usable was read, so it is the same failure as no connection at
// all, and the same retry -- a body parsed from half an answer would be worse than none.
test('an answer cut off part way through is sent again', async () => {
  const gh = await fakeGitHub();
  try {
    const { id } = await seedRelease(gh);
    gh.state.cutOff.set(`GET /repos/${OWNER_REPO}/releases/${id}`, 1);
    const release = await gh.json('GET', `/releases/${id}`);
    assert.equal(release.id, id, 'the retried read got the whole answer');
    const reads = gh.calls.filter((c) => c.method === 'GET' && c.path.endsWith(`/releases/${id}`));
    assert.equal(reads.length, 2, 'the read was sent twice, and the second answer is the one used');
  } finally {
    await gh.close();
  }
});

// A connection that is refused, or a name that does not resolve: nothing was sent, so there
// is no answer to act on and the same retry applies. A publish that is a few calls after a
// thirty-minute build should not die because one of them met a closed port.
test('a request that cannot connect is sent again', async () => {
  const gh = await fakeGitHub();
  const url = gh.url;
  try {
    await gh.close();
    // Nothing is listening now. The client is given a single attempt, so what is checked is
    // that the failure is reported as one rather than that it waits for a server to return.
    const dead = client({ token: 't', repository: OWNER_REPO, apiUrl: url, attempts: 1, backoff: [0], log: () => {} });
    await assert.rejects(dead.json('GET', '/releases'), /GET \/releases: /);
    // And with more attempts it is tried the number of times it was given, then reported.
    const tries = [];
    const retrying = client({
      token: 't', repository: OWNER_REPO, apiUrl: url, attempts: 3, backoff: [0],
      sleep: async (ms) => { tries.push(ms); },
      log: () => {},
    });
    await assert.rejects(retrying.json('GET', '/releases'));
    assert.equal(tries.length, 2, 'three attempts, so two waits between them');
  } finally {
    await gh.close().catch(() => {});
  }
});

// The rolling release's own create, retried after its answer was lost: the second attempt is
// answered 422 because the tag is now taken, and the release the first attempt made is read
// back rather than reported as a failure. What it must not do is create a second release.
test('a release create whose answer was lost does not fail the publish, or make a second release', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub();
    gh.state.lostAnswers.set(`POST /repos/${OWNER_REPO}/releases`, 1);
    const { stdout } = await publish(['--apk', ws.apk, '--commit', 'abc1234'], { gh });
    assert.equal(gh.releases.length, 1, 'one release, not two');
    assert.equal(gh.releases[0].tag_name, 'v1.9.6-abc1234');
    assert.match(stdout, /Created release v1\.9\.6-abc1234/);
    // The build still went on: the files are on the release the first attempt made, and it
    // was made visible, because the read-back is the release as it really is rather than a
    // create that was assumed to have happened.
    assert.deepEqual(
      gh.releases[0].assets.map((a) => a.name).sort(),
      ['Brapmony-1.9.6-abc1234-debug.apk', 'Brapmony-1.9.6-abc1234-debug.apk.sha256'],
    );
    assert.equal(gh.releases[0].draft, false, 'and it is published');
    assert.equal(gh.releases[0].name, '1.9.6-abc1234');
  } finally {
    await ws.cleanup();
  }
});

// The same for a version release, which release.yml creates before it attaches the APK: a
// retried create reads the release back by tag instead of failing the tag's build.
test('a version release whose create answer was lost is found again, not refused', async () => {
  const gh = await fakeGitHub();
  try {
    gh.state.lostAnswers.set(`POST /repos/${OWNER_REPO}/releases`, 1);
    const out = join(mkdtempSync(join(tmpdir(), 'brapmony-tag-')), 'output');
    writeFileSync(out, '');
    const { stdout } = await run(process.execPath, [join(here, 'release.mjs'), 'tag', '--tag', 'v1.9.6'], {
      env: { ...process.env, ...FAST_RETRIES, GITHUB_TOKEN: 't', GITHUB_REPOSITORY: OWNER_REPO, GITHUB_API_URL: gh.url, GITHUB_OUTPUT: out },
    });
    assert.match(stdout, /Created release v1\.9\.6/);
    assert.equal(gh.releases.length, 1, 'one release, not two');
    assert.equal(gh.releases[0].tag_name, 'v1.9.6');
    assert.match(readFileSync(out, 'utf8'), new RegExp(`id=${gh.releases[0].id}\\n`),
      'the id it prints is the release that is really there');
  } finally {
    await gh.close();
  }
});

test('only a failure to answer is transient, and a 4xx is not', () => {
  for (const status of [500, 502, 503, 504, 429, 408]) {
    assert.ok(isTransientStatus(status), `${status} is a failure to answer`);
  }
  for (const status of [400, 401, 403, 404, 409, 422, 200, 201, 204]) {
    assert.ok(!isTransientStatus(status), `${status} is an answer, and is not retried`);
  }
});

// The waits are what a run in CI gets when nothing overrides them. An unset environment
// variable must not read as a backoff of nothing -- `''.split(',')` is one empty item and
// `Number('')` is 0 -- or every retry would fire at once, which is the opposite of what the
// wait is for.
test('an unset retry setting falls back to the waits the publisher ships with', () => {
  assert.deepEqual(retrySettings({}), { attempts: RETRY_ATTEMPTS, backoff: RETRY_BACKOFF_MS });
  assert.deepEqual(retrySettings({ HARMONY_RETRY_BACKOFF_MS: '' }).backoff, RETRY_BACKOFF_MS);
  assert.deepEqual(retrySettings({ HARMONY_RETRY_BACKOFF_MS: ' , ' }).backoff, RETRY_BACKOFF_MS);
  // What is set is used, including a wait of nothing, which is what the tests run with.
  assert.deepEqual(retrySettings({ HARMONY_RETRY_BACKOFF_MS: '0' }).backoff, [0]);
  assert.deepEqual(retrySettings({ HARMONY_RETRY_BACKOFF_MS: '10,20' }).backoff, [10, 20]);
  assert.equal(retrySettings({ HARMONY_RETRY_ATTEMPTS: '3' }).attempts, 3);
  // A value that names no number is not a setting.
  assert.equal(retrySettings({ HARMONY_RETRY_ATTEMPTS: 'lots' }).attempts, RETRY_ATTEMPTS);
  assert.equal(retrySettings({ HARMONY_RETRY_ATTEMPTS: '0' }).attempts, RETRY_ATTEMPTS);
  // And the shipped waits grow, so a service that is overwhelmed gets the calls thinned out.
  for (let i = 1; i < RETRY_BACKOFF_MS.length; i += 1) {
    assert.ok(RETRY_BACKOFF_MS[i] > RETRY_BACKOFF_MS[i - 1], 'each wait is longer than the one before');
  }
  // In all, well inside the publish job's fifteen-minute timeout.
  const total = RETRY_BACKOFF_MS.slice(0, RETRY_ATTEMPTS - 1).reduce((a, b) => a + b, 0);
  assert.ok(total < 60_000, `the waits add up to ${total} ms, which is too long beside a build`);
});

test('a retry-after the response names is honoured, within a bound', () => {
  assert.equal(retryAfterMs('3'), 3000);
  assert.equal(retryAfterMs(null), null);
  assert.equal(retryAfterMs(''), null);
  assert.equal(retryAfterMs('soon'), null);
  // A value larger than a job can afford is capped rather than waited out.
  assert.equal(retryAfterMs('86400'), 60_000);
  const now = Date.parse('2026-01-01T00:00:00Z');
  assert.equal(retryAfterMs('Thu, 01 Jan 2026 00:00:05 GMT', now), 5000);
  // A date in the past is no wait at all.
  assert.equal(retryAfterMs('Thu, 01 Jan 2026 00:00:00 GMT', now + 10_000), 0);
});

// GitHub computes a SHA-256 over every asset it stores and reports it as `digest`. It is
// the one digest the publisher did not compute itself, so it is the one check that the
// file a downloader will get is the file this run built. A mismatch fails the publish
// before the stable name moves, so nothing that is already published is disturbed.
test('the asset GitHub stored is checked against the file that was uploaded', async () => {
  const first = workspace('abc1234');
  const second = workspace('def5678');
  try {
    const firstRun = await publish(['--apk', first.apk, '--commit', 'abc1234']);
    const before = firstRun.gh.releases[0].assets.map((a) => `${a.name}:${a.id}`).sort();
    // The next build's bytes are stored as something else: a proxy or a truncated upload.
    await assert.rejects(
      publish(['--apk', second.apk, '--commit', 'def5678'], {
        gh: firstRun.gh,
        corruptUploads: /def5678/,
      }),
      /GitHub stored Brapmony-1\.9\.6-def5678-debug\.apk as sha256:[0-9a-f]{64}, but the file hashes to sha256:[0-9a-f]{64}/,
    );
    // The published release is exactly what it was: a publish that failed leaves the last
    // good download where it is.
    const after = firstRun.gh.releases[0].assets.map((a) => `${a.name}:${a.id}`).sort();
    assert.deepEqual(after, before, 'the release that was already published is untouched');
    const failed = firstRun.gh.releases.find((r) => r.tag_name === 'v1.9.6-def5678');
    assert.equal(failed.draft, true, 'and the failed one is not offered to anybody');
  } finally {
    await first.cleanup();
    await second.cleanup();
  }
});

// The digest is compared, not recorded: an API that reports none leaves nothing to
// compare, and the upload is accepted as it came back. GitHub reports one for every
// asset, so this is what keeps the check from becoming a failure of the publish on a
// server that does not model it (an older GitHub Enterprise, the local stand-in). The
// byte count is what is left to compare then, and it is GitHub's too.
test('a stored asset without a digest is not a failure', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub();
    gh.state.digests = false;
    const { id } = await seedRelease(gh);
    const name = 'Brapmony-1.9.6-abc1234-debug.apk';
    const uploaded = await gh.json('POST', `/releases/${id}/assets?name=${encodeURIComponent(name)}`, {
      body: readFileSync(ws.apk),
      contentType: 'application/octet-stream',
    });
    assert.equal(uploaded.digest, undefined, 'the stand-in answered without a digest');
    // The same bytes are already there under that name, so nothing is re-uploaded, and
    // with no digest to compare the publish continues. The byte count is what is left to
    // compare, and it is GitHub's too.
    const build = await publishAsset(gh, { releaseId: id, file: ws.apk, name });
    assert.equal(build.uploaded, false);
    assert.equal(build.asset.id, uploaded.id);
    const again = await publishAsset(gh, { releaseId: id, file: ws.apk, name });
    assert.equal(again.asset.id, uploaded.id, 'a second call is a no-op, as a re-run must be');
    await gh.close();
  } finally {
    await ws.cleanup();
  }
});

// A re-run finds its own asset by name and compares it with the file in hand using
// GitHub's digest of the stored bytes. When they differ, the bytes already published are
// not the ones this run built, so the publish must not report "kept": the new bytes go
// under their own name, and the name a link already points at keeps resolving to what it
// resolved to before.
test('a re-run whose stored bytes are not the build gets its own name, not a silent success', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub();
    const { id } = await seedRelease(gh);
    const name = 'Brapmony-1.9.6-abc1234-debug.apk';
    const uploaded = await gh.json('POST', `/releases/${id}/assets?name=${encodeURIComponent(name)}`, {
      body: readFileSync(ws.apk),
      contentType: 'application/octet-stream',
    });
    assert.equal(uploaded.uploader.login, BOT_LOGIN, 'the stored asset is this workflow\'s');
    // What is stored is not what this run built: the digest GitHub computed says so. The
    // asset list the publisher reads is where the digest comes from.
    gh.releases[0].assets.find((a) => a.id === uploaded.id).digest = `sha256:${'c'.repeat(64)}`;
    const build = await publishAsset(gh, { releaseId: id, file: ws.apk, name, attempt: 2 });
    assert.equal(build.uploaded, true, 'the bytes in hand are published, not assumed to be there');
    assert.equal(build.name, 'Brapmony-1.9.6-abc1234-debug.r2.apk', 'under a name of their own');
    // The earlier name was not replaced, so whatever a link to it resolves to still does.
    const earlier = gh.releases[0].assets.find((a) => a.id === uploaded.id);
    assert.equal(earlier.name, name);
    assert.equal(earlier.content, uploaded.content);
    // And GitHub's digest of the new asset is the file that was uploaded.
    const fresh = gh.releases[0].assets.find((a) => a.name === build.name);
    assert.equal(fresh.digest, `sha256:${sha(readFileSync(ws.apk))}`);
    await gh.close();
  } finally {
    await ws.cleanup();
  }
});

// A release per commit would grow without bound: one APK for every push, for ever. The
// window is on the releases, and what it drops is the file, not the page: the changelog is
// the record of the commit, and the build is reproducible from it.
test('releases beyond the window lose their APK, and keep their changelog', async () => {
  const gh = await fakeGitHub();
  try {
    // Five releases, each with an APK and its checksum, newest last.
    for (let i = 0; i < 5; i += 1) {
      const commit = `abc000${i}`;
      const release = await ensureCommitRelease(gh, {
        version: '1.9.6', commit, name: releaseTitle('1.9.6', commit), body: `the changes of ${commit}`,
      });
      await visible(gh, release.id);
      await gh.json('POST', `/releases/${release.id}/assets?name=${encodeURIComponent(`Brapmony-1.9.6-${commit}-debug.apk`)}`, {
        body: Buffer.from(`build ${i}`),
        contentType: 'application/octet-stream',
      });
      await gh.json('POST', `/releases/${release.id}/assets?name=${encodeURIComponent(`Brapmony-1.9.6-${commit}-debug.apk.sha256`)}`, {
        body: Buffer.from(`${sha(Buffer.from(`build ${i}`))}  Brapmony-1.9.6-${commit}-debug.apk\n`),
        contentType: 'application/octet-stream',
      });
    }
    const pruned = await pruneOldReleaseAssets(gh, { keep: 2 });
    assert.equal(pruned.length, 6, 'three releases beyond the window, two files each');
    assert.equal(gh.releases.length, 5, 'no release is deleted: the page is the record');
    const kept = gh.releases.slice(-2);
    for (const release of kept) assert.equal(release.assets.length, 2, `${release.tag_name} keeps its files`);
    for (const release of gh.releases.slice(0, 3)) {
      assert.deepEqual(release.assets, [], `${release.tag_name} lost its APK`);
      assert.match(release.body, /the changes of/, 'and still says what the commit was');
    }
    // A version release is not in this window at all: it is the archive, and nothing here
    // touches it.
    assert.ok(!pruned.some((name) => name.includes('V1.9.6')));
    await assert.rejects(pruneOldReleaseAssets(gh, { keep: 0 }), /--keep must be at least 1/);
  } finally {
    await gh.close();
  }
});

// Ownership without a label: the uploader says a workflow put the file there, and the name
// says which workflow. The account alone is not enough -- every workflow in this repository
// uploads as the same bot -- so a file another workflow uploaded must not be pruned or
// replaced by this one, even though it is a bot's.
test('a bot upload under a name this workflow does not generate is not its own', async () => {
  const gh = await fakeGitHub();
  const ws = workspace();
  try {
    // An old release with another workflow's file on it, beside its own.
    const old = await ensureCommitRelease(gh, { version: '1.9.6', commit: 'abc0001', name: releaseTitle('1.9.6', 'abc0001'), body: 'x' });
    await visible(gh, old.id);
    for (const name of ['Release-Tool-1.0.0.apk', 'checksums.txt', 'Brapmony-1.9.6-abc0001-debug.apk']) {
      await gh.json('POST', `/releases/${old.id}/assets?name=${encodeURIComponent(name)}`, {
        body: Buffer.from('a file'),
        contentType: 'application/octet-stream',
      });
    }
    const newer = await ensureCommitRelease(gh, { version: '1.9.6', commit: 'abc0002', name: releaseTitle('1.9.6', 'abc0002'), body: 'y' });
    await visible(gh, newer.id);
    const pruned = await pruneOldReleaseAssets(gh, { keep: 1 });
    assert.deepEqual(pruned, ['Brapmony-1.9.6-abc0001-debug.apk'], 'only this workflow\'s own file went');
    const names = gh.releases.find((r) => r.id === old.id).assets.map((a) => a.name).sort();
    assert.deepEqual(names, ['Release-Tool-1.0.0.apk', 'checksums.txt'], 'another workflow\'s files survive');
    // And the same rule holds when it is a name this workflow would replace: a file attached
    // by a person under a name this one publishes is not replaced either.
    gh.state.uploader = { login: 'a-person', type: 'User' };
    await gh.json('POST', `/releases/${newer.id}/assets?name=${encodeURIComponent('Brapmony-1.9.6-abc0002-debug.apk')}`, {
      body: Buffer.from('a person put this here'),
      contentType: 'application/octet-stream',
    });
    gh.state.uploader = null;
    await assert.rejects(
      publishAsset(gh, { releaseId: newer.id, file: ws.apk, name: 'Brapmony-1.9.6-abc0002-debug.apk' }),
      /was not uploaded by this workflow; refusing to replace it/,
    );
  } finally {
    await ws.cleanup();
    await gh.close();
  }
});

// The first publish after this change finds the rolling releases the scheme this replaced
// left behind: one per branch, ten builds each and a `Brapmony-latest.apk` beside them,
// labelled so their names are hidden. That is the release page somebody sees now, and what
// makes a downloader guess which file to take, so it is removed rather than added to.
test('the first publish removes the rolling releases and tags, and leaves the version releases', async () => {
  const ws = workspace();
  try {
    const old = `${LEGACY_OWNER} sha256:${'b'.repeat(64)}`;
    const gh = await fakeGitHub({
      releases: [
        {
          id: 453200307,
          tag_name: `${LEGACY_ROLLING_PREFIX}main`,
          name: 'Android (rolling) main',
          body: 'Rolling build of `main` for the Android client.',
          draft: false,
          prerelease: false,
          target_commitish: '606ab10ff2c37b983d482607303d53d19ff31845',
          assets: [
            { id: 1, name: 'Brapmony_Android_latest.apk', label: old, size: 9, state: 'uploaded' },
            { id: 2, name: 'Brapmony_Android_latest.apk.sha256', label: old, size: 9, state: 'uploaded' },
            { id: 3, name: 'Brapmony_Android_V1.9.6_debug-main-606ab10.apk', label: old, size: 9, state: 'uploaded' },
            { id: 4, name: 'Brapmony-1.9.6-85332c5-debug.apk', label: old, size: 9, state: 'uploaded' },
          ],
        },
        {
          id: 453200308,
          tag_name: `${LEGACY_ROLLING_PREFIX}task-build-native-android-app-with-parity`,
          name: 'Android (rolling) task/build-native-android-app-with-parity',
          body: 'Rolling build of a task branch.',
          draft: false,
          prerelease: true,
          assets: [{ id: 5, name: 'Brapmony-latest.apk', label: '', size: 9, state: 'uploaded' }],
        },
        versionRelease(),
      ],
    });
    gh.refs.set(`${LEGACY_ROLLING_PREFIX}main`, '606ab10ff2c37b983d482607303d53d19ff31845');
    // A rolling tag whose release was deleted by hand, which is only in the tag list.
    gh.refs.set(`${LEGACY_ROLLING_PREFIX}gone`, 'a'.repeat(40));
    gh.refs.set('v1.9.6', 'b'.repeat(40));

    const { stdout } = await publish(['--apk', ws.apk, '--commit', 'abc1234'], { gh });
    // This commit has its own release, holding only its own build.
    const release = gh.releases.find((r) => r.tag_name === 'v1.9.6-abc1234');
    assert.ok(release, 'the commit has a release');
    assert.deepEqual(release.assets.map((a) => a.name).sort(),
      ['Brapmony-1.9.6-abc1234-debug.apk', 'Brapmony-1.9.6-abc1234-debug.apk.sha256']);
    // And the rolling releases and tags are gone, so the one file on the page is a build.
    assert.deepEqual(
      gh.releases.map((r) => r.tag_name).sort(),
      ['v1.9.6', 'v1.9.6-abc1234'],
      'the old rolling releases are removed and the version release is not',
    );
    assert.ok(![...gh.refs.keys()].some((tag) => tag.startsWith(LEGACY_ROLLING_PREFIX)),
      `a rolling tag is still there: ${[...gh.refs.keys()].join(', ')}`);
    assert.ok(gh.refs.has('v1.9.6'), 'and the version tag is not touched');
    assert.match(stdout, new RegExp(`Deleted the old rolling release ${LEGACY_ROLLING_PREFIX}main`));
    assert.match(stdout, new RegExp(`Deleted the old rolling release ${LEGACY_ROLLING_PREFIX}task-`));
    assert.match(stdout, /Deleted the old rolling tag android-rolling-gone/);
    // The version release and its assets are exactly as they were.
    const version = gh.releases.find((r) => r.tag_name === 'v1.9.6');
    assert.deepEqual(
      version.assets.map((a) => a.name).sort(),
      ['Brapmony-1.9.6.apk', 'Brapmony_Android_V1.9.6.apk', 'Other_Tool_V1.9.6.apk', 'latest.json'],
    );
  } finally {
    await ws.cleanup();
  }
});

// Only a rolling tag is ever deleted. A commit tag and a version tag are what the releases
// above are addressed by, and a sweep that reached one would take a download link with it.
test('only a rolling tag or release is ever retired', async () => {
  const gh = await fakeGitHub({
    releases: [
      versionRelease(),
      { id: 90, tag_name: 'v1.9.6-abc1234', name: '1.9.6-abc1234', draft: false, prerelease: false, assets: [] },
    ],
  });
  try {
    gh.refs.set('v1.9.6', 'b'.repeat(40));
    gh.refs.set('v1.9.6-abc1234', 'c'.repeat(40));
    const retired = await retireRollingScheme(gh, {});
    assert.deepEqual(retired, [], 'nothing that is not a rolling release or tag is retired');
    assert.deepEqual(gh.releases.map((r) => r.tag_name).sort(), ['v1.9.6', 'v1.9.6-abc1234']);
    assert.deepEqual([...gh.refs.keys()].sort(), ['v1.9.6', 'v1.9.6-abc1234']);
  } finally {
    await gh.close();
  }
});

// A publish is one commit's. It may not move, retitle or add a file to the release a version
// tag names: that is the archive, cut deliberately, and nothing a push does.
test('a commit publish leaves a version release and its assets alone', async () => {
  const ws = workspace();
  try {
    const { gh } = await publish(['--apk', ws.apk, '--commit', 'abc1234'], {
      releases: [versionRelease()],
    });
    const version = gh.releases.find((r) => r.tag_name === 'v1.9.6');
    assert.deepEqual(
      version.assets.map((a) => a.name).sort(),
      ['Brapmony-1.9.6.apk', 'Brapmony_Android_V1.9.6.apk', 'Other_Tool_V1.9.6.apk', 'latest.json'],
    );
    assert.equal(version.name, 'Brapmony 1.9.6');
    assert.equal(version.draft, false);
    const deletes = gh.calls.filter((c) => c.method === 'DELETE');
    assert.ok(deletes.every((c) => !String(c.path).includes('/41/')), 'nothing of the version release is deleted');
  } finally {
    await ws.cleanup();
  }
});

// GitHub shows an asset's label **in place of its file name** on the release page. Nothing
// this workflow uploads may carry one: a labelled asset is one a person cannot find by name.
test('no asset this workflow publishes carries a label, so every name is visible', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub();
    await publish(['--apk', ws.apk, '--commit', 'abc1234'], { gh });
    const release = gh.releases[0];
    for (const a of release.assets) {
      assert.equal(a.label, '', `${a.name} carries a label, which hides its name on the page`);
    }
    // Nothing the publisher sends asks for a label in the first place.
    const uploads = gh.calls.filter((c) => c.method === 'POST' && c.name);
    assert.ok(uploads.length, 'the publish uploaded something');
    for (const call of uploads) assert.equal(call.label, null, `${call.name} was uploaded with a label`);
  } finally {
    await ws.cleanup();
  }
});

test('a dry run contacts nothing and says what it would publish', async () => {
  const ws = workspace();
  try {
    const { stdout } = await run(process.execPath, [join(here, 'release.mjs'), 'publish',
      '--apk', ws.apk, '--version', '1.9.6', '--commit', 'abc1234', '--dry-run'], {
      env: { ...process.env, GITHUB_TOKEN: '', GITHUB_REPOSITORY: OWNER_REPO },
    });
    assert.match(stdout, /Would publish Brapmony-1\.9\.6-abc1234-debug\.apk as 1\.9\.6-abc1234 \(v1\.9\.6-abc1234\)/);
  } finally {
    await ws.cleanup();
  }
});

test('an upload into a version release replaces only the names it owns', async () => {
  const ws = workspace();
  const dir = mkdtempSync(join(tmpdir(), 'brapmony-up-'));
  try {
    const gh = await fakeGitHub({ releases: [versionRelease()] });
    const apk = join(dir, 'Brapmony-1.9.6.apk');
    writeFileSync(apk, Buffer.from('release apk'));
    const { stdout } = await run(process.execPath, [join(here, 'release.mjs'), 'upload', '--release-id', '41', apk], {
      env: { ...process.env, GITHUB_TOKEN: 't', GITHUB_REPOSITORY: OWNER_REPO, GITHUB_API_URL: gh.url },
    });
    assert.match(stdout, /Uploaded Brapmony-1\.9\.6\.apk to release 41/);
    const names = gh.releases[0].assets.map((a) => a.name).sort();
    // The name it uploaded is replaced; the one from before the scheme changed, and the
    // file that was never this workflow's, are left where they are.
    assert.deepEqual(names, ['Brapmony-1.9.6.apk', 'Brapmony_Android_V1.9.6.apk', 'Other_Tool_V1.9.6.apk', 'latest.json']);
    await gh.close();
  } finally {
    rmSync(dir, { recursive: true, force: true });
    await ws.cleanup();
  }
});

test('an upload refuses to replace a release asset it did not put there', async () => {
  const ws = workspace();
  const dir = mkdtempSync(join(tmpdir(), 'brapmony-up-'));
  try {
    const gh = await fakeGitHub({ releases: [versionRelease()] });
    // A name this workflow never uploads, so it is somebody else's asset.
    const apk = join(dir, 'Other_Tool_V1.9.6.apk');
    writeFileSync(apk, Buffer.from('not ours to replace'));
    await assert.rejects(
      run(process.execPath, [join(here, 'release.mjs'), 'upload', '--release-id', '41', apk], {
        env: { ...process.env, GITHUB_TOKEN: 't', GITHUB_REPOSITORY: OWNER_REPO, GITHUB_API_URL: gh.url },
      }),
      /was not uploaded by this workflow; refusing to replace it/,
    );
    const asset = gh.releases[0].assets.find((a) => a.name === 'Other_Tool_V1.9.6.apk');
    assert.equal(asset.id, 4101, 'the other asset was not touched');
    await gh.close();
  } finally {
    rmSync(dir, { recursive: true, force: true });
    await ws.cleanup();
  }
});

// The tag command is what release.yml calls before it attaches the APK. Its job is to
// make a tag's release exist exactly once, and never to reach into a rolling one.
async function tagCommand(args, gh) {
  const out = join(mkdtempSync(join(tmpdir(), 'brapmony-tag-')), 'output');
  writeFileSync(out, '');
  const { stdout } = await run(process.execPath, [join(here, 'release.mjs'), 'tag', ...args], {
    env: {
      ...process.env,
      GITHUB_TOKEN: 'test-token',
      GITHUB_REPOSITORY: OWNER_REPO,
      GITHUB_API_URL: gh.url,
      GITHUB_OUTPUT: out,
    },
  });
  return { stdout, output: readFileSync(out, 'utf8') };
}

test('a tag creates its release once, as a draft, and re-running the tag finds the same one', async () => {
  const gh = await fakeGitHub();
  try {
    const first = await tagCommand(['--tag', 'v1.9.6', '--body', 'what changed for 1.9.6'], gh);
    assert.match(first.stdout, /Created release v1\.9\.6/);
    assert.equal(gh.releases.length, 1);
    assert.equal(gh.releases[0].tag_name, 'v1.9.6');
    assert.equal(gh.releases[0].name, '1.9.6');
    assert.equal(gh.releases[0].body, 'what changed for 1.9.6', 'the body is the changelog, not instructions');
    assert.equal(gh.releases[0].prerelease, false, 'a version release is not a prerelease');
    // A draft until its APK is on it: a tag whose build fails offers nobody a download that
    // is not there. The upload step is what makes it visible (publish-release).
    assert.equal(gh.releases[0].draft, true, 'created as a draft');
    const id = gh.releases[0].id;
    assert.match(first.output, new RegExp(`id=${id}\\n`), 'the id goes to GITHUB_OUTPUT');

    const second = await tagCommand(['--tag', 'v1.9.6', '--body', 'what changed for 1.9.6'], gh);
    assert.match(second.stdout, /Found release v1\.9\.6/);
    assert.equal(gh.releases.length, 1, 'a second run creates no second release');
    assert.match(second.output, new RegExp(`id=${id}\\n`), 'and names the release that exists');

    // The step that follows the upload: the release is made visible, and says what changed.
    await run(process.execPath, [join(here, 'release.mjs'), 'publish-release', '--release-id', String(id)], {
      env: { ...process.env, GITHUB_TOKEN: 't', GITHUB_REPOSITORY: OWNER_REPO, GITHUB_API_URL: gh.url },
    });
    assert.equal(gh.releases[0].draft, false, 'the release is published');
  } finally {
    await gh.close();
  }
});

// A release found on a re-run gets this run's notes: they name the commits since the
// previous release, which is not what a body written when the tag was first cut says.
test('a re-run of a tag brings its notes up to date', async () => {
  const gh = await fakeGitHub();
  try {
    await tagCommand(['--tag', 'v1.9.6', '--body', 'the first draft of the notes'], gh);
    await tagCommand(['--tag', 'v1.9.6', '--body', 'the notes as they are now'], gh);
    assert.equal(gh.releases.length, 1);
    assert.equal(gh.releases[0].body, 'the notes as they are now');
  } finally {
    await gh.close();
  }
});

test('a tag is only ever vX.Y.Z, so a rolling tag can never become a version release', async () => {
  const gh = await fakeGitHub();
  try {
    for (const tag of [`${LEGACY_ROLLING_PREFIX}master`, 'master', 'v1.9', 'v1.9.6-rc1']) {
      await assert.rejects(
        tagCommand(['--tag', tag], gh),
        /--tag must be vX\.Y\.Z/,
        `${tag} must be refused`,
      );
    }
    // No tag at all is a different mistake, and is refused before any request.
    await assert.rejects(tagCommand(['--tag', ''], gh), /--tag is required/);
    assert.equal(gh.releases.length, 0, 'nothing was created');
  } finally {
    await gh.close();
  }
});

test('a version release never carries a commit tag, and a rolling tag is refused', async () => {
  const gh = await fakeGitHub();
  try {
    // A commit tag is a different namespace: the version path must not adopt one.
    await assert.rejects(ensureTagRelease(gh, { tag: 'v1.9.6-abc1234' }), /--tag must be vX\.Y\.Z/);
    // And the rolling tags of the scheme this replaced are not version tags either.
    await assert.rejects(ensureTagRelease(gh, { tag: 'android-rolling-main' }), /--tag must be vX\.Y\.Z/);
    assert.equal(gh.releases.length, 0, 'nothing was created');
  } finally {
    await gh.close();
  }
});

// The cleanup command deletes nothing without --apply: it lists what it would remove, so a
// dispatch that was meant as a dry run cannot take a download down.
test('cleanup lists without --apply and deletes with it', async () => {
  const gh = await fakeGitHub({
    releases: [
      // Three of this workflow's own releases, and one the version tag names.
      { id: 80, tag_name: 'v1.9.6-aaa0001', name: '1.9.6-aaa0001', draft: false, prerelease: false, assets: [
        { id: 8001, name: 'Brapmony-1.9.6-aaa0001-debug.apk', label: '', size: 9, state: 'uploaded', uploader: { login: BOT_LOGIN } },
        { id: 8002, name: 'Brapmony-1.9.6-aaa0001-debug.apk.sha256', label: '', size: 9, state: 'uploaded', uploader: { login: BOT_LOGIN } },
      ] },
      { id: 81, tag_name: 'v1.9.6-aaa0002', name: '1.9.6-aaa0002', draft: false, prerelease: false, assets: [
        { id: 8101, name: 'Brapmony-1.9.6-aaa0002-debug.apk', label: '', size: 9, state: 'uploaded', uploader: { login: BOT_LOGIN } },
      ] },
      rollingRelease('main', { id: 71, prerelease: false }),
      versionRelease(),
    ],
  });
  try {
    gh.state.artifacts = [
      { id: 1, name: 'old-artifact', size_in_bytes: 10 },
      { id: 2, name: 'newest-artifact', size_in_bytes: 10 },
    ];
    const env = { ...process.env, GITHUB_TOKEN: 'test-token', GITHUB_REPOSITORY: OWNER_REPO, GITHUB_API_URL: gh.url };
    const args = [join(here, 'release.mjs'), 'cleanup', '--keep-releases', '1', '--keep-artifacts', '1'];

    const dry = await run(process.execPath, args, { env });
    assert.match(dry.stdout, /Nothing deleted/);
    assert.match(dry.stdout, /v1\.9\.6-aaa0001/, 'the dry run names what it would take');
    assert.match(dry.stdout, /Old rolling releases to remove: android-rolling-main/);
    assert.equal(gh.releases.length, 4, 'a dry run deletes nothing');
    assert.equal(gh.state.artifacts.length, 2);

    const applied = await run(process.execPath, [...args, '--apply'], { env });
    assert.match(applied.stdout, /Deleted 2 release asset/);
    assert.match(applied.stdout, /Deleted the old rolling release android-rolling-main/);
    // The release that is inside the window keeps its file; the ones beyond it keep their
    // page and lose the APK.
    const inside = gh.releases.find((r) => r.tag_name === 'v1.9.6-aaa0002');
    assert.equal(inside.assets.length, 1, 'the newest release keeps its APK');
    const beyond = gh.releases.find((r) => r.tag_name === 'v1.9.6-aaa0001');
    assert.deepEqual(beyond.assets, [], 'an older release loses its APK');
    assert.ok(beyond, 'and stays on the page');
    // The rolling release and its tag are gone; the version release and its tag are not.
    assert.ok(!gh.releases.some((r) => r.tag_name.startsWith(LEGACY_ROLLING_PREFIX)));
    assert.ok(![...gh.refs.keys()].some((tag) => tag.startsWith(LEGACY_ROLLING_PREFIX)));
    assert.ok(gh.releases.some((r) => r.tag_name === 'v1.9.6'));
    assert.equal(gh.releases.find((r) => r.tag_name === 'v1.9.6').assets.length, 4,
      'the version release is the archive, and keeps its files');
    assert.deepEqual(gh.state.artifacts.map((a) => a.name), ['newest-artifact'], 'only the newest artifact is kept');
  } finally {
    await gh.close();
  }
});

// The oldest artifacts are the ones removed: an artifact is the record of a run, and the
// newest few are the ones worth reading back.
test('artifact retention keeps the newest and deletes the oldest', async () => {
  const gh = await fakeGitHub();
  try {
    gh.state.artifacts = [
      { id: 1, name: 'oldest', size_in_bytes: 5 },
      { id: 2, name: 'older', size_in_bytes: 5 },
      { id: 3, name: 'newer', size_in_bytes: 5 },
      { id: 4, name: 'newest', size_in_bytes: 5 },
    ];
    const old = await oldArtifacts(gh, { keep: 2 });
    assert.deepEqual(old.map((a) => a.name), ['older', 'oldest']);
    for (const artifact of old) await deleteArtifact(gh, artifact);
    assert.deepEqual(gh.state.artifacts.map((a) => a.name).sort(), ['newer', 'newest']);
    // Nothing is deleted when there are not more than `keep` of them.
    assert.deepEqual(await oldArtifacts(gh, { keep: DEFAULT_ARTIFACT_KEEP }), []);
    await assert.rejects(oldArtifacts(gh, { keep: -1 }), /must not be negative/);
  } finally {
    await gh.close();
  }
});
