//! An opt-in local record of what the status line reported.
//!
//! Off unless `--log` names a file. Nothing here is ever sent anywhere: the
//! point is that the series the relay throws away can be kept at the machine
//! that produced it, by someone who asked for it, and looked at with whatever
//! reads a CSV.
//!
//! The file is its own memory. A row is appended only when a figure changed,
//! and the last line is what that is compared against, so keeping the log
//! costs no state of its own.

use std::path::Path;

const HEADER: &str = "at,session_percent,session_resets_at,week_percent,week_resets_at";

/// Append a row unless it would repeat the figures already on the last line.
///
/// Best effort throughout: this runs inside the detached push, where a
/// failure to write a log the user asked for must not cost them the reading
/// they actually wanted.
pub fn append(path: &Path, body: &str, now: u64) {
    let Some(figures) = figures(body) else { return };
    if last_figures(path).as_deref() == Some(figures.as_str()) {
        return;
    }
    let mut line = String::new();
    if !path.exists() {
        line.push_str(HEADER);
        line.push('\n');
    }
    line.push_str(&format!("{now},{figures}\n"));

    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let _ = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .and_then(|mut file| std::io::Write::write_all(&mut file, line.as_bytes()));
}

/// The four figures as they appear after the timestamp, or `None` when the
/// body carries neither window worth recording.
fn figures(body: &str) -> Option<String> {
    let parsed: serde_json::Value = serde_json::from_str(body).ok()?;
    let limits = parsed.get("limits")?.as_array()?;
    let of =
        |kind: &str| limits.iter().find(|e| e.get("kind").and_then(|k| k.as_str()) == Some(kind));
    let cell = |entry: Option<&serde_json::Value>, field: &str| -> String {
        entry
            .and_then(|e| e.get(field))
            .filter(|value| !value.is_null())
            .map(|value| value.to_string())
            .unwrap_or_default()
    };
    let session = of("session");
    let weekly = of("weekly_all");
    if session.is_none() && weekly.is_none() {
        return None;
    }
    Some(format!(
        "{},{},{},{}",
        cell(session, "percent"),
        cell(session, "resets_at"),
        cell(weekly, "percent"),
        cell(weekly, "resets_at"),
    ))
}

fn last_figures(path: &Path) -> Option<String> {
    let contents = std::fs::read_to_string(path).ok()?;
    let last = contents.lines().rfind(|line| !line.is_empty())?;
    let (_, figures) = last.split_once(',')?;
    Some(figures.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    const BODY: &str = r#"{"limits":[
        {"kind":"session","percent":41.6,"resets_at":1788000000},
        {"kind":"weekly_all","percent":12.0,"resets_at":1788400000}
    ]}"#;

    fn temp(name: &str) -> PathBuf {
        let path = std::env::temp_dir().join(format!(
            "headroom-log-{}-{:?}-{name}",
            std::process::id(),
            std::thread::current().id()
        ));
        std::fs::remove_file(&path).ok();
        path
    }

    #[test]
    fn a_row_carries_both_windows_and_their_reset_times() {
        assert_eq!(figures(BODY).unwrap(), "41.6,1788000000,12.0,1788400000");
    }

    #[test]
    fn a_body_with_no_windows_is_not_worth_a_row() {
        assert!(figures(r#"{"limits":[]}"#).is_none());
    }

    #[test]
    fn a_missing_window_leaves_its_columns_empty_rather_than_zero() {
        // Zero is a real utilisation. An absent window has to look absent, or
        // a gap in the record reads as a week that was never touched.
        let only_session = r#"{"limits":[{"kind":"session","percent":41.6,"resets_at":1}]}"#;
        assert_eq!(figures(only_session).unwrap(), "41.6,1,,");
    }

    #[test]
    fn the_first_row_is_preceded_by_a_header() {
        let path = temp("header");
        append(&path, BODY, 1_000);
        let written = std::fs::read_to_string(&path).unwrap();
        assert_eq!(written.lines().next().unwrap(), HEADER);
        assert_eq!(written.lines().count(), 2);
        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn unchanged_figures_do_not_earn_a_row() {
        // The status line fires several times a minute; only a change is worth
        // recording, and the file's own last line is what says whether one
        // happened.
        let path = temp("unchanged");
        append(&path, BODY, 1_000);
        append(&path, BODY, 1_060);
        assert_eq!(std::fs::read_to_string(&path).unwrap().lines().count(), 2);
        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn a_changed_figure_earns_a_row() {
        let path = temp("changed");
        append(&path, BODY, 1_000);
        append(&path, &BODY.replace("41.6", "43.1"), 1_060);
        let written = std::fs::read_to_string(&path).unwrap();
        assert_eq!(written.lines().count(), 3);
        assert!(written.lines().last().unwrap().starts_with("1060,43.1,"));
        std::fs::remove_file(&path).ok();
    }
}
