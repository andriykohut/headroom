import json
import pytest
from headroom_link.resolvers import (
    KeychainResolver, SecretToolResolver, KWalletResolver, ResolverError,
)

SECRET = json.dumps({
    "accessToken": "at", "refreshToken": "rt", "expiresAt": 1787262000,
})


def runner_returning(value):
    return lambda argv: value


def runner_recording(store, value):
    def run(argv):
        store.append(argv)
        return value
    return run


@pytest.mark.parametrize("cls", [KeychainResolver, SecretToolResolver, KWalletResolver])
def test_unavailable_when_command_missing(cls):
    assert cls(runner=runner_returning(None)).available() is False


@pytest.mark.parametrize("cls", [KeychainResolver, SecretToolResolver, KWalletResolver])
def test_resolves_json_secret(cls):
    tokens = cls(runner=runner_returning(SECRET)).resolve()
    assert tokens.access_token == "at"
    assert tokens.expires_at == 1787262000


@pytest.mark.parametrize("cls", [KeychainResolver, SecretToolResolver, KWalletResolver])
def test_returns_none_when_no_secret_stored(cls):
    assert cls(runner=runner_returning(None)).resolve() is None


@pytest.mark.parametrize("cls", [KeychainResolver, SecretToolResolver, KWalletResolver])
def test_non_json_secret_raises_resolver_error(cls):
    with pytest.raises(ResolverError, match="could not be parsed"):
        cls(runner=runner_returning("not-json")).resolve()


def test_keychain_queries_the_configured_service():
    from headroom_link.constants import KEYCHAIN_SERVICE
    calls = []
    KeychainResolver(runner=runner_recording(calls, SECRET)).resolve()
    assert calls and KEYCHAIN_SERVICE in calls[0]


def test_secret_tool_passes_configured_attributes():
    from headroom_link.constants import SECRET_TOOL_ATTRS
    calls = []
    SecretToolResolver(runner=runner_recording(calls, SECRET)).resolve()
    for key, value in SECRET_TOOL_ATTRS.items():
        assert key in calls[0] and value in calls[0]


def test_resolver_names_are_distinct_and_human_readable():
    names = {c(runner=runner_returning(None)).name
             for c in (KeychainResolver, SecretToolResolver, KWalletResolver)}
    assert len(names) == 3
    assert all(n and n.islower() for n in names)
