// node --test scripts/changelog.test.mjs
//
// The release notes are a git query, so what they report is checked against a real history
// rather than a stub: a scratch repository is built commit by commit, tagged the way releases
// are tagged, and the notes are read back from it. The properties that matter are the ones a
// person reading the Releases page depends on -- that the notes start at the previous release,
// that a re-run says the same thing as the first run, that a merge commit is not a line of its
// own, and that a release with nothing behind it still says what it is.
import { after, test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import {
  COMMIT_TAG_RE,
  DEFAULT_CHANGELOG_LIMIT,
  RELEASE_TAG_RE,
  VERSION_TAG_RE,
  changelogEntries,
  commitTag,
  previousReleaseTag,
  releaseBody,
  releaseTitle,
} from './release.mjs';

// A scratch repository, with a commit per subject and the tags a release would carry.
const repos = [];
after(() => {
  for (const dir of repos) rmSync(dir, { recursive: true, force: true });
});

function scratch() {
  const dir = mkdtempSync(join(tmpdir(), 'brapmony-changelog-'));
  repos.push(dir);
  const git = (...args) => {
    const r = spawnSync('git', args, { cwd: dir, encoding: 'utf8' });
    assert.equal(r.status, 0, `git ${args.join(' ')}: ${r.stderr}`);
    return r.stdout.trim();
  };
  git('init', '-q', '-b', 'main');
  git('config', 'user.email', 'test@example.com');
  git('config', 'user.name', 'Test');
  git('config', 'commit.gpgsign', 'false');
  const commit = (subject, file = 'file.txt') => {
    writeFileSync(join(dir, file), `${subject}\n`, { flag: 'a' });
    git('add', file);
    git('commit', '-q', '-m', subject);
    return git('rev-parse', 'HEAD');
  };
  return { dir, git, commit };
}

test('the notes of the first release are the commits of the branch', () => {
  const { dir, commit } = scratch();
  commit('feat: a first thing');
  commit('fix: a second thing');
  const notes = changelogEntries({ cwd: dir });
  assert.equal(notes.previous, null, 'there is no release behind the first one');
  assert.deepEqual(notes.entries.map((e) => e.subject), ['fix: a second thing', 'feat: a first thing']);
  assert.equal(notes.more, 0);
  const body = releaseBody({ commit: 'HEAD', sha256: 'a'.repeat(64), ...notes });
  assert.match(body, /^## Changes\n/, 'the heading names no release to compare with');
  assert.ok(!/compare/.test(body), 'and there is no comparison to link to');
});

test('the notes start at the release before the commit, and never at the commit itself', () => {
  const { dir, git, commit } = scratch();
  const first = commit('feat: the first thing');
  git('tag', 'v0.1.0', first);
  commit('fix: the second thing');
  const head = commit('docs: the third thing');
  const notes = changelogEntries({ cwd: dir });
  assert.equal(notes.previous, 'v0.1.0');
  assert.deepEqual(notes.entries.map((e) => e.subject), ['docs: the third thing', 'fix: the second thing']);
  // The release of this commit is tagged on it. The next run of the same commit must say
  // what the first run said, not nothing: the previous release is the one behind it.
  git('tag', commitTag('0.1.0', head), head);
  const rerun = changelogEntries({ cwd: dir });
  assert.equal(rerun.previous, 'v0.1.0');
  assert.deepEqual(rerun.entries.map((e) => e.subject), ['docs: the third thing', 'fix: the second thing']);
});

test('a merge commit is not a line of the changelog, and what it merged is', () => {
  const { dir, git, commit } = scratch();
  const base = commit('feat: the base');
  git('tag', 'v0.1.0', base);
  git('checkout', '-q', '-b', 'side');
  commit("feat: a branch's work", 'side.txt');
  git('checkout', '-q', 'main');
  commit('fix: work on main', 'main.txt');
  git('merge', '-q', '--no-ff', '-m', 'Merge branch side', 'side');
  const notes = changelogEntries({ cwd: dir });
  assert.ok(!notes.entries.some((e) => e.subject.startsWith('Merge ')), 'the merge is not a line');
  assert.ok(notes.entries.some((e) => e.subject === "feat: a branch's work"), 'the work it merged is');
  assert.ok(notes.entries.some((e) => e.subject === 'fix: work on main'));
});

test('a release lists a bounded number of commits and says how many it left out', () => {
  const { dir, git, commit } = scratch();
  const base = commit('feat: the base');
  git('tag', 'v0.1.0', base);
  for (let i = 0; i < 12; i += 1) commit(`feat: change ${i}`);
  const notes = changelogEntries({ cwd: dir, limit: 5 });
  assert.equal(notes.entries.length, 5);
  assert.equal(notes.more, 7, 'the rest are counted rather than dropped silently');
  const body = releaseBody({ commit: 'HEAD', sha256: 'b'.repeat(64), ...notes });
  assert.match(body, /and 7 more commits/);
  // The default bound is what a release page has to render, and it is generous.
  assert.ok(DEFAULT_CHANGELOG_LIMIT >= 20, 'the default lists enough to be a changelog');
  const all = changelogEntries({ cwd: dir });
  assert.equal(all.entries.length, 12, 'and the default is above this history');
  assert.equal(all.more, 0);
});

test('a history that cannot answer fails rather than publishing an empty changelog', () => {
  const dir = mkdtempSync(join(tmpdir(), 'brapmony-empty-'));
  repos.push(dir);
  assert.throws(() => changelogEntries({ cwd: dir }), /cannot read the commit|not a git repository/);
  assert.throws(() => changelogEntries({ cwd: dir, from: 'v1.0.0' }), /cannot read the commits/);
});

test('the release tag and title are the version and the commit, and never a version tag', () => {
  assert.equal(commitTag('0.1.0', 'abcdef1234'), 'v0.1.0-abcdef1');
  assert.equal(releaseTitle('0.1.0', 'abcdef1234'), '0.1.0-abcdef1');
  // The two namespaces cannot be confused in either direction.
  for (const tag of ['v0.1.0', 'v1.9.6']) {
    assert.ok(VERSION_TAG_RE.test(tag), `${tag} is a version tag`);
    assert.ok(!COMMIT_TAG_RE.test(tag), `${tag} is not a commit tag`);
  }
  for (const tag of ['v0.1.0-abcdef1', 'v1.9.6-1234567890']) {
    assert.ok(COMMIT_TAG_RE.test(tag), `${tag} is a commit tag`);
    assert.ok(!VERSION_TAG_RE.test(tag), `${tag} is not a version tag`);
    assert.ok(RELEASE_TAG_RE.test(tag), `${tag} is one of the releases this workflow publishes`);
  }
  assert.ok(RELEASE_TAG_RE.test('v0.1.0'));
  for (const tag of ['android-rolling-main', '0.1.0-abcdef1', 'v0.1', 'v0.1.0-abcdef', 'v0.1.0-ABCDEF1']) {
    assert.ok(!RELEASE_TAG_RE.test(tag), `${tag} is not a release tag this workflow publishes`);
  }
  // A stray tag is not named as the previous release: the body links to it, and a link to
  // something that is not a release goes nowhere.
  const { dir, git, commit } = scratch();
  commit('feat: a thing');
  git('tag', 'nightly', 'HEAD');
  assert.equal(previousReleaseTag({ cwd: dir }), null);
});
