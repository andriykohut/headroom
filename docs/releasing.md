# Releasing

Headroom is distributed as a signed APK attached to a GitHub release.
[Obtainium](https://github.com/ImranR98/Obtainium) installs and updates from
those releases directly, which is why the artifact name and the version have to
be predictable.

## One-time setup

The release workflow signs with the same key you sign with locally, so the key
has to reach the runner. Four repository secrets, under
**Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -i headroom-release.keystore \| pbcopy` |
| `KEYSTORE_PASSWORD` | `storePassword` from `keystore.properties` |
| `KEY_ALIAS` | `headroom` |
| `KEY_PASSWORD` | `keyPassword` from `keystore.properties` |

The workflow writes both files onto the runner, builds, and deletes them in an
`always()` step so they do not survive a failure.

**Back up `headroom-release.keystore` and `keystore.properties` somewhere other
than this machine.** Android identifies an app by its signing key: lose it and
you cannot ship an update to anyone who installed a previous build, only ask
them to uninstall and start again. Both files are gitignored, so the repository
is not that backup.

## Cutting a release

```bash
git tag v0.2.0
git push origin v0.2.0
```

That is the whole process. The workflow runs the distribution checks, runs both
test suites, builds, verifies the APK is signed and **not** debuggable, and
attaches `headroom-<tag>.apk` to a GitHub release.

The version is derived from the tag rather than stored in the build file, so
`v0.2.0` produces `versionName 0.2.0` and `versionCode 200`. Nothing to
remember to bump, and nothing to get out of step.

Tags must be `vMAJOR.MINOR.PATCH`. Anything else builds with the fallback
version, which Obtainium will not see as an update.

## What the workflow refuses to publish

- Anything failing `scripts/check-distribution.sh` — a provider identifier in
  the app, a logging call that could reach a credential, drifted payload
  fixtures, or a missing licence.
- Anything failing the test suites.
- A debuggable APK. This app holds a live credential, and a debuggable build
  hands it to anyone with adb access.

## Installing with Obtainium

Point Obtainium at the repository URL. It reads the releases, matches the
`.apk` asset, and offers an update whenever a newer `versionName` appears.

The first install has to be manual — Obtainium cannot install an app signed
with a key Android has not seen before without the user approving it, same as
any sideload.

## F-Droid

Nothing in the app is non-free — the scanner is zxing-cpp (Apache-2.0) — so
F-Droid's inclusion policy no longer excludes it. It has not been submitted.
Doing so means a metadata/fastlane directory and a reproducible build recipe
in F-Droid's format, which is its own piece of work.
