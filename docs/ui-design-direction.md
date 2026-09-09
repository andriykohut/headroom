# Headroom — UI design direction

Design direction for the three Compose screens in the Part 3 plan (Tasks 14–16).
It fits the behaviour and state handling those tasks define; where it asks for
something the plan does not have, §12 lists it. Everything here is expressed in
Material 3 terms so it maps onto Compose without interpretation.

The rendered version of this document is `docs/ui-mockup.html` — thirteen
phone renders, light and dark, every state below, self-contained with the
typeface embedded. Where the two disagree the mockup is the look and this
document is the reasoning; both were revised together.

Read with: spec §1 (purpose), §2 (the live response shape, verified
2026-09-06 — an earlier draft of this document was designed against the
superseded shape), §5 (triggers), §7 (nothing fails silently), and the real
fixture at `domain/src/test/resources/usage_response.json`.

What the design consumes per limit entry, in wire terms: `kind` (`session`,
`weekly_all`, `weekly_scoped`, or anything new), `group` (`session`,
`weekly`, or anything new), `percent` (integer 0–100, percent used),
`severity` (string; `normal` observed), `resets_at` (ISO-8601 with offset),
`scope.model.display_name` (for `weekly_scoped`), and `is_active` (boolean).
There is no rejection flag anywhere in the response, and the app never makes
a request that could be refused, so it cannot observe one. The wall is
therefore defined by the only signal the data can produce: **fully used**,
`percent >= 100` (`TriggerEvaluator.FULLY_USED` in the domain). Every
"wall" below means exactly that, and the copy says "used up" and "lifts",
never "rejected".

## 1. Point of view

Headroom is an instrument, not a dashboard. It is opened for two seconds to
answer one question — *how much is left, and when does it come back* — and it
should look like a gauge you glance at rather than a page you read. So the
screen is quiet by default: cool neutral surfaces, one big number, flat bars
with no card chrome. Colour is spent only where attention is needed. A calm
screen *means* you are fine; amber means you are near your own warning line;
the wall is a hatched, blocked-off bar. Each of those reads without colour.

This is also what keeps it unmistakably not an official client. There is no
wordmark, no logo, no warm cream or terracotta anywhere, no accent used
decoratively. The identity is a steel-blue gauge on a cool ground.

## 2. Colour

### Scheme

A fixed scheme, not dynamic colour. Status colours must mean the same thing on
every phone, and Material You would re-seed `tertiary` from the wallpaper.
Pass these into `lightColorScheme(...)` / `darkColorScheme(...)`; roles not
listed take the M3 defaults for that scheme.

| Role | Light | Dark | Carries |
| --- | --- | --- | --- |
| `primary` | `#3E5C76` | `#A6C8E8` | Bar fill when fine; the one filled button per screen |
| `onPrimary` | `#FFFFFF` | `#0A3350` | |
| `primaryContainer` | `#D3E4F8` | `#254566` | Not used on the meter; available for the linked-account row |
| `onPrimaryContainer` | `#0D2136` | `#D3E4F8` | |
| `tertiary` | `#8F6300` | `#F2BE4A` | Approaching: bar fill, number, status word. The light value is the brightest amber that still clears 4.5:1 for the 14sp status word on `surface` and 3:1 as a fill on the track; a prettier gold fails both |
| `onTertiary` | `#FFFFFF` | `#402D00` | |
| `tertiaryContainer` | `#FFDF9E` | `#5C4300` | Unused by default; reserved for the approaching state if a container is ever needed |
| `error` | `#B3261E` | `#F2B8B5` | Wall: hatch lines, number, status word; the re-link panel title |
| `errorContainer` | `#F9DEDC` | `#8C1D18` | Wall: track fill under the hatch; re-link panel surface |
| `onErrorContainer` | `#410E0B` | `#F9DEDC` | |
| `surface` | `#F8F9FC` | `#101417` | Screen ground. Set it explicitly on the root `Surface` |
| `onSurface` | `#191C1F` | `#E1E3E6` | Titles, numbers, status lines that need weight |
| `onSurfaceVariant` | `#43474E` | `#C3C7CF` | Reset lines, group headers, the age line, descriptions |
| `surfaceContainer` | `#ECEEF2` | `#1C2023` | Offline strip |
| `surfaceContainerHigh` | `#E6E8EC` | `#262A2E` | Not-linked panel, permission-denied panel |
| `surfaceContainerHighest` | `#E0E3E7` | `#313539` | Bar track (the unfilled part — the headroom) |
| `outline` | `#737780` | `#8D9199` | Text field border, placeholder bar outline |
| `outlineVariant` | `#C3C7CF` | `#43474E` | Nothing structural; do not add dividers |

Rules that keep the palette honest:

- `tertiary` and `error` are status colours only. Never use them as accents on
  buttons, icons or headings outside a status context.
- No green. "Fine" is `primary` — a neutral-ish steel blue — so the screen has
  no traffic-light identity, and the first appearance of chroma is itself the
  signal.
- No warm ground. Light `surface` is a cool white, never cream.

### The three states, without colour

A fourth appearance sits outside this partition. When a window's reset time has
passed, the reading describes a window that has ended, so the bar empties and
says so rather than drawing a figure that is no longer true — the one case
where the honest answer is that there is nothing to measure yet.

