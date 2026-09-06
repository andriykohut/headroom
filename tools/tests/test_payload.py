import pytest
from headroom_link.payload import (
    Tokens, Provider, encode, decode, PayloadError, SCHEME,
)

TOKENS = Tokens(access_token="at-123", refresh_token="rt-456", expires_at=1787262000)
PROVIDER = Provider(
    client_id="cid-789",
    token_endpoint="https://example.test/oauth/token",
    usage_endpoint="https://example.test/api/oauth/usage",
)


def test_round_trip_preserves_every_field():
    tokens, provider = decode(encode(TOKENS, PROVIDER))
    assert tokens == TOKENS
    assert provider == PROVIDER


def test_encoded_payload_carries_scheme_prefix():
    assert encode(TOKENS, PROVIDER).startswith(SCHEME)


def test_encoded_payload_does_not_leak_token_in_cleartext():
    # Compressed and base64url'd, so the raw token must not be greppable.
    assert "at-123" not in encode(TOKENS, PROVIDER)


def test_decode_rejects_foreign_payload():
    with pytest.raises(PayloadError, match="not a Headroom"):
        decode("otherapp1:abcdef")


def test_decode_rejects_corrupted_body():
    payload = encode(TOKENS, PROVIDER)
    corrupted = payload[:-4] + "AAAA"
    with pytest.raises(PayloadError):
        decode(corrupted)


def test_decode_rejects_missing_field():
    import base64, json, zlib
    body = json.dumps({"access_token": "a"}).encode()
    packed = base64.urlsafe_b64encode(zlib.compress(body)).decode().rstrip("=")
    with pytest.raises(PayloadError, match="missing"):
        decode(SCHEME + packed)


def test_expires_at_survives_as_int():
    tokens, _ = decode(encode(TOKENS, PROVIDER))
    assert isinstance(tokens.expires_at, int)


def test_golden_fixture_still_decodes():
    """Guards the wire contract shared with the Android app.

    If this fails, the format changed and the app can no longer read codes
    from this generator. Regenerate the fixture only alongside a matching
    app-side change.
    """
    from pathlib import Path
    golden = (Path(__file__).parent / "fixtures" / "payload_golden.txt").read_text().strip()
    tokens, provider = decode(golden)
    assert tokens == TOKENS
    assert provider == PROVIDER
