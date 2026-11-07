//! Runs a real server session over an in-memory link, with a hand-driven client on the other end.
//! The client's packets go through the real NAT to real sockets on the loopback interface.

use akiyama_core::nat::{Nat, NatConfig};
use akiyama_core::proto::{self, CLIENT_V4, DEFAULT_DNS, DEFAULT_MTU, Frame, GATEWAY_V4, VERSION, Welcome};
use akiyama_core::session::{self, SessionEvent};
use bytes::Bytes;
use etherparse::{IpNumber, Ipv4Header, PacketBuilder, SlicedPacket, TcpHeader, TransportSlice};
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt, DuplexStream, duplex};
use tokio::net::{TcpListener, UdpSocket};
use tokio::sync::{mpsc, oneshot};
use tokio::time::timeout;

const WAIT: Duration = Duration::from_secs(10);
const SESSION: u64 = 0x1122334455667788;

struct Client {
    to_link: mpsc::Sender<Bytes>,
    from_link: mpsc::UnboundedReceiver<Bytes>,
    welcome: Welcome,
    stop: Option<oneshot::Sender<()>>,
    server: tokio::task::JoinHandle<std::io::Result<()>>,
}

/// A stand-in for the phone: it does the handshake, then moves frames with a simple pair of tasks.
async fn connect(config: NatConfig) -> Client {
    let (client_end, server_end) = duplex(1024 * 1024);
    let (stop, stopped) = oneshot::channel::<()>();
    let server = spawn_server(server_end, config, async move {
        let _ = stopped.await;
    });
    let (welcome, to_link, from_link) = start_client(client_end).await;
    Client { to_link, from_link, welcome, stop: Some(stop), server }
}

fn spawn_server(
    stream: DuplexStream,
    config: NatConfig,
    stop: impl std::future::Future<Output = ()> + Send + 'static,
) -> tokio::task::JoinHandle<std::io::Result<()>> {
    let (reader, writer) = tokio::io::split(stream);
    tokio::spawn(session::serve_link(reader, writer, Arc::new(config), stop, |_: SessionEvent| {}))
}

async fn start_client(stream: DuplexStream) -> (Welcome, mpsc::Sender<Bytes>, mpsc::UnboundedReceiver<Bytes>) {
    let (mut reader, mut writer) = tokio::io::split(stream);
    proto::write_frame(&mut writer, &Frame::Hello { version: VERSION, session: SESSION }).await.unwrap();
    let welcome = next_welcome(&mut reader).await;
    let (to_link, mut outbound) = mpsc::channel::<Bytes>(64);
    let (delivered, from_link) = mpsc::unbounded_channel();
    tokio::spawn(async move {
        while let Some(packet) = outbound.recv().await {
            if proto::write_frame(&mut writer, &Frame::Packet(packet)).await.is_err() {
                return;
            }
        }
    });
    tokio::spawn(async move {
        while let Ok(frame) = proto::read_frame(&mut reader).await {
            if let Frame::Packet(packet) = frame {
                let _ = delivered.send(packet);
            }
        }
    });
    (welcome, to_link, from_link)
}

/// Builds an IPv4 UDP packet for the tests to send.
fn build_udp(src: Ipv4Addr, src_port: u16, dst: Ipv4Addr, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let mut packet = Vec::new();
    PacketBuilder::ipv4(src.octets(), dst.octets(), 64).udp(src_port, dst_port).write(&mut packet, payload).unwrap();
    packet
}

fn nat_for_tests() -> NatConfig {
    NatConfig { allow_loopback: true, ..NatConfig::new(DEFAULT_DNS) }
}

/// A very small TCP client: enough to open a connection, move data and close.
struct TcpProbe {
    src_port: u16,
    dst: SocketAddr,
    seq: u32,
    ack: u32,
}

