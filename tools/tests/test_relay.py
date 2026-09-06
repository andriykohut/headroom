import json

import pytest
from headroom_link.payload import Provider, Tokens
from headroom_link.relay import (
    DEFAULT_BACKOFF,
    MAX_BACKOFF,
    Relay,
    RelayError,
    Snapshot,
    State,
)

TOKENS = Tokens(access_token="at-1", refresh_token="rt-1", expires_at=2_000)
PROVIDER = Provider(
    client_id="cid",
    token_endpoint="https://example.test/oauth/token",
    usage_endpoint="https://example.test/api/oauth/usage",
)


def relay(tmp_path, **kwargs):
    return Relay(
        state=State(tokens=TOKENS, provider=PROVIDER),
        state_path=tmp_path / "state.json",
        secret="s3cret",
        now=kwargs.pop("now", lambda: 1_000.0),
        **kwargs,
    )


class FakeHTTPError(Exception):
    """Stands in for urllib's HTTPError, which needs a file object to construct."""

    def __init__(self, code, retry_after=None):
        self.code = code
        self.headers = {"Retry-After": retry_after} if retry_after else {}


# --- authorisation ---

def test_the_right_token_is_accepted(tmp_path):
    assert relay(tmp_path).authorised("Bearer s3cret")


@pytest.mark.parametrize(
    "header",
    [None, "", "s3cret", "Bearer", "Bearer ", "Bearer wrong", "Basic s3cret", "bearer s3cret"],
)
def test_anything_else_is_rejected(tmp_path, header):
    assert not relay(tmp_path).authorised(header)


def test_a_prefix_of_the_secret_is_rejected(tmp_path):
    # Guards the comparison: a length-independent check would let a caller find
    # the secret one character at a time.
    assert not relay(tmp_path).authorised("Bearer s3cre")


# --- serving what upstream said ---

def test_a_poll_stores_the_body_verbatim(tmp_path):
    r = relay(tmp_path)
    r._fetch = lambda: '{"limits":[{"kind":"session"}]}'
    r.poll()
    assert r.snapshot.body == '{"limits":[{"kind":"session"}]}'
    assert r.snapshot.fetched_at == 1_000.0


def test_a_failed_poll_keeps_the_previous_reading(tmp_path):
    # An old reading with its age shown beats an empty screen.
    r = relay(tmp_path)
    r.snapshot = Snapshot(body='{"limits":[]}', fetched_at=500.0)

    def boom():
        raise RelayError("upstream returned HTTP 503")

    r._fetch = boom
    r.poll()
    assert r.snapshot.fetched_at == 500.0
    assert "503" in r.last_error


def test_a_success_clears_the_previous_error(tmp_path):
    r = relay(tmp_path)
    r.last_error = "upstream returned HTTP 503"
    r._fetch = lambda: "{}"
    r.poll()
    assert r.last_error is None


# --- rate limiting ---

def test_a_rate_limited_relay_makes_no_request(tmp_path):
    calls = []
    r = relay(tmp_path)
    r.retry_after = 5_000.0
    r._fetch = lambda: calls.append(1) or "{}"
    r.poll()
    assert calls == [], "polled while holding off"


def test_the_hold_expires(tmp_path):
    r = relay(tmp_path, now=lambda: 9_000.0)
    r.retry_after = 5_000.0
    r._fetch = lambda: "{}"
    r.poll()
    assert r.snapshot is not None


@pytest.mark.parametrize(
    "retry_after,expected",
    [
        ("120", 120),
        (None, DEFAULT_BACKOFF),
        ("not a number", DEFAULT_BACKOFF),
        ("0", DEFAULT_BACKOFF),
        ("-5", DEFAULT_BACKOFF),
        (str(MAX_BACKOFF * 10), MAX_BACKOFF),
    ],
)
def test_retry_after_is_honoured_within_bounds(retry_after, expected):
    assert Relay._retry_after_seconds(FakeHTTPError(429, retry_after)) == expected


# --- state ---

def test_state_round_trips(tmp_path):
    path = tmp_path / "nested" / "state.json"
    State(tokens=TOKENS, provider=PROVIDER).save(path)
    loaded = State.load(path)
    assert loaded.tokens == TOKENS
    assert loaded.provider == PROVIDER


def test_saved_state_is_not_world_readable(tmp_path):
    # It holds a refresh token.
    path = tmp_path / "state.json"
    State(tokens=TOKENS, provider=PROVIDER).save(path)
    assert path.stat().st_mode & 0o077 == 0


def test_missing_or_corrupt_state_reads_as_absent(tmp_path):
    assert State.load(tmp_path / "nope.json") is None
    bad = tmp_path / "bad.json"
    bad.write_text("{not json")
    assert State.load(bad) is None
    partial = tmp_path / "partial.json"
    partial.write_text(json.dumps({"tokens": {"access_token": "a"}}))
    assert State.load(partial) is None


def test_a_rotated_refresh_token_is_persisted_before_it_is_used(tmp_path, monkeypatch):
    # Refresh tokens rotate. Losing the new one to a crash would strand the
    # relay holding a token the server has already retired.
    r = relay(tmp_path)
    body = json.dumps({
        "access_token": "at-2", "refresh_token": "rt-2", "expires_in": 3_600,
    }).encode()

    class Response:
        def read(self): return body
        def __enter__(self): return self
        def __exit__(self, *a): return False

    monkeypatch.setattr("urllib.request.urlopen", lambda *a, **k: Response())
    r._refresh()

    assert r.state.tokens.access_token == "at-2"
    assert r.state.tokens.refresh_token == "rt-2"
    assert r.state.tokens.expires_at == 1_000 + 3_600
    assert State.load(tmp_path / "state.json").tokens.refresh_token == "rt-2"


def test_a_refresh_without_a_new_refresh_token_keeps_the_old_one(tmp_path, monkeypatch):
    r = relay(tmp_path)
    body = json.dumps({"access_token": "at-2", "expires_in": 60}).encode()

    class Response:
        def read(self): return body
        def __enter__(self): return self
        def __exit__(self, *a): return False

    monkeypatch.setattr("urllib.request.urlopen", lambda *a, **k: Response())
    r._refresh()
    assert r.state.tokens.refresh_token == "rt-1"
