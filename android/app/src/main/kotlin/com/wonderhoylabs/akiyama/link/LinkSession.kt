package com.wonderhoylabs.akiyama.link

import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Counters for one link. Each has one writer, so plain volatile fields are enough. */
class LinkStats {
	@Volatile var bytesSent = 0L

	@Volatile var bytesReceived = 0L

	/** The latest ping time in microseconds, or zero before the first reply. */
	@Volatile var rttMicros = 0
}

/**
 * Moves packets between Android's VPN device and the link, in both directions at once.
 *
 * This is the fast path, and it's written that way on purpose: direct buffers go straight to system
 * calls, and nothing is copied or allocated per packet unless it's DNS. Packets are big, up to the
 * tunnel's MTU, so the work per packet is what limits the speed.
 */
class LinkSession(private val link: FileDescriptor) {
	val stats = LinkStats()
	private var source: (ByteBuffer) -> Int = { buffer -> Os.read(link, buffer) }
	private val reader = FrameReader { buffer -> source(buffer) }
	private val finished = CountDownLatch(1)
	private val failure = AtomicReference<IOException?>()

	@Volatile private var stopping = false

	@Volatile private var lastHeardNanos = 0L

	@Volatile private var pongDue = false

	@Volatile private var pongNonce = 0L
	private val wakePipe: Array<ParcelFileDescriptor> = ParcelFileDescriptor.createPipe()

	/**
	 * Says hello until the computer answers. It repeats because a USB accessory stays connected even
	 * when nothing's running on the computer, so the first hellos may land before anyone's reading.
	 */
	fun handshake(): Proto.Welcome {
		val session = SecureRandom().nextLong()
		val hello = Proto.encodeHello(session)
		val helloThread = thread(name = "akiyama-hello", isDaemon = true) {
			try {
				while (!Thread.currentThread().isInterrupted && !stopping) {
					Os.write(link, hello, 0, hello.size)
					Thread.sleep(HELLO_INTERVAL_MILLIS)
				}
			} catch (_: ErrnoException) {
				// Reading from the link will find the same problem.
			} catch (_: InterruptedException) {
			}
		}
		try {
			reader.skipTo(Proto.welcomeStart(session))
			reader.next()
			return Proto.decodeWelcome(reader.selectPayload())
		} finally {
			helloThread.interrupt()
		}
	}

	/** Runs until the session ends, and returns what went wrong if anything did. */
	fun run(
		tun: FileDescriptor,
		resolver: DnsResolver,
		mtu: Int,
		gateway: InetAddress,
		resolverSource: InetAddress,
	): IOException? {
		lastHeardNanos = System.nanoTime()
		val gatewayBits = ByteBuffer.wrap(gateway.address).int
		val sourceBits = ByteBuffer.wrap(resolverSource.address).int
		val linkReader = LinkReader(link)
		linkReader.start()
		source = linkReader::take
		thread(name = "akiyama-up", isDaemon = true) {
			guarded { sendLoop(tun, resolver, mtu, gatewayBits) }
		}
		thread(name = "akiyama-down", isDaemon = true) {
			guarded { receiveLoop(tun, resolver, sourceBits) }
		}
		finished.await()
		wake()
		return failure.get()
	}

	/** Ends the session from this side. The computer hears a goodbye. */
	fun stop() {
		stopping = true
		wake()
	}

	private fun wake() {
		try {
			Os.write(wakePipe[1].fileDescriptor, WAKE_BYTE, 0, 1)
		} catch (_: ErrnoException) {
		}
	}

	private inline fun guarded(loop: () -> Unit) {
		Process.setThreadPriority(FAST_PRIORITY)
		try {
			loop()
		} catch (error: ErrnoException) {
			if (!stopping) failure.compareAndSet(null, IOException(error.message, error))
		} catch (error: IOException) {
			if (!stopping) failure.compareAndSet(null, error)
		} finally {
			finished.countDown()
		}
	}

