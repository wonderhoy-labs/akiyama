//! `akiyama probe`: checks a link end to end, with no TUN device and no root.

use crate::{ProbeArgs, aoa};
use akiyama_core::checksum;
use akiyama_core::dns::build_udp;
use akiyama_core::proto::{RESOLVER_V4, Welcome};
use akiyama_core::session::{Stats, client_handshake, pump};
use anyhow::{Context, Result, bail};
use bytes::Bytes;
use std::net::Ipv4Addr;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::time::timeout;

const REPLY_TIMEOUT: Duration = Duration::from_secs(5);
const PING_ID: u16 = 0x414b;

pub async fn run(args: ProbeArgs) -> Result<()> {
    let started = Instant::now();
    if let Some(address) = &args.dev_tcp {
        let stream = TcpStream::connect(address).await.with_context(|| format!("couldn't connect to {address}"))?;
        stream.set_nodelay(true)?;
        let (mut reader, mut writer) = stream.into_split();
        let welcome = client_handshake(&mut reader, &mut writer).await.context("the handshake failed")?;
        return check(reader, writer, address, welcome, args.count, started).await;
    }
    println!("Looking for a phone. If one's plugged in, tap Share in the Akiyama app");
    let (accessory, phone, welcome) = aoa::connect_to_phone(args.serial.as_deref(), true).await?;
    let aoa::Accessory { reader, writer } = accessory;
    check(reader, writer, &phone, welcome, args.count, started).await
}

/// Pings the far end and asks it for a DNS answer, over a link that's already said hello.
async fn check<R, W>(
    mut reader: R,
    mut writer: W,
    phone: &str,
    welcome: Welcome,
    count: u32,
    started: Instant,
) -> Result<()>
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin + Send + 'static,
{
    println!("Connected to {phone} in {:.1} s", started.elapsed().as_secs_f32());
    println!("Address {}, DNS through {}, MTU {}", welcome.client_v4, welcome.upstream_dns, welcome.mtu);

    let (to_link, mut outbound) = mpsc::channel::<Bytes>(16);
    let (delivered, mut inbound) = mpsc::unbounded_channel::<Bytes>();
    let session = welcome.session;
    let pump_task = tokio::spawn(async move {
        let on_packet = move |packet: Bytes| {
            let _ = delivered.send(packet);
        };
        let mut stop = std::pin::pin!(std::future::pending::<()>());
        pump(&mut reader, &mut writer, &mut outbound, on_packet, session, Arc::new(Stats::default()), &mut stop).await
    });

    let mut times = Vec::new();
    for sequence in 0..count as u16 {
        let sent = Instant::now();
        to_link.send(gateway_ping(&welcome, sequence)).await?;
        let reply =
            timeout(REPLY_TIMEOUT, next_matching(&mut inbound, |packet| is_ping_reply(packet, &welcome, sequence)));
        match reply.await {
            Ok(Some(())) => {
                let millis = sent.elapsed().as_secs_f32() * 1000.0;
                println!("Ping {}: {millis:.1} ms", sequence + 1);
                times.push(millis);
            }
            Ok(None) => bail!("the link closed"),
            Err(_) => println!("Ping {}: no reply", sequence + 1),
        }
    }
    if times.is_empty() {
        bail!("the phone never answered a ping, so the link isn't carrying traffic");
    }
    let average = times.iter().sum::<f32>() / times.len() as f32;
    println!("Answered {} of {count} pings, averaging {average:.1} ms", times.len());

    // A real lookup, sent the way a client's resolver sends one. Getting an answer shows the phone's
    // internet connection works too.
    let source_port = 4242;
    let query = dns_query(0x414b, "one.one.one.one");
    to_link.send(Bytes::from(build_udp(RESOLVER_V4, source_port, welcome.upstream_dns, 53, &query))).await?;
    let answer = timeout(REPLY_TIMEOUT, next_matching(&mut inbound, |packet| first_a_record(packet, source_port)));
    match answer.await {
        Ok(Some(address)) => println!("DNS through the phone works: one.one.one.one is {address}"),
        Ok(None) => bail!("the link closed"),
        Err(_) => bail!("DNS through the phone got no answer. Does it have internet access?"),
    }

    drop(to_link);
    let _ = timeout(Duration::from_secs(2), pump_task).await;
    Ok(())
}

