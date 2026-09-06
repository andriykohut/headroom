//! The relay's protocol is unit-tested without a socket; this exercises the
//! wiring around it - headers, status codes, body limits and concurrency -
//! against the real server, because that layer is where a correct handler
//! still gets served wrongly.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpStream;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU16, Ordering};
use std::time::{Duration, Instant};

struct Relay {
    process: Child,
    port: u16,
    secret: String,
}

impl Drop for Relay {
    fn drop(&mut self) {
        let _ = self.process.kill();
        let _ = self.process.wait();
    }
}

fn start(name: &str) -> Relay {
    let directory =
        std::env::temp_dir().join(format!("headroom-relay-it-{}-{name}", std::process::id()));
    std::fs::remove_dir_all(&directory).ok();
    std::fs::create_dir_all(&directory).unwrap();

    let secret = "test-secret-not-a-real-one".to_string();
    let secret_file = directory.join("secret");
    std::fs::write(&secret_file, &secret).unwrap();

    // Tests run in parallel, so each relay needs its own port. The process id
    // separates concurrent `cargo test` runs; the counter separates the tests
    // within one.
    static NEXT: AtomicU16 = AtomicU16::new(0);
    let port =
        18_000 + (std::process::id() % 1_000) as u16 * 16 + NEXT.fetch_add(1, Ordering::Relaxed);
    let process = Command::new(env!("CARGO_BIN_EXE_headroom"))
        .args(["serve", "--host", "127.0.0.1", "--port", &port.to_string()])
        .args(["--secret-file", secret_file.to_str().unwrap()])
        .args(["--state", directory.join("relay.json").to_str().unwrap()])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .expect("start the relay");

    let relay = Relay { process, port, secret };
    let deadline = Instant::now() + Duration::from_secs(5);
    while Instant::now() < deadline {
        if TcpStream::connect(("127.0.0.1", port)).is_ok() {
            return relay;
        }
        std::thread::sleep(Duration::from_millis(25));
    }
    panic!("the relay never started listening on {port}");
}

struct Reply {
    status: u16,
    headers: Vec<(String, String)>,
    body: String,
}

impl Reply {
    fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(field, _)| field.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
    }
}

fn request(
    relay: &Relay,
    method: &str,
    path: &str,
    auth: Option<&str>,
    body: Option<&str>,
) -> Reply {
    let mut stream = TcpStream::connect(("127.0.0.1", relay.port)).expect("connect");
    stream.set_read_timeout(Some(Duration::from_secs(5))).unwrap();

    let mut head = format!("{method} {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n");
    if let Some(auth) = auth {
        head.push_str(&format!("Authorization: {auth}\r\n"));
    }
    head.push_str(&format!("Content-Length: {}\r\n\r\n", body.map_or(0, str::len)));
    stream.write_all(head.as_bytes()).unwrap();
    if let Some(body) = body {
        stream.write_all(body.as_bytes()).unwrap();
    }
    stream.flush().unwrap();

    let mut reader = BufReader::new(stream);
    let mut status_line = String::new();
    reader.read_line(&mut status_line).unwrap();
    let status = status_line.split_whitespace().nth(1).unwrap().parse().unwrap();

    let mut headers = Vec::new();
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).unwrap() == 0 || line.trim().is_empty() {
            break;
        }
        if let Some((field, value)) = line.split_once(':') {
            headers.push((field.trim().to_string(), value.trim().to_string()));
        }
    }
    let mut body = String::new();
    reader.read_to_string(&mut body).unwrap();
    Reply { status, headers, body }
}

const READING: &str = r#"{"limits":[{"kind":"session","percent":41.6,"is_active":true}]}"#;

#[test]
fn a_pushed_reading_comes_back_to_an_authorised_reader() {
    let relay = start("round-trip");
    let bearer = format!("Bearer {}", relay.secret);

    assert_eq!(request(&relay, "GET", "/usage", Some(&bearer), None).status, 503);

    let pushed = request(&relay, "POST", "/usage", Some(&bearer), Some(READING));
    assert_eq!(pushed.status, 204);

    let read = request(&relay, "GET", "/usage", Some(&bearer), None);
    assert_eq!(read.status, 200);
    assert_eq!(read.body, READING, "the relay must serve back exactly what it was given");
    assert_eq!(read.header("Content-Type"), Some("application/json"));
    assert!(read.header("X-Headroom-Age").is_some(), "the app needs to know how old this is");
}

#[test]
fn the_wrong_secret_gets_nothing_in_either_direction() {
    let relay = start("auth");
    let bearer = format!("Bearer {}", relay.secret);
    request(&relay, "POST", "/usage", Some(&bearer), Some(READING));

    let read = request(&relay, "GET", "/usage", Some("Bearer wrong"), None);
    assert_eq!(read.status, 401);
    assert!(!read.body.contains("41.6"), "a rejected read leaked the reading");

    let write = request(&relay, "POST", "/usage", Some("Bearer wrong"), Some(r#"{"limits":[]}"#));
    assert_eq!(write.status, 401);
    let after = request(&relay, "GET", "/usage", Some(&bearer), None);
    assert_eq!(after.body, READING, "an unauthorised push overwrote the reading");
}

#[test]
fn an_oversized_body_is_refused_without_being_stored() {
    let relay = start("oversized");
    let bearer = format!("Bearer {}", relay.secret);
    request(&relay, "POST", "/usage", Some(&bearer), Some(READING));

    let huge = format!(r#"{{"limits":[],"pad":"{}"}}"#, "x".repeat(300 * 1024));
    assert_eq!(request(&relay, "POST", "/usage", Some(&bearer), Some(&huge)).status, 413);
    assert_eq!(
        request(&relay, "GET", "/usage", Some(&bearer), None).body,
        READING,
        "an oversized push displaced a good reading"
    );
}

#[test]
fn health_is_available_without_the_secret() {
    let relay = start("health");
    let reply = request(&relay, "GET", "/healthz", None, None);
    assert_eq!(reply.status, 200);
    assert!(reply.body.contains("\"ok\":false"));
}

#[test]
fn concurrent_readers_do_not_deadlock_each_other() {
    // Four workers share one Mutex around the reading. A lock held across a
    // response write would serialise, or worse, wedge.
    let relay = start("concurrent");
    let bearer = format!("Bearer {}", relay.secret);
    request(&relay, "POST", "/usage", Some(&bearer), Some(READING));

    let started = Instant::now();
    std::thread::scope(|scope| {
        for _ in 0..12 {
            let (relay, bearer) = (&relay, &bearer);
            scope.spawn(move || {
                assert_eq!(request(relay, "GET", "/usage", Some(bearer), None).status, 200);
            });
        }
    });
    assert!(started.elapsed() < Duration::from_secs(10), "readers took {:?}", started.elapsed());
}
