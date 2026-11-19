# Security policy

This repository is the Android client. It talks to a Harmony instance over that instance's
HTTP API, with the person's own token, and it holds that token and their drafts on the device.

## Reporting a vulnerability

**Please do not open a public issue for a security problem.** Use GitHub's private
vulnerability reporting on this repository: **Security** → **Advisories** → **Report a
vulnerability**. That keeps the report private until a fix ships.

Please include:

- what the issue is and what it lets an attacker do;
- steps to reproduce, or a proof of concept;
- the app version and commit, and the Android version and device;
- any mitigation you can suggest.

## What is in scope

- The app: `app/src/main/java/lol/mony/harmony/`, its manifest, resources and Gradle build.
- The stored session and any secret the app keeps on the device.
- How the app handles an instance's data: rendering, links, intents, notifications and the
  WebView-free UI.
- The release tooling in `scripts/` and the workflows in `.github/workflows/`, where a flaw
  could make a published APK be something other than what was built.

## What is not in scope here

- **The Harmony server.** Its federation, database policies, encryption and admin surface are a
  different project. Report those to that project's own security contact.
- **An instance's configuration.** A self-hosted instance is its operator's responsibility.
- Issues that need physical access to an unlocked device, or a rooted device with the app's
  data already readable.
- A dependency with a published advisory that a `./gradlew dependencyUpdates`-style bump fixes:
  open a normal issue or a pull request for the bump.

## What the app does, and does not, claim

These are the properties to check a report against. If the app breaks one, that is a bug.

- **No admin key.** The app carries the instance's public anon key and the signed-in person's
  token. It never holds a service-role or admin key.
- **The session is sealed.** The access and refresh tokens are encrypted with a key in the
  Android Keystore, in a file excluded from backup (`allowBackup="false"` and the data
  extraction rules). Signing out clears them.
- **Row-level security is the instance's.** The app never reports a write the instance rejected
  as a success, and it does not cache around a denial.
- **Cleartext is loopback only.** The network security config permits cleartext to the
  emulator's host gateway for local testing; anything else requires HTTPS.
- **A notification carries a destination, not a URL.** A tapped notification opens a screen the
  app itself named; the intent carries no arbitrary link to follow.
- **No third-party typeface or script is loaded.** The app draws with the device's own face and
  does not execute instance-supplied code, CSS or HTML.

## What is known to be missing

Stated plainly so a report is not spent on it:

- **End-to-end encryption is not implemented.** The app refuses to pretend it has it: an
  encrypted channel is not silently sent plaintext, but the app also cannot read or write one.
  See [docs/parity-audit.md](docs/parity-audit.md) (B03, B04).
- **Push is not implemented.** There is no FCM or UnifiedPush integration, so there is no push
  token on the device.
- **Attachment uploads are not implemented.** The app does not send a file anywhere.

## Disclosure

Please coordinate disclosure through the private vulnerability report. Reporters who follow this policy are
credited in the advisory and in the commit that fixes it, unless they prefer to stay anonymous.

## Licence

The app is free software under the GNU GPL v3 or later; see [LICENSE](LICENSE). A security
report is not affected by the licence, and reporting one does not license your work to us.
