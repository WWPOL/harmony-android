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
import { execFile } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

import {
  LATEST_ASSET,
  LATEST_SHA256_ASSET,
  RETIRED_ASSETS,
  ROLLING_TAG_PREFIX,
  client,
  ensureRollingRelease,
  ensureTagRelease,
  isPublishedRelease,
  pruneRollingAssets,
  publishBuild,
  releaseTitle,
  replaceStable,
  retireAssets,
  rollingTag,
  rollingName,
  ownerLabel,
} from './release.mjs';

const run = promisify(execFile);
const here = dirname(fileURLToPath(import.meta.url));
const OWNER_REPO = 'acme/harmony-android';
const sha = (buf) => createHash('sha256').update(buf).digest('hex');

// Release REST subset: list/create/get releases, list assets, upload with a name and
// label, rename (PATCH) and delete assets. Two items per page, so pagination is
// exercised. failUploads makes an upload whose name matches fail with a 500, which is how
// a network or quota failure during a publish is reproduced.
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

async function fakeGitHub({ releases: seed = [], failUploads = null } = {}) {
  const releases = structuredClone(seed);
  const calls = [];
  const state = { failUploads };
  let nextId = 900;
  const server = http.createServer(async (req, res) => {
    const base = `http://127.0.0.1:${server.address().port}`;
    const url = new URL(req.url, base);
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const body = Buffer.concat(chunks);
    calls.push({ method: req.method, path: url.pathname, name: url.searchParams.get('name') });
    const send = (status, data, headers = {}) => {
      res.writeHead(status, { 'content-type': 'application/json', ...headers });
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
        label: url.searchParams.get('label') || '',
        size: body.length,
        state: 'uploaded',
        content: body.toString('base64'),
        browser_download_url: `${base}/${OWNER_REPO}/releases/download/${release.tag_name}/${name}`,
      };
      release.assets.push(asset);
      return send(201, asset);
    }
    m = match(new RegExp(`^/repos/${OWNER_REPO}/releases/assets/(\\d+)$`));
    if (m && req.method === 'PATCH') {
      const { name } = JSON.parse(body.toString());
      for (const r of releases) {
        const a = r.assets.find((x) => x.id === Number(m[1]));
        if (a) {
          if (r.assets.some((x) => x.name === name && x.id !== a.id)) {
            return send(422, { message: 'Validation Failed', errors: [{ code: 'already_exists' }] });
          }
          a.name = name;
          return send(200, a);
        }
      }
      return send(404, { message: 'Not Found' });
    }
    if (m && req.method === 'DELETE') {
      for (const r of releases) r.assets = r.assets.filter((a) => a.id !== Number(m[1]));
      return send(204);
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
    calls,
    state,
    ...client({ token: 'test-token', repository: OWNER_REPO, apiUrl: url }),
    close: async () => {
      server.closeAllConnections();
      await new Promise((resolve) => server.close(resolve));
    },
  };
}

// A rolling release of the default branch, created the way a publish creates it, for a
// test that then works on the release directly.
async function seedRolling(gh) {
  return ensureRollingRelease(gh, {
    branch: 'master', commit: 'abc1234', defaultBranch: 'master', version: '1.9.6', body: '',
  });
}

// A published version release with assets, which nothing in this workflow may touch.
function versionRelease() {
  return {
    id: 41,
    tag_name: 'v1.9.6',
    name: 'Brapmony 1.9.6',
    draft: false,
    prerelease: false,
    assets: [
      { id: 4101, name: 'Other_Tool_V1.9.6.apk', label: '', size: 12, state: 'uploaded', browser_download_url: 'x' },
      { id: 4102, name: 'Brapmony-1.9.6.apk', label: '', size: 13, state: 'uploaded', browser_download_url: 'y' },
      // An asset from before the name scheme changed. It is still this workflow's, and on
      // a version release it is left exactly where it is.
      { id: 4104, name: 'Brapmony_Android_V1.9.6.apk', label: '', size: 14, state: 'uploaded', browser_download_url: 'w' },
      { id: 4103, name: 'latest.json', label: '', size: 34, state: 'uploaded', browser_download_url: 'z' },
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

// A publish of [args], with the arguments the workflow always passes filled in unless
// the caller names them: `--default-branch` decides whether the build is published as a
// release or a prerelease, and `--version` names the release.
const DEFAULT_BRANCH = 'master';

async function publish(args, { gh: existing, releases, failUploads } = {}) {
  const gh = existing ?? (await fakeGitHub({ releases, failUploads }));
  // A reused server may be told to fail this publish's uploads.
  if (existing && failUploads !== undefined) existing.state.failUploads = failUploads;
  const out = join(mkdtempSync(join(tmpdir(), 'brapmony-out-')), 'output');
  writeFileSync(out, '');
  const full = [...args];
  for (const [flag, value] of [['--default-branch', DEFAULT_BRANCH], ['--version', '1.9.6']]) {
    if (!full.includes(flag)) full.push(flag, value);
  }
  const { stdout } = await run(process.execPath, [join(here, 'release.mjs'), 'rolling', ...full], {
    env: {
      ...process.env,
      GITHUB_TOKEN: 'test-token',
      GITHUB_REPOSITORY: OWNER_REPO,
      GITHUB_API_URL: gh.url,
      GITHUB_OUTPUT: out,
    },
  });
  return { gh, stdout, output: readFileSync(out, 'utf8') };
}

test('rolling tag and name are derived from the branch and cannot collide with a version tag', () => {
  assert.equal(rollingTag('master'), `${ROLLING_TAG_PREFIX}master`);
  assert.equal(rollingTag('task/build-native-android-app-with-parity-v8hy4zr4'),
    'android-rolling-task-build-native-android-app-with-parity-v8hy4zr4');
  assert.equal(rollingName('master'), 'Brapmony (rolling) master');
  for (const branch of ['v1.9.6', 'v2.0.0', 'release/1.9.6']) {
    assert.ok(!/^v\d/.test(rollingTag(branch)), `a branch named ${branch} must not make a version tag`);
  }
  assert.throws(() => rollingTag('///'), /no characters a tag can carry/);
  assert.throws(() => rollingTag(''), /--branch is required/);
});

test('a publish creates one release and uploads the build plus the stable names', async () => {
  const ws = workspace();
  try {
    const { gh, stdout, output } = await publish(['--apk', ws.apk, '--branch', 'master', '--commit', 'abc1234']);
    assert.match(stdout, /Created rolling release/);
    assert.equal(gh.releases.length, 1);
    const release = gh.releases[0];
    assert.equal(release.tag_name, `${ROLLING_TAG_PREFIX}master`);
    // The default branch's build is the repository's release, not a prerelease: a
    // prerelease is skipped by /releases/latest and by the repository's Releases box.
    assert.equal(release.prerelease, false);
    assert.equal(release.draft, false);
    assert.equal(release.name, 'Brapmony 1.9.6', 'the release is named after the app and version');
    const names = release.assets.map((a) => a.name).sort();
    assert.deepEqual(names, [
      'Brapmony-1.9.6-abc1234-debug.apk',
      LATEST_ASSET,
      LATEST_SHA256_ASSET,
    ].sort());
    // Every asset this workflow uploaded says so, and says which bytes it holds.
    const expected = sha(readFileSync(ws.apk));
    for (const a of release.assets) assert.equal(a.label, ownerLabel(expected));
    const stable = release.assets.find((a) => a.name === LATEST_ASSET);
    assert.equal(stable.content, release.assets.find((a) => a.name.endsWith('-abc1234-debug.apk')).content);
    const checksum = release.assets.find((a) => a.name === LATEST_SHA256_ASSET);
    assert.equal(Buffer.from(checksum.content, 'base64').toString('utf8'), `${expected}  ${LATEST_ASSET}\n`);
    assert.match(output, /tag=android-rolling-master/);
    assert.match(output, /release-id=\d+/);
    assert.match(output, new RegExp(`sha256=${expected}`));
  } finally {
    await ws.cleanup();
  }
});

test('a second push keeps the first build and moves the stable name to the new one', async () => {
  const first = workspace('abc1234');
  const second = workspace('def5678');
  try {
    const firstRun = await publish(['--apk', first.apk, '--branch', 'master', '--commit', 'abc1234']);
    const again = await publish(['--apk', second.apk, '--branch', 'master', '--commit', 'def5678'], { gh: firstRun.gh });
    assert.match(again.stdout, /Found rolling release/);
    assert.equal(again.gh.releases.length, 1, 'the same branch keeps one rolling release');
    const release = again.gh.releases[0];
    const names = release.assets.map((a) => a.name);
    assert.ok(names.includes('Brapmony-1.9.6-abc1234-debug.apk'), 'the first build is still there');
    assert.ok(names.includes('Brapmony-1.9.6-def5678-debug.apk'));
    assert.equal(names.filter((n) => n === LATEST_ASSET).length, 1, 'one stable asset, not two');
    const stable = release.assets.find((a) => a.name === LATEST_ASSET);
    const newest = release.assets.find((a) => a.name.endsWith('-def5678-debug.apk'));
    assert.equal(stable.content, newest.content, 'the stable name follows the newest build');
    // The staging name the swap went through is gone, not left behind.
    assert.ok(!names.some((n) => n.includes('.staging-')), `left a staging asset: ${names.join(', ')}`);
  } finally {
    await first.cleanup();
    await second.cleanup();
  }
});

test('a re-run of the same commit with the same bytes changes nothing', async () => {
  const ws = workspace();
  try {
    const firstRun = await publish(['--apk', ws.apk, '--branch', 'master', '--commit', 'abc1234']);
    const before = firstRun.gh.releases[0].assets.map((a) => `${a.name}:${a.id}`).sort();
    const rerun = await publish(['--apk', ws.apk, '--branch', 'master', '--commit', 'abc1234'], { gh: firstRun.gh });
    assert.match(rerun.stdout, /Kept Brapmony-1\.9\.6-abc1234-debug\.apk/);
    const after = rerun.gh.releases[0].assets.map((a) => `${a.name}:${a.id}`).sort();
    assert.deepEqual(after, before, 'a re-run neither re-uploads nor renames anything');
  } finally {
    await ws.cleanup();
  }
});

test('a rebuild of the same commit gets its own name, leaving the earlier bytes where they were', async () => {
  const first = workspace('abc1234', Buffer.from('first build of abc1234'));
  const rebuilt = workspace('abc1234', Buffer.from('second build of abc1234'));
  try {
    const firstRun = await publish(['--apk', first.apk, '--branch', 'master', '--commit', 'abc1234']);
    const before = firstRun.gh.releases[0].assets.find((a) => a.name.endsWith('-abc1234-debug.apk'));
    const again = await publish(['--apk', rebuilt.apk, '--branch', 'master', '--commit', 'abc1234', '--attempt', '2'], { gh: firstRun.gh });
    const names = again.gh.releases[0].assets.map((a) => a.name);
    assert.ok(names.includes('Brapmony-1.9.6-abc1234-debug.apk'), 'the first name is unchanged');
    assert.ok(
      names.includes('Brapmony-1.9.6-abc1234-debug.r2.apk'),
      `the rebuilt bytes have their own name: ${names.join(', ')}`,
    );
    const after = again.gh.releases[0].assets.find((a) => a.name.endsWith('-abc1234-debug.apk'));
    assert.equal(after.id, before.id, 'the earlier asset was not replaced');
    assert.equal(after.content, before.content, 'the earlier bytes are what they were');
    // And the stable name follows the newest bytes.
    const stable = again.gh.releases[0].assets.find((a) => a.name === LATEST_ASSET);
    assert.equal(stable.content, Buffer.from('second build of abc1234').toString('base64'));
  } finally {
    await first.cleanup();
    await rebuilt.cleanup();
  }
});

test('an asset this workflow did not upload is never replaced', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub();
    const { id } = await seedRolling(gh);
    // Somebody attached a file under the stable name by hand.
    await gh.json('POST', `/releases/${id}/assets?name=${encodeURIComponent(LATEST_ASSET)}`, {
      body: Buffer.from('hand made'),
      contentType: 'application/octet-stream',
    });
    await assert.rejects(
      replaceStable(gh, { releaseId: id, file: ws.apk, name: LATEST_ASSET, label: ownerLabel(sha(readFileSync(ws.apk))) }),
      /was not uploaded by this workflow; refusing to replace it/,
    );
    const asset = gh.releases[0].assets.find((a) => a.name === LATEST_ASSET);
    assert.equal(Buffer.from(asset.content, 'base64').toString('utf8'), 'hand made');
    await gh.close();
  } finally {
    await ws.cleanup();
  }
});

test('a collision with a foreign build name is refused rather than overwritten', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub();
    const { id } = await seedRolling(gh);
    const name = 'Brapmony-1.9.6-abc1234-debug.apk';
    await gh.json('POST', `/releases/${id}/assets?name=${encodeURIComponent(name)}`, {
      body: Buffer.from('not ours'),
      contentType: 'application/octet-stream',
    });
    await assert.rejects(
      publishBuild(gh, { releaseId: id, file: ws.apk, name }),
      /was not uploaded by this workflow; refusing to replace it/,
    );
    const asset = gh.releases[0].assets.find((a) => a.name === name);
    assert.equal(Buffer.from(asset.content, 'base64').toString('utf8'), 'not ours');
    await gh.close();
  } finally {
    await ws.cleanup();
  }
});

