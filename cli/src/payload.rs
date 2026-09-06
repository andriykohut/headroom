//! Pairing codec shared with the Headroom Android app.
//!
//! Wire format: `headroom1:` + base64url(zlib(json)), unpadded. The prefix lets
//! the app reject foreign QR codes; zlib's adler32 catches corruption.
//!
//! The six fields are unchanged from when the phone talked to the provider
//! directly, and that is deliberate: the app still just sends `access_token` as
//! a bearer token to `usage_endpoint`. Pointing it at a relay is a change of
//! address, not a change of protocol, so no app release is needed.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use flate2::Compression;
use flate2::read::ZlibDecoder;
use flate2::write::ZlibEncoder;
use serde::{Deserialize, Serialize};
use std::fmt;
use std::io::{Read, Write};

pub const SCHEME: &str = "headroom1:";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Pairing {
    pub access_token: String,
    pub refresh_token: String,
    pub expires_at: i64,
    pub client_id: String,
    pub token_endpoint: String,
    pub usage_endpoint: String,
}

/// A relay's secret does not expire, so the app must never try to refresh it.
/// The app only refreshes on a 401, which a relay does not return - but a far
/// future expiry makes the intent explicit rather than incidental.
const NEVER: i64 = 4_102_444_800; // 2100-01-01

impl Pairing {
    /// Point a phone at a relay.
    ///
    /// The two token fields are unused and say so. `client_id` and
    /// `token_endpoint` exist only because the app's decoder requires all six
    /// keys; nothing reads them on this path.
    pub fn for_relay(relay_url: &str, secret: &str) -> Self {
        let base = relay_url.trim_end_matches('/');
        Pairing {
            access_token: secret.to_string(),
            refresh_token: "unused-by-relay".to_string(),
            expires_at: NEVER,
            client_id: "relay".to_string(),
            token_endpoint: format!("{base}/usage"),
            usage_endpoint: format!("{base}/usage"),
        }
    }
}

#[derive(Debug)]
pub struct PayloadError(String);

impl fmt::Display for PayloadError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for PayloadError {}

pub fn encode(pairing: &Pairing) -> String {
    let body = serde_json::to_vec(pairing).expect("Pairing is always serialisable");
    let mut encoder = ZlibEncoder::new(Vec::new(), Compression::best());
    encoder.write_all(&body).expect("writing to a Vec cannot fail");
    let packed = encoder.finish().expect("writing to a Vec cannot fail");
    format!("{SCHEME}{}", URL_SAFE_NO_PAD.encode(packed))
}

pub fn decode(payload: &str) -> Result<Pairing, PayloadError> {
    let packed = payload
        .strip_prefix(SCHEME)
        .ok_or_else(|| PayloadError("not a Headroom payload".into()))?;
    // Encoders differ on whether they pad; accept both rather than reject a
    // code that is otherwise perfectly readable.
    let compressed = URL_SAFE_NO_PAD
        .decode(packed.trim_end_matches('='))
        .map_err(|_| PayloadError("payload is corrupted: not base64url".into()))?;
    let mut body = Vec::new();
    ZlibDecoder::new(&compressed[..])
        .read_to_end(&mut body)
        .map_err(|_| PayloadError("payload is corrupted: bad compressed data".into()))?;
    serde_json::from_slice(&body).map_err(|error| {
        // serde names the missing field, which is the useful half. It never
        // quotes a value, so this cannot carry a token into an error message.
        PayloadError(format!("payload is not a valid pairing: {error}"))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Pairing {
        Pairing {
            access_token: "at-123".into(),
            refresh_token: "rt-456".into(),
            expires_at: 1_787_262_000,
            client_id: "cid-789".into(),
            token_endpoint: "https://example.test/oauth/token".into(),
            usage_endpoint: "https://example.test/api/oauth/usage".into(),
        }
    }

    #[test]
    fn round_trip_preserves_every_field() {
        assert_eq!(decode(&encode(&sample())).unwrap(), sample());
    }

    #[test]
    fn the_token_is_not_greppable_in_the_payload() {
        assert!(!encode(&sample()).contains("at-123"));
    }

    #[test]
    fn a_foreign_payload_is_rejected() {
        assert!(decode("otherapp1:abcdef").unwrap_err().to_string().contains("not a Headroom"));
    }

    #[test]
    fn a_corrupted_body_is_rejected() {
        let payload = encode(&sample());
        let corrupted = format!("{}AAAA", &payload[..payload.len() - 4]);
        assert!(decode(&corrupted).is_err());
    }

    #[test]
    fn a_payload_missing_a_field_is_rejected() {
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::best());
        encoder.write_all(br#"{"access_token":"a"}"#).unwrap();
        let packed = URL_SAFE_NO_PAD.encode(encoder.finish().unwrap());
        let error = decode(&format!("{SCHEME}{packed}")).unwrap_err().to_string();
        assert!(error.contains("refresh_token"), "{error}");
    }

    /// Guards the wire contract shared with the Android app. If this fails, the
    /// format changed and the app can no longer read codes from this tool.
    ///
    /// The fixture is checked by decoding rather than by re-encoding: zlib
    /// output is implementation-defined, so this Rust encoder need not emit the
    /// same bytes the Python one did - only bytes the app can read.
    #[test]
    fn the_golden_fixture_still_decodes() {
        let golden = include_str!("../../fixtures/payload_golden.txt").trim();
        assert_eq!(decode(golden).unwrap(), sample());
    }

    #[test]
    fn a_relay_pairing_points_both_endpoints_at_the_relay() {
        let pairing = Pairing::for_relay("https://relay.test/", "s3cret");
        assert_eq!(pairing.access_token, "s3cret");
        assert_eq!(pairing.usage_endpoint, "https://relay.test/usage");
        assert!(pairing.expires_at > 4_000_000_000, "the app must never try to refresh this");
    }
}
