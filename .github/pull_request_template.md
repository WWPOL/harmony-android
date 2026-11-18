# What this changes

A summary of the change and the problem it solves. Link the issue it fixes:

Fixes # (issue)

## Type

- [ ] Bug fix
- [ ] New feature
- [ ] Breaking change
- [ ] Documentation
- [ ] Refactor
- [ ] Dependency update

## How it was checked

What you ran, and what it showed. For a UI change, a screenshot or a render-test capture.

- [ ] `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
- [ ] `node --test scripts/*.test.mjs`
- [ ] On an emulator, if the change is to what a person sees or touches

## Checklist

- [ ] The change is to the Android app; no server, web or desktop code
- [ ] A test would fail without this change
- [ ] The docs say what is implemented and what is not
- [ ] No secret, keystore, SDK path or generated APK is in the diff
