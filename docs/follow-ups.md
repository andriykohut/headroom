# Follow-ups

Known-open work, with why it matters and how to settle it. Everything here is a
deliberate decision to stop, not an oversight — the reasoning is recorded so a
future reader does not have to reconstruct it.

Last reviewed 2026-09-06, after the architecture changed from
credential-on-phone to push-and-relay.

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

**Newly relevant:** the relay only receives readings while you are working, so a
window that resets overnight is now first observed by whichever fires first —
the alarm, or the next morning's push. The alarm path matters *more* under this
architecture, not less.

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

### The two sources agree today, but only one has been sampled at length

The status line reports `resets_at` as epoch seconds; the usage endpoint reports
an ISO timestamp it recomputes per request. Both are rounded to the nearest
minute by `UsageParser`, and a live comparison on 2026-09-06 found them 0.18
seconds apart — comfortably inside that rounding.

**Why it matters:** the two sources alternate. A cheap push lands on most
messages, an enriched one every five minutes. If they ever disagreed by more
than ±30 seconds they would round to different minutes, `EventKey` would see two
different windows, and reset notifications would fire repeatedly.

**How to settle it:** sample both across a window boundary rather than at one
instant. A single agreeing sample is weak evidence that they always agree.

## For Fable: a design pass on the new architecture

The screens were designed for an app that held your account credential and
polled the provider directly. The copy has been corrected, but the *design* has
not been revisited, and the architecture change created states the original
design never had to express. `docs/ui-mockup.html` and
`docs/ui-design-direction.md` still show the old screens.

**What actually changed underneath:**

- **The phone holds no account.** "Link an account" became "Link this phone";
  "Linked account" became "Relay". The mental model is now *this phone is
  paired with a box I run*, not *this phone is logged in*.

- **A new failure mode exists, and it is the important one.** The reading can be
  hours old while the phone is perfectly online, because the machine that pushes
  has been shut. That is not "offline" - the phone reached the relay and got an
  answer. Every bar is stale *together*, and by the same amount. The design has
  one staleness treatment, built for a phone that could not reach the network;
  it now has to express "this is what your usage was at 6pm last night", which
  is a different and more common situation.

- **The age line changed meaning.** It used to be "when this phone last
  fetched". It is now "when the reading was taken", read from the relay's
  `X-Headroom-Age`. A number that says "3 hours ago" no longer implies anything
  is wrong.

- **"Re-link needed" is nearly dead.** A relay secret does not expire and a
  relay never answers 401 unless the secret is actually wrong. The alarming
  error panel now has almost no way to fire, while the genuinely common problem
  - a relay that is up but has not been pushed to in a while - has no treatment
  at all. That is backwards.

- **The per-model bars can lag the other two.** They refresh every five minutes;
  session and week refresh on every message. Two freshnesses on one screen, and
  currently nothing says so.

**What to produce:** an updated `docs/ui-mockup.html` and a revision to
`docs/ui-design-direction.md` covering the states above - in particular a
staleness treatment that distinguishes "your machine has been off" from "your
phone is offline", without adding colour as the only cue, and without disturbing
the notch/weight/hatching vocabulary that already works.

## Deliberately not done

### Minification is off

R8 would cut the release APK substantially. The remaining bulk is native code
shipped for four ABIs, which is what ABI splits address.

**Why it is off:** Ktor and Koin both resolve by reflection, and nothing in this
project has been tested against a shrunk build. A smaller APK that fails to read
a relay is worse than a large one that works.

**Cheaper size win first:** ABI splits (`splits { abi { ... } }`) produce
per-architecture APKs of roughly 25 MB with no behavioural risk at all. Do that
before reaching for R8.

### The two Linux credential resolvers are guesses

`headroom push` tries `secret-tool` and `kwallet-query` after the credentials
file, but no evidence was ever found that Claude Code writes to either store —
the only mentions in its binary are its sandbox scanner enumerating credential
helpers on `PATH`. Their lookup attributes are a guess.

**How to settle it:** run `headroom push --once` on a Linux machine with Claude
Code logged in. If the file resolver answers, delete these two rather than
leaving speculative code that looks verified.

**Lower stakes than it was.** These only affect enrichment. If every resolver
misses, you lose the per-model bars and keep everything else.

### The enrichment interval is defensible, not measured

Five minutes while working, sixty seconds floor. The reasoning: the one public
data point, from `hass-claude-usage`, reports that "a couple of dozen bursts in
a minute" triggers a limit whose penalty is "around 24 hours, during which you
won't be able to see your usage here, in Claude Code, or on https://claude.ai".
Twelve requests an hour, only while a session is live, is two orders of
magnitude under that.

**What would sharpen it:** an actual figure for the limit. Until then the floor
stays where it is, because the cost of being wrong is a day-long lockout that
also takes out Claude Code.

## Settled by the architecture change

### The old design's machinery is gone from the app

Done 2026-09-07. Deleted: the token refresh path (`CredentialStore.refresh`,
`RefreshFailedException`, and the form post in `ImportedCredentialStore`), the
persisted rate-limit gate (`RateLimitGate`, `DataStoreRateLimitGate`,
`RateLimitedException`, `Retry-After` parsing and the backoff constants), and
the `atWall` parameter threaded through four call sites - a relay ignores query
strings, so `at_wall=1&skip_spend=1` did nothing.

