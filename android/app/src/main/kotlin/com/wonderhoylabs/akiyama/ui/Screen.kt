package com.wonderhoylabs.akiyama.ui

import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.akiyama.R
import com.wonderhoylabs.akiyama.ShareStatus
import com.wonderhoylabs.akiyama.TunnelStatus
import com.wonderhoylabs.akiyama.link.LinkStats
import kotlinx.coroutines.delay

/** What the computer on the other end of the cable asked for when it switched the phone's mode. */
enum class ComputerRole { Shares, Wants, Unknown }

/** A computer on the end of the cable. Its name is null when it didn't give one. */
data class AttachedComputer(val name: String?, val role: ComputerRole)

/** Everything the screen shows. It's all here so that any state can be drawn, on any device. */
data class ScreenState(
	val status: TunnelStatus = TunnelStatus.Idle,
	val share: ShareStatus = ShareStatus.Stopped,
	val error: String? = null,
	val attached: AttachedComputer? = null,
	val askDismissed: Boolean = false,
)

class ScreenActions(
	val onUseComputer: () -> Unit = {},
	val onUseAdb: () -> Unit = {},
	val onShare: (blockLan: Boolean) -> Unit = {},
	val onNotNow: () -> Unit = {},
	val onDisconnect: () -> Unit = {},
	val onStopSharing: () -> Unit = {},
	val onDismissError: () -> Unit = {},
)

@Composable
fun AkiyamaScreen(state: ScreenState, actions: ScreenActions) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.safeDrawingPadding()
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 20.dp, vertical = 16.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		BrandHeader(subtitle = stringResource(R.string.tagline), logo = painterResource(R.drawable.logo))
		state.error?.let { ErrorNotice(it, actions.onDismissError) }
		val share = state.share
		val status = state.status
		when {
			share is ShareStatus.Waiting -> ShareReady(share.computer, actions)
			share is ShareStatus.Sharing -> Sharing(share.computer, share.stats, actions)
			status is TunnelStatus.Connecting -> Connecting(status.computer, actions)
			status is TunnelStatus.Waiting -> WaitingForComputer(status.computer, status.overAdb, actions)
			status is TunnelStatus.Connected -> Connected(status.computer, status.stats, actions)
			status is TunnelStatus.Reconnecting -> Reconnecting(status.computer, status.reason, actions)
			state.attached != null -> Attached(state.attached, state.askDismissed, actions)
			else -> Unplugged(actions)
		}
	}
}

@Composable
private fun nameOrTheComputer(computer: String?): String = computer ?: stringResource(R.string.the_computer)

// Nothing plugged in

@Composable
private fun Unplugged(actions: ScreenActions) {
	var showAdb by rememberSaveable { mutableStateOf(false) }
	StatusCard(
		computer = null,
		flow = LinkFlow.None,
		title = stringResource(R.string.unplugged_title),
		body = stringResource(R.string.unplugged_body),
		quiet = true,
	) {
		HowTo(
			stringResource(R.string.option_use),
			stringResource(R.string.command_server),
			stringResource(R.string.option_use_hint),
		)
		HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
		HowTo(
			stringResource(R.string.option_share),
			stringResource(R.string.command_client),
			stringResource(R.string.option_share_hint),
		)
	}
	TextButton(onClick = { showAdb = !showAdb }) { Text(stringResource(R.string.adb_toggle)) }
	if (showAdb) {
		Card(
			modifier = Modifier.fillMaxWidth(),
			colors = quietCardColors(),
			shape = RoundedCornerShape(24.dp),
		) {
			Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
				Text(stringResource(R.string.adb_body), style = MaterialTheme.typography.bodyMedium)
				Command(stringResource(R.string.command_server_adb))
				OutlinedButton(onClick = actions.onUseAdb, modifier = Modifier.fillMaxWidth()) {
					Text(stringResource(R.string.adb_connect))
				}
			}
		}
	}
}

@Composable
private fun HowTo(title: String, command: String, hint: String) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
		Command(command)
		Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

@Composable
private fun Command(command: String) {
	Surface(
		shape = RoundedCornerShape(8.dp),
		color = MaterialTheme.colorScheme.surface,
		modifier = Modifier.fillMaxWidth(),
	) {
		Text(
			command,
			fontFamily = FontFamily.Monospace,
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
		)
	}
}

