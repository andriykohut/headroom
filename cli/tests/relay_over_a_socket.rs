//! The relay's protocol is unit-tested without a socket; this exercises the
//! wiring around it - headers, status codes, body limits and concurrency -
//! against the real server, because that layer is where a correct handler
//! still gets served wrongly.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpStream;
use std::process::{Child, Command, Stdio};
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

    // The relay picks its own port and says which in its log. This test must
    // not bind a throwaway listener to find a free port first: seven of these
    // start at once, and on macOS a socket is created and then marked
    // close-on-exec in two steps while `Command::spawn` copies every
    // descriptor not yet marked. A sibling's relay then inherits the
    // throwaway listener, so the port stays bound after this thread drops it,
    // this test's relay cannot bind, and every request queues on a listener
    // nobody accepts from - until the sibling's test ends and kills its relay,
    // which resets them all. That was the "connection reset by peer" that only
    // showed under parallel load.
    //
    // Kept, so a startup failure reads as a startup failure rather than a
    // timeout with no explanation.
    let log = directory.join("relay.log");
    let process = Command::new(env!("CARGO_BIN_EXE_headroom"))
        .args(["serve", "--host", "127.0.0.1", "--port", "0"])
        .args(["--secret-file", secret_file.to_str().unwrap()])
        .args(["--state", directory.join("relay.json").to_str().unwrap()])
        // Short, so the slow-client test does not cost ten seconds. Everything
        // else here completes far inside it.
        .args(["--header-timeout", "1"])
        .stdout(Stdio::null())
        .stderr(Stdio::from(std::fs::File::create(&log).expect("open the relay log")))
        .spawn()
        .expect("start the relay");
    let mut relay = Relay { process, port: 0, secret };

    // Generous on purpose: a CI runner starting seven of these at once is far
    // slower than a laptop starting one, and a flaky harness costs more than a
    // slow one.
    let deadline = Instant::now() + Duration::from_secs(30);
    while Instant::now() < deadline {
        let stderr = std::fs::read_to_string(&log).unwrap_or_default();
        if let Some(port) = listening_port(&stderr) {
            relay.port = port;
            return relay;
        }
        if let Some(Ok(status)) = relay.process.try_wait().transpose() {
            panic!("the relay exited with {status} before listening. Its stderr said: {stderr}");
        }
        std::thread::sleep(Duration::from_millis(25));
    }
    let stderr = std::fs::read_to_string(&log).unwrap_or_default();
    panic!("the relay never said it was listening. Its stderr said: {stderr}");
}

/// The port from the relay's "serving on" line, which it prints only once it
/// is accepting connections.
fn listening_port(stderr: &str) -> Option<u16> {
    stderr.lines().find_map(|line| {
        line.strip_prefix("headroom: serving on http://127.0.0.1:")?
            .strip_suffix("/usage")?
            .parse()
            .ok()
    })
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

#[test]
fn a_client_that_connects_and_says_nothing_is_hung_up_on() {
    // The reason for moving off a thread-per-connection server with no socket
    // timeout. Previously this connection was held for as long as the client
    // cared to hold it, and each one cost a thread; measured at 400 idle
    // connections it reached 406 threads. Now the server closes it.
    let relay = start("slowloris");
    let mut stream = TcpStream::connect(("127.0.0.1", relay.port)).expect("connect");
    stream.write_all(b"GET /healthz HTTP/1.1\r\nHost: x\r\n").unwrap(); // no final CRLF
    stream.flush().unwrap();
    stream.set_read_timeout(Some(Duration::from_secs(8))).unwrap();

    let started = Instant::now();
    let mut sink = Vec::new();
    // Reading to EOF returns when the server hangs up. If it never does, the
    // socket read timeout above fires instead and this fails.
    let outcome = stream.read_to_end(&mut sink);
    let elapsed = started.elapsed();

    assert!(outcome.is_ok(), "the server never closed an idle connection: {outcome:?}");
    assert!(elapsed < Duration::from_secs(6), "took {elapsed:?} to hang up");

    // And the relay is still serving everyone else afterwards.
    assert_eq!(request(&relay, "GET", "/healthz", None, None).status, 200);
}

#[test]
fn many_idle_connections_do_not_stop_it_serving() {
    let relay = start("flood");
    let mut held = Vec::new();
    for _ in 0..80 {
        if let Ok(stream) = TcpStream::connect(("127.0.0.1", relay.port)) {
            let _ = (&stream).write_all(b"GET /healthz HTTP/1.1\r\n");
            held.push(stream);
        }
    }
    assert!(held.len() >= 64, "only opened {} connections", held.len());
    // Past --max-connections these queue in the kernel rather than becoming
    // tasks, and the ones already accepted are on a timer. A legitimate caller
    // still gets served.
    assert_eq!(request(&relay, "GET", "/healthz", None, None).status, 200);
}