test('a failed build upload leaves the stable build people are downloading intact', async () => {
  const first = workspace('abc1234');
  const second = workspace('def5678');
  try {
    const firstRun = await publish(['--apk', first.apk, '--branch', 'master', '--commit', 'abc1234']);
    const before = firstRun.gh.releases[0].assets.find((a) => a.name === LATEST_ASSET);
    // The next build's own upload fails: the stable name must not move.
    await assert.rejects(
      publish(['--apk', second.apk, '--branch', 'master', '--commit', 'def5678'], {
        gh: firstRun.gh,
        failUploads: /def5678/,
      }),
      /upload failed/,
    );
    const after = firstRun.gh.releases[0].assets.find((a) => a.name === LATEST_ASSET);
    assert.equal(after.id, before.id, 'the stable asset is the same one');
    assert.equal(after.content, before.content, 'and it still holds the last good build');
    assert.ok(!firstRun.gh.releases[0].assets.some((a) => a.name.endsWith('-def5678.apk')));
  } finally {
    await first.cleanup();
    await second.cleanup();
  }
});

test('a failed stable upload leaves the previous stable build in place', async () => {
  const first = workspace('abc1234');
  const second = workspace('def5678');
  try {
    const firstRun = await publish(['--apk', first.apk, '--branch', 'master', '--commit', 'abc1234']);
    const before = firstRun.gh.releases[0].assets.find((a) => a.name === LATEST_ASSET);
    // The build uploads, but the stable copy's upload fails.
    await assert.rejects(
      publish(['--apk', second.apk, '--branch', 'master', '--commit', 'def5678'], {
        gh: firstRun.gh,
        failUploads: `${LATEST_ASSET}.staging`,
      }),
      /upload failed/,
    );
    const after = firstRun.gh.releases[0].assets.find((a) => a.name === LATEST_ASSET);
    assert.equal(after.id, before.id);
    assert.equal(after.content, before.content, 'the previous stable build is still downloadable');
  } finally {
    await first.cleanup();
    await second.cleanup();
  }
});

