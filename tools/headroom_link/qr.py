"""Render a payload as a QR code for a phone camera to read off the screen."""

import io

import segno


def render(payload: str) -> str:
    """Return the payload as terminal-drawable QR text.

    Error correction stays at the default: a two-JWT payload already pushes
    QR capacity, and raising correction would push it past what a phone can
    read off a terminal.
    """
    code = segno.make(payload, micro=False)
    buffer = io.StringIO()
    code.terminal(out=buffer, compact=True)
    return buffer.getvalue()
