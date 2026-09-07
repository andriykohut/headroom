# End-to-end verification

Run on **2026-09-06**, against a real subscription account.

| | |
| --- | --- |
| Device | Android emulator, `Medium_Phone_API_35`, 1080×2400 |
| Android | 15 (API 35) |
| Build | `:app:assembleDebug`, compileSdk/targetSdk 37, minSdk 31 |
| CLI | `cli/`, Rust 1.94 |

## Test counts

| Suite | Tests |
| --- | --- |
| `:domain:test` | 53 |
| `:app:testDebugUnitTest` | 110 |
| `cli` (`cargo test`) | 65 |
| **Total** | **228** |

All green.

## Distribution constraints

Both greps the public release depends on (spec §3) pass, and were run against
the **built APK** as well as the source, since resources and generated code
ship too:

| Check | Result |
| --- | --- |
| `anthropic` in source | none |
| `claude.ai` in source | none |
| `sk-ant` / `client_id = "` in source | none |
| Logging calls (`Log.*`, `println`) in shipped source | none |
| `anthropic` / `claude.ai` / `sk-ant` anywhere in the APK | none |
| UUID-shaped strings in our own DEX | none |

Five UUID-shaped strings exist in the APK: four inside ML Kit's native barcode
library and one in a dependency's DEX. None is in a DEX containing
`dev/andrii/headroom`.

## What passed

| Step | Result |
| --- | --- |
| 4. Link and read a real account | **Pass.** Generator → payload → Keystore → API → parser → screen |
| 5. Keystore survives a restart | **Pass.** Force-stopped, reinstalled, still linked |
| 6. Notification fires, then de-duplicates | **Pass.** "Current session at 71%" fired once; two further cycles across process restarts notified nothing, against the real DataStore |
| 7. Degraded exact-alarm path | **Pass.** With `SCHEDULE_EXACT_ALARM` denied the cycle completed, still scheduled, no crash |
| 8. Back and rotation | **Pass.** Back from Settings returns to Usage and keeps the app open; rotating on Settings stays on Settings |
| 9. Offline | **Pass.** Neutral "Couldn't update" strip with the last reading dimmed — not zeros, not blank |

### The side-by-side

The screen and the wire agreed exactly:

| Bucket | Server | Screen |
| --- | --- | --- |
| session | 69% | 69% |
| weekly_all | 17% | 17% |
| weekly_scoped | 14% | 14% |

This is the only check that the chain is correct rather than merely
self-consistent: every unit test would still pass if the parser read the wrong
field.

## Four bugs this run found

Each was fixed and covered by a test. None was reachable from a unit test.

**1. `resets_at` drifts between requests.** The server computes it per request
rather than sending a fixed instant — two polls a minute apart returned
`17:20:00.480923` and `17:19:59.820504`. `EventKey` is *(bucket identity,
resets_at, trigger type)*, so the raw value mints a new key on every poll:
de-duplication would fail open and **the same notification would fire every
twenty minutes forever**. The parser now rounds to the nearest minute. Spec §2
records it.

**2. The UI's refresh poisoned the notification baseline.** The threshold rule
required an upward crossing against the cached snapshot, but
`UsageRepository.refresh()` writes to the same cache the coordinator reads as
`previous`. Opening the app while over the line meant the next poll saw "above,
and above before too" — no crossing, no notification, silently. The rule is now
once-per-window, which is what was wanted; the notification log already
guaranteed it.

**3. Material 3's baseline palette leaked in.** Only the roles the screens named
were defined, so `background`, `secondary` and friends fell back to M3's
purple-tinted defaults — a pink Settings screen, in a design whose first rule is
that nothing warm appears in either theme. Every role is now set.

**4. Settings and Import did not scroll.** In landscape the last rows — including
"Unlink this phone" — were unreachable. Both scroll now.

## Still unverified

- **The reset alarm has not yet elapsed.** The next session reset was scheduled
  for `1788715200`; confirming the notification actually arrives at it takes up
  to five hours and was not waited out. The scheduling itself is verified, in
  both the exact and degraded paths.
- **A real at-the-wall response.** Nobody has seen the server's output from an
  account that is actually at its limit, so the wall trigger fires on 100% used
  rather than on any server flag. See `docs/discovery-notes.md`.
