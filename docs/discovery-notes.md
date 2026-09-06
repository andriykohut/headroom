# Discovery notes

Where Claude Code actually keeps its credentials and provider constants, verified
by inspection on a real install. **These are undocumented internals, not a public
API.** They will drift. Every claim below is stamped with when it was checked.

| | |
| --- | --- |
| Verified | 2026-09-06 |
| Claude Code | 2.1.263 |
| Platform | macOS (Darwin 25.3.0), arm64 |
| Install | `~/.local/share/claude/versions/<version>`, a 199 MB Mach-O executable |

**No literal values are recorded here.** Spec §3 commits the project to shipping
no Anthropic identifiers, and a public repo carrying the `client_id` or the
endpoint hostnames would break that commitment as surely as compiling them into
the app would. What follows is where to look and what pattern to match — the
resolvers read the values at runtime, on the user's own machine.

## Credential storage

`~/.claude/.credentials.json` **did not exist** on the verified machine. The live
credential was in the macOS Keychain:

- Service: `Claude Code-credentials`
- Account: the current username

Read with `security find-generic-password -s "Claude Code-credentials" -w`, which
returned JSON without raising a Keychain access dialog.

### Shape of the credential JSON

Inspected by printing key names and types only; no value was ever echoed.

```
claudeAiOauth.accessToken            str
claudeAiOauth.refreshToken           str
claudeAiOauth.expiresAt              int, 13 digits
claudeAiOauth.refreshTokenExpiresAt  int, 13 digits
claudeAiOauth.scopes                 list
claudeAiOauth.subscriptionType       str
claudeAiOauth.rateLimitTier          str
```

Two corrections to the plan follow from this:

1. **The fields are nested under a `claudeAiOauth` wrapper**, not at the top
   level. A resolver that reads `data["accessToken"]` finds nothing. The
   resolvers flatten nested objects before looking up their key aliases, which
   handles this without naming the wrapper anywhere.
2. **`expiresAt` is milliseconds, not seconds** — 13 digits. The payload spec
   requires epoch *seconds*, so the millisecond heuristic in the file resolver is
   load-bearing, not defensive.

The same JSON shape is assumed for the file-based resolver, since the file and
the Keychain hold the same blob; that half is unverified until a machine with the
file turns up.

### Linux stores are unverified

Spec §3 lists `secret-tool` and `kwallet` as resolvers 3 and 4. Searching the
binary for those names found only its **sandbox permission scanner**, which
enumerates credential *helpers* on `PATH` (`docker-credential-*`,
`git-credential-*`, `secret-tool`, `kwallet-query`). That is not Claude Code
storing its own credential there.

No evidence was found that Claude Code writes to libsecret or kwallet at all; on
Linux it most likely uses `~/.claude/.credentials.json`. The two resolvers are
still worth building — they cost little and degrade to "unavailable" — but their
lookup attributes are a **guess**, and should be corrected by someone on a Linux
install rather than trusted.

## Provider constants

Not present in the credential. They come from the Claude Code executable, found
by scanning `strings -a` over it.

| Field | How to find it |
| --- | --- |
| `client_id` | A UUID-shaped value at `CLIENT_ID:"…"` inside a minified config object, adjacent to a `MANUAL_REDIRECT_URL` ending `/oauth/code/callback`. |
| `token_endpoint` | A complete `https://` literal ending `/v1/oauth/token`. |
| `usage_endpoint` | The API base joined to the path literal `/api/oauth/usage`. |

Three corrections to Task 7 follow:

1. **`client_id=` is the wrong pattern.** It matches exactly once in the binary,
   against a *minified identifier* — the code is compiled, so `client_id:` and
   `clientId:` overwhelmingly appear as variable references, not literals. The
   only literal assignment is the `CLIENT_ID:"<uuid>"` form above. Match on the
   UUID shape, not on a bare word after `=`.
2. **`DESIGN_CLIENT_ID` sits directly beside `CLIENT_ID`** in the same object and
   is also UUID-shaped. A pattern anchored on the substring `CLIENT_ID:"` matches
   both. Anchor so the longer name cannot match.
