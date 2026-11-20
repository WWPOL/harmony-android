// node --test scripts/workflow.test.mjs
//
// The properties of the Android workflows that make publishing safe, checked rather than
// described in a comment: which events can reach a write token, that every action is
// pinned, that a branch build and a tag build cannot collide, and that a tag's APK goes to
// the release for that tag instead of creating a second one.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
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
  assert.deepEqual(android.on.push.branches, ['**'], 'every branch builds, not only main');
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

  // The branch build: a debug APK, named after its version, ref and commit.
  const branch = spawnSync('bash', ['scripts/name-artifact.sh', 'debug', apk], { cwd: root, env, encoding: 'utf8' });
  assert.equal(branch.status, 0, `the branch build's namer call failed: ${branch.stderr}`);
  const named = branch.stdout.trim();
  assert.match(
    named,
    /Brapmony_Android_V\d+\.\d+\.\d+_debug-main-c214e8e\.apk$/,
    'the branch APK is named after its version, ref and commit',
  );
  assert.ok(existsSync(named), 'the APK really was renamed to that name');
  assert.match(readFileSync(env.GITHUB_OUTPUT, 'utf8'), /^path=/m, 'the path is written to GITHUB_OUTPUT');

  // The tag build: `--release`, with the profile as the signing state.
  for (const profile of ['release', 'debug']) {
    const file = join(dir, `app-release-${profile}.apk`);
    writeFileSync(file, 'a file to be renamed');
    const tagged = spawnSync('bash', ['scripts/name-artifact.sh', '--release', profile, file], {
      cwd: root, env, encoding: 'utf8',
    });
    assert.equal(tagged.status, 0, `the tag build's namer call failed for ${profile}: ${tagged.stderr}`);
    const expected = profile === 'release' ? /Brapmony_Android_V\d+\.\d+\.\d+\.apk$/ : /Brapmony_Android_V\d+\.\d+\.\d+_debug\.apk$/;
    assert.match(tagged.stdout.trim(), expected, `a ${profile}-profile tag build is named for its signing`);
  }

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