// Plugged in, and nothing running

@Composable
private fun Attached(attached: AttachedComputer, askDismissed: Boolean, actions: ScreenActions) {
	when {
		attached.role == ComputerRole.Wants && !askDismissed -> Ask(attached.name, actions)
		attached.role == ComputerRole.Wants -> NotSharing(attached.name, actions)
		attached.role == ComputerRole.Shares -> Disconnected(attached.name, actions)
		else -> ChooseDirection(attached.name, actions)
	}
}

@Composable
private fun Ask(computer: String?, actions: ScreenActions) {
	var blockLan by rememberSaveable { mutableStateOf(false) }
	StatusCard(
		computer = computer,
		flow = LinkFlow.ToComputer,
		title = stringResource(R.string.ask_title),
		body = stringResource(R.string.ask_body, nameOrTheComputer(computer)),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) {
				Text(stringResource(R.string.block_lan), style = MaterialTheme.typography.bodyMedium)
				Text(
					stringResource(R.string.block_lan_hint),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Spacer(Modifier.width(12.dp))
			Switch(checked = blockLan, onCheckedChange = { blockLan = it })
		}
		Button(onClick = { actions.onShare(blockLan) }, modifier = Modifier.fillMaxWidth()) {
			Text(stringResource(R.string.share))
		}
		TextButton(onClick = actions.onNotNow, modifier = Modifier.fillMaxWidth()) {
			Text(stringResource(R.string.not_now))
		}
	}
}

@Composable
private fun NotSharing(computer: String?, actions: ScreenActions) {
	StatusCard(
		computer = computer,
		flow = LinkFlow.None,
		title = stringResource(R.string.not_sharing_title),
		body = stringResource(R.string.not_sharing_body, nameOrTheComputer(computer)),
		quiet = true,
	) {
		Button(onClick = { actions.onShare(false) }, modifier = Modifier.fillMaxWidth()) {
			Text(stringResource(R.string.option_share))
		}
	}
}

@Composable
private fun Disconnected(computer: String?, actions: ScreenActions) {
	StatusCard(
		computer = computer,
		flow = LinkFlow.None,
		title = stringResource(R.string.disconnected_title),
		body = stringResource(R.string.disconnected_body, nameOrTheComputer(computer)),
		quiet = true,
	) {
		Button(onClick = actions.onUseComputer, modifier = Modifier.fillMaxWidth()) {
			Text(stringResource(R.string.connect))
		}
	}
}

@Composable
private fun ChooseDirection(computer: String?, actions: ScreenActions) {
	StatusCard(
		computer = computer,
		flow = LinkFlow.None,
		title = stringResource(R.string.attached_title),
		body = stringResource(R.string.attached_body, nameOrTheComputer(computer)),
	) {
		Button(onClick = actions.onUseComputer, modifier = Modifier.fillMaxWidth()) {
			Text(stringResource(R.string.option_use))
		}
		OutlinedButton(onClick = { actions.onShare(false) }, modifier = Modifier.fillMaxWidth()) {
			Text(stringResource(R.string.option_share))
		}
	}
}

// Using the computer's internet

@Composable
private fun Connecting(computer: String?, actions: ScreenActions) {
	StatusCard(
		computer = computer,
		flow = LinkFlow.ToPhone,
		title = stringResource(R.string.connecting_title),
		busy = true,
	) {
		EndButton(stringResource(R.string.cancel), actions.onDisconnect)
	}
}

@Composable
private fun WaitingForComputer(computer: String?, overAdb: Boolean, actions: ScreenActions) {
	StatusCard(
		computer = computer,
		flow = LinkFlow.ToPhone,
		title = stringResource(R.string.waiting_title),
		body = stringResource(R.string.waiting_body),
		busy = true,
	) {
		Command(stringResource(if (overAdb) R.string.command_server_adb else R.string.command_server))
		EndButton(stringResource(R.string.cancel), actions.onDisconnect)
	}
}

