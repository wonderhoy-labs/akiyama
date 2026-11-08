package com.wonderhoylabs.akiyama.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.wonderhoylabs.akiyama.ShareStatus
import com.wonderhoylabs.akiyama.TunnelStatus
import com.wonderhoylabs.akiyama.link.LinkStats
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Draws every state of the screen, checks what it says and what its buttons do, and saves a picture. */
class ScreenTest {
	@get:Rule
	val rule = createComposeRule()

	private val calls = mutableListOf<String>()
	private val actions = ScreenActions(
		onUseComputer = { calls += "use" },
		onUseAdb = { calls += "adb" },
		onShare = { blockLan -> calls += "share blockLan=$blockLan" },
		onNotNow = { calls += "not now" },
		onDisconnect = { calls += "disconnect" },
		onStopSharing = { calls += "stop sharing" },
		onDismissError = { calls += "dismiss" },
	)

	private fun show(state: ScreenState, dark: Boolean = false) {
		// The arrows and spinners never stop, so the test's clock only moves when it's told to.
		rule.mainClock.autoAdvance = false
		rule.setContent {
			AkiyamaTheme(darkTheme = dark) {
				Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
					AkiyamaScreen(state, actions)
				}
			}
		}
		rule.mainClock.advanceTimeBy(FRAME_MILLIS)
	}

	private fun tick(seconds: Int, stats: LinkStats, downBytes: Long, upBytes: Long) {
		repeat(seconds) {
			stats.bytesReceived += downBytes
			stats.bytesSent += upBytes
			rule.mainClock.advanceTimeBy(SECOND_MILLIS)
		}
	}

	private fun click(text: String) {
		rule.onNodeWithText(text).performScrollTo().performClick()
		rule.mainClock.advanceTimeBy(FRAME_MILLIS)
	}

	private fun picture(name: String) {
		val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val folder = File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }
		File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
	}

	private fun assertShown(vararg texts: String) {
		texts.forEach { rule.onNodeWithText(it, substring = true).assertExists() }
	}

	private fun assertNotShown(vararg texts: String) {
		texts.forEach { rule.onNodeWithText(it, substring = true).assertDoesNotExist() }
	}

	@Test
	fun unpluggedExplainsBothDirections() {
		show(ScreenState())
		assertShown("Plug into a computer", "akiyama server", "sudo akiyama client", "This phone", "Computer")
		picture("unplugged")
	}

	@Test
	fun unpluggedRevealsUsbDebugging() {
		show(ScreenState(), dark = true)
		click("Windows, or using USB debugging")
		assertShown("akiyama server --adb")
		picture("unplugged-adb-dark")
		click("Connect with USB debugging")
		assertEquals(listOf("adb"), calls)
	}

	@Test
	fun aComputerAskingHasToBeAnswered() {
		show(ScreenState(attached = AttachedComputer("workstation", ComputerRole.Wants)))
		assertShown("Share this phone's internet?", "Requested by workstation")
		rule.onNodeWithText("workstation").assertExists()
		picture("ask")
		click("Share")
		assertEquals(listOf("share blockLan=false"), calls)
	}

	@Test
	fun theLocalNetworkSwitchReachesTheAnswer() {
		show(ScreenState(attached = AttachedComputer("workstation", ComputerRole.Wants)), dark = true)
		rule.onNode(isToggleable()).performClick()
		rule.mainClock.advanceTimeBy(FRAME_MILLIS)
		picture("ask-dark")
		click("Share")
		assertEquals(listOf("share blockLan=true"), calls)
	}

	@Test
	fun anUnnamedComputerStillReadsProperly() {
		show(ScreenState(attached = AttachedComputer(null, ComputerRole.Wants)))
		assertShown("Requested by the computer", "Computer")
		picture("ask-unnamed")
	}

	@Test
	fun notNowCanBeAnswered() {
		show(ScreenState(attached = AttachedComputer("workstation", ComputerRole.Wants)))
		click("Not now")
		assertEquals(listOf("not now"), calls)
	}

	@Test
	fun afterNotNowSharingIsStillOnOffer() {
		show(ScreenState(attached = AttachedComputer("workstation", ComputerRole.Wants), askDismissed = true))
		assertShown("Not sharing", "Still plugged into workstation")
		picture("not-sharing")
		click("Share this phone's internet")
		assertEquals(listOf("share blockLan=false"), calls)
	}

	@Test
	fun afterDisconnectingItCanReconnect() {
		show(ScreenState(attached = AttachedComputer("desktop", ComputerRole.Shares)))
		assertShown("Disconnected", "Still plugged into desktop")
		assertNotShown("Share this phone's internet")
		picture("disconnected")
		click("Connect")
		assertEquals(listOf("use"), calls)
	}

	@Test
	fun anUnknownComputerOffersBothWays() {
		show(ScreenState(attached = AttachedComputer("desktop", ComputerRole.Unknown)))
		assertShown("Plugged into a computer", "Which way should the internet go?")
		picture("choose")
		click("Use the computer's internet")
		click("Share this phone's internet")
		assertEquals(listOf("use", "share blockLan=false"), calls)
	}

	@Test
	fun connectingCanBeCancelled() {
		show(ScreenState(status = TunnelStatus.Connecting(null)))
		assertShown("Connecting")
		picture("connecting")
		click("Cancel")
		assertEquals(listOf("disconnect"), calls)
	}

	@Test
	fun waitingSaysWhatToRun() {
		show(ScreenState(status = TunnelStatus.Waiting("workstation", overAdb = false)))
		assertShown("Waiting for the computer", "akiyama server")
		assertNotShown("--adb")
		picture("waiting")
	}

	@Test
	fun waitingOverAdbSaysSo() {
		show(ScreenState(status = TunnelStatus.Waiting(null, overAdb = true)))
		assertShown("akiyama server --adb")
	}

	@Test
	fun connectedShowsRatesForThePhone() {
		val stats = LinkStats().apply { rttMicros = 2_400 }
		show(ScreenState(status = TunnelStatus.Connected("workstation", stats)))
		tick(30, stats, downBytes = 25_000_000, upBytes = 6_000_000)
		assertShown("Connected", "using the internet from workstation", "200.0 Mbit/s", "48.0 Mbit/s", "2.4 ms ping")
		picture("connected")
		click("Disconnect")
		assertEquals(listOf("disconnect"), calls)
	}

	@Test
	fun connectedLooksRightInTheDark() {
		val stats = LinkStats().apply { rttMicros = 2_400 }
		show(ScreenState(status = TunnelStatus.Connected(null, stats)), dark = true)
		tick(30, stats, downBytes = 25_000_000, upBytes = 6_000_000)
		assertShown("using the internet from the computer")
		picture("connected-dark")
	}

	@Test
	fun reconnectingExplainsItself() {
		show(ScreenState(status = TunnelStatus.Reconnecting("workstation", "the computer went quiet")))
		assertShown("Reconnecting", "The link to workstation dropped", "the computer went quiet")
		picture("reconnecting")
		click("Disconnect")
		assertEquals(listOf("disconnect"), calls)
	}

	@Test
	fun shareReadySaysWhatToRun() {
		show(ScreenState(share = ShareStatus.Waiting("workstation")))
		assertShown("Ready to share", "Waiting for workstation", "sudo akiyama client")
		picture("share-ready")
		click("Stop sharing")
		assertEquals(listOf("stop sharing"), calls)
	}

	/** What the phone sends is what the computer downloads, so the numbers swap over here. */
	@Test
	fun sharingShowsRatesForTheComputer() {
		val stats = LinkStats().apply { rttMicros = 900 }
		show(ScreenState(share = ShareStatus.Sharing("workstation", stats)))
		tick(30, stats, downBytes = 1_000_000, upBytes = 20_000_000)
		assertShown("Sharing", "going to workstation", "160.0 Mbit/s", "8.0 Mbit/s")
		picture("sharing")
		click("Stop sharing")
		assertEquals(listOf("stop sharing"), calls)
	}

	@Test
	fun errorsCanBeDismissed() {
		show(ScreenState(error = "Couldn't open the USB connection."))
		rule.onNodeWithText("Couldn't open the USB connection.").assertIsDisplayed()
		picture("error")
		click("Dismiss")
		assertEquals(listOf("dismiss"), calls)
	}

	@Test
	fun aLongNameFits() {
		show(ScreenState(attached = AttachedComputer("a-very-long-computer-name-indeed…", ComputerRole.Wants)))
		rule.onNodeWithText("Share").performScrollTo().assertIsDisplayed()
		picture("ask-long-name")
	}

	private companion object {
		const val FRAME_MILLIS = 100L
		const val SECOND_MILLIS = 1_000L
	}
}
