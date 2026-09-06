import json
import pytest
from headroom_link.provider import ProviderNotFound, resolve_provider

# A stand-in for the real thing. Its shape is what matters: the value is
# UUID-like, and a DESIGN_CLIENT_ID sits directly beside it.
FAKE_UUID = "11111111-2222-3333-4444-555555555555"
OTHER_UUID = "99999999-8888-7777-6666-555555555555"


def write_creds(tmp_path, payload):
    d = tmp_path / ".claude"
    d.mkdir(parents=True, exist_ok=True)
    (d / ".credentials.json").write_text(json.dumps(payload))
    return tmp_path


def test_reads_provider_fields_from_credentials_file(tmp_path):
    home = write_creds(tmp_path, {
        "clientId": "cid",
        "tokenEndpoint": "https://example.test/oauth/token",
        "usageEndpoint": "https://example.test/api/oauth/usage",
    })
    provider = resolve_provider(home=home, runner=lambda argv: None)
    assert provider.client_id == "cid"
    assert provider.token_endpoint.endswith("/oauth/token")


def test_falls_back_to_scanning_the_claude_binary(tmp_path):
    home = write_creds(tmp_path, {})
    scanned = "\n".join([
        "https://example.test/oauth/token",
        "https://example.test/api/oauth/usage",
        "client_id=cid-from-binary",
    ])
    provider = resolve_provider(home=home, runner=lambda argv: scanned)
    assert provider.client_id == "cid-from-binary"
    assert provider.usage_endpoint.endswith("/api/oauth/usage")


def test_raises_when_nothing_can_be_found(tmp_path):
    home = write_creds(tmp_path, {})
    with pytest.raises(ProviderNotFound, match="client_id"):
        resolve_provider(home=home, runner=lambda argv: None)


def test_error_explains_the_manual_override(tmp_path):
    home = write_creds(tmp_path, {})
    with pytest.raises(ProviderNotFound) as exc:
        resolve_provider(home=home, runner=lambda argv: None)
    assert "HEADROOM_CLIENT_ID" in str(exc.value)


def test_environment_variables_take_precedence(tmp_path, monkeypatch):
    home = write_creds(tmp_path, {"clientId": "from-file"})
    monkeypatch.setenv("HEADROOM_CLIENT_ID", "from-env")
    monkeypatch.setenv("HEADROOM_TOKEN_ENDPOINT", "https://env.test/token")
    monkeypatch.setenv("HEADROOM_USAGE_ENDPOINT", "https://env.test/usage")
    provider = resolve_provider(home=home, runner=lambda argv: None)
    assert provider.client_id == "from-env"


# The four tests below encode what the binary actually looks like, which is
# not what the plan assumed. See docs/discovery-notes.md.

def minified_config(client_id=FAKE_UUID, design_id=OTHER_UUID):
    """The shape the client_id really appears in: a minified object literal."""
    return (
        'MANUAL_REDIRECT_URL:"https://example.test/oauth/code/callback",'
        f'CLIENT_ID:"{client_id}",DESIGN_CLIENT_ID:"{design_id}",'
        'OAUTH_FILE_SUFFIX:".oauth"'
    )


def test_client_id_is_read_from_the_minified_config_literal(tmp_path):
    home = write_creds(tmp_path, {})
    scanned = "\n".join([
        minified_config(),
        "https://example.test/v1/oauth/token",
        "/api/oauth/usage",
        "https://example.test/api/oauth/roles",
    ])
    assert resolve_provider(home=home, runner=lambda argv: scanned).client_id == FAKE_UUID


def test_design_client_id_is_not_mistaken_for_the_client_id(tmp_path):
    home = write_creds(tmp_path, {})
    # DESIGN_CLIENT_ID comes first here, so a pattern that merely looks for
    # the substring "CLIENT_ID" picks the wrong one.
    scanned = "\n".join([
        f'DESIGN_CLIENT_ID:"{OTHER_UUID}",CLIENT_ID:"{FAKE_UUID}"',
        "https://example.test/v1/oauth/token",
        "https://example.test/api/oauth/usage",
    ])
    assert resolve_provider(home=home, runner=lambda argv: scanned).client_id == FAKE_UUID


def test_usage_endpoint_is_composed_when_only_the_path_is_present(tmp_path):
    home = write_creds(tmp_path, {})
    # The binary holds the path and a same-origin sibling URL, never the
    # full usage URL - so the origin has to come from the sibling.
    scanned = "\n".join([
        minified_config(),
        "https://example.test/v1/oauth/token",
        "https://api.example.test/api/oauth/roles",
        "/api/oauth/usage?at_wall=1&skip_spend=1",
        "/api/oauth/usage",
    ])
    provider = resolve_provider(home=home, runner=lambda argv: scanned)
    assert provider.usage_endpoint == "https://api.example.test/api/oauth/usage"


def test_anthropic_base_url_supplies_the_origin(tmp_path, monkeypatch):
    home = write_creds(tmp_path, {})
    monkeypatch.setenv("ANTHROPIC_BASE_URL", "https://self-hosted.test/")
    scanned = "\n".join([
        minified_config(),
        "https://example.test/v1/oauth/token",
        "https://api.example.test/api/oauth/roles",
        "/api/oauth/usage",
    ])
    provider = resolve_provider(home=home, runner=lambda argv: scanned)
    assert provider.usage_endpoint == "https://self-hosted.test/api/oauth/usage"
