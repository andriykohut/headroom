//! Pair a phone with a relay by showing it a QR code.
//!
//! What the code carries is the relay's URL and its shared secret - not a
//! provider credential. Someone who photographs your screen learns how much of
//! your quota you have used, and can write readings to your relay. That is
//! worth protecting, and it is not your account.

use crate::payload::{self, Pairing};
use qrcode::QrCode;
use qrcode::render::unicode;
use std::io::IsTerminal;
use std::process::Command;

pub fn run(relay: &str, secret: &str, as_text: bool) -> Result<(), String> {
    let pairing = Pairing::for_relay(relay, secret);
    let encoded = payload::encode(&pairing);
    // Check the code we are about to ask someone to scan actually says what we
    // meant. Finding out here costs nothing; finding out with a phone already
    // pointed at the screen costs a puzzled ten minutes.
    match payload::decode(&encoded) {
        Ok(round_tripped) if round_tripped == pairing => {}
        _ => return Err("the pairing code did not survive a round trip; this is a bug".into()),
    }

    if as_text {
        println!("Paste this into Headroom's import screen:\n");
        println!("{encoded}");
        return Ok(());
    }

    let code = QrCode::new(encoded.as_bytes())
        .map_err(|error| format!("could not build a QR code: {error}"))?;
    // Error correction stays at the default. Raising it grows the code, and a
    // code too wide for the window is unreadable in a way a lower correction
    // level never is.
    let drawing = code.render::<unicode::Dense1x2>().quiet_zone(true).build();
    warn_if_too_narrow(&drawing);
    println!("{drawing}");
    println!("Scan this in Headroom to point the app at your relay.");
    Ok(())
}

/// A QR wider than the window wraps, and a wrapped QR looks right and does not
/// scan. Saying so is cheap; letting someone photograph a broken code is not.
fn warn_if_too_narrow(drawing: &str) {
    let needed = drawing.lines().map(|line| line.chars().count()).max().unwrap_or(0);
    let Some(columns) = terminal_width() else {
        eprintln!(
            "headroom: this QR needs {needed} columns. Output is not going to a \
             terminal, so its width cannot be checked - if the window showing \
             this is narrower, the code wraps and will not scan. Widen it, or \
             use --text and paste instead."
        );
        return;
    };
    if needed > columns {
        eprintln!(
            "headroom: warning - this QR needs {needed} columns but the terminal \
             is {columns} wide, so it will wrap and will not scan. Widen the \
             window, reduce the font size, or use --text and paste it."
        );
    }
}

/// The width of the window this will be looked at in, when that is knowable.
///
/// Under a pipe, a redirect, or a tool that runs the command on someone's
/// behalf, there is no window to measure. Returning `None` there is deliberate:
/// a confident warning about a window nobody is looking at is worse than
/// admitting the width is unknown.
fn terminal_width() -> Option<usize> {
    if !std::io::stdout().is_terminal() {
        return None;
    }
    let output = Command::new("tput").arg("cols").output().ok()?;
    String::from_utf8(output.stdout).ok()?.trim().parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_printed_code_decodes_to_the_relay() {
        let encoded = payload::encode(&Pairing::for_relay("https://relay.test", "s3cret"));
        let decoded = payload::decode(&encoded).unwrap();
        assert_eq!(decoded.usage_endpoint, "https://relay.test/usage");
        assert_eq!(decoded.access_token, "s3cret");
    }

    #[test]
    fn a_real_pairing_still_fits_in_a_qr_code() {
        // Capacity is the constraint that decides the error correction level;
        // a secret is short, but the URL is not bounded.
        let long = format!("https://{}.example.test/headroom", "a".repeat(180));
        let encoded = payload::encode(&Pairing::for_relay(&long, &"s".repeat(64)));
        assert!(QrCode::new(encoded.as_bytes()).is_ok());
    }
}