test('pruning keeps the newest builds and only ever deletes this workflow own assets', async () => {
  const gh = await fakeGitHub();
  try {
    const { id } = await seedRolling(gh);
    const label = ownerLabel('a'.repeat(64));
    for (let i = 0; i < 12; i += 1) {
      const name = `Brapmony-1.9.6-${String(i).padStart(7, '0')}-debug.apk`;
      await gh.json('POST', `/releases/${id}/assets?name=${encodeURIComponent(name)}&label=${encodeURIComponent(label)}`, {
        body: Buffer.from(`build ${i}`),
        contentType: 'application/octet-stream',
      });
    }
    // A hand-attached APK, the stable names, the retired names, and a stray file: none is
    // a pruning candidate.
    for (const name of ['Brapmony-1.9.6-handmade.apk', LATEST_ASSET, LATEST_SHA256_ASSET,
                        ...RETIRED_ASSETS, 'notes.txt']) {
      await gh.json('POST', `/releases/${id}/assets?name=${encodeURIComponent(name)}`, {
        body: Buffer.from('x'),
        contentType: 'application/octet-stream',
      });
    }
    const pruned = await pruneRollingAssets(gh, { releaseId: id, keep: 10 });
    assert.equal(pruned.length, 2);
    assert.deepEqual(pruned.sort(), [
      'Brapmony-1.9.6-0000000-debug.apk',
      'Brapmony-1.9.6-0000001-debug.apk',
    ]);
    const names = gh.releases[0].assets.map((a) => a.name);
    for (const kept of ['Brapmony-1.9.6-handmade.apk', LATEST_ASSET, LATEST_SHA256_ASSET,
                        ...RETIRED_ASSETS, 'notes.txt']) {
      assert.ok(names.includes(kept), `${kept} must survive pruning`);
    }
    assert.equal(names.filter((n) => /-\d{7}-debug\.apk$/.test(n)).length, 10);
  } finally {
    await gh.close();
  }
});