async fn next_matching<T>(
    inbound: &mut mpsc::UnboundedReceiver<Bytes>,
    mut check: impl FnMut(&[u8]) -> Option<T>,
) -> Option<T> {
    while let Some(packet) = inbound.recv().await {
        if let Some(found) = check(&packet) {
            return Some(found);
        }
    }
    None
}

fn gateway_ping(welcome: &Welcome, sequence: u16) -> Bytes {
    let mut icmp = vec![8, 0, 0, 0];
    icmp.extend_from_slice(&PING_ID.to_be_bytes());
    icmp.extend_from_slice(&sequence.to_be_bytes());
    icmp.extend_from_slice(b"akiyama probe");
    let sum = checksum::of(&icmp);
    icmp[2..4].copy_from_slice(&sum.to_be_bytes());

    let total = 20 + icmp.len();
    let mut packet = vec![0; total];
    packet[0] = 0x45;
    packet[2..4].copy_from_slice(&(total as u16).to_be_bytes());
    packet[8] = 64;
    packet[9] = 1;
    packet[12..16].copy_from_slice(&welcome.client_v4.octets());
    packet[16..20].copy_from_slice(&welcome.gateway_v4.octets());
    let sum = checksum::of(&packet[..20]);
    packet[10..12].copy_from_slice(&sum.to_be_bytes());
    packet[20..].copy_from_slice(&icmp);
    Bytes::from(packet)
}

fn is_ping_reply(packet: &[u8], welcome: &Welcome, sequence: u16) -> Option<()> {
    let header_len = usize::from(packet.first()? & 0x0f) * 4;
    let icmp = packet.get(header_len..)?;
    let matches = packet.get(9) == Some(&1)
        && packet.get(12..16)? == welcome.gateway_v4.octets()
        && icmp.first() == Some(&0)
        && icmp.get(4..6)? == PING_ID.to_be_bytes()
        && icmp.get(6..8)? == sequence.to_be_bytes();
    matches.then_some(())
}

fn dns_query(id: u16, name: &str) -> Vec<u8> {
    let mut query = id.to_be_bytes().to_vec();
    query.extend_from_slice(&[0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0]);
    for label in name.split('.') {
        query.push(label.len() as u8);
        query.extend_from_slice(label.as_bytes());
    }
    query.extend_from_slice(&[0, 0, 1, 0, 1]);
    query
}

/// Reads the first IPv4 address out of the DNS reply to our query, if this packet is that reply.
fn first_a_record(packet: &[u8], source_port: u16) -> Option<Ipv4Addr> {
    if packet.first()? >> 4 != 4 || *packet.get(9)? != 17 {
        return None;
    }
    let header_len = usize::from(packet[0] & 0x0f) * 4;
    let udp = packet.get(header_len..)?;
    if u16::from_be_bytes([*udp.get(2)?, *udp.get(3)?]) != source_port {
        return None;
    }
    let message = udp.get(8..)?;
    let answers = u16::from_be_bytes([*message.get(6)?, *message.get(7)?]);

    let mut offset = 12;
    while *message.get(offset)? != 0 {
        offset += 1 + usize::from(message[offset]);
    }
    offset += 5;
    for _ in 0..answers {
        // Names in answers are nearly always a two-byte pointer.
        offset += if message.get(offset)? & 0xc0 == 0xc0 { 2 } else { return None };
        let record = message.get(offset..offset + 10)?;
        let kind = u16::from_be_bytes([record[0], record[1]]);
        let len = usize::from(u16::from_be_bytes([record[8], record[9]]));
        offset += 10;
        if kind == 1 && len == 4 {
            let octets = message.get(offset..offset + 4)?;
            return Some(Ipv4Addr::new(octets[0], octets[1], octets[2], octets[3]));
        }
        offset += len;
    }
    None
}