- **A physical device.** Everything in the run above is an emulator.

  Two things were settled by installing on a real phone afterwards. The app's
  own CameraX + ML Kit scanner read the QR off a terminal and linked the
  account first try — that path had only ever run on an emulator with no
  camera, so it was previously covered by unit tests alone. And see the
  status-bar bug below, which no emulator run had surfaced.

  Installing on a real phone immediately found what the emulator could not: the
  Usage screen drew behind the status bar, putting its refresh and settings
  buttons under the clock and the battery. Android 15 forces edge-to-edge for
  targetSdk 35+, and that screen — unlike Import and Settings, which get it from
  their Scaffold — applied no window insets. The emulator's status bar is short
  enough that the collision never appeared. Fixed, and re-checked on the
  emulator with `cmd overlay enable
  com.android.internal.display.cutout.emulation.tall`, which reproduces a tall
  status bar.
- **The `/usage` comparison was made against the API directly**, not against
  Claude Code's own panel. Same source, so it proves the app agrees with the
  wire, not that both agree with what the user is shown elsewhere.

---

# Second run: push and relay

Run on **2026-09-06**, after the architecture changed from credential-on-phone
to push-and-relay. Against a real subscription account and a **physical
device** — a Pixel 8a on Android 17 (API 37), not an emulator.

| | |
| --- | --- |
| Relay | `headroom serve` on a laptop, `0.0.0.0:8765`, plain HTTP |
| Pusher | `headroom push` wired into a real `~/.claude/statusline.sh` |
| App | `:app:assembleDebug`, installed over adb (wireless debugging) |

## What was confirmed

**The whole loop works.** A status line render pushes; the relay stores; the
app reads and draws three bars — session, current week, and the per-model
weekly window labelled `Fable`. Verified from both ends: the relay's `/usage`
response and the app's own screen.

**The two sources agree on reset times.** The status line reported
`resets_at: 1788733200` (`22:20:00Z`); the usage endpoint, fetched seconds
later, reported `22:20:00.181Z`. 0.18 s apart, far inside `UsageParser`'s
rounding to the minute — so alternating between cheap and enriched pushes does
not mint new `EventKey`s and does not re-notify. Percentages agreed exactly
(33/33, 23/23).

**The status line is not slowed down.** Release binary, 40 runs against an
unreachable relay: 2.6 ms median, 3.4 ms worst. Identical against a relay that
accepts the connection and never answers, which is the case a timeout alone
does not save you from.

**The enrichment response is trimmed at source.** The live response is ~20 keys
including unreleased product codenames and a `spend` object. What reached the
relay was 517 bytes containing only `limits`. Checked by grepping the stored
bytes for each key that must not travel.

## Three bugs a physical device found that nothing else would have

- **Cleartext HTTP was blocked in the debug build.** `cleartextTrafficPermitted`
  had been set inside `<debug-overrides>`, which accepts `<trust-anchors>` and
  nothing else — the attribute was silently ignored and the debug build
  inherited `false`. OkHttp raised `UnknownServiceException`. Fixed with a
  variant-specific resource in `app/src/debug/res/xml/`, verified by dumping the
  compiled XML out of *both* APKs with `aapt2 dump xmltree`, and locked down by
  a `check-distribution.sh` gate so a permissive config cannot reach `src/main`.

- **The per-model bar flickered and vanished.** The status line fires on every
  message and carries only two windows; enrichment runs every five minutes and
  carries three. Each cheap push *replaced* the stored reading, so the `Fable`
  bar appeared for one push after each fetch and was then destroyed by the next
  keystroke. Found by a user watching the screen, not by any test. Fixed by
  having the pusher carry forward the windows the status line cannot produce,
  with a one-hour ceiling so a broken enrichment yields an absent bar rather
  than a quietly wrong one.

- **The staleness line measured the wrong thing.** `fetchedAt` was set to the
  moment of the request. Against a relay that is when the *phone polled*, not
  when the reading was taken, so an hour-old number would have rendered "just
  now" and never gone stale. `UsageApi` now dates the snapshot from the relay's
  `X-Headroom-Age`.

## Still not covered

- **The reset alarm has still never fired in anger.** Unchanged from the first
  run, and now more load-bearing: the relay only receives readings while you are
  working, so an overnight reset is first observed by whichever comes first —
  the alarm, or the next morning's push.
- **A real at-the-wall response.** Unchanged.
- **The `/usage` panel comparison.** Unchanged.
- **A relay reached over the public internet.** Everything above used a laptop
  relay on a LAN, and finally a loopback tunnel. TLS termination, and the
  latency and failure modes of a real host, are untested.