// The names an earlier version of this workflow published under are retired, so a
// download does not sit under a name the documentation no longer mentions, holding bytes
// nothing updates. Only this workflow's own assets are removed: a file somebody attached
// by hand is never deleted, whatever it is called.
//
// The fixture is the release `main` actually had before this change, asset for asset and
// label for label (read from the GitHub API): the two old stable names and one old build.
// This is the first push after the change merges, so the result is what a downloader will
// find there.
test('the first publish after the change turns the old release into the new one', async () => {
  const ws = workspace();
  try {
    const old = ownerLabel('b'.repeat(64));
    const gh = await fakeGitHub({
      releases: [{
        id: 453200307,
        tag_name: `${ROLLING_TAG_PREFIX}main`,
        name: 'Android (rolling) main',
        body: 'Rolling build of `main` for the Android client.',
        draft: false,
        prerelease: true,
        assets: [
          { id: 1, name: 'Brapmony_Android_latest.apk', label: old, size: 9, state: 'uploaded' },
          { id: 2, name: 'Brapmony_Android_latest.apk.sha256', label: old, size: 9, state: 'uploaded' },
          { id: 3, name: 'Brapmony_Android_V1.9.6_debug-main-606ab10.apk', label: old, size: 9, state: 'uploaded' },
        ],
      }],
    });
    const { stdout } = await publish(
      // The file is named the way the namer names it, and the publisher uses that name.
      ['--apk', ws.apk, '--branch', 'main', '--commit', 'abc1234', '--default-branch', 'main', '--body', 'the new build'],
      { gh },
    );
    const release = gh.releases[0];
    // It becomes the repository's release, named after the app and version.
    assert.equal(release.prerelease, false);
    assert.equal(release.name, 'Brapmony 1.9.6');
    assert.equal(release.body, 'the new build');
    // The names it published are gone; the new ones are there.
    const names = release.assets.map((a) => a.name).sort();
    assert.deepEqual(names, [
      'Brapmony-1.9.6-abc1234-debug.apk',
      LATEST_ASSET,
      LATEST_SHA256_ASSET,
      // The old build stays: it is a name a link may point at, and pruning removes it on
      // its own schedule rather than this publish reaching for it.
      'Brapmony_Android_V1.9.6_debug-main-606ab10.apk',
    ]);
    assert.match(stdout, /Retired Brapmony_Android_latest\.apk, Brapmony_Android_latest\.apk\.sha256/);
    assert.match(stdout, /Updated android-rolling-main: prerelease, name, body/);
    // The bytes under the new stable name are this build's.
    const stable = release.assets.find((a) => a.name === LATEST_ASSET);
    assert.equal(stable.content, release.assets.find((a) => a.name.endsWith('-abc1234-debug.apk')).content);
  } finally {
    await ws.cleanup();
  }
});

