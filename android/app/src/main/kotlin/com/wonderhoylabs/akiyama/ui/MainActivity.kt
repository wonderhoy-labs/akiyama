package com.wonderhoylabs.akiyama.ui

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderhoylabs.akiyama.AkiyamaState
import com.wonderhoylabs.akiyama.BuildConfig
import com.wonderhoylabs.akiyama.R
import com.wonderhoylabs.akiyama.client.TunnelService
import com.wonderhoylabs.akiyama.link.Proto
import com.wonderhoylabs.akiyama.link.tidyComputerName
import com.wonderhoylabs.akiyama.share.ShareService
import kotlinx.coroutines.flow.MutableStateFlow

/** What to connect to, once Android has agreed to the VPN. */
private sealed interface Target {
	data class Accessory(val accessory: UsbAccessory) : Target
	data object Adb : Target
	data class Tcp(val address: String) : Target
}

/** An accessory's serial string says what the computer wants, if the app's allowed to read it. */
private fun roleOf(accessory: UsbAccessory): ComputerRole {
	val serial = try {
		accessory.serial
	} catch (_: SecurityException) {
		null
	}
	return when (serial) {
		Proto.ROLE_COMPUTER_SHARES -> ComputerRole.Shares
		Proto.ROLE_COMPUTER_WANTS -> ComputerRole.Wants
		else -> ComputerRole.Unknown
	}
}

class MainActivity : ComponentActivity() {
	private var accessory: UsbAccessory? = null
	private val attached = MutableStateFlow<AttachedComputer?>(null)
	private val askDismissed = MutableStateFlow(false)
	private var waitingForConsent: Target? = null
	private var permissionReceiver: BroadcastReceiver? = null

