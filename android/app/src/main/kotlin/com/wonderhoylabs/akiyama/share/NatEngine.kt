package com.wonderhoylabs.akiyama.share

import java.net.Inet4Address
import java.nio.ByteBuffer

/**
 * One computer's NAT, which is the Rust code from `crates/akiyama-core`, also used by the
 * command-line server. See `crates/akiyama-jni` for the other side of these calls. Packets move
 * through direct buffers, so nothing gets copied on the way across.
 */
class NatEngine(upstreamDns: Inet4Address, blockLan: Boolean, mtu: Int) {
	private var handle = nativeStart(ByteBuffer.wrap(upstreamDns.address).int, blockLan, mtu)

	init {
		check(handle != 0L) { "Couldn't start the NAT." }
	}

	/** Hands the NAT an IP packet from the computer. */
	fun input(buffer: ByteBuffer, offset: Int, length: Int) = nativeInput(handle, buffer, offset, length)

	/** Gets a packet for the computer. Returns its length, 0 if there's none yet, or -1 once stopped. */
	fun read(buffer: ByteBuffer, offset: Int, capacity: Int, waitMillis: Int): Int =
		nativeRead(handle, buffer, offset, capacity, waitMillis)

	/** Ends the computer's connections. A thread waiting in [read] gets -1. */
	fun stop() = nativeStop(handle)

	/** Releases everything. Only call this once [stop] has returned and nothing's reading. */
	fun free() {
		nativeFree(handle)
		handle = 0
	}

	private companion object {
		init {
			System.loadLibrary("akiyama_jni")
		}

		@JvmStatic external fun nativeStart(upstreamDns: Int, blockLan: Boolean, mtu: Int): Long

		@JvmStatic external fun nativeInput(handle: Long, buffer: ByteBuffer, offset: Int, length: Int)

		@JvmStatic external fun nativeRead(handle: Long, buffer: ByteBuffer, offset: Int, capacity: Int, waitMillis: Int): Int

		@JvmStatic external fun nativeStop(handle: Long)

		@JvmStatic external fun nativeFree(handle: Long)
	}
}