impl TcpProbe {
    fn segment(&self, build: impl FnOnce(TcpHeader) -> TcpHeader, payload: &[u8]) -> Bytes {
        let SocketAddr::V4(dst) = self.dst else { panic!("IPv4 only") };
        let mut header = TcpHeader::new(self.src_port, dst.port(), self.seq, 64240);
        header.acknowledgment_number = self.ack;
        header = build(header);
        let mut ip = Ipv4Header::new(0, 64, IpNumber::TCP, CLIENT_V4.octets(), dst.ip().octets()).unwrap();
        ip.set_payload_len(header.header_len() + payload.len()).unwrap();
        header.checksum = header.calc_checksum_ipv4(&ip, payload).unwrap();
        let mut packet = Vec::new();
        ip.write(&mut packet).unwrap();
        header.write(&mut packet).unwrap();
        packet.extend_from_slice(payload);
        Bytes::from(packet)
    }
}

/// The next TCP segment from the link: its flags, sequence, acknowledgment and payload.
async fn next_tcp(client: &mut Client) -> (bool, bool, bool, u32, Vec<u8>) {
    loop {
        let packet = timeout(WAIT, client.from_link.recv()).await.expect("timed out waiting for TCP").unwrap();
        let sliced = SlicedPacket::from_ip(&packet).unwrap();
        if let Some(TransportSlice::Tcp(tcp)) = sliced.transport {
            return (tcp.syn(), tcp.fin(), tcp.rst(), tcp.sequence_number(), tcp.payload().to_vec());
        }
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn tcp_works_in_both_directions_at_once() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let target = listener.local_addr().unwrap();
    let echo = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut buffer = [0; 1024];
        let len = socket.read(&mut buffer).await.unwrap();
        socket.write_all(b"pong:").await.unwrap();
        socket.write_all(&buffer[..len]).await.unwrap();
        socket.shutdown().await.unwrap();
    });

    let mut client = connect(nat_for_tests()).await;
    let mut probe = TcpProbe { src_port: 40000, dst: target, seq: 1000, ack: 0 };

    let syn = probe.segment(
        |mut h| {
            h.syn = true;
            h
        },
        &[],
    );
    client.to_link.send(syn).await.unwrap();
    let (is_syn, _, _, server_seq, _) = next_tcp(&mut client).await;
    assert!(is_syn, "expected a SYN-ACK");
    probe.seq += 1;
    probe.ack = server_seq.wrapping_add(1);
    let ack = probe.segment(
        |mut h| {
            h.ack = true;
            h
        },
        &[],
    );
    client.to_link.send(ack).await.unwrap();

    let request = probe.segment(
        |mut h| {
            h.ack = true;
            h.psh = true;
            h
        },
        b"ping",
    );
    client.to_link.send(request).await.unwrap();
    probe.seq += 4;

    let mut received = Vec::new();
    loop {
        let (_, fin, rst, seq, payload) = next_tcp(&mut client).await;
        assert!(!rst, "the connection was reset");
        if !payload.is_empty() && seq == probe.ack {
            probe.ack = probe.ack.wrapping_add(payload.len() as u32);
            received.extend_from_slice(&payload);
            let ack = probe.segment(
                |mut h| {
                    h.ack = true;
                    h
                },
                &[],
            );
            client.to_link.send(ack).await.unwrap();
        }
        if fin {
            break;
        }
    }
    assert_eq!(received, b"pong:ping");
    echo.await.unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn off_limits_destinations_get_a_reset() {
    // Loopback is off limits with the default settings.
    let mut client = connect(NatConfig::new(DEFAULT_DNS)).await;
    let probe = TcpProbe { src_port: 40001, dst: "127.0.0.1:9".parse().unwrap(), seq: 1, ack: 0 };
    let syn = probe.segment(
        |mut h| {
            h.syn = true;
            h
        },
        &[],
    );
    client.to_link.send(syn).await.unwrap();

    let (is_syn, _, rst, ..) = next_tcp(&mut client).await;
    assert!(!is_syn, "the stack answered a connection it should have refused");
    assert!(rst, "expected a reset");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_client_learns_when_a_destination_refuses_the_connection() {
    // Find a port with nothing listening on it.
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let target = listener.local_addr().unwrap();
    drop(listener);

    let mut client = connect(nat_for_tests()).await;
    let probe = TcpProbe { src_port: 40002, dst: target, seq: 1, ack: 0 };
    let syn = probe.segment(
        |mut h| {
            h.syn = true;
            h
        },
        &[],
    );
    client.to_link.send(syn).await.unwrap();

    let (is_syn, _, rst, ..) = next_tcp(&mut client).await;
    assert!(!is_syn, "the client was told it connected");
    assert!(rst, "expected a reset");
}

/// A client that closes its sending side still has to get the answer, as with `nc` or an old
/// HTTP client.
#[tokio::test(flavor = "multi_thread")]
async fn a_half_closed_connection_still_gets_its_answer() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let target = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut request = Vec::new();
        socket.read_to_end(&mut request).await.unwrap();
        assert_eq!(request, b"question");
        socket.write_all(b"answer").await.unwrap();
        socket.shutdown().await.unwrap();
    });

    let mut client = connect(nat_for_tests()).await;
    let mut probe = TcpProbe { src_port: 40004, dst: target, seq: 500, ack: 0 };
    let syn = probe.segment(
        |mut h| {
            h.syn = true;
            h
        },
        &[],
    );
    client.to_link.send(syn).await.unwrap();
    let (is_syn, _, _, server_seq, _) = next_tcp(&mut client).await;
    assert!(is_syn);
    probe.seq += 1;
    probe.ack = server_seq.wrapping_add(1);

    let request = probe.segment(
        |mut h| {
            h.ack = true;
            h.psh = true;
            h
        },
        b"question",
    );
    client.to_link.send(request).await.unwrap();
    probe.seq += 8;
    let fin = probe.segment(
        |mut h| {
            h.ack = true;
            h.fin = true;
            h
        },
        &[],
    );
    client.to_link.send(fin).await.unwrap();
    probe.seq += 1;

    let mut received = Vec::new();
    loop {
        let (_, fin, rst, seq, payload) = next_tcp(&mut client).await;
        assert!(!rst, "the connection was reset");
        if !payload.is_empty() && seq == probe.ack {
            probe.ack = probe.ack.wrapping_add(payload.len() as u32);
            received.extend_from_slice(&payload);
        }
        if fin {
            break;
        }
    }
    assert_eq!(received, b"answer");
    timeout(WAIT, server).await.unwrap().unwrap();
}

