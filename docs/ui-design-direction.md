# Headroom — UI design direction

Design direction for the three Compose screens in the Part 3 plan (Tasks 14–16).
It fits the behaviour and state handling those tasks define; where it asks for
something the plan does not have, §12 lists it. Everything here is expressed in
Material 3 terms so it maps onto Compose without interpretation.

Read with: spec §1 (purpose), §2 (bucket kinds and titles), §5 (triggers),
§7 (nothing fails silently).

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
| `tertiary` | `#7A5900` | `#F2BE4A` | Approaching: bar fill, number, status word |
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

Colour is the fastest cue, so it is used, but every state also differs in
**form**, **weight** and **words**:

| | Fine | Approaching (`utilization >= threshold`, not rejected) | Wall (`rejected == true`) |
| --- | --- | --- | --- |
| Fill | solid `primary`, stops short of the notch | solid `tertiary`, past the notch | full-width `errorContainer` with 45° `error` hatch lines |
| Notch | visible ahead of the fill | visible behind the fill | visible, inside the hatch |
| Number | weight 400, `onSurface` | weight 700, `tertiary` | weight 700, `error` |
| Status line | "Resets in 2h 14m" | "Near limit, resets in 2h 14m" | "Limit reached. Lifts in 2h 14m", promoted to `titleMedium` |
| What is loud | nothing | the number | the countdown |

So at a glance: approaching makes *how much* loud; the wall makes *when* loud.
That difference holds in greyscale, and it holds for a `weekly_scoped` bucket
with a 30-character model name just as well as for the hero.

Edge: `utilization >= 100` with `rejected == false` renders as approaching
(full solid `tertiary` fill) with the status line "At limit, resets in …".
The hatch is reserved for the server saying rejected.

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
| Hero number ("42") | `displayLarge` (57sp), letter-spacing −1sp | 400 / 700 on alert | `onSurface` / status colour |
| Hero "%" | `headlineMedium`, same baseline as the digits | as the digits | as the digits |
| Hero "used" | `labelLarge`, baseline-aligned, 6dp after the "%" | 400 | `onSurfaceVariant` |
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
                                                   ↕ 6dp
Resets in 3d 4h                                    bodyMedium onSurfaceVariant
```

Anatomy and rules:

- **Track**: `surfaceContainerHighest`, full-width, `CircleShape`. It is the
  headroom, so it is always solid — never faded, never an outline (except the
  placeholder, §6).
- **Fill**: from the left, `fraction = (utilization / 100).coerceIn(0, 1)`,
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
- **Number** shows the actual value even above 100 ("112%"); only the fill
  clamps.
- **Status line**: the countdown is `formatCountdown(resetsAt - now)`. When
  it reads "now" (the window has passed but the data predates it), render
  "Reset. Refresh for the new window." in `onSurface` — the number above is
  from the old window, so it is dimmed as stale (§6).

## 5. Grouping and order

`resetsAt` is absolute and `utilization` is per window, so session and weekly
buckets mean different things and are never interleaved.

```
[Current session]         hero — BucketKind.FIVE_HOUR

This week                 group header
  All models              SEVEN_DAY, always first
  Opus                    then SEVEN_DAY_*, SEVEN_DAY_OVERAGE_INCLUDED, and any bucket
  Sonnet only             whose rawKind == "weekly_scoped", in the order the server sent them
  Fable limit

Other                     group header, shown only if the group is non-empty
  Usage / overage         OVERAGE
  seven_day_haiku         UNKNOWN, titled by rawKind, with the hint line from §6
