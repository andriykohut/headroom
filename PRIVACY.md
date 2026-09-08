# Privacy

Effective 2026-09-06. This describes what Headroom does with data, in full.
It is short because there is not much to describe.

## The one-sentence version

Everything stays on your phone except requests to a relay **you** run, and the
developer operates no server and receives nothing — from the app, the relay, or
the command-line tool.

There are three pieces, and it is worth being clear about which is which:

| Piece | Runs where | Holds |
| --- | --- | --- |
| The **app** | Your phone | The relay's address and its read-only key |
| The **relay** (`headroom serve`) | A machine you control | The last usage reading |
| The **CLI** (`headroom push`) | The machine you code on | Nothing, unless you pass `--log`; it reads and forwards |

## What the app stores, on the device only

| Data | Where | Why |
| --- | --- | --- |
| Your relay's address and its read-only key | Encrypted with a non-exportable key in the Android Keystore; ciphertext in the app's private storage | To read your usage from the relay |
| The last usage reading | App-private storage | So the screen shows something while offline, and so the next reading can be compared with the last |
| Which notifications have already fired | App-private storage | So none fires twice |
| Your notification settings and warning threshold | App-private storage | They are your settings |

All of it is deleted when you unlink the phone (Settings → Unlink this phone)
or uninstall the app.

**The phone holds no Claude credential.** It cannot read your account, act as
you, or renew anything. Its key is read-only, so it cannot even overwrite a
reading on your own relay. Someone who takes your unlocked phone learns what
fraction of your quota you have used. That is the whole of it.

## What leaves the device

Requests to one address: your relay, from the QR code you scanned. Nothing is
sent anywhere else.

Specifically, there is **no**:

- analytics or telemetry of any kind;
- crash reporting;
- advertising;
- third-party SDK that contacts its maker. The barcode scanner (zxing-cpp)
  runs entirely on the device; camera frames are decoded in memory and never
  stored or transmitted.

## Permissions, and what each is for

| Permission | Used for | And nothing else |
| --- | --- | --- |
| Camera | Reading the QR code on the Link screen | The camera is off everywhere else |
| Notifications | The four usage notifications you can enable | — |
| Alarms & reminders | Waking at the exact moment a window resets | — |
| Run at boot | Rescheduling that alarm after a reboot, since alarms do not survive one | — |
| Internet | Your relay | — |

## The relay you run

`headroom serve` keeps the most recent reading pushed to it and hands it back
to whoever presents its read key. Concretely, that is three percentages,
three reset times, and the display name of one model — around 500 bytes, held
in memory and mirrored to one file so a restart does not blank your phone.

Beside the reading it keeps two timestamps: when the reading arrived, and when
the machine that sent it says it was taken. The second is what lets two of your
machines push to one relay without the older reading of the two winning. It is
a clock value, not a record of what you did.

It holds **no credential**, makes **no outbound requests**, and contacts
Anthropic never. If it is compromised, what leaks is how much of your quota you
have used.

## The CLI on the machine you code on

`headroom push` runs as a Claude Code status line hook and does one thing: it
reads the usage figures **already present** in the payload Claude Code hands its
status line, and forwards them to your relay. No request is made to anyone but
your relay; the numbers arrived on a response you had already paid for.

It never reads, writes or touches Claude Code's credential store, and it never
contacts the provider. `scripts/check-distribution.sh` fails the build if that
stops being true.

`--log <path>` is the one thing it will write, and only if you ask for it by
naming a file. It appends a CSV row — timestamp, the two percentages and their
reset times — whenever a figure changes. The file stays on that machine and is
never sent anywhere, including to your relay. Without the flag, nothing is
written at all.

`headroom link` renders your relay's address and secret as a QR code in your
terminal. It writes nothing to disk and contacts no server.

## Changes and contact

This file lives in the repository, and its history is the record of changes.
Questions go to the repository's issue tracker.
