//! `serve` takes its bind address from the environment as well as the flag,
//! because a container cannot pass the flag reliably: an operator's own
//! arguments replace the image's CMD and would silently drop it.

use std::process::Command;

#[test]
fn the_environment_can_set_the_bind_address() {
    let output = Command::new(env!("CARGO_BIN_EXE_headroom"))
        .args(["serve", "--secret", "s3cret", "--port", "8765"])
        .env("HEADROOM_HOST", "203.0.113.1") // TEST-NET-3: never local
        .output()
        .expect("spawn headroom serve");

    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        stderr.contains("203.0.113.1"),
        "the address from the environment never reached the bind: {stderr}"
    );
}

#[test]
fn the_flag_still_wins_over_the_environment() {
    let output = Command::new(env!("CARGO_BIN_EXE_headroom"))
        .args(["serve", "--secret", "s3cret", "--port", "8765", "--host", "203.0.113.2"])
        .env("HEADROOM_HOST", "203.0.113.1")
        .output()
        .expect("spawn headroom serve");

    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(stderr.contains("203.0.113.2"), "the flag was ignored: {stderr}");
    assert!(!stderr.contains("203.0.113.1"), "the environment overrode the flag: {stderr}");
}