	/** Link to Android. */
	private fun receiveLoop(tun: FileDescriptor, resolver: DnsResolver, sourceBits: Int) {
		val buffer = reader.buffer
		while (!stopping) {
			reader.next()
			lastHeardNanos = System.nanoTime()
			val start = reader.payloadStart
			val length = reader.payloadLength
			when (reader.type) {
				Proto.TYPE_PACKET -> {
					stats.bytesReceived += length.toLong()
					if (length >= IPV4_MIN && buffer.getInt(start + IPV4_DST) == sourceBits && isIpv4(buffer, start)) {
						val packet = ByteArray(length)
						reader.selectPayload().get(packet)
						(resolver.incoming(packet, length) as? DnsResolver.Incoming.Reply)?.let {
							writeTun(tun, it.packet)
						}
					} else {
						writeTun(tun, reader.selectPayload())
					}
				}

				Proto.TYPE_PING -> if (length >= Long.SIZE_BYTES) {
					pongNonce = buffer.getLong(start)
					pongDue = true
					wake()
				}

				Proto.TYPE_PONG -> if (length >= Long.SIZE_BYTES) {
					val micros = (System.nanoTime() / NANOS_PER_MICRO) - buffer.getLong(start)
					stats.rttMicros = micros.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
				}

				Proto.TYPE_BYE -> return
			}
		}
	}

	/** Android to link. Packets that are already waiting go out together as one write. */
	private fun sendLoop(tun: FileDescriptor, resolver: DnsResolver, mtu: Int, gatewayBits: Int) {
		val maxFrame = Proto.HEADER_LEN + mtu + TUN_SLACK
		val batch = ByteBuffer.allocateDirect(maxOf(WRITE_BATCH_BYTES, maxFrame * 2))
		val spaceNeeded = maxFrame + CONTROL_ROOM
		var used = 0
		var nextPing = System.nanoTime()

		val tunPoll = StructPollfd().apply {
			fd = tun
			events = OsConstants.POLLIN.toShort()
		}
		val wakePoll = StructPollfd().apply {
			fd = wakePipe[0].fileDescriptor
			events = OsConstants.POLLIN.toShort()
		}
		val polls = arrayOf(tunPoll, wakePoll)
		val drain = ByteArray(64)

		fun flush() {
			if (used == 0) return
			batch.limit(used).position(0)
			while (batch.hasRemaining()) Os.write(link, batch)
			used = 0
		}

		while (!stopping) {
			val now = System.nanoTime()
			if (now - lastHeardNanos > LINK_TIMEOUT_NANOS) throw IOException("The computer went quiet.")
			if (pongDue) {
				pongDue = false
				used = putControl(batch, used, Proto.TYPE_PONG, pongNonce)
			}
			if (now - nextPing >= 0) {
				used = putControl(batch, used, Proto.TYPE_PING, now / NANOS_PER_MICRO)
				nextPing = now + PING_INTERVAL_NANOS
			}
			if (batch.capacity() - used < spaceNeeded) flush()

			batch.limit(used + Proto.HEADER_LEN + mtu + TUN_SLACK).position(used + Proto.HEADER_LEN)
			val count = try {
				Os.read(tun, batch)
			} catch (error: ErrnoException) {
				if (error.errno != OsConstants.EAGAIN) throw error
				-1
			}

			if (count > 0) {
				val packet = used + Proto.HEADER_LEN
				if (isDnsQuery(batch, packet, count, gatewayBits)) {
					val bytes = ByteArray(count)
					batch.limit(packet + count).position(packet)
					batch.get(bytes)
					when (val outcome = resolver.outgoing(bytes, count)) {
						is DnsResolver.Outgoing.Pass -> used = putPacket(batch, used, bytes)
						is DnsResolver.Outgoing.Query -> used = putPacket(batch, used, outcome.packet)
						is DnsResolver.Outgoing.Reply -> writeTun(tun, outcome.packet)
						is DnsResolver.Outgoing.Drop -> {}
					}
				} else {
					batch.put(used, Proto.TYPE_PACKET.toByte())
					batch.putShort(used + 1, count.toShort())
					used = packet + count
					stats.bytesSent += count.toLong()
				}
				continue
			}

			// Nothing more is waiting, so send what there is, and sleep until something turns up.
			flush()
			val waitMillis = ((nextPing - System.nanoTime()) / NANOS_PER_MILLI).coerceIn(
				1,
				PING_INTERVAL_MILLIS,
			).toInt()
			try {
				Os.poll(polls, waitMillis)
			} catch (error: ErrnoException) {
				if (error.errno != OsConstants.EINTR) throw error
			}
			if (wakePoll.revents.toInt() != 0) {
				try {
					Os.read(wakePipe[0].fileDescriptor, drain, 0, drain.size)
				} catch (_: ErrnoException) {
				}
			}
		}

		// A phone can't interrupt its own read of a USB accessory. The goodbye gets answered, and
		// that's what wakes the other thread up.
		val bye = Proto.encodeBye(Proto.BYE_NORMAL)
		flush()
		Os.write(link, bye, 0, bye.size)
	}

