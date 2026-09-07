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

use serde::{Deserialize, Serialize};
use std::io::Read;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};
use tiny_http::{Header, Request, Response, Server};

/// A usage reading is a few kilobytes. Anything approaching this is a mistake
/// or an attempt to fill the disk, and neither deserves the memory.
const MAX_BODY: usize = 256 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Snapshot {
    /// Exactly what was pushed, kept verbatim.
    pub body: String,
    pub received_at: u64,
}

pub struct Relay {
    secret: String,
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
    pub fn new(secret: String, state_path: PathBuf) -> Self {
        let snapshot = std::fs::read_to_string(&state_path)
            .ok()
            .and_then(|raw| serde_json::from_str(&raw).ok());
        Relay { secret, snapshot: Mutex::new(snapshot), state_path, now: unix_time }
    }

    /// Constant-time, so a wrong secret cannot be found one character at a time.
    fn authorised(&self, header: Option<&str>) -> bool {
        let Some(offered) = header.and_then(|value| value.strip_prefix("Bearer ")) else {
            return false;
        };
        let (offered, expected) = (offered.as_bytes(), self.secret.as_bytes());
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
        body: Option<String>,
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
            ("GET" | "POST", "/usage") if !self.authorised(authorization) => {
                Reply { status: 401, body: error("unauthorised"), age: None }
            }
            ("POST", "/usage") => match body {
                Some(body) => {
                    self.store(body);
                    Reply { status: 204, body: String::new(), age: None }
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

    fn store(&self, body: String) {
        let snapshot = Snapshot { body, received_at: (self.now)() };
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
        *lock(&self.snapshot) = Some(snapshot);
    }
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

pub fn serve(relay: Relay, host: &str, port: u16, workers: usize) -> Result<(), String> {
    let server =
        Server::http((host, port)).map_err(|error| format!("could not listen: {error}"))?;
    let server = Arc::new(server);
    let relay = Arc::new(relay);

    let mut threads = Vec::new();
    for _ in 0..workers.max(1) {
        let (server, relay) = (Arc::clone(&server), Arc::clone(&relay));
        threads.push(std::thread::spawn(move || -> Result<(), String> {
            loop {
                match server.recv() {
                    Ok(request) => answer(&relay, request),
                    // The listening socket is gone. Ending the loop quietly
                    // would look like an orderly shutdown, which it is not.
                    Err(error) => return Err(format!("accept failed: {error}")),
                }
            }
        }));
    }

    // Whether the workers died matters more than it looks. Exiting 0 after a
    // panic tells a supervisor configured to restart on failure that all is
    // well, so the relay stays dead - and nothing on the phone says so either,
    // because a relay that is not answering and a relay with nothing new to say
    // look identical from there. It just serves an ever-older reading.
    let mut fatal: Option<String> = None;
    for thread in threads {
        let outcome = match thread.join() {
            Ok(Ok(())) => continue,
            Ok(Err(error)) => error,
            Err(_) => "a worker thread panicked".to_string(),
        };
        fatal.get_or_insert(outcome);
    }
    match fatal {
        Some(reason) => Err(format!("{reason}; the relay is no longer serving")),
        None => Ok(()),
    }
}

fn answer(relay: &Relay, mut request: Request) {
    let method = request.method().as_str().to_string();
    let target = request.url().to_string();
    let authorization = request
        .headers()
        .iter()
        .find(|header| header.field.equiv("Authorization"))
        .map(|header| header.value.as_str().to_string());

    // Refuse an oversized body by its declared length before reading a byte of
    // it, and cap the read anyway in case the length was a lie.
    let too_large = request.body_length().is_some_and(|length| length > MAX_BODY);
    let body = if method == "POST" && !too_large {
        let mut buffer = String::new();
        request
            .as_reader()
            .take(MAX_BODY as u64)
            .read_to_string(&mut buffer)
            .ok()
            .filter(|_| !buffer.trim().is_empty())
            .map(|_| buffer)
    } else {
        None
    };

    let reply = if too_large {
        Reply { status: 413, body: error("reading too large"), age: None }
    } else {
        relay.handle(&method, &target, authorization.as_deref(), body)
    };

    let mut response = Response::from_string(reply.body)
        .with_status_code(reply.status)
        .with_header(header("Content-Type", "application/json"));
    if let Some(age) = reply.age {
        response = response.with_header(header("X-Headroom-Age", &age.to_string()));
    }
    // Nothing is logged, here or anywhere: a request line can carry a bearer
    // token in a query string, and there is nothing this needs a journal for.
    let _ = request.respond(response);
}

fn header(field: &str, value: &str) -> Header {
    Header::from_bytes(field.as_bytes(), value.as_bytes()).expect("static header is well-formed")
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

    fn relay() -> Relay {
        let path = std::env::temp_dir().join(format!(
            "headroom-relay-{}-{:?}.json",
            std::process::id(),
            std::thread::current().id()
        ));
        std::fs::remove_file(&path).ok();
        let mut relay = Relay::new("s3cret".into(), path);
        relay.now = || 1_000;
        relay
    }

    fn push(relay: &Relay, body: &str) -> Reply {
        relay.handle("POST", "/usage", Some("Bearer s3cret"), Some(body.into()))
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
        assert_eq!(relay().handle("GET", "/usage", Some("Bearer s3cre"), None).status, 401);
    }

    // --- storing and serving ---

    #[test]
    fn a_reading_comes_back_exactly_as_it_was_pushed() {
        let relay = relay();
        let body = r#"{"limits":[{"kind":"session","percent":41.6}]}"#;
        push(&relay, body);
        let reply = relay.handle("GET", "/usage", Some("Bearer s3cret"), None);
        assert_eq!(reply.status, 200);
        assert_eq!(reply.body, body, "the relay must not reinterpret what it stores");
    }

    #[test]
    fn a_reading_reports_its_age() {
        let mut relay = relay();
        push(&relay, "{}");
        relay.now = || 1_180;
        assert_eq!(relay.handle("GET", "/usage", Some("Bearer s3cret"), None).age, Some(180));
    }

    #[test]
    fn nothing_pushed_yet_is_not_an_empty_reading() {
        // The app must be able to tell this from "you have used nothing".
        let reply = relay().handle("GET", "/usage", Some("Bearer s3cret"), None);
        assert_eq!(reply.status, 503);
    }

    #[test]
    fn a_later_push_replaces_an_earlier_one() {
        let relay = relay();
        push(&relay, r#"{"limits":[{"percent":10}]}"#);
        push(&relay, r#"{"limits":[{"percent":20}]}"#);
        assert!(relay.handle("GET", "/usage", Some("Bearer s3cret"), None).body.contains("20"));
    }

    #[test]
    fn an_empty_body_is_refused_rather_than_stored() {
        // Storing it would blank a good reading on the phone.
        let relay = relay();
        push(&relay, "{}");
        assert_eq!(relay.handle("POST", "/usage", Some("Bearer s3cret"), None).status, 400);
        assert_eq!(relay.handle("GET", "/usage", Some("Bearer s3cret"), None).status, 200);
    }

    #[test]
    fn a_reading_survives_a_restart() {
        let relay = relay();
        push(&relay, r#"{"limits":[{"percent":33}]}"#);
        let restarted = Relay::new("s3cret".into(), relay.state_path.clone());
        let reply = restarted.handle("GET", "/usage", Some("Bearer s3cret"), None);
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
            relay.handle("GET", "/usage?at_wall=1", Some("Bearer s3cret"), None).status,
            200
        );
    }

    #[test]
    fn unknown_paths_and_methods_are_refused() {
        let relay = relay();
        assert_eq!(relay.handle("GET", "/", Some("Bearer s3cret"), None).status, 404);
        assert_eq!(relay.handle("DELETE", "/usage", Some("Bearer s3cret"), None).status, 405);
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
