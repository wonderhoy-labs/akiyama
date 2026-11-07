//! The Akiyama command-line tool: shares a network between this computer and a plugged-in Android
//! phone, in either direction.

mod adb;
#[cfg(not(windows))]
mod aoa;
#[cfg(not(windows))]
mod client;
#[cfg(not(windows))]
mod probe;
mod server;
#[cfg(not(windows))]
mod tunnel;

use akiyama_core::proto::{DEFAULT_DNS, DEFAULT_MTU};
use anyhow::Result;
use clap::{Args, Parser, Subcommand};
use std::net::{Ipv4Addr, SocketAddr};
use tracing_subscriber::EnvFilter;

#[derive(Parser)]
#[command(name = "akiyama", version, about = "Full-duplex networking over USB")]
struct Cli {
    /// Log more. Repeat for even more.
    #[arg(short, long, action = clap::ArgAction::Count, global = true)]
    verbose: u8,

    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Share this machine's network with any Android phone plugged in over USB
    Server(ServerArgs),
    /// Get networking from a plugged-in Android phone. Needs root
    #[cfg(not(windows))]
    Client(ClientArgs),
    /// Connect to a phone that's sharing its network and check the link, without changing anything
    #[cfg(not(windows))]
    Probe(ProbeArgs),
    /// List the USB devices Akiyama could talk to
    Devices,
}

#[derive(Args)]
pub struct ServerArgs {
    /// Send the phone's traffic through this network device or TUN, like xray0 (Linux and macOS)
    #[arg(long, value_name = "DEVICE")]
    dev: Option<String>,

    /// The resolver the phone uses for DNS
    #[arg(long, value_name = "IPV4", default_value_t = DEFAULT_DNS)]
    dns: Ipv4Addr,

    /// Keep the phone off private addresses, like this machine's local network
    #[arg(long)]
    no_lan: bool,

    /// Connect through `adb` instead of as a USB accessory. This needs USB debugging on the phone,
    /// and it's the only way on Windows
    #[arg(long, default_value_t = cfg!(windows), conflicts_with = "dev_tcp")]
    adb: bool,

    /// Only serve the phone with this serial number
    #[arg(long, value_name = "SERIAL")]
    serial: Option<String>,

    /// The tunnel's MTU. Bigger is faster
    #[arg(long, default_value_t = DEFAULT_MTU, value_parser = clap::value_parser!(u16).range(1280..=65000))]
    mtu: u16,

    /// Listen on TCP instead of USB. For development: anyone who can connect gets in
    #[arg(long, value_name = "ADDRESS", hide = true)]
    dev_tcp: Option<SocketAddr>,
}

#[cfg(not(windows))]
#[derive(Args)]
pub struct ClientArgs {
    /// Use this TUN device for the USB network instead of creating one
    #[arg(long, value_name = "DEVICE")]
    dev: Option<String>,

    /// Don't send this machine's traffic into the tunnel. Add your own routes to use it
    #[arg(long)]
    no_routes: bool,

    /// Don't point this machine's DNS at the tunnel
    #[arg(long)]
    no_dns: bool,

    /// Only connect to the phone with this serial number
    #[arg(long, value_name = "SERIAL")]
    serial: Option<String>,

    /// Exit when the link drops instead of reconnecting
    #[arg(long)]
    once: bool,
}

#[cfg(not(windows))]
#[derive(Args)]
pub struct ProbeArgs {
    /// Only connect to the phone with this serial number
    #[arg(long, value_name = "SERIAL")]
    serial: Option<String>,

    /// How many pings to send
    #[arg(long, default_value_t = 5)]
    count: u32,

    /// Connect over TCP instead of USB, to a debug build of the app. For development
    #[arg(long, value_name = "ADDRESS", hide = true)]
    dev_tcp: Option<String>,
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();

    let default_level = match cli.verbose {
        0 => "akiyama=info,akiyama_core=info,warn",
        1 => "akiyama=debug,akiyama_core=debug,warn",
        _ => "akiyama=trace,akiyama_core=trace,warn",
    };
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new(default_level)))
        .with_target(false)
        .init();

    match cli.command {
        Command::Server(args) => server::run(args).await,
        #[cfg(not(windows))]
        Command::Client(args) => client::run(args).await,
        #[cfg(not(windows))]
        Command::Probe(args) => probe::run(args).await,
        #[cfg(not(windows))]
        Command::Devices => aoa::print_devices().await,
        #[cfg(windows)]
        Command::Devices => adb::print_devices().await,
    }
}

/// Resolves when the user asks the program to stop.
pub async fn shutdown_signal() {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{SignalKind, signal};
        let mut terminate = signal(SignalKind::terminate()).expect("couldn't listen for SIGTERM");
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {}
            _ = terminate.recv() => {}
        }
    }
    #[cfg(not(unix))]
    {
        let _ = tokio::signal::ctrl_c().await;
    }
}
