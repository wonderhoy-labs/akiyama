//! An Akiyama server and client in one process, joined by an in-memory pipe instead of USB.
//!
//! The client looks up one.one.one.one by sending a DNS query through the link as an IP packet.
//! The server's NAT sends it on from an ordinary UDP socket, and the answer comes back the same
//! way. It needs an internet connection:
//!
//! ```bash
//! cargo run -p akiyama-core --example in_process
//! ```

use akiyama_core::dns::build_udp;
use akiyama_core::nat::NatConfig;
use akiyama_core::proto::DEFAULT_DNS;
use akiyama_core::session::{SessionEvent, Stats, client_handshake, pump, serve_link};
use bytes::Bytes;
use std::io;
use std::net::Ipv4Addr;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::{mpsc, oneshot};
use tokio::time::timeout;

const SOURCE_PORT: u16 = 40_000;

#[tokio::main]
async fn main() -> io::Result<()> {
	// Any reliable, ordered byte stream will do. Akiyama itself uses a USB accessory's bulk endpoints.
	let (server_end, client_end) = tokio::io::duplex(256 * 1024);

	// The server: one call per link, serving one session after another until it's stopped.
	let (stop_server, stopped) = oneshot::channel::<()>();
	let (server_reader, server_writer) = tokio::io::split(server_end);
	let config = Arc::new(NatConfig::new(DEFAULT_DNS));
	let server = tokio::spawn(serve_link(
		server_reader,
		server_writer,
		config,
		async {
			let _ = stopped.await;
		},
		|event| match event {
			SessionEvent::Started => println!("Server: a session started"),
			SessionEvent::Ended(stats, error) => println!(
				"Server: the session ended after {} bytes in and {} out{}",
				stats.bytes_received.load(std::sync::atomic::Ordering::Relaxed),
				stats.bytes_sent.load(std::sync::atomic::Ordering::Relaxed),
				error.map(|error| format!(", because {error}")).unwrap_or_default(),
			),
			SessionEvent::Refused(why) => println!("Server: refused a client, because {why}"),
		},
	));

	// The client: a handshake, then packets both ways until the outbound queue closes.
	let (mut reader, mut writer) = tokio::io::split(client_end);
	let welcome = client_handshake(&mut reader, &mut writer).await?;
	println!("Joined as {}, with an MTU of {}", welcome.client_v4, welcome.mtu);
	let (to_server, mut outbound) = mpsc::channel::<Bytes>(16);
	let (deliver, mut from_server) = mpsc::unbounded_channel::<Bytes>();
	let session = welcome.session;
	let client = tokio::spawn(async move {
		let on_packet = move |packet: Bytes| {
			let _ = deliver.send(packet);
		};
		let mut stop = std::pin::pin!(std::future::pending::<()>());
		let end =
			pump(&mut reader, &mut writer, &mut outbound, on_packet, session, Arc::new(Stats::default()), &mut stop)
				.await;
		// Handing the halves back keeps the link open, the way a USB cable stays plugged in between sessions.
		(end, reader, writer)
	});

	let sent = Instant::now();
	let query = dns_query("one.one.one.one");
	let packet = build_udp(welcome.client_v4, SOURCE_PORT, welcome.upstream_dns, 53, &query);
	to_server.send(Bytes::from(packet)).await.map_err(|_| io::Error::other("the link closed"))?;
	let reply = timeout(Duration::from_secs(5), from_server.recv())
		.await
		.map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "no answer. Is this computer online?"))?
		.ok_or_else(|| io::Error::other("the link closed"))?;
	match first_a_record(&reply) {
		Some(address) => println!("one.one.one.one is {address}, answered in {:.0?}", sent.elapsed()),
		None => println!("The answer had no address in it"),
	}

	// Closing the outbound queue ends the client's session with a goodbye. The server then waits for
	// the next session on the same link, so it has to be stopped too. That has to happen before the
	// link closes, because to the server a closed link means the cable came out, which is an error.
	drop(to_server);
	let (end, reader, writer) = client.await.map_err(io::Error::other)?;
	println!("Client: the session ended with {:?}", end?);
	let _ = stop_server.send(());
	server.await.map_err(io::Error::other)??;
	drop((reader, writer));
	Ok(())
}

/// A DNS question for the IPv4 address of `name`.
fn dns_query(name: &str) -> Vec<u8> {
	let mut query = vec![0x5a, 0x4b, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
	for label in name.split('.') {
		query.push(label.len() as u8);
		query.extend_from_slice(label.as_bytes());
	}
	query.extend_from_slice(&[0, 0, 1, 0, 1]);
	query
}

/// The first IPv4 address in the DNS answer inside an IPv4 UDP packet.
fn first_a_record(packet: &[u8]) -> Option<Ipv4Addr> {
	let header = usize::from(packet.first()? & 0x0f) * 4;
	let message = packet.get(header + 8..)?;
	// Skip the question, which is the name in labels, then its type and class.
	let mut at = 12;
	while *message.get(at)? != 0 {
		at += 1 + usize::from(message[at]);
	}
	at += 5;
	let answers = u16::from_be_bytes([*message.get(6)?, *message.get(7)?]);
	for _ in 0..answers {
		// Each answer's name is a two-byte pointer back to the question's.
		let kind = u16::from_be_bytes([*message.get(at + 2)?, *message.get(at + 3)?]);
		let length = usize::from(u16::from_be_bytes([*message.get(at + 10)?, *message.get(at + 11)?]));
		let data = message.get(at + 12..at + 12 + length)?;
		if kind == 1 && length == 4 {
			return Some(Ipv4Addr::new(data[0], data[1], data[2], data[3]));
		}
		at += 12 + length;
	}
	None
}
