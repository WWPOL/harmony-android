// node --test scripts/workflow.test.mjs
//
// The properties of the Android workflows that make publishing safe, checked rather than
// described in a comment: which events can reach a write token, that every action is
// pinned, that a branch build and a tag build cannot collide, and that a tag's APK goes to
// the release for that tag instead of creating a second one.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const read = (p) => readFileSync(join(root, p), 'utf8');
const parse = (p) => YAML.parse(read(p));

const ANDROID = '.github/workflows/android.yml';
const RELEASE = '.github/workflows/release.yml';
const WORKFLOWS = [ANDROID, RELEASE];

// Every `uses:` in a document, with the file and job it is in.
function uses(doc, file) {
  const out = [];
  for (const [job, spec] of Object.entries(doc.jobs || {})) {
    for (const step of spec.steps || []) if (step.uses) out.push({ file, job, uses: step.uses });
  }
  return out;
}

// Every `run:` in a job, joined: the shell of the job is what most of these check.
const shell = (job) => job.steps.map((s) => s.run || '').join('\n');

test('every action is pinned to a full commit SHA', () => {
  for (const file of WORKFLOWS) {
    for (const u of uses(parse(file), file)) {
      assert.match(u.uses, /^[^@]+@[0-9a-f]{40}$/, `${u.file} ${u.job}: ${u.uses} is not pinned to a SHA`);
    }
  }
});

test('no workflow runs untrusted code with a write token', () => {
  const android = parse(ANDROID);
  // pull_request_target runs the base repository's token against a pull request's code.
  assert.deepEqual(Object.keys(android.on).sort(), ['pull_request', 'push', 'workflow_dispatch']);
  // The workflow default is read-only; only jobs that publish opt in.
  assert.deepEqual(android.permissions, { contents: 'read' });
  for (const [job, spec] of Object.entries(android.jobs)) {
    if (spec.permissions && spec.permissions.contents === 'write') {
      assert.equal(job, 'publish', `only the publishing job may write, not ${job}`);
      // A pull request, and a pull request from a fork in particular, must never reach it.
      assert.match(String(spec.if), /github\.event_name == 'push'/, `${job} must be push-only`);
      assert.match(String(spec.if), /github\.repository == 'WWPOL\/harmony-android'/, `${job} must be this repository`);
      assert.ok(spec.needs, `${job} must build before it publishes`);
    }
  }
  // The build job that pull requests run holds no permission of its own.
  assert.equal(android.jobs.build.permissions, undefined);
  // The release workflow writes only from the job that creates the release.
  const release = parse(RELEASE);
  assert.deepEqual(release.permissions, { contents: 'read' });
  assert.deepEqual(release.jobs.release.permissions, { contents: 'write' });
});

test('every push to a branch builds the client', () => {
  const android = parse(ANDROID);
  assert.deepEqual(android.on.push.branches, ['**'], 'every branch builds, not only master');
  // Literally every push: a path filter would leave the previous commit's APK
  // standing after a push that touched nothing under the app.
  assert.equal(android.on.push.paths, undefined, 'push has no path filter');
  assert.ok(android.on.pull_request, 'pull requests are still checked');
  assert.ok('workflow_dispatch' in android.on, 'a branch can be built by hand');
  // A push and a pull request from the same branch must not fight over one concurrency group.
  assert.match(android.concurrency.group, /github\.ref/);
});

