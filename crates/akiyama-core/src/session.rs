//! Runs the two ends of a link, once a transport has produced a byte stream.

use crate::dns::{self, DnsResolver};
use crate::nat::{LINK_QUEUE_PACKETS, Nat, NatConfig};
use crate::proto::{self, ByeReason, Frame, HELLO_LEN, MAGIC, RESOLVER_V4, VERSION, Welcome};
use bytes::{Bytes, BytesMut};
use std::future::Future;
use std::io;
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use tokio::time::{MissedTickBehavior, interval, sleep, timeout};
use tracing::{debug, trace};

const PING_INTERVAL: Duration = Duration::from_secs(2);
/// A cable either works or it doesn't, so a few missed pings means something has gone wrong.
const LINK_TIMEOUT: Duration = Duration::from_secs(8);
const WATCHDOG_INTERVAL: Duration = Duration::from_secs(1);
const HELLO_INTERVAL: Duration = Duration::from_secs(1);
/// Frames are gathered up to about this size before writing, so a burst becomes one USB transfer.
const WRITE_BATCH_BYTES: usize = 128 * 1024;

/// Counters for one phone, safe to read from anywhere.
#[derive(Debug, Default)]
pub struct Stats {
    pub bytes_sent: AtomicU64,
    pub bytes_received: AtomicU64,
    /// The latest ping time in microseconds, or zero before the first reply.
    pub rtt_micros: AtomicU32,
}

/// How a session ended without anything failing.
#[derive(Debug, PartialEq, Eq)]
pub enum SessionEnd {
    /// The phone said goodbye. It may come back on the same link.
    Goodbye,
    /// The app restarted, and this is its new session ID.
    NewSession(u64),
    /// The server is stopping.
    Stopped,
}

/// Waits for a phone's hello, and returns its session ID.
///
/// This scans the stream byte by byte rather than reading frames. A USB link outlives the app on it,
/// so an app killed partway through a write leaves half a frame behind for the next one to get past.
pub async fn wait_for_hello<R, W>(reader: &mut R, writer: &mut W) -> io::Result<u64>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let pattern = [1, 0, HELLO_LEN as u8, MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3]];
    let mut matched = 0;
    while matched < pattern.len() {
        let byte = reader.read_u8().await?;
        matched = if byte == pattern[matched] { matched + 1 } else { usize::from(byte == pattern[0]) };
    }
    let version = reader.read_u8().await?;
    let session = reader.read_u64().await?;
    if version != VERSION {
        let _ = proto::write_frame(writer, &Frame::Bye(ByeReason::VersionMismatch)).await;
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("the phone speaks protocol {version}, and this is {VERSION}. Update the older one"),
        ));
    }
    Ok(session)
}