Colour is the fastest cue, so it is used, but every state also differs in
**form**, **weight** and **words**:

| | Fine (`percent < threshold`) | Approaching (`threshold <= percent < 100`) | Wall (`percent >= 100`, fully used) |
| --- | --- | --- | --- |
| Fill | solid `primary`, stops short of the notch | solid `tertiary`, past the notch | full-width `errorContainer` with 45° `error` hatch lines |
| Notch | visible ahead of the fill | visible behind the fill | visible, inside the hatch |
| Number | weight 400, `onSurface` | weight 700, `tertiary` | weight 700, `error` |
| Status line | "Resets in 2h 14m" | "Near limit, resets in 2h 14m" | "Limit reached. Lifts in 2h 14m", promoted to `titleMedium` |
| What is loud | nothing | the number | the countdown |

So at a glance: approaching makes *how much* loud; the wall makes *when* loud.
That difference holds in greyscale, and it holds for a `weekly_scoped` bucket
with a 30-character model name just as well as for the hero.

The three states partition 0–100 exactly: below the threshold, at or above
it, and at 100. There is no fourth state. An earlier draft kept a solid
tertiary "At limit" bar for 100% and reserved the hatch for a server-side
rejection flag; that flag does not exist in the live shape, so "fully used"
*is* the wall, and 100% hatches. The status word is "Limit reached" (the
notification title's wording) and the countdown is phrased "Lifts in" (the
notification body's), so screen and notification say the same thing. The
server's own `severity` never changes which of the three states a bar is in —
see §5 for why, and for what it does instead.

## 3. Typography

One family, bundled: **Atkinson Hyperlegible Next**, weights 400, 600 and 700
(three files, under ~100 KB each). It exists for exactly this job — being read
correctly at a glance — and its unambiguous figures are the point on a screen
that is mostly digits. Register it as the `FontFamily` for the whole
`Typography`; leave the M3 size scale untouched. If bundling is skipped for any
reason, everything below works on the default Roboto; the design does not
depend on the face.

Numbers must not jitter as they change. Request tabular figures
(`fontFeatureSettings = "tnum"`) *and* reserve the number's slot at the width
of "100%" measured in the same style, right-aligned. The reservation is what
actually guarantees stability, whatever the font supports.

| Element | Slot | Weight | Colour |
| --- | --- | --- | --- |
| Hero number ("23") | `displayLarge.copy(fontSize = 72.sp, lineHeight = 80.sp, letterSpacing = (-2.5).sp)` — larger than the M3 slot on purpose: three bars leave air, and the number is what should fill it | 400 / 700 on alert | `onSurface` / status colour |
| Hero "%" | `headlineLarge` (32sp), same baseline as the digits, 3dp after them | as the digits | as the digits |
| Hero "used" | `bodyMedium`, baseline-aligned, 10dp after the "%" | 400 | `onSurfaceVariant` |
| Hero title ("Current session") | `titleMedium` | 600 | `onSurface` |
| Compact number ("67%") | `headlineSmall` (24sp) | 400 / 700 on alert | `onSurface` / status colour |
| Compact title | `bodyLarge` | 400 | `onSurface` |
| Reset / status line | `bodyMedium`; `titleMedium` at the wall | 400; the status word 600 | `onSurfaceVariant`; status word in the status colour |
| Group header ("This week") | `titleSmall`, sentence case | 600 | `onSurfaceVariant` |
| Age line ("Updated 5 min ago") | `bodyMedium`; `titleSmall` when stale | 400; 600 when stale | `onSurfaceVariant`; `onSurface` when stale |
| Panel title | `titleMedium` | 600 | `onSurface` (`error` for re-link) |
| Panel body | `bodyMedium` | 400 | `onSurfaceVariant` |
| Settings row title / description | `bodyLarge` / `bodyMedium` | 400 | `onSurface` / `onSurfaceVariant` |
| Threshold value ("90%") | `titleLarge`, tabular | 600 | `onSurface` |

The number is *used*, not remaining, and the hero says so once with the small
"used" suffix. The terminal `/usage` panel, the threshold setting and every
notification are all phrased in percent used; the app must not be the one
place that inverts it. The bar's empty track is the headroom — that is where
the name lives, visually, without a second number.

`percent` arrives as an integer, so there is no formatting step: render it
with `toString()` and a "%" — no rounding, no decimals to drop, no locale
number formatter (which would otherwise insert grouping separators or
non-ASCII digits and defeat the tabular slot). The slot reserved at the width
of "100%" is therefore also the maximum the value can occupy.

No all-caps labels anywhere. No eyebrow labels. No dividers.

## 4. The bar

Drawn with `Canvas`, not `LinearProgressIndicator`: the notch cut-out and the
hatch cannot be expressed through the M3 indicator's parameters. Give the
composable `progressBarRangeInfo` semantics and merge the whole bar into one
accessibility node reading "Current session, 42 percent used, resets in 2
hours 14 minutes" (or the status-line wording on alert).

### Hero (Current session)

```
Current session                                    titleMedium
                                                   ↕ 4dp
42% used                                           displayLarge digits, headlineMedium "%", labelLarge "used"
                                                   ↕ 8dp
████████████████████░░░░░░░░░░░░░░░░░░░░ ░░░░░     track 12dp tall, CircleShape ends
                                        ^ notch    2dp cut-out at threshold, full track height
                                                   ↕ 8dp
Resets in 2h 14m                                   bodyMedium onSurfaceVariant
```

### Compact (every other bucket)

```
All models                                    67%  bodyLarge · headlineSmall, right-aligned, slot = width of "100%"
                                                   ↕ 6dp
████████████████████████████░░░░░░░ ░░░░           track 8dp tall
                                    ^ notch
                                                   (no reset line in the fine state — the band header carries it, §5)
                                                   ↕ 8dp, only when a status line is present:
Near limit, resets Wed 02:00                       bodyMedium onSurfaceVariant, status word 600 in the status colour
```

Anatomy and rules:

- **Track**: `surfaceContainerHighest`, full-width, `CircleShape`. It is the
  headroom, so it is always solid — never faded, never an outline (except the
  placeholder, §6).
- **Fill**: from the left, `fraction = (percent / 100f).coerceIn(0f, 1f)`,
  same rounded shape, `primary` / `tertiary` per state. Animate the fraction
  with `animateFloatAsState` (400 ms, `FastOutSlowInEasing`) — the only
  non-user-triggered motion in the app. The system animator scale handles
  reduced motion.
- **Notch**: a 2dp-wide vertical gap cut through both track and fill at
  `x = width * threshold / 100`, showing `surface` through. Always drawn,
  whatever the trigger toggle says: it tells the user where their warning line
  is, and when the fill crosses it the state change is legible as geometry.
- **Wall**: track painted `errorContainer` edge to edge, then 45° `error` lines,
  2dp stroke, 8dp pitch, clipped to the rounded track. Notch still cut.
- **Title**: takes the remaining width (`weight(1f)`), `maxLines = 2`, ellipsis
  at the end. The number column never shrinks or wraps. A long `weekly_scoped`
  display name therefore wraps to a second line and the number stays put.
- **Number** shows `percent` exactly as sent. The clamp on the fill is
  defensive only; the wire contract is 0–100.
- **Status line**: `resets_at` is a wall-clock instant (ISO-8601 with offset;
  parse with `java.time.OffsetDateTime` and keep it as epoch seconds in the
  domain). The countdown is `formatCountdown(resetsAt - now)` evaluated at
  render time, so it is always computed against the phone's clock, never
  against the snapshot's. When it reads "now" (the window has passed but the
  data predates it), render "Reset. Refresh for the new window." in
  `onSurface` — the number above is from the old window, so it is dimmed as
  stale (§6).

