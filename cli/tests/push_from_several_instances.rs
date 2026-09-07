//! Several Claude Code sessions on one machine, all running the status line.
//!
//! They share a state directory, so they share a lock, and that lock decides
//! two things worth testing across real processes rather than in one:
//!
//! - Overlapping pushes collapse to a single request. Losing a race here costs
//!   nothing, because the readings are account-wide: whichever child wins is
//!   reporting the same numbers on everyone's behalf.
//! - A child killed mid-request cannot take the lock with it. The lock is
//!   advisory and the kernel drops it when its holder dies, so the file left
//!   behind is just a file - not a holder the next child has to wait out.

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

    let (request_line, body) =
        receiver.recv_timeout(Duration::from_secs(10)).expect("the relay was never called");
    assert!(request_line.starts_with("POST /usage "), "got {request_line:?}");
    assert!(body.contains("41.6"), "the reading did not survive: {body}");
}

/// Read one HTTP request off a socket and answer it, without a server crate.
fn read_request(mut stream: TcpStream) -> (String, String) {
    let mut reader = BufReader::new(stream.try_clone().unwrap());
    let mut request_line = String::new();
    reader.read_line(&mut request_line).unwrap();

    let mut length = 0usize;
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).unwrap() == 0 || line.trim().is_empty() {
            break;
        }
        if let Some(value) = line.to_ascii_lowercase().strip_prefix("content-length:") {
            length = value.trim().parse().unwrap_or(0);
        }
    }

    let mut body = vec![0u8; length];
    reader.read_exact(&mut body).unwrap();
    stream.write_all(b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n").unwrap();
    (request_line.trim().to_string(), String::from_utf8_lossy(&body).into_owned())
}
