# Architecture options

A recommendation, not a plan. Written 2026-09-06 against Claude Code 2.1.263
on macOS and the Claude Code docs fetched the same day. Nothing here is
implemented. `docs/follow-ups.md`, `docs/discovery-notes.md` and
`docs/verification.md` are the facts this builds on; this file adds the facts
found today and draws the conclusion.

> **Superseded in part, 2026-09-08.** The push-and-relay shape recommended here
> was built and shipped. The per-model weekly windows it discusses were not
> kept: reaching them meant reading Claude Code's access token and calling an
> undocumented endpoint, and Anthropic's Claude Code terms reserve subscription
> OAuth for Claude Code and its own applications and forbid developers
> collecting or intermediating those credentials. That half was removed, so
> every mention of per-model data below describes an option that was considered
> and is no longer available.

## The short version

**The pull design should be abandoned, and not only because of the token
collision.** The collision is fixable — see §3.5, the fix is one extra login —
but fixing it leaves every other problem in place: automated access with a
subscription credential, a ban risk that also locks the user out of Claude
Code, a live refresh token on the phone, and an undocumented endpoint.

**Claude Code already hands the two headline numbers to a supported, documented
surface.** The status line command receives, on stdin, `rate_limits.five_hour`
and `rate_limits.seven_day` — `used_percentage` and `resets_at` — after every
assistant message. The values come from the rate-limit headers on the user's
own API responses; no extra request is made to get them. This is the surface
`Claude-Code-Usage-Monitor --statusline` uses, and it is the only place Claude
Code exposes the server's own utilization figure to user code.