## 5. Grouping and order

The server sends `group` with every entry, so session-versus-weekly is given,
not derived. The screen has one band per `group` value, in this order:

```
[Current session]         hero — the entry with kind == "session"
                          (any further entries with group == "session" render compact beneath it)

This week                 band for group == "weekly"
  All models              kind == "weekly_all", always first
  Opus                    then every other weekly entry — weekly_scoped, titled from
  Sonnet                  scope.model.display_name, and any kind not yet known —
                          in the order the server sent them

<group value>             one band per unrecognised group value, headed by that value
  <kind or display name>  verbatim (e.g. "monthly"), after This week, in server order;
                          entries render with the hint line from §6
```

Rules, each a line in a pure `groupBuckets(snapshot): List<Band>` with a
unit test against the committed fixture:

- Band order: `session`, `weekly`, then unrecognised groups in first-seen
  order. A band with no entries is not drawn, except the two expected slots
  in §6 (missing bucket).
- Within a band, the anchor kind (`session`, `weekly_all`) comes first; the
  rest keep server order. Never sort by percent, `severity` or `is_active` —
  the layout must be the same shape every time it is opened.
- There is no catch-all "Other" band any more. An unrecognised `kind` inside
  a known group stays in that group (a new weekly limit is still a weekly
  limit); only an unrecognised `group` earns a new band, and the band's
  header is the group string itself, sentence-cased if it is a single word,
  otherwise verbatim.
