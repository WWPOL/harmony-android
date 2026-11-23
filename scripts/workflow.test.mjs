// node --test scripts/workflow.test.mjs
//
// The properties of the Android workflows that make publishing safe, checked rather than
// described in a comment: which events can reach a write token, that every action is
// pinned, that a branch build and a tag build cannot collide, and that a tag's APK goes to
// the release for that tag instead of creating a second one.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, globSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const read = (p) => readFileSync(join(root, p), 'utf8');
const parse = (p) => YAML.parse(read(p));

const ANDROID = '.github/workflows/android.yml';
const RELEASE = '.github/workflows/release.yml';
const CLEANUP = '.github/workflows/cleanup.yml';
const WORKFLOWS = [ANDROID, RELEASE, CLEANUP];

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
    if (!spec.permissions) continue;
    const writes = Object.entries(spec.permissions).filter(([, level]) => level === 'write');
    if (!writes.length) continue;
    assert.equal(job, 'publish', `only the publishing job may write, not ${job}`);
    // A pull request, and a pull request from a fork in particular, must never reach it.
    assert.match(String(spec.if), /github\.event_name == 'push'/, `${job} must be push-only`);
    assert.match(String(spec.if), /github\.repository == 'WWPOL\/harmony-android'/, `${job} must be this repository`);
    assert.ok(spec.needs, `${job} must build before it publishes`);
  }
  // The build job that pull requests run holds no permission of its own, and in particular
  // never the ones that sign a provenance statement: a fork's code must not be able to
  // obtain an attestation that says GitHub's workflow produced it.
  assert.equal(android.jobs.build.permissions, undefined);
  for (const signing of ['id-token', 'attestations']) {
    assert.equal(android.jobs.publish.permissions[signing], 'write', `the publish job needs ${signing}: write`);
    assert.equal(android.jobs.build.permissions, undefined, `the build job must not hold ${signing}`);
    assert.equal(android.jobs.tooling.permissions, undefined, `the tooling job must not hold ${signing}`);
  }
  // Nothing publishes a container package, so nothing needs to be allowed to: an APK is not
  // an image, and a package is not a download a phone can use.
  for (const file of WORKFLOWS) {
    for (const [job, spec] of Object.entries(parse(file).jobs)) {
      assert.equal(spec.permissions && spec.permissions.packages, undefined,
        `${file} ${job}: no job may ask for packages: write`);
      assert.equal(spec.permissions && spec.permissions['artifact-metadata'], undefined,
        `${file} ${job}: no job may ask for artifact-metadata: write`);
    }
  }
  // The release workflow writes only from the job that creates the release, and that job
  // writes the release and the attestation of its APK.
  const release = parse(RELEASE);
  assert.deepEqual(release.permissions, { contents: 'read' });
  assert.deepEqual(release.jobs.release.permissions, {
    contents: 'write',
    'id-token': 'write',
    attestations: 'write',
  });
  // The cleanup workflow deletes releases, tags and artifacts, so it writes too -- and it
  // is the one workflow a push can never run: deleting a release is not something a build
  // should do by accident. It runs by hand only, lists before it deletes, and takes no
  // code from anywhere but this repository.
  const cleanup = parse(CLEANUP);
  assert.deepEqual(Object.keys(cleanup.on), ['workflow_dispatch'], 'cleanup runs only when dispatched');
  assert.deepEqual(cleanup.permissions, { contents: 'read' });
  assert.deepEqual(cleanup.jobs.cleanup.permissions, { contents: 'write', actions: 'write' });
  const cleanupRun = cleanup.jobs.cleanup.steps.find((s) => s.run && s.run.includes('release.mjs'));
  assert.match(cleanupRun.run, /--apply/, 'it can delete');
  assert.match(String(cleanupRun.env.APPLY), /inputs\.apply/, 'but only when the dispatch asks for it');
  assert.match(cleanupRun.run, /if \[ "\$APPLY" = 'true' \]/, 'and the default is a dry run');
  // Every job of it is a dispatch, so no push, pull request or schedule can reach it.
  for (const [job, spec] of Object.entries(cleanup.jobs)) {
    assert.equal(spec.if, undefined, `${job} runs only on the event that triggers it`);
  }
});