/// Serves one link for as long as it lasts. A phone can come and go on it, since a USB accessory
/// stays connected while the app restarts, so this keeps waiting for the next hello.
pub async fn serve_link<R, W>(
    mut reader: R,
    mut writer: W,
    config: Arc<NatConfig>,
    stop: impl Future<Output = ()>,
    mut on_session: impl FnMut(SessionEvent),
) -> io::Result<()>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    tokio::pin!(stop);
    // If the phone's app is still running from before this server started, this ends that session so
    // that it starts a new one. And if a thread there is stuck reading from the link, this frees it.
    // That matters because Android won't reopen a USB accessory until the old read has returned, and
    // the phone has no way of cancelling one.
    proto::write_frame(&mut writer, &Frame::Bye(ByeReason::Normal)).await?;
    let mut next_session = None;
    loop {
        let session = match next_session.take() {
            Some(session) => session,
            None => tokio::select! {
                () = &mut stop => return Ok(()),
                result = wait_for_hello(&mut reader, &mut writer) => match result {
                    Ok(session) => session,
                    // A phone running a different version can still be updated and come back.
                    Err(error) if error.kind() == io::ErrorKind::InvalidData => {
                        on_session(SessionEvent::Refused(error.to_string()));
                        continue;
                    }
                    Err(error) => return Err(error),
                },
            },
        };
        let welcome = Welcome::new(session, config.mtu, config.upstream_dns);
        proto::write_frame(&mut writer, &Frame::Welcome(welcome)).await?;
        on_session(SessionEvent::Started);

        let mut nat = Nat::start(config.clone());
        let input = nat.input();
        let mut output = nat.take_output().expect("a new Nat still has its output");
        let stats = Arc::new(Stats::default());
        let on_packet = |packet| input.send(packet);
        let result = pump(&mut reader, &mut writer, &mut output, on_packet, session, stats.clone(), &mut stop).await;
        drop(nat);

        match result {
            Ok(SessionEnd::Stopped) => {
                on_session(SessionEvent::Ended(stats, None));
                return Ok(());
            }
            Ok(SessionEnd::Goodbye) => on_session(SessionEvent::Ended(stats, None)),
            Ok(SessionEnd::NewSession(session)) => {
                on_session(SessionEvent::Ended(stats, None));
                next_session = Some(session);
            }
            Err(error) => {
                // These mean the app went away or the stream got confused, and a new hello fixes both.
                let recoverable = matches!(error.kind(), io::ErrorKind::TimedOut | io::ErrorKind::InvalidData);
                on_session(SessionEvent::Ended(stats, Some(error.to_string())));
                if !recoverable {
                    return Err(error);
                }
            }
        }
    }
}

/// What's happening on a link, for whoever's reporting on it.
pub enum SessionEvent {
    Started,
    Ended(Arc<Stats>, Option<String>),
    Refused(String),
}

/// Moves frames in both directions at once until the session ends. Arriving packets go to
/// `on_packet`, which must not block.
pub async fn pump<R, W, F>(
    reader: &mut R,
    writer: &mut W,
    outbound: &mut mpsc::Receiver<Bytes>,
    mut on_packet: F,
    session: u64,
    stats: Arc<Stats>,
    stop: &mut (impl Future<Output = ()> + Unpin),
) -> io::Result<SessionEnd>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
    F: FnMut(Bytes),
{
    let (control_tx, mut control_rx) = mpsc::channel::<Frame>(8);
    let last_heard = Mutex::new(Instant::now());
    let epoch = Instant::now();

    let read_loop = async {
        loop {
            let frame = proto::read_frame(reader).await?;
            *last_heard.lock().unwrap() = Instant::now();
            match frame {
                Frame::Packet(packet) => {
                    stats.bytes_received.fetch_add(packet.len() as u64, Ordering::Relaxed);
                    on_packet(packet);
                }
                Frame::Ping(nonce) => {
                    let _ = control_tx.try_send(Frame::Pong(nonce));
                }
                Frame::Pong(sent_at_micros) => {
                    let now = epoch.elapsed().as_micros() as u64;
                    stats.rtt_micros.store(now.saturating_sub(sent_at_micros).max(1) as u32, Ordering::Relaxed);
                }
                Frame::Bye(reason) => {
                    debug!(?reason, "the phone said goodbye");
                    return io::Result::Ok(SessionEnd::Goodbye);
                }
                Frame::Hello { session: other, .. } if other != session => {
                    debug!("the app restarted");
                    return Ok(SessionEnd::NewSession(other));
                }
                // The phone repeats its hello until it's answered, so one more may arrive.
                Frame::Hello { .. } | Frame::Welcome(_) => {}
            }
        }
    };

    let write_loop = async {
        let mut ticker = interval(PING_INTERVAL);
        ticker.set_missed_tick_behavior(MissedTickBehavior::Delay);
        let mut batch = BytesMut::with_capacity(WRITE_BATCH_BYTES * 2);
        loop {
            tokio::select! {
                biased;
                Some(frame) = control_rx.recv() => frame.encode(&mut batch),
                _ = ticker.tick() => {
                    Frame::Ping(epoch.elapsed().as_micros() as u64).encode(&mut batch);
                }
                packet = outbound.recv() => match packet {
                    Some(packet) => push_packet(&mut batch, packet, &stats),
                    None => return io::Result::Ok(SessionEnd::Goodbye),
                },
            }
            // Take whatever else is ready, so a burst of packets goes out as one write.
            while batch.len() < WRITE_BATCH_BYTES {
                match outbound.try_recv() {
                    Ok(packet) => push_packet(&mut batch, packet, &stats),
                    Err(_) => break,
                }
            }
            writer.write_all(&batch).await?;
            writer.flush().await?;
            batch.clear();
        }
    };

    // This can't live in the write loop: with nobody reading on the phone, writes stop finishing, and
    // that's exactly when it's needed.
    let watchdog = async {
        loop {
            sleep(WATCHDOG_INTERVAL).await;
            if last_heard.lock().unwrap().elapsed() > LINK_TIMEOUT {
                return io::Result::<SessionEnd>::Err(io::Error::new(io::ErrorKind::TimedOut, "the phone went quiet"));
            }
        }
    };

    let end = tokio::select! {
        result = read_loop => result?,
        result = write_loop => result?,
        result = watchdog => result?,
        () = stop => SessionEnd::Stopped,
    };
    // A phone blocked reading from the link only finds out it can stop when something arrives, so a
    // goodbye gets one back.
    if !matches!(end, SessionEnd::NewSession(_)) {
        let _ = timeout(Duration::from_secs(1), proto::write_frame(writer, &Frame::Bye(ByeReason::Normal))).await;
    }
    Ok(end)
}