	private fun putPacket(batch: ByteBuffer, used: Int, packet: ByteArray): Int {
		batch.limit(batch.capacity()).position(used)
		batch.put(Proto.TYPE_PACKET.toByte()).putShort(packet.size.toShort()).put(packet)
		stats.bytesSent += packet.size.toLong()
		return batch.position()
	}

	private fun putControl(batch: ByteBuffer, used: Int, type: Int, value: Long): Int {
		batch.limit(batch.capacity()).position(used)
		batch.put(type.toByte()).putShort(Long.SIZE_BYTES.toShort()).putLong(value)
		return batch.position()
	}

	private fun writeTun(tun: FileDescriptor, packet: ByteBuffer) {
		try {
			Os.write(tun, packet)
		} catch (_: ErrnoException) {
			// The device is closing, or its queue is full. Either way, this packet is lost.
		}
	}

	private fun writeTun(tun: FileDescriptor, packet: ByteArray) {
		try {
			Os.write(tun, packet, 0, packet.size)
		} catch (_: ErrnoException) {
		}
	}

	private fun isIpv4(buffer: ByteBuffer, packet: Int) = (buffer.get(packet).toInt() shr 4) and 0x0f == 4

	/** Whether a packet is UDP to the resolver's address and port. */
	private fun isDnsQuery(buffer: ByteBuffer, packet: Int, length: Int, gatewayBits: Int): Boolean {
		if (length < IPV4_MIN + UDP_HEADER || !isIpv4(buffer, packet)) return false
		if (buffer.get(packet + IPV4_PROTOCOL).toInt() != UDP_PROTOCOL) return false
		if (buffer.getInt(packet + IPV4_DST) != gatewayBits) return false
		val headerLength = (buffer.get(packet).toInt() and 0x0f) * 4
		return length >= headerLength + UDP_HEADER && buffer.getShort(packet + headerLength + 2).toInt() == DNS_PORT
	}

	/** Lets go of what this session holds. The link itself belongs to whoever opened it. */
	fun close() {
		for (end in wakePipe) {
			try {
				end.close()
			} catch (_: IOException) {
			}
		}
	}

	private companion object {
		const val HELLO_INTERVAL_MILLIS = 1_000L
		const val NANOS_PER_MILLI = 1_000_000L
		const val NANOS_PER_MICRO = 1_000L
		const val PING_INTERVAL_MILLIS = 2_000L
		const val PING_INTERVAL_NANOS = PING_INTERVAL_MILLIS * NANOS_PER_MILLI
		const val LINK_TIMEOUT_NANOS = 8_000L * NANOS_PER_MILLI
		const val WRITE_BATCH_BYTES = 128 * 1024
		const val TUN_SLACK = 64
		const val CONTROL_ROOM = 64
		const val FAST_PRIORITY = Process.THREAD_PRIORITY_DISPLAY
		const val IPV4_MIN = 20
		const val IPV4_PROTOCOL = 9
		const val IPV4_DST = 16
		const val UDP_PROTOCOL = 17
		const val UDP_HEADER = 8
		const val DNS_PORT = 53
		val WAKE_BYTE = byteArrayOf(1)
	}
}
