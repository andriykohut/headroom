# Privacy

Effective 2026-09-06. This describes what Headroom does with data, in full.
It is short because there is not much to describe.

## The one-sentence version

Everything stays on your phone, except the requests the app makes — under your
own account — to the two endpoints carried in the QR code you scanned. Nobody
else receives anything, including the developer.

## What the app stores, on the device only

| Data | Where | Why |
| --- | --- | --- |
| Your OAuth access and refresh tokens, and the endpoints and client ID that came with them | Encrypted with a non-exportable key in the Android Keystore; ciphertext in the app's private storage | To read usage and renew the token |
| The last usage reading | App-private storage | So the screen shows something while offline, and so the next poll can be compared with the last |
| Which notifications have already fired | App-private storage | So none fires twice |
| Your notification settings and warning threshold | App-private storage | They are your settings |
| A "do not call before" timestamp | App-private storage | Honoured when the server asks for fewer requests |

All of it is deleted when you unlink the phone (Settings → Unlink this phone)
or uninstall the app.

## What leaves the device

Requests to exactly two URLs — the usage endpoint and the token endpoint —
both of which arrived inside the QR code you scanned. They are the provider's
own servers, reached over TLS, using your own credential. The provider sees
these as ordinary activity on your account. Nothing is sent anywhere else.

Specifically, there is **no**:

- analytics or telemetry of any kind;
- crash reporting;
- advertising;
- third-party SDK that contacts its maker. The barcode scanner (zxing-cpp)
  runs entirely on the device; camera frames are decoded in memory and never
  stored or transmitted.

The developer operates no server and receives nothing from the app.

## Permissions, and what each is for

| Permission | Used for | And nothing else |
| --- | --- | --- |
| Camera | Reading the QR code on the Link screen | The camera is off everywhere else |
| Notifications | The four usage notifications you can enable | — |
| Alarms & reminders | Waking at the exact moment a window resets | — |
| Run at boot | Rescheduling that alarm after a reboot, since alarms do not survive one | — |
| Internet | The two endpoints above | — |

## The link generator on your computer

`tools/headroom-link` reads the credential that Claude Code already stores on
your machine, in the Keychain or its credentials file, and renders it as a QR
code in your terminal. It writes nothing to disk and contacts no server. The
QR code contains your credential: anyone who scans it can act as you until it
expires, so treat it like a password.

## Your account with the provider

Headroom uses your subscription credential to poll an endpoint that is not
publicly documented. The provider's terms may not permit that; the README
says so plainly. That is between you and them, and this document does not
change it.

## Changes and contact

This file lives in the repository, and its history is the record of changes.
Questions go to the repository's issue tracker.
