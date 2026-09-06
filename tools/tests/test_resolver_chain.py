import pytest
from headroom_link.payload import Tokens
from headroom_link.resolvers import (
    CredentialNotFound, ResolverError, default_resolvers, resolve_tokens,
)

TOKENS = Tokens("at", "rt", 1787262000)


class Fake:
    def __init__(self, name, tokens=None, error=None):
        self.name = name
        self._tokens = tokens
        self._error = error
        self.resolve_calls = 0

    def available(self):
        return self._tokens is not None or self._error is not None

    def resolve(self):
        self.resolve_calls += 1
        if self._error:
            raise self._error
        return self._tokens


def test_returns_tokens_from_first_available_source():
    first = Fake("first", TOKENS)
    second = Fake("second", Tokens("other", "other", 1))
    assert resolve_tokens([first, second]) == TOKENS
    assert second.resolve_calls == 0


def test_skips_unavailable_sources():
    assert resolve_tokens([Fake("empty"), Fake("second", TOKENS)]) == TOKENS


def test_raises_when_nothing_available_and_lists_every_source():
    with pytest.raises(CredentialNotFound) as exc:
        resolve_tokens([Fake("alpha"), Fake("beta")])
    assert exc.value.tried == ["alpha", "beta"]
    assert "alpha" in str(exc.value) and "beta" in str(exc.value)


def test_a_broken_source_does_not_stop_a_later_working_one():
    broken = Fake("broken", error=ResolverError("corrupt"))
    assert resolve_tokens([broken, Fake("good", TOKENS)]) == TOKENS


def test_all_sources_broken_reports_the_failures():
    with pytest.raises(CredentialNotFound) as exc:
        resolve_tokens([Fake("broken", error=ResolverError("corrupt"))])
    assert "corrupt" in str(exc.value)


def test_default_chain_matches_the_spec_order():
    assert [r.name for r in default_resolvers()] == [
        "credentials file", "macos keychain", "libsecret", "kwallet",
    ]
