//! The parts of Akiyama that don't care which platform they run on.
//!
//! A link is a reliable, ordered, full-duplex byte stream. Over USB that's the pair of bulk
//! endpoints of an Android accessory. [`proto`] defines what travels on it, [`session`] runs both
//! ends, [`nat`] turns a client's IP packets into ordinary sockets on the server, and [`dns`] is the
//! small resolver that clients run for themselves.

pub mod checksum;
pub mod dns;
pub mod nat;
pub mod proto;
pub mod session;
