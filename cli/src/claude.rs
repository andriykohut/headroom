//! What this machine's Claude Code install can tell us.
//!
//! Two things are needed for enrichment, and neither is a secret this tool
//! manages:
//!
//! - the **access token** Claude Code is currently using, read and never
//!   written;
//! - the **usage endpoint**, which is not in the credential store and has to be
//!   read out of the executable.
//!
//! Conspicuously absent: refreshing. The status line runs immediately after
//! Claude Code has made a request, so the token in the store was valid seconds
//! ago; when it does expire, Claude Code renews it on its own next message and
//! the following status line picks up the new one. Leaving the refresh chain
//! entirely to its owner is what stops this tool from ever invalidating your
//! login - the failure that sank the design where the phone held a copy.
//!
//! Everything here is undocumented internals found by inspection, not a public
//! API. `docs/discovery-notes.md` records when and how each was verified. This
//! file is also why the scan lives in the desktop tool and not in the app: the
//! published app ships no provider identifiers at all (spec section 3), and it
//! is only legitimate to read these on the machine where they already live.

use serde_json::Value;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::Command;

const CREDENTIAL_FILE: &str = ".claude/.credentials.json";
const KEYCHAIN_SERVICE: &str = "Claude Code-credentials";
const USAGE_PATH: &str = "/api/oauth/usage";

/// Claude Code stores expiry in milliseconds; this is year 2286 in seconds, so
/// anything larger is unambiguously a millisecond timestamp.
const MILLIS_THRESHOLD: i64 = 10_000_000_000;

/// The token Claude Code is using, from the first source that has one.
///
/// Ordered portable file first, then the OS keystores. Returns `None` rather
/// than an error: every caller's fallback is to push the status line numbers
/// alone, which is a smaller screen rather than a broken one.
pub fn access_token() -> Option<String> {
    let home = home_dir()?;
    if let Some(token) = token_from_json(&std::fs::read_to_string(home.join(CREDENTIAL_FILE)).ok()?)
    {
        return Some(token);
    }
    None
}

/// The same, extended to the OS keystores. Split out because the file path is
/// by far the common case and costs no subprocess.
pub fn access_token_anywhere() -> Option<String> {
    if let Some(token) = access_token() {
        return Some(token);
    }
    let candidates: [&[&str]; 3] = [
        &["security", "find-generic-password", "-s", KEYCHAIN_SERVICE, "-w"],
        &["secret-tool", "lookup", "service", KEYCHAIN_SERVICE],
        &["kwallet-query", "-r", KEYCHAIN_SERVICE, "kdewallet"],
    ];
    for argv in candidates {
        let Some(output) = run(argv) else { continue };
        if let Some(token) = token_from_json(&output) {
            return Some(token);
        }
    }
    None
}

fn token_from_json(raw: &str) -> Option<String> {
    let value: Value = serde_json::from_str(raw).ok()?;
    // The token sits one level down, under a wrapper object, rather than at the
    // top. Searching by key name rather than by path means a renamed wrapper
    // does not break this.
    find_string(&value, &["accessToken", "access_token"])
}

/// The expiry Claude Code recorded, in epoch seconds, if it can be read.
///
/// Only used to decide whether enrichment is worth attempting at all - an
/// expired token would just spend a request to earn a 401.
pub fn token_expiry() -> Option<i64> {
    let home = home_dir()?;
    let raw = std::fs::read_to_string(home.join(CREDENTIAL_FILE)).ok()?;
    let value: Value = serde_json::from_str(&raw).ok()?;
    let seconds = find_number(&value, &["expiresAt", "expires_at"])?;
    Some(if seconds > MILLIS_THRESHOLD { seconds / 1000 } else { seconds })
}

fn find_string(value: &Value, keys: &[&str]) -> Option<String> {
    let object = value.as_object()?;
    for key in keys {
        if let Some(found) = object.get(*key).and_then(Value::as_str)
            && !found.is_empty()
        {
            return Some(found.to_string());
        }
    }
    object.values().find_map(|nested| find_string(nested, keys))
}

