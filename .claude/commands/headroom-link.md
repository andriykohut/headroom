---
description: Print a QR code linking the Headroom Android app to this machine's Claude Code credentials
allowed-tools: Bash(uv run --directory tools headroom-link*), Bash(uv --version)
---

Run the Headroom link generator and show the user the result.

1. Run `uv run --directory tools headroom-link` from the repository root.
2. Show the QR code output to the user verbatim, so they can scan it.
3. Tell them to open Headroom on their phone, tap Link account, and scan.

If the command fails because `uv` is not installed, tell the user to install it
from https://astral.sh/uv and stop — do not attempt an alternative install path.

If it fails with a credentials error, relay the error's list of locations tried
and ask whether Claude Code is logged in on this machine.

If it warns that the QR is wider than the terminal, relay that warning rather
than showing the wrapped output as if it were scannable: a wrapped QR looks
right and does not scan. Tell them to widen the window or reduce the font size.

Never echo the payload or any token into your own response text. The QR output
is safe to show; the raw `headroom1:` string is not — it grants account access,
and your response may be logged or shared. If the user explicitly asks for the
raw payload for manual paste, run with `--text` and tell them to treat it like
a password.
