package com.wonderhoylabs.akiyama.client

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.ErrnoException
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wonderhoylabs.akiyama.AkiyamaState
import com.wonderhoylabs.akiyama.BuildConfig
import com.wonderhoylabs.akiyama.R
import com.wonderhoylabs.akiyama.TunnelStatus
import com.wonderhoylabs.akiyama.link.AccessoryLink
import com.wonderhoylabs.akiyama.link.AdbLink
import com.wonderhoylabs.akiyama.link.DnsResolver
import com.wonderhoylabs.akiyama.link.Link
import com.wonderhoylabs.akiyama.link.LinkSession
import com.wonderhoylabs.akiyama.link.Proto
import com.wonderhoylabs.akiyama.link.TcpLink
import com.wonderhoylabs.akiyama.link.tidyComputerName
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** A VPN that carries all of this phone's traffic to a computer over a USB cable. */
class TunnelService : VpnService() {
    @Volatile private var stopping = false
    private val shutDownOnce = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile private var session: LinkSession? = null
    @Volatile private var link: Link? = null
    @Volatile private var tunnel: ParcelFileDescriptor? = null
    private var tunnelMtu = 0
    private var resolver: DnsResolver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) shutDown()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutDown()
            return Service.START_NOT_STICKY
        }
        if (worker != null) return Service.START_NOT_STICKY

        val accessory = intent?.parcelable<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
        val useAdb = intent?.getBooleanExtra(EXTRA_ADB, false) == true
        val devTcp = intent?.getStringExtra(EXTRA_DEV_TCP)?.takeIf { BuildConfig.DEBUG }
        if (accessory == null && !useAdb && devTcp == null) {
            stopSelf()
            return Service.START_NOT_STICKY
        }

        val computer = tidyComputerName(accessory?.description)
        AkiyamaState.setError(null)
        AkiyamaState.setStatus(TunnelStatus.Connecting(computer))
        goForeground(R.string.notification_connecting, computer)
        if (accessory != null) {
            ContextCompat.registerReceiver(
                this,
                detachReceiver,
                IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        // The phone is plugged into a computer, so it's charging, and sleeping would only slow this down.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "akiyama:tunnel")
            .apply { acquire() }

        worker = thread(name = "akiyama-tunnel", isDaemon = true) { runTunnel(accessory, useAdb, devTcp, computer) }
        return Service.START_NOT_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over, or the user revoked this one in Settings.
        shutDown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun runTunnel(accessory: UsbAccessory?, useAdb: Boolean, devTcp: String?, computer: String?) {
        var reason: String? = null
        while (!stopping) {
            var opened: Link? = null
            var current: LinkSession? = null
            try {
                opened = openLink(accessory, useAdb, devTcp)
                link = opened
                current = LinkSession(opened.fd)
                session = current
                AkiyamaState.setStatus(TunnelStatus.Waiting(computer, useAdb))
                val welcome = current.handshake()
                if (stopping) break
                val established = ensureTunnel(welcome)
                AkiyamaState.setStatus(TunnelStatus.Connected(computer, current.stats))
                goForeground(R.string.notification_connected, computer)
                val failure = current.run(
                    established.fileDescriptor,
                    resolver ?: throw IOException("The resolver went away."),
                    welcome.mtu,
                    welcome.gatewayV4,
                    Proto.RESOLVER_V4,
                )
                reason = failure?.message
            } catch (error: IOException) {
                reason = error.message
            } catch (error: ErrnoException) {
                reason = error.message
            } catch (error: IllegalArgumentException) {
                // Android refuses settings it doesn't like when building the VPN.
                AkiyamaState.setError(error.message)
                break
            } finally {
                session = null
                link = null
                current?.close()
                opened?.close()
            }
            if (stopping) break
            if (accessory != null && !stillAttached(accessory)) break
            Log.i(TAG, "link ended: $reason")
            AkiyamaState.setStatus(TunnelStatus.Reconnecting(computer, reason))
            goForeground(R.string.notification_reconnecting, computer)
            try {
                Thread.sleep(RETRY_MILLIS)
            } catch (_: InterruptedException) {
                break
            }
        }
        shutDown()
    }

    private fun openLink(accessory: UsbAccessory?, useAdb: Boolean, devTcp: String?): Link = when {
        accessory != null -> AccessoryLink(getSystemService(UsbManager::class.java), accessory)
        useAdb -> AdbLink()
        else -> {
            val address = devTcp ?: throw IOException("Nothing to connect to.")
            val socket = Socket()
            // A VPN's own socket mustn't go through the VPN. Java only creates the real socket once
            // it's bound, and before that this would have nothing to act on.
            socket.bind(null)
            if (!protect(socket)) throw IOException("Couldn't keep the link out of the VPN.")
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address.substringBeforeLast(':'), address.substringAfterLast(':').toInt()))
            TcpLink(socket)
        }
    }

    private fun stillAttached(accessory: UsbAccessory): Boolean =
        getSystemService(UsbManager::class.java).accessoryList?.contains(accessory) == true

    private fun ensureTunnel(welcome: Proto.Welcome): ParcelFileDescriptor {
        resolver?.setUpstream(welcome.upstreamDns)
        // The computer may have restarted with a different MTU, and the device has to match it.
        tunnel?.takeIf { welcome.mtu == tunnelMtu }?.let { return it }

        if (resolver == null) resolver = DnsResolver(welcome.gatewayV4, Proto.RESOLVER_V4, welcome.upstreamDns)
        val previous = tunnel
        val established = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(welcome.mtu)
            .addAddress(welcome.clientV4, welcome.prefixV4)
            .addAddress(welcome.clientV6, welcome.prefixV6)
            .addRoute("0.0.0.0", 0)
            // Without this, IPv6 would slip past the tunnel on networks that have it.
            .addRoute("::", 0)
            .addDnsServer(welcome.gatewayV4)
            .setBlocking(false)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setMetered(false) }
            .establish() ?: throw IOException("Android didn't allow the VPN. Open Akiyama and try again.")
        tunnel = established
        tunnelMtu = welcome.mtu
        // Android moves traffic to the new device as it comes up, so nothing gets out between them.
        previous?.let { runCatching { it.close() } }
        markAsConnected()
        return established
    }

    /**
     * With no other network, Android can treat a VPN as having nothing underneath it and tell apps
     * they're offline. Naming the VPN as its own underlying network tells them otherwise.
     */
    private fun markAsConnected() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        repeat(VPN_LOOKUP_TRIES) {
            @Suppress("DEPRECATION")
            val vpn = connectivity.allNetworks.firstOrNull { network ->
                connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            if (vpn != null) {
                setUnderlyingNetworks(arrayOf(vpn))
                return
            }
            Thread.sleep(VPN_LOOKUP_DELAY_MILLIS)
        }
    }

    private fun shutDown() {
        if (!shutDownOnce.compareAndSet(false, true)) return
        stopping = true
        session?.stop()
        // A read blocked on a USB accessory can't be interrupted, so nothing here waits for one.
        // The goodbye that stop() sends gets an answer, and that's what lets it finish.
        thread(name = "akiyama-close", isDaemon = true) {
            Thread.sleep(CLOSE_GRACE_MILLIS)
            link?.close()
        }
        tunnel?.let { runCatching { it.close() } }
        tunnel = null
        resolver = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (receiverRegistered) {
            receiverRegistered = false
            runCatching { unregisterReceiver(detachReceiver) }
        }
        AkiyamaState.setStatus(TunnelStatus.Idle)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(text: Int, computer: String?) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            statusNotification(
                this,
                getString(text, computer ?: getString(R.string.the_computer)),
                getString(R.string.disconnect),
                Intent(this, TunnelService::class.java).setAction(ACTION_STOP),
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    companion object {
        private const val TAG = "AkiyamaTunnel"
        private const val ACTION_STOP = "com.wonderhoylabs.akiyama.STOP"
        private const val EXTRA_ADB = "adb"
        private const val EXTRA_DEV_TCP = "dev_tcp"
        private const val NOTIFICATION_ID = 1
        private const val RETRY_MILLIS = 1_000L
        private const val CLOSE_GRACE_MILLIS = 300L
        private const val VPN_LOOKUP_TRIES = 20
        private const val VPN_LOOKUP_DELAY_MILLIS = 100L

        fun connectAccessory(context: Context, accessory: UsbAccessory) {
            start(context, Intent(context, TunnelService::class.java).putExtra(UsbManager.EXTRA_ACCESSORY, accessory))
        }

        fun connectAdb(context: Context) {
            start(context, Intent(context, TunnelService::class.java).putExtra(EXTRA_ADB, true))
        }

        /** Debug builds only: connects over TCP, to test on an emulator. */
        fun connectTcp(context: Context, address: String) {
            start(context, Intent(context, TunnelService::class.java).putExtra(EXTRA_DEV_TCP, address))
        }

        fun disconnect(context: Context) {
            context.startService(Intent(context, TunnelService::class.java).setAction(ACTION_STOP))
        }

        private fun start(context: Context, intent: Intent) {
            context.startForegroundService(intent)
        }

        private inline fun <reified T : android.os.Parcelable> Intent.parcelable(name: String): T? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(name, T::class.java)
            } else {
                @Suppress("DEPRECATION")
                getParcelableExtra(name)
            }
    }
}
