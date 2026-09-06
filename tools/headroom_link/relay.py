"""A small always-on relay between the usage endpoint and the phone.

The phone holds no provider credential and never contacts the provider. This
process does: it owns its own credential, polls on a schedule, and serves the
most recent reading to the phone over one authenticated endpoint.

Two consequences follow, and they are the point of the design:

- **No shared refresh chain.** The credential this holds is minted by its own
  Claude Code login on this machine, so refreshing it cannot invalidate the one
  on a laptop. Sharing a rotating chain between two clients is what made the
  phone need re-linking about once a day.
- **The phone's view survives the laptop being off**, because this is not the
  laptop.

It answers with exactly the bytes the upstream endpoint returned, so the app's
parser is unchanged and this cannot quietly reinterpret anything.
"""

from __future__ import annotations

import hmac
import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from .payload import Provider, Tokens
from .provider import resolve_provider
from .resolvers import resolve_tokens

# The upstream limit is reported as roughly a couple of dozen requests a minute,
# with a penalty near 24 hours that also locks the user out of Claude Code and
# the web. One request every fifteen minutes is three orders of magnitude below
# that, and the phone reads a cache rather than adding to it.
DEFAULT_INTERVAL = 900
MIN_INTERVAL = 300

DEFAULT_BACKOFF = 6 * 3600
MAX_BACKOFF = 24 * 3600

# Usage cannot change while nobody is using Claude, so polling an idle account
# asks a question whose answer is already on file. When this machine shows no
# Claude Code activity for this long, the relay goes quiet - overnight and at
# weekends that is no requests at all. Generous, because the cost of being
# slightly late is a stale number and the cost of being wrong is a day-long
# lockout that also hits Claude Code and the web.
IDLE_AFTER = 4 * 3600


class RelayError(Exception):
    """Fatal to a poll cycle. Never carries a token value."""


@dataclass
class Snapshot:
    """The last thing upstream said, kept verbatim."""

    body: str
    fetched_at: float
    status: int = 200


@dataclass
class State:
    """Everything the relay must survive a restart with.

    The credential is stored here rather than read from Claude Code's store on
    every start: once this process refreshes, the copy in that store is stale,
    and reading it again would hand back a spent refresh token.
    """

    tokens: Tokens
    provider: Provider

    @classmethod
    def load(cls, path: Path) -> "State | None":
        if not path.is_file():
            return None
        try:
            data = json.loads(path.read_text())
        except (OSError, ValueError):
            return None
        try:
            return cls(
                tokens=Tokens(**data["tokens"]),
                provider=Provider(**data["provider"]),
            )
        except (KeyError, TypeError):
            return None

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"tokens": self.tokens._asdict(), "provider": self.provider._asdict()}
        # Written via a temporary file in the same directory so a crash mid-write
        # cannot leave a truncated credential behind.
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(payload))
        temporary.chmod(0o600)
        temporary.replace(path)


def last_activity(config_dir: Path | None = None) -> float | None:
    """When Claude Code last wrote a session on this machine, if it can be seen.

    Returns None when there is nothing to read - a machine where Claude Code has
    never run, or a layout this does not recognise. That is deliberately not the
    same as "idle": with no signal the relay polls on its schedule rather than
    inventing a reason to stay quiet.
    """
    base = config_dir or Path(os.environ.get("CLAUDE_CONFIG_DIR", Path.home() / ".claude"))
    projects = base / "projects"
    if not projects.is_dir():
        return None
    newest = None
    for path in projects.rglob("*.jsonl"):
        try:
            stamp = path.stat().st_mtime
        except OSError:
            continue
        if newest is None or stamp > newest:
            newest = stamp
    return newest


def bootstrap_state() -> State:
    """Take ownership of the credential this machine's Claude Code holds."""
    return State(tokens=resolve_tokens(), provider=resolve_provider())


