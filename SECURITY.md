# Security

Headroom has three parts with very different exposure, and it is worth being
explicit about which holds what:

| Part | Holds | Worst case if it fails |
| --- | --- | --- |
| The **app**, on your phone | Your relay's address and shared secret | Someone reads your quota usage and writes false readings |
| The **relay**, on a machine you run | The last usage reading | The same, plus whatever the host itself is worth |
| The **CLI**, on the machine you code on | Nothing durable; it *reads* Claude Code's access token to make one request | Your Claude credential leaks |

Only the third is account-critical, and it is the one that runs on a machine
where that credential already lives. The phone deliberately holds nothing that
can act as you.

## Reporting a vulnerability

Use GitHub's private reporting: **Security → Report a vulnerability** on this
repository. It reaches the maintainer without a public issue.

Please do **not** open a public issue for anything that could expose a
credential or a relay secret. Anyone reading the tracker learns about it at the
same moment as the person who can fix it.

This is a one-person project. Expect an acknowledgement within a week, and a
fix before any public disclosure — please give it that time.

## What counts

**Anything that lets Claude Code's access token escape the machine it lives
on.** This is the sharpest edge in the project:

- `headroom push` sending that token anywhere other than the usage endpoint it
  read out of the local Claude Code install — including to the relay, which
  must never see it.
- The token reaching a log, an error message, a process listing, or the state
  file in `~/.local/state/headroom/`.
- `headroom push` *writing* to Claude Code's credential store, or attempting a
  token refresh. It does neither by design; doing either could invalidate the
  user's login.
- The enrichment response reaching the relay untrimmed. Only the `limits` array
  may travel — see `trim()` in `cli/src/push.rs`, and the check in
  `scripts/check-distribution.sh` that fails the build if it disappears.

**Anything that lets the relay's shared secret escape**, or be bypassed:

- A non-constant-time comparison in `Relay::authorised`, which would let the
  secret be recovered one character at a time.
- An unauthenticated path that returns a reading. `/healthz` is deliberately
  open and deliberately reports only `ok` and an age.
- The secret appearing in a log line, a request URL, or a process listing.
  The relay logs nothing at all, for exactly this reason.

**Anything in the app** that misuses what it does hold:

- The relay secret appearing in a log, a crash report, a Compose preview, or a
  notification.
- Weakness in how it is stored (`KeystoreSecureStore`).
- A crafted QR code or pasted payload causing the app to do something other
  than reject it (`CredentialImport`, `interpretScan`).
- A request going to any host other than the one carried in the payload.

Also welcome, though less urgent: anything that could make the CLI fetch far
more often than intended, since that is what gets an account rate-limited. The
five-minute default and the sixty-second floor exist for that reason.

## What is out of scope

- Behaviour of the provider's endpoints — rate limits, response shape changes,
  the endpoint disappearing. Those are documented risks, not vulnerabilities.
- Whether using the app complies with the provider's terms. The README
  addresses that; it is not a security question.
- **Denial of service against your own relay.** It is a box you run and expose;
  put it behind whatever you normally put in front of an HTTP service. Reports
  that it can be flooded are not interesting.

## Running the relay safely

It speaks plain HTTP and binds to `127.0.0.1` by default. That default is the
safe one and you should keep it, terminating TLS in front — the phone sends the
shared secret on every request, usually across the open internet.

The app refuses cleartext outright (`network_security_config.xml`), so this is
enforced rather than advised.

## One thing to know about debug builds

A debug build is `debuggable`, which lets anyone with adb access to an unlocked
phone run code as the app and read what the Keystore protects. It also permits
cleartext HTTP, so it can be pointed at a relay on a laptop with no
certificate.

Debug builds install under a separate application ID (`…​.debug`), so they sit
alongside a real install rather than replacing it. That makes them convenient
for exactly this — and it means an old test build can linger on a phone
unnoticed. **Uninstall it when you are done.**

Releases are built non-debuggable, and the release workflow refuses to publish
one that is not.

## Supported versions

The latest release only. Fixes ship as a new release, which Obtainium will
offer as an update.
