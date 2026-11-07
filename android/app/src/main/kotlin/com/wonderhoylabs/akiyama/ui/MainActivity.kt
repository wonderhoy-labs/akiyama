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
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderhoylabs.akiyama.AkiyamaState
import com.wonderhoylabs.akiyama.BuildConfig
import com.wonderhoylabs.akiyama.R
import com.wonderhoylabs.akiyama.ShareStatus
import com.wonderhoylabs.akiyama.TunnelStatus
import com.wonderhoylabs.akiyama.client.TunnelService
import com.wonderhoylabs.akiyama.link.LinkStats
import com.wonderhoylabs.akiyama.link.Proto
import com.wonderhoylabs.akiyama.share.ShareService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** What to connect to, once Android has agreed to the VPN. */
private sealed interface Target {
    data class Accessory(val accessory: UsbAccessory) : Target
    data object Adb : Target
    data class Tcp(val address: String) : Target
}

/** What the computer on the other end of an accessory wants, if the phone is allowed to read that. */
private fun roleOf(accessory: UsbAccessory): String? = try {
    accessory.serial
} catch (_: SecurityException) {
    null
}

class MainActivity : ComponentActivity() {
    private val requested = MutableStateFlow<Target?>(null)
    /** A computer that's asking to use this phone's network. It takes a tap to say yes. */
    private val asking = MutableStateFlow<UsbAccessory?>(null)
    private var permissionReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        askForNotifications()
        handleIntent(intent)
        setContent {
            AkiyamaTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Screen(
                        requested,
                        asking,
                        onConnectAccessory = { accessory -> withPermission(accessory) { requested.value = Target.Accessory(it) } },
                        onShare = { accessory, blockLan -> share(accessory, blockLan) },
                        onConnectAdb = { requested.value = Target.Adb },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        permissionReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    /** Android opens this screen when a computer switches the phone to accessory mode. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            val accessory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            }
            if (accessory == null) return
            if (roleOf(accessory) == Proto.ROLE_COMPUTER_WANTS) {
                asking.value = accessory
            } else {
                requested.value = Target.Accessory(accessory)
            }
        } else if (BuildConfig.DEBUG) {
            intent.getStringExtra(EXTRA_DEV_TCP)?.let { requested.value = Target.Tcp(it) }
        }
    }

    /** Android 17 blocks private addresses without this, and sharing a network includes its LAN. */
    private fun share(accessory: UsbAccessory, blockLan: Boolean) {
        if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_PERMISSION_SDK && !blockLan &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), 1)
        }
        withPermission(accessory) {
            asking.value = null
            ShareService.start(this, it, blockLan)
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
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) then(accessory)
            }
        }
        permissionReceiver?.let { runCatching { unregisterReceiver(it) } }
        permissionReceiver = receiver
        ContextCompat.registerReceiver(this, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Android adds its answer to this intent, so it has to be mutable.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val answer = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
        manager.requestPermission(accessory, answer)
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private companion object {
        /** Debug builds only: `--es dev_tcp host:port` connects over TCP, for testing on an emulator. */
        const val EXTRA_DEV_TCP = "dev_tcp"
        const val ACTION_USB_PERMISSION = "com.wonderhoylabs.akiyama.USB_PERMISSION"
        /** The version of Android that started enforcing the local network permission. */
        const val LOCAL_NETWORK_PERMISSION_SDK = 37
    }
}