@Composable
private fun Connected(computer: String?, stats: LinkStats, actions: ScreenActions) {
	val rates = rememberLiveRates(stats, computerIsClient = false)
	StatusCard(
		computer = computer,
		flow = LinkFlow.ToPhone,
		moving = rates.flowing,
		title = stringResource(R.string.connected_title),
		body = stringResource(R.string.connected_body, nameOrTheComputer(computer)),
	) {
		Rates(rates)
		EndButton(stringResource(R.string.disconnect), actions.onDisconnect)
	}
}

@Composable
private fun Reconnecting(computer: String?, reason: String?, actions: ScreenActions) {
	StatusCard(
		computer = computer,
		flow = LinkFlow.ToPhone,
		title = stringResource(R.string.reconnecting_title),
		body = stringResource(R.string.reconnecting_body, nameOrTheComputer(computer)),
		busy = true,
	) {
		reason?.let {
			Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		EndButton(stringResource(R.string.disconnect), actions.onDisconnect)
	}
}

// Sharing this phone's internet

@Composable
private fun ShareReady(computer: String?, actions: ScreenActions) {
	StatusCard(
		computer = computer,
		flow = LinkFlow.ToComputer,
		title = stringResource(R.string.share_ready_title),
		body = stringResource(R.string.share_ready_body, nameOrTheComputer(computer)),
		busy = true,
	) {
		Command(stringResource(R.string.command_client))
		EndButton(stringResource(R.string.stop_sharing), actions.onStopSharing)
	}
}

@Composable
private fun Sharing(computer: String?, stats: LinkStats, actions: ScreenActions) {
	val rates = rememberLiveRates(stats, computerIsClient = true)
	StatusCard(
		computer = computer,
		flow = LinkFlow.ToComputer,
		moving = rates.flowing,
		title = stringResource(R.string.sharing_title),
		body = stringResource(R.string.sharing_body, nameOrTheComputer(computer)),
	) {
		Rates(rates)
		EndButton(stringResource(R.string.stop_sharing), actions.onStopSharing)
	}
}

// Shared pieces

@Composable
private fun EndButton(label: String, onClick: () -> Unit) {
	FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun StatusCard(
	computer: String?,
	flow: LinkFlow,
	title: String,
	modifier: Modifier = Modifier,
	body: String? = null,
	moving: Boolean = false,
	busy: Boolean = false,
	quiet: Boolean = false,
	content: @Composable ColumnScope.() -> Unit,
) {
	Card(
		modifier = modifier.fillMaxWidth(),
		colors = if (quiet) {
			quietCardColors()
		} else {
			CardDefaults.cardColors(
				containerColor = MaterialTheme.colorScheme.primaryContainer,
			)
		},
		shape = RoundedCornerShape(24.dp),
	) {
		Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
			LinkDiagram(flow, moving, computer)
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						title,
						style = MaterialTheme.typography.headlineSmall,
						fontWeight = FontWeight.SemiBold,
						modifier = Modifier.weight(1f),
					)
					if (busy) {
						Spacer(Modifier.width(12.dp))
						CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
					}
				}
				body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
			}
			content()
		}
	}
}

/** For a card that isn't reporting anything happening. Its text still has to read as active. */
@Composable
private fun quietCardColors() = CardDefaults.cardColors(
	containerColor = MaterialTheme.colorScheme.surfaceVariant,
	contentColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun ErrorNotice(message: String, onDismiss: () -> Unit) {
	Card(
		modifier = Modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
		shape = RoundedCornerShape(16.dp),
	) {
		Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
			Text(
				message,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onErrorContainer,
				modifier = Modifier.padding(end = 8.dp),
			)
			TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
				Text(stringResource(R.string.dismiss), color = MaterialTheme.colorScheme.onErrorContainer)
			}
		}
	}
}

private const val GRAPH_SECONDS = 60
private const val GRAPH_MINIMUM_PEAK = 1_000_000f

private const val FLOWING_BITS_PER_SECOND = 8_000L

private class RateSample(val down: Long, val up: Long)

private class LiveRates {
	var samples by mutableStateOf(emptyList<RateSample>())
	var down by mutableLongStateOf(0)
	var up by mutableLongStateOf(0)
	var totalDown by mutableLongStateOf(0)
	var totalUp by mutableLongStateOf(0)
	var rtt by mutableIntStateOf(0)

	/** Whether the arrow should move. A still arrow also means nothing's being redrawn. */
	val flowing get() = down + up >= FLOWING_BITS_PER_SECOND
}