3. **The usage endpoint is not a full URL literal.** The binary holds the path
   only — both `/api/oauth/usage` and the `?at_wall=1&skip_spend=1` variant that
   spec §2 predicted — and joins it to a base at runtime. The resolver has to
   compose it, and should let `ANTHROPIC_BASE_URL` win when that is set, since
   Claude Code itself honours it.

The `HEADROOM_CLIENT_ID` / `HEADROOM_TOKEN_ENDPOINT` / `HEADROOM_USAGE_ENDPOINT`
environment overrides in Task 7 are the escape hatch for when this scan stops
working, which it eventually will. They are the supported path, not a debug aid.

## QR density

Measured against a real payload on 2026-09-06:

| | |
| --- | --- |
| Payload | 469 characters |
| QR | version 15, 77×77 modules, error correction L |
| Terminal footprint | **85 columns × 43 rows** (segno, `compact=True`) |

**85 columns is wider than the 80-column default**, and a QR that wraps looks
like a QR while being unscannable. The CLI therefore compares the drawing's
width against `shutil.get_terminal_size()` and warns, pointing at `--text`.

Error correction stays at L deliberately. The payload is two 108-character
tokens plus a UUID and two URLs; raising correction raises the version, and the
symbol is already at the edge of what a phone reads off a screen.

**Scanned first try** on 2026-09-06, with a phone's built-in camera app pointed
at a terminal wider than 85 columns. No fiddling with distance, angle, or font
size was needed. So the QR stays the primary path and `--text` stays the
fallback, which is what the spec's onboarding order assumes.

Two caveats on that result. It is one phone against one terminal at one font
size, so it establishes that the geometry works rather than that it always
will; and it says nothing about a *narrower* window, where the code wraps and
silently stops being a QR at all — which is why the width check exists.

## The usage response

Fetched 2026-09-06, HTTP 200, 1925 bytes. Spec §2's original "Response shape"
was written from reading Claude Code's client code rather than from a live
response, and it was wrong in almost every particular:

| §2 predicted | Actually |
| --- | --- |
| Top level is an array of entries | Top level is an object; entries are under `limits` |
| Field `utilization` | Field `percent`, an integer 0–100 |
| `resets_at` in epoch seconds | `resets_at` as an ISO-8601 string with offset |
| Kinds `five_hour`, `seven_day`, `seven_day_sonnet`, `seven_day_opus`, `seven_day_overage_included`, `overage` | Kinds `session`, `weekly_all`, `weekly_scoped` |
| — | Also `group`, `severity`, `is_active` |
| `scope.model.display_name` for `weekly_scoped` | Correct — the only prediction that held |

The predicted kind names do exist, but as **top-level keys of a second, older
shape** carrying `{utilization: float, resets_at, limit_dollars, …}`. On the
verified account most of those were `null` while `limits` was populated, so
`limits` is the live shape. That is almost certainly how the wrong spec arose:
those names are visible in the client code, and they look like the answer.

**What the fixture deliberately omits.** The full response also carries a
`spend` block with real amounts, an `extra_usage` block, and about ten keys that
read as unreleased product codenames. None of it is anything the app parses, and
this repo is public, so the committed fixture is the `limits` array alone — with
`display_name` replaced by a placeholder and `resets_at` normalised to fixed
stamps in the identical format, which also makes the parser tests deterministic.

Re-fetch the shape (never commit the output) with:

```bash
uv run --directory tools headroom-link --text | <your own inspector>
```

## How to re-verify

The scan is cheap to redo after a Claude Code upgrade:

```bash
strings -a "$(readlink -f "$(command -v claude)")" > /tmp/cli_strings.txt
grep -c 'CLIENT_ID:"' /tmp/cli_strings.txt      # expect >= 1
grep -oE '[/a-z_]*oauth/usage[a-z_?=&0-9]*' /tmp/cli_strings.txt | sort -u
```

Do not paste the results into this file.