fn find_number(value: &Value, keys: &[&str]) -> Option<i64> {
    let object = value.as_object()?;
    for key in keys {
        if let Some(found) = object.get(*key).and_then(Value::as_i64) {
            return Some(found);
        }
    }
    object.values().find_map(|nested| find_number(nested, keys))
}

/// Where to ask for the full usage picture.
///
/// Environment first, so a user on a different deployment - or debugging - can
/// state it outright and skip the scan entirely.
pub fn usage_endpoint() -> Option<String> {
    if let Ok(explicit) = std::env::var("HEADROOM_USAGE_ENDPOINT")
        && !explicit.is_empty()
    {
        return Some(explicit);
    }
    if let Ok(base) = std::env::var("ANTHROPIC_BASE_URL")
        && !base.is_empty()
    {
        // Claude Code honours this, so a user pointed at another deployment
        // gets the endpoint their own CLI would call.
        return Some(format!("{}{USAGE_PATH}", base.trim_end_matches('/')));
    }
    let origin = scan_for_api_origin(&claude_binary()?)?;
    Some(format!("{origin}{USAGE_PATH}"))
}

fn claude_binary() -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    for directory in std::env::split_paths(&path) {
        let candidate = directory.join("claude");
        if candidate.is_file() {
            // Usually a symlink into a versioned directory.
            return std::fs::canonicalize(&candidate).ok().or(Some(candidate));
        }
    }
    None
}

/// Find `https://<host>` from a `/api/oauth/` URL inside the executable.
///
/// The endpoint is never a whole URL in the binary - the path is a literal and
/// the origin is joined on at runtime - so the origin is taken from a sibling
/// `/api/oauth/` URL that *is* a literal.
///
/// Read in chunks because the executable is around 200 MB and this runs on a
/// machine someone is typing on. Successive chunks overlap so a URL that
/// straddles a boundary is still found whole.
fn scan_for_api_origin(binary: &Path) -> Option<String> {
    const CHUNK: usize = 1 << 20;
    const OVERLAP: usize = 512;

    let mut file = std::fs::File::open(binary).ok()?;
    let mut buffer = vec![0u8; CHUNK + OVERLAP];
    let mut carried = 0usize;
    loop {
        let read = read_fully(&mut file, &mut buffer[carried..]).ok()?;
        if read == 0 && carried == 0 {
            return None;
        }
        let filled = carried + read;
        if let Some(origin) = find_origin(&buffer[..filled]) {
            return Some(origin);
        }
        if read == 0 {
            return None;
        }
        // Carry the tail forward, so a match spanning the seam is not lost.
        carried = filled.min(OVERLAP);
        buffer.copy_within(filled - carried..filled, 0);
    }
}

fn read_fully(file: &mut std::fs::File, into: &mut [u8]) -> std::io::Result<usize> {
    let mut total = 0;
    while total < into.len() {
        match file.read(&mut into[total..]) {
            Ok(0) => break,
            Ok(n) => total += n,
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(error),
        }
    }
    Ok(total)
}

fn find_origin(haystack: &[u8]) -> Option<String> {
    const MARKER: &[u8] = b"/api/oauth/";
    const SCHEME: &[u8] = b"https://";
    let mut from = 0;
    while let Some(offset) = subslice(&haystack[from..], MARKER) {
        let marker_at = from + offset;
        // A host is short, so only a small window back needs searching.
        let window_start = marker_at.saturating_sub(128);
        if let Some(scheme_offset) = rsubslice(&haystack[window_start..marker_at], SCHEME) {
            let origin_at = window_start + scheme_offset;
            let host = &haystack[origin_at + SCHEME.len()..marker_at];
            let plausible = !host.is_empty()
                && host.len() < 100
                && host.contains(&b'.')
                && host
                    .iter()
                    .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-'));
            if plausible {
                return Some(String::from_utf8_lossy(&haystack[origin_at..marker_at]).into_owned());
            }
        }
        from = marker_at + 1;
    }
    None
}

fn subslice(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack.windows(needle.len()).position(|window| window == needle)
}

fn rsubslice(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack.windows(needle.len()).rposition(|window| window == needle)
}

