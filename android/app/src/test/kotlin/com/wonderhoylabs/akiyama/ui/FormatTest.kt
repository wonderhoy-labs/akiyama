package com.wonderhoylabs.akiyama.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatTest {
	@Test
	fun ratesPickAUnit() {
		assertEquals("0 kbit/s", formatRate(0, Locale.ROOT))
		assertEquals("300 kbit/s", formatRate(300_000, Locale.ROOT))
		assertEquals("1.0 Mbit/s", formatRate(1_000_000, Locale.ROOT))
		assertEquals("212.3 Mbit/s", formatRate(212_300_000, Locale.ROOT))
		assertEquals("1.25 Gbit/s", formatRate(1_250_000_000, Locale.ROOT))
	}

	@Test
	fun pingIsInMilliseconds() {
		assertEquals("9.4", formatMillis(9_400, Locale.ROOT))
	}
}