test('every push to a branch builds the client', () => {
  const android = parse(ANDROID);
  assert.deepEqual(android.on.push.branches, ['**'], 'every branch builds, not only main');
  // Literally every push: a path filter would leave the previous commit's APK
  // standing after a push that touched nothing under the app.
  assert.equal(android.on.push.paths, undefined, 'push has no path filter');
  assert.ok(android.on.pull_request, 'pull requests are still checked');
  assert.ok('workflow_dispatch' in android.on, 'a branch can be built by hand');
  // A push and a pull request from the same branch must not fight over one concurrency group.
  assert.match(android.concurrency.group, /github\.ref/);
});

test('a branch build publishes an immutable artifact and a rolling release, in that order', () => {
  const android = parse(ANDROID);
  const build = android.jobs.build;
  const upload = build.steps.find((s) => String(s.uses || '').startsWith('actions/upload-artifact@'));
  assert.ok(upload, 'the build uploads its APK');
  // The artifact is named after the APK itself, so what a run uploads and what a release
  // publishes are called the same thing. It is still unique per attempt: the namer adds
  // `-attempt-<n>` when this is a re-run, which is what keeps it immutable.
  assert.match(String(upload.with.name), /steps\.name\.outputs\.artifact/, 'the artifact is named by the namer');
  const namer = build.steps.find((s) => String(s.run || '').includes('name-artifact.sh'));
  assert.match(namer.run, /name-artifact\.sh/, 'the namer names the APK');
  assert.match(read('scripts/name-artifact.sh'), /GITHUB_RUN_ATTEMPT/, 'the namer knows which attempt this is');
  assert.match(read('scripts/name-artifact.sh'), /artifact="\$stem-attempt-\$attempt"/, 'a re-run gets its own artifact name');
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
  assert.equal(job.permissions.contents, 'write');
  const run = shell(job);
  assert.match(run, /release\.mjs tag --tag "\$RELEASE_TAG"/, 'the release for the tag is created or found');
  assert.match(run, /release\.mjs upload --release-id "\$RELEASE_ID"/, 'the APK is attached by id');
  // The tag's APK goes on the tag's release and nowhere else: no container package, which
  // would be a second place to look for the same file that no phone can open.
  assert.ok(!/publish-package/.test(run), 'the tag\'s APK is not pushed to a registry');
  assert.ok(!/oras|ghcr\.io/.test(run), 'nothing talks to a container registry');
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
  // The profile passed to the namer is the signing state, so a test-signed build is -debug.
  assert.match(name.run, /profile=\$\(/);
  assert.match(name.run, /HAS_KEYSTORE/);
  assert.match(name.run, /--release "\$profile"/);
  const check = job.steps.find((s) => s.run && s.run.includes('apksigner verify'));
  assert.ok(check, 'the release APK is signature-checked');
  assert.match(check.run, /certificate SHA-256 digest/, 'the certificate is read from the APK');
  assert.match(check.run, /the release keystore is configured, but the APK carries the test key/);
});

// What the repository offers as its release: the default branch's build. A prerelease is
// skipped by /releases/latest and by the Releases box on the repository page, so before
// this the repository offered no release at all and only the tag-specific download URL
// worked. Every other branch stays a prerelease: a branch build is not an offer.
test('the default branch is a release, and another branch is a prerelease', () => {
  // Comments name endpoints; only code paths count.
  const source = read('scripts/release.mjs')
    .split('\n')
    .filter((line) => !/^\s*(\/\/|\*|\/\*)/.test(line))
    .join('\n');
  assert.match(source, /prerelease: !published/, 'the state follows the branch');
  assert.match(source, /function isPublishedRelease/, 'and is decided in one place');
  assert.ok(!/releases\/latest/.test(source), 'nothing reads or writes /releases/latest');
  assert.ok(!/\/releases\/tags\//.test(source), 'no release is looked up by tag URL');
  // The workflow says which branch that is, in one place, and passes it to the publisher.
  const android = parse(ANDROID);
  assert.equal(String(android.env.DEFAULT_BRANCH), 'main');
  const publish = android.jobs.publish.steps.find((s) => s.run && s.run.includes('release.mjs rolling'));
  assert.match(publish.run, /--default-branch "\$DEFAULT_BRANCH"/, 'the publisher is told which branch is the release');
  assert.match(publish.run, /--version "\$VERSION"/, 'and names the release after the version');
});

test('the app\'s version is the VERSION line plus the commit, never a hand-bumped number', () => {
  const gradle = read('app/build.gradle.kts');
  assert.match(gradle, /rootProject\.file\("VERSION"\)/, 'the app reads the root VERSION');
  // The build is identified by its commit, not by the release line: `1.9.6` was one value
  // in VERSION from the first commit on, so every build of every commit reported it, and
  // `versionCode` was a constant 1009006 that only ever went up when somebody remembered
  // to edit the file. The name carries the commit and the code comes from git.
  assert.match(gradle, /"rev-parse", "HEAD"/, 'the build reads the commit it is of');
  assert.match(gradle, /"rev-list", "--count", "HEAD"/, 'and its versionCode is the commit count');
  assert.match(gradle, /BUILD_NUMBER_BASE/, 'the build number starts above the old scheme, so the change is not a downgrade');
  assert.match(gradle, /harmonyVersionCode/, 'and can be overridden for the upgrade test');
  assert.match(gradle, /harmonyReleaseBuild/, 'a tag build reports the release line rather than the commit');
  // A checkout without the history cannot derive the number, so every job that builds the
  // app fetches all of it. A shallow clone reports 1 for every commit.
  for (const file of [ANDROID, RELEASE]) {
    const doc = parse(file);
    const checkout = doc.jobs.build?.steps?.find((s) => String(s.uses || '').startsWith('actions/checkout'))
      ?? doc.jobs.release.steps.find((s) => String(s.uses || '').startsWith('actions/checkout'));
    assert.equal(checkout.with?.['fetch-depth'], 0, `${file} must fetch the whole history`);
  }
  assert.match(gradle, /keystore\.properties/, 'production signing comes from an ignored file, never the tree');
  const ignored = read('.gitignore');
  assert.match(ignored, /keystore\.properties/);
  assert.match(ignored, /\*\.keystore/);
});

// The versionCode is derived in three places -- Gradle, the build job's version step and
// the tag job's -- and each writes the base out rather than reading it from the others,
// so each is an independent expectation of what the APK should report. They have to agree:
// a workflow that expected a different number than Gradle wrote would fail every run at
// the manifest check, and a wrong base would make the first build under this scheme a
// downgrade of the last one under the old.
test('the build number base is the same number everywhere it is written', () => {
  const gradle = read('app/build.gradle.kts');
  const declared = /val BUILD_NUMBER_BASE = ([0-9_]+)/.exec(gradle);
  assert.ok(declared, 'app/build.gradle.kts declares BUILD_NUMBER_BASE');
  const base = Number(declared[1].replace(/_/g, ''));
  // Above 1_999_999, the highest code the old maj*1e6 + min*1e3 + pat scheme could give a
  // 1.x release, so this change is not a downgrade Android refuses.
  assert.ok(base > 1_999_999, `the base ${base} must be above the old scheme's ceiling`);
  for (const file of [ANDROID, RELEASE]) {
    const doc = parse(file);
    const job = file === ANDROID ? doc.jobs.build : doc.jobs.release;
    const step = job.steps.find((st) => st.id === 'version');
    assert.ok(step, `${file} has a version step`);
    assert.match(step.run, new RegExp(`\\$\\(\\(${base} \\+ commits\\)\\)`),
      `${file}'s version step must add the same base (${base}) to the commit count`);
  }
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

// `keytool -list` proves the store opens with the password; it says nothing about whether
// the alias names a key inside it. Both signing steps therefore check the alias too, and
// fail before the build rather than at Gradle's signing task. The release keystore's step
// is the one a tag build reaches, and it had the same gap.
test('both signing steps check that the alias names a key in the keystore', () => {
  const cases = [
    [ANDROID, 'build', 'Configure the test signing key', 'ANDROID_TEST_KEY_ALIAS'],
    [RELEASE, 'release', 'Configure the test signing key', 'ANDROID_TEST_KEY_ALIAS'],
    [RELEASE, 'release', 'Configure release signing', 'ANDROID_KEY_ALIAS'],
  ];
  for (const [file, job, name, variable] of cases) {
    const step = parse(file).jobs[job].steps.find((s) => s.name === name);
    assert.ok(step, `${file} ${job} has no step ${name}`);
    // The alias is checked against the store, with the alias the secrets name. The command
    // is wrapped across lines with a trailing `\`, so the match spans whitespace.
    assert.match(step.run, /keytool -list[\s\S]{0,200}?-alias "\$KEY_ALIAS"/, `${file} ${name} does not check the alias`);
    // And the failure says which setting to fix.
    assert.match(step.run, new RegExp(`${variable} is not|Check ${variable}`), `${name} does not say what to fix`);
  }
});

// The check is only worth anything if its expectation comes from somewhere other than the
// keystore the same run configured: a run that derived it from the secret would prove that
// the secret agrees with itself and pass any certificate. The expectation is the repository
// variable, and a run with the secret but without it fails rather than publishing unproven.
test('the expected certificate is pinned independently of the configured keystore', () => {
  const android = parse(ANDROID);
  const digest = android.jobs.build.steps.find((s) => s.id === 'digest');
  const run = digest.run;
  assert.match(String(android.jobs.build.env.TEST_KEY_SHA256), /vars\.ANDROID_TEST_KEY_SHA256/);
  assert.match(run, /expected_key=\$\(normalize "\$TEST_KEY_SHA256"\)/);
  // The expected value comes from the variable and never from the keystore the same run
  // configured: no command in the step runs keytool or opens test.keystore, so it cannot
  // take its expectation from the same secret it is checking. (Both words do appear, in a
  // comment and in the error message that tells an operator how to read the fingerprint;
  // neither is a command.)
  assert.ok(!/test\.keystore/.test(run), 'the step never opens the configured keystore');
  const commands = run.split('\n').map((l) => l.trim())
    .filter((l) => l && !l.startsWith('#') && !l.startsWith('echo'));
  assert.ok(!commands.some((l) => /\bkeytool\b/.test(l)), 'the expectation is not read from the keystore');
  // A secret without the pin fails, and says which variable to set, rather than comparing
  // against an empty string and passing whatever the APK carries.
  assert.match(run, /if \[ -z "\$expected_key" \]/);
  assert.match(run, /ANDROID_TEST_KEY_SHA256 is not/);
  // And a certificate that differs from the pin fails the build, so nothing is published.
  assert.match(run, /if \[ "\$fingerprint" != "\$expected_key" \]/);
  // The check runs before the checksum, the artifact and the publish: a mismatch stops the
  // run before anything is uploaded rather than after.
  const order = (name) => run.indexOf(name);
  assert.ok(order('expected_key=') < order('sha256sum'), 'the certificate is checked before the checksum is written');
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
  assert.match(check.run, /releases\/download\/\$tag\/Brapmony-latest\.apk/, 'the stable name is the new one');
  assert.match(check.run, /curl -fsSL/, 'the file is downloaded');
  assert.match(check.run, /aapt2" dump badging|aapt2 dump badging/, 'and its package and versionCode read');
  assert.match(check.run, /apksigner" verify|apksigner verify/, 'and its certificate');
  assert.match(check.run, /sha256sum/, 'and its checksum');
  // The summary link is the same tag-specific URL.
  const report = publish.find((s) => s.name === 'Report the download links');
  assert.match(report.run, /releases\/download\/\$tag\/Brapmony-latest\.apk/);
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

// `name-artifact.sh` exits 2 on a profile it does not know, and android.yml called it as
// `name-artifact.sh Android debug <apk>` after the restructure. Every push to every branch
// therefore died at that step with exit 2, and every step after it -- the digest check, the
// artifact upload -- was skipped, so no APK artifact and no rolling prerelease was ever
// produced. The tests above passed throughout, because they only looked for the script's
// name. This one runs the namer with the arguments each workflow actually passes and
// asserts the file it produces, so a call the script refuses fails here instead of on a
// runner.
test('the APK namer is called with arguments it accepts, and names the file', () => {
  const dir = mkdtempSync(join(tmpdir(), 'namer-'));
  const apk = join(dir, 'app-debug.apk');
  writeFileSync(apk, 'not a real apk, only a file to be renamed');
  const env = {
    ...process.env,
    GITHUB_REF_NAME: 'main',
    GITHUB_SHA: 'c214e8e5b731df3db9ac1062510ff5391d7f573a',
    GITHUB_OUTPUT: join(dir, 'github-output'),
  };
  writeFileSync(env.GITHUB_OUTPUT, '');

  // The branch build: a test-signed APK named for the version the APK reports, which is
  // the release line and the commit. The workflow passes it as APP_VERSION_NAME rather
  // than letting the namer derive it a second time, so the file name and the manifest are
  // one claim; `-debug` is not appended twice when the version name already says it.
  const versionName = '0.1.0-c214e8e';
  const branchEnv = { ...env, APP_VERSION_NAME: versionName };
  const branch = spawnSync('bash', ['scripts/name-artifact.sh', 'debug', apk], { cwd: root, env: branchEnv, encoding: 'utf8' });
  assert.equal(branch.status, 0, `the branch build's namer call failed: ${branch.stderr}`);
  const named = branch.stdout.trim();
  assert.equal(basename(named), `Brapmony-${versionName}-debug.apk`, 'the branch APK is named for the version it reports');
  assert.ok(existsSync(named), 'the APK really was renamed to that name');
  const output = readFileSync(env.GITHUB_OUTPUT, 'utf8');
  assert.match(output, /^path=/m, 'the path is written to GITHUB_OUTPUT');
  assert.match(output, new RegExp(`^artifact=Brapmony-${versionName}-debug$`, 'm'), 'and the artifact name with it');
  // A re-run of the same commit must not land on the first attempt's artifact name.
  writeFileSync(apk, 'a second attempt');
  const rerun = spawnSync('bash', ['scripts/name-artifact.sh', 'debug', apk], {
    cwd: root, env: { ...branchEnv, GITHUB_RUN_ATTEMPT: '2' }, encoding: 'utf8',
  });
  assert.equal(rerun.status, 0, rerun.stderr);
  assert.match(readFileSync(env.GITHUB_OUTPUT, 'utf8'), new RegExp(`^artifact=Brapmony-${versionName}-debug-attempt-2$`, 'm'));

  // The tag build: `--release`, with the profile as the signing state and the plain
  // release line as the version. A release is one named point, not a stream of builds.
  for (const profile of ['release', 'debug']) {
    const file = join(dir, `app-release-${profile}.apk`);
    writeFileSync(file, 'a file to be renamed');
    const tagged = spawnSync('bash', ['scripts/name-artifact.sh', '--release', profile, file], {
      cwd: root, env: { ...env, APP_VERSION_NAME: '0.1.0' }, encoding: 'utf8',
    });
    assert.equal(tagged.status, 0, `the tag build's namer call failed for ${profile}: ${tagged.stderr}`);
    const expected = profile === 'release' ? /Brapmony-0\.1\.0\.apk$/ : /Brapmony-0\.1\.0-debug\.apk$/;
    assert.match(tagged.stdout.trim(), expected, `a ${profile}-profile tag build is named for its signing`);
  }

  // Without APP_VERSION_NAME the namer still derives a name from VERSION and GITHUB_SHA,
  // which is what `upgrade-test.sh` relies on when it names its two builds.
  const derivedFile = join(dir, 'app-derived.apk');
  writeFileSync(derivedFile, 'a file to be renamed');
  const derived = spawnSync('bash', ['scripts/name-artifact.sh', 'debug', derivedFile], {
    cwd: root, env, encoding: 'utf8',
  });
  assert.equal(derived.status, 0, derived.stderr);
  assert.match(basename(derived.stdout.trim()), /^Brapmony-\d+\.\d+\.\d+-c214e8e-debug\.apk$/);

  // And the exact string each workflow passes, taken from the workflow itself, is one the
  // namer accepts: the check above would still pass if a workflow passed `Android`.
  const android = parse(ANDROID);
  const call = android.jobs.build.steps.find((s) => String(s.run || '').includes('name-artifact.sh'));
  assert.ok(call, 'the branch build names its APK with the script');
  const args = /name-artifact\.sh\s+([^\n]+)/.exec(call.run)[1];
  assert.ok(!/\bAndroid\b/.test(args), `the namer takes a profile (release|debug), not a platform: ${args}`);
  assert.match(args, /^(--release )?(release|debug)\b/, `the namer call starts with a profile it knows: ${args}`);
});

// GitHub runs every `run:` step with `bash -eo pipefail`, so a command whose output is
// piped into `head` fails the step with SIGPIPE (exit 141) as soon as it writes past the
// first line. The publish job read the published APK's badging that way, which failed
// every single time it was reached. The build job's digest step already writes to a file
// for this reason; the rule is that anything that can write more than one line is not put
// on the left of a pipe that stops reading.
test('no step pipes a command that keeps writing into head', () => {
  const offenders = [];
  for (const file of WORKFLOWS) {
    const doc = parse(file);
    for (const [job, spec] of Object.entries(doc.jobs || {})) {
      for (const step of spec.steps || []) {
        if (!step.run) continue;
        for (const line of step.run.split('\n')) {
          // `aapt2 dump badging` and `apksigner verify` both print several lines; neither
          // may be the left side of a pipe into head. `sed -n ...p` over a file is fine.
          if (!/\|/.test(line) || !/\bhead\b/.test(line)) continue;
          const left = line.slice(0, line.indexOf('|'));
          if (/\baapt2\b|\bapksigner\b/.test(left)) {
            offenders.push(`${file} ${job} ${step.name || step.uses}: ${line.trim()}`);
          }
        }
      }
    }
  }
  assert.deepEqual(offenders, [], `these pipe a multi-line command into head, which fails under pipefail:\n${offenders.join('\n')}`);
});

// `scripts/workflow-steps.mjs` is how a change to a step is run before it is pushed, so it
// has to understand the `if:` forms the workflows actually use. It understood only a single
// comparison, and release.yml guards its test-signing step with two joined by `&&`: that
// step could not be run locally at all, and the tool exited 2 rather than running it. This
// runs the runner against each guarded step -- with no secrets, so each one skips and
// nothing is executed -- and fails if a condition cannot be evaluated. A step whose `if:`
// is refused here is a step nobody can check on this machine.
test('the local step runner can evaluate every guarded step', () => {
  const guarded = [];
  for (const file of WORKFLOWS) {
    const doc = parse(file);
    for (const [job, spec] of Object.entries(doc.jobs || {})) {
      for (const step of spec.steps || []) {
        if (step.run && step.if) guarded.push({ file, job, name: step.name, when: String(step.if) });
      }
    }
  }
  assert.ok(guarded.length >= 4, 'the workflows still guard their signing steps');

  // The compound form is the one that was refused; keep it covered explicitly.
  assert.ok(
    guarded.some((g) => /\|\||&&/.test(g.when)),
    'a guarded step joins two conditions, which the runner must evaluate',
  );

  for (const { file, job, name, when } of guarded) {
    const run = spawnSync(
      'node',
      ['scripts/workflow-steps.mjs', file, job, '--step', name],
      { cwd: root, encoding: 'utf8', env: { ...process.env, ANDROID_TEST_KEYSTORE_BASE64: '' } },
    );
    assert.notEqual(run.status, 2, `the runner cannot evaluate \`if: ${when}\` (${file} ${job}):\n${run.stderr}`);
    assert.equal(run.status, 0, `running ${name} with no secrets failed:\n${run.stdout}\n${run.stderr}`);
  }
});

// The APK is published as a release asset and as nothing else. It was also pushed to
// ghcr.io as an OCI artifact, which on a client-only repository meant a container package
// on the repository page -- `docker pull ghcr.io/<owner>/<repo>` -- for a file that is not
// an image, that no phone can install from, and that needs `oras` or `docker` to fetch. The
// release is the route a person uses; the package was a second place to look that was
// harder to reach than the first.
test('nothing publishes the APK as a container package', () => {
  assert.ok(!existsSync(join(root, 'scripts/publish-package.sh')), 'the package script is gone');
  for (const file of WORKFLOWS) {
    const doc = parse(file);
    for (const [job, spec] of Object.entries(doc.jobs)) {
      const text = shell(spec);
      assert.ok(!/publish-package/.test(text), `${file} ${job}: no package push`);
      assert.ok(!/oras/.test(text), `${file} ${job}: no OCI client`);
      assert.ok(!/ghcr\.io|pkgs\/container/.test(text), `${file} ${job}: nothing names a container registry`);
      for (const step of spec.steps) {
        assert.ok(!/oras-project/.test(String(step.uses || '')), `${file} ${job}: no oras action`);
      }
    }
  }
  // And the documentation offers the release, not a package, as where to download: no
  // Markdown file links a package page, and the README's Download section is the release's
  // download URL.
  for (const doc of globSync('**/*.md', { cwd: root, exclude: (p) => p.startsWith('node_modules/') })) {
    assert.ok(!/pkgs\/container/.test(read(doc)), `${doc} links a package page`);
  }
  const download = read('README.md').slice(read('README.md').indexOf('# Download'));
  const section = download.slice(0, download.indexOf('\n# '));
  assert.match(section, /releases\/download\/android-rolling-main\/Brapmony-latest\.apk/,
    'the README links the APK itself, which is what a phone taps');
  // No command a person has to run to get the file: the link is the download.
  const beforeSteps = section.slice(0, section.indexOf('1. Open that link'));
  assert.ok(!/oras|docker pull/.test(beforeSteps), 'and asks no tool but a browser to fetch it');
});

// The APK carries a signed build-provenance attestation, and it is read back from outside
// before the run finishes: a statement that does not verify fails the run rather than being
// described as provenance it does not have. There is one subject -- the file a person
// downloads -- because the release is the only place the file is published.
test('the published APK is attested, and the attestation is verified', () => {
  const cases = [
    [ANDROID, 'publish', 'android.yml', 'Publish'],
    [RELEASE, 'release', 'release.yml', 'Name the APK'],
  ];
  for (const [file, job, workflow, apkStep] of cases) {
    const spec = parse(file).jobs[job];
    const steps = spec.steps;
    const attest = steps.filter((s) => String(s.uses || '').startsWith('actions/attest@'));
    assert.equal(attest.length, 1, `${file}: one attestation, for the APK`);
    // The subject is the file itself, so a downloader's copy is what is checked.
    const apk = attest[0];
    assert.ok(apk.with && apk.with['subject-path'], `${file}: the APK is attested by path`);
    assert.match(String(apk.with['subject-path']), new RegExp(`steps\\.${steps.find((s) => s.name === apkStep).id}\\.outputs`),
      `${file}: the APK attested is the one this run published`);
    assert.equal(apk.with['subject-digest'], undefined, `${file}: there is no second subject`);
    // And the statement is read back, pinned to this repository, this workflow and this
    // commit: an attestation that says something else is not this run's.
    const verify = steps.find((s) => s.name === 'Verify the attestation');
    assert.ok(verify, `${file}: the attestation is verified, not assumed`);
    assert.match(verify.run, /--repo "\$GITHUB_REPOSITORY"/);
    assert.match(verify.run, /--signer-workflow "\$WORKFLOW"/);
    assert.match(String(verify.env.WORKFLOW), new RegExp(`${workflow.replace(/\./g, '\\.')}$`),
      `${file}: the signer is this workflow`);
    assert.match(verify.run, /--source-digest "\$COMMIT"/, `${file}: the attestation must name the commit that was built`);
    // A pull request never reaches the signing: the build job holds none of it.
    assert.equal(parse(file).jobs[job].permissions['id-token'], 'write');
  }
});

// GitHub computes a digest of every release asset it stores. The publisher compares it with
// the file it uploaded, which is the only check of the stored bytes that does not come from
// this workflow itself.
test('the stored asset is checked against GitHub\'s own digest', () => {
  const source = read('scripts/release.mjs');
  assert.match(source, /export function checkAssetDigest/, 'the comparison is in one place');
  assert.match(source, /sha256:\$\{String\(sha256\)\.toLowerCase\(\)\}/, 'it compares sha256:hex');
  assert.match(source, /refusing to publish it/, 'and refuses a mismatch');
  // Every upload goes through it.
  const upload = source.slice(source.indexOf('async function uploadFile'), source.indexOf('export async function publishBuild'));
  assert.match(upload, /checkAssetDigest\(asset/, 'an upload is checked');
  // "Already these bytes" is GitHub's digest too, not a label: a re-run that finds the name
  // compares what GitHub stores with the file in hand, and a rebuild goes under its own name
  // instead of being reported as kept.
  assert.match(source, /export function storedBytes/, 'sameness is decided in one place');
  const sameness = source.slice(source.indexOf('export function storedBytes'), source.indexOf('export function ownsAsset'));
  assert.match(sameness, /asset\?\.digest/, 'by the digest GitHub reports');
  assert.match(sameness, /asset\?\.size === size/, 'and by the byte count where no digest is reported');
  const build = source.slice(source.indexOf('export async function publishBuild'), source.indexOf('export async function replaceStable'));
  assert.match(build, /storedBytes\(a, sha256, size\)/, 'a re-run compares what is stored with the file');
  const stable = source.slice(source.indexOf('export async function replaceStable'), source.indexOf('export async function clearLegacyLabels'));
  assert.match(stable, /storedBytes\(current\[0\]/, 'and so does the stable name');
  // A digest that is not reported leaves nothing to compare, and is not an error.
  assert.match(source, /if \(!reported\) return \{ checked: false, digest: null \}/);
});

// The artifact the publish job reads back is checked against the digest GitHub recorded
// when the build uploaded it: a transfer that changed anything fails before publishing.
test('the downloaded artifact is checked against the digest it was uploaded with', () => {
  const android = parse(ANDROID);
  const download = android.jobs.publish.steps.find((s) => String(s.uses || '').startsWith('actions/download-artifact@'));
  assert.ok(download, 'the publish job downloads what the build uploaded');
  assert.equal(download.with['digest-mismatch'], 'error', 'a digest mismatch fails the job rather than being ignored');
  // The build reports the checksum, and the publish job compares it again with the file it
  // received: two digests, one computed here and one recorded there.
  const publish = android.jobs.publish.steps.find((s) => s.id === 'publish');
  assert.match(publish.run, /actual=\$\(sha256sum/, 'the received file is hashed');
  assert.match(publish.run, /"\$actual" != "\$SHA256"/, 'and compared with what the build reported');
});

// A rolling tag names the newest build of its branch, and the release page shows the
// commit that tag points at. A tag left where the release was created showed the first
// build's commit beside the newest build's assets, so the publish moves it and the
// workflow passes the commit that was actually built.
test('the publish moves the rolling tag onto the commit it built', () => {
  const publish = parse(ANDROID).jobs.publish.steps.find((s) => s.id === 'publish');
  assert.match(String(publish.env.COMMIT), /github\.sha/, 'the step is given the commit it built');
  const source = read('scripts/release.mjs');
  assert.match(source, /export async function moveRollingTag/, 'one function moves the tag');
  assert.match(source, /refusing to move \$\{tag\}: not a rolling tag/, 'and refuses anything but a rolling tag');
  // The tag moves with the build, and its name is the release's own tag, not a second one.
  assert.match(publish.run, /--commit "\$COMMIT"/, 'the commit goes with the publish');
});

// GitHub shows an asset's label **in place of its file name**. Every asset this workflow
// uploaded carried a label naming its checksum, so the release page listed
// `brapmony-android sha256:…` five times and not one file name -- there was no
// `Brapmony-latest.apk` on the page to tap, although the download URL worked.
test('nothing the publisher uploads carries a label, which would hide the file name', () => {
  const source = read('scripts/release.mjs');
  // The upload function has no way to send a label: its query is the name alone, and no
  // caller passes one.
  const upload = source.slice(source.indexOf('async function uploadFile'), source.indexOf('export async function publishBuild'));
  assert.match(upload, /new URLSearchParams\(\{ name \}\)/, 'an upload names the file and nothing else');
  assert.ok(!/label/.test(upload), 'the upload path does not mention a label at all');
  for (const m of source.matchAll(/uploadFile\(gh, \{([^}]*)\}/g)) {
    assert.ok(!/label/.test(m[1]), `a call to uploadFile passes a label: ${m[1].trim()}`);
  }
  // The ones an earlier version labelled are cleared, so their names show again.
  assert.match(source, /export async function clearLegacyLabels/, 'the old labels are cleared');
  assert.match(source, /JSON\.stringify\(\{ label: '' \}\)/, 'cleared by sending an empty label');
  // And what the release page will show is read back rather than assumed.
  assert.match(source, /did not clear, so those assets still show a hash where their name should be/,
    'a label that will not clear is reported');
});
