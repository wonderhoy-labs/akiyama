package com.wonderhoylabs.akiyama.share

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import com.wonderhoylabs.akiyama.link.FrameReader
import com.wonderhoylabs.akiyama.link.LinkReader
import com.wonderhoylabs.akiyama.link.LinkStats
import com.wonderhoylabs.akiyama.link.Proto
import java.io.FileDescriptor
import java.net.Inet4Address
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The phone's end of a link when it's the one sharing its network. Each hello starts a session with
 * a NAT of its own, and a computer can come and go while the cable stays plugged in.
 */
class ShareSession(
    private val link: FileDescriptor,
    private val blockLan: Boolean,
    private val onSessionChanged: (LinkStats?) -> Unit,
) {
    private val linkReader = LinkReader(link)
    private val reader = FrameReader { buffer -> linkReader.take(buffer) }
    @Volatile private var stopping = false
    private val natLock = Any()
    private var liveNat: NatEngine? = null

    /** Runs until the link ends or [stop] is called. */
    fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        linkReader.start()
        val helloStart = Proto.helloStart()
        var nextSession: Long? = null
        while (!stopping) {
            val session = nextSession ?: run {
                reader.skipTo(helloStart)
                reader.next()
                readHello() ?: return@run null
            }
            nextSession = null
            if (session == null) continue
            nextSession = serve(session)
        }
    }

    /** Reads on an accessory can't be interrupted, so [run] may not return until the next frame. */
    fun stop() {
        stopping = true
        stopNat()
    }

    private fun stopNat() = synchronized(natLock) {
        liveNat?.stop()
    }

    /** Returns the hello's session ID, or null after turning away a version this can't talk to. */
    private fun readHello(): Long? {
        val payload = reader.selectPayload()
        if (payload.remaining() < Proto.HELLO_LEN) return null
        payload.position(payload.position() + Proto.MAGIC.size)
        val version = payload.get().toInt() and 0xff
        val session = payload.long
        if (version != Proto.VERSION) {
            val bye = Proto.encodeBye(Proto.BYE_VERSION_MISMATCH)
            Os.write(link, bye, 0, bye.size)
            return null
        }
        return session
    }

    /** Serves one session, and returns the ID of the next if the computer started a new one. */
    private fun serve(session: Long): Long? {
        val welcome = Proto.encodeWelcome(session, MTU)
        Os.write(link, welcome, 0, welcome.size)

        val nat = NatEngine(Proto.DEFAULT_DNS as Inet4Address, blockLan, MTU)
        val stats = LinkStats()
        val finished = AtomicBoolean(false)
        val heard = AtomicLong(System.nanoTime())
        val pong = PendingPong()
        synchronized(natLock) { liveNat = nat }
        onSessionChanged(stats)
        val sender = thread(name = "akiyama-share-up", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            try {
                sendLoop(nat, stats, pong, heard, finished)
            } catch (_: ErrnoException) {
                // The computer went quiet or the link is going away. The reader may stay blocked
                // until a frame turns up, so the session ends here without waiting for it.
            }
            finished.set(true)
            stopNat()
            if (!stopping) onSessionChanged(null)
        }

        var next: Long? = null
        try {
            next = receiveLoop(nat, stats, pong, heard, session, finished)
        } finally {
            finished.set(true)
            stopNat()
            sender.join(SENDER_JOIN_MILLIS)
            synchronized(natLock) { liveNat = null }
            // A sender stuck in a write still needs it, and the process ending will clean that up.
            if (!sender.isAlive) nat.free()
            onSessionChanged(null)
        }
        return next
    }

    private class PendingPong {
        @Volatile var due = false
        @Volatile var nonce = 0L
    }

    private fun receiveLoop(
        nat: NatEngine,
        stats: LinkStats,
        pong: PendingPong,
        heard: AtomicLong,
        session: Long,
        finished: AtomicBoolean,
    ): Long? {
        val buffer = reader.buffer
        while (!stopping && !finished.get()) {
            reader.next()
            heard.set(System.nanoTime())
            val start = reader.payloadStart
            val length = reader.payloadLength
            when (reader.type) {
                Proto.TYPE_PACKET -> {
                    stats.bytesReceived += length.toLong()
                    nat.input(buffer, start, length)
                }
                Proto.TYPE_PING -> if (length >= Long.SIZE_BYTES) {
                    pong.nonce = buffer.getLong(start)
                    pong.due = true
                }
                Proto.TYPE_PONG -> if (length >= Long.SIZE_BYTES) {
                    val micros = (System.nanoTime() / NANOS_PER_MICRO) - buffer.getLong(start)
                    stats.rttMicros = micros.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                }
                Proto.TYPE_HELLO -> {
                    val other = readHello()
                    if (other != null && other != session) return other
                }
                Proto.TYPE_BYE -> return null
            }
        }
        return null
    }

    private fun sendLoop(nat: NatEngine, stats: LinkStats, pong: PendingPong, heard: AtomicLong, finished: AtomicBoolean) {
        val maxFrame = Proto.HEADER_LEN + MTU + SLACK
        val batch = ByteBuffer.allocateDirect(maxOf(WRITE_BATCH_BYTES, maxFrame * 2))
        var used = 0
        var nextPing = System.nanoTime()

        fun flush() {
            if (used == 0) return
            batch.limit(used).position(0)
            while (batch.hasRemaining()) Os.write(link, batch)
            used = 0
        }

        fun putControl(type: Int, value: Long) {
            batch.limit(batch.capacity()).position(used)
            batch.put(type.toByte()).putShort(Long.SIZE_BYTES.toShort()).putLong(value)
            used = batch.position()
        }

        while (!stopping && !finished.get()) {
            val now = System.nanoTime()
            if (now - heard.get() > LINK_TIMEOUT_NANOS) throw ErrnoException("the computer went quiet", 0)
            if (pong.due) {
                pong.due = false
                putControl(Proto.TYPE_PONG, pong.nonce)
            }
            if (now - nextPing >= 0) {
                putControl(Proto.TYPE_PING, now / NANOS_PER_MICRO)
                nextPing = now + PING_INTERVAL_NANOS
            }
            if (batch.capacity() - used < maxFrame + CONTROL_ROOM) flush()

            // Only wait when there's nothing to send. A pong waits at most this long.
            val wait = if (used == 0) WAIT_MILLIS else 0
            val count = nat.read(batch, used + Proto.HEADER_LEN, MTU + SLACK, wait)
            when {
                count > 0 -> {
                    batch.limit(batch.capacity())
                    batch.put(used, Proto.TYPE_PACKET.toByte())
                    batch.putShort(used + 1, count.toShort())
                    used += Proto.HEADER_LEN + count
                    stats.bytesSent += count.toLong()
                }
                count == 0 -> flush()
                else -> break
            }
        }
        flush()
        if (stopping) {
            val bye = Proto.encodeBye(Proto.BYE_NORMAL)
            Os.write(link, bye, 0, bye.size)
        }
    }

    private companion object {
        /** Bigger packets are what make the link fast. See DEFAULT_MTU in proto.rs. */
        const val MTU = 16_000
        const val SLACK = 64
        const val CONTROL_ROOM = 64
        const val WRITE_BATCH_BYTES = 128 * 1024
        const val WAIT_MILLIS = 100
        const val SENDER_JOIN_MILLIS = 1_000L
        const val NANOS_PER_MICRO = 1_000L
        const val PING_INTERVAL_NANOS = 2_000_000_000L
        const val LINK_TIMEOUT_NANOS = 8_000_000_000L
    }
}
