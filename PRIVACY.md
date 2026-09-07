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
| The **app** | Your phone | The relay's address and shared secret |
| The **relay** (`headroom serve`) | A machine you control | The last usage reading |
| The **CLI** (`headroom push`) | The machine you code on | Nothing; it reads and forwards |

## What the app stores, on the device only

| Data | Where | Why |
| --- | --- | --- |
| Your relay's address and its shared secret | Encrypted with a non-exportable key in the Android Keystore; ciphertext in the app's private storage | To read your usage from the relay |
| The last usage reading | App-private storage | So the screen shows something while offline, and so the next reading can be compared with the last |
| Which notifications have already fired | App-private storage | So none fires twice |
| Your notification settings and warning threshold | App-private storage | They are your settings |

All of it is deleted when you unlink the phone (Settings → Unlink this phone)
or uninstall the app.

**The phone holds no Claude credential.** It cannot read your account, act as
you, or renew anything. Someone who takes your unlocked phone learns what
fraction of your quota you have used and can write false readings to your
relay. That is the whole of it.

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
to whoever presents the shared secret. Concretely, that is three percentages,
three reset times, and the display name of one model — around 500 bytes, held
in memory and mirrored to one file so a restart does not blank your phone.

It holds **no credential**, makes **no outbound requests**, and contacts
Anthropic never. If it is compromised, what leaks is how much of your quota you
have used.

## The CLI on the machine you code on

`headroom push` runs as a Claude Code status line hook. It does two things:

- Reads the usage figures **already present** in the payload Claude Code hands
  its status line, and forwards them to your relay. No request is made; the
  numbers arrived on a response you had already paid for.
- At most once every five minutes, and only when the status line runs — that
  is, only while you are working — reads the access token Claude Code stores on
  that machine and fetches the fuller picture, which adds the per-model weekly
  windows.

It **never writes** to Claude Code's credential store and never refreshes a
token, so it cannot invalidate your login. The token it reads goes to exactly
one place: the endpoint Claude Code itself calls.

The full response to that fetch carries about twenty keys, including product
codenames and a `spend` object. Only the `limits` array is forwarded; the rest
is discarded on the machine it came from and never reaches your relay or your
phone. `scripts/check-distribution.sh` fails the build if that stops being
true.

`headroom link` renders your relay's address and secret as a QR code in your
terminal. It writes nothing to disk and contacts no server.

## Your account with the provider

The enrichment fetch calls an endpoint that is not publicly documented. The
provider's terms may not permit that; the README says so plainly, along with
why it is a narrower question than it was. That is between you and them, and
this document does not change it.

## Changes and contact

This file lives in the repository, and its history is the record of changes.
Questions go to the repository's issue tracker.
