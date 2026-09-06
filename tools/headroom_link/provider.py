"""Find the client_id and endpoints that the app needs in order to refresh.

The published app ships no provider identifiers (spec §3), so they travel in
the payload and are discovered here, on the user's own machine, where they
already legitimately live.

The scan patterns below were derived from a real install rather than guessed;
docs/discovery-notes.md records when, against which version, and why each one
looks the way it does.
"""

import json
import os
import re
import shutil
from pathlib import Path

from .constants import CREDENTIAL_FILE
from .payload import Provider
from .resolvers import run_command

_CLIENT_ID_KEYS = ("client_id", "clientId")
_TOKEN_ENDPOINT_KEYS = ("token_endpoint", "tokenEndpoint")
_USAGE_ENDPOINT_KEYS = ("usage_endpoint", "usageEndpoint")

_UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

# Claude Code ships minified, so `client_id:` and `clientId:` in its code are
# almost always variable references - matching those yields a mangled local
# name. The one literal assignment looks like
#   MANUAL_REDIRECT_URL:"…/oauth/code/callback",CLIENT_ID:"<uuid>",DESIGN_CLIENT_ID:"<uuid>"
# so the UUID form is tried first. The lookbehind is what stops the adjacent
# DESIGN_CLIENT_ID from matching, since it ends in the same eight characters.
_CLIENT_ID_PATTERNS = (
    re.compile(rf"(?<![A-Z_])CLIENT_ID[=\"':\s]+({_UUID})"),
    re.compile(r"client_id[=\"':\s]+([A-Za-z0-9._~-]{8,})"),
)

_TOKEN_URL_PATTERN = re.compile(r"https://[\w.-]+/(?:v1/)?oauth/token\b")

# The usage endpoint is never a whole URL in the binary: the path is a literal
# and the origin is joined on at runtime. The full-URL pattern is kept first in
# case a future build inlines it; otherwise the origin comes from a sibling
# /api/oauth/ URL that is a literal.
_USAGE_PATH = "/api/oauth/usage"
_USAGE_URL_PATTERN = re.compile(r"https://[\w.-]+/api/oauth/usage\b")
_API_ORIGIN_PATTERN = re.compile(r"(https://[\w.-]+)/api/oauth/")


class ProviderNotFound(Exception):
    """Could not determine the client_id or endpoints."""


def _from_file(home: Path) -> dict:
    path = home / CREDENTIAL_FILE
    if not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text())
    except (OSError, ValueError):
        return {}
    flat: dict = {}

    def walk(obj):
        if isinstance(obj, dict):
            for key, value in obj.items():
                if isinstance(value, dict):
                    walk(value)
                else:
                    flat.setdefault(key, value)

    walk(data)
    return flat


def _first(mapping: dict, keys: tuple[str, ...]) -> str | None:
    for key in keys:
        value = mapping.get(key)
        if value:
            return str(value)
    return None


def _scan_claude_binary(runner) -> str:
    binary = shutil.which("claude")
    if not binary:
        return ""
    return runner(["strings", "-a", str(Path(binary).resolve())]) or ""


def _find_client_id(scanned: str) -> str | None:
    for pattern in _CLIENT_ID_PATTERNS:
        match = pattern.search(scanned)
        if match:
            return match.group(1)
    return None


def _find_usage_endpoint(scanned: str) -> str | None:
    match = _USAGE_URL_PATTERN.search(scanned)
    if match:
        return match.group(0)
    if _USAGE_PATH not in scanned:
        return None
    # Claude Code honours ANTHROPIC_BASE_URL, so a user pointed at a different
    # deployment gets the endpoint their own CLI would call.
    base = os.environ.get("ANTHROPIC_BASE_URL")
    if not base:
        origin = _API_ORIGIN_PATTERN.search(scanned)
        base = origin.group(1) if origin else None
    return base.rstrip("/") + _USAGE_PATH if base else None


def resolve_provider(home: Path | None = None, runner=run_command) -> Provider:
    home = home or Path.home()
    from_file = _from_file(home)

    client_id = os.environ.get("HEADROOM_CLIENT_ID") or _first(from_file, _CLIENT_ID_KEYS)
    token_endpoint = (
        os.environ.get("HEADROOM_TOKEN_ENDPOINT") or _first(from_file, _TOKEN_ENDPOINT_KEYS)
    )
    usage_endpoint = (
        os.environ.get("HEADROOM_USAGE_ENDPOINT") or _first(from_file, _USAGE_ENDPOINT_KEYS)
    )

    if not (client_id and token_endpoint and usage_endpoint):
        scanned = _scan_claude_binary(runner)
        if scanned:
            if not client_id:
                client_id = _find_client_id(scanned)
            if not token_endpoint:
                match = _TOKEN_URL_PATTERN.search(scanned)
                token_endpoint = match.group(0) if match else None
            if not usage_endpoint:
                usage_endpoint = _find_usage_endpoint(scanned)

    missing = [
        name for name, value in (
            ("client_id", client_id),
            ("token_endpoint", token_endpoint),
            ("usage_endpoint", usage_endpoint),
        ) if not value
    ]
    if missing:
        raise ProviderNotFound(
            f"could not determine {', '.join(missing)}. Set them explicitly with "
            "HEADROOM_CLIENT_ID, HEADROOM_TOKEN_ENDPOINT and HEADROOM_USAGE_ENDPOINT."
        )
    return Provider(
        client_id=client_id,
        token_endpoint=token_endpoint,
        usage_endpoint=usage_endpoint,
    )
