# End-to-end verification

Run on **2026-09-06**, against a real subscription account.

| | |
| --- | --- |
| Device | Android emulator, `Medium_Phone_API_35`, 1080×2400 |
| Android | 15 (API 35) |
| Build | `:app:assembleDebug`, compileSdk/targetSdk 37, minSdk 31 |
| Generator | `tools/headroom-link`, Python 3.14 |

## Test counts

| Suite | Tests |
| --- | --- |
| `:domain:test` | 53 |
| `:app:testDebugUnitTest` | 116 |
| `tools` (pytest) | 59 |
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
- **A physical device.** Everything here is an emulator. The QR has been scanned
  off a terminal by a real phone camera (recorded in the discovery notes), but
  the app itself has only run emulated — so the camera scan path is exercised
  only by its unit tests.
- **The `/usage` comparison was made against the API directly**, not against
  Claude Code's own panel. Same source, so it proves the app agrees with the
  wire, not that both agree with what the user is shown elsewhere.
