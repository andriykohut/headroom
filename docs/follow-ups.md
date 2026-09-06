# Follow-ups

Known-open work, with why it matters and how to settle it. Everything here is a
deliberate decision to stop, not an oversight — the reasoning is recorded so a
future reader does not have to reconstruct it.

Last reviewed 2026-09-06.

## Not yet observed

### The reset alarm has never fired in anger

`ResetAlarmScheduler` picks the earliest future reset, `AlarmReceiver` enqueues
a poll, and the coordinator notifies. Every link in that chain is tested, and
the alarm is verified as *scheduled* — in both the exact and the degraded path —
but no session window has actually elapsed with the app installed, so nobody
has seen the notification arrive at the moment it should.

**How to settle it:** leave the app installed and note whether a
"Current session reset" notification arrives at the time the app showed. Takes
up to five hours.

**Why it is worth doing:** this is the app's headline feature. Everything else
degrades gracefully; a reset notification that never arrives is the product not
working.

### The zxing-cpp scanner has not yet been tried on a phone

The scanner was swapped from ML Kit to zxing-cpp after the app had already
been linked once by camera. zxing-cpp is configured with every "try" option on
(harder, rotate, invert, downscale) because our code is dense — version 15 at
error correction L — and that is the case where a weaker decoder shows.

**How to settle it:** link a phone by scanning with a build that carries
zxing-cpp. If it struggles where ML Kit did not, the paste fallback still
works, and the option set above is the first thing to tune.

### No response from an account actually at its limit

The wall trigger fires on `percent >= 100`, because that is the only signal the
app can observe: the live response carries no rejection flag, `severity` was
`normal` on every bucket ever seen, and the `at_wall=1` variant is a call a
client makes *after its own request was refused* — which Headroom never makes.

**How to settle it:** capture a usage response from an account that is genuinely
at its limit and see whether anything in it distinguishes "full" from "refused".

**Blast radius if it changes:** one predicate in `TriggerEvaluator` and one row
in spec §2. Nothing in the bar's anatomy, colour or copy, because "fully used"
and "refused" look and read the same on this screen.

### The `/usage` comparison was made against the API, not the panel

Verification compared the app's numbers to the wire, which proves the app agrees
with the server. It does not prove both agree with what Claude Code's own
`/usage` panel shows.

**How to settle it:** open the app and run `/usage` side by side.

### The rate-limit backoff numbers are guesses

A 429 now parks the app until `Retry-After` says otherwise, or for six hours
if the server does not say.

**How to settle it:** find out what the limit is. The one public data point
comes from `hass-claude-usage`, whose README reports that "a couple of dozen
bursts in a minute" triggers it and that the penalty is "around 24 hours,
during which you won't be able to see your usage here, in Claude Code, or on
https://claude.ai". The default backoff is six hours on that basis, and the cap
is a day; both are still estimates.

**Also unhandled:** `Retry-After` in its HTTP-date form. Only delta-seconds is
read; a date falls back to the default. Legal per the spec, never observed
here, and guessing at a date format to derive a wait is a worse failure than
waiting a fixed amount.

## Deliberately not done

### Minification is off

R8 would cut the release APK substantially. The remaining bulk is native code
shipped for four ABIs, which is what ABI splits address.

**Why it is off:** Ktor and Koin both resolve by reflection, and nothing in this
project has been tested against a shrunk build. A smaller APK that fails to
refresh a token is worse than a large one that works.

**How to do it safely:** turn on `isMinifyEnabled`, add keep rules for Ktor's
engine loading and Koin's module definitions, then re-run the whole of
`docs/verification.md` against the shrunk build — particularly the refresh path
and the JSON parsing, which are where reflection bites.

**Cheaper size win first:** ABI splits (`splits { abi { ... } }`) produce
per-architecture APKs of roughly 25 MB with no behavioural risk at all. Do that
before reaching for R8.

### The two Linux credential resolvers are guesses

`SecretToolResolver` and `KWalletResolver` are built and unit-tested, but no
evidence was ever found that Claude Code writes to either store — the only
mentions in its binary are its sandbox scanner enumerating credential helpers on
`PATH`. Their lookup attributes are a guess.

**How to settle it:** run `tools/headroom-link` on a Linux machine with Claude
Code logged in. If resolver 1 (the file) answers, delete these two rather than
leaving speculative code that looks verified. Spec §3's table marks them
unverified in the meantime.

### The generator's file resolver is only half-verified

`~/.claude/.credentials.json` did not exist on the machine used for discovery —
the credential lived in the macOS Keychain — so `CredentialFileResolver` is
tested against the JSON shape found there, on the assumption that the file holds
the same blob. Reasonable, unconfirmed.

### Headroom cannot have its own OAuth client identity

Tested 2026-09-06, and recorded so nobody spends the afternoon again.

Claude Code's `client_id` is a URL —
`https://claude.ai/oauth/claude-code-client-metadata` — which serves a genuine
OAuth **client ID metadata document** (`client_name: "Claude Code"`,
`token_endpoint_auth_method: none`). That strongly suggests a server which
fetches any such document, letting a third-party client identify itself
honestly.

**It does not.** An authorize request carrying that URL as `client_id` is
rejected before any lookup:

> `client_id: Input should be a valid UUID, invalid character: expected an
> optional prefix of `urn:uuid:` followed by [0-9a-fA-F-], found `h` at 1`

The endpoint validates `client_id` as a UUID and never fetches anything. That
document belongs to some other flow. There is also no OAuth server metadata
published — `/.well-known/oauth-authorization-server` is 404 on `claude.ai`,
`platform.claude.com` and `console.anthropic.com` — so there is nothing to
discover and no registration endpoint.

**Consequences.** A third-party tool has exactly two options: copy an existing
credential (what Headroom does, at the cost of periodic re-linking), or run a
PKCE flow using Claude Code's own client ID (what `hass-claude-usage` does,
which fixes the token collision but shows "Claude Code" on the consent screen
for software that is not Claude Code). The narrower `user:profile` scope is
only available on the second path.

**What would change this:** Anthropic issuing a client ID, or opening
registration. The ask is small and specific, and worth making before assuming
the answer.

Note also that `/oauth/authorize` sits behind a bot challenge, so this can only
be tested from a real browser.

## Watch for drift

Everything in `docs/discovery-notes.md` is an undocumented internal and will
break eventually. The two most likely to move:

- **The `client_id` scan.** It matches a UUID-shaped literal in a minified
  bundle. A build-tooling change at the other end breaks it silently. The
  `HEADROOM_CLIENT_ID` / `HEADROOM_TOKEN_ENDPOINT` / `HEADROOM_USAGE_ENDPOINT`
  environment variables are the supported escape hatch, not a debug aid.
- **`resets_at` rounding.** The server computes this per request, so it drifts
  by fractions of a second between polls; the parser rounds to the nearest
  minute because `EventKey` carries it and unrounded jitter would re-notify
  forever. If the server ever starts sending a fixed instant, the rounding
  becomes harmless rather than wrong — but if the drift ever grows beyond
  ±30 seconds, rounding to the minute stops absorbing it.
