---
description: Print a QR code pairing the Headroom Android app with your relay
allowed-tools: Bash(cargo run --manifest-path cli/Cargo.toml*), Bash(cli/target/release/headroom link*), Bash(headroom link*)
---

Print the QR code that points Headroom at the user's relay, and show them the
result.

1. Run `headroom link --relay "$HEADROOM_RELAY_URL"` if `headroom` is on PATH.
   Otherwise build and run it from the repository root:
   `cargo run --release --manifest-path cli/Cargo.toml -- link --relay "$HEADROOM_RELAY_URL"`
2. Show the QR output to the user verbatim, so they can scan it.
3. Tell them to open Headroom, tap Link account, and scan.

The secret comes from `$HEADROOM_RELAY_SECRET` or `--secret-file`. If neither is
set, the command says so - relay that and ask where their relay's secret lives.
Do not invent one: a mismatched secret produces a QR that scans cleanly and then
fails with 401, which is a confusing way to spend ten minutes.

The command prints the number of columns the QR needs. Run this way it cannot
measure the user's window, so relay that number rather than presenting the
output as known-scannable: a wrapped QR looks right and does not scan. If it
looks wrapped, tell them to widen the window, reduce the font size, or run the
command directly in their own terminal, where it checks the width itself.

Never echo the payload into your own response text. The QR output is safe to
show; the raw `headroom1:` string is not - it carries the relay's shared secret,
and your response may be logged or shared. If the user explicitly asks for the
raw payload for manual paste, run with `--text` and tell them to treat it like a
password.

What this code carries is the relay's URL and secret - not a Claude credential.
Someone who photographs it learns the user's quota usage and can write readings
to their relay. That is worth protecting, and it is not account access.