**Recommendation: push.** A status line wrapper extracts `rate_limits`, and
when they change, publishes them — encrypted — to a small mailbox the phone can
read (ntfy, on the user's own server or ntfy.sh). Headroom reads the mailbox,
shows the last state, and computes reset notifications locally from
`resets_at`. Nothing on the phone talks to Anthropic. Nothing on the phone
holds a credential. The QR code carries a mailbox address and a key, not a
token.

**What it costs, said plainly:**

- Usage made on surfaces other than Claude Code (claude.ai, the official
  mobile app, Claude Desktop, headless `claude -p`, cron routines, a machine
  without the wrapper) is invisible until the next interactive Claude Code
  response. The weekly number can be stale by however much of that the user
  does.
- Per-model weekly windows (Opus, Sonnet) are not in the status line payload.
  v1 shows session and week only.
- Threshold and wall notifications need a Claude Code session to be producing
  responses — which is exactly when those numbers move, for Claude Code use.

**The promise has to change.** "Works with your computer off" becomes:
*no companion process, no credential on your phone, nothing polls Anthropic;
reset notifications need no computer at all; live numbers arrive from your own
machine while you use Claude, which is the only time Claude Code changes
them.* That is a narrower promise and a more defensible one.

### Three questions asked during this review

**Does the laptop need to be running?** Only for the numbers to *change*. The
pusher is not a daemon — it runs inside the status line event, i.e. only while
Claude Code is producing responses. When the laptop sleeps, Claude Code is not
consuming anything, so the last pushed state stays true; the phone alarms for
resets on its own. The gap is usage from *other* surfaces while the laptop is
off. Nothing short of polling the server closes that gap.

**Can the collector run on the server that hosts ntfy instead?** A server
cannot see your laptop's status line. What it *can* do is poll the usage
endpoint with its own credential and push to the phone — candidate B′ below.
That restores "computer off" completely and sees all surfaces, at the price of
being automated access (the terms problem, unchanged) and a credential living
on a server. It is a legitimate personal choice; it is not what the public
product should require or default to. It can be layered on later as an opt-in.

**Do we actually need ntfy?** We need *a* store-and-forward mailbox: the
laptop writes without knowing where the phone is, the phone reads when the
laptop is asleep, and — optionally — the phone can be woken instantly. Without
one, the phone has to reach the laptop (same network or VPN) *and* the laptop
has to run a listener, which is the companion process the product rejects.
ntfy is the cheapest thing that is exactly that mailbox, is self-hostable, has
a three-call HTTP API, and you already run one. Headroom would speak to it
directly; the ntfy Android app is not involved. The interface Headroom needs is
small enough (`publish`, `fetch since`, optional `stream`) that swapping in a
Cloudflare Worker + KV, a WebDAV file, or MQTT later is a transport adapter,
not a redesign.

## 1. What was verified today

Everything in this section was checked against the local install or the live
docs on 2026-09-06. Binary facts come from `strings -a` over the 2.1.263
executable, the same method `docs/discovery-notes.md` uses. As there, they are
undocumented internals unless marked *documented*.

### 1.1 The status line receives official rate-limit data (documented)

From `code.claude.com/docs/en/statusline`:

- The script gets JSON on stdin. `rate_limits.five_hour.used_percentage` and
  `.resets_at`, likewise `seven_day`, and `spend_limit` behind a gateway.
  `used_percentage` is 0–100; `resets_at` is Unix epoch seconds.
- `rate_limits` "appears only for Claude.ai Pro and Max subscribers ... and
  only after the first API response in the session. Each window ... may be
  independently absent, and Claude Code drops a window once its `resets_at`
  time passes."
- **When it runs:** once at session start (including resume); then on each
  new assistant message, after `/compact`, on permission-mode or vim-mode
  change, when the command changes, when a `refreshInterval` timer elapses,
  and — notably — "a rate-limit window in the data your script last received
  reaches its `resets_at` time".
- **Constraints that shape a pusher:** updates are debounced at 300 ms; "if a
  new update triggers while your script is still running, Claude Code cancels
  the in-flight script." So the network call must be detached from the script,
  or it will be killed mid-flight during a busy turn.
- `/statusline` accepts natural-language setup; `refreshInterval` (min 1 s)
  re-runs the command on a timer for idle sessions.

**Where the numbers come from (binary).** The builder that assembles the
stdin JSON reads a header cache and emits
`five_hour: {used_percentage: utilization*100, resets_at}` and the same for
`seven_day`, plus `spend_limit` only when the session is behind a gateway.
Nothing else goes into `rate_limits`. The cache is populated from the
`anthropic-ratelimit-unified-*` response headers (`5h-utilization`,
`5h-reset`, `7d-utilization`, `7d-reset`, `status`, `representative-claim`,
and overage variants) on the session's ordinary API responses. An internal
schema comment describes the same data as "read from the
anthropic-ratelimit-unified-* response headers ... utilization is the
fraction of the window used (usually 0-1 ...; values above 1 occur when usage
legitimately runs past a window's cap)". So `used_percentage` can exceed 100.

**What is not in it:** `seven_day_opus`, `seven_day_sonnet`, per-model
`model_scoped` buckets, `subscription_type`. Those exist in a different
internal shape (§1.4), not the status line.

### 1.2 Hooks carry no usage data (documented)

`code.claude.com/docs/en/hooks` lists 33 events. Common fields are
`session_id`, `prompt_id`, `transcript_path`, `cwd`, `permission_mode`,
`hook_event_name`, agent identity, `effort`. No event carries rate-limit or
utilization fields. The `Notification` event has matchers
`quota_auto_resume_fired`, `quota_auto_resume_stale`,
`quota_auto_resume_disabled` — the auto-continue-after-limit feature — but
those fire only when a session was parked at a limit and carry no numbers.
Hooks *can* be `async`, which is attractive for a pusher, but the data is not
there. The status line is the surface.

### 1.3 Transcripts are token counts, not utilization

`~/.claude/projects/**/*.jsonl` assistant records carry `message.usage`
(input, output, cache-creation and cache-read tokens, service tier). Across
170 transcript files on this machine there is no utilization field. The only
rate-limit structure that appears is `quotaLimits` on assistant records, and
every observed instance has `status: "rejected"` with `rateLimitType` and
`resetsAt` — i.e. the transcript records *being refused*, nothing about how
close you are. `Claude-Code-Usage-Monitor`'s non-statusline mode derives its
percentages from these token counts against a plan size it estimates ("90th
percentile of your usage history"). That is a heuristic, not the server's
number; its README treats the status line as "the live source of truth when
fresh" and the local estimate as a labelled fallback. For Headroom the
transcripts offer at most a wall-hit backstop (a `rejected` record), and the
status line already covers that.

### 1.4 The Agent SDK / control protocol has a `get_usage` request (experimental)

The stream-json control protocol accepts `{subtype: "get_usage",
skip_behaviors?: bool}`, described in the binary as: "Requests the structured
/usage data: session cost/usage totals plus claude.ai plan rate-limit
utilization when available. Experimental — the response shape may change."
The `skip_behaviors` flag is "for callers that need only the plan rate limits,
**such as a usage meter**". The response carries `subscription_type`,
`rate_limits_available`, and `rate_limits` with `five_hour`, `seven_day`,
`seven_day_opus`, `seven_day_sonnet`, `seven_day_oauth_apps` and a
`model_scoped[]` array of `{display_name, utilization, resets_at}`.

That is the richest usage surface Claude Code has, and Anthropic wrote "usage
meter" into its description. Two caveats: it is served by a running Claude Code
process (the handler is a callback the REPL registers; a plain headless run may
answer "not supported in this context"), and the per-model figures it adds
appear to come from Claude Code calling the usage endpoint itself
(§1.5) — the strings "Failed to load usage data", "model_scoped projection
failed" and "Usage fetch returned a fieldless or non-object body" sit
together. So driving it on a timer is polling by proxy. It is the right way
to get per-model bars *later*, if a user opts into that. Related: the SDK's
`rate_limit_event` message carries `status`, `resetsAt`, `rateLimitType`, and
`utilization` only on warning; GitHub issue anthropics/claude-code#50518 asked
for per-bucket utilization in headless mode and was closed as not planned.

### 1.5 Claude Code is itself a client of the usage endpoint

`/usage` calls `GET /api/oauth/usage` (and the `?at_wall=1&skip_spend=1`
variant after a refusal) with a 5-second timeout and refresh-on-401. The app
was imitating that call. Nothing changes in the analysis, but it explains why
the endpoint looked safe: it is what the official client does, when a human
opens a panel.

### 1.6 Direction of travel: Anthropic's own phone path

Claude Code forwards `rate_limit_event` to its Remote Control bridge and
mirrors `rate_limit_info` as session metadata (`bridge_rate_limit_forward`).
Its settings include `agentPushNotifEnabled` — "Allow Claude to push proactive
mobile notifications" — and `inputNeededNotifEnabled` — "Push to mobile when a
permission prompt or question is waiting", with surfaces `android`,
`desktop_app`, `web_claude_ai`. The official app also has a Settings → Usage
page. So Anthropic already moves session events and rate-limit state from a
local session to the user's phone through its own channel. Headroom's
remaining ground is: notifications on *resets and thresholds*, a glanceable
home screen, and working for people who do not use Remote Control. Anything
Headroom does that merely re-displays the number is at risk of being absorbed.

### 1.7 Credential storage facts that matter for the pull-rescue option

- The Keychain service name is `Claude Code-credentials` by default. With
  `CLAUDE_CONFIG_DIR` set it becomes
  `Claude Code-credentials-<first 8 hex of sha256(config dir)>` — a *separate*
  Keychain item. The file fallback is `<config dir>/.credentials.json`.
- `claude auth logout` prints "Successfully logged out from your Anthropic
  account." No OAuth revocation call was found in the binary, so logout looks
  local-only. That is absence of evidence, not proof.

### 1.8 Terms

Consumer Terms effective 2025-10-08, §3: "Except when you are accessing our
Services via an Anthropic API Key or where we otherwise explicitly permit it,
to access the Services through automated or non-human means, whether through a
bot, script, or otherwise." §2: "You may not share your Account login
information, Anthropic API key, or Account credentials with anyone else."
Reading the status line is an explicitly documented mechanism; a script
consuming what Claude Code hands it is not accessing the Services at all. A
script polling `/api/oauth/usage` is.

### 1.9 ntfy

Messages up to 4,096 bytes; cached (12 h in memory by default, longer and
on-disk if the server sets `cache-file`/`cache-duration`); clients catch up
with `GET /<topic>/json?poll=1&since=<id|time>`; live delivery over SSE/JSON
stream or WebSocket. Topics are open by default ("the topic is essentially a
password"); a self-hosted server can set `auth-default-access: deny-all` with
users, ACLs and access tokens. No end-to-end encryption — the payload must be
encrypted by us. The ntfy Android app's Firebase use applies only to that app
on ntfy.sh; Headroom subscribing directly is unaffected.

### 1.10 hass-claude-usage

Last commit 2026-08-28 (scope reduced to `user:profile`); no issue reporting an
authorization failure as of today. Whether its flow is currently broken, as
`docs/follow-ups.md` predicts, is unverified.

## 2. The candidates

| | A. Pull, shared credential (today) | B. Pull, independent credential | B′. Pull from an always-on box, push to phone | C. Push from the status line | D. Push from a headless Claude Code (`get_usage`) |
| --- | --- | --- | --- | --- | --- |
| **User sets up** | Scan a QR from `headroom-link` | Scan, then `/login` on the computer once (or a one-off login in a scratch config dir) | A credential on a server + a poller + a mailbox | Add one line to the status line; scan a QR with mailbox + key | A daemon running `claude` with the SDK, plus a mailbox |
| **Computer off** | Fine for ~a day, then dead until re-link | Fully fine | Fully fine | Numbers freeze at last push (correct for Claude Code use; blind to other surfaces); resets still notify | Same as C, and the daemon must be up |
| **Privacy** | Refresh token on the phone; requests to Anthropic from the phone | Same | Refresh token on a server; requests from the server | Nothing to Anthropic from the phone; two percentages and two timestamps to a mailbox you choose, encrypted; the relay learns timing and IP | Same as C |
| **Terms** | Automated access, and refresh identifies as Claude Code | Same, now as a second "device" | Same | Clean: documented surface, no automated access to the Services | Polling by proxy: Claude Code fetches the endpoint on a timer |
| **Ban risk** | Real; account-wide | Real; account-wide | Real but controllable cadence | None | Real |
| **Data** | Session, week, per-model | Same | Same | Session, week | Session, week, per-model |
| **Verdict** | Not viable | Viable, wrong | Legitimate opt-in for people with a server; not the default | **Recommended** | Later, opt-in, for per-model |

E (a hybrid: C plus a human-initiated "refresh now" that pulls with a
credential) was considered and dropped for v1: the tap is arguably not
automated, but the credential on the phone brings back the collision and the
ban exposure, and the pushed numbers already answer "what is it now" whenever
Claude Code is running.

## 3. Question by question

### 3.1 Does a push design lose anything real?

The observation holds for Claude Code: subscription usage moves only when a
request is made, a Claude Code request produces a response, and the response
re-runs the status line with fresh headers. Reset times are known in advance.
So a phone that stores the last `(used_percentage, resets_at)` pairs and sets
local alarms at `resets_at` reproduces two of the four notifications with no
network at all, and the other two whenever the numbers move — through Claude
Code.

What actually breaks:

1. **Other surfaces.** claude.ai, the official app, Claude Desktop, Cowork,
   headless `claude -p`, scheduled routines, CI, and any machine without the
   wrapper all consume the same windows. None of them re-run your status line.
   The phone will show a weekly number that is right as of the last interactive
   response and wrong by however much has happened since. This is the one loss
   that cannot be engineered away without polling; it must be shown in the UI
   as "as of HH:MM, from <machine>", not hidden.
2. **Per-model weekly bars.** Not in the status line payload. Drop them from
   v1 and say so. The path back is §1.4, as an opt-in.
3. **Session-start gap.** `rate_limits` is absent until the session's first
   API response. The wrapper must treat absence as "unknown", never as zero.
4. **Window drop at reset.** Claude Code deletes a window once `resets_at`
   passes and re-runs the script at that moment. The wrapper should push
   "session: none" so the phone can render "no active session" rather than a
   stale bar — an improvement over today, where the app has no such state.
5. **Values above 100.** Utilization can exceed 1.0 after the cap. The
   existing wall rule (`>= 100`) still fires; the display clamps.
6. **Cancellation.** A pusher that does its network I/O inside the status
   line script will be killed during busy turns. It has to hand off to a
   detached process and return.
7. **Latency to the phone** depends on the transport: instant with a live
   stream, up to one WorkManager period with polling. Today's app polls every
   20 minutes, so polling the mailbox at the same cadence is no regression;
   a foreground stream is an upgrade to offer, not a requirement.

What does not break: reset notifications, staleness display, the notification
de-duplication model (`EventKey` on `resets_at` still works; keep the rounding
to the minute — headers are per-request too), and the entire trigger,
alarm and UI layer.

### 3.2 What Claude Code exposes to hooks and the status line

Covered in §1.1–§1.2. Summary: hooks nothing; status line the two headline
windows, officially, on every assistant message, from headers, with documented
absence rules and re-run triggers. It runs only in interactive sessions (TUI),
which is fine: that is where the usage happens.

Design consequences for the wrapper:

- It must **compose** with the user's existing status line, not replace it.
  This machine already has `~/.claude/statusline.sh` reading
  `.rate_limits.five_hour`. The wrapper reads stdin once, forwards it to the
  user's command, prints that command's output, and separately extracts
  `rate_limits`. `/statusline` (natural-language setup) can also be told to
  add the call.
- It extracts **only** `rate_limits`. The stdin payload also contains cwd,
  session name, git branch, PR, transcript path and cost. None of that may
  leave the machine.
- Change detection against a small state file (`~/.claude/headroom/state.json`)
  so that a hundred status line re-runs during a long turn become a handful of
  pushes. Multiple concurrent sessions read the same account headers and
  coalesce naturally through the same file.
- The push itself runs detached (`setsid`/double-fork; `nohup` on Linux and
  macOS, `Start-Process` on Windows), with a lock so that a state change
  during an in-flight push causes exactly one more push.

### 3.3 The transcripts

Not a source of utilization (§1.3). Reading them would reintroduce the exact
class of bug `docs/discovery-notes.md` records — a plausible-looking shape
that is not the server's number. Do not build on them.

### 3.4 Transports

What the transport has to be: writable from the laptop without knowing where
the phone is; readable by the phone after the laptop has gone to sleep; small
messages; optional wake-up. What it should be for this project: self-hostable,
open, no account with the developer, no developer-run server, dependency-light
in the app.

| Transport | Setup for the user | Privacy | Notes |
| --- | --- | --- | --- |
| **ntfy, own server** | Already running for you; a topic name and (with ACLs) a token | Nothing leaves your infrastructure but ciphertext | Recommended default when the user has one |
| **ntfy.sh** | None | Relay sees ciphertext, topic, IP, timing; topic is the only gate | Recommended fallback; state the exposure in PRIVACY.md |
| Pushover | Paid app, API token | Proprietary relay sees plaintext unless we encrypt; closed client | Headroom would have to speak Pushover's Open Client API to receive. No |
| Home Assistant | HA + webhook + REST sensor | Stays home | Fine as a *second sink* from the same wrapper for HA users; too heavy as the required path |
| Own cloud storage (Drive/Dropbox/S3/R2/WebDAV) | Bucket + credentials on both ends | Provider sees ciphertext | Works as a mailbox; no wake-up; OAuth on the phone for Drive/Dropbox is heavy. A WebDAV/S3-style adapter is a reasonable later addition |
| Developer-run server / FCM | None | Developer receives data | Contradicts PRIVACY.md and "no server". No |
| Tailscale / LAN, direct | VPN on both ends + a listener on the laptop | Best | Needs a companion listener and a reachable phone; fails on cellular without VPN. No |
| Telegram / email | Bot token or SMTP | Third party, plaintext | No |

Wire design, whichever mailbox: one JSON message per change,

```
{ "v": 1, "at": <epoch>, "session": {"pct": 23.5, "resets_at": <epoch>} | null,
  "week":   {"pct": 41.2, "resets_at": <epoch>} | null }
```

~150 bytes, encrypted with a symmetric key generated at link time (AES-GCM or
XChaCha20-Poly1305 with a random nonce per message), base64url, published as
the ntfy message body with `Cache: yes`. The QR payload becomes
`{server, topic, token?, key}` — a QR of version ~6 instead of 15, which also
retires the 85-column problem. Optionally a machine label (user-chosen, not the
hostname) so the phone can say where the last reading came from.

On the phone: a WorkManager poll of `GET /<topic>/json?poll=1&since=<last id>`
every 15–20 minutes plus on app open, exactly as `PollWorker` runs today; an
optional foreground-service SSE stream for instant delivery, off by default.
The ntfy Android app and UnifiedPush are not needed and should not be required.

### 3.5 Can the pull design be kept?

Rigorously: the collision is a property of *two clients sharing one rotating
refresh-token chain*. Every option that keeps sharing fails:

- The phone never refreshes → an 8-hour horizon after each link. No.
- The computer pushes each fresh access token to the phone → the phone can
  poll until 8 hours after the laptop's last refresh, then dies. It is a push
  design with polling bolted on and a cliff. No.
- The phone pushes its rotated refresh token back to the computer → the
  computer must be on to receive it, Claude Code uses its stale copy first and
  fails, and writing into Claude Code's Keychain from outside is the kind of
  thing that breaks silently on the next release. No.

But the premise that the phone *must* share a chain is false. Two Claude Code
installs on two machines each hold their own chain today; that is how multiple
machines work. So:

- **Mint a second chain.** Either link the phone, then run `/login` on the
  computer — the old chain now belongs to the phone alone; or run
  `CLAUDE_CONFIG_DIR=<scratch> claude auth login`, which stores to a separate
  Keychain item (`Claude Code-credentials-<hash>`, §1.7), export that, and
  delete the scratch dir. No shared chain, no collision.
- **Unknowns**, each a two-day experiment: whether a new login invalidates
  earlier refresh tokens server-side; whether the phone's regular refreshes
  roll the 28-day expiry forward or it is absolute; whether there is a cap on
  concurrent chains per account.

So: *the collision can be removed*. What remains is everything the README
already confesses — automated access with a subscription credential, refresh
identifying as Claude Code (now as a second device), an account-wide ban if
the cadence is ever wrong, an undocumented endpoint that will drift, and a
live refresh token on a phone. None of that is improved by fixing the
collision. The pull design is technically salvageable and should still not be
built. Keep the experiment in the back pocket as B′ (the always-on box) for
users who want cross-surface truth and accept the terms exposure.

## 4. Recommendation

Build **C**: push from the status line to a user-owned mailbox; Headroom reads
the mailbox and computes resets locally. Offer **ntfy** as the mailbox
protocol, with the server URL user-supplied (own server first, ntfy.sh as the
no-setup fallback). Encrypt every message on the laptop; the phone holds the
key. Ship session and week only. Say what is not seen.

### 4.1 The product, restated

- **What it is:** a phone-side view of what Claude Code last reported, and an
  alarm clock for the windows — plus threshold and wall alerts whenever Claude
  Code is producing responses.
- **What it promises:** no companion process, no credential on the phone,
  nothing polls Anthropic, nothing leaves your machine but two numbers and two
  timestamps, encrypted, to a mailbox you choose. Reset notifications work with
  every computer you own switched off.
- **What it says out loud:** live numbers arrive from your own machine while
  you use Claude Code; usage on claude.ai or other surfaces shows up at your
  next Claude Code response; per-model bars are not shown.
- **What it stops saying:** "works with your computer off" as a blanket claim,
  "probably against the terms", "you will have to re-link periodically", and
  "at refresh time it identifies itself as Claude Code". All four go away.

### 4.2 What survives in the codebase

Most of it. `:domain` models, `TriggerEvaluator`, `EventKey` de-duplication,
`ResetAlarmScheduler`, `NotificationCoordinator`, the notification log, the
settings store, the UI and its three visual states, the Keystore-backed
`SecureStore` (now holding a mailbox key instead of tokens), `PollWorker` (now
polling the mailbox), the QR import screen and the payload codec shape.

What goes: `UsageApi`, `CredentialStore.refresh`, `RateLimitGate`, the
`Credential` type's token fields, and — in `tools/` — every credential resolver
and the entire provider scan of the Claude Code binary. The generator becomes
a status line wrapper plus a link-QR printer, and stops reading anything
secret from the user's machine at all. `scripts/check-distribution.sh` keeps
its identifier grep and gains one for token-shaped strings in the wrapper's
push path.

What changes: `UsageParser` gains a second input shape (the pushed JSON, two
buckets, `five_hour → SESSION`, `seven_day → WEEKLY_ALL`); `UsageSnapshot`
gains an "observed at" distinct from "received at" and an explicit "no active
session" state; PRIVACY.md is rewritten around the mailbox.

### 4.3 Edge behaviour to design in from the start

- `rate_limits` absent → push nothing (session start) or push `null` windows
  (post-reset drop); the phone distinguishes "unknown" from "none".
- Percentages above 100 → wall rule unchanged, bar clamps, copy says "over".
- Multiple machines / sessions → last write wins by `at`; the phone ignores a
  message older than what it has.
- Mailbox unreachable from the laptop → the detached pusher retries a few
  times with backoff and gives up; the state file keeps the latest value and
  the next change pushes it. No queue on the laptop.
- Phone offline for longer than the mailbox cache → the next Claude Code
  response pushes a fresh state anyway; the reset alarms were set from the
  last message the phone did receive.

## 5. What to build first, to de-risk

In order. The first item needs no product code and settles most of §3.1
empirically.

1. **A tee, for a day.** A 20-line wrapper on this machine that forwards
   stdin to the existing `statusline.sh` and appends `{at, rate_limits}` to a
   log. Read the log for: agreement with `/usage` (the side-by-side
   `docs/verification.md` never got); update latency during a long agentic
   turn; what arrives at the moment a 5-hour window resets while idle; the
   payload when a request is actually refused (`used_percentage` above 100?);
   whether background subagents or a parallel headless run change the numbers
   in an open session; and how many re-runs a busy hour produces (sizes the
   change detection). Also measure how long the wrapper takes — it sits in
   the render path.
2. **The detached push.** Add change detection and a detached publish to
   your ntfy server. Verify that cancellation during a busy turn never loses
   the final state, that a burst becomes one or two messages, and that the
   message survives the laptop sleeping mid-push.
3. **The phone reads it.** Replace `UsageApi` with a mailbox client behind the
   same `fetch` seam `UsageRepository` already takes; map two buckets; keep the
   tests. Verify the reset alarm fires from a pushed `resets_at` — the item
   `docs/follow-ups.md` still lists as never observed.
4. **Link flow.** New QR payload (`headroom2:`), key generation, mailbox
   settings. Delete the credential resolvers and the binary scan.
5. **Then, optionally:** a live-stream toggle (foreground service); a
   Home Assistant sink from the same wrapper; per-model bars via `get_usage`
   as an explicit opt-in with the polling-by-proxy caveat stated.

## 6. Risks that remain

- **The status line JSON changes.** It is documented, which is as stable as
  Claude Code gets, and `rate_limits` has already survived additions
  (`spend_limit`, `prompt_cache`). Handle absence, never crash on extras.
- **The wrapper breaks someone's status line.** Composition must be exact:
  same stdin, same stdout, same exit code, negligible latency. Ship a
  `--doctor` that shows what would be pushed and what the inner command
  printed.
- **Windows.** Detaching a process and the shell used for `statusLine.command`
  differ there; test it before claiming support.
- **The blind spot is misread as a bug.** "Week 41% as of 14:02 from
  laptop" has to be the default rendering, not a detail on a settings page.
- **Anthropic absorbs the display.** §1.6. Headroom's value is the alarms
  and the glance, not the number. Build the widget early.
