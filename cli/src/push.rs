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

use crate::claude;
use crate::statusline::{self, StatusLine};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// How often to spend a request on the full picture.
///
/// The upstream limit punishes bursts - a couple of dozen requests in a minute
/// has been reported to earn a lockout near 24 hours that also blocks Claude
/// Code and the web. Five minutes is twelve an hour, two orders of magnitude
/// under that, and because the status line only runs while you are working, an
/// idle night costs nothing at all.
pub const DEFAULT_ENRICH_INTERVAL: u64 = 300;

/// A status line can fire several times a minute during heavy work. Enriching
/// on every one of those is the single shape that approaches the burst limit,
/// so it is refused rather than merely discouraged.
pub const MIN_ENRICH_INTERVAL: u64 = 60;

const HTTP_TIMEOUT: Duration = Duration::from_secs(10);

/// The bucket kinds the status line payload can produce on its own. Anything
/// else came from enrichment and has to be carried between fetches, or it shows
/// up once and disappears.
const FROM_STATUS_LINE: [&str; 2] = ["session", "weekly_all"];

/// How long a carried window may be shown before it is dropped instead.
///
/// It is normally at most one enrichment interval old. This bound only matters
/// when enrichment has started failing - an unreadable credential, a moved
/// endpoint - and there it decides between a bar that is quietly wrong and a
/// bar that is honestly absent. It matches the app's own staleness threshold.
const CARRY_FOR: u64 = 3_600;

#[derive(Debug, Default, Serialize, Deserialize)]
struct State {
    /// When the full picture was last fetched.
    #[serde(default)]
    enriched_at: u64,
    /// Cached so the 200 MB executable is scanned once, not every five minutes.
    #[serde(default)]
    usage_endpoint: Option<String>,
    /// The windows only enrichment can see - the per-model weekly ones.
    ///
    /// Kept because the status line fires on every message and enrichment only
    /// every few minutes. Without this, each cheap push would replace a reading
    /// that had per-model bars with one that does not, and those bars would
    /// appear for a few seconds after every fetch and then vanish.
    #[serde(default)]
    carried: Vec<Value>,
    #[serde(default)]
    carried_at: u64,
}

pub struct Config {
    pub relay: String,
    pub secret: String,
    pub interval: u64,
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
        .arg("--interval")
        .arg(config.interval.to_string())
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn();
    0
}

/// The child. Everything slow lives here.
pub fn deliver_detached(config: &Config, body: &str) -> i32 {
    // Under heavy use the status line fires faster than a request completes.
    // Without this, children stack up and each one races the others to enrich.
    let Some(_lock) = Lock::acquire(&state_directory().join("push.lock")) else { return 0 };
    let _ = deliver(config, body);
    0
}

fn deliver(config: &Config, cheap_body: &str) -> Result<String, String> {
    let path = state_path();
    let mut state = load_state(&path);
    let now = unix_time();

    let mut enriched = None;
    if now.saturating_sub(state.enriched_at) >= config.interval {
        enriched = enrich(&mut state);
    }

    let body = match &enriched {
        // A fetch just happened: send it whole, and remember the windows the
        // status line will not be able to reproduce on the next message.
        Some(limits) => {
            state.carried =
                limits.iter().filter(|entry| !is_from_status_line(entry)).cloned().collect();
            state.carried_at = now;
            state.enriched_at = now;
            json!({ "limits": limits }).to_string()
        }
        // The common case, on nearly every message. The status line's two
        // windows are fresh; the per-model ones ride along from the last fetch
        // rather than being dropped.
        None => {
            let mut limits: Vec<Value> = serde_json::from_str::<Value>(cheap_body)
                .ok()
                .and_then(|body| body.get("limits").and_then(Value::as_array).cloned())
                .unwrap_or_default();
            if now.saturating_sub(state.carried_at) < CARRY_FOR {
                limits.extend(state.carried.iter().cloned());
            } else {
                state.carried.clear();
            }
            json!({ "limits": limits }).to_string()
        }
    };

    post(&config.relay, &config.secret, &body, now)?;
    save_state(&path, &state);
    Ok(match (&enriched, state.carried.len()) {
        (Some(_), _) => "fetched the full picture, per-model windows included".into(),
        (None, 0) => "pushed the session and weekly windows".into(),
        (None, n) => format!("pushed the session and weekly windows, carrying {n} per-model"),
    })
}

/// Whether the status line could have produced this entry itself.
fn is_from_status_line(entry: &Value) -> bool {
    entry.get("kind").and_then(Value::as_str).is_some_and(|kind| FROM_STATUS_LINE.contains(&kind))
}

