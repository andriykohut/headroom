//! The relay: a small always-on box between your machine and your phone.
//!
//! It holds no credential and never talks to the provider. Your machine pushes
//! readings to it while you work; your phone reads them back. That is the whole
//! program.
//!
//! Two consequences, and they are the point:
//!
//! - **Nothing on this host is worth stealing.** The earlier design had the
//!   relay hold a refresh token and poll around the clock. This one stores
//!   percentages. A shared secret gates it, and losing that secret leaks how
//!   much of your quota you have used - not your account.
//! - **No shared refresh chain.** Nothing here refreshes anything, so nothing
//!   here can invalidate the login on your laptop. That was the failure that
//!   made the phone need re-linking about once a day.
//!
//! It serves back exactly the bytes it was given, so the app's parser is
//! unchanged and this cannot quietly reinterpret anything.
//!
//! **Two keys, not one.** The machine that pushes and the phone that reads hold
//! different secrets, because they need different powers: one writes and never
//! reads, the other reads and never writes. A phone that is lost or a QR code
//! that is photographed therefore leaks your usage figures but cannot forge
//! them, and either key can be rotated without touching the other.

use http_body_util::{BodyExt, Full, Limited};
use hyper::body::{Bytes, Incoming};
use hyper::service::service_fn;
use hyper::{Request, Response, StatusCode};
use hyper_util::rt::{TokioIo, TokioTimer};
use serde::{Deserialize, Serialize};
use std::io::Read;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::net::TcpListener;
use tokio::sync::Semaphore;

/// A usage reading is a few kilobytes. Anything approaching this is a mistake
/// or an attempt to fill the disk, and neither deserves the memory.
const MAX_BODY: usize = 256 * 1024;

/// How long a connection may take to send request headers.
///
/// This is the slow-client defence, and it covers idle keep-alive too: a
/// connection waiting to send its next request is one that has not sent
/// headers yet. Without it, opening a socket and saying nothing costs the
/// server a connection for as long as the client cares to hold it - which is
/// exactly what the previous server could not prevent.
pub const HEADER_TIMEOUT: Duration = Duration::from_secs(10);

/// How long the body of a push may take to arrive, once its headers have.
const BODY_TIMEOUT: Duration = Duration::from_secs(15);

/// A ceiling on any single connection, however well-behaved it looks.
const CONNECTION_LIFETIME: Duration = Duration::from_secs(120);

/// The header a machine stamps its reading with, and the only way the relay
/// can order two machines that cannot see each other.
pub const OBSERVED_AT_HEADER: &str = "X-Headroom-Observed-At";

/// How long a reading outranks one observed before it.
///
/// A machine that is working pushes every few seconds, so a held reading older
/// than this means its machine stopped - and a fresh reading from a second
/// machine is then worth more than a stale one, whatever the two clocks say
/// about each other. This is the bound on a wrong clock: without it, a machine
/// running fast would silence the other one for as long as it was wrong, which
/// is unbounded. The relay measures it on its own clock, so it needs no
/// agreement between the machines to be right.
const OUTRANKS_FOR: u64 = 120;

/// Concurrent connections. Two clients need two; the rest is headroom for
/// retries and overlap. Past this, new connections wait in the kernel's queue
/// rather than each costing memory.
pub const DEFAULT_MAX_CONNECTIONS: usize = 64;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Snapshot {
    /// Exactly what was pushed, kept verbatim.
    pub body: String,
    pub received_at: u64,
    /// When the machine that pushed it says it saw it. Absent in a file
    /// written by a relay from before ordering existed, which is why it
    /// defaults rather than failing the whole file to parse.
    #[serde(default)]
    pub observed_at: Option<u64>,
}

/// A reading arriving from a machine, and when that machine observed it.
pub struct Push {
    pub body: String,
    /// `None` when the pusher did not say: an older `headroom push`, or a
    /// proxy in front of the relay that dropped the header. Unorderable, and
    /// for that reason always accepted.
    pub observed_at: Option<u64>,
}

