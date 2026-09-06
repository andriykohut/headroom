import io
import random
import re
import string
import pytest
from headroom_link import cli
from headroom_link.payload import Provider, Tokens, decode
from headroom_link.resolvers import CredentialNotFound

TOKENS = Tokens("at-123", "rt-456", 1787262000)
PROVIDER = Provider(
    "cid", "https://example.test/oauth/token", "https://example.test/api/oauth/usage",
)


@pytest.fixture
def wired(monkeypatch):
    monkeypatch.setattr(cli, "resolve_tokens", lambda: TOKENS)
    monkeypatch.setattr(cli, "resolve_provider", lambda: PROVIDER)


def test_success_exits_zero(wired, capsys):
    assert cli.main([]) == 0


def test_prints_a_qr_code_by_default(wired, capsys):
    cli.main([])
    out = capsys.readouterr().out
    # segno's terminal output is block characters; assert we drew something wide.
    assert any(len(line) > 20 for line in out.splitlines())


def test_text_flag_prints_the_raw_payload_and_it_decodes(wired, capsys):
    cli.main(["--text"])
    out = capsys.readouterr().out.strip().splitlines()[-1]
    tokens, provider = decode(out)
    assert tokens == TOKENS and provider == PROVIDER


def test_qr_output_never_contains_the_raw_token(wired, capsys):
    cli.main([])
    assert "at-123" not in capsys.readouterr().out


def test_missing_credentials_exits_one_with_actionable_message(monkeypatch, capsys):
    def boom():
        raise CredentialNotFound(["credentials file"], [])
    monkeypatch.setattr(cli, "resolve_tokens", boom)
    monkeypatch.setattr(cli, "resolve_provider", lambda: PROVIDER)
    assert cli.main([]) == 1
    assert "credentials file" in capsys.readouterr().err


def test_unknown_flag_exits_two(wired):
    with pytest.raises(SystemExit) as exc:
        cli.main(["--nonsense"])
    assert exc.value.code == 2


def test_expiry_warning_shown_for_already_expired_token(monkeypatch, capsys):
    monkeypatch.setattr(cli, "resolve_tokens", lambda: Tokens("at", "rt", 1))
    monkeypatch.setattr(cli, "resolve_provider", lambda: PROVIDER)
    cli.main([])
    assert "expired" in capsys.readouterr().err.lower()


def terminal_of_width(columns):
    """Stands in for a real terminal; None means "not a terminal at all"."""
    return lambda: columns


# Real Claude Code tokens are 108 characters each and the client_id is a
# 36-character UUID. Toy values make a far smaller QR, so the width tests
# would pass against a symbol no real payload ever produces - and a repeated
# character is no better, since the payload is zlib-compressed before it is
# drawn. Only high-entropy values of the right length reproduce the geometry.
def _jwt_like(seed, length=108):
    rng = random.Random(seed)
    alphabet = string.ascii_letters + string.digits + "-_"
    return "".join(rng.choice(alphabet) for _ in range(length))


REALISTIC_TOKENS = Tokens(_jwt_like("access"), _jwt_like("refresh"), 4102444800)
REALISTIC_PROVIDER = Provider(
    "c" * 36,
    "https://example.test/v1/oauth/token",
    "https://example.test/api/oauth/usage",
)


@pytest.fixture
def wired_unexpired(monkeypatch):
    """As `wired`, but sized like the real thing and not expired.

    The shared TOKENS expiry is in the past, which puts an expiry warning on
    stderr - fine for the tests above, fatal for asserting stderr is empty.
    """
    monkeypatch.setattr(cli, "resolve_tokens", lambda: REALISTIC_TOKENS)
    monkeypatch.setattr(cli, "resolve_provider", lambda: REALISTIC_PROVIDER)


def test_warns_when_the_terminal_is_too_narrow_to_draw_the_qr(wired_unexpired, monkeypatch, capsys):
    """A wrapped QR is unscannable, and looks fine until you try it.

    Real payloads need 85 columns, so the common 80-column terminal fails.
    Spec §7: never fail silently.
    """
    monkeypatch.setattr(cli, "_terminal_width", terminal_of_width(80))
    cli.main([])
    err = capsys.readouterr().err
    assert "80" in err and "--text" in err


def test_no_width_warning_when_the_terminal_is_wide_enough(wired_unexpired, monkeypatch, capsys):
    monkeypatch.setattr(cli, "_terminal_width", terminal_of_width(200))
    cli.main([])
    assert capsys.readouterr().err == ""


def test_text_output_is_not_subject_to_the_width_warning(wired_unexpired, monkeypatch, capsys):
    monkeypatch.setattr(cli, "_terminal_width", terminal_of_width(40))
    cli.main(["--text"])
    assert capsys.readouterr().err == ""


def test_states_the_required_width_when_it_cannot_be_measured(wired_unexpired, monkeypatch, capsys):
    """Piped or run by a tool: there is no window, so claim nothing about one.

    get_terminal_size() would answer 80 regardless, which reads as a
    confident warning about a terminal nobody is looking at.
    """
    monkeypatch.setattr(cli, "_terminal_width", terminal_of_width(None))
    cli.main([])
    err = capsys.readouterr().err
    # The number is whatever this payload draws; the point is that the
    # requirement is stated without asserting anything about a window.
    assert re.search(r"needs \d+ columns", err)
    assert "not going to a terminal" in err
    assert "warning" not in err


def test_width_is_unknown_when_stdout_is_not_a_terminal(monkeypatch):
    monkeypatch.setattr(cli.sys, "stdout", io.StringIO())
    assert cli._terminal_width() is None
