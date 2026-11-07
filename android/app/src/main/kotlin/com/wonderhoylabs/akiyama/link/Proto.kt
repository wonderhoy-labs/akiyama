package com.wonderhoylabs.akiyama.link

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * The Akiyama wire protocol, matching `crates/akiyama-core/src/proto.rs`. ProtoTest checks the same
 * bytes as the Rust tests do, and `docs/protocol.md` describes it all for people.
 */
object Proto {
    const val VERSION = 1
    val MAGIC = byteArrayOf('A'.code.toByte(), 'K'.code.toByte(), 'I'.code.toByte(), 'Y'.code.toByte())

    /** Where the client resolver's own queries come from. Nothing else sends from this address. */
    val RESOLVER_V4: InetAddress = InetAddress.getByName("10.233.78.3")
    private val CLIENT_V4: InetAddress = InetAddress.getByName("10.233.78.2")
    private val GATEWAY_V4: InetAddress = InetAddress.getByName("10.233.78.1")
    private val CLIENT_V6: InetAddress = InetAddress.getByName("fd41:6b69:7961::2")
    private val GATEWAY_V6: InetAddress = InetAddress.getByName("fd41:6b69:7961::1")
    private const val PREFIX_V4 = 24
    private const val PREFIX_V6 = 64
    val DEFAULT_DNS: InetAddress = InetAddress.getByName("1.1.1.1")

    /** The accessory's serial string says what the computer wants. `aoa.rs` sends these. */
    const val ROLE_COMPUTER_SHARES = "akiyama-server"
    const val ROLE_COMPUTER_WANTS = "akiyama-client"

    const val TYPE_HELLO = 1
    const val TYPE_WELCOME = 2
    const val TYPE_PACKET = 3
    const val TYPE_PING = 4
    const val TYPE_PONG = 5
    const val TYPE_BYE = 6

    const val HEADER_LEN = 3
    const val WELCOME_LEN = 8 + 2 + 4 + 4 + 1 + 16 + 16 + 1 + 4
    const val HELLO_LEN = 4 + 1 + 8
    const val BYE_NORMAL = 0
    const val BYE_VERSION_MISMATCH = 1

    /** What a server tells a phone about the network it's joining. */
    data class Welcome(
        val mtu: Int,
        val clientV4: Inet4Address,
        val gatewayV4: Inet4Address,
        val prefixV4: Int,
        val clientV6: Inet6Address,
        val gatewayV6: Inet6Address,
        val prefixV6: Int,
        val upstreamDns: Inet4Address,
    )

    fun encodeHello(session: Long): ByteArray = ByteBuffer.allocate(HEADER_LEN + HELLO_LEN)
        .put(TYPE_HELLO.toByte())
        .putShort(HELLO_LEN.toShort())
        .put(MAGIC)
        .put(VERSION.toByte())
        .putLong(session)
        .array()

    /** What the start of a hello looks like, to find it among any leftovers. */
    fun helloStart(): ByteArray = ByteBuffer.allocate(HEADER_LEN + MAGIC.size)
        .put(TYPE_HELLO.toByte())
        .putShort(HELLO_LEN.toShort())
        .put(MAGIC)
        .array()

    /** The answer to a hello, when the phone's the one sharing its network. */
    fun encodeWelcome(session: Long, mtu: Int): ByteArray = ByteBuffer.allocate(HEADER_LEN + WELCOME_LEN)
        .put(TYPE_WELCOME.toByte())
        .putShort(WELCOME_LEN.toShort())
        .putLong(session)
        .putShort(mtu.toShort())
        .put(CLIENT_V4.address)
        .put(GATEWAY_V4.address)
        .put(PREFIX_V4.toByte())
        .put(CLIENT_V6.address)
        .put(GATEWAY_V6.address)
        .put(PREFIX_V6.toByte())
        .put(DEFAULT_DNS.address)
        .array()

    /** What the start of the answer to a hello looks like, to find it among any leftovers. */
    fun welcomeStart(session: Long): ByteArray = ByteBuffer.allocate(HEADER_LEN + Long.SIZE_BYTES)
        .put(TYPE_WELCOME.toByte())
        .putShort(WELCOME_LEN.toShort())
        .putLong(session)
        .array()

    fun encodeBye(reason: Int): ByteArray = byteArrayOf(TYPE_BYE.toByte(), 0, 1, reason.toByte())

    /** Reads a welcome out of a frame's payload. Longer is fine: a later version may add fields. */
    fun decodeWelcome(payload: ByteBuffer): Welcome {
        if (payload.remaining() < WELCOME_LEN) throw IOException("welcome frame is too short")
        fun address(length: Int): InetAddress {
            val bytes = ByteArray(length)
            payload.get(bytes)
            return InetAddress.getByAddress(bytes)
        }
        payload.long // The session ID, which whoever found this frame has already checked.
        val mtu = payload.short.toInt() and 0xffff
        return Welcome(
            mtu = mtu,
            clientV4 = address(4) as Inet4Address,
            gatewayV4 = address(4) as Inet4Address,
            prefixV4 = payload.get().toInt() and 0xff,
            clientV6 = address(16) as Inet6Address,
            gatewayV6 = address(16) as Inet6Address,
            prefixV6 = payload.get().toInt() and 0xff,
            upstreamDns = address(4) as Inet4Address,
        )
    }
}