impl From<String> for Push {
    /// A body with nothing said about when it was observed.
    fn from(body: String) -> Self {
        Push { body, observed_at: None }
    }
}

impl From<&str> for Push {
    fn from(body: &str) -> Self {
        body.to_string().into()
    }
}

/// What a caller is asking to do. The two are gated by different secrets.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Access {
    /// Read the stored reading. This is what the phone holds.
    Read,
    /// Replace it. This is what the machine you code on holds.
    Write,
}

pub struct Relay {
    read_secret: String,
    write_secret: String,
    snapshot: Mutex<Option<Snapshot>>,
    state_path: PathBuf,
    now: fn() -> u64,
}

#[derive(Debug, PartialEq, Eq)]
pub struct Reply {
    pub status: u16,
    pub body: String,
    /// Seconds since the reading was pushed, so the app can say how old it is
    /// rather than implying it is live.
    pub age: Option<u64>,
}

impl Relay {
    pub fn new(read_secret: String, write_secret: String, state_path: PathBuf) -> Self {
        let snapshot = std::fs::read_to_string(&state_path)
            .ok()
            .and_then(|raw| serde_json::from_str(&raw).ok());
        Relay {
            read_secret,
            write_secret,
            snapshot: Mutex::new(snapshot),
            state_path,
            now: unix_time,
        }
    }

    /// Constant-time, so a wrong secret cannot be found one character at a time.
    ///
    /// Each access is checked against its own secret. Presenting the phone's
    /// key to a `POST` fails exactly as presenting a stranger's would - the
    /// point of holding two is that neither is a master key.
    fn authorised(&self, header: Option<&str>, access: Access) -> bool {
        let expected = match access {
            Access::Read => &self.read_secret,
            Access::Write => &self.write_secret,
        };
        let Some(offered) = header.and_then(|value| value.strip_prefix("Bearer ")) else {
            return false;
        };
        let (offered, expected) = (offered.as_bytes(), expected.as_bytes());
        // The length comparison leaks the length of the secret, which is not
        // the secret. Everything after it is length-independent.
        offered.len() == expected.len()
            && offered.iter().zip(expected).fold(0u8, |acc, (a, b)| acc | (a ^ b)) == 0
    }

    /// The whole protocol, with no socket in sight so it can be tested directly.
    pub fn handle(
        &self,
        method: &str,
        target: &str,
        authorization: Option<&str>,
        body: Option<Push>,
    ) -> Reply {
        let path = target.split('?').next().unwrap_or("");
        match (method, path) {
            // Unauthenticated on purpose, so an uptime check does not need the
            // secret. It reports that a reading exists and how old it is, and
            // nothing about what is in it.
            ("GET", "/healthz") => {
                let snapshot = lock(&self.snapshot);
                let age = snapshot.as_ref().map(|s| (self.now)().saturating_sub(s.received_at));
                Reply {
                    status: 200,
                    body: serde_json::json!({"ok": snapshot.is_some(), "age_seconds": age})
                        .to_string(),
                    age: None,
                }
            }
            ("GET", "/usage") if !self.authorised(authorization, Access::Read) => {
                Reply { status: 401, body: error("unauthorised"), age: None }
            }
            ("POST", "/usage") if !self.authorised(authorization, Access::Write) => {
                Reply { status: 401, body: error("unauthorised"), age: None }
            }
            ("POST", "/usage") => match body {
                Some(push) => {
                    if self.store(push) {
                        return Reply { status: 204, body: String::new(), age: None };
                    }
                    // Superseded, which is not the sender's error: it pushed a
                    // true reading and a newer one simply got here first.
                    // Saying so in a 2xx keeps it out of the status line's
                    // error path, and still tells anyone holding a curl what
                    // happened.
                    Reply {
                        status: 200,
                        body: serde_json::json!({"stored": false, "held": "a newer reading"})
                            .to_string(),
                        age: None,
                    }
                }
                None => Reply { status: 400, body: error("empty body"), age: None },
            },
            ("GET", "/usage") => match lock(&self.snapshot).as_ref() {
                Some(snapshot) => Reply {
                    status: 200,
                    body: snapshot.body.clone(),
                    age: Some((self.now)().saturating_sub(snapshot.received_at)),
                },
                // 503 rather than an empty 200: the app must be able to tell
                // "nothing has been pushed yet" from "you have used nothing".
                None => Reply { status: 503, body: error("no reading yet"), age: None },
            },
            ("POST", _) | ("GET", _) => Reply { status: 404, body: error("not found"), age: None },
            _ => Reply { status: 405, body: error("method not allowed"), age: None },
        }
    }