/// Moves a megabyte down to the client while it sends a steady stream up, so that the two
/// directions are busy together on one link.
#[tokio::test(flavor = "multi_thread")]
async fn a_big_download_finishes_while_uploading() {
    const DOWNLOAD: usize = 1024 * 1024;
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let target = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (socket, _) = listener.accept().await.unwrap();
        let (mut reader, mut writer) = socket.into_split();
        let upload = tokio::spawn(async move {
            let mut total = 0;
            let mut buffer = [0; 4096];
            while let Ok(len) = reader.read(&mut buffer).await {
                if len == 0 {
                    break;
                }
                total += len;
            }
            total
        });
        let chunk = vec![0x5a; 8192];
        for _ in 0..DOWNLOAD / chunk.len() {
            writer.write_all(&chunk).await.unwrap();
        }
        writer.shutdown().await.unwrap();
        upload.await.unwrap()
    });

    let mut client = connect(nat_for_tests()).await;
    let mut probe = TcpProbe { src_port: 40003, dst: target, seq: 1000, ack: 0 };
    let syn = probe.segment(
        |mut h| {
            h.syn = true;
            h
        },
        &[],
    );
    client.to_link.send(syn).await.unwrap();
    let (is_syn, _, _, server_seq, _) = next_tcp(&mut client).await;
    assert!(is_syn);
    probe.seq += 1;
    probe.ack = server_seq.wrapping_add(1);

    let mut downloaded = 0;
    let mut uploaded = 0;
    loop {
        // Acknowledge what's arrived so far, and send a little with it.
        let upload = [0x33; 200];
        let segment = probe.segment(
            |mut h| {
                h.ack = true;
                h.psh = true;
                h
            },
            &upload,
        );
        client.to_link.send(segment).await.unwrap();
        probe.seq += upload.len() as u32;
        uploaded += upload.len();

        let (_, fin, rst, seq, payload) = next_tcp(&mut client).await;
        assert!(!rst, "the connection was reset");
        if seq == probe.ack {
            probe.ack = probe.ack.wrapping_add(payload.len() as u32);
            downloaded += payload.len();
        }
        if fin && downloaded == DOWNLOAD {
            break;
        }
    }

    probe.ack = probe.ack.wrapping_add(1);
    let fin = probe.segment(
        |mut h| {
            h.ack = true;
            h.fin = true;
            h
        },
        &[],
    );
    client.to_link.send(fin).await.unwrap();

    assert_eq!(timeout(WAIT, server).await.unwrap().unwrap(), uploaded);
}

