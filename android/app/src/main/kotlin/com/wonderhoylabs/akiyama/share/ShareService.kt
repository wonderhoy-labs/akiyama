package com.wonderhoylabs.akiyama.share

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.system.ErrnoException
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wonderhoylabs.akiyama.AkiyamaState
import com.wonderhoylabs.akiyama.R
import com.wonderhoylabs.akiyama.ShareStatus
import com.wonderhoylabs.akiyama.client.statusNotification
import com.wonderhoylabs.akiyama.link.AccessoryLink
import com.wonderhoylabs.akiyama.link.Link
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Shares this phone's network with a plugged-in computer. There's no VPN involved: a NAT turns the
 * computer's packets into ordinary sockets.
 */
class ShareService : Service() {
    private val shutDownOnce = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile private var session: ShareSession? = null
    @Volatile private var link: Link? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) shutDown()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutDown()
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY

        val accessory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }
        if (accessory == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val blockLan = intent?.getBooleanExtra(EXTRA_BLOCK_LAN, false) == true
        val computer = accessory.description?.takeIf { it.isNotBlank() } ?: getString(R.string.the_computer)

        AkiyamaState.setError(null)
        AkiyamaState.setShare(ShareStatus.Waiting(computer))
        goForeground(getString(R.string.status_share_waiting_plain, computer))
        ContextCompat.registerReceiver(
            this,
            detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "akiyama:share")
            .apply { acquire() }

        worker = thread(name = "akiyama-share", isDaemon = true) {
            try {
                val opened = AccessoryLink(getSystemService(UsbManager::class.java), accessory)
                link = opened
                val current = ShareSession(opened.fd, blockLan) { stats ->
                    if (shutDownOnce.get()) return@ShareSession
                    if (stats != null) {
                        AkiyamaState.setShare(ShareStatus.Sharing(computer, stats))
                        goForeground(getString(R.string.status_sharing, computer))
                    } else {
                        AkiyamaState.setShare(ShareStatus.Waiting(computer))
                        goForeground(getString(R.string.status_share_waiting_plain, computer))
                    }
                }
                session = current
                current.run()
            } catch (error: IOException) {
                Log.i(TAG, "link ended: ${error.message}")
                if (!shutDownOnce.get()) AkiyamaState.setError(error.message)
            } catch (error: ErrnoException) {
                Log.i(TAG, "link ended: ${error.message}")
            }
            shutDown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun shutDown() {
        if (!shutDownOnce.compareAndSet(false, true)) return
        session?.stop()
        // A read blocked on a USB accessory can't be interrupted, so nothing here waits for one.
        thread(name = "akiyama-close", isDaemon = true) {
            Thread.sleep(CLOSE_GRACE_MILLIS)
            link?.close()
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (receiverRegistered) {
            receiverRegistered = false
            runCatching { unregisterReceiver(detachReceiver) }
        }
        AkiyamaState.setShare(ShareStatus.Stopped)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(text: String) {
        val stop = Intent(this, ShareService::class.java).setAction(ACTION_STOP)
        val notification = statusNotification(this, text, getString(R.string.stop_sharing), stop)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    companion object {
        private const val TAG = "AkiyamaShare"
        private const val ACTION_STOP = "com.wonderhoylabs.akiyama.STOP_SHARING"
        private const val EXTRA_BLOCK_LAN = "block_lan"
        private const val NOTIFICATION_ID = 2
        private const val CLOSE_GRACE_MILLIS = 300L

        fun start(context: Context, accessory: UsbAccessory, blockLan: Boolean) {
            val intent = Intent(context, ShareService::class.java)
                .putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
                .putExtra(EXTRA_BLOCK_LAN, blockLan)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ShareService::class.java).setAction(ACTION_STOP))
        }
    }
}