    /// Keep the arriving reading unless the one already held is newer, and
    /// report which happened.
    fn store(&self, push: Push) -> bool {
        let now = (self.now)();
        // Held across the decision and the write both: two machines can push
        // in the same instant, and deciding under one lock only to write under
        // another puts them straight back into the race this is settling. What
        // it costs is a small file write inside the lock, on a server whose
        // whole job is one reading.
        let mut held = lock(&self.snapshot);
        if let Some(current) = held.as_ref()
            && !supersedes(push.observed_at, current, now)
        {
            return false;
        }
        let snapshot =
            Snapshot { body: push.body, received_at: now, observed_at: push.observed_at };
        // Persisted so a restart does not blank the phone. It holds
        // percentages and reset times - no credential, nothing secret.
        if let Some(parent) = self.state_path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        if let Ok(encoded) = serde_json::to_string(&snapshot) {
            let temporary = self.state_path.with_extension("tmp");
            if std::fs::write(&temporary, encoded).is_ok() {
                let _ = std::fs::rename(&temporary, &self.state_path);
            }
        }
        *held = Some(snapshot);
        true
    }
}

/// Whether an arriving reading should replace the one held.
///
/// Two machines reporting one account cannot be ordered by arrival: a reading
/// observed earlier can be delivered later, behind a slow request. The pusher's
/// own clock breaks that tie - but only while the relay can see for itself that
/// what it holds is current. Past `OUTRANKS_FOR` the held reading is stale, and
/// a fresh one wins however the two machines' clocks compare.
fn supersedes(arriving: Option<u64>, held: &Snapshot, now: u64) -> bool {
    let (Some(arriving), Some(held_at)) = (arriving, held.observed_at) else {
        // Nothing to order by. Refusing here would turn an unknown into an
        // outage - an older pusher, or a stripped header, would stop reporting
        // for as long as anything else kept pushing.
        return true;
    };
    arriving >= held_at || now.saturating_sub(held.received_at) > OUTRANKS_FOR
}

/// Lock without `unwrap`.
///
/// A panic anywhere in a worker would otherwise poison this mutex and take
/// every *other* worker down with it on their next request - turning one bug
/// into a total outage. What it guards is a `String` and a timestamp, replaced
/// wholesale; a panic cannot leave that half-written, so recovering the value
/// is both safe and strictly better than propagating.
fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn error(message: &str) -> String {
    serde_json::json!({ "error": message }).to_string()
}

fn unix_time() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs()
}

/// Run the relay until a worker fails.
///
/// hyper on a two-thread tokio runtime, rather than a thread-per-connection
/// server, for one reason: every limit below has to exist somewhere, and the
/// obvious lighter choice offered no way to set any of them. A relay on a
/// public host must be able to hang up on a client that connects and then says
/// nothing.
pub fn serve(
    relay: Relay,
    host: &str,
    port: u16,
    max_connections: usize,
    header_timeout: Duration,
) -> Result<(), String> {
    let address: SocketAddr = format!("{host}:{port}")
        .parse()
        .map_err(|_| format!("{host}:{port} is not an address I can bind"))?;

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .map_err(|error| format!("could not start the runtime: {error}"))?;

    runtime.block_on(accept_loop(Arc::new(relay), address, max_connections.max(1), header_timeout))
}

