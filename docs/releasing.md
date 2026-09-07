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

The tag drives everything, but `cargo publish` reads `cli/Cargo.toml`, so the
manifest has to be bumped before the tag exists — `scripts/check-version.sh`
fails the release if they disagree.

1. Bump `version` in `cli/Cargo.toml`.
2. Commit it: `git commit -am "Release vX.Y.Z"`.
3. Tag and push: `git tag vX.Y.Z && git push origin main vX.Y.Z`.

The workflow then checks the versions agree, builds four binaries on native
runners, checksums them, builds and smoke-tests the container, publishes the
GitHub release with the APK and the binaries, and publishes the crate last —
a crates.io version cannot be unpublished, so it goes after every other gate.

Needs two secrets beyond the APK signing keys: `CRATES_IO_TOKEN`, and the
built-in `GITHUB_TOKEN` for ghcr.io.

Tags must be `vMAJOR.MINOR.PATCH`, optionally with a `-` suffix for a
prerelease (`v0.0.1-rc1`). Anything that fails that shape builds the app with
its `0.1.0` fallback version, which Obtainium will not see as an update.

A version containing `-` publishes only its own image tag on ghcr.io; `latest`
moves only for a release without one. Deleting a prerelease's package version
afterwards would otherwise remove a manifest `latest` still pointed at, so a
release candidate is never allowed to move it in the first place.

**The first release makes a GHCR package that is private by default.** Nobody
but you can `docker pull` the image until you make it public, on the
repository's **Packages** page (Package settings → Change visibility). The
workflow's smoke test cannot catch this — the runner that builds and tests the
image is already authenticated to it, so an unauthenticated stranger's `docker
pull` failing is invisible from inside the pipeline. Check it after the first
release, not before every one.

**A rehearsal tag such as `v0.0.1-rc1` builds an APK with `versionName
0.1.0`, not `0.0.1-rc1`.** Expected, not a fault: `app/build.gradle.kts`'s
version regex accepts only `x.y.z`, and a prerelease suffix does not match it.
Nothing to chase there.

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

## Updating the relay

The relay is a static binary; deploying a new one is a copy and a restart. It
is built in a container so the server needs no Rust toolchain:

```bash
tar czf - --exclude='cli/target' cli fixtures \
  | ssh root@your-relay 'rm -rf /tmp/headroom-src && mkdir -p /tmp/headroom-src \
      && tar xzf - -C /tmp/headroom-src'

ssh root@your-relay '
  docker run --rm -v /tmp/headroom-src:/src -w /src/cli rust:1-alpine \
    sh -c "apk add --no-cache musl-dev >/dev/null && cargo build --release --locked"
  install -m 755 /tmp/headroom-src/cli/target/release/headroom /usr/local/bin/headroom
  systemctl restart headroom
  rm -rf /tmp/headroom-src
  docker image rm rust:1-alpine
'
```

The keys are untouched by this, so the phone does not need re-linking. The
stored reading survives too — it lives in the state directory, not the binary.
