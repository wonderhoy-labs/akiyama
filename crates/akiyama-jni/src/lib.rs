//! Gives the Android app the same NAT the command-line server uses, for when the phone is the one
//! sharing its network.
//!
//! The app is Kotlin, and so is its half of the protocol. A userspace TCP/IP stack is the one piece
//! that would be a bad idea to write twice, so only that crosses over. Packets move through direct
//! buffers, so nothing gets copied on the way across. The matching declarations are in
//! `NatEngine.kt`.

#![allow(non_snake_case)]

use akiyama_core::nat::{Nat, NatConfig, NatInput};
use bytes::Bytes;
use jni_sys::{JNI_FALSE, JNIEnv, jboolean, jclass, jint, jlong, jobject};
use std::net::Ipv4Addr;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::runtime::Runtime;
use tokio::sync::mpsc;
use tokio::time::timeout;

const WORKER_THREADS: usize = 4;
/// Smaller than on a computer, as each connection uses four times this in memory.
const TCP_BUFFER_BYTES: u32 = 256 * 1024;

struct Engine {
	runtime: Runtime,
	nat: Mutex<Option<Nat>>,
	input: NatInput,
	output: Mutex<mpsc::Receiver<Bytes>>,
}

impl Engine {
	fn start(upstream_dns: Ipv4Addr, block_lan: bool, mtu: u16) -> std::io::Result<Self> {
		let runtime = tokio::runtime::Builder::new_multi_thread()
			.worker_threads(WORKER_THREADS)
			.thread_name("akiyama-nat")
			.enable_all()
			.build()?;
		let mut config = NatConfig::new(upstream_dns);
		config.block_lan = block_lan;
		config.mtu = mtu;
		config.tcp_buffer_bytes = TCP_BUFFER_BYTES;
		let mut nat = runtime.block_on(async { Nat::start(Arc::new(config)) });
		let input = nat.input();
		let output = nat.take_output().expect("a new Nat still has its output");
		Ok(Engine { runtime, nat: Mutex::new(Some(nat)), input, output: Mutex::new(output) })
	}
}

/// Starts a NAT, returning a handle for the other calls, or 0 if it couldn't start.
/// `upstream_dns` is an IPv4 address in network byte order, as `ByteBuffer.getInt()` reads it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wonderhoylabs_akiyama_share_NatEngine_nativeStart(
	_env: *mut JNIEnv,
	_class: jclass,
	upstream_dns: jint,
	block_lan: jboolean,
	mtu: jint,
) -> jlong {
	let upstream_dns = Ipv4Addr::from(upstream_dns as u32);
	match Engine::start(upstream_dns, block_lan != JNI_FALSE, mtu as u16) {
		Ok(engine) => Box::into_raw(Box::new(engine)) as jlong,
		Err(_) => 0,
	}
}

/// Hands the NAT an IP packet from a direct buffer. It's dropped if the NAT has fallen behind.
///
/// # Safety
/// The handle has to come from `nativeStart` and not have been freed, and `buffer` must be a direct
/// buffer holding at least `offset + length` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_wonderhoylabs_akiyama_share_NatEngine_nativeInput(
	env: *mut JNIEnv,
	_class: jclass,
	handle: jlong,
	buffer: jobject,
	offset: jint,
	length: jint,
) {
	let engine = unsafe { &*(handle as *const Engine) };
	let packet = unsafe {
		let start = ((**env).v1_4.GetDirectBufferAddress)(env, buffer).cast::<u8>().add(offset as usize);
		std::slice::from_raw_parts(start, length as usize)
	};
	engine.input.send(Bytes::copy_from_slice(packet));
}

/// Waits for an IP packet for the client and copies it to a direct buffer. Returns its length, 0 if
/// none came within `wait_millis`, or -1 once the NAT has been stopped. A wait of 0 doesn't wait.
///
/// # Safety
/// The handle has to come from `nativeStart` and not have been freed, and `buffer` must be a direct
/// buffer with room for `capacity` bytes at `offset`.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_wonderhoylabs_akiyama_share_NatEngine_nativeRead(
	env: *mut JNIEnv,
	_class: jclass,
	handle: jlong,
	buffer: jobject,
	offset: jint,
	capacity: jint,
	wait_millis: jint,
) -> jint {
	let engine = unsafe { &*(handle as *const Engine) };
	let mut output = engine.output.lock().unwrap();
	let packet = if wait_millis == 0 {
		match output.try_recv() {
			Ok(packet) => packet,
			Err(mpsc::error::TryRecvError::Empty) => return 0,
			Err(mpsc::error::TryRecvError::Disconnected) => return -1,
		}
	} else {
		let wait = Duration::from_millis(wait_millis as u64);
		match engine.runtime.block_on(async { timeout(wait, output.recv()).await }) {
			Ok(Some(packet)) => packet,
			Ok(None) => return -1,
			Err(_) => return 0,
		}
	};
	let length = packet.len().min(capacity as usize);
	unsafe {
		let start = ((**env).v1_4.GetDirectBufferAddress)(env, buffer).cast::<u8>().add(offset as usize);
		std::ptr::copy_nonoverlapping(packet.as_ptr(), start, length);
	}
	length as jint
}

/// Stops the NAT and ends its connections. A thread waiting in `nativeRead` gets -1.
///
/// # Safety
/// The handle has to come from `nativeStart` and not have been freed.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_wonderhoylabs_akiyama_share_NatEngine_nativeStop(
	_env: *mut JNIEnv,
	_class: jclass,
	handle: jlong,
) {
	let engine = unsafe { &*(handle as *const Engine) };
	let _guard = engine.runtime.enter();
	engine.nat.lock().unwrap().take();
}

/// Frees a stopped NAT. Nothing may be using the handle, or use it afterwards.
///
/// # Safety
/// The handle has to come from `nativeStart`, have been stopped, and not have been freed.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_wonderhoylabs_akiyama_share_NatEngine_nativeFree(
	_env: *mut JNIEnv,
	_class: jclass,
	handle: jlong,
) {
	drop(unsafe { Box::from_raw(handle as *mut Engine) });
}