async fn accept_loop(
    relay: Arc<Relay>,
    address: SocketAddr,
    max_connections: usize,
    header_timeout: Duration,
) -> Result<(), String> {
    let listener = TcpListener::bind(address)
        .await
        .map_err(|error| format!("could not listen on {address}: {error}"))?;
    // Bounded, so a flood costs waiting rather than memory. Acquiring before
    // accepting is what applies the back-pressure: past the ceiling, new
    // connections stay in the kernel's queue instead of becoming tasks.
    let capacity = Arc::new(Semaphore::new(max_connections));

    loop {
        let permit = Arc::clone(&capacity)
            .acquire_owned()
            .await
            .map_err(|_| "connection limiter closed".to_string())?;
        let (stream, _) = match listener.accept().await {
            Ok(accepted) => accepted,
            // One failed accept is not a reason to take the relay down; a
            // listener that is gone for good is.
            Err(error) if is_transient(&error) => continue,
            Err(error) => return Err(format!("accept failed: {error}")),
        };
        let relay = Arc::clone(&relay);
        tokio::spawn(async move {
            let _permit = permit;
            let service = service_fn(move |request| answer(Arc::clone(&relay), request));
            let connection = hyper::server::conn::http1::Builder::new()
                // Required for header_read_timeout to work at all: without a
                // timer hyper has no clock to measure against, and the timeout
                // silently does nothing.
                .timer(TokioTimer::new())
                // Covers idle keep-alive too: a connection waiting to send its
                // next request is one that has not sent headers yet.
                .header_read_timeout(header_timeout)
                .serve_connection(TokioIo::new(stream), service);
            // A ceiling on any single connection, however well-behaved it looks.
            let _ = tokio::time::timeout(CONNECTION_LIFETIME, connection).await;
        });
    }
}

fn is_transient(error: &std::io::Error) -> bool {
    use std::io::ErrorKind::*;
    matches!(error.kind(), ConnectionAborted | ConnectionReset | Interrupted | WouldBlock)
}