**What was deliberately *not* deleted.** The "scan a new code" surface looked
dead and is not. A relay mints nothing and expires nothing, so it never returns
401 for a reason the app can fix by itself - but it does return 401 when the
secret is wrong or has been rotated on the relay, and that is a real thing a
user must be told about. So the panel and its notification channel were rewired
onto a new `RelayRejectedException` (401 or 403) rather than removed. Deleting
them would have traded a dead code path for a silent failure, which is the one
thing this app is built not to have.

The notification channel id stays `relink_needed` and the storage keys keep
their names: both are frozen, because changing them orphans settings and stored
values in installs that already exist.

`ImportedCredentialStore` still persists all six payload fields, three of which
nothing reads. They are the wire contract with the generator and with installed
app versions; dropping them is a format change, not a cleanup.


### The staleness line now measures the reading, not the fetch

Fixed 2026-09-06. `UsageApi` reads the relay's `X-Headroom-Age` header and dates
the snapshot from when the reading was *taken*, not when it was fetched.

Under the old design those were the same instant, so `fetchedAt = now()` was
correct. Against a relay they come apart: a reading pushed an hour ago and
polled ten seconds ago would have rendered as "just now" and never gone stale -
which is the one failure this app was built not to have. Everything downstream
(`ageSeconds`, `isStale`, the "updated N ago" line) derives from `fetchedAt`, so
the single assignment was the whole fix.

An absent or unreadable header still means "live", which is correct for anything
that is not a relay; a negative age is treated as live rather than as a reading
from the future, since dating a snapshot ahead of `now` would make it look fresh
indefinitely.


### Headroom cannot run its own OAuth flow — and no longer needs to

Tested exhaustively on 2026-09-06. Kept here because it looks achievable right
up until you try it, and because it is the reason the design changed.

**The problem it caused.** The phone held a copy of Claude Code's credential, so
the two shared one refresh token. Refresh tokens rotate, so whichever client
refreshed second found its token spent and reported "Re-link needed" — about
once a day. The fix would have been for Headroom to hold its own tokens.

**It cannot have its own client identity.** Claude Code's `client_id` is a URL
serving a genuine OAuth client ID metadata document (`client_name: "Claude
Code"`), which suggests a server that would let a third-party client identify
itself honestly. It does not: an authorize request carrying that URL is
rejected with

> `client_id: Input should be a valid UUID, invalid character: expected an
> optional prefix of `urn:uuid:` followed by [0-9a-fA-F-], found `h` at 1`

before anything is fetched. No OAuth server metadata is published either —
`/.well-known/oauth-authorization-server` is 404 on `claude.ai`,
`platform.claude.com` and `console.anthropic.com` — so there is no registration
endpoint. A client ID can only come from Anthropic issuing one.

**And borrowing Claude Code's client ID does not work either.** Nine variants
were tried, using Claude Code's own UUID. All reached a consent screen and then
failed identically at code issuance with **"Authorization failed / Invalid
request format"**:

| Varied | Values tried |
| --- | --- |
| Authorize host | `claude.ai/oauth/authorize`, `platform.claude.com/oauth/authorize` |
| Redirect | `console.anthropic.com/oauth/code/callback`, `platform.claude.com/oauth/code/callback`, `http://localhost/callback` |
| Scope | `user:profile`, `org:create_api_key user:profile user:inference` |
| Mode | with and without `code=true` |

The consent screen rendering means `client_id`, `scope` and the PKCE challenge
are all accepted; the failure is at code issuance.

**How it was actually resolved:** by removing the requirement. Nothing refreshes
a token any more. Claude Code owns its credential chain exclusively, the CLI
reads it without ever writing, and the phone holds no credential at all. The
re-linking problem is gone rather than worked around.

**Still worth doing:** one of those nine variants reproduces
`hass-claude-usage`'s request exactly — same host, redirect, scope and
parameters — and it fails too. **That project is very likely broken right now**
(last commit 2026-08-28, no issue filed). Worth telling them.

**Still useful if this ever opens up:** `user:profile` alone is sufficient for
the usage endpoint (`hass` issue #13), which is much narrower than the three
scopes Claude Code's credential carries.

Note that `/oauth/authorize` sits behind a bot challenge, so any retest needs a
real browser.

## Watch for drift

Everything in `docs/discovery-notes.md` is an undocumented internal and will
break eventually. The two most likely to move:

- **The usage-endpoint scan.** `headroom push` recovers the origin from a
  `/api/oauth/` URL literal in the Claude Code executable, then caches it. A
  build-tooling change at the other end breaks it silently — and because the
  result is cached in `~/.local/state/headroom/push.json`, a stale wrong answer
  can outlive the change. `HEADROOM_USAGE_ENDPOINT` is the supported escape
  hatch, not a debug aid. Deleting the state file forces a rescan.
- **The status line payload shape.** `rate_limits.five_hour` and `seven_day`
  with `used_percentage` and epoch `resets_at`, confirmed against six captured
  samples. This is Claude Code's own interface to a user script, so it is the
  most stable thing the project depends on — but it is still not a contract.
  `statusline.rs` skips a window it cannot read rather than reporting zero.
