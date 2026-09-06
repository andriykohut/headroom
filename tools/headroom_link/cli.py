"""Entry point: read local credentials, print a QR code for the phone."""

import argparse
import shutil
import sys
import time

from .payload import encode
from .provider import ProviderNotFound, resolve_provider
from .qr import render
from .relay import relay_payload
from .resolvers import CredentialNotFound, resolve_tokens


def _parse(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="headroom-link",
        description="Print a QR code that links the Headroom Android app to this "
                    "machine's Claude Code credentials.",
        epilog="Nothing is written to disk. Do not share the output: it grants "
               "access to your account.",
    )
    parser.add_argument(
        "--text", action="store_true",
        help="print the raw payload instead of a QR code (for manual paste)",
    )
    parser.add_argument(
        "--relay", metavar="URL",
        help="point the phone at a headroom-relay instead of at the provider, "
             "so no credential leaves this machine",
    )
    parser.add_argument(
        "--relay-secret", metavar="TOKEN",
        help="the relay's shared secret (required with --relay)",
    )
    args = parser.parse_args(argv)
    if args.relay and not args.relay_secret:
        parser.error("--relay needs --relay-secret")
    return args


def main(argv: list[str] | None = None) -> int:
    args = _parse(argv)
    if args.relay:
        # Nothing to resolve: the phone is being pointed at a relay, which holds
        # the credential itself.
        tokens, provider = relay_payload(args.relay, args.relay_secret)
    else:
        try:
            tokens = resolve_tokens()
            provider = resolve_provider()
        except (CredentialNotFound, ProviderNotFound) as exc:
            print(f"headroom-link: {exc}", file=sys.stderr)
            return 1

    if tokens.expires_at <= time.time():
        print(
            "headroom-link: warning - this access token has already expired. "
            "The app will refresh it on first use; if that fails, run any Claude "
            "Code command to renew it and re-link.",
            file=sys.stderr,
        )

    payload = encode(tokens, provider)
    if args.text:
        print("Paste this into Headroom's import screen:\n")
        print(payload)
    else:
        drawing = render(payload)
        _warn_if_too_narrow(drawing)
        print(drawing)
        print("Scan this in Headroom to link this account. Do not share it.")
    return 0


def _terminal_width() -> int | None:
    """Width of the window this QR will be looked at in, when that is knowable.

    Under a pipe, a redirect, or a tool that runs the command for the user,
    there is no window to measure - and get_terminal_size() answers anyway,
    with an 80-column fallback that says nothing about the user's terminal.
    Treating that guess as fact produces a confident warning about a window
    nobody is looking at, so it returns None instead.
    """
    if not sys.stdout.isatty():
        return None
    return shutil.get_terminal_size().columns


def _warn_if_too_narrow(drawing: str) -> None:
    """A QR wider than the terminal wraps, and a wrapped QR cannot be scanned.

    Real payloads render 85 columns wide, so the common 80-column terminal
    silently produces something that looks like a QR and is not one. Spec §7:
    never fail silently.
    """
    needed = max((len(line) for line in drawing.splitlines()), default=0)
    columns = _terminal_width()
    if columns is None:
        print(
            f"headroom-link: this QR needs {needed} columns. Output is not "
            "going to a terminal, so its width cannot be checked - if the "
            "window showing this is narrower, the code wraps and will not "
            "scan. Widen it, or use --text and paste instead.",
            file=sys.stderr,
        )
        return
    if needed <= columns:
        return
    print(
        f"headroom-link: warning - this QR needs {needed} columns but the "
        f"terminal is {columns} wide, so it will wrap and will not scan. "
        "Widen the window, reduce the font size, or use --text and paste it.",
        file=sys.stderr,
    )


if __name__ == "__main__":
    raise SystemExit(main())