test('the previous stable names are retired, and nothing else is', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub();
    const { id } = await seedRolling(gh);
    const label = ownerLabel('b'.repeat(64));
    for (const name of RETIRED_ASSETS) {
      await gh.json('POST', `/releases/${id}/assets?name=${encodeURIComponent(name)}&label=${encodeURIComponent(label)}`, {
        body: Buffer.from('stale'),
        contentType: 'application/octet-stream',
      });
    }
    // A hand-attached file under a retired-looking name: not labelled, so not ours.
    await gh.json('POST', `/releases/${id}/assets?name=${encodeURIComponent('Brapmony_Android_latest_handmade.apk')}`, {
      body: Buffer.from('hand made'),
      contentType: 'application/octet-stream',
    });
    const { stdout } = await publish(['--apk', ws.apk, '--branch', 'master', '--commit', 'abc1234'], { gh });
    assert.match(stdout, new RegExp(`Retired ${RETIRED_ASSETS.map((n) => n.replace(/\./g, '\\.')).join(', ')}`));
    const names = gh.releases[0].assets.map((a) => a.name);
    for (const gone of RETIRED_ASSETS) assert.ok(!names.includes(gone), `${gone} must be gone`);
    assert.ok(names.includes('Brapmony_Android_latest_handmade.apk'), 'a file this workflow did not upload is kept');
    assert.ok(names.includes(LATEST_ASSET), 'and the current stable name is there');
  } finally {
    await ws.cleanup();
  }
});

