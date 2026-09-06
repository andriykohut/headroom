"""Payload codec shared with the Headroom Android app.

Wire format: "headroom1:" + base64url(zlib(json)), unpadded. The prefix lets
the app reject foreign QR codes; zlib's adler32 check catches corruption.
"""

import base64
import json
import zlib
from typing import NamedTuple

SCHEME = "headroom1:"

_TOKEN_FIELDS = ("access_token", "refresh_token", "expires_at")
_PROVIDER_FIELDS = ("client_id", "token_endpoint", "usage_endpoint")


class PayloadError(Exception):
    """Payload could not be decoded. Never carries a token value."""


class Tokens(NamedTuple):
    access_token: str
    refresh_token: str
    expires_at: int


class Provider(NamedTuple):
    client_id: str
    token_endpoint: str
    usage_endpoint: str


def encode(tokens: Tokens, provider: Provider) -> str:
    body = json.dumps(
        {**tokens._asdict(), **provider._asdict()},
        separators=(",", ":"),
        sort_keys=True,
    ).encode()
    packed = base64.urlsafe_b64encode(zlib.compress(body, 9)).decode()
    return SCHEME + packed.rstrip("=")


def decode(payload: str) -> tuple[Tokens, Provider]:
    if not payload.startswith(SCHEME):
        raise PayloadError("not a Headroom payload")
    packed = payload[len(SCHEME):]
    padding = "=" * (-len(packed) % 4)
    try:
        body = zlib.decompress(base64.urlsafe_b64decode(packed + padding))
        data = json.loads(body)
    except Exception as exc:
        raise PayloadError(f"payload is corrupted: {type(exc).__name__}") from None
    if not isinstance(data, dict):
        raise PayloadError("payload is not an object")
    missing = [f for f in _TOKEN_FIELDS + _PROVIDER_FIELDS if f not in data]
    if missing:
        raise PayloadError(f"payload is missing fields: {', '.join(missing)}")
    return (
        Tokens(
            access_token=str(data["access_token"]),
            refresh_token=str(data["refresh_token"]),
            expires_at=int(data["expires_at"]),
        ),
        Provider(**{f: str(data[f]) for f in _PROVIDER_FIELDS}),
    )