test('a branch build publishes an immutable artifact and a rolling prerelease, in that order', () => {
  const android = parse(ANDROID);
  const build = android.jobs.build;
  const upload = build.steps.find((s) => String(s.uses || '').startsWith('actions/upload-artifact@'));
  assert.ok(upload, 'the build uploads its APK');
  assert.match(String(upload.with.name), /github\.run_id/, 'the artifact name is per run, so it is never overwritten');
  assert.match(String(upload.with.name), /github\.run_attempt/);
  assert.match(String(upload.with.path), /sha256|sha256sum|\.sha256/, 'the artifact carries a checksum');
  // The APK's own metadata file is written beside it.
  const digest = build.steps.find((s) => s.id === 'digest');
  assert.ok(digest, 'the build records the APK checksum');
  assert.match(digest.run, /apksigner verify/, 'the signature is verified, not assumed');
  assert.match(digest.run, /aapt2" dump badging|aapt2 dump badging/, 'the package and version are read from the APK');
  const publish = android.jobs.publish;
  assert.ok(
    publish.steps.some((s) => String(s.uses || '').startsWith('actions/download-artifact@')),
    'publishing reads back the built bytes instead of rebuilding',
  );
  const run = publish.steps.find((s) => s.run && s.run.includes('release.mjs'));
  assert.ok(run, 'publishing goes through the release script');
  assert.match(run.run, /--sha256|sha256/, 'publishing checks the bytes it downloaded');
});

test('a tag builds the APK into the release for that tag', () => {
  const release = parse(RELEASE);
  const job = release.jobs.release;
  assert.ok(job, 'release.yml builds the APK');
  assert.deepEqual(job.permissions, { contents: 'write' });
  const run = shell(job);
  assert.match(run, /release\.mjs tag --tag "\$RELEASE_TAG"/, 'the release for the tag is created or found');
  assert.match(run, /release\.mjs upload --release-id "\$RELEASE_ID"/, 'the APK is attached by id');
  // The APK is built from the tag's tree, and the helper comes from the workflow's own commit.
  assert.ok(job.steps.some((s) => String(s.with && s.with.ref) === '${{ github.event.inputs.tag || github.ref }}'));
  assert.ok(job.steps.some((s) => String(s.with && s.with.path) === '.ci'));
  assert.match(run, /\.ci\/scripts\/name-artifact\.sh/, 'the helper is the workflow commit\'s, not the tag\'s');
  // The version comes from the tag, and a tag that is not x.y.z fails the run.
  const stamp = job.steps.find((s) => s.id === 'version');
  assert.ok(stamp, 'the version is stamped from the tag');
  assert.match(stamp.run, /VERSION/, 'VERSION is what the build reads');
  assert.match(stamp.run, /is not vX\.Y\.Z/);
});

test('the APK is never published under a name that hides its signing', () => {
  const release = parse(RELEASE);
  const job = release.jobs.release;
  const name = job.steps.find((s) => s.id === 'name');
  // The profile passed to the namer is the signing state, so a test-signed build is _debug.
  assert.match(name.run, /profile=\$\(/);
  assert.match(name.run, /HAS_KEYSTORE/);
  assert.match(name.run, /--release "\$profile"/);
  const check = job.steps.find((s) => s.run && s.run.includes('apksigner verify'));
  assert.ok(check, 'the release APK is signature-checked');
  assert.match(check.run, /certificate SHA-256 digest/, 'the certificate is read from the APK');
  assert.match(check.run, /the release keystore is configured, but the APK carries the test key/);
});

test('the rolling release is a prerelease, so it is never offered as a release', () => {
  // Comments name endpoints; only code paths count.
  const source = read('scripts/release.mjs')
    .split('\n')
    .filter((line) => !/^\s*(\/\/|\*|\/\*)/.test(line))
    .join('\n');
  assert.match(source, /prerelease: true/, 'the rolling release is a prerelease');
  assert.match(source, /is not a prerelease; refusing to publish into it/, 'an existing full release is refused');
  assert.ok(!/releases\/latest/.test(source), 'nothing reads or writes /releases/latest');
  assert.ok(!/\/releases\/tags\//.test(source), 'no release is looked up by tag URL');
});

test('the build the app reads its version from is the repository VERSION file', () => {
  const gradle = read('app/build.gradle.kts');
  assert.match(gradle, /rootProject\.file\("VERSION"\)/, 'the app reads the root VERSION');
  assert.match(gradle, /1_000_000/, 'versionCode uses the x*1e6 + y*1e3 + z scheme');
  assert.match(gradle, /keystore\.properties/, 'production signing comes from an ignored file, never the tree');
  const ignored = read('.gitignore');
  assert.match(ignored, /keystore\.properties/);
  assert.match(ignored, /\*\.keystore/);
});

test('the test signing key is configured from a secret, and its absence is reported', () => {
  const android = parse(ANDROID);
  const steps = android.jobs.build.steps;
  const configure = steps.find((s) => s.name === 'Configure the test signing key');
  assert.ok(configure, 'the build can use a stable test key');
  assert.match(String(configure.if), /HAS_TEST_KEYSTORE/);
  assert.match(String(configure.env.KEYSTORE_B64), /secrets\.ANDROID_TEST_KEYSTORE_BASE64/);
  assert.match(configure.run, /test-keystore\.properties/, 'it writes the file the build reads');
  assert.match(configure.run, /keytool -list/, 'the key is checked before it is used');
  // Without it, the run says exactly what that costs: no cross-run installs.
  const note = steps.find((s) => s.name === 'Note the test signing key');
  assert.ok(note, 'the absence of a stable key is reported');
  assert.match(note.run, /will not install over one from another run/);
  // And the APK is checked against the key that was configured.
  const digest = steps.find((s) => s.id === 'digest');
  assert.match(digest.run, /apksigner verify/);
  assert.match(digest.run, /HAS_TEST_KEYSTORE/);
  // The repository contains no key: both keystore files are ignored.
  const ignored = read('.gitignore');
  assert.match(ignored, /test-keystore\.properties/);
  assert.match(ignored, /\*\.keystore/);
});

test('the tag build can use the stable test key too', () => {
  const release = parse(RELEASE);
  const run = shell(release.jobs.release);
  assert.match(run, /ANDROID_TEST_KEYSTORE_BASE64/, 'the tag build can use the stable test key');
  assert.match(run, /test-keystore\.properties/);
  // An APK that is not signed at all is refused, whatever the secrets say.
  assert.match(run, /certificate SHA-256 digest/);
});

test('signing is compared by certificate fingerprint, not by subject', () => {
  // Two different keys can both say CN=Android Debug; only the SHA-256 digest says
  // whether one APK will install over another.
  const android = parse(ANDROID);
  const release = parse(RELEASE);
  const digest = android.jobs.build.steps.find((s) => s.id === 'digest');
  assert.match(digest.run, /certificate SHA-256 digest/, 'the fingerprint is read from the APK');
  assert.match(digest.run, /certificate-sha256=/, 'and recorded with the artifact');
  assert.ok(!/grep -q 'CN=Android Debug'/.test(digest.run), 'the subject is not what decides');
  assert.match(String(android.jobs.build.env.TEST_KEY_SHA256), /ANDROID_TEST_KEY_SHA256/);
  const releaseShell = shell(release.jobs.release);
  assert.match(releaseShell, /certificate SHA-256 digest/);
  assert.match(releaseShell, /normalize\(\)/, 'keytool and apksigner print the digest differently');
  assert.ok(!/grep -q 'CN=Android Debug'/.test(releaseShell), 'the tag job does not decide by subject');
});

test('the rolling download link is tag-specific and is fetched back', () => {
  const android = parse(ANDROID);
  const publish = android.jobs.publish.steps;
  const check = publish.find((s) => s.name === 'Check the published download');
  assert.ok(check, 'the published URL is verified, not just printed');
  // A prerelease is excluded from /releases/latest, so only the tag names the file.
  assert.match(check.run, /releases\/download\/\$tag\//);
  assert.ok(!/releases\/latest\/download/.test(check.run), 'never the latest-release path');
  assert.match(check.run, /curl -fsSL/, 'the file is downloaded');
  assert.match(check.run, /aapt2" dump badging|aapt2 dump badging/, 'and its package and versionCode read');
  assert.match(check.run, /apksigner" verify|apksigner verify/, 'and its certificate');
  assert.match(check.run, /sha256sum/, 'and its checksum');
  // The summary link is the same tag-specific URL.
  const report = publish.find((s) => s.name === 'Report the download links');
  assert.match(report.run, /releases\/download\/\$tag\/Brapmony_Android_latest\.apk/);
  assert.ok(!/releases\/latest\/download/.test(report.run));
});

test('the upgrade test compares package, fingerprint and versionCode', () => {
  const script = read('upgrade-test.sh');
  assert.match(script, /certificate\(\)/, 'it reads the signing certificate');
  assert.match(script, /first_cert.*second_cert|first_cert" = "\$second_cert/s, 'the two certificates must match');
  assert.match(script, /first_pkg.*second_pkg|first_pkg" = "\$second_pkg/s, 'the two packages must match');
  assert.match(script, /versionCode did not increase/);
  // It installs in place: -r, never an uninstall between the two builds.
  assert.match(script, /D install -r "\$second"/);
  const between = script.slice(script.indexOf('installing the second build'), script.indexOf('D install -r'));
  assert.ok(!/D uninstall/.test(between), 'the second install does not uninstall first');
  assert.equal((script.match(/D uninstall/g) || []).length, 1, 'exactly one uninstall, before the first build');
  assert.ok(
    script.indexOf('D uninstall') < script.indexOf('D install "$first"'),
    'the uninstall is only there to start from nothing',
  );
  assert.match(script, /did not survive the upgrade/, 'the app data is checked, not assumed');
});

test('a publish is not cancelled half way through moving the stable name', () => {
  const android = parse(ANDROID);
  // The workflow-level group cancels a superseded build, which is right; the publish
  // job has its own group, so a publish that is moving assets always finishes.
  assert.equal(android.concurrency['cancel-in-progress'], true);
  const publish = android.jobs.publish.concurrency;
  assert.ok(publish, 'the publish job has its own concurrency group');
  assert.equal(publish['cancel-in-progress'], false, 'and a publish is never cancelled');
  assert.notEqual(publish.group, android.concurrency.group);
});

// `android-actions/setup-android` defaults to the package list `tools platform-tools`.
// cmdline-tools 16.0 no longer carries `tools`, so sdkmanager exits 1 inside the action and
// the job fails before any of its own steps run -- which is how run 43641479379 died with
// only deprecation warnings to read. Every use in this repository must name its packages.
test('setup-android names its packages, because the default list includes the removed tools', () => {
  let seen = 0;
  for (const file of WORKFLOWS) {
    const doc = parse(file);
    for (const [job, spec] of Object.entries(doc.jobs || {})) {
      for (const step of spec.steps || []) {
        if (!String(step.uses || '').startsWith('android-actions/setup-android@')) continue;
        seen += 1;
        const packages = step.with && step.with.packages;
        assert.ok(packages, `${file} ${job}: setup-android must pass packages, not take the default`);
        const list = String(packages).split(/\s+/).filter(Boolean);
        assert.ok(!list.includes('tools'), `${file} ${job}: \`tools\` is not in cmdline-tools 16.0`);
        assert.ok(list.includes('platform-tools'), `${file} ${job}: adb comes from platform-tools`);
      }
    }
  }
  assert.ok(seen >= 2, `expected every setup-android use to be covered, saw ${seen}`);
});

// The repository holds the Android app and nothing else. This is the invariant the
// restructure exists for, so it is checked rather than trusted: a path that belongs to a
// server, a web client or a desktop build must not come back.
test('the repository holds the Android app and nothing else', () => {
  const android = parse(ANDROID);
  const release = parse(RELEASE);
  for (const [file, doc] of [[ANDROID, android], [RELEASE, release]]) {
    const text = read(file);
    for (const foreign of ['src-tauri', 'federation-backend', 'bot-gateway', 'db_schema', 'android-native/', 'src/components']) {
      assert.ok(!text.includes(foreign), `${file} must not name ${foreign}`);
    }
    assert.ok(!/npm run (dev|build|test)\b/.test(text), `${file} must not run the web app's scripts`);
    assert.ok(!/vitest|playwright|tauri/i.test(text), `${file} must not run the web app's tooling`);
    assert.ok(doc.jobs, `${file} has jobs`);
  }
  // And the app really is at the root: the module the build declares is `:app`, beside
  // settings.gradle.kts, not under a sub-directory.
  assert.match(read('settings.gradle.kts'), /include\(":app"\)/);
  assert.ok(read('settings.gradle.kts').includes('rootProject.name'), 'the root project is named');
  assert.match(read('settings.gradle.kts'), /rootProject\.name = "Brapmony"/);
});

// The licence is GPL v3, and only that: the AGPL's additional §7 terms and the trademark
// policy were the upstream project's, and they described files that are no longer here.
test('the repository is licensed GPL v3, with no additional terms', () => {
  const license = read('LICENSE');
  assert.match(license, /GNU GENERAL PUBLIC LICENSE/, 'the licence text is the GPL');
  assert.match(license, /Version 3, 29 June 2007/, 'version 3');
  // The GPL's own §13 names the AGPL, so the title is what says which licence this is.
  assert.ok(!/^GNU AFFERO/mi.test(license), 'the licence text is not the AGPL');
  assert.ok(!/GNU AFFERO GENERAL PUBLIC LICENSE\nVersion 3/i.test(license), 'not the AGPL text');
  assert.match(read('COPYRIGHT'), /GNU General Public License/, 'the copyright statement names the GPL');
  assert.ok(!/Affero/i.test(read('COPYRIGHT')), 'the copyright statement does not claim the AGPL');
  assert.ok(!/additional terms/i.test(read('COPYRIGHT')), 'and claims no additional terms');
  assert.match(String(JSON.parse(read('package.json')).license), /^GPL-3\.0-or-later$/);
  // The APK carries its own licence, so an installed copy travels with it.
  assert.equal(read('app/src/main/assets/legal/LICENSE'), license, 'the APK bundles the same licence');
  assert.ok(read('app/src/main/assets/legal/COPYRIGHT').includes('GNU General Public License'));
  // The AGPL's §7 terms and the trademark policy are gone, with the assets they described.
  for (const gone of ['LICENSE-ADDITIONAL-TERMS.md', 'TRADEMARK.md']) {
    assert.throws(() => read(gone), `\${gone} must not exist`);
  }
});

// One app means one package. A `.debug` suffix made a debug build a second app that could
// not update a release build, and it made every log, script and document carry two names.
test('every build carries one package, and the build type is not part of it', () => {
  const gradle = read('app/build.gradle.kts');
  assert.match(gradle, /applicationId = "io\.github\.wwpol\.brapmony"/, 'the application id is set');
  assert.ok(!/applicationIdSuffix/.test(gradle), 'no build type changes the package');
  assert.match(gradle, /versionNameSuffix = "-debug"/, 'the version name still says which build it is');
  // The workflows check the package they built, and it is that one, in both.
  for (const file of WORKFLOWS) {
    const text = read(file);
    for (const [, pkg] of text.matchAll(/package: name='([^']+)'/g)) {
      assert.equal(pkg, 'io.github.wwpol.brapmony', `${file} expects the one package, not ${pkg}`);
    }
  }
});

// The README is the app's. A reader of it must not be told this repository is a server.
// Its title and wording are the maintainers' to choose; what is checked is that it describes
// the app and does not claim a server.
test('the README describes the app, not a server', () => {
  const readme = read('README.md');
  assert.match(readme, /^# /m, 'it has a title');
  assert.match(readme, /Android/, 'it says what the app is');
  assert.match(readme, /GNU General Public License|GPL v3/, 'it states the licence');
  for (const claim of ['self-host', 'Self-hosting', 'federation backend', 'federation-backend',
                       'Tauri', 'Supabase project', 'docker compose', 'ActivityPub']) {
    assert.ok(!new RegExp(claim, 'i').test(readme), `the README must not claim a \${claim}`);
  }
});
