"""Locations and key names for Claude Code's credential storage.

These are undocumented internals discovered by inspection, not a public
API. See docs/discovery-notes.md for when and how they were verified.

Deliberately holds no provider identifiers: no client_id, no endpoint
URLs, no hostnames. Spec section 3 commits the project to shipping none,
and this file is public. The provider resolver reads them at runtime from
the user's own Claude Code install.
"""

CREDENTIAL_FILE = ".claude/.credentials.json"

KEYCHAIN_SERVICE = "Claude Code-credentials"

# Unverified: the binary shows no sign that Claude Code writes to libsecret
# at all, so this mirrors the macOS service name as the likeliest attribute.
# Correct it from a Linux install rather than trusting it.
SECRET_TOOL_ATTRS: dict[str, str] = {"service": "Claude Code-credentials"}

# The token fields sit one level down, under a "claudeAiOauth" wrapper,
# rather than at the top of the JSON. The resolvers flatten nested objects
# before looking up these aliases, so the wrapper needs no name here.
TOKEN_KEY_ALIASES = ("accessToken", "access_token")
REFRESH_KEY_ALIASES = ("refreshToken", "refresh_token")

# Claude Code stores this in milliseconds (13 digits), while the payload
# spec requires epoch seconds - so the resolvers' millisecond heuristic is
# the normal path, not a defensive one.
EXPIRY_KEY_ALIASES = ("expiresAt", "expires_at")

PROVIDER_SOURCE_NOTES = (
    "client_id and endpoints are absent from the credential; they are scanned "
    "out of the Claude Code executable at runtime - see docs/discovery-notes.md"
)