- Reset time, once per band. When every entry in a band shares `resets_at`
  (the fixture's two weekly entries do), the band header carries it on the
  right — "Resets Wed 02:00", `bodySmall` `onSurfaceVariant`, local time as
  weekday + `HH:mm` — and the rows carry no reset line of their own. A band
  whose entries disagree shows the time per row instead, in the row's status
  line. The session hero always keeps its own countdown ("Resets in 2h 14m"):
  hours away, relative is the right grain; days away, a clock time is.
- Titles: the hero keeps "Current session". Compact rows show a
  `displayLabel(bucket)`: inside "This week", a title of the form
  `Current week (X)` shows as `X` (so `weekly_all` reads "All models" and a
  scoped entry reads its `display_name`); every other title shows verbatim.
  This only removes the prefix the header already states — `LimitBucket.title`
  and the notification copy are unchanged. `scope.surface` has only been seen
  as `null`; ignore it until it means something.

### `is_active` — kept, not shown

The server marks one entry as the limit currently binding. It is tempting to
let it choose the hero. Don't: a glanceable meter must be the same shape every
time, so the hand goes to the same place — hero is always the session, the
week is always below it. Reordering on a server flag would also collide with
the stale rule (a stale snapshot would keep a stale hero) and with the
three-state vocabulary, which already uses weight and position. The flag's
semantics are also known from exactly one observation (session active at 23%,
weeklies inactive at 12% and 7%), which is not enough to build layout on.

So `is_active` is carried in the domain model, ignored by the layout, and not
rendered in v1. If a second reading ever shows it moving to a weekly bucket
while the session is fine, the right use is the accessibility description
("currently the binding limit") and possibly a small `onSurface` dot before
that row's title — never a reorder.

### `severity` — the server's word versus the user's line

The notch, the approaching colour and the approaching notification are all
keyed to the user's own threshold; that is the one number the user set and
can see on the meter. `severity` is the server's independent assessment,
observed only as `normal`, and the two can disagree in both directions:

| `percent` vs threshold | `severity` | Bar state | Extra |
| --- | --- | --- | --- |
| 95 ≥ 90 | `normal` | approaching (tertiary) | nothing |
| 75 < 90 | something other than `normal` | fine (primary) | a hint line |
| 100 | anything | wall (hatched) | nothing — the wall says it |
| 60 < 90 | `normal` | fine (primary) | nothing |

The picture always follows the user's number and the 100% line; the server's
disagreement becomes words, never a second colour system. Concretely: any
`severity` other than `normal` is rendered as a hint line under the status
line, `bodySmall` `onSurfaceVariant`, in the same slot the unknown-kind hint
uses: "Server flags this limit as <value>." — value verbatim. It is a hint
even at the wall, where it is suppressed only because the hatch already says
everything a hint could. Nothing is dropped silently (§7), and nothing
contradicts the notch. `severity` is never mapped to a state (§12).

## 6. Usage screen, state by state

Layout skeleton, portrait, 360dp wide:

```
┌────────────────────────────────────────┐
│ Updated 5 min ago               ↻   ⚙  │  age line (bodyMedium) + two IconButtons, 48dp targets
│                                        │  ↕ 24dp
│ Current session                        │
│ 42% used                               │  hero block
│ ████████████████░░░░░░░░░░ ░░░░░       │
│ Resets in 2h 14m                       │
│                                        │  ↕ 36dp
│ This week            Resets Wed 02:00  │  band header: titleSmall left, bodySmall right (shared reset time)
│                                        │  ↕ 14dp
│ All models                       67%   │
│ ████████████████████████░░ ░░░         │  no reset line: the header already said it
│                                        │  ↕ 20dp between compact bars
│ Opus                             91%   │  ← bold, tertiary
│ ████████████████████████████████ ██░   │  ← tertiary fill, past the notch
│ Near limit, resets Wed 02:00           │  ← a status line appears only when there is status
│                                        │
│ Sonnet                           12%   │
│ █████░░░░░░░░░░░░░░░░░░░░░ ░░░         │
└────────────────────────────────────────┘
```

There is no app name on this screen. The launcher already names it; the one
line of chrome is spent on the fact that matters most for trusting the bars —
how old they are. Panels below are the only containers on the screen, all
`shapes.large` (16dp), 16dp inner padding, full-width, sitting between the age
line and the hero.

**Ready, fresh.** As above. The age line is `onSurfaceVariant`.

**Ready, stale** (`now - fetchedAt > STALE_AFTER_SECONDS`). The age line
becomes `titleSmall`, 600, `onSurface`: "Updated 3 hr ago". Every number and
fill drops to 38% alpha (the M3 disabled convention). Countdowns keep full
strength. The split is principled, not cosmetic: `percent` is a *sample* —
it was true at `fetchedAt` and has drifted since — whereas `resets_at` is a
*fact*, a wall-clock instant that does not age. The countdown is recomputed
from the phone's clock on every render, so it is exactly as correct at three
hours old as at three seconds old, right up until it reaches zero and the
"Reset. Refresh for the new window." line takes over (§4). The refresh icon
stays. Nothing else changes; the screen looks dimmed, which is exactly what
it is.

**Refreshing** (a refresh in flight from any state). The age line reads
"Updating…" and the refresh icon is replaced by a 20dp indeterminate
`CircularProgressIndicator`. Bars stay as they are. Needs a `refreshing`
boolean from the ViewModel (§12).

**Loading** (cold start, before the first result). The age line reads
"Checking…"; the hero and "All models" slots show placeholder bars:

```
Current session                         │
—                                       │  displayLarge, onSurfaceVariant
╭──────────────────────────────╮        │  outline-only track: 1dp `outline` stroke, no fill, notch still cut
╰──────────────────────────────╯        │
```

**Offline / fetch failed** (`Failed`, `needsRelink = false`). A quiet strip,
`surfaceContainer`, no icon, no border:

```
┌────────────────────────────────────────┐
│ Couldn't update                        │  titleMedium onSurface
│ The request timed out.                 │  bodyMedium onSurfaceVariant — state.message verbatim
│ Showing the reading from 25 min ago.   │  bodyMedium onSurfaceVariant
│                            Try again   │  TextButton, right-aligned
└────────────────────────────────────────┘
```

The bars below render exactly as Ready (fresh or stale by age). The strip is
neutral on purpose: this is not the user's fault and needs no alarm, only an
explanation. If `snapshot` is null (never succeeded), the placeholders from
Loading appear below the strip, so the screen is still not empty.

**Re-link needed** (`Failed`, `needsRelink = true`). This is the one failure
that needs the user, so it gets the only alarming container in the app:

```
┌────────────────────────────────────────┐
│ ⚿  Re-link needed                      │  Icons.Outlined.LinkOff (24dp) + titleMedium, both `error`
│ The saved credential stopped working,  │  bodyMedium onErrorContainer
│ so usage can't be updated.             │
│ The server rejected the refresh        │  bodySmall onErrorContainer — state.message verbatim
│ (HTTP 400).                            │
│                                        │
│ ┌────────────────────────────────────┐ │
│ │          Scan a new code           │ │  filled Button, full width, `error` container / `onError` text
│ └────────────────────────────────────┘ │
└────────────────────────────────────────┘
```

Surface `errorContainer`. The bars below are dimmed as stale regardless of
age — the credential is dead, so nothing below is going to update. The
distinction from offline is therefore threefold: colour (error vs neutral),
form (icon, filled button vs text button), and action (scan vs retry).

**Not linked** (first run, or after unlink). Panel on `surfaceContainerHigh`:

```
┌────────────────────────────────────────┐
│ Nothing linked yet                     │  titleMedium
│ On your computer, run /headroom-link   │  bodyMedium onSurfaceVariant
│ in Claude Code. It prints a code;      │
│ scan it here and the bars appear.      │
│                                        │
│ ┌────────────────────────────────────┐ │
│ │             Scan code              │ │  filled Button, `primary`
│ └────────────────────────────────────┘ │
└────────────────────────────────────────┘

Current session                            placeholder hero, 38% alpha
—
╭──────────────────────────────╮
╰──────────────────────────────╯

This week
All models                         —       placeholder compact, 38% alpha
╭──────────────────────────────╮
╰──────────────────────────────╯
```

The age line is absent (nothing to date). The ghosted placeholders show what
the screen becomes, so the empty state is a preview rather than a void. The
tool name appears in body copy only — it is an instruction, not a brand — and
never in a title, app bar or icon.

**Unrecognised kind or group.** An entry whose `kind` the parser has not seen
renders with the compact bar in whatever band its `group` puts it (§5),
titled from `scope.model.display_name` if the server supplied one, otherwise
the `kind` string verbatim, plus a hint line under the status line:

```
weekly_surface                    34%
██████████░░░░░░░░░░░░░░░░ ░░░
Resets in 2d 1h
Limit type the app doesn't recognise yet   bodySmall onSurfaceVariant
```

An entry whose `group` is new gets its own band headed by the group string
(§5) and the same hint. Same bar, same states, same notch; an unrecognised
entry at the wall still hatches, and one past the user's threshold still turns
tertiary. The hint slot is shared with the `severity` hint from §5; if both
apply, the kind hint comes first, each on its own line.

**Expected bucket missing.** Only two entries are expected: `kind ==
"session"` (the hero) and `kind == "weekly_all"` (first in This week). When
either is absent from a snapshot, its slot shows the placeholder bar at full
alpha with "—" for the number and the line "Not reported in the last
reading." Never 0%, never an empty fill. `weekly_scoped` entries come and go
with the plan and get no placeholder.

**Approaching and the wall** on the hero, side by side:

```
Current session                     │  Current session
91% used                            │  100% used                        ← both 700; tertiary vs error
██████████████████████████████ ██░  │  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ ▓▓▓  ← solid past notch vs hatched
Near limit, resets in 1h 02m        │  Limit reached. Lifts in 1h 02m   ← bodyMedium vs titleMedium 600
```

## 7. Import screen

Small `TopAppBar`, navigation icon `ArrowBack` → `onCancel`, title "Link an
account" (`titleLarge`). Content is a single column, 20dp horizontal padding.

**Scanning** (permission granted, `showPaste = false`):

```
┌────────────────────────────────────────┐
│ ←  Link an account                     │
│                                        │  ↕ 16dp
│ ┌────────────────────────────────────┐ │
│ │ ┌                              ┐   │ │  PreviewView in AndroidView, aspect 1:1,
│ │                                    │ │  clipped to shapes.large; four 24dp corner brackets
│ │          (camera preview)          │ │  in `onSurface` at 70% alpha, 2dp stroke, inset 24dp
│ │                                    │ │
│ │ └                              ┘   │ │
│ └────────────────────────────────────┘ │
│                                        │  ↕ 16dp
│ Point the camera at the code printed   │  bodyLarge onSurface
│ by /headroom-link.                     │
│                                        │  ↕ 8dp
│ On your computer, run /headroom-link   │  bodyMedium onSurfaceVariant
│ in Claude Code, or tools/headroom-link │
│ from a terminal.                       │
│                                        │  (flexible space)
│           Paste the code instead       │  TextButton, centred, 16dp above the nav bar inset
└────────────────────────────────────────┘
```

Foreign codes are ignored (the plan's `NotOurs`); nothing flashes. A damaged
Headroom code shows a strip under the preview, `errorContainer`,
`shapes.medium` (12dp), 12dp padding: the `Rejected` message in
`onErrorContainer` `bodyMedium`, plus the line "Run /headroom-link again and
scan the new code." Scanning continues behind it. A successful scan calls
`onLinked` immediately — the Usage screen appearing with bars *is* the
confirmation; no toast.

**Permission denied** (`granted = false`). The preview square is replaced by a
`surfaceContainerHigh` panel of the same shape and height, and paste is opened
automatically (`showPaste = true`) so the screen is never a dead end:

```
│ ┌────────────────────────────────────┐ │
│ │        ⊘  Camera access is off     │ │  Icons.Outlined.NoPhotography 32dp + titleMedium, centred
│ │   Scanning needs the camera. You   │ │  bodyMedium onSurfaceVariant, centred
│ │   can paste the code below instead.│ │
│ │           Allow camera             │ │  TextButton
│ └────────────────────────────────────┘ │
│  (paste field and Link button follow)  │
```

"Allow camera" re-launches the permission request; when the system reports the
permission permanently denied (`shouldShowRequestPermissionRationale` is
false after a denial), it opens the app's system settings page instead, with
the button relabelled "Open settings".

**Paste** (`showPaste = true`):

```
│ ┌────────────────────────────────────┐ │
│ │ Code from /headroom-link       ⎘   │ │  OutlinedTextField, label as shown, trailing IconButton
│ │ headroom1:eJyrVkrOz…               │ │  ContentPaste reads the clipboard into the field;
│ │                                    │ │  minLines = 4, FontFamily.Monospace bodyMedium,
│ │                                    │ │  autoCorrect off, KeyboardType.Ascii
│ └────────────────────────────────────┘ │
│                                        │  ↕ 12dp
│ ┌────────────────────────────────────┐ │
│ │               Link                 │ │  filled Button, full width, enabled when non-blank
│ └────────────────────────────────────┘ │
│              Scan instead              │  TextButton (hidden while permission is denied)
```

Monospace is justified here and nowhere else: the field holds a machine
string, and the face makes a truncated paste visible. `NotOurs` from a paste
shows "That doesn't look like a Headroom code." in the same error strip as a
rejected scan. The field is `rememberSaveable`, as the plan requires.

## 8. Settings screen

Small `TopAppBar`, `ArrowBack` → `onBack`, title "Notifications". Four rows,
each a `ListItem` made `toggleable` as a whole (56dp minimum height, the
`Switch` as `trailingContent`), separated by 24dp of space rather than
dividers. The threshold lives *inside* the approaching-limit row: a setting
belongs with the trigger it tunes, and that is what stops the screen reading
as a form.

```
┌────────────────────────────────────────┐
│ ←  Notifications                       │
│                                        │  ↕ 8dp
│ Session reset                     (●)  │  bodyLarge · Switch
│ When the 5-hour window rolls over      │  bodyMedium onSurfaceVariant
│                                        │  ↕ 24dp
│ Weekly reset                      (●)  │
│ When a weekly window rolls over,       │
│ per model                              │
│                                        │
│ Approaching limit                 (●)  │
│ When any limit passes your warning     │
│ line                                   │
│                                        │  ↕ 12dp
│   Warn at                        90%   │  bodyMedium onSurfaceVariant · titleLarge 600, tabular
│   ○────────────────────────●───○       │  Slider 50..99, steps = 48, full width, inset 16dp left
│                                        │
│ Limit reached                     (●)  │
│ When a limit is fully used             │
│                                        │
│                                        │  (flexible space)
│ Linked account                         │  titleSmall onSurfaceVariant
│ Unlink this phone                      │  TextButton, `error` content colour
└────────────────────────────────────────┘
```

Slider block: wrapped in `AnimatedVisibility(visible = approachingLimit)` with
the default expand/shrink — motion that answers the toggle. It is not shown
disabled; a greyed slider under an off switch is a form again. While the
slider is being dragged the value text updates live; the store is written on
`onValueChangeFinished`, clamped by `clampThreshold`. The threshold value is
the same number the notch on the Usage screen is cut at, so the description
says "your warning line" — the same phrase the status line uses ("Near
limit").

The linked-account group and "Unlink this phone" are an addition (§12); if
omitted, the screen ends after the last row.

## 9. Spacing, layout rhythm, small phones

- 4dp grid. Screen horizontal padding 20dp on compact widths. Content column
  capped at 560dp and centred on anything wider (tablets, unfolded foldables);
  no second column.
- Vertical rhythm on Usage: age line (48dp row) → 16dp → hero → 36dp → band header →
  12dp → first compact bar → 20dp between bars → 32dp before the next group →
  24dp bottom padding plus navigation-bar insets. Panels sit 16dp below the
  age line and 24dp above the hero.
- Shape scale used: `CircleShape` for tracks and fills; `shapes.medium` (12dp)
  for inline message strips; `shapes.large` (16dp) for panels and the camera
  square; M3 defaults for buttons and text fields. Nothing else is rounded.
- Every screen is a `verticalScroll` column; nothing is fixed to the bottom
  except the import screen's paste toggle, which is pushed down with a
  `Spacer(weight(1f))` and still scrolls when the keyboard is up.
- 360×640dp at 1.3× font scale: the hero number ("100% used") is ~250dp wide
  and fits; the screen scrolls past two groups. The compact number column is
  measured, not hard-coded, so it grows with the font. Titles wrap to two
  lines before anything else gives.
- Landscape is the same column; no special layout.
- Touch targets: every icon button 48dp; the whole settings row toggles; the
  refresh icon is a real `IconButton`, not the age text.
- Theme: `MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light)`;
  the root `Surface(color = colorScheme.surface)` fills the window. Enable
  edge-to-edge and pad with `WindowInsets.safeDrawing`.

## 10. Compose mapping, for the implementer

| Design element | Compose |
| --- | --- |
| Bar | custom `@Composable UsageBar(bucket, threshold, now, hero: Boolean, dimmed: Boolean)` drawing with `Canvas`; `animateFloatAsState` on the fraction |
| Hatch | `clipPath(roundedRectPath) { repeat(...) { drawLine(error, start, end, strokeWidth = 2.dp.toPx()) } }` |
| Notch | after drawing track and fill, `drawRect(surface, topLeft = Offset(x - 1.dp.toPx(), 0), size = Size(2.dp.toPx(), height))` |
| Hero number | `Text(buildAnnotatedString { digits at 72.sp; "%" in headlineLarge })` beside `Text("used", bodyMedium)`, `alignByBaseline()` |
| Panels | `Surface(shape = shapes.large, color = …)` — not `Card`; no elevation, no border |
| Age line + actions | `Row` with `Text(weight(1f))`, `IconButton(Refresh)`, `IconButton(Settings)` |
| Top bars (Import, Settings) | `TopAppBar(title, navigationIcon = IconButton(ArrowBack))` |
| Settings rows | `ListItem(headlineContent, supportingContent, trailingContent = Switch)` with `Modifier.toggleable(role = Role.Switch)` |
| Threshold | `Slider(valueRange = 50f..99f, steps = 48)` inside `AnimatedVisibility` |
| Camera square | `AndroidView(PreviewView)` in a `Box(aspectRatio(1f)).clip(shapes.large)` with a `Canvas` overlay for the brackets |
| Paste field | `OutlinedTextField(minLines = 4, textStyle = bodyMedium.copy(fontFamily = Monospace), trailingIcon = IconButton(ContentPaste))` |

## 11. What not to do

- No app name, logo or wordmark in any screen's chrome. The launcher icon is
  the mark in §13 — a disc with the threshold slit cut through it — and
  nothing that resembles any AI company's mark.
- No cream, terracotta, coral, orange or salmon anywhere, in either theme.
- No cards around data. A container means "something other than the data is
  talking".
- No dividers, eyebrow labels, all-caps labels, or middle-dot meta strings.
- No monospace outside the paste field.
- No entrance animations, no per-item fades. The fill animation is the one
  motion.
- Never invent a bucket. If the server sends four, show four; if it sends a
  kind or group the app has never seen, show it as it came.
- Never reorder on data. Not by `percent`, not by `severity`, not by
  `is_active`. The layout is the same shape every time it is opened.
- Never let the server's `severity` recolour a bar. The user's threshold owns
  the picture; the server's opinion is a line of text.

## 12. Where this departs from the plan

Behaviour is the plan's; these are the places the design needs something the
plan's code does not have, or reverses a rendering choice. Each is small.

1. **`LinearProgressIndicator` → `Canvas` bar.** The notch and hatch are the
   colour-independent cues and cannot be expressed through the M3 indicator.
   Same inputs, same semantics; rendering only.
2. **`UsageScreen` needs `thresholdPercent: Double`.** The notch is drawn at
   the configured threshold. `MainActivity` reads `SettingsStore.flow` and
   passes it down; the approaching state is computed in the UI as
   `percent >= threshold && percent < 100`, and the wall as `percent >= 100`,
   matching `TriggerEvaluator`'s threshold and `FULLY_USED` rules.
3. **Hard-coded green/amber/red → theme roles.** The plan's `barColour`
   mirrors the terminal statusline (60/85 breakpoints). This design keys the
   colour change to the user's own threshold instead, so the bar, the notch
   and the notification all agree on where "approaching" begins. One rule, one
   number, visible on the meter.
4. **`Card` → flat.** Bars sit on `surface`; only interruptions get containers.
5. **"Headroom" title + filled "Settings" button → age line + icon buttons.**
   The chrome line carries data age, which is the one thing a glance needs to
   trust the bars, and a filled button for navigation over-weights it.
6. **`refreshing` flag on `UsageViewModel`.** `UsageState` does not express an
   in-flight refresh; a `StateFlow<Boolean>` set around `repository.refresh()`
   is enough.
7. **Settings "Back" `TextButton` → `TopAppBar` navigation icon.** Same
   `onBack`; Android convention. The threshold slider is nested under its
   trigger and hidden (not disabled) when that trigger is off.
8. **Import: denied permission opens paste automatically**, and the
   permanently-denied path opens the app's settings page instead of
   re-requesting into silence. Also a clipboard-paste trailing icon.
9. **`unlink()` has no UI.** `UsageViewModel.unlink()` exists; the Settings
   screen should expose it as "Unlink this phone", with a confirmation
   `AlertDialog` ("Unlink this phone? Notifications stop until you scan a new
   code.") since it discards a Keystore-held credential.
10. **Optional, recommended:** wrap the Usage column in `PullToRefreshBox`
    (Material 3 ≥ 1.3) alongside the refresh icon — the natural gesture on a
    glanceable screen. And, when `AlarmManager.canScheduleExactAlarms()` is
    false (spec §6), show a neutral strip at the top of Settings: "Reset
    alerts may arrive late. Allow exact alarms" with a button deep-linking to
    the system page. Both are outside Tasks 14–16 and can follow.

Added after the live response shape replaced spec §2's original (the plans
still describe the old `five_hour`/`seven_day` model as of this revision;
these are what the design needs from the rewritten domain):

11. **`LimitBucket` must carry `group: String`, `severity: String` and
    `isActive: Boolean`** alongside `kind`, `title`, `percent: Int`,
    and `resetsAt` (epoch seconds, parsed from the ISO-8601 string). The
    domain built against the live shape has all of these (`utilization` is
    the `percent` field widened to a Double, which is fine — the UI renders
    `toInt()`). `group` drives the bands (§5); `severity` feeds the hint
    line; `isActive` is stored but unused (§5). Keep the raw `kind` string
    even for recognised kinds, as before, so an unrecognised one can be shown
    verbatim.
12. **The wall is "fully used", not "rejected" — and there is no `rejected`
    flag.** An earlier draft of this document asked the parser to derive one
    from `severity` or from the `at_wall=1` response, and reserved the hatch
    for it. That was unreachable: the live response carries no rejection
    field (the plan's `status: "rejected"` never existed), `severity` has
    only ever been observed as `normal` so any mapping from it would be the
    same inference that made spec §2 wrong, and `at_wall=1` is what Claude
    Code sends *after its own request was refused* — Headroom never sends a
    request that could be, so it can never learn this. The domain therefore
    keys the wall on `percent >= 100` (`FULLY_USED`), the notification says
    the limit is used up and when it lifts, and a test asserts it never
    claims requests are being rejected. The design follows: 100% hatches,
    the status word is "Limit reached", the countdown is "Lifts in". If a
    real at-the-wall response is ever captured and carries a genuine signal —
    a `severity` value, a new field — the change is confined to the wall
    predicate in `TriggerEvaluator` and the matching line in §2's state
    table; nothing in the bar's anatomy, colour or copy moves, because
    "fully used" and "refused" look and read the same on this screen.
13. **Unrecognised `severity` must reach the UI as a string**, not be
    collapsed to `normal`, so the §5 hint line can name it.
14. **Weekly reset shown as a clock time, once per band.** Because `resets_at`
    is a real timestamp, a window days away is better told as a time than a
    countdown: "Resets Wed 02:00" beats "2d 8h". The mockup adopts this (§5):
    the band header carries the shared time, rows carry none, and the session
    hero keeps `formatCountdown`. Needs a pure `formatResetTime(resetsAt,
    zone)` — local weekday plus `HH:mm` — beside the plan's formatter, with its
    own test, and a `sharedResetsAt(band)` check in `groupBuckets`.

None of these change `UsageState`, `interpretScan`, `clampThreshold`, the
existing formatters, or any UI test in the plan; items 11–13 are parser and
domain-model requirements the Part 1 rewrite should absorb.

## 13. The mark

One mark has to serve three places that want different things: the launcher
(adaptive, masked to a circle, squircle, rounded square or teardrop, and
parallaxed), the themed icon (Android 13+, a single colour re-tinted to the
wallpaper), and the notification small icon — the one that matters most for
an app whose job is notifying — which Android renders as a flat white
silhouette at 24dp, keeping only the alpha channel.

**The mark is a disc cut once by a thin horizontal slit near the top.** The
body below the slit is what has been used; the slit is the warning line from
the meter — the same notch that cuts through every bar on the Usage screen;
the sliver above it is the headroom. It is two paths and one gap, so the
silhouette, the monochrome icon and the full-colour icon are literally the
same shape rather than three interpretations of it. Rendered in context in
`docs/ui-mockup.html`.

What it is not: a battery (no nub, no outline), a gauge (no arc, no needle),
a pie (a chord, not a radius), a progress bar in a box. The slit is thin
(≈9% of the diameter) and sits at 30% of the height, which keeps it clear of
the centred, thick bar of the no-entry sign even when a themed wallpaper
tints it red.

Adaptive icon, 108dp canvas, disc r = 27 at (54, 54) — 54dp across, inside
the 66dp safe zone with 6dp to spare for parallax — slit 5dp tall:

```xml
<!-- res/drawable/ic_launcher_foreground.xml  viewportWidth/Height = 108 -->
<path android:fillColor="#DCEAF7" android:pathData="M29.25 43.2A27 27 0 0 1 78.75 43.2Z"/>
<path android:fillColor="#DCEAF7" android:pathData="M27.63 48.2A27 27 0 1 0 80.37 48.2Z"/>
<!-- res/drawable/ic_launcher_background.xml: one 108x108 rect path, #24405A -->
<!-- res/drawable/ic_launcher_monochrome.xml: the two foreground paths, any single colour -->
```

Notification icon, 24dp canvas, disc r = 10.5 at (12, 12), slit 2.2dp:

```xml
<!-- res/drawable/ic_notification.xml  viewportWidth/Height = 24 -->
<path android:fillColor="#FFFFFF" android:pathData="M2.94 6.7A10.5 10.5 0 0 1 21.06 6.7Z"/>
<path android:fillColor="#FFFFFF" android:pathData="M1.97 8.9A10.5 10.5 0 1 0 22.03 8.9Z"/>
```

Colours: background `#24405A` (a deep tone of the primary hue), foreground
`#DCEAF7` (a very light tone of it). Both parts of the disc share one colour
in every variant — the gap is the mark, not a fill level — and the
notification `color` is the dark-scheme `primary` (`#A6C8E8`) so the shade
tints the silhouette to match the bars. The mark carries no gradient, no
stroke and no text, so it needs nothing a `VectorDrawable` cannot draw.