/// Says hello to whatever is on the other end of a link until it answers, and returns its welcome.
///
/// It repeats because a server may not be listening yet, like a phone whose app hasn't opened. This
/// also starts with a goodbye: if a thread on the phone is stuck reading from the link, that frees
/// it, and Android won't reopen a USB accessory until it has been.
pub async fn client_handshake<R, W>(reader: &mut R, writer: &mut W) -> io::Result<Welcome>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let session = new_session_id();
    proto::write_frame(writer, &Frame::Bye(ByeReason::Normal)).await?;
    let wait = wait_for_welcome(reader, session);
    tokio::pin!(wait);
    let mut ticker = interval(HELLO_INTERVAL);
    loop {
        tokio::select! {
            result = &mut wait => return result,
            _ = ticker.tick() => proto::write_frame(writer, &Frame::Hello { version: VERSION, session }).await?,
        }
    }
}

/// Scans for the answer to one particular hello. Whatever comes before it is left over from earlier,
/// and it may not even start at the beginning of a frame.
async fn wait_for_welcome<R: AsyncRead + Unpin>(reader: &mut R, session: u64) -> io::Result<Welcome> {
    let id = session.to_be_bytes();
    let pattern = [2, 0, proto::WELCOME_LEN as u8, id[0], id[1], id[2], id[3], id[4], id[5], id[6], id[7]];
    let mut matched = 0;
    while matched < pattern.len() {
        let byte = reader.read_u8().await?;
        matched = if byte == pattern[matched] { matched + 1 } else { usize::from(byte == pattern[0]) };
    }
    let mut payload = vec![0; proto::WELCOME_LEN];
    payload[..8].copy_from_slice(&id);
    reader.read_exact(&mut payload[8..]).await?;
    match Frame::decode(2, Bytes::from(payload))? {
        Some(Frame::Welcome(welcome)) => Ok(welcome),
        _ => Err(io::Error::new(io::ErrorKind::InvalidData, "couldn't read the welcome")),
    }
}

/// A session ID different from any an earlier run used. It only has to tell runs apart.
fn new_session_id() -> u64 {
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default();
    now.as_nanos() as u64 ^ (u64::from(std::process::id()) << 32)
}

/// A client's TUN device, kept open across links so that routes survive a reconnection.
pub struct ClientDevice {
    resolver: Arc<Mutex<DnsResolver>>,
    from_device: mpsc::Receiver<Bytes>,
    to_device: mpsc::Sender<Bytes>,
    tasks: [JoinHandle<io::Result<()>>; 2],
}

