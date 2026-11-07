//! `akiyama server`: finds phones, and gives each one its own NAT for as long as it's plugged in.

#[cfg(not(windows))]
use crate::aoa;
use crate::{ServerArgs, adb, shutdown_signal};
use akiyama_core::nat::NatConfig;
use akiyama_core::session::{SessionEvent, serve_link};
use anyhow::{Context, Result};
#[cfg(not(windows))]
use nusb::DeviceId;
#[cfg(not(windows))]
use std::collections::HashMap;
use std::collections::HashSet;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncWrite, BufReader};
use tokio::net::TcpListener;
use tokio::sync::watch;
#[cfg(not(windows))]
use tokio::task::JoinHandle;
use tokio::task::JoinSet;
use tracing::{debug, info, warn};

#[cfg(not(windows))]
const SCAN_INTERVAL: Duration = Duration::from_millis(500);
/// A phone whose app is reconnecting says hello every second, so this is plenty for it to answer.
#[cfg(not(windows))]
const TAKE_OVER_WAIT: Duration = Duration::from_secs(3);
const ADB_SCAN_INTERVAL: Duration = Duration::from_secs(2);
const TCP_READ_BUFFER_BYTES: usize = 256 * 1024;

pub async fn run(args: ServerArgs) -> Result<()> {
    if args.dev.is_some() && cfg!(windows) {
        anyhow::bail!("--dev only works on Linux and macOS");
    }
    let mut config = NatConfig::new(args.dns);
    config.device = args.dev.clone();
    config.block_lan = args.no_lan;
    config.mtu = args.mtu;
    config.check_device()?;
    let config = Arc::new(config);

    match &args.dev {
        Some(device) => info!(%device, dns = %args.dns, "the phone's traffic goes through this device"),
        None => info!(dns = %args.dns, "the phone's traffic follows this machine's routes"),
    }

    let (stop_tx, stop_rx) = watch::channel(false);
    tokio::spawn(async move {
        shutdown_signal().await;
        info!("stopping");
        let _ = stop_tx.send(true);
    });

    if let Some(address) = args.dev_tcp {
        let listener = TcpListener::bind(address).await.context("couldn't listen on that address")?;
        warn!(%address, "listening on TCP with no pairing. Anyone who can connect gets in");
        serve_tcp(listener, config, stop_rx).await
    } else if args.adb {
        serve_adb(config, args.serial, stop_rx).await
    } else {
        #[cfg(not(windows))]
        return serve_usb(config, args.serial, stop_rx).await;
        #[cfg(windows)]
        anyhow::bail!("Windows can only connect through adb");
    }
}

async fn stopped(mut stop: watch::Receiver<bool>) {
    let _ = stop.wait_for(|stopped| *stopped).await;
}

/// Finishes after `wait` if nothing has answered by then, and otherwise never does.
#[cfg(not(windows))]
async fn unanswered_for(wait: Duration, answered: &AtomicBool) {
    tokio::time::sleep(wait).await;
    if answered.load(Ordering::Relaxed) {
        std::future::pending::<()>().await;
    }
}

async fn serve<R, W>(
    reader: R,
    writer: W,
    peer: String,
    config: Arc<NatConfig>,
    stop: watch::Receiver<bool>,
    answered: Arc<AtomicBool>,
) where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let name = peer.clone();
    let report = move |event: SessionEvent| match event {
        SessionEvent::Started => {
            answered.store(true, Ordering::Relaxed);
            info!(phone = %name, "connected");
        }
        SessionEvent::Ended(stats, problem) => {
            let sent = stats.bytes_sent.load(Ordering::Relaxed);
            let received = stats.bytes_received.load(Ordering::Relaxed);
            match problem {
                Some(error) => warn!(phone = %name, sent, received, %error, "the session failed"),
                None => info!(phone = %name, sent, received, "disconnected"),
            }
        }
        SessionEvent::Refused(error) => warn!(phone = %name, "{error}"),
    };
    if let Err(error) = serve_link(reader, writer, config, stopped(stop), report).await {
        debug!(phone = %peer, %error, "the link ended");
    }
}

