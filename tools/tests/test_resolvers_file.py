import json
import pytest
from headroom_link.resolvers import CredentialFileResolver, ResolverError


def write_creds(tmp_path, payload):
    d = tmp_path / ".claude"
    d.mkdir(parents=True, exist_ok=True)
    (d / ".credentials.json").write_text(json.dumps(payload))
    return tmp_path


def test_unavailable_when_file_absent(tmp_path):
    assert CredentialFileResolver(tmp_path).available() is False


def test_available_when_file_present(tmp_path):
    home = write_creds(tmp_path, {})
    assert CredentialFileResolver(home).available() is True


def test_resolves_camel_case_keys(tmp_path):
    home = write_creds(tmp_path, {
        "accessToken": "at", "refreshToken": "rt", "expiresAt": 1787262000,
    })
    tokens = CredentialFileResolver(home).resolve()
    assert (tokens.access_token, tokens.refresh_token) == ("at", "rt")
    assert tokens.expires_at == 1787262000


def test_resolves_snake_case_keys(tmp_path):
    home = write_creds(tmp_path, {
        "access_token": "at", "refresh_token": "rt", "expires_at": 1787262000,
    })
    assert CredentialFileResolver(home).resolve().access_token == "at"


def test_resolves_keys_nested_under_a_wrapper(tmp_path):
    home = write_creds(tmp_path, {
        "claudeAiOauth": {
            "accessToken": "at", "refreshToken": "rt", "expiresAt": 1787262000,
        }
    })
    assert CredentialFileResolver(home).resolve().access_token == "at"


def test_expiry_in_milliseconds_is_normalised_to_seconds(tmp_path):
    home = write_creds(tmp_path, {
        "accessToken": "at", "refreshToken": "rt", "expiresAt": 1787262000000,
    })
    assert CredentialFileResolver(home).resolve().expires_at == 1787262000


def test_missing_field_error_names_aliases_but_never_values(tmp_path):
    home = write_creds(tmp_path, {"accessToken": "super-secret-value"})
    with pytest.raises(ResolverError) as exc:
        CredentialFileResolver(home).resolve()
    assert "refresh" in str(exc.value).lower()
    assert "super-secret-value" not in str(exc.value)


def test_malformed_json_raises_resolver_error(tmp_path):
    d = tmp_path / ".claude"
    d.mkdir(parents=True)
    (d / ".credentials.json").write_text("{not json")
    with pytest.raises(ResolverError, match="could not be parsed"):
        CredentialFileResolver(tmp_path).resolve()