/**
 * The live rates, from the point of view of whichever end is getting the internet. When the computer
 * is the client, what the phone sends is what the computer downloads.
 */
@Composable
private fun rememberLiveRates(stats: LinkStats, computerIsClient: Boolean): LiveRates {
	val rates = remember(stats) { LiveRates() }
	fun received() = if (computerIsClient) stats.bytesSent else stats.bytesReceived
	fun sent() = if (computerIsClient) stats.bytesReceived else stats.bytesSent

	LaunchedEffect(stats) {
		var lastDown = received()
		var lastUp = sent()
		rates.totalDown = lastDown
		rates.totalUp = lastUp
		while (true) {
			delay(1_000)
			val nowDown = received()
			val nowUp = sent()
			rates.down = (nowDown - lastDown) * 8
			rates.up = (nowUp - lastUp) * 8
			lastDown = nowDown
			lastUp = nowUp
			rates.totalDown = nowDown
			rates.totalUp = nowUp
			rates.rtt = stats.rttMicros
			rates.samples = (rates.samples + RateSample(rates.down, rates.up)).takeLast(GRAPH_SECONDS)
		}
	}
	return rates
}

@Composable
private fun Rates(rates: LiveRates) {
	val context = LocalContext.current
	val locale = LocalConfiguration.current.locales[0]
	Row {
		Rate(
			stringResource(R.string.rate_down),
			formatRate(rates.down, locale),
			MaterialTheme.colorScheme.primary,
			Modifier.weight(1f),
		)
		Rate(
			stringResource(R.string.rate_up),
			formatRate(rates.up, locale),
			MaterialTheme.colorScheme.secondary,
			Modifier.weight(1f),
		)
	}
	RateGraph(rates.samples)
	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(
			stringResource(
				R.string.totals,
				Formatter.formatShortFileSize(context, rates.totalDown),
				Formatter.formatShortFileSize(context, rates.totalUp),
			),
			style = MaterialTheme.typography.bodySmall,
			modifier = Modifier.weight(1f),
		)
		if (rates.rtt > 0) {
			Text(
				stringResource(R.string.ping, formatMillis(rates.rtt, locale)),
				style = MaterialTheme.typography.bodySmall,
			)
		}
	}
}

@Composable
private fun Rate(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
	Column(modifier) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Box(Modifier.size(8.dp).clip(CircleShape).background(color))
			Spacer(Modifier.width(6.dp))
			Text(label, style = MaterialTheme.typography.labelMedium)
		}
		Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
	}
}

@Composable
private fun RateGraph(samples: List<RateSample>) {
	val downColor = MaterialTheme.colorScheme.primary
	val upColor = MaterialTheme.colorScheme.secondary
	val baseColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
	Canvas(Modifier.fillMaxWidth().height(56.dp)) {
		val base = size.height - 1.dp.toPx()
		drawLine(baseColor, Offset(0f, base), Offset(size.width, base), strokeWidth = 1.dp.toPx())
		if (samples.size < 2) return@Canvas
		val peak = maxOf(samples.maxOf { maxOf(it.down, it.up) }.toFloat(), GRAPH_MINIMUM_PEAK)
		drawRateLine(samples.map { it.down }, peak, downColor, filled = true)
		drawRateLine(samples.map { it.up }, peak, upColor, filled = false)
	}
}

/** Draws one direction's history, with the newest second on the right. */
private fun DrawScope.drawRateLine(values: List<Long>, peak: Float, color: Color, filled: Boolean) {
	val width = 2.dp.toPx()
	val height = size.height - width
	val offset = GRAPH_SECONDS - values.size
	fun x(index: Int) = size.width * (offset + index) / (GRAPH_SECONDS - 1)
	val line = Path()
	values.forEachIndexed { index, value ->
		val y = height - (height * value / peak)
		if (index == 0) line.moveTo(x(index), y) else line.lineTo(x(index), y)
	}
	if (filled) {
		val area = Path().apply {
			addPath(line)
			lineTo(x(values.lastIndex), size.height)
			lineTo(x(0), size.height)
			close()
		}
		drawPath(area, color.copy(alpha = 0.18f))
	}
	drawPath(line, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
