package com.wonderhoylabs.akiyama.link

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.net.Socket

/** An open connection to a computer. */
interface Link : Closeable {
    val fd: FileDescriptor
}

/**
 * Whether this accessory is still plugged in. Comparing accessories reads their serial numbers, and
 * Android refuses that once the permission has gone with the accessory, so a refusal means no.
 */
fun UsbManager.stillAttached(accessory: UsbAccessory): Boolean = try {
    accessoryList?.contains(accessory) == true
} catch (_: SecurityException) {
    false
}

/** The USB accessory: the phone's end of the cable, as a file. */
class AccessoryLink(manager: UsbManager, accessory: UsbAccessory) : Link {
    private val file: ParcelFileDescriptor = open(manager, accessory)
        ?: throw IOException("Couldn't open the USB connection. Try unplugging the phone and plugging it back in.")
    override val fd: FileDescriptor get() = file.fileDescriptor

    override fun close() {
        runCatching { file.close() }
    }

    private fun open(manager: UsbManager, accessory: UsbAccessory): ParcelFileDescriptor? = try {
        manager.openAccessory(accessory)
    } catch (error: SecurityException) {
        // It was replaced or unplugged after this got hold of it.
        throw IOException("The USB connection went away.", error)
    } catch (error: IllegalArgumentException) {
        throw IOException("The USB connection went away.", error)
    }
}

/** A socket on the phone that `adb reverse` carries to the computer. */
class AdbLink : Link {
    private val socket = LocalSocket()

    init {
        socket.connect(LocalSocketAddress(SOCKET_NAME))
    }

    override val fd: FileDescriptor get() = socket.fileDescriptor

    override fun close() {
        runCatching { socket.shutdownInput() }
        runCatching { socket.close() }
    }

    private companion object {
        /** crates/akiyama/src/adb.rs asks `adb` for this same name. */
        const val SOCKET_NAME = "akiyama"
    }
}

/** Plain TCP, which debug builds can use to test on an emulator with no USB to speak of. */
class TcpLink(private val socket: Socket) : Link {
    private val file = ParcelFileDescriptor.fromSocket(socket)
    override val fd: FileDescriptor get() = file.fileDescriptor

    override fun close() {
        runCatching { file.close() }
        runCatching { socket.close() }
    }
}
