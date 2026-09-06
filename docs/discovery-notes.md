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

## How to re-verify

The scan is cheap to redo after a Claude Code upgrade:

```bash
strings -a "$(readlink -f "$(command -v claude)")" > /tmp/cli_strings.txt
grep -c 'CLIENT_ID:"' /tmp/cli_strings.txt      # expect >= 1
grep -oE '[/a-z_]*oauth/usage[a-z_?=&0-9]*' /tmp/cli_strings.txt | sort -u
```

Do not paste the results into this file.