#[tokio::test(flavor = "multi_thread")]
async fn udp_is_relayed_and_answers_come_back() {
    let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let port = socket.local_addr().unwrap().port();
    tokio::spawn(async move {
        let mut buffer = [0; 1500];
        let (len, from) = socket.recv_from(&mut buffer).await.unwrap();
        buffer[..len].reverse();
        socket.send_to(&buffer[..len], from).await.unwrap();
    });

    let mut client = connect(nat_for_tests()).await;
    let packet = build_udp(CLIENT_V4, 5353, Ipv4Addr::LOCALHOST, port, b"abc");
    client.to_link.send(Bytes::from(packet)).await.unwrap();

    let reply = timeout(WAIT, client.from_link.recv()).await.unwrap().unwrap();
    let sliced = SlicedPacket::from_ip(&reply).unwrap();
    let Some(TransportSlice::Udp(udp)) = sliced.transport else { panic!("expected UDP") };
    assert_eq!(udp.source_port(), port);
    assert_eq!(udp.destination_port(), 5353);
    assert_eq!(udp.payload(), b"cba");
}

#[tokio::test(flavor = "multi_thread")]
async fn pinging_the_gateway_is_answered_by_the_server() {
    let mut client = connect(NatConfig::new(DEFAULT_DNS)).await;
    let mut packet = Vec::new();
    PacketBuilder::ipv4(CLIENT_V4.octets(), GATEWAY_V4.octets(), 64)
        .icmpv4_echo_request(0x4242, 7)
        .write(&mut packet, b"hello")
        .unwrap();
    client.to_link.send(Bytes::from(packet)).await.unwrap();

    let reply = timeout(WAIT, client.from_link.recv()).await.unwrap().unwrap();
    let sliced = SlicedPacket::from_ip(&reply).unwrap();
    let Some(TransportSlice::Icmpv4(icmp)) = sliced.transport else { panic!("expected ICMP") };
    assert_eq!(icmp.type_u8(), 0);
    assert_eq!(akiyama_core::checksum::of(icmp.slice()), 0);
    assert_eq!(&icmp.slice()[4..6], &[0x42, 0x42]);
    assert_eq!(&icmp.slice()[8..], b"hello");
}

#[tokio::test(flavor = "multi_thread")]
async fn the_handshake_hands_over_the_network_settings() {
    let client = connect(NatConfig::new(Ipv4Addr::new(9, 9, 9, 9))).await;
    assert_eq!(client.welcome, Welcome::new(SESSION, DEFAULT_MTU, Ipv4Addr::new(9, 9, 9, 9)));
    assert_eq!(client.welcome.client_v4, CLIENT_V4);
}

