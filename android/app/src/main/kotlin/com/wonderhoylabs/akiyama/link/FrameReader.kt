package com.wonderhoylabs.akiyama.link

import java.io.EOFException
import java.nio.ByteBuffer

/**
 * Finds frames in a stream without copying them or allocating anything per frame. A frame's payload
 * stays where it landed in a direct buffer, ready to go straight to the next system call, and stays
 * valid until the next call to [next].
 *
 * [read] fills the buffer it's given from its position, and returns how much it read. A USB
 * accessory hands over at most 16 KiB at a time, and it needs that much room offered or it loses
 * data, so this always offers plenty.
 */
class FrameReader(private val read: (ByteBuffer) -> Int) {
	val buffer: ByteBuffer = ByteBuffer.allocateDirect(BUFFER_BYTES)
	private var start = 0
	private var end = 0

	var type = 0
		private set
	var payloadStart = 0
		private set
	var payloadLength = 0
		private set

	/** Waits for the next whole frame. Frames of a type this version doesn't know are included. */
	fun next() {
		// Whoever handled the last frame will have narrowed the buffer to its payload.
		buffer.limit(BUFFER_BYTES)
		while (true) {
			if (end - start >= Proto.HEADER_LEN) {
				val length =
					((buffer.get(start + 1).toInt() and 0xff) shl 8) or (buffer.get(start + 2).toInt() and 0xff)
				if (end - start >= Proto.HEADER_LEN + length) {
					type = buffer.get(start).toInt() and 0xff
					payloadStart = start + Proto.HEADER_LEN
					payloadLength = length
					start += Proto.HEADER_LEN + length
					return
				}
			}
			fill()
		}
	}

	/**
	 * Throws away everything up to the next occurrence of [pattern]. A USB link outlives the app on
	 * it, so whatever the last app didn't read is still on its way, and may not even start at the
	 * beginning of a frame.
	 */
	fun skipTo(pattern: ByteArray) {
		buffer.limit(BUFFER_BYTES)
		while (true) {
			while (end - start >= pattern.size) {
				if (matchesAt(start, pattern)) return
				start++
			}
			fill()
		}
	}

	private fun matchesAt(offset: Int, pattern: ByteArray): Boolean {
		for (index in pattern.indices) {
			if (buffer.get(offset + index) != pattern[index]) return false
		}
		return true
	}

	/** Points [buffer]'s position and limit at the current payload. */
	fun selectPayload(): ByteBuffer {
		buffer.limit(payloadStart + payloadLength).position(payloadStart)
		return buffer
	}

	private fun fill() {
		if (BUFFER_BYTES - end < MIN_ROOM_BYTES) {
			buffer.limit(end).position(start)
			buffer.compact()
			end -= start
			start = 0
		}
		buffer.limit(BUFFER_BYTES).position(end)
		val count = read(buffer)
		if (count <= 0) throw EOFException("the link closed")
		end += count
		buffer.limit(BUFFER_BYTES)
	}

	private companion object {
		const val BUFFER_BYTES = 1024 * 1024

		/** A whole frame's worth, so a frame can always finish arriving after a compaction. */
		const val MIN_ROOM_BYTES = 128 * 1024
	}
}
