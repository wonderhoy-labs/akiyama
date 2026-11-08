//! The parts of Akiyama that don't care which platform they run on.
//!
//! A link is a reliable, ordered, full-duplex byte stream. Over USB that's the pair of bulk
//! endpoints of an Android accessory. [`proto`] defines what travels on it, [`session`] runs both
//! ends, [`nat`] turns a client's IP packets into ordinary sockets on the server, and [`dns`] is the
//! small resolver that clients run for themselves.
//!
//! The link itself can be anything reliable and ordered, so the same code serves Akiyama over USB,
//! over TCP for testing, or over whatever another app has. A server hands each link to
//! [`session::serve_link`], which serves one phone after another on it. A client calls
//! [`session::client_handshake`], then [`session::run_client`] with a [`session::ClientDevice`]
//! around its TUN device, or [`session::pump`] to handle packets itself. `examples/in_process.rs`
//! runs both ends in one process.

#![warn(missing_docs)]

pub mod checksum;
pub mod dns;
pub mod nat;
pub mod proto;
pub mod session;
