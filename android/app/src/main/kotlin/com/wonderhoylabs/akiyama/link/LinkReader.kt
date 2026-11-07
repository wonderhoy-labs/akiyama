package com.wonderhoylabs.akiyama.link

import android.os.Build
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import java.io.EOFException
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/**
 * Keeps one thread doing nothing but reading from the link, handing what it gets to another thread.
 *
 * This is what sets the download speed. A USB accessory only ever has one read waiting, 16 KiB at a
 * time, and between one read finishing and the next starting, the cable sits idle. So that gap has to
 * be as small as possible, and nothing else can happen in it, least of all a write to Android's
 * network stack, which takes far longer than the read.
 */
class LinkReader(private val link: FileDescriptor) {
    private val slots = Array(SLOTS) { ByteBuffer.allocateDirect(SLOT_BYTES) }
    private val lengths = IntArray(SLOTS)
    @Volatile private var produced = 0L
    @Volatile private var consumed = 0L
    @Volatile private var failure: IOException? = null
    @Volatile private var waitingConsumer: Thread? = null
    @Volatile private var waitingProducer: Thread? = null

    fun start() {
        thread(name = "akiyama-link", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                readLoop()
            } catch (error: ErrnoException) {
                failure = IOException(error.message, error)
            } catch (error: IOException) {
                failure = error
            }
            waitingConsumer?.let(LockSupport::unpark)
        }
    }

    private fun readLoop() {
        var next = produced
        while (true) {
            while (next - consumed >= SLOTS) {
                waitingProducer = Thread.currentThread()
                if (next - consumed >= SLOTS) LockSupport.parkNanos(PARK_NANOS)
                waitingProducer = null
            }
            val index = (next % SLOTS).toInt()
            val slot = slots[index]
            slot.clear()
            val count = Os.read(link, slot)
            if (count <= 0) throw EOFException("the link closed")
            lengths[index] = count
            next++
            produced = next
            waitingConsumer?.let(LockSupport::unpark)
        }
    }

    /** Waits for data and moves one read's worth to [into], which has to have room for it. */
    fun take(into: ByteBuffer): Int {
        val next = consumed
        var spins = 0
        while (produced == next) {
            failure?.let { throw it }
            if (spins < SPINS) {
                spins++
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Thread.onSpinWait()
            } else {
                waitingConsumer = Thread.currentThread()
                if (produced == next && failure == null) LockSupport.parkNanos(PARK_NANOS)
                waitingConsumer = null
            }
        }
        val index = (next % SLOTS).toInt()
        val slot = slots[index]
        val count = lengths[index]
        slot.limit(count).position(0)
        into.put(slot)
        consumed = next + 1
        waitingProducer?.let(LockSupport::unpark)
        return count
    }

    private companion object {
        const val SLOTS = 16
        /** A USB accessory hands over at most 16 KiB, and sockets get to use the rest. */
        const val SLOT_BYTES = 64 * 1024
        /** A brief wait before sleeping, since under load the next read is only moments away. */
        const val SPINS = 2_000
        const val PARK_NANOS = 50_000_000L
    }
}