impl ClientDevice {
    /// Starts moving packets to and from a device, which reads and writes one IP packet at a time.
    pub fn new<T>(device: T, welcome: &Welcome) -> Self
    where
        T: AsyncRead + AsyncWrite + Send + 'static,
    {
        let resolver = Arc::new(Mutex::new(DnsResolver::new(welcome.gateway_v4, RESOLVER_V4, welcome.upstream_dns)));
        let (to_link, from_device) = mpsc::channel::<Bytes>(LINK_QUEUE_PACKETS);
        let (to_device, mut device_queue) = mpsc::channel::<Bytes>(LINK_QUEUE_PACKETS);
        let (mut device_reader, mut device_writer) = tokio::io::split(device);

        let reader_resolver = resolver.clone();
        let reader_to_device = to_device.clone();
        let mtu = usize::from(welcome.mtu);
        let read_task = tokio::spawn(async move {
            let mut buffer = vec![0; mtu + 64];
            loop {
                let len = device_reader.read(&mut buffer).await?;
                if len == 0 {
                    return Ok(());
                }
                let packet = &buffer[..len];
                let outcome = reader_resolver.lock().unwrap().outgoing(packet, Instant::now());
                // Waiting for the link here would queue up packets in the kernel for no benefit, whereas
                // dropping tells TCP to slow down.
                let sent = match outcome {
                    dns::Outgoing::Pass => to_link.try_send(Bytes::copy_from_slice(packet)),
                    dns::Outgoing::Query(query) => to_link.try_send(Bytes::from(query)),
                    dns::Outgoing::Reply(reply) => {
                        let _ = reader_to_device.try_send(Bytes::from(reply));
                        Ok(())
                    }
                    dns::Outgoing::Drop => Ok(()),
                };
                match sent {
                    Err(mpsc::error::TrySendError::Closed(_)) => return Ok(()),
                    Err(mpsc::error::TrySendError::Full(_)) => trace!("link queue full, dropping a packet"),
                    Ok(()) => {}
                }
            }
        });
        let write_task = tokio::spawn(async move {
            while let Some(packet) = device_queue.recv().await {
                device_writer.write_all(&packet).await?;
            }
            Ok(())
        });

        ClientDevice { resolver, from_device, to_device, tasks: [read_task, write_task] }
    }
}

impl Drop for ClientDevice {
    fn drop(&mut self) {
        for task in &self.tasks {
            task.abort();
        }
    }
}

/// Connects a device to a link until it ends or `stop` resolves. The handshake has already happened.
pub async fn run_client<R, W>(
    reader: &mut R,
    writer: &mut W,
    device: &mut ClientDevice,
    welcome: &Welcome,
    stats: Arc<Stats>,
    stop: &mut (impl Future<Output = ()> + Unpin),
) -> io::Result<SessionEnd>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    device.resolver.lock().unwrap().set_upstream(welcome.upstream_dns);
    let resolver = device.resolver.clone();
    let to_device = device.to_device.clone();
    let on_packet = move |packet: Bytes| {
        let outcome = resolver.lock().unwrap().incoming(&packet, Instant::now());
        let packet = match outcome {
            dns::Incoming::Pass => packet,
            dns::Incoming::Reply(reply) => Bytes::from(reply),
            dns::Incoming::Drop => return,
        };
        if to_device.try_send(packet).is_err() {
            trace!("device queue full, dropping a packet");
        }
    };
    // Packets that piled up while there was no link are long out of date.
    while device.from_device.try_recv().is_ok() {}
    pump(reader, writer, &mut device.from_device, on_packet, welcome.session, stats, stop).await
}

fn push_packet(batch: &mut BytesMut, packet: Bytes, stats: &Stats) {
    stats.bytes_sent.fetch_add(packet.len() as u64, Ordering::Relaxed);
    Frame::Packet(packet).encode(batch);
}