async fn answer(
    relay: Arc<Relay>,
    request: Request<Incoming>,
) -> Result<Response<Full<Bytes>>, std::convert::Infallible> {
    let method = request.method().as_str().to_string();
    let target = request.uri().path_and_query().map(|p| p.as_str().to_string()).unwrap_or_default();
    let authorization = request
        .headers()
        .get(hyper::header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .map(str::to_string);
    let request_observed_at = request
        .headers()
        .get(OBSERVED_AT_HEADER)
        .and_then(|value| value.to_str().ok())
        .map(str::to_string);

    let reply = if method == "POST" {
        // Limited caps what can be read regardless of what Content-Length
        // claimed; the timeout caps how long the sender may take to send it.
        let limited = Limited::new(request.into_body(), MAX_BODY);
        match tokio::time::timeout(BODY_TIMEOUT, limited.collect()).await {
            Ok(Ok(collected)) => {
                let bytes = collected.to_bytes();
                let observed_at = request_observed_at.and_then(|value| value.parse().ok());
                let body = String::from_utf8(bytes.to_vec())
                    .ok()
                    .filter(|text| !text.trim().is_empty())
                    .map(|body| Push { body, observed_at });
                relay.handle(&method, &target, authorization.as_deref(), body)
            }
            // Over the cap, malformed, or too slow. All three are the sender's
            // problem, and none may displace a good reading.
            Ok(Err(_)) => Reply { status: 413, body: error("reading too large"), age: None },
            Err(_) => Reply { status: 408, body: error("took too long to send"), age: None },
        }
    } else {
        relay.handle(&method, &target, authorization.as_deref(), None)
    };

    let mut response = Response::builder()
        .status(StatusCode::from_u16(reply.status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR))
        .header(hyper::header::CONTENT_TYPE, "application/json");
    if let Some(age) = reply.age {
        response = response.header("X-Headroom-Age", age.to_string());
    }
    // Nothing is logged, here or anywhere: a request line can carry a bearer
    // token in a query string, and there is nothing this needs a journal for.
    Ok(response
        .body(Full::new(Bytes::from(reply.body)))
        .unwrap_or_else(|_| Response::new(Full::new(Bytes::new()))))
}

/// Bytes from the kernel, formatted as a URL-safe token. No dependency, and no
/// question about whether the generator is seeded.
pub fn new_secret() -> Result<String, String> {
    let mut bytes = [0u8; 32];
    std::fs::File::open("/dev/urandom")
        .and_then(|mut file| file.read_exact(&mut bytes))
        .map_err(|error| format!("could not read /dev/urandom: {error}"))?;
    const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    Ok(bytes.iter().map(|byte| ALPHABET[(byte & 63) as usize] as char).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    const READ: &str = "read-key";
    const WRITE: &str = "write-key";

    fn relay() -> Relay {
        let path = std::env::temp_dir().join(format!(
            "headroom-relay-{}-{:?}.json",
            std::process::id(),
            std::thread::current().id()
        ));
        std::fs::remove_file(&path).ok();
        let mut relay = Relay::new(READ.into(), WRITE.into(), path);
        relay.now = || 1_000;
        relay
    }

    fn push(relay: &Relay, body: &str) -> Reply {
        relay.handle("POST", "/usage", Some(&format!("Bearer {WRITE}")), Some(body.into()))
    }

    /// A push from a machine that says when it saw the reading.
    fn push_observed(relay: &Relay, body: &str, observed_at: u64) -> Reply {
        let push = Push { body: body.into(), observed_at: Some(observed_at) };
        relay.handle("POST", "/usage", Some(&format!("Bearer {WRITE}")), Some(push))
    }

    fn held(relay: &Relay) -> String {
        relay.handle("GET", "/usage", Some(&format!("Bearer {READ}")), None).body
    }

    // --- two machines, one relay ---
    //
    // Both report the same account, so their readings are interchangeable -
    // except in age. Arrival order is not observation order: a slow request on
    // one machine outlives a fast one on the other, and the phone then shows a
    // percentage stepping backwards. The pusher's own clock breaks the tie.

    #[test]
    fn a_reading_observed_earlier_does_not_replace_the_one_held() {
        let relay = relay();
        push_observed(&relay, r#"{"limits":[{"percent":42}]}"#, 900);
        let late = push_observed(&relay, r#"{"limits":[{"percent":41}]}"#, 800);
        assert_eq!(late.status, 200, "a superseded push is not the sender's error");
        assert!(held(&relay).contains("42"), "the newer reading was overwritten");
    }

    #[test]
    fn a_reading_observed_later_replaces_the_one_held() {
        let relay = relay();
        push_observed(&relay, r#"{"limits":[{"percent":41}]}"#, 800);
        assert_eq!(push_observed(&relay, r#"{"limits":[{"percent":42}]}"#, 900).status, 204);
        assert!(held(&relay).contains("42"));
    }

    #[test]
    fn two_readings_observed_in_the_same_second_take_the_later_arrival() {
        // One machine pushes several times a second, and those cannot overtake
        // each other - the lock lets one child run at a time - so within a
        // second arrival order is the truth.
        let relay = relay();
        push_observed(&relay, r#"{"limits":[{"percent":41}]}"#, 900);
        assert_eq!(push_observed(&relay, r#"{"limits":[{"percent":42}]}"#, 900).status, 204);
        assert!(held(&relay).contains("42"));
    }

    #[test]
    fn a_push_that_carries_no_observed_time_is_still_stored() {
        // An older `headroom push`, or a proxy that dropped the header. An
        // ordering we cannot know must not become a refusal to report at all.
        let relay = relay();
        push_observed(&relay, r#"{"limits":[{"percent":42}]}"#, 900);
        assert_eq!(push(&relay, r#"{"limits":[{"percent":7}]}"#).status, 204);
        assert!(held(&relay).contains("7"));
    }

    #[test]
    fn a_held_reading_stops_outranking_once_its_machine_goes_quiet() {
        // The bound on a wrong clock. A machine whose clock runs ahead would
        // otherwise silence the other one for as long as it was wrong, which
        // is unbounded; here it costs at most OUTRANKS_FOR after it stops
        // pushing. The relay's own clock is what measures that, so it needs no
        // agreement between the two machines to be right.
        let mut relay = relay();
        push_observed(&relay, r#"{"limits":[{"percent":42}]}"#, 9_000);
        relay.now = || 1_000 + OUTRANKS_FOR + 1;
        assert_eq!(push_observed(&relay, r#"{"limits":[{"percent":41}]}"#, 800).status, 204);
        assert!(held(&relay).contains("41"), "a stale reading outranked a fresh one");
    }

    #[test]
    fn a_reading_stored_by_an_older_relay_still_loads() {
        // Upgrading the relay must not blank the phone: the state file on a
        // running deployment has no observed time in it.
        let path =
            std::env::temp_dir().join(format!("headroom-relay-old-{}.json", std::process::id()));
        std::fs::write(&path, r#"{"body":"{\"limits\":[]}","received_at":900}"#).unwrap();
        let relay = Relay::new(READ.into(), WRITE.into(), path.clone());
        assert_eq!(
            relay.handle("GET", "/usage", Some(&format!("Bearer {READ}")), None).status,
            200
        );
        std::fs::remove_file(&path).ok();
    }

    // --- authorisation ---

    #[test]
    fn the_right_secret_is_accepted() {
        assert_eq!(push(&relay(), "{}").status, 204);
    }

    #[test]
    fn anything_else_is_rejected() {
        let relay = relay();
        for header in [
            None,
            Some(""),
            Some("s3cret"),
            Some("Bearer"),
            Some("Bearer "),
            Some("Bearer wrong"),
            Some("Basic s3cret"),
            Some("bearer s3cret"),
        ] {
            let reply = relay.handle("GET", "/usage", header, None);
            assert_eq!(reply.status, 401, "accepted {header:?}");
        }
    }

    #[test]
    fn a_prefix_of_the_secret_is_rejected() {
        // Guards the comparison: a length-independent check would let a caller
        // find the secret one character at a time.
        assert_eq!(relay().handle("GET", "/usage", Some("Bearer read-ke"), None).status, 401);
    }

    // --- the two keys are not interchangeable ---
    //
    // This is the whole point of holding two. The phone carries the read key,
    // so a photographed QR code or a lost phone leaks what fraction of the
    // quota is gone - and cannot forge it.

    #[test]
    fn the_read_key_cannot_write() {
        let relay = relay();
        push(&relay, r#"{"limits":[{"percent":10}]}"#);
        let attempt = relay.handle(
            "POST",
            "/usage",
            Some(&format!("Bearer {READ}")),
            Some(r#"{"limits":[{"percent":99}]}"#.into()),
        );
        assert_eq!(attempt.status, 401);
        assert!(
            relay
                .handle("GET", "/usage", Some(&format!("Bearer {READ}")), None)
                .body
                .contains("10"),
            "the read key overwrote a reading",
        );
    }

    #[test]
    fn the_write_key_cannot_read() {
        // Less critical than the other direction, but least privilege runs
        // both ways: the pusher has no business reading back.
        let relay = relay();
        push(&relay, r#"{"limits":[{"percent":10}]}"#);
        let reply = relay.handle("GET", "/usage", Some(&format!("Bearer {WRITE}")), None);
        assert_eq!(reply.status, 401);
        assert!(!reply.body.contains("10"));
    }

    #[test]
    fn one_key_for_both_roles_still_works() {
        // The simple setup, and the one an existing install is already on.
        let path =
            std::env::temp_dir().join(format!("headroom-single-{}.json", std::process::id()));
        std::fs::remove_file(&path).ok();
        let relay = Relay::new("same".into(), "same".into(), path);
        assert_eq!(
            relay.handle("POST", "/usage", Some("Bearer same"), Some("{}".into())).status,
            204,
        );
        assert_eq!(relay.handle("GET", "/usage", Some("Bearer same"), None).status, 200);
    }

    // --- storing and serving ---

    #[test]
    fn a_reading_comes_back_exactly_as_it_was_pushed() {
        let relay = relay();
        let body = r#"{"limits":[{"kind":"session","percent":41.6}]}"#;
        push(&relay, body);
        let reply = relay.handle("GET", "/usage", Some(&format!("Bearer {READ}")), None);
        assert_eq!(reply.status, 200);
        assert_eq!(reply.body, body, "the relay must not reinterpret what it stores");
    }

    #[test]
    fn a_reading_reports_its_age() {
        let mut relay = relay();
        push(&relay, "{}");
        relay.now = || 1_180;
        assert_eq!(
            relay.handle("GET", "/usage", Some(&format!("Bearer {READ}")), None).age,
            Some(180)
        );
    }

    #[test]
    fn nothing_pushed_yet_is_not_an_empty_reading() {
        // The app must be able to tell this from "you have used nothing".
        let reply = relay().handle("GET", "/usage", Some(&format!("Bearer {READ}")), None);
        assert_eq!(reply.status, 503);
    }

    #[test]
    fn a_later_push_replaces_an_earlier_one() {
        let relay = relay();
        push(&relay, r#"{"limits":[{"percent":10}]}"#);
        push(&relay, r#"{"limits":[{"percent":20}]}"#);
        assert!(
            relay
                .handle("GET", "/usage", Some(&format!("Bearer {READ}")), None)
                .body
                .contains("20")
        );
    }

    #[test]
    fn an_empty_body_is_refused_rather_than_stored() {
        // Storing it would blank a good reading on the phone.
        let relay = relay();
        push(&relay, "{}");
        assert_eq!(
            relay.handle("POST", "/usage", Some(&format!("Bearer {WRITE}")), None).status,
            400
        );
        assert_eq!(
            relay.handle("GET", "/usage", Some(&format!("Bearer {READ}")), None).status,
            200
        );
    }

    #[test]
    fn a_reading_survives_a_restart() {
        let relay = relay();
        push(&relay, r#"{"limits":[{"percent":33}]}"#);
        let restarted = Relay::new(READ.into(), WRITE.into(), relay.state_path.clone());
        let reply = restarted.handle("GET", "/usage", Some(&format!("Bearer {READ}")), None);
        assert_eq!(reply.status, 200);
        assert!(reply.body.contains("33"));
    }

    // --- everything else ---

    #[test]
    fn health_needs_no_secret_and_leaks_no_reading() {
        let relay = relay();
        push(&relay, r#"{"limits":[{"percent":99}]}"#);
        let reply = relay.handle("GET", "/healthz", None, None);
        assert_eq!(reply.status, 200);
        assert!(reply.body.contains("\"ok\":true"));
        assert!(!reply.body.contains("99"), "health must not carry the reading");
    }

    #[test]
    fn health_reports_honestly_before_anything_is_pushed() {
        assert!(relay().handle("GET", "/healthz", None, None).body.contains("\"ok\":false"));
    }

    #[test]
    fn a_query_string_does_not_defeat_routing() {
        let relay = relay();
        push(&relay, "{}");
        assert_eq!(
            relay.handle("GET", "/usage?at_wall=1", Some(&format!("Bearer {READ}")), None).status,
            200
        );
    }

    #[test]
    fn unknown_paths_and_methods_are_refused() {
        let relay = relay();
        assert_eq!(relay.handle("GET", "/", Some(&format!("Bearer {READ}")), None).status, 404);
        assert_eq!(
            relay.handle("DELETE", "/usage", Some(&format!("Bearer {READ}")), None).status,
            405
        );
    }

    #[test]
    fn a_poisoned_lock_does_not_take_the_other_workers_down() {
        // One panicking request must cost that request, not the process. With
        // `.lock().unwrap()` every later reader panics too, all four workers
        // die in turn, and the relay goes silently deaf.
        let mutex = Mutex::new(41);
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let mut guard = lock(&mutex);
            *guard = 42;
            panic!("while holding the lock");
        }));
        assert!(mutex.is_poisoned(), "the test did not reproduce a poisoned lock");
        assert_eq!(*lock(&mutex), 42, "a later reader must still get the value");
    }

    #[test]
    fn a_generated_secret_is_long_and_url_safe() {
        let secret = new_secret().unwrap();
        assert_eq!(secret.len(), 32);
        assert!(secret.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'));
        assert_ne!(secret, new_secret().unwrap());
    }
}