/// Watches for phones on USB, and switches each new one into accessory mode.
#[cfg(not(windows))]
async fn serve_usb(config: Arc<NatConfig>, serial: Option<String>, mut stop: watch::Receiver<bool>) -> Result<()> {
    info!("waiting for an Android phone. Plug one in with a USB cable, then allow Akiyama on it");
    let mut sessions: HashMap<DeviceId, JoinHandle<()>> = HashMap::new();
    let mut already_asked: HashSet<DeviceId> = HashSet::new();
    let mut already_warned: HashSet<DeviceId> = HashSet::new();
    let mut first_look = true;

    while !*stop.borrow() {
        let devices: Vec<_> = nusb::list_devices().await.context("couldn't list USB devices")?.collect();
        let present: HashSet<DeviceId> = devices.iter().map(|info| info.id()).collect();
        sessions.retain(|id, task| present.contains(id) && !task.is_finished());
        already_asked.retain(|id| present.contains(id));
        already_warned.retain(|id| present.contains(id));

        for info in &devices {
            if serial.as_deref().is_some_and(|wanted| info.serial_number() != Some(wanted)) {
                continue;
            }
            let id = info.id();
            if aoa::is_accessory(info) {
                if sessions.contains_key(&id) {
                    continue;
                }
                let was_already_there = first_look;
                match aoa::open_accessory(info).await {
                    Ok(accessory) => {
                        info!(phone = %aoa::describe(info), "found a phone. Waiting for the Akiyama app");
                        let peer = aoa::describe(info);
                        let config = config.clone();
                        let stop = stop.clone();
                        let phone = info.clone();
                        sessions.insert(
                            id,
                            tokio::spawn(async move {
                                let aoa::Accessory { reader, writer } = accessory;
                                let answered = Arc::new(AtomicBool::new(false));
                                let serving = serve(reader, writer, peer, config, stop, answered.clone());
                                // A phone that was already in accessory mode might be there for
                                // something else, like sharing its own network. If it doesn't say
                                // hello, it gets asked again, with the right question this time.
                                if was_already_there {
                                    tokio::select! {
                                        () = serving => {}
                                        () = unanswered_for(TAKE_OVER_WAIT, &answered) => {
                                            debug!("it isn't saying hello, so asking it to switch");
                                            aoa::ask_again(&phone, aoa::Role::Server).await;
                                        }
                                    }
                                } else {
                                    serving.await;
                                }
                            }),
                        );
                    }
                    // Telling someone once is enough, and a phone that's mid-switch isn't a problem.
                    Err(error) if aoa::vanished(&error) => debug!("{error:#}"),
                    Err(error) if already_warned.insert(id) => warn!("{error:#}"),
                    Err(_) => {}
                }
            } else if aoa::might_be_a_phone(info) && already_asked.insert(id) {
                match aoa::switch_to_accessory(info, aoa::Role::Server).await {
                    Ok(true) => debug!(device = %aoa::describe(info), "asked it to switch to accessory mode"),
                    Ok(false) => debug!(device = %aoa::describe(info), "it doesn't speak the accessory protocol"),
                    Err(error) => warn!(device = %aoa::describe(info), "{error:#}"),
                }
            }
        }

        first_look = false;
        tokio::select! {
            () = tokio::time::sleep(SCAN_INTERVAL) => {}
            _ = stop.changed() => {}
        }
    }

    for (_, session) in sessions {
        let _ = session.await;
    }
    Ok(())
}

/// Reaches phones through `adb reverse`, which ends up as TCP connections to a local port.
async fn serve_adb(config: Arc<NatConfig>, serial: Option<String>, mut stop: watch::Receiver<bool>) -> Result<()> {
    let adb = adb::find_adb()?;
    let listener = TcpListener::bind(("127.0.0.1", 0)).await.context("couldn't open a local port")?;
    let port = listener.local_addr()?.port();
    info!("waiting for an Android phone with USB debugging on. Plug one in, then tap Use USB debugging in the app");

    let mut sessions = JoinSet::new();
    let mut known: HashSet<String> = HashSet::new();
    let mut scan = tokio::time::interval(ADB_SCAN_INTERVAL);
    while !*stop.borrow() {
        tokio::select! {
            _ = stop.changed() => {}
            _ = scan.tick() => {
                let mut phones = adb::devices(&adb).await?;
                phones.retain(|phone| serial.as_deref().is_none_or(|wanted| phone == wanted));
                known.retain(|phone| phones.contains(phone));
                for phone in phones {
                    if known.contains(&phone) {
                        continue;
                    }
                    match adb::connect_phone(&adb, &phone, port).await {
                        Ok(()) => {
                            info!(%phone, "found a phone. Waiting for the Akiyama app");
                            known.insert(phone);
                        }
                        Err(error) => warn!(%phone, "{error:#}"),
                    }
                }
            }
            accepted = listener.accept() => {
                let (stream, address) = accepted.context("couldn't accept a connection")?;
                spawn_tcp_session(&mut sessions, stream, address.to_string(), &config, &stop);
            }
            Some(_) = sessions.join_next() => {}
        }
    }
    while sessions.join_next().await.is_some() {}
    for phone in &known {
        adb::disconnect_phone(&adb, phone).await;
    }
    Ok(())
}

async fn serve_tcp(listener: TcpListener, config: Arc<NatConfig>, mut stop: watch::Receiver<bool>) -> Result<()> {
    let mut sessions = JoinSet::new();
    while !*stop.borrow() {
        tokio::select! {
            _ = stop.changed() => {}
            accepted = listener.accept() => {
                let (stream, address) = accepted.context("couldn't accept a connection")?;
                spawn_tcp_session(&mut sessions, stream, address.to_string(), &config, &stop);
            }
            Some(_) = sessions.join_next() => {}
        }
    }
    while sessions.join_next().await.is_some() {}
    Ok(())
}

fn spawn_tcp_session(
    sessions: &mut JoinSet<()>,
    stream: tokio::net::TcpStream,
    peer: String,
    config: &Arc<NatConfig>,
    stop: &watch::Receiver<bool>,
) {
    let _ = stream.set_nodelay(true);
    let (reader, writer) = stream.into_split();
    let reader = BufReader::with_capacity(TCP_READ_BUFFER_BYTES, reader);
    sessions.spawn(serve(reader, writer, peer, config.clone(), stop.clone(), Arc::default()));
}