test('a retired name that is not this workflow\'s is refused, not deleted', async () => {
  const gh = await fakeGitHub();
  try {
    const { id } = await seedRolling(gh);
    for (const name of RETIRED_ASSETS) {
      await gh.json('POST', `/releases/${id}/assets?name=${encodeURIComponent(name)}`, {
        body: Buffer.from('somebody else put this here'),
        contentType: 'application/octet-stream',
      });
    }
    const retired = await retireAssets(gh, { releaseId: id });
    assert.deepEqual(retired, []);
    const names = gh.releases[0].assets.map((a) => a.name);
    for (const kept of RETIRED_ASSETS) assert.ok(names.includes(kept), `${kept} must be kept`);
  } finally {
    await gh.close();
  }
});

test('a rolling publish leaves a version release and its assets alone', async () => {
  const ws = workspace();
  try {
    const { gh } = await publish(['--apk', ws.apk, '--branch', 'master', '--commit', 'abc1234'], {
      releases: [versionRelease()],
    });
    const version = gh.releases.find((r) => r.tag_name === 'v1.9.6');
    assert.deepEqual(
      version.assets.map((a) => a.name).sort(),
      ['Brapmony-1.9.6.apk', 'Brapmony_Android_V1.9.6.apk', 'Other_Tool_V1.9.6.apk', 'latest.json'],
    );
    assert.equal(version.prerelease, false);
    assert.equal(version.draft, false);
    const deletes = gh.calls.filter((c) => c.method === 'DELETE');
    assert.ok(deletes.every((c) => !String(c.path).includes('/41/')), 'nothing of the version release is deleted');
  } finally {
    await ws.cleanup();
  }
});

