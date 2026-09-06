# Security

Headroom holds a live credential for your account. That makes some bugs
worse than others, and this file is about those.

## Reporting a vulnerability

Use GitHub's private reporting: **Security → Report a vulnerability** on this
repository. It reaches the maintainer without a public issue.

Please do **not** open a public issue for anything that could expose a
credential. Anyone reading the tracker learns about it at the same moment as
the person who can fix it.

This is a one-person project. Expect an acknowledgement within a week, and a
fix before any public disclosure — please give it that time.

## What counts

Anything that could let the credential reach somewhere it should not:

- A token appearing in a log, a crash report, an error message, a Compose
  preview, or a notification.
- Weakness in how the credential is stored (`KeystoreSecureStore`) or sent
  (only ever to the endpoints inside the payload, over TLS).
- The generator (`tools/headroom-link`) writing a secret to disk, or printing
  one anywhere other than the QR and `--text` output the user asked for.
- A crafted QR code or pasted payload causing the app to do something other
  than reject it (`CredentialImport`, `interpretScan`).
- A request going to any host other than the two carried in the payload.

Also welcome, though less urgent: anything in the notification pipeline that
could make the app poll far more often than intended, since that is what
gets an account rate-limited or flagged.

## What is out of scope

- Behaviour of the provider's endpoints — rate limits, response shape changes,
  the endpoint disappearing. Those are documented risks, not vulnerabilities.
- Whether using the app complies with the provider's terms. The README
  addresses that; it is not a security question.

## Supported versions

The latest release only. Fixes ship as a new release, which Obtainium will
offer as an update.

## One thing to know about debug builds

A debug build is `debuggable`, which lets anyone with adb access to an
unlocked phone run code as the app and ask the Keystore to decrypt the
credential. That is by design in Android and fine on a test device. **Do not
install a debug build on a phone you actually use.** Releases are built
non-debuggable, and the release workflow refuses to publish one that is not.
