//! The Akiyama wire protocol. `docs/protocol.md` describes the same thing for people.
//!
//! Everything on a link is a frame: a one-byte type, a two-byte big-endian payload length, then
//! the payload. The Android app has its own copy of this in Kotlin. The byte strings in this
//! file's tests are repeated in its tests, so the two can't drift apart unnoticed.

use bytes::{BufMut, Bytes, BytesMut};
use std::io;
use std::net::{Ipv4Addr, Ipv6Addr};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const MAGIC: [u8; 4] = *b"AKIY";
pub const VERSION: u8 = 1;
/// The IP MTU inside the tunnel. TCP ends at the server, and only the tunnel ever sees these
/// packets, so they can be far bigger than on a real network. Each one costs about the same work
/// to move, so bigger packets are what make the link fast.
pub const DEFAULT_MTU: u16 = 16_000;

/// The resolver address a client gives its system. It only exists inside the tunnel.
pub const GATEWAY_V4: Ipv4Addr = Ipv4Addr::new(10, 233, 78, 1);
pub const CLIENT_V4: Ipv4Addr = Ipv4Addr::new(10, 233, 78, 2);
/// The source address of the client resolver's own queries. Nothing else sends from it, so its
/// replies can be picked out of the tunnel without colliding with an app's ports.
pub const RESOLVER_V4: Ipv4Addr = Ipv4Addr::new(10, 233, 78, 3);
pub const PREFIX_V4: u8 = 24;
pub const GATEWAY_V6: Ipv6Addr = Ipv6Addr::new(0xfd41, 0x6b69, 0x7961, 0, 0, 0, 0, 1);
pub const CLIENT_V6: Ipv6Addr = Ipv6Addr::new(0xfd41, 0x6b69, 0x7961, 0, 0, 0, 0, 2);
pub const PREFIX_V6: u8 = 64;
pub const DEFAULT_DNS: Ipv4Addr = Ipv4Addr::new(1, 1, 1, 1);

const TYPE_HELLO: u8 = 1;
const TYPE_WELCOME: u8 = 2;
const TYPE_PACKET: u8 = 3;
const TYPE_PING: u8 = 4;
const TYPE_PONG: u8 = 5;
const TYPE_BYE: u8 = 6;

const HEADER_LEN: usize = 3;
pub const WELCOME_LEN: usize = 8 + 2 + 4 + 4 + 1 + 16 + 16 + 1 + 4;
/// The magic, the version and the session ID.
pub const HELLO_LEN: usize = 4 + 1 + 8;

/// What a server tells a client about the network it's joining.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Welcome {
    /// The session ID from the hello this answers.
    pub session: u64,
    pub mtu: u16,
    pub client_v4: Ipv4Addr,
    pub gateway_v4: Ipv4Addr,
    pub prefix_v4: u8,
    pub client_v6: Ipv6Addr,
    pub gateway_v6: Ipv6Addr,
    pub prefix_v6: u8,
    pub upstream_dns: Ipv4Addr,
}

impl Welcome {
    pub fn new(session: u64, mtu: u16, upstream_dns: Ipv4Addr) -> Self {
        Welcome {
            session,
            mtu,
            client_v4: CLIENT_V4,
            gateway_v4: GATEWAY_V4,
            prefix_v4: PREFIX_V4,
            client_v6: CLIENT_V6,
            gateway_v6: GATEWAY_V6,
            prefix_v6: PREFIX_V6,
            upstream_dns,
        }
    }
}

/// Why a link is ending.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ByeReason {
    Normal,
    VersionMismatch,
    Unknown(u8),
}

impl ByeReason {
    fn code(self) -> u8 {
        match self {
            ByeReason::Normal => 0,
            ByeReason::VersionMismatch => 1,
            ByeReason::Unknown(code) => code,
        }
    }

