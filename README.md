# Headroom

An Android app that shows how much Claude subscription capacity you have left,
and notifies you when a usage window resets.

Renders the same bars as Claude Code's `/usage` — current session (5h), current
week, and the per-model weekly buckets — and notifies on four events: session
reset, weekly reset, an approaching-limit threshold you choose, and hitting the
wall.

It reads usage directly from the API and holds its own credential, so it works
with your computer asleep or switched off. No companion process, no relay, no
same-network requirement.

## This is not an official Anthropic client

Headroom is an unofficial, community tool. It is not built, endorsed, or
supported by Anthropic, and it ships **no Anthropic credentials or identifiers
of any kind** — no client ID, no endpoint URLs.

Instead it is **bring-your-own-credential**: you link it to your own account by
running a command on your own computer, which reads the credentials already
stored there by Claude Code and hands them to the app as a QR code. You are
supplying your own credentials to software you chose to install. Two things
follow from that, and you should know both before using it:

- Reading usage this way is **off-label**. It relies on an endpoint that is not
  publicly documented and can change or stop working at any time.
- The app refreshes the token it is given, which **may sign out Claude Code on
  your computer** (and vice versa). If that happens, re-link — it is one
  command.

If you want an officially supported way to see this data, use `/usage` in Claude
Code.

## Linking

Three ways, easiest first:

1. **Claude Code slash command** — from a clone of this repo, run
   `/headroom-link`. Renders a QR code in your terminal; scan it with the app.
2. **Standalone** — `uv run --directory tools headroom-link`. Add `--text` to
   print the payload for manual paste instead.
3. **Manual paste** — run with `--text` and paste the string into the app.

Treat the payload like a password: it grants access to your account. Nothing is
written to disk, and the generators print to your terminal only.

The QR needs a terminal **at least 85 columns wide**. Narrower than that and it
wraps, which looks like a QR but will not scan — the generator warns you when
this happens rather than letting you find out by holding up a phone.

Credentials are read from whichever of these your system uses:
`~/.claude/.credentials.json`, macOS Keychain, `secret-tool` (libsecret), or
`kwallet`. On macOS the Keychain is the usual source; the two Linux keystores
are unverified guesses, so on Linux expect the file to be what works. See
[`docs/discovery-notes.md`](docs/discovery-notes.md).

On the phone, tokens are stored in the Android Keystore and never logged.

## Status

The link generator in `tools/` is built and works end to end against a real
Claude Code install. The Android app is not started yet.

See [`docs/superpowers/specs/2026-09-04-headroom-design.md`](docs/superpowers/specs/2026-09-04-headroom-design.md)
for the design and [`docs/discovery-notes.md`](docs/discovery-notes.md) for what
was verified against a live install, and when.

One thing remains unverified: whether a phone camera actually scans the QR off a
terminal. That needs a device and the app, so it is checked when the app exists.

App implementation targets a machine with the Android toolchain installed (JDK,
Android SDK, Gradle).
