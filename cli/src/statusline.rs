//! The Claude Code status line payload, translated into the usage wire shape.
//!
//! Claude Code hands its status line command a JSON object on stdin, and that
//! object already carries the two windows the app draws largest: the five-hour
//! session and the seven-day week. They arrive on a response the user paid for
//! anyway, so reporting them costs no request at all.
//!
//! These two windows are all there is. The per-model weekly windows are not in
//! this payload and have no supported source, so Headroom does not show them.

use serde::Deserialize;
use serde_json::{Value, json};

#[derive(Debug, Default, Deserialize)]
pub struct StatusLine {
    #[serde(default)]
    pub rate_limits: Option<RateLimits>,
}

#[derive(Debug, Default, Deserialize)]
pub struct RateLimits {
    #[serde(default)]
    pub five_hour: Option<Window>,
    #[serde(default)]
    pub seven_day: Option<Window>,
}

#[derive(Debug, Default, Deserialize)]
pub struct Window {
    /// `used_percentage` is what the status line sends. The alias is defensive:
    /// the usage endpoint calls the same quantity `utilization`, and a future
    /// build unifying them should not blank the screen.
    #[serde(alias = "utilization", alias = "percent")]
    pub used_percentage: Option<f64>,
    /// Epoch seconds here, an ISO-8601 string on the usage endpoint. Both are
    /// passed through untouched because the app's parser accepts either.
    pub resets_at: Option<i64>,
}

/// Build the body to push, or `None` when there is nothing worth pushing.
///
/// `None` rather than an empty list on purpose: an empty `limits` array would
/// overwrite a good reading on the relay with a blank one every time Claude
/// Code omitted the field.
pub fn to_usage(status: &StatusLine) -> Option<Value> {
    let limits = status.rate_limits.as_ref()?;
    let mut entries = Vec::new();
    for (window, kind, group) in
        [(&limits.five_hour, "session", "session"), (&limits.seven_day, "weekly_all", "weekly")]
    {
        let Some(window) = window else { continue };
        let Some(percent) = window.used_percentage else { continue };
        entries.push(json!({
            "kind": kind,
            "group": group,
            "percent": percent,
            "severity": "normal",
            "resets_at": window.resets_at,
            "scope": Value::Null,
            // The session window is the one Claude Code is spending against
            // right now; the app highlights it on that basis.
            "is_active": kind == "session",
        }));
    }
    if entries.is_empty() {
        return None;
    }
    Some(json!({ "limits": entries }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(raw: &str) -> StatusLine {
        serde_json::from_str(raw).unwrap()
    }

    /// Shaped after a payload captured from a real session.
    const REAL: &str = r#"{
        "model": {"display_name": "Fable", "id": "claude-fable-5-1"},
        "workspace": {"current_dir": "/Users/x/code/headroom"},
        "rate_limits": {
            "five_hour":  {"used_percentage": 41.6, "resets_at": 1788000000},
            "seven_day":  {"used_percentage": 12.0, "resets_at": 1788400000}
        }
    }"#;

    #[test]
    fn both_windows_become_limits() {
        let usage = to_usage(&parse(REAL)).unwrap();
        let limits = usage["limits"].as_array().unwrap();
        assert_eq!(limits.len(), 2);
        assert_eq!(limits[0]["kind"], "session");
        assert_eq!(limits[0]["percent"], 41.6);
        assert_eq!(limits[0]["resets_at"], 1_788_000_000i64);
        assert_eq!(limits[1]["kind"], "weekly_all");
    }

    #[test]
    fn only_the_session_window_is_marked_active() {
        let usage = to_usage(&parse(REAL)).unwrap();
        assert_eq!(usage["limits"][0]["is_active"], true);
        assert_eq!(usage["limits"][1]["is_active"], false);
    }

    #[test]
    fn unknown_fields_are_ignored() {
        // The status line schema is Claude Code's, not ours, and it grows.
        let usage = to_usage(&parse(
            r#"{"cost":{"total_cost_usd":1.5},"rate_limits":{"five_hour":{"used_percentage":1.0,"resets_at":1,"something_new":true}}}"#,
        ));
        assert_eq!(usage.unwrap()["limits"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn a_payload_without_rate_limits_pushes_nothing() {
        // Older Claude Code builds, and API-key users, have no limits to report.
        // Pushing an empty list would blank a good reading on the relay.
        assert!(to_usage(&parse(r#"{"model":{"display_name":"Fable"}}"#)).is_none());
    }

    #[test]
    fn a_window_without_a_percentage_is_skipped_not_zeroed() {
        let usage = to_usage(&parse(
            r#"{"rate_limits":{"five_hour":{"resets_at":1},"seven_day":{"used_percentage":9.0,"resets_at":2}}}"#,
        ))
        .unwrap();
        let limits = usage["limits"].as_array().unwrap();
        assert_eq!(limits.len(), 1, "a missing percentage must not read as 0%");
        assert_eq!(limits[0]["kind"], "weekly_all");
    }

    #[test]
    fn a_missing_reset_time_still_reports_the_percentage() {
        // The bar matters more than the countdown; the app renders 0 as "no
        // reset time known" rather than dropping the bucket.
        let usage =
            to_usage(&parse(r#"{"rate_limits":{"five_hour":{"used_percentage":50.0}}}"#)).unwrap();
        assert_eq!(usage["limits"][0]["resets_at"], Value::Null);
    }

    #[test]
    fn the_endpoint_spelling_of_a_percentage_is_accepted_too() {
        let usage =
            to_usage(&parse(r#"{"rate_limits":{"five_hour":{"utilization":33.0}}}"#)).unwrap();
        assert_eq!(usage["limits"][0]["percent"], 33.0);
    }
}
