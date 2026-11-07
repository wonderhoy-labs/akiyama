package com.wonderhoylabs.akiyama.link

import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The Rust side's proto tests check these same bytes. Change them together. */
class ProtoTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val session = 0x0102030405060708L

    private val welcomePayload = bytes(
        1, 2, 3, 4, 5, 6, 7, 8, // session
        0x3e, 0x80, // mtu
        10, 233, 78, 2, // client v4
        10, 233, 78, 1, // gateway v4
        24, // prefix v4
        0xfd, 0x41, 0x6b, 0x69, 0x79, 0x61, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, // client v6
        0xfd, 0x41, 0x6b, 0x69, 0x79, 0x61, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, // gateway v6
        64, // prefix v6
        1, 1, 1, 1, // upstream dns
    )

    @Test
    fun framesMatchTheDocumentedBytes() {
        assertArrayEquals(
            bytes(1, 0, 13, 'A'.code, 'K'.code, 'I'.code, 'Y'.code, 1, 1, 2, 3, 4, 5, 6, 7, 8),
            Proto.encodeHello(session),
        )
        assertArrayEquals(bytes(2, 0, 56, 1, 2, 3, 4, 5, 6, 7, 8), Proto.welcomeStart(session))
        assertArrayEquals(bytes(6, 0, 1, 1), Proto.encodeBye(Proto.BYE_VERSION_MISMATCH))
    }

    /** The phone writes welcomes too, when it's the one sharing. They have to match Rust's. */
    @Test
    fun aWelcomeEncodesToTheDocumentedBytes() {
        assertArrayEquals(bytes(2, 0, 56) + welcomePayload, Proto.encodeWelcome(session, 16000))
    }

    @Test
    fun helloStartMatchesAHello() {
        assertArrayEquals(Proto.helloStart(), Proto.encodeHello(session).copyOf(Proto.helloStart().size))
    }

    @Test
    fun welcomeDecodes() {
        val welcome = Proto.decodeWelcome(ByteBuffer.wrap(welcomePayload))
        assertEquals(16000, welcome.mtu)
        assertEquals(InetAddress.getByName("10.233.78.2"), welcome.clientV4)
        assertEquals(InetAddress.getByName("10.233.78.1"), welcome.gatewayV4)
        assertEquals(24, welcome.prefixV4)
        assertEquals(InetAddress.getByName("fd41:6b69:7961::2"), welcome.clientV6)
        assertEquals(InetAddress.getByName("fd41:6b69:7961::1"), welcome.gatewayV6)
        assertEquals(64, welcome.prefixV6)
        assertEquals(InetAddress.getByName("1.1.1.1"), welcome.upstreamDns)
    }

    @Test
    fun welcomeAcceptsTrailingFields() {
        val welcome = Proto.decodeWelcome(ByteBuffer.wrap(welcomePayload + bytes(0xff)))
        assertEquals(16000, welcome.mtu)
    }

    @Test
    fun aShortWelcomeIsAnError() {
        assertThrows(IOException::class.java) { Proto.decodeWelcome(ByteBuffer.wrap(ByteArray(10))) }
    }
}