fn run(argv: &[&str]) -> Option<String> {
    let output = Command::new(argv[0]).args(&argv[1..]).output().ok()?;
    if !output.status.success() {
        return None;
    }
    let text = String::from_utf8(output.stdout).ok()?;
    let trimmed = text.trim().to_string();
    (!trimmed.is_empty()).then_some(trimmed)
}

fn home_dir() -> Option<PathBuf> {
    std::env::var_os("HOME").map(PathBuf::from)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_token_is_found_under_its_wrapper() {
        let raw = r#"{"claudeAiOauth":{"accessToken":"at-1","refreshToken":"rt-1"}}"#;
        assert_eq!(token_from_json(raw).unwrap(), "at-1");
    }

    #[test]
    fn a_snake_case_spelling_is_accepted() {
        assert_eq!(token_from_json(r#"{"access_token":"at-2"}"#).unwrap(), "at-2");
    }

    #[test]
    fn an_empty_token_is_not_a_token() {
        // An empty string in the store means logged out, not "use empty".
        assert!(token_from_json(r#"{"claudeAiOauth":{"accessToken":""}}"#).is_none());
    }

    #[test]
    fn unparseable_credentials_yield_nothing_rather_than_panicking() {
        assert!(token_from_json("{not json").is_none());
    }

    #[test]
    fn an_origin_is_recovered_from_a_sibling_url() {
        let binary = b"\x00\x01garbage\x00https://api.example.test/api/oauth/profile\x00more";
        assert_eq!(find_origin(binary).unwrap(), "https://api.example.test");
    }

    #[test]
    fn a_full_usage_url_yields_the_same_origin() {
        let binary = b"xx https://api.example.test/api/oauth/usage yy";
        assert_eq!(find_origin(binary).unwrap(), "https://api.example.test");
    }

    #[test]
    fn a_marker_without_a_scheme_is_not_a_match() {
        assert!(find_origin(b"just /api/oauth/usage on its own").is_none());
    }

    #[test]
    fn a_hostless_url_is_rejected() {
        assert!(find_origin(b"https:///api/oauth/usage").is_none());
    }

    #[test]
    fn a_path_between_the_scheme_and_the_marker_is_rejected() {
        // Otherwise "https://a.test/x" followed later by the marker would splice
        // an origin out of two unrelated strings.
        assert!(find_origin(b"https://a.test/something/else\x00/api/oauth/usage").is_none());
    }

    #[test]
    fn the_scan_finds_an_origin_split_across_a_chunk_boundary() {
        // The seam is the whole reason for the overlap; without it a URL
        // straddling 1 MiB would be invisible.
        let directory = std::env::temp_dir().join(format!("headroom-scan-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        let path = directory.join("fake-binary");
        let url = b"https://api.example.test/api/oauth/usage";
        let mut bytes = vec![b'.'; (1 << 20) - 20];
        bytes.extend_from_slice(url);
        bytes.extend(std::iter::repeat_n(b'.', 4096));
        std::fs::write(&path, &bytes).unwrap();

        assert_eq!(scan_for_api_origin(&path).unwrap(), "https://api.example.test");
        std::fs::remove_dir_all(&directory).ok();
    }

    #[test]
    fn a_binary_with_no_marker_scans_to_nothing() {
        let directory = std::env::temp_dir().join(format!("headroom-none-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        let path = directory.join("fake-binary");
        std::fs::write(&path, vec![b'z'; 3 << 20]).unwrap();
        assert!(scan_for_api_origin(&path).is_none());
        std::fs::remove_dir_all(&directory).ok();
    }

    #[test]
    fn an_explicit_endpoint_skips_the_scan_entirely() {
        // SAFETY: single-threaded test; no other thread reads the environment.
        unsafe { std::env::set_var("HEADROOM_USAGE_ENDPOINT", "https://explicit.test/usage") };
        assert_eq!(usage_endpoint().unwrap(), "https://explicit.test/usage");
        unsafe { std::env::remove_var("HEADROOM_USAGE_ENDPOINT") };
    }
}
