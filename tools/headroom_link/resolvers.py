"""Platform-specific credential resolvers.

Each resolver reports whether its source exists on this machine, and if so
returns the tokens found there. Errors name the locations and field names
tried; they never carry a token value.
"""

import json
from pathlib import Path
from typing import Protocol

from .constants import (
    CREDENTIAL_FILE, EXPIRY_KEY_ALIASES, REFRESH_KEY_ALIASES, TOKEN_KEY_ALIASES,
)
from .payload import Tokens

# Expiries above this are milliseconds, not seconds: the value is year-2286
# in seconds, so anything larger is unambiguously a millisecond timestamp.
_MILLIS_THRESHOLD = 10_000_000_000


class ResolverError(Exception):
    """A source existed but could not yield usable tokens."""


class Resolver(Protocol):
    name: str

    def available(self) -> bool: ...

    def resolve(self) -> Tokens | None: ...


def _flatten(obj: object, out: dict) -> dict:
    """Collect leaf keys from nested dicts, so a wrapper object is transparent."""
    if isinstance(obj, dict):
        for key, value in obj.items():
            if isinstance(value, dict):
                _flatten(value, out)
            else:
                out.setdefault(key, value)
    return out


def pick(data: dict, aliases: tuple[str, ...], label: str) -> object:
    for alias in aliases:
        if alias in data and data[alias] not in (None, ""):
            return data[alias]
    raise ResolverError(
        f"no {label} found; tried key names: {', '.join(aliases) or '(none configured)'}"
    )


def normalise_expiry(value: object) -> int:
    seconds = int(value)
    return seconds // 1000 if seconds > _MILLIS_THRESHOLD else seconds


def tokens_from_mapping(data: dict) -> Tokens:
    flat = _flatten(data, {})
    return Tokens(
        access_token=str(pick(flat, TOKEN_KEY_ALIASES, "access token")),
        refresh_token=str(pick(flat, REFRESH_KEY_ALIASES, "refresh token")),
        expires_at=normalise_expiry(pick(flat, EXPIRY_KEY_ALIASES, "expiry")),
    )


class CredentialFileResolver:
    name = "credentials file"

    def __init__(self, home: Path | None = None) -> None:
        self._path = (home or Path.home()) / CREDENTIAL_FILE

    @property
    def location(self) -> str:
        return str(self._path)

    def available(self) -> bool:
        return self._path.is_file()

    def resolve(self) -> Tokens | None:
        if not self.available():
            return None
        try:
            data = json.loads(self._path.read_text())
        except (OSError, ValueError) as exc:
            raise ResolverError(
                f"{self._path} could not be parsed: {type(exc).__name__}"
            ) from None
        return tokens_from_mapping(data)
