"""Platform-specific credential resolvers.

Each resolver reports whether its source exists on this machine, and if so
returns the tokens found there. Errors name the locations and field names
tried; they never carry a token value.
"""

import json
import shutil
import subprocess
from pathlib import Path
from typing import Callable, Protocol

from .constants import (
    CREDENTIAL_FILE, EXPIRY_KEY_ALIASES, KEYCHAIN_SERVICE, REFRESH_KEY_ALIASES,
    SECRET_TOOL_ATTRS, TOKEN_KEY_ALIASES,
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


def run_command(argv: list[str]) -> str | None:
    """Run a lookup command, returning stdout, or None if it cannot succeed.

    A missing binary and a missing secret are the same outcome to callers:
    this source has nothing for us. Only a corrupt secret is an error.
    """
    if not shutil.which(argv[0]):
        return None
    try:
        done = subprocess.run(argv, capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return None
    if done.returncode != 0:
        return None
    out = done.stdout.strip()
    return out or None


class _CommandResolver:
    """Shared behaviour for resolvers that shell out to a secret store."""

    name = "command"
    argv: list[str] = []

    def __init__(self, runner: Callable[[list[str]], str | None] = run_command) -> None:
        self._runner = runner

    def _read(self) -> str | None:
        return self._runner(self.argv)

    def available(self) -> bool:
        return self._read() is not None

    def resolve(self) -> Tokens | None:
        raw = self._read()
        if raw is None:
            return None
        try:
            data = json.loads(raw)
        except ValueError as exc:
            raise ResolverError(
                f"secret from {self.name} could not be parsed: {type(exc).__name__}"
            ) from None
        return tokens_from_mapping(data)


class KeychainResolver(_CommandResolver):
    name = "macos keychain"

    def __init__(self, runner: Callable[[list[str]], str | None] = run_command) -> None:
        super().__init__(runner)
        self.argv = ["security", "find-generic-password", "-s", KEYCHAIN_SERVICE, "-w"]


class SecretToolResolver(_CommandResolver):
    name = "libsecret"

    def __init__(self, runner: Callable[[list[str]], str | None] = run_command) -> None:
        super().__init__(runner)
        argv = ["secret-tool", "lookup"]
        for key, value in SECRET_TOOL_ATTRS.items():
            argv += [key, value]
        self.argv = argv


class KWalletResolver(_CommandResolver):
    name = "kwallet"

    def __init__(self, runner: Callable[[list[str]], str | None] = run_command) -> None:
        super().__init__(runner)
        self.argv = [
            "kwallet-query", "-r", KEYCHAIN_SERVICE or "Claude Code", "kdewallet",
        ]


class CredentialNotFound(Exception):
    """No source yielded a credential. Names every source tried."""

    def __init__(self, tried: list[str], failures: list[str]) -> None:
        self.tried = tried
        detail = "; ".join(failures) if failures else "no source had a credential"
        super().__init__(
            f"could not find Claude Code credentials. Tried: {', '.join(tried)}. "
            f"{detail}. Is Claude Code installed and logged in on this machine?"
        )


def default_resolvers() -> list[Resolver]:
    """Spec §3 resolution order: portable file first, then OS keystores."""
    return [
        CredentialFileResolver(),
        KeychainResolver(),
        SecretToolResolver(),
        KWalletResolver(),
    ]


def resolve_tokens(resolvers: list[Resolver] | None = None) -> Tokens:
    chain = default_resolvers() if resolvers is None else resolvers
    tried: list[str] = []
    failures: list[str] = []
    for resolver in chain:
        tried.append(resolver.name)
        try:
            if not resolver.available():
                continue
            tokens = resolver.resolve()
        except ResolverError as exc:
            # A corrupt source must not mask a working one further down.
            failures.append(f"{resolver.name}: {exc}")
            continue
        if tokens is not None:
            return tokens
    raise CredentialNotFound(tried, failures)
