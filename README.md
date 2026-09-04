# Headroom

An Android app that shows how much Claude subscription capacity is left, and
notifies when a usage window resets.

Renders the same bars as Claude Code's `/usage` — current session (5h), current
week, and the per-model weekly buckets — and notifies on four events: session
reset, weekly reset, an approaching-limit threshold, and hitting the wall.

It reads usage directly from the Claude API and holds its own credential, so it
works with no companion process and no machine of yours running.

## Status

Design approved, not yet implemented. See
[`docs/superpowers/specs/2026-09-04-headroom-design.md`](docs/superpowers/specs/2026-09-04-headroom-design.md).

Implementation targets a machine with the Android toolchain installed (JDK,
Android SDK, Gradle).