#[tokio::test(flavor = "multi_thread")]
async fn stopping_the_server_says_goodbye() {
    let mut client = connect(NatConfig::new(DEFAULT_DNS)).await;
    client.stop.take().unwrap().send(()).unwrap();
    timeout(WAIT, &mut client.server).await.unwrap().unwrap().unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn a_phone_with_the_wrong_version_is_turned_away_and_can_come_back() {
    let (client_end, server_end) = duplex(1024);
    let (mut reader, mut writer) = tokio::io::split(client_end);
    let _server = spawn_server(server_end, NatConfig::new(DEFAULT_DNS), std::future::pending());
    proto::write_frame(&mut writer, &Frame::Hello { version: VERSION + 1, session: 1 }).await.unwrap();
    // The first goodbye is the one every link starts with.
    assert!(matches!(next_frame(&mut reader).await, Frame::Bye(proto::ByeReason::Normal)));
    assert!(matches!(next_frame(&mut reader).await, Frame::Bye(proto::ByeReason::VersionMismatch)));

    proto::write_frame(&mut writer, &Frame::Hello { version: VERSION, session: 2 }).await.unwrap();
    assert_eq!(next_welcome(&mut reader).await.session, 2);
}

/// A USB link outlives the app on it, so an app killed in the middle of a frame leaves the rest of
/// that frame behind. The next hello still has to get through.
#[tokio::test(flavor = "multi_thread")]
async fn a_hello_is_found_after_leftover_garbage() {
    let (client_end, server_end) = duplex(64 * 1024);
    let (mut reader, mut writer) = tokio::io::split(client_end);
    let _server = spawn_server(server_end, NatConfig::new(DEFAULT_DNS), std::future::pending());
    writer.write_all(&[3, 0x40, 0x00, 1, 2, 3, 1, 0, 9, 9]).await.unwrap();
    proto::write_frame(&mut writer, &Frame::Hello { version: VERSION, session: 7 }).await.unwrap();
    assert_eq!(next_welcome(&mut reader).await.session, 7);
}

async fn next_frame(reader: &mut (impl tokio::io::AsyncRead + Unpin)) -> Frame {
    timeout(WAIT, proto::read_frame(reader)).await.unwrap().unwrap()
}

async fn next_welcome(reader: &mut (impl tokio::io::AsyncRead + Unpin)) -> Welcome {
    loop {
        if let Frame::Welcome(welcome) = next_frame(reader).await {
            return welcome;
        }
    }
}

/// The app can restart while the cable stays plugged in, and each time it needs a fresh session.
#[tokio::test(flavor = "multi_thread")]
async fn a_phone_can_come_back_after_saying_goodbye() {
    let (client_end, server_end) = duplex(64 * 1024);
    let (mut reader, mut writer) = tokio::io::split(client_end);
    let _server = spawn_server(server_end, NatConfig::new(DEFAULT_DNS), std::future::pending());
    for session in 1..=2 {
        proto::write_frame(&mut writer, &Frame::Hello { version: VERSION, session }).await.unwrap();
        assert_eq!(next_welcome(&mut reader).await.session, session);
        proto::write_frame(&mut writer, &Frame::Bye(proto::ByeReason::Normal)).await.unwrap();
        // The goodbye gets answered, which is what unblocks the phone's reader.
        while !matches!(next_frame(&mut reader).await, Frame::Bye(_)) {}
    }
}

/// An app that got killed never says goodbye. Its replacement's hello has to start a new session,
/// even though the server still thinks the old one is running.
#[tokio::test(flavor = "multi_thread")]
async fn a_restarted_app_gets_a_new_session_without_a_goodbye() {
    let (client_end, server_end) = duplex(64 * 1024);
    let (mut reader, mut writer) = tokio::io::split(client_end);
    let _server = spawn_server(server_end, NatConfig::new(DEFAULT_DNS), std::future::pending());
    proto::write_frame(&mut writer, &Frame::Hello { version: VERSION, session: 1 }).await.unwrap();
    assert_eq!(next_welcome(&mut reader).await.session, 1);

    // A repeat of the same hello changes nothing, as it's only the app being impatient.
    proto::write_frame(&mut writer, &Frame::Hello { version: VERSION, session: 1 }).await.unwrap();
    proto::write_frame(&mut writer, &Frame::Hello { version: VERSION, session: 2 }).await.unwrap();
    assert_eq!(next_welcome(&mut reader).await.session, 2);
}

/// Dropping the NAT has to end its connections and let go of their sockets.
#[tokio::test(flavor = "multi_thread")]
async fn a_nat_starts_and_stops_cleanly() {
    let mut nat = Nat::start(Arc::new(NatConfig::new(DEFAULT_DNS)));
    assert!(nat.take_output().is_some());
    assert!(nat.take_output().is_none());
    drop(nat);
}

/// Android won't reopen a USB accessory while a read from the last app is still blocked, so every
/// link starts by sending something that makes such a read return.
#[tokio::test(flavor = "multi_thread")]
async fn a_link_starts_with_a_goodbye_that_frees_stuck_readers() {
    let (client_end, server_end) = duplex(1024);
    let (mut reader, _writer) = tokio::io::split(client_end);
    let _server = spawn_server(server_end, NatConfig::new(DEFAULT_DNS), std::future::pending());
    assert!(matches!(next_frame(&mut reader).await, Frame::Bye(proto::ByeReason::Normal)));
}
