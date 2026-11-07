# The Akiyama protocol

Version 1. This is what's on the cable, for anyone writing another client or server. The Rust
version is in [`proto.rs`](../crates/akiyama-core/src/proto.rs) and the Kotlin one is in
[`Proto.kt`](../android/app/src/main/kotlin/com/wonderhoylabs/akiyama/link/Proto.kt). Their tests
check the same bytes.

## The link

The computer is always the USB host, and it switches the phone into Android Open Accessory mode with
control requests 51, 52 and 53. The strings it sends are:

| Index | Name | Value |
| --- | --- | --- |
| 0 | Manufacturer | `Wonderhoy Labs` |
| 1 | Model | `Akiyama` |
| 2 | Description | The computer's name, which the app shows |
| 3 | Version | The version of the tool |
| 4 | URI | This repository |
| 5 | Serial | `akiyama-server` if the computer's sharing, `akiyama-client` if it wants the phone's network |

The phone comes back as vendor `18d1`, product `2d00` to `2d05`. Everything after that uses the
first vendor-specific interface's two bulk endpoints as a reliable, ordered byte stream that runs in
both directions at once, so USB's packet boundaries don't mean anything.

Android's accessory driver only finishes a read on a short packet. A computer has to end every burst
with one, which means sending an empty packet after a burst that's an exact number of packets long.
The app also has to offer at least 16 KiB on each read.

With `adb` instead, the same stream runs over `adb reverse localabstract:akiyama tcp:PORT`, with the
computer listening on the port and the phone connecting to the abstract socket `akiyama`.

## Frames

Everything on the stream is a frame. Numbers are big-endian.

| Bytes | Value |
| --- | --- |
| 1 | Type |
| 2 | Payload length |
| n | Payload |

Skip frames with a type you don't know. Later versions may add some.

| Type | Name | Payload |
| --- | --- | --- |
| 1 | Hello | `AKIY`, the protocol version in 1 byte, then a session ID in 8 bytes |
| 2 | Welcome | See below |
| 3 | Packet | One IP packet, IPv4 or IPv6 |
| 4 | Ping | 8 bytes of anything |
| 5 | Pong | The same 8 bytes as the ping it answers |
| 6 | Bye | The reason in 1 byte: 0 for normal, 1 for a version mismatch |

Allow for longer payloads than these. A later version may add fields to the end.

### Handshake

The side that wants the network is the client, and that can be either the computer or the phone. It
picks a random session ID and sends a hello every second until it gets a welcome with the same ID.
The other side, the server, answers with this:

| Bytes | Value |
| --- | --- |
| 8 | The session ID from the hello |
| 2 | MTU of the tunnel |
| 4 | Client's IPv4 address |
| 4 | Gateway's IPv4 address |
| 1 | IPv4 prefix length |
| 16 | Client's IPv6 address |
| 16 | Gateway's IPv6 address |
| 1 | IPv6 prefix length |
| 4 | Upstream DNS server, IPv4 |

A server that gets a hello with a new session ID drops the old session, including its connections,
and starts again. One with the same ID is a repeat, and gets ignored.

If the versions don't match, the server sends a bye with reason 1.

### Finding the start

A USB link outlives the programs on each end, so a new program can find the last one's unread data
waiting for it. Before a session starts, both sides read until they find what they're looking for:
a server looks for the bytes of a hello's header and `AKIY`, and a client looks for a welcome's header
and its own session ID.

A client sends a bye before its first hello. The phone's reads can't be interrupted, so that wakes up
a read that an old session left behind.

### During a session

Either side can send packets at any time. Each side sends a ping every 2 seconds, and gives up after
8 seconds without receiving anything at all. Either side can end things with a bye. After that the
client starts again with a new session ID, if it's going to.

## Addresses

| | IPv4 | IPv6 |
| --- | --- | --- |
| Gateway | `10.233.78.1/24` | `fd41:6b69:7961::1/64` |
| Client | `10.233.78.2` | `fd41:6b69:7961::2` |
| Client's resolver | `10.233.78.3` | |

The client points its system's DNS at the gateway, picks those queries out of the tunnel, and sends
what it can't answer from its cache to the upstream server, from the resolver's address. A TCP
connection to the gateway on port 53 goes to the upstream server too, which is how DNS over TCP works.

The default MTU is 16000. Connections end at the server's userspace TCP/IP stack, so nothing past it
sees that number, and bigger packets mean less work per byte.