@Composable
private fun Screen(
    requested: MutableStateFlow<Target?>,
    asking: MutableStateFlow<UsbAccessory?>,
    onConnectAccessory: (UsbAccessory) -> Unit,
    onShare: (UsbAccessory, Boolean) -> Unit,
    onConnectAdb: () -> Unit,
) {
    val context = LocalContext.current
    val status by AkiyamaState.status.collectAsStateWithLifecycle()
    val share by AkiyamaState.share.collectAsStateWithLifecycle()
    val error by AkiyamaState.error.collectAsStateWithLifecycle()
    val askingAccessory by asking.collectAsStateWithLifecycle()
    val target by requested.collectAsStateWithLifecycle()
    var waitingForConsent by remember { mutableStateOf<Target?>(null) }

    fun start(chosen: Target) {
        when (chosen) {
            is Target.Accessory -> TunnelService.connectAccessory(context, chosen.accessory)
            is Target.Adb -> TunnelService.connectAdb(context)
            is Target.Tcp -> TunnelService.connectTcp(context, chosen.address)
        }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) waitingForConsent?.let(::start)
        waitingForConsent = null
    }

    LaunchedEffect(target) {
        val chosen = target ?: return@LaunchedEffect
        requested.value = null
        val prompt = VpnService.prepare(context)
        if (prompt == null) {
            start(chosen)
        } else {
            waitingForConsent = chosen
            consent.launch(prompt)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header()
        error?.let { message -> Notice(message) }
        when (val current = share) {
            is ShareStatus.Waiting -> {
                ShareCard(stringResource(R.string.status_share_waiting, current.computer), busy = true, stats = null)
                return@Column
            }
            is ShareStatus.Sharing -> {
                ShareCard(stringResource(R.string.status_sharing, current.computer), busy = false, stats = current.stats)
                return@Column
            }
            is ShareStatus.Stopped -> {}
        }
        when (val current = status) {
            is TunnelStatus.Idle -> IdlePanel(askingAccessory, onConnectAccessory, onShare, onConnectAdb)
            is TunnelStatus.Connecting -> BusyCard(stringResource(R.string.status_connecting, current.computer), null)
            is TunnelStatus.Waiting -> BusyCard(stringResource(R.string.status_waiting, current.computer), null)
            is TunnelStatus.Reconnecting ->
                BusyCard(stringResource(R.string.status_reconnecting, current.computer), current.reason)
            is TunnelStatus.Connected -> ConnectedCard(current.computer, current.stats)
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = null,
            modifier = Modifier.size(72.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun IdlePanel(
    asking: UsbAccessory?,
    onConnectAccessory: (UsbAccessory) -> Unit,
    onShare: (UsbAccessory, Boolean) -> Unit,
    onConnectAdb: () -> Unit,
) {
    val context = LocalContext.current
    // The app can be opened with the cable already in, or disconnected and left on this screen.
    val attached = context.getSystemService(UsbManager::class.java).accessoryList?.firstOrNull()
    val accessory = asking ?: attached
    val theComputer = stringResource(R.string.the_computer)
    val computer = accessory?.description?.takeIf { it.isNotBlank() } ?: theComputer
    var blockLan by rememberSaveable { mutableStateOf(false) }

    if (accessory == null) {
        StatusCard(stringResource(R.string.status_idle), busy = false) {
            Text(stringResource(R.string.status_plug_in), style = MaterialTheme.typography.bodyMedium)
        }
    } else if (asking != null || roleOf(accessory) == Proto.ROLE_COMPUTER_WANTS) {
        StatusCard(stringResource(R.string.status_share_ask, computer), busy = false) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.block_lan), modifier = Modifier.weight(1f))
                Switch(checked = blockLan, onCheckedChange = { blockLan = it })
            }
            Button(onClick = { onShare(accessory, blockLan) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.share_with, computer))
            }
            TextButton(onClick = { onConnectAccessory(accessory) }) {
                Text(stringResource(R.string.connect_to, computer))
            }
        }
    } else {
        StatusCard(stringResource(R.string.status_idle), busy = false) {
            Button(onClick = { onConnectAccessory(accessory) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.connect_to, computer))
            }
            TextButton(onClick = { onShare(accessory, blockLan) }) {
                Text(stringResource(R.string.share_with, computer))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onConnectAdb) { Text(stringResource(R.string.use_adb)) }
        Text(
            stringResource(R.string.use_adb_hint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun ShareCard(title: String, busy: Boolean, stats: LinkStats?) {
    val context = LocalContext.current
    StatusCard(title, busy = busy) {
        stats?.let { Rates(it) }
        FilledTonalButton(onClick = { ShareService.stop(context) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.stop_sharing))
        }
    }
}

@Composable
private fun BusyCard(title: String, detail: String?) {
    val context = LocalContext.current
    StatusCard(title, busy = true) {
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        FilledTonalButton(onClick = { TunnelService.disconnect(context) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.disconnect))
        }
    }
}

@Composable
private fun ConnectedCard(computer: String, stats: LinkStats) {
    val context = LocalContext.current
    StatusCard(stringResource(R.string.status_connected, computer), busy = false) {
        Rates(stats)
        FilledTonalButton(onClick = { TunnelService.disconnect(context) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.disconnect))
        }
    }
}

@Composable
private fun Rates(stats: LinkStats) {
    val context = LocalContext.current
    var downBitsPerSecond by remember { mutableLongStateOf(0) }
    var upBitsPerSecond by remember { mutableLongStateOf(0) }
    var totalDown by remember { mutableLongStateOf(0) }
    var totalUp by remember { mutableLongStateOf(0) }
    var rttMicros by remember { mutableLongStateOf(0) }
    LaunchedEffect(stats) {
        var lastDown = stats.bytesReceived
        var lastUp = stats.bytesSent
        while (true) {
            delay(1000)
            val down = stats.bytesReceived
            val up = stats.bytesSent
            downBitsPerSecond = (down - lastDown) * 8
            upBitsPerSecond = (up - lastUp) * 8
            lastDown = down
            lastUp = up
            totalDown = down
            totalUp = up
            rttMicros = stats.rttMicros.toLong()
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Rate(stringResource(R.string.rate_down), downBitsPerSecond)
        Rate(stringResource(R.string.rate_up), upBitsPerSecond)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.total_down, Formatter.formatShortFileSize(context, totalDown)),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.total_up, Formatter.formatShortFileSize(context, totalUp)),
            style = MaterialTheme.typography.bodySmall,
        )
        if (rttMicros > 0) {
            Text(
                stringResource(R.string.ping, oneDecimal(rttMicros / 1000.0)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun oneDecimal(value: Double): String = String.format(LocalConfiguration.current.locales[0], "%.1f", value)

@Composable
private fun Rate(label: String, bitsPerSecond: Long) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            stringResource(R.string.rate_value, oneDecimal(bitsPerSecond / 1_000_000.0)),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatusCard(title: String, busy: Boolean, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (busy) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            content()
        }
    }
}

@Composable
private fun Notice(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
