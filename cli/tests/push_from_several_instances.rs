//! More than one thing pushing to one relay: several Claude Code sessions on a
//! machine, and several machines on an account.
//!
//! Sessions on one machine share a state directory, so they share a lock, and
//! that lock decides two things worth testing across real processes rather
//! than in one:
//!
//! - Overlapping pushes collapse to a single request. Losing a race here costs
//!   nothing, because the readings are account-wide: whichever child wins is
//!   reporting the same numbers on everyone's behalf.
//! - A child killed mid-request cannot take the lock with it. The lock is
//!   advisory and the kernel drops it when its holder dies, so the file left
//!   behind is just a file - not a holder the next child has to wait out.
//!
//! Machines share nothing at all, so ordering between them has to travel on
//! the wire: each push stamps the moment its machine observed the reading, and
//! the relay keeps the newer of the two.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const PAYLOAD: &str = r#"{"model":{"display_name":"Fable"},"rate_limits":{"five_hour":{"used_percentage":41.6,"resets_at":1788000000},"seven_day":{"used_percentage":12.0,"resets_at":1788400000}}}"#;

/// Sessions start within milliseconds of each other; this is three orders of
/// magnitude more slack than that, so a loaded CI machine cannot turn an
/// overlap into a sequence and quietly pass the test for the wrong reason.
const LONG_ENOUGH_TO_OVERLAP: Duration = Duration::from_millis(1500);

fn scratch(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("headroom-mi-{}-{name}", std::process::id()));
    std::fs::remove_dir_all(&path).ok();
    std::fs::create_dir_all(&path).unwrap();
    path
}

/// Pre-date the enrichment clock: these tests are about the lock, and a fetch
/// would reach for a real credential and a real endpoint.
fn skip_enrichment(state: &Path) {
    let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs();
    std::fs::write(state.join("push.json"), format!(r#"{{"enriched_at":{now}}}"#)).unwrap();
}

/// One session's status line: feed it the payload and wait for it to return.
/// The detached child it spawns outlives it, which is the point.
fn push(relay: &str, state: &Path) {
    let mut child = Command::new(env!("CARGO_BIN_EXE_headroom"))
        .args(["push", "--relay", relay, "--secret", "s3cret"])
        .env("HEADROOM_STATE_DIR", state)
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .expect("spawn headroom push");
    child.stdin.take().unwrap().write_all(PAYLOAD.as_bytes()).unwrap();
    child.wait().expect("the status line half returns immediately");
}

#[test]
fn sessions_pushing_at_the_same_moment_make_one_request_between_them() {
    // The relay accepts and then says nothing, so the child that wins the lock
    // is still holding it while the others try. Without a lock this is five
    // requests; with one it is a single request and four quiet exits.
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let connections = Arc::new(AtomicUsize::new(0));
    let counter = Arc::clone(&connections);
    std::thread::spawn(move || {
        let mut held = Vec::new();
        for stream in listener.incoming() {
            counter.fetch_add(1, Ordering::SeqCst);
            held.push(stream); // Kept open and unanswered, deliberately.
        }
    });

    let state = scratch("overlap");
    skip_enrichment(&state);
    let relay = format!("http://127.0.0.1:{port}");
    for _ in 0..5 {
        push(&relay, &state);
    }
    std::thread::sleep(LONG_ENOUGH_TO_OVERLAP);

    assert_eq!(
        connections.load(Ordering::SeqCst),
        1,
        "five sessions must cost one request, not five"
    );
}

#[test]
fn a_lock_left_behind_by_a_killed_child_does_not_silence_the_next_push() {
    // A child killed mid-request - a reboot, an OOM kill, a closed laptop -
    // cannot remove its own lock file. If the file alone counted as a holder,
    // every session on the machine would stop reporting until something aged
    // it out, which is a far worse failure than the duplicate request the lock
    // exists to prevent.
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let (sender, receiver) = mpsc::channel();
    std::thread::spawn(move || {
        if let Ok((stream, _)) = listener.accept() {
            let _ = sender.send(read_request(stream));
        }
    });

    let state = scratch("orphaned");
    skip_enrichment(&state);
    std::fs::write(state.join("push.lock"), b"").unwrap();

    push(&format!("http://127.0.0.1:{port}"), &state);

    let request =
        receiver.recv_timeout(Duration::from_secs(10)).expect("the relay was never called");
    assert!(request.line.starts_with("POST /usage "), "got {:?}", request.line);
    assert!(request.body.contains("41.6"), "the reading did not survive: {}", request.body);
}

#[test]
fn a_push_stamps_the_moment_the_machine_observed_the_reading() {
    // Two machines cannot see each other's state, so the only way the relay
    // can tell which of two readings is the newer one is for each to say when
    // it was taken. Arrival order does not answer that: a slow request on one
    // machine outlives a fast one on the other.
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let (sender, receiver) = mpsc::channel();
    std::thread::spawn(move || {
        if let Ok((stream, _)) = listener.accept() {
            let _ = sender.send(read_request(stream));
        }
    });

    let state = scratch("observed");
    skip_enrichment(&state);
    let before = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs();

    push(&format!("http://127.0.0.1:{port}"), &state);

    let request =
        receiver.recv_timeout(Duration::from_secs(10)).expect("the relay was never called");
    let stamp: u64 = request
        .header("X-Headroom-Observed-At")
        .expect("a push must say when it observed the reading")
        .parse()
        .expect("the stamp must be epoch seconds");
    let after = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs();
    assert!(
        (before..=after).contains(&stamp),
        "the stamp is {stamp}, which is outside the {before}..={after} the push happened in"
    );
}

/// One HTTP request, as much of it as these tests need.
struct Request {
    line: String,
    headers: Vec<(String, String)>,
    body: String,
}

impl Request {
    /// Header lookup is case-insensitive, as HTTP is.
    fn header(&self, name: &str) -> Option<&str> {
        let name = name.to_ascii_lowercase();
        self.headers.iter().find(|(key, _)| *key == name).map(|(_, value)| value.as_str())
    }
}

/// Read one HTTP request off a socket and answer it, without a server crate.
fn read_request(mut stream: TcpStream) -> Request {
    let mut reader = BufReader::new(stream.try_clone().unwrap());
    let mut line = String::new();
    reader.read_line(&mut line).unwrap();

    let mut headers = Vec::new();
    loop {
        let mut header = String::new();
        if reader.read_line(&mut header).unwrap() == 0 || header.trim().is_empty() {
            break;
        }
        if let Some((key, value)) = header.split_once(':') {
            headers.push((key.trim().to_ascii_lowercase(), value.trim().to_string()));
        }
    }
    let length: usize = headers
        .iter()
        .find(|(key, _)| key == "content-length")
        .and_then(|(_, value)| value.parse().ok())
        .unwrap_or(0);

    let mut body = vec![0u8; length];
    reader.read_exact(&mut body).unwrap();
    stream.write_all(b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n").unwrap();
    Request {
        line: line.trim().to_string(),
        headers,
        body: String::from_utf8_lossy(&body).into_owned(),
    }
}