@dataclass
class Relay:
    """Poll upstream, hold the answer, hand it to whoever knows the secret."""

    state: State
    state_path: Path
    secret: str
    interval: int = DEFAULT_INTERVAL
    now: Callable[[], float] = time.time

    idle_after: int = IDLE_AFTER
    activity: Callable[[], float | None] = staticmethod(last_activity)

    snapshot: Snapshot | None = None
    retry_after: float = 0.0
    last_error: str | None = None
    idle: bool = False
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def authorised(self, header: str | None) -> bool:
        """Constant-time, so a wrong token cannot be found one character at a time."""
        if not header or not header.startswith("Bearer "):
            return False
        return hmac.compare_digest(header[len("Bearer "):], self.secret)

    def should_poll(self) -> bool:
        """Whether asking upstream could tell us anything new."""
        if self.now() < self.retry_after:
            return False
        seen = self.activity()
        if seen is None:
            # No activity signal on this machine - poll on the schedule.
            return True
        return (self.now() - seen) < self.idle_after

    def poll(self) -> None:
        """One cycle. Records failures rather than raising: a relay that exits
        on a transient network error is worse than one showing an old reading."""
        if not self.should_poll():
            with self._lock:
                self.idle = self.now() >= self.retry_after
            return
        with self._lock:
            self.idle = False
        try:
            body = self._fetch()
        except RelayError as error:
            with self._lock:
                self.last_error = str(error)
            return
        with self._lock:
            self.snapshot = Snapshot(body=body, fetched_at=self.now())
            self.last_error = None

    def _fetch(self, allow_refresh: bool = True) -> str:
        request = urllib.request.Request(
            self.state.provider.usage_endpoint,
            headers={
                "Authorization": f"Bearer {self.state.tokens.access_token}",
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return response.read().decode()
        except urllib.error.HTTPError as error:
            if error.code == 401 and allow_refresh:
                self._refresh()
                return self._fetch(allow_refresh=False)
            if error.code == 429:
                wait = self._retry_after_seconds(error)
                self.retry_after = self.now() + wait
                raise RelayError(f"rate limited; not calling again for {wait}s") from None
            raise RelayError(f"upstream returned HTTP {error.code}") from None
        except urllib.error.URLError as error:
            raise RelayError(f"could not reach upstream: {type(error).__name__}") from None

    @staticmethod
    def _retry_after_seconds(error: urllib.error.HTTPError) -> int:
        header = (error.headers.get("Retry-After") or "").strip()
        try:
            value = int(header)
        except ValueError:
            return DEFAULT_BACKOFF
        return min(value, MAX_BACKOFF) if value > 0 else DEFAULT_BACKOFF

    def _refresh(self) -> None:
        """Renew this relay's own chain, and persist the rotated token at once.

        Refresh tokens rotate: losing the new one to a crash before it is written
        would strand the relay with a token the server has already retired.
        """
        data = urllib.parse.urlencode({
            "grant_type": "refresh_token",
            "refresh_token": self.state.tokens.refresh_token,
            "client_id": self.state.provider.client_id,
        }).encode()
        request = urllib.request.Request(self.state.provider.token_endpoint, data=data)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                body = json.loads(response.read().decode())
        except urllib.error.HTTPError as error:
            raise RelayError(
                f"refresh rejected (HTTP {error.code}); this relay needs re-linking"
            ) from None
        except (urllib.error.URLError, ValueError) as error:
            raise RelayError(f"refresh failed: {type(error).__name__}") from None

        access = body.get("access_token")
        if not access:
            raise RelayError("refresh response carried no access token")
        self.state.tokens = Tokens(
            access_token=access,
            refresh_token=body.get("refresh_token") or self.state.tokens.refresh_token,
            expires_at=int(self.now()) + int(body.get("expires_in", 3600)),
        )
        self.state.save(self.state_path)



class Handler(BaseHTTPRequestHandler):
    relay: Relay

    def do_GET(self) -> None:  # noqa: N802 - name fixed by BaseHTTPRequestHandler
        if self.path.startswith("/healthz"):
            self._respond(200, json.dumps(self._health()), "application/json")
            return
        if not self.path.startswith("/usage"):
            self._respond(404, '{"error":"not found"}', "application/json")
            return
        if not self.relay.authorised(self.headers.get("Authorization")):
            self._respond(401, '{"error":"unauthorised"}', "application/json")
            return

        with self.relay._lock:
            snapshot = self.relay.snapshot
            error = self.relay.last_error
        if snapshot is None:
            self._respond(
                503,
                json.dumps({"error": error or "no reading yet"}),
                "application/json",
            )
            return
        # Verbatim upstream bytes, plus the age so the app can say how old the
        # reading is rather than implying it is live.
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-Headroom-Age", str(int(self.relay.now() - snapshot.fetched_at)))
        body = snapshot.body.encode()
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _health(self) -> dict:
        with self.relay._lock:
            snapshot = self.relay.snapshot
            error = self.relay.last_error
        return {
            "ok": snapshot is not None,
            "age_seconds": int(self.relay.now() - snapshot.fetched_at) if snapshot else None,
            "last_error": error,
            "rate_limited_until": int(self.relay.retry_after) or None,
            "idle": self.relay.idle,
        }

    def _respond(self, status: int, body: str, content_type: str) -> None:
        payload = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format: str, *args) -> None:
        """Silence the default logger: it writes the request line, and a bearer
        token in a query string would land in the journal. Nothing here needs
        per-request logging."""
        return


def serve(relay: Relay, host: str, port: int) -> None:
    handler = type("BoundHandler", (Handler,), {"relay": relay})
    server = ThreadingHTTPServer((host, port), handler)

    def loop() -> None:
        while True:
            relay.poll()
            time.sleep(relay.interval)

    threading.Thread(target=loop, daemon=True).start()
    server.serve_forever()


# A relay token never expires, so the app must never try to refresh it. The
# app only refreshes on a 401, which the relay does not return - but a far
# future expiry makes the intent explicit rather than incidental.
NEVER = 4_102_444_800  # 2100-01-01


def relay_payload(relay_url: str, secret: str) -> tuple[Tokens, Provider]:
    """The QR that points a phone at a relay instead of at the provider.

    Deliberately the same six fields as a direct link, so the app needs no
    change: it sends `access_token` as a bearer token to `usage_endpoint` and
    parses whatever comes back. Here that is the relay's secret and the relay's
    URL. The two token fields are unused and say so.
    """
    base = relay_url.rstrip("/")
    return (
        Tokens(access_token=secret, refresh_token="unused-by-relay", expires_at=NEVER),
        Provider(
            client_id="relay",
            token_endpoint=f"{base}/usage",
            usage_endpoint=f"{base}/usage",
        ),
    )


def main(argv: list[str] | None = None) -> int:
    """Run the relay."""
    import argparse
    import os
    import secrets as secrets_module

    parser = argparse.ArgumentParser(
        prog="headroom-relay",
        description="Poll the usage endpoint on a schedule and serve the last "
                    "reading to a phone. Holds its own credential.",
        epilog="The shared secret comes from HEADROOM_RELAY_SECRET, or --secret-file.",
    )
    parser.add_argument("--host", default="127.0.0.1",
                        help="default 127.0.0.1; put a TLS terminator in front")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--interval", type=int, default=DEFAULT_INTERVAL,
                        help=f"seconds between polls (minimum {MIN_INTERVAL})")
    parser.add_argument("--state", type=Path,
                        default=Path.home() / ".local/state/headroom-relay/state.json")
    parser.add_argument("--secret-file", type=Path)
    parser.add_argument("--new-secret", action="store_true",
                        help="print a fresh secret and exit")
    args = parser.parse_args(argv)

    if args.new_secret:
        print(secrets_module.token_urlsafe(32))
        return 0

    if args.interval < MIN_INTERVAL:
        parser.error(
            f"--interval below {MIN_INTERVAL}s risks a rate limit that also locks "
            "you out of Claude Code and the web for about a day"
        )

    secret = os.environ.get("HEADROOM_RELAY_SECRET")
    if not secret and args.secret_file:
        secret = args.secret_file.read_text().strip()
    if not secret:
        parser.error(
            "no shared secret: set HEADROOM_RELAY_SECRET or pass --secret-file. "
            "Generate one with --new-secret."
        )

    state = State.load(args.state)
    if state is None:
        # First run: take over the credential this machine's Claude Code holds.
        # From here on the relay owns its own chain and never reads that store
        # again, so refreshing here cannot invalidate a laptop's login.
        state = bootstrap_state()
        state.save(args.state)
        print(f"took ownership of this machine's credential; stored in {args.state}")

    relay = Relay(state=state, state_path=args.state, secret=secret, interval=args.interval)
    print(f"polling every {args.interval}s, serving on http://{args.host}:{args.port}/usage")
    serve(relay, args.host, args.port)
    return 0