    fn from_code(code: u8) -> Self {
        match code {
            0 => ByeReason::Normal,
            1 => ByeReason::VersionMismatch,
            code => ByeReason::Unknown(code),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Frame {
    /// A phone's app starting a session. The ID is random, and stays the same if it has to repeat
    /// itself, which is how a repeat can be told apart from an app that restarted.
    Hello {
        version: u8,
        session: u64,
    },
    Welcome(Welcome),
    Packet(Bytes),
    Ping(u64),
    Pong(u64),
    Bye(ByeReason),
}

impl Frame {
    /// Appends this frame to `out`.
    pub fn encode(&self, out: &mut BytesMut) {
        match self {
            Frame::Hello { version, session } => {
                put_header(out, TYPE_HELLO, HELLO_LEN);
                out.put_slice(&MAGIC);
                out.put_u8(*version);
                out.put_u64(*session);
            }
            Frame::Welcome(w) => {
                put_header(out, TYPE_WELCOME, WELCOME_LEN);
                out.put_u64(w.session);
                out.put_u16(w.mtu);
                out.put_slice(&w.client_v4.octets());
                out.put_slice(&w.gateway_v4.octets());
                out.put_u8(w.prefix_v4);
                out.put_slice(&w.client_v6.octets());
                out.put_slice(&w.gateway_v6.octets());
                out.put_u8(w.prefix_v6);
                out.put_slice(&w.upstream_dns.octets());
            }
            Frame::Packet(packet) => {
                put_header(out, TYPE_PACKET, packet.len());
                out.put_slice(packet);
            }
            Frame::Ping(nonce) => {
                put_header(out, TYPE_PING, 8);
                out.put_u64(*nonce);
            }
            Frame::Pong(nonce) => {
                put_header(out, TYPE_PONG, 8);
                out.put_u64(*nonce);
            }
            Frame::Bye(reason) => {
                put_header(out, TYPE_BYE, 1);
                out.put_u8(reason.code());
            }
        }
    }

    /// Decodes a frame's payload. Unknown types come back as `None`, so that a later protocol
    /// version can add frames that older peers just skip.
    pub fn decode(kind: u8, payload: Bytes) -> io::Result<Option<Frame>> {
        let frame = match kind {
            TYPE_HELLO => {
                if payload.len() < HELLO_LEN || payload[..MAGIC.len()] != MAGIC {
                    return Err(invalid("not an Akiyama hello"));
                }
                Frame::Hello { version: payload[MAGIC.len()], session: u64_at(&payload[MAGIC.len() + 1..]) }
            }
            TYPE_WELCOME => {
                // Longer is fine: a later version may append fields.
                if payload.len() < WELCOME_LEN {
                    return Err(invalid("welcome frame is too short"));
                }
                Frame::Welcome(Welcome {
                    session: u64_at(&payload),
                    mtu: u16::from_be_bytes([payload[8], payload[9]]),
                    client_v4: v4(&payload[10..]),
                    gateway_v4: v4(&payload[14..]),
                    prefix_v4: payload[18],
                    client_v6: v6(&payload[19..]),
                    gateway_v6: v6(&payload[35..]),
                    prefix_v6: payload[51],
                    upstream_dns: v4(&payload[52..]),
                })
            }
            TYPE_PACKET => Frame::Packet(payload),
            TYPE_PING | TYPE_PONG => {
                let nonce = payload
                    .get(..8)
                    .and_then(|bytes| <[u8; 8]>::try_from(bytes).ok())
                    .map(u64::from_be_bytes)
                    .ok_or_else(|| invalid("ping frame is too short"))?;
                if kind == TYPE_PING { Frame::Ping(nonce) } else { Frame::Pong(nonce) }
            }
            TYPE_BYE => {
                let code = *payload.first().ok_or_else(|| invalid("bye frame is empty"))?;
                Frame::Bye(ByeReason::from_code(code))
            }
            _ => return Ok(None),
        };
        Ok(Some(frame))
    }
}

fn put_header(out: &mut BytesMut, kind: u8, len: usize) {
    debug_assert!(len <= usize::from(u16::MAX));
    out.reserve(HEADER_LEN + len);
    out.put_u8(kind);
    out.put_u16(len as u16);
}

fn u64_at(bytes: &[u8]) -> u64 {
    let mut value = [0; 8];
    value.copy_from_slice(&bytes[..8]);
    u64::from_be_bytes(value)
}

fn v4(bytes: &[u8]) -> Ipv4Addr {
    Ipv4Addr::new(bytes[0], bytes[1], bytes[2], bytes[3])
}

fn v6(bytes: &[u8]) -> Ipv6Addr {
    let mut octets = [0; 16];
    octets.copy_from_slice(&bytes[..16]);
    Ipv6Addr::from(octets)
}

fn invalid(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

/// Reads the next frame this version understands.
pub async fn read_frame<R: AsyncRead + Unpin>(reader: &mut R) -> io::Result<Frame> {
    loop {
        let mut header = [0; HEADER_LEN];
        reader.read_exact(&mut header).await?;
        let len = usize::from(u16::from_be_bytes([header[1], header[2]]));
        let mut payload = BytesMut::zeroed(len);
        reader.read_exact(&mut payload).await?;
        if let Some(frame) = Frame::decode(header[0], payload.freeze())? {
            return Ok(frame);
        }
    }
}

/// Writes one frame and flushes it. The session's packet path batches instead of using this.
pub async fn write_frame<W: AsyncWrite + Unpin>(writer: &mut W, frame: &Frame) -> io::Result<()> {
    let mut out = BytesMut::new();
    frame.encode(&mut out);
    writer.write_all(&out).await?;
    writer.flush().await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn encoded(frame: &Frame) -> Vec<u8> {
        let mut out = BytesMut::new();
        frame.encode(&mut out);
        out.to_vec()
    }

    const SESSION: u64 = 0x0102030405060708;

    // The Android app's ProtoTest checks the same bytes. Change them together.
    const WELCOME_BYTES: &[u8] = &[
        2, 0, 56, // header
        1, 2, 3, 4, 5, 6, 7, 8, // session
        0x3e, 0x80, // mtu
        10, 233, 78, 2, // client v4
        10, 233, 78, 1,  // gateway v4
        24, // prefix v4
        0xfd, 0x41, 0x6b, 0x69, 0x79, 0x61, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, // client v6
        0xfd, 0x41, 0x6b, 0x69, 0x79, 0x61, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,  // gateway v6
        64, // prefix v6
        1, 1, 1, 1, // upstream dns
    ];

    #[test]
    fn frames_match_the_documented_bytes() {
        assert_eq!(
            encoded(&Frame::Hello { version: 1, session: SESSION }),
            [1, 0, 13, b'A', b'K', b'I', b'Y', 1, 1, 2, 3, 4, 5, 6, 7, 8]
        );
        assert_eq!(encoded(&Frame::Welcome(Welcome::new(SESSION, DEFAULT_MTU, DEFAULT_DNS))), WELCOME_BYTES);
        assert_eq!(encoded(&Frame::Packet(Bytes::from_static(&[0x45, 0x00]))), [3, 0, 2, 0x45, 0x00]);
        assert_eq!(encoded(&Frame::Ping(0x0102030405060708)), [4, 0, 8, 1, 2, 3, 4, 5, 6, 7, 8]);
        assert_eq!(encoded(&Frame::Pong(9)), [5, 0, 8, 0, 0, 0, 0, 0, 0, 0, 9]);
        assert_eq!(encoded(&Frame::Bye(ByeReason::VersionMismatch)), [6, 0, 1, 1]);
    }

    #[tokio::test]
    async fn frames_survive_a_round_trip() {
        let frames = [
            Frame::Hello { version: VERSION, session: SESSION },
            Frame::Welcome(Welcome::new(SESSION, DEFAULT_MTU, Ipv4Addr::new(9, 9, 9, 9))),
            Frame::Packet(Bytes::from(vec![7; 1280])),
            Frame::Ping(u64::MAX),
            Frame::Pong(0),
            Frame::Bye(ByeReason::Normal),
            Frame::Bye(ByeReason::Unknown(200)),
        ];
        let mut wire = BytesMut::new();
        for frame in &frames {
            frame.encode(&mut wire);
        }
        let mut reader = &wire[..];
        for frame in &frames {
            assert_eq!(&read_frame(&mut reader).await.unwrap(), frame);
        }
    }

    #[tokio::test]
    async fn unknown_frames_are_skipped() {
        let mut reader: &[u8] = &[99, 0, 2, 1, 2, 4, 0, 8, 0, 0, 0, 0, 0, 0, 0, 5];
        assert_eq!(read_frame(&mut reader).await.unwrap(), Frame::Ping(5));
    }

    #[tokio::test]
    async fn a_bad_hello_is_an_error() {
        let mut reader: &[u8] = &[1, 0, 13, b'N', b'O', b'P', b'E', 1, 0, 0, 0, 0, 0, 0, 0, 0];
        assert!(read_frame(&mut reader).await.is_err());
    }

    #[test]
    fn welcome_accepts_trailing_fields() {
        let mut payload = WELCOME_BYTES[3..].to_vec();
        payload.push(0xff);
        let frame = Frame::decode(TYPE_WELCOME, Bytes::from(payload)).unwrap();
        assert_eq!(frame, Some(Frame::Welcome(Welcome::new(SESSION, DEFAULT_MTU, DEFAULT_DNS))));
    }
}
