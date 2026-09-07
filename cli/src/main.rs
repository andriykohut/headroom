//! Headroom's desktop and server side.
//!
//! One binary, three jobs:
//!
//! - `push` runs as a Claude Code status line hook and reports your usage.
//! - `serve` is the relay: it keeps the last reading and hands it to the phone.
//! - `link` prints the QR code that points the phone at your relay.
//!
//! The shape is `machine --push--> relay <--poll-- phone`, and the relay is not
//! optional: a phone on a mobile network has no address anything can push to.
//! What the relay does *not* do is hold a credential - see `relay.rs`.

mod claude;
mod link;
mod payload;
mod push;
mod relay;
mod statusline;

use clap::{Parser, Subcommand};
use std::path::PathBuf;

#[derive(Parser)]
#[command(name = "headroom", version, about = "Report Claude Code usage to your phone")]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Report usage from a Claude Code status line. Reads the status line
    /// payload on stdin and writes it back out unchanged, so it composes with
    /// whatever you already render.
    ///
    /// Add it to ~/.claude/statusline.sh as the first thing that runs:
    ///
    ///   input=$(cat)
    ///   printf '%s' "$input" | headroom push
    ///   ... your existing status line, using "$input" ...
    #[command(verbatim_doc_comment)]
    Push {
        /// Relay to report to. Defaults to $HEADROOM_RELAY_URL.
        #[arg(long, env = "HEADROOM_RELAY_URL")]
        relay: Option<String>,
        /// Shared secret. Defaults to $HEADROOM_RELAY_SECRET.
        #[arg(long, env = "HEADROOM_RELAY_SECRET", hide_env_values = true)]
        secret: Option<String>,
        /// Read the secret from a file instead, so it is not in your shell history.
        #[arg(long)]
        secret_file: Option<PathBuf>,
        /// Seconds between full fetches of the per-model windows.
        #[arg(long, env = "HEADROOM_ENRICH_INTERVAL", default_value_t = push::DEFAULT_ENRICH_INTERVAL)]
        interval: u64,
        /// Do the work here and report it, instead of detaching. For checking
        /// that your setup works; never use it in a real status line.
        #[arg(long)]
        once: bool,
        /// Internal: the detached half of a normal run.
        #[arg(long, hide = true)]
        deliver: Option<String>,
    },

    /// Run the relay. Keeps the last reading pushed to it and serves it to the
    /// phone. Holds no credential and never contacts the provider.
    ///
    /// Two keys, ideally. The machine you code on needs to write and never
    /// read; your phone needs to read and never write. Giving each only what
    /// it needs means a photographed QR code leaks your usage figures without
    /// letting anyone forge them, and either key can be rotated on its own.
    /// A single --secret-file uses one key for both: simpler, and weaker.
    #[command(verbatim_doc_comment)]
    Serve {
        /// Bind address. The default is loopback because this speaks plain
        /// HTTP; put a TLS terminator in front of it. The environment is
        /// read too, so a container image can change the default without
        /// an argument an operator's own arguments would replace.
        #[arg(long, env = "HEADROOM_HOST", default_value = "127.0.0.1")]
        host: String,
        #[arg(long, default_value_t = 8765)]
        port: u16,
        /// One key for both reading and writing.
        #[arg(long, env = "HEADROOM_RELAY_SECRET", hide_env_values = true)]
        secret: Option<String>,
        /// The same, from a file, so it is not in your shell history.
        #[arg(long)]
        secret_file: Option<PathBuf>,
        /// The key the machine you code on pushes with. Write only.
        #[arg(long)]
        push_secret_file: Option<PathBuf>,
        /// The key your phone reads with. Read only - it cannot overwrite a
        /// reading, so a lost phone cannot feed you false numbers.
        #[arg(long)]
        read_secret_file: Option<PathBuf>,
        /// Where to keep the last reading across restarts.
        #[arg(long)]
        state: Option<PathBuf>,
        /// Connections served at once. Past this, new ones wait in the
        /// kernel's queue rather than each costing memory.
        #[arg(long, default_value_t = relay::DEFAULT_MAX_CONNECTIONS)]
        max_connections: usize,
        /// Seconds a connection may take to send its request headers, which
        /// is also how long an idle keep-alive connection is kept. Lower it if
        /// you are not behind a proxy that already does this.
        #[arg(long, default_value_t = relay::HEADER_TIMEOUT.as_secs())]
        header_timeout: u64,
        /// Print a fresh secret and exit. Run it twice for two keys.
        #[arg(long)]
        new_secret: bool,
    },

    /// Print a QR code that points the Headroom app at your relay.
    ///
    /// Give it the *read* key. The phone never needs to write, and a code that
    /// cannot write is one you can hold up to a camera in a room with other
    /// people in it.
    #[command(verbatim_doc_comment)]
    Link {
        #[arg(long, env = "HEADROOM_RELAY_URL")]
        relay: String,
        #[arg(long, env = "HEADROOM_RELAY_SECRET", hide_env_values = true)]
        secret: Option<String>,
        /// The relay's read key.
        #[arg(long, alias = "read-secret-file")]
        secret_file: Option<PathBuf>,
        /// Print the raw payload instead of a QR code, for manual paste.
        #[arg(long)]
        text: bool,
    },
}