	private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		val target = waitingForConsent
		waitingForConsent = null
		if (result.resultCode == Activity.RESULT_OK) {
			target?.let(::startTunnel)
		} else if (target != null) {
			AkiyamaState.setError(getString(R.string.vpn_refused))
		}
	}

	private val detachReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			if (intent.action != UsbManager.ACTION_USB_ACCESSORY_DETACHED) return
			askDismissed.value = false
			refreshAttached(gone = true)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		ContextCompat.registerReceiver(
			this,
			detachReceiver,
			IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED),
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
		// Turning the phone re-creates this with the same intent, which shouldn't reconnect anything.
		if (savedInstanceState == null) handleIntent(intent)
		setContent {
			val status by AkiyamaState.status.collectAsStateWithLifecycle()
			val share by AkiyamaState.share.collectAsStateWithLifecycle()
			val error by AkiyamaState.error.collectAsStateWithLifecycle()
			val computer by attached.collectAsStateWithLifecycle()
			val dismissed by askDismissed.collectAsStateWithLifecycle()
			val actions = remember {
				ScreenActions(
					onUseComputer = ::useComputer,
					onUseAdb = { connect(Target.Adb) },
					onShare = ::share,
					onNotNow = { askDismissed.value = true },
					onDisconnect = { TunnelService.disconnect(this) },
					onStopSharing = { ShareService.stop(this) },
					onDismissError = { AkiyamaState.setError(null) },
				)
			}
			AkiyamaTheme {
				Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
					AkiyamaScreen(ScreenState(status, share, error, computer, dismissed), actions)
				}
			}
		}
	}

	override fun onResume() {
		super.onResume()
		refreshAttached()
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		handleIntent(intent)
	}

	override fun onDestroy() {
		runCatching { unregisterReceiver(detachReceiver) }
		permissionReceiver?.let { runCatching { unregisterReceiver(it) } }
		super.onDestroy()
	}

	/** Android opens this screen when a computer switches the phone to accessory mode. */
	private fun handleIntent(intent: Intent?) {
		if (intent == null) return
		if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
			val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
			} else {
				@Suppress("DEPRECATION")
				intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
			} ?: return
			askDismissed.value = false
			setAccessory(found)
			// Anything else waits for a tap, and that includes not being able to tell.
			if (roleOf(found) == ComputerRole.Shares) connect(Target.Accessory(found))
		} else if (BuildConfig.DEBUG) {
			intent.getStringExtra(EXTRA_DEV_TCP)?.let { connect(Target.Tcp(it)) }
			intent.getIntExtra(EXTRA_DEV_TCP_LISTEN, 0).takeIf { it != 0 }?.let { port ->
				askForNotifications()
				ShareService.listenTcp(this, port)
			}
			if (intent.getBooleanExtra(EXTRA_DEV_ACCEPT_SHARE, false)) {
				refreshAttached()
				share(blockLan = false)
			}
		}
	}

	private fun refreshAttached(gone: Boolean = false) {
		// A detach can arrive before the list catches up, so that case doesn't ask the list.
		setAccessory(if (gone) null else getSystemService(UsbManager::class.java).accessoryList?.firstOrNull())
	}

	private fun setAccessory(found: UsbAccessory?) {
		accessory = found
		attached.value = found?.let { AttachedComputer(tidyComputerName(it.description), roleOf(it)) }
	}

	private fun useComputer() {
		val current = accessory ?: return
		withPermission(current) { connect(Target.Accessory(it)) }
	}

	/** Android 17 blocks private addresses without this, and sharing a network includes its LAN. */
	private fun share(blockLan: Boolean) {
		val current = accessory ?: return
		val wanted = mutableListOf<String>()
		if (needsPermission(Manifest.permission.POST_NOTIFICATIONS, Build.VERSION_CODES.TIRAMISU)) {
			wanted += Manifest.permission.POST_NOTIFICATIONS
		}
		if (!blockLan && needsPermission(Manifest.permission.ACCESS_LOCAL_NETWORK, LOCAL_NETWORK_PERMISSION_SDK)) {
			wanted += Manifest.permission.ACCESS_LOCAL_NETWORK
		}
		if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
		withPermission(current) { ShareService.start(this, it, blockLan) }
	}

	private fun connect(target: Target) {
		val prompt = VpnService.prepare(this)
		if (prompt == null) {
			startTunnel(target)
		} else {
			waitingForConsent = target
			consent.launch(prompt)
		}
	}

	private fun startTunnel(target: Target) {
		// Not any earlier, or this would land on top of the VPN's own question.
		askForNotifications()
		when (target) {
			is Target.Accessory -> TunnelService.connectAccessory(this, target.accessory)
			is Target.Adb -> TunnelService.connectAdb(this)
			is Target.Tcp -> TunnelService.connectTcp(this, target.address)
		}
	}

	/** Runs [then], first asking for access if Android didn't already give it by opening this screen. */
	private fun withPermission(accessory: UsbAccessory, then: (UsbAccessory) -> Unit) {
		val manager = getSystemService(UsbManager::class.java)
		if (manager.hasPermission(accessory)) {
			then(accessory)
			return
		}
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
					// Now the app's allowed to read what the computer asked for.
					setAccessory(accessory)
					then(accessory)
				}
			}
		}
		permissionReceiver?.let { runCatching { unregisterReceiver(it) } }
		permissionReceiver = receiver
		ContextCompat.registerReceiver(
			this,
			receiver,
			IntentFilter(ACTION_USB_PERMISSION),
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
		// Android adds its answer to this intent, so it has to be mutable.
		val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
		val answer = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
		manager.requestPermission(accessory, answer)
	}

	private fun askForNotifications() {
		if (needsPermission(Manifest.permission.POST_NOTIFICATIONS, Build.VERSION_CODES.TIRAMISU)) {
			requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_PERMISSIONS)
		}
	}

	private fun needsPermission(permission: String, sinceSdk: Int): Boolean =
		Build.VERSION.SDK_INT >= sinceSdk && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED

	private companion object {
		/** Debug builds only: `--es dev_tcp host:port` connects the VPN over TCP, for an emulator. */
		const val EXTRA_DEV_TCP = "dev_tcp"

		/** Debug builds only: `--ei dev_tcp_listen port` shares over TCP, for an emulator. */
		const val EXTRA_DEV_TCP_LISTEN = "dev_tcp_listen"

		/** Debug builds only: `--ez dev_accept_share true` answers the prompt, on a phone nobody's holding. */
		const val EXTRA_DEV_ACCEPT_SHARE = "dev_accept_share"
		const val ACTION_USB_PERMISSION = "com.wonderhoylabs.akiyama.USB_PERMISSION"
		const val REQUEST_PERMISSIONS = 1

		/** The version of Android that started enforcing the local network permission. */
		const val LOCAL_NETWORK_PERMISSION_SDK = 37
	}
}