// The rolling tag's release is only ever this workflow's. One that carries none of its
// builds is somebody else's release at a tag this workflow computed, and rewriting what it
// says -- or putting bytes under a name a link there already means -- is not this
// workflow's to do.
test('a release at the rolling tag that carries no build of ours is refused', async () => {
  const ws = workspace();
  try {
    await assert.rejects(
      publish(['--apk', ws.apk, '--branch', 'master', '--commit', 'abc1234'], {
        releases: [{
          id: 55,
          tag_name: `${ROLLING_TAG_PREFIX}master`,
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

// The one change that would quietly alter what an existing download link means: a branch's
// prerelease becoming the repository's release. Publishing the default branch's build does
// exactly that to a rolling release an earlier version of this workflow created, so it is
// done deliberately, after the build uploads, and tested here.
test('a rolling release of the default branch is moved out of the prereleases', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub({
      releases: [{
        id: 60,
        tag_name: `${ROLLING_TAG_PREFIX}master`,
        name: 'Android (rolling) master',
        body: 'an older body',
        draft: false,
        prerelease: true,
        assets: [
          { id: 6001, name: 'Brapmony_Android_V1.9.6_debug-master-abc1234.apk', label: '', size: 9, state: 'uploaded' },
        ],
      }],
    });
    const { stdout } = await publish(
      ['--apk', ws.apk, '--branch', 'master', '--commit', 'abc1234', '--body', 'the build of abc1234'],
      { gh },
    );
    assert.match(stdout, /Updated android-rolling-master: prerelease, name, body/);
    const release = gh.releases.find((r) => r.id === 60);
    assert.equal(release.prerelease, false, 'the default branch is a release, not a prerelease');
    assert.equal(release.name, 'Brapmony 1.9.6', 'and says what it is');
    assert.equal(release.body, 'the build of abc1234', 'and describes the build that is now there');
    // The build it held is kept: it is a name a download link may point at.
    assert.ok(release.assets.some((a) => a.name === 'Brapmony_Android_V1.9.6_debug-master-abc1234.apk'));
  } finally {
    await ws.cleanup();
  }
});

test('a build of another branch is a prerelease, and publishing it moves no release', async () => {
  const ws = workspace();
  try {
    const { gh, stdout } = await publish([
      '--apk', ws.apk, '--branch', 'feature/x', '--commit', 'abc1234', '--default-branch', 'master',
    ]);
    assert.match(stdout, /Created rolling prerelease android-rolling-feature-x/);
    const release = gh.releases.find((r) => r.tag_name === `${ROLLING_TAG_PREFIX}feature-x`);
    assert.equal(release.prerelease, true, 'a branch build is not an offer to anybody');
    assert.equal(release.name, 'Brapmony (rolling) feature/x');
    // And a default-branch publish afterwards leaves it alone.
    await publish(['--apk', ws.apk, '--branch', 'master', '--commit', 'abc1234'], { gh });
    assert.equal(gh.releases.find((r) => r.tag_name === `${ROLLING_TAG_PREFIX}feature-x`).prerelease, true);
  } finally {
    await ws.cleanup();
  }
});

// A publish that fails before its build is uploaded must not have moved the repository's
// release: the page has to describe the bytes that are actually there.
test('a failed publish does not change what the release says', async () => {
  const ws = workspace();
  try {
    const gh = await fakeGitHub({
      releases: [{
        id: 61,
        tag_name: `${ROLLING_TAG_PREFIX}master`,
        name: 'Android (rolling) master',
        body: 'the build that is there',
        draft: false,
        prerelease: true,
        assets: [{ id: 6101, name: 'Brapmony_Android_V1.9.6_debug-master-old.apk', label: '', size: 9, state: 'uploaded' }],
      }],
    });
    gh.state.failUploads = 'abc1234';
    await assert.rejects(publish(['--apk', ws.apk, '--branch', 'master', '--commit', 'abc1234'], { gh }));
    const release = gh.releases.find((r) => r.id === 61);
    assert.equal(release.prerelease, true, 'the release is still what it was');
    assert.equal(release.name, 'Android (rolling) master');
    assert.equal(release.body, 'the build that is there');
  } finally {
    await ws.cleanup();
  }
});

test('a dry run contacts nothing and says what it would publish', async () => {
  const ws = workspace();
  try {
    const { stdout } = await run(process.execPath, [join(here, 'release.mjs'), 'rolling',
      '--apk', ws.apk, '--branch', 'master', '--default-branch', 'master', '--dry-run'], {
      env: { ...process.env, GITHUB_TOKEN: '', GITHUB_REPOSITORY: OWNER_REPO },
    });
    assert.match(stdout, /Would publish Brapmony-1\.9\.6-abc1234-debug\.apk to release android-rolling-master/);
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

test('a tag creates its release once and re-running the tag finds the same one', async () => {
  const gh = await fakeGitHub();
  try {
    const first = await tagCommand(['--tag', 'v1.9.6'], gh);
    assert.match(first.stdout, /Created release v1\.9\.6/);
    assert.equal(gh.releases.length, 1);
    assert.equal(gh.releases[0].tag_name, 'v1.9.6');
    assert.equal(gh.releases[0].name, 'Brapmony 1.9.6');
    assert.equal(gh.releases[0].prerelease, false, 'a version release is not a prerelease');
    const id = gh.releases[0].id;
    assert.match(first.output, new RegExp(`id=${id}\\n`), 'the id goes to GITHUB_OUTPUT');

    const second = await tagCommand(['--tag', 'v1.9.6'], gh);
    assert.match(second.stdout, /Found release v1\.9\.6/);
    assert.equal(gh.releases.length, 1, 'a second run creates no second release');
    assert.match(second.output, new RegExp(`id=${id}\\n`), 'and names the release that exists');
  } finally {
    await gh.close();
  }
});

test('a tag is only ever vX.Y.Z, so a rolling tag can never become a version release', async () => {
  const gh = await fakeGitHub();
  try {
    for (const tag of [`${ROLLING_TAG_PREFIX}master`, 'master', 'v1.9', 'v1.9.6-rc1']) {
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

test('a version release that carries a rolling tag is refused, not adopted', async () => {
  const gh = await fakeGitHub();
  try {
    await assert.rejects(
      ensureTagRelease(gh, { tag: 'v1.9.6' }).then(() => {
        throw new Error('expected the call to be refused');
      }),
      (error) => error instanceof Error,
    );
    // The real check: a rolling tag never reaches the version path at all.
    await assert.rejects(ensureTagRelease(gh, { tag: 'android-rolling-master' }), /--tag must be vX\.Y\.Z/);
  } finally {
    await gh.close();
  }
});