fn main() {
    std::process::exit(dispatch());
}

fn dispatch() -> i32 {
    match Cli::parse().command {
        Commands::Push { relay, secret, secret_file, interval, once, deliver } => {
            let interval = interval.max(push::MIN_ENRICH_INTERVAL);
            let config = match (relay, read_secret(secret, secret_file)) {
                (Some(relay), Some(secret)) => Some(push::Config { relay, secret, interval }),
                // Not configured yet. The status line still has to work, so
                // this is silent rather than an error on every prompt.
                _ => None,
            };
            match (deliver, config) {
                (Some(body), Some(config)) => push::deliver_detached(&config, &body),
                (Some(_), None) => 0,
                (None, config) => push::run(config, once),
            }
        }

        Commands::Serve {
            host,
            port,
            secret,
            secret_file,
            push_secret_file,
            read_secret_file,
            state,
            max_connections,
            header_timeout,
            new_secret,
        } => {
            if new_secret {
                return match relay::new_secret() {
                    Ok(secret) => {
                        println!("{secret}");
                        0
                    }
                    Err(error) => fail(&error),
                };
            }
            let shared = read_secret(secret, secret_file);
            let write = read_secret(None, push_secret_file).or_else(|| shared.clone());
            let read = read_secret(None, read_secret_file).or_else(|| shared.clone());
            let (Some(write), Some(read)) = (write, read) else {
                return fail(
                    "no shared secret: set HEADROOM_RELAY_SECRET, or pass --secret-file, \
                     or pass both --push-secret-file and --read-secret-file. Generate \
                     keys with --new-secret.",
                );
            };
            if write == read {
                // Not refused - one key is a legitimate simple setup - but it
                // is the weaker one, and worth saying once rather than never.
                eprintln!(
                    "headroom: one key for both reading and writing. Pass \
                     --push-secret-file and --read-secret-file to give your phone a key \
                     that cannot overwrite readings.",
                );
            }
            let state = state.unwrap_or_else(default_relay_state);
            eprintln!("headroom: serving on http://{host}:{port}/usage");
            match relay::serve(
                relay::Relay::new(read, write, state),
                &host,
                port,
                max_connections,
                std::time::Duration::from_secs(header_timeout.max(1)),
            ) {
                Ok(()) => 0,
                Err(error) => fail(&error),
            }
        }

        Commands::Link { relay, secret, secret_file, text } => {
            let Some(secret) = read_secret(secret, secret_file) else {
                return fail("no shared secret: set HEADROOM_RELAY_SECRET or pass --secret-file.");
            };
            match link::run(&relay, &secret, text) {
                Ok(()) => 0,
                Err(error) => fail(&error),
            }
        }
    }
}

/// A file wins over the flag: passing a secret on a command line puts it in
/// your shell history and in every `ps` listing on the machine.
fn read_secret(inline: Option<String>, file: Option<PathBuf>) -> Option<String> {
    if let Some(path) = file {
        return std::fs::read_to_string(path)
            .ok()
            .map(|raw| raw.trim().to_string())
            .filter(|secret| !secret.is_empty());
    }
    inline.filter(|secret| !secret.is_empty())
}

fn default_relay_state() -> PathBuf {
    let home = std::env::var_os("HOME").map(PathBuf::from).unwrap_or_else(std::env::temp_dir);
    home.join(".local/state/headroom/relay.json")
}

fn fail(message: &str) -> i32 {
    eprintln!("headroom: {message}");
    1
}