/// The per-model windows, which the status line does not carry.
///
/// Returns `None` on any failure. That is not a silent swallow: the caller
/// still pushes the two windows it already has, so a failed enrichment costs
/// one bar on the phone, not the screen.
fn enrich(state: &mut State) -> Option<Vec<Value>> {
    // An expired token would spend a request to earn a 401. Claude Code will
    // have refreshed it by the next message; wait for that instead.
    if let Some(expiry) = claude::token_expiry()
        && expiry <= unix_time() as i64
    {
        return None;
    }
    let token = claude::access_token_anywhere()?;
    let endpoint = match &state.usage_endpoint {
        Some(cached) => cached.clone(),
        None => {
            let found = claude::usage_endpoint()?;
            state.usage_endpoint = Some(found.clone());
            found
        }
    };
    let mut response = agent()
        .get(&endpoint)
        .header("Authorization", format!("Bearer {token}"))
        .header("Content-Type", "application/json")
        .call()
        .ok()?;
    if response.status().as_u16() != 200 {
        return None;
    }
    let text = response.body_mut().read_to_string().ok()?;
    let parsed: Value = serde_json::from_str(&text).ok()?;
    trim(&parsed)
}

/// Keep the `limits` array and nothing else.
///
/// The full response is around twenty keys wide. Beyond `limits` it carries
/// unreleased product codenames and a `spend` object with what you have been
/// billed - none of which the app reads, and none of which has any business
/// being copied onto a relay, cached on a phone, or sitting in a file on a
/// box exposed to the internet. Sending only what is drawn is also the answer
/// to "what does the relay know about me": three percentages and three reset
/// times.
///
/// Returning `None` when `limits` is absent doubles as the response-shape
/// check: an unrecognised body must not displace a reading that was fine.
fn trim(response: &Value) -> Option<Vec<Value>> {
    Some(response.get("limits")?.as_array()?.clone())
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

fn state_path() -> PathBuf {
    state_directory().join("push.json")
}

fn load_state(path: &Path) -> State {
    std::fs::read_to_string(path)
        .ok()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default()
}

fn save_state(path: &Path, state: &State) {
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    if let Ok(encoded) = serde_json::to_string(state) {
        // One scratch name per writer. `--once` does its work outside the lock,
        // so two writers can be here at the same moment, and a shared name let
        // them truncate each other's half-written state - or rename the other's
        // bytes into place - rather than each landing whole.
        let scratch = path.with_extension(format!("{}.tmp", std::process::id()));
        if std::fs::write(&scratch, encoded).is_ok() && std::fs::rename(&scratch, path).is_ok() {
            return;
        }
        // Best effort: a failed write or rename must not leave scratch behind.
        let _ = std::fs::remove_file(&scratch);
    }
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

    /// Shaped after a real response, with the parts that must not travel.
    const FULL_RESPONSE: &str = r#"{
        "five_hour": {"utilization": 33.0},
        "seven_day_unreleased_codename": null,
        "nimbus_quill": {"utilization": 0.0},
        "limits": [
            {"kind": "session", "group": "session", "percent": 33, "is_active": true},
            {"kind": "weekly_scoped", "percent": 22,
             "scope": {"model": {"display_name": "Fable"}}}
        ],
        "spend": {"used": {"amount_minor": 1234, "currency": "USD"}, "percent": 0},
        "member_dashboard_available": false
    }"#;

    #[test]
    fn enrichment_keeps_the_limits() {
        let limits = trim(&serde_json::from_str(FULL_RESPONSE).unwrap()).unwrap();
        assert_eq!(limits.len(), 2);
        // The per-model window is the entire reason enrichment costs a request.
        assert_eq!(limits[1]["scope"]["model"]["display_name"], "Fable");
    }

    #[test]
    fn enrichment_leaves_spending_and_codenames_behind() {
        let limits = trim(&serde_json::from_str(FULL_RESPONSE).unwrap()).unwrap();
        let sent = serde_json::json!({ "limits": limits }).to_string();
        for leaked in ["spend", "1234", "nimbus_quill", "unreleased_codename", "member_dashboard"] {
            assert!(!sent.contains(leaked), "{leaked} reached the relay: {sent}");
        }
    }

    #[test]
    fn a_response_without_limits_does_not_displace_a_good_reading() {
        assert!(trim(&serde_json::json!({"error": "nope"})).is_none());
        assert!(trim(&serde_json::json!({"limits": "not an array"})).is_none());
    }

    // --- carrying the per-model windows between fetches ---
    //
    // The status line fires on every message; enrichment every few minutes.
    // Without carrying, each cheap push replaced a reading that had per-model
    // bars with one that did not, so the Fable bar appeared for a moment after
    // every fetch and was then destroyed by the next keystroke.

    fn entry(kind: &str, percent: f64) -> Value {
        serde_json::json!({"kind": kind, "percent": percent})
    }

    fn scoped(name: &str, percent: f64) -> Value {
        serde_json::json!({
            "kind": "weekly_scoped", "percent": percent,
            "scope": {"model": {"display_name": name}},
        })
    }

    #[test]
    fn only_the_windows_the_status_line_cannot_produce_are_carried() {
        assert!(is_from_status_line(&entry("session", 1.0)));
        assert!(is_from_status_line(&entry("weekly_all", 1.0)));
        assert!(!is_from_status_line(&scoped("Fable", 1.0)));
        // Forward-compatible: a kind we have never seen is assumed to need
        // carrying, because the status line demonstrably did not send it.
        assert!(!is_from_status_line(&entry("something_new", 1.0)));
    }

    #[test]
    fn a_fetch_records_the_per_model_windows_for_later() {
        let limits = [entry("session", 33.0), entry("weekly_all", 23.0), scoped("Fable", 22.0)];
        let carried: Vec<Value> =
            limits.iter().filter(|e| !is_from_status_line(e)).cloned().collect();
        assert_eq!(carried.len(), 1);
        assert_eq!(carried[0]["scope"]["model"]["display_name"], "Fable");
    }

    #[test]
    fn a_carried_window_is_dropped_once_it_is_too_old_to_trust() {
        // Only reachable when enrichment has been failing for an hour. A bar
        // that is honestly absent beats one that is quietly wrong.
        let mut state =
            State { carried: vec![scoped("Fable", 22.0)], carried_at: 1_000, ..Default::default() };
        let now = 1_000 + CARRY_FOR + 1;
        assert!(now.saturating_sub(state.carried_at) >= CARRY_FOR);
        state.carried.clear();
        assert!(state.carried.is_empty());
    }

    #[test]
    fn carried_windows_survive_a_restart() {
        // The state file is the only thing between a reboot and a missing bar
        // until the next fetch.
        let path = temp().join("carry.json");
        save_state(
            &path,
            &State {
                enriched_at: 500,
                carried: vec![scoped("Fable", 22.0)],
                carried_at: 500,
                ..Default::default()
            },
        );
        let loaded = load_state(&path);
        assert_eq!(loaded.carried.len(), 1);
        assert_eq!(loaded.carried[0]["scope"]["model"]["display_name"], "Fable");
        assert_eq!(loaded.carried_at, 500);
        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn state_written_by_an_older_version_still_loads() {
        // Upgrading must not lose the enrichment clock and start a fetch storm.
        let path = temp().join("old.json");
        std::fs::write(&path, r#"{"enriched_at":42,"usage_endpoint":"https://x.test/u"}"#).unwrap();
        let loaded = load_state(&path);
        assert_eq!(loaded.enriched_at, 42);
        assert!(loaded.carried.is_empty());
        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn state_round_trips() {
        let path = temp().join("push.json");
        let state = State {
            enriched_at: 42,
            usage_endpoint: Some("https://x.test/u".into()),
            ..Default::default()
        };
        save_state(&path, &state);
        let loaded = load_state(&path);
        assert_eq!(loaded.enriched_at, 42);
        assert_eq!(loaded.usage_endpoint.as_deref(), Some("https://x.test/u"));
        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn missing_or_corrupt_state_reads_as_a_fresh_start() {
        let directory = temp();
        assert_eq!(load_state(&directory.join("absent.json")).enriched_at, 0);
        let bad = directory.join("bad.json");
        std::fs::write(&bad, "{not json").unwrap();
        assert_eq!(load_state(&bad).enriched_at, 0);
        std::fs::remove_file(&bad).ok();
    }

    #[test]
    fn a_second_holder_is_turned_away() {
        let path = temp().join("a.lock");
        let _held = Lock::acquire(&path).expect("first acquire");
        assert!(Lock::acquire(&path).is_none(), "two children would both enrich");
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

    #[test]
    fn a_save_does_not_clobber_another_writer_s_scratch_file() {
        // `--once` does its work outside the lock, so two writers can be in
        // save_state at the same moment. One shared scratch filename let them
        // truncate each other's half-written state and rename the result.
        let path = temp().join("concurrent.json");
        let theirs = path.with_extension("tmp");
        std::fs::write(&theirs, b"another writer was mid-write").unwrap();

        save_state(&path, &State { enriched_at: 7, ..Default::default() });

        assert_eq!(std::fs::read_to_string(&theirs).unwrap(), "another writer was mid-write");
        assert_eq!(load_state(&path).enriched_at, 7);
    }
}
