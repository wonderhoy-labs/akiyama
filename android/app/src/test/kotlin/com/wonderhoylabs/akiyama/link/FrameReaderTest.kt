package com.wonderhoylabs.akiyama.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.EOFException
import java.nio.ByteBuffer

class FrameReaderTest {
	private fun frame(type: Int, payload: ByteArray): ByteArray =
		byteArrayOf(type.toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + payload

	/** Hands over a stream in pieces of a set size, the way a USB accessory hands over 16 KiB. */
	private fun readerOf(stream: ByteArray, chunk: Int): FrameReader {
		var offset = 0
		return FrameReader { buffer ->
			if (offset == stream.size) {
				-1
			} else {
				val count = minOf(chunk, stream.size - offset, buffer.remaining())
				buffer.put(stream, offset, count)
				offset += count
				count
			}
		}
	}

	private fun FrameReader.payload(): ByteArray {
		val bytes = ByteArray(payloadLength)
		selectPayload().get(bytes)
		return bytes
	}

	@Test
	fun framesAreFoundAcrossPiecesOfAnySize() {
		val first = ByteArray(40_000) { it.toByte() }
		val second = byteArrayOf(1, 2, 3)
		val third = ByteArray(0)
		val stream = frame(3, first) + frame(4, second) + frame(6, third)
		for (chunk in listOf(1, 7, 512, 16_384, 200_000)) {
			val reader = readerOf(stream, chunk)
			reader.next()
			assertEquals(3, reader.type)
			assertArrayEquals(first, reader.payload())
			reader.next()
			assertEquals(4, reader.type)
			assertArrayEquals(second, reader.payload())
			reader.next()
			assertEquals(6, reader.type)
			assertEquals(0, reader.payloadLength)
			assertThrows(EOFException::class.java) { reader.next() }
		}
	}

	/** Runs through several times the buffer's size, so it has to compact itself along the way. */
	@Test
	fun aLongStreamSurvivesCompaction() {
		val frames = (0 until 200).map { index -> ByteArray(60_000 + index) { index.toByte() } }
		val stream = frames.map { frame(3, it) }.reduce(ByteArray::plus)
		val reader = readerOf(stream, 16_384)
		for (expected in frames) {
			reader.next()
			assertArrayEquals(expected, reader.payload())
		}
	}

	/** What's left over from an app that was killed, which can stop in the middle of a frame. */
	@Test
	fun aWelcomeIsFoundAfterLeftovers() {
		val session = 0x1122334455667788L
		val other = Proto.welcomeStart(session + 1)
		val payload = ByteArray(Proto.WELCOME_LEN) { 9 }
		val start = Proto.welcomeStart(session)
		val stream = ByteArray(5) { 3 } + other + frame(3, ByteArray(20_000)) +
			start + payload.copyOfRange(Long.SIZE_BYTES, payload.size) + frame(4, byteArrayOf(1))
		for (chunk in listOf(1, 512, 16_384)) {
			val reader = readerOf(stream, chunk)
			reader.skipTo(start)
			reader.next()
			assertEquals(Proto.TYPE_WELCOME, reader.type)
			assertEquals(Proto.WELCOME_LEN, reader.payloadLength)
			reader.next()
			assertEquals(4, reader.type)
		}
	}

	@Test
	fun theBiggestFrameFits() {
		val biggest = ByteArray(65_535) { 7 }
		val reader = readerOf(frame(3, biggest), 16_384)
		reader.next()
		assertArrayEquals(biggest, reader.payload())
	}

	@Test
	fun aBufferIsReusedRatherThanReallocated() {
		val reader = readerOf(frame(3, ByteArray(10)), 16_384)
		val before: ByteBuffer = reader.buffer
		reader.next()
		assertEquals(before, reader.buffer)
	}
}
