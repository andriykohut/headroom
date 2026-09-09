//! `push` runs on every message Claude Code sends. If it can be slow, the
//! prompt can be slow, so the guarantee is tested rather than asserted in a
//! comment.
//!
//! The measurement matters as much as the bound. Claude Code runs the status
//! line command and reads its stdout until end-of-file, so that is what these
//! tests do: `wait_with_output` returns only once the pipe closes *and* the
//! process exits. A detached child that inherited stdout would keep that pipe
//! open and hang this test for as long as it ran - which is exactly the bug
//! being guarded against, and one that is invisible if you only time the exit.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::mpsc;
use std::time::{Duration, Instant};

const PAYLOAD: &str = r#"{"model":{"display_name":"Fable"},"rate_limits":{"five_hour":{"used_percentage":41.6,"resets_at":1788000000},"seven_day":{"used_percentage":12.0,"resets_at":1788400000}}}"#;

/// Generous enough to survive a loaded CI machine, and two orders of magnitude
/// under the 10-second HTTP timeout a blocking implementation would wait out.
const MUST_RETURN_WITHIN: Duration = Duration::from_millis(1500);

fn scratch(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!("headroom-it-{}-{name}", std::process::id()));
    std::fs::remove_dir_all(&path).ok();
    std::fs::create_dir_all(&path).unwrap();
    path
}

fn run_push(relay: &str, state: &Path) -> (Duration, String) {
    let started = Instant::now();
    let mut child = Command::new(env!("CARGO_BIN_EXE_headroom"))
        .args(["push", "--relay", relay, "--secret", "s3cret"])
        .env("HEADROOM_STATE_DIR", state)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .expect("spawn headroom push");
    child.stdin.take().unwrap().write_all(PAYLOAD.as_bytes()).unwrap();
    let output = child.wait_with_output().expect("read stdout to EOF");
    (started.elapsed(), String::from_utf8(output.stdout).unwrap())
}

#[test]
fn a_relay_that_never_answers_does_not_delay_the_prompt() {
    // Accepts the connection and then says nothing at all - the worst case for
    // a client, and the one a plain timeout does not save you from.
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    std::thread::spawn(move || {
        let mut held = Vec::new();
        for stream in listener.incoming().take(4) {
            held.push(stream); // Kept open deliberately.
        }
        std::thread::sleep(Duration::from_secs(30));
    });

    let state = scratch("blackhole");
    let (elapsed, stdout) = run_push(&format!("http://127.0.0.1:{port}"), &state);

    assert!(
        elapsed < MUST_RETURN_WITHIN,
        "push took {elapsed:?}; the status line would have waited on the network"
    );
    assert_eq!(stdout, PAYLOAD, "the status line's own payload must come back untouched");
}

#[test]
fn an_unroutable_relay_does_not_delay_the_prompt() {
    // Nothing listening at all: a connection refused, or on some networks a
    // long SYN timeout. Either way the prompt must not notice.
    let state = scratch("unroutable");
    let (elapsed, stdout) = run_push("http://127.0.0.1:9", &state);
    assert!(elapsed < MUST_RETURN_WITHIN, "push took {elapsed:?}");
    assert_eq!(stdout, PAYLOAD);
}

#[test]
fn an_unconfigured_push_is_a_pass_through_not_an_error() {
    // Before anyone has set a relay up. Every prompt would otherwise carry an
    // error, which is a worse outcome than simply not reporting usage.
    let state = scratch("unconfigured");
    let mut child = Command::new(env!("CARGO_BIN_EXE_headroom"))
        .arg("push")
        .env("HEADROOM_STATE_DIR", &state)
        .env_remove("HEADROOM_RELAY_URL")
        .env_remove("HEADROOM_RELAY_SECRET")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    child.stdin.take().unwrap().write_all(PAYLOAD.as_bytes()).unwrap();
    let output = child.wait_with_output().unwrap();
    assert!(output.status.success());
    assert_eq!(String::from_utf8(output.stdout).unwrap(), PAYLOAD);
    assert!(output.stderr.is_empty(), "a status line must not be noisy");
}

#[test]
fn a_payload_it_cannot_use_is_still_passed_through_verbatim() {
    // An older Claude Code, an API-key user, or simply something unexpected.
    let state = scratch("nonsense");
    let mut child = Command::new(env!("CARGO_BIN_EXE_headroom"))
        .args(["push", "--relay", "http://127.0.0.1:9", "--secret", "s"])
        .env("HEADROOM_STATE_DIR", &state)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    child.stdin.take().unwrap().write_all(b"not json at all").unwrap();
    let output = child.wait_with_output().unwrap();
    assert!(output.status.success());
    assert_eq!(String::from_utf8(output.stdout).unwrap(), "not json at all");
}

#[test]
fn the_reading_does_arrive_even_though_the_prompt_did_not_wait() {
    // The other half of the guarantee: returning fast is only worth anything if
    // the work still happens.
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let (sender, receiver) = mpsc::channel();
    std::thread::spawn(move || {
        if let Ok(stream) = listener.accept().map(|(stream, _)| stream) {
            let _ = sender.send(read_request(stream));
        }
    });

    let state = scratch("delivery");
    let (elapsed, _) = run_push(&format!("http://127.0.0.1:{port}"), &state);
    assert!(elapsed < MUST_RETURN_WITHIN, "push took {elapsed:?}");

    let (request_line, authorization, body) =
        receiver.recv_timeout(Duration::from_secs(10)).expect("the relay was never called");
    assert!(request_line.starts_with("POST /usage "), "got {request_line:?}");
    assert_eq!(authorization.as_deref(), Some("Bearer s3cret"));
    assert!(body.contains(r#""kind":"session""#), "got {body}");
    assert!(body.contains("41.6"), "the session percentage was lost: {body}");
    assert!(body.contains(r#""kind":"weekly_all""#), "got {body}");
}

/// Read one HTTP request off a socket and answer it, without a server crate.
fn read_request(mut stream: TcpStream) -> (String, Option<String>, String) {
    let mut reader = BufReader::new(stream.try_clone().unwrap());
    let mut request_line = String::new();
    reader.read_line(&mut request_line).unwrap();

    let (mut length, mut authorization) = (0usize, None);
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).unwrap() == 0 || line.trim().is_empty() {
            break;
        }
        let lowered = line.to_ascii_lowercase();
        if let Some(value) = lowered.strip_prefix("content-length:") {
            length = value.trim().parse().unwrap_or(0);
        } else if lowered.starts_with("authorization:") {
            authorization = Some(line[line.find(':').unwrap() + 1..].trim().to_string());
        }
    }

    let mut body = vec![0u8; length];
    reader.read_exact(&mut body).unwrap();
    stream.write_all(b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n").unwrap();
    (request_line.trim().to_string(), authorization, String::from_utf8_lossy(&body).into_owned())
}
