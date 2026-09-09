//! The status line hook: report usage without slowing the prompt down.
//!
//! # Why this never blocks
//!
//! Claude Code runs the status line command on every message and waits for it
//! to finish, so anything slow here is felt on every prompt. Three rules keep
//! that from happening, and all three are load-bearing:
//!
//! 1. **stdin is echoed to stdout and flushed before anything else.** The
//!    status line renders from that, so it is never waiting on us to think.
//! 2. **No network call happens in this process.** The work is handed to a
//!    detached child and the parent returns. Total added latency is a process
//!    spawn - about two milliseconds - and it does not vary with the network.
//! 3. **The child's stdout and stderr go to /dev/null.** This is the subtle
//!    one: a child inheriting the pipe would hold it open after the parent
//!    exits, and Claude Code, reading until EOF, would block for exactly as
//!    long as the child ran. Redirecting is what makes the detach real.
//!
//! `tests/push_does_not_block.rs` holds this to a stopwatch against a relay
//! that accepts connections and never answers.
//!
//! Everything reported here arrives in the payload Claude Code hands this hook,
//! on a response the user already paid for. Nothing in this file contacts the
//! provider, and `scripts/check-distribution.sh` fails the build if that
//! changes.

use crate::statusline::{self, StatusLine};
use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const HTTP_TIMEOUT: Duration = Duration::from_secs(10);

pub struct Config {
    pub relay: String,
    pub secret: String,
}

/// The parent: echo, hand off, exit. Never returns an error to the caller,
/// because a failure to report usage must not become a failure to draw a
/// status line.
pub fn run(config: Option<Config>, foreground: bool) -> i32 {
    let mut raw = String::new();
    if std::io::stdin().read_to_string(&mut raw).is_err() {
        return 0;
    }
    // Rule 1: the status line's own content goes out first and immediately.
    let mut stdout = std::io::stdout();
    let _ = stdout.write_all(raw.as_bytes());
    let _ = stdout.flush();

    let Some(config) = config else { return 0 };

    // Parsing here rather than in the child keeps the handoff to a single
    // small argument - no pipe to the child, so nothing that can block on a
    // full buffer. A status line payload is a few kilobytes; this is
    // microseconds.
    let Ok(status) = serde_json::from_str::<StatusLine>(&raw) else { return 0 };
    let Some(body) = statusline::to_usage(&status) else { return 0 };
    let body = body.to_string();

    if foreground {
        match deliver(&config, &body) {
            Ok(outcome) => eprintln!("headroom: {outcome}"),
            Err(error) => eprintln!("headroom: {error}"),
        }
        return 0;
    }

    // Rule 2 and 3: the request happens somewhere this process does not wait on.
    let Ok(exe) = std::env::current_exe() else { return 0 };
    let _ = Command::new(exe)
        .arg("push")
        .arg("--deliver")
        .arg(&body)
        .arg("--relay")
        .arg(&config.relay)
        .arg("--secret")
        .arg(&config.secret)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn();
    0
}

/// The child. Everything slow lives here.
pub fn deliver_detached(config: &Config, body: &str) -> i32 {
    // Under heavy use the status line fires faster than a request completes,
    // and every Claude Code session on the machine shares this lock. Without
    // it they would each send the same account-wide reading.
    let Some(_lock) = Lock::acquire(&state_directory().join("push.lock")) else { return 0 };
    let _ = deliver(config, body);
    0
}

fn deliver(config: &Config, body: &str) -> Result<String, String> {
    post(&config.relay, &config.secret, body, unix_time())?;
    Ok("pushed the session and weekly windows".into())
}

fn post(relay: &str, secret: &str, body: &str, observed_at: u64) -> Result<(), String> {
    let url = format!("{}/usage", relay.trim_end_matches('/'));
    let response = agent()
        .post(&url)
        .header("Authorization", format!("Bearer {secret}"))
        .header("Content-Type", "application/json")
        // Two machines on one account cannot see each other, so each says when
        // it took the reading and the relay keeps the newer of the two.
        .header(crate::relay::OBSERVED_AT_HEADER, observed_at.to_string())
        .send(body)
        .map_err(|error| format!("could not reach the relay: {error}"))?;
    let status = response.status().as_u16();
    if !(200..300).contains(&status) {
        return Err(format!("the relay refused the reading (HTTP {status})"));
    }
    Ok(())
}

fn agent() -> ureq::Agent {
    ureq::Agent::config_builder()
        .timeout_global(Some(HTTP_TIMEOUT))
        // Statuses are decisions here, not exceptions: a 401 from the relay
        // means the wrong secret, which is worth saying plainly.
        .http_status_as_error(false)
        .build()
        .new_agent()
}

/// An advisory lock on a file, released by the kernel when its holder exits -
/// including a `kill -9`, and including a machine that lost power.
///
/// The file's *existence* means nothing and it is never removed; it is only
/// something to hang the lock on. Locking by existence instead needs a
/// staleness rule to recover from a killed child, and two children reaching
/// that rule in the same instant both recover - each deleting the lock the
/// other just took. That race is reached by running several Claude Code
/// sessions at once, which is the ordinary case this exists to handle.
struct Lock {
    /// Held open for exactly as long as the lock is: closing it unlocks.
    _file: File,
}

impl Lock {
    /// `None` when another process holds it, and that child then pushes
    /// nothing. Nothing is lost by that: the readings are account-wide, so the
    /// holder is reporting the same numbers on its behalf.
    fn acquire(path: &Path) -> Option<Self> {
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        // Never truncated: the file is a handle to lock, not somewhere to
        // write, and another session may be holding it right now.
        let file =
            std::fs::OpenOptions::new().write(true).create(true).truncate(false).open(path).ok()?;
        file.try_lock().ok()?;
        Some(Lock { _file: file })
    }
}

fn state_directory() -> PathBuf {
    if let Some(base) = std::env::var_os("HEADROOM_STATE_DIR") {
        return PathBuf::from(base);
    }
    if let Some(base) = std::env::var_os("XDG_STATE_HOME") {
        return PathBuf::from(base).join("headroom");
    }
    let home = std::env::var_os("HOME").map(PathBuf::from).unwrap_or_else(std::env::temp_dir);
    home.join(".local/state/headroom")
}

fn unix_time() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp() -> PathBuf {
        let path = std::env::temp_dir().join(format!(
            "headroom-push-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        std::fs::create_dir_all(&path).unwrap();
        path
    }

    #[test]
    fn a_second_holder_is_turned_away() {
        let path = temp().join("a.lock");
        let _held = Lock::acquire(&path).expect("first acquire");
        assert!(Lock::acquire(&path).is_none(), "two children would both push");
    }

    #[test]
    fn the_lock_clears_when_its_holder_finishes() {
        let path = temp().join("b.lock");
        drop(Lock::acquire(&path).expect("first acquire"));
        assert!(Lock::acquire(&path).is_some(), "a released lock must be re-acquirable");
    }

    #[test]
    fn a_lock_file_left_by_a_crash_is_available_at_once() {
        // A child killed mid-request cannot clean up after itself. The kernel
        // drops an advisory lock when its holder dies, so the next child takes
        // the file over immediately: there is no window in which a holder that
        // no longer exists silences pushes.
        let path = temp().join("crashed.lock");
        std::fs::write(&path, b"").unwrap();
        assert!(Lock::acquire(&path).is_some(), "a leftover file is not a live holder");
    }
}