```

Implement as a pure `groupBuckets(snapshot): Groups` with a unit test; the
UI only iterates. Compact titles come from a pure `displayLabel(bucket)`:
inside the "This week" group, a title matching `Current week (X)` shows as
`X`; every other title shows verbatim. This removes the prefix the header
already states and touches nothing outside the UI — `LimitBucket.title` and
the notification copy are unchanged.

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
│                                        │  ↕ 32dp
│ This week                              │  group header
│                                        │  ↕ 12dp
│ All models                       67%   │
│ ████████████████████████░░ ░░░         │
│ Resets in 3d 4h                        │
│                                        │  ↕ 20dp between compact bars
│ Opus                             91%   │  ← bold, tertiary
│ ████████████████████████████████ ██░   │  ← tertiary fill, past the notch
│ Near limit, resets in 3d 4h            │
│                                        │
│ Sonnet only                      12%   │
│ █████░░░░░░░░░░░░░░░░░░░░░ ░░░         │
│ Resets in 3d 4h                        │
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
fill drops to 38% alpha (the M3 disabled convention) — the utilization is what
went stale. Countdowns keep full strength, because `resetsAt` is absolute and
still true. The refresh icon stays. Nothing else changes; the screen looks
dimmed, which is exactly what it is.

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

**Unknown bucket kind** (`kind == UNKNOWN`, `rawKind` e.g. `seven_day_haiku`).
Rendered in the Other group with the compact bar, title = `rawKind` verbatim,
plus a hint line under the status line:

```
seven_day_haiku                   34%
██████████░░░░░░░░░░░░░░░░ ░░░
Resets in 2d 1h
Limit type the app doesn't recognise yet   bodySmall onSurfaceVariant
```

Same bar, same states, same notch. The hint is the only difference; an unknown
bucket at the wall still hatches.

**Expected bucket missing.** Only two buckets are expected: `FIVE_HOUR` (the
hero) and `SEVEN_DAY` (first in This week). When either is absent from a
snapshot, its slot shows the placeholder bar at full alpha with "—" for the
number and the line "Not reported in the last reading." Never 0%, never an
empty fill. Per-model buckets come and go with the plan and get no
placeholder.

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
│ When requests start being rejected     │
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
- Vertical rhythm on Usage: age line → 24dp → hero → 32dp → group header →
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
| Hero number | `Text(buildAnnotatedString { digits in displayLarge; "%" in headlineMedium })` beside `Text("used", labelLarge)`, `alignByBaseline()` |
| Panels | `Surface(shape = shapes.large, color = …)` — not `Card`; no elevation, no border |
| Age line + actions | `Row` with `Text(weight(1f))`, `IconButton(Refresh)`, `IconButton(Settings)` |
| Top bars (Import, Settings) | `TopAppBar(title, navigationIcon = IconButton(ArrowBack))` |
| Settings rows | `ListItem(headlineContent, supportingContent, trailingContent = Switch)` with `Modifier.toggleable(role = Role.Switch)` |
| Threshold | `Slider(valueRange = 50f..99f, steps = 48)` inside `AnimatedVisibility` |
| Camera square | `AndroidView(PreviewView)` in a `Box(aspectRatio(1f)).clip(shapes.large)` with a `Canvas` overlay for the brackets |
| Paste field | `OutlinedTextField(minLines = 4, textStyle = bodyMedium.copy(fontFamily = Monospace), trailingIcon = IconButton(ContentPaste))` |

## 11. What not to do

- No app name, logo or wordmark in any screen's chrome. The launcher icon is
  an abstract gauge mark in `primary` on `surface` — a notched arc — and
  nothing that resembles any AI company's mark.
- No cream, terracotta, coral, orange or salmon anywhere, in either theme.
- No cards around data. A container means "something other than the data is
  talking".
- No dividers, eyebrow labels, all-caps labels, or middle-dot meta strings.
- No monospace outside the paste field.
- No entrance animations, no per-item fades. The fill animation is the one
  motion.
- Never invent a bucket. If the server sends four, show four; if it sends a
  kind the app has never seen, show it as it came.

## 12. Where this departs from the plan

Behaviour is the plan's; these are the places the design needs something the
plan's code does not have, or reverses a rendering choice. Each is small.

1. **`LinearProgressIndicator` → `Canvas` bar.** The notch and hatch are the
   colour-independent cues and cannot be expressed through the M3 indicator.
   Same inputs, same semantics; rendering only.
2. **`UsageScreen` needs `thresholdPercent: Double`.** The notch is drawn at
   the configured threshold. `MainActivity` reads `SettingsStore.flow` and
   passes it down; the approaching state is computed in the UI as
   `utilization >= threshold && !rejected`, matching `TriggerEvaluator`.
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

None of these change `UsageState`, `LimitBucket`, `interpretScan`,
`clampThreshold`, the formatters, or any test in the plan.
