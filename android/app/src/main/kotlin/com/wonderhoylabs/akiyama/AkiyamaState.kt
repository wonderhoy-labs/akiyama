package com.wonderhoylabs.akiyama

import com.wonderhoylabs.akiyama.link.LinkStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the tunnel service is up to. */
sealed interface TunnelStatus {
	data object Idle : TunnelStatus
	data class Connecting(val computer: String?) : TunnelStatus

	/** The link is open, but nothing on the computer has answered yet. */
	data class Waiting(val computer: String?, val overAdb: Boolean) : TunnelStatus
	data class Connected(val computer: String?, val stats: LinkStats) : TunnelStatus
	data class Reconnecting(val computer: String?, val reason: String?) : TunnelStatus
}

/** What the sharing service is up to. */
sealed interface ShareStatus {
	data object Stopped : ShareStatus

	/** The cable is in, and nothing on the computer has said hello yet. */
	data class Waiting(val computer: String?) : ShareStatus
	data class Sharing(val computer: String?, val stats: LinkStats) : ShareStatus
}

/** Where the services tell the screen what's happening. They all live in one process. */
object AkiyamaState {
	private val statusFlow = MutableStateFlow<TunnelStatus>(TunnelStatus.Idle)
	private val shareFlow = MutableStateFlow<ShareStatus>(ShareStatus.Stopped)
	private val errorFlow = MutableStateFlow<String?>(null)

	val status: StateFlow<TunnelStatus> = statusFlow
	val share: StateFlow<ShareStatus> = shareFlow
	val error: StateFlow<String?> = errorFlow

	fun setStatus(status: TunnelStatus) {
		statusFlow.value = status
	}

	fun setShare(status: ShareStatus) {
		shareFlow.value = status
	}

	fun setError(message: String?) {
		errorFlow.value = message
	}
}
