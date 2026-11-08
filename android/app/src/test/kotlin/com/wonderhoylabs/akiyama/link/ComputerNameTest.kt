package com.wonderhoylabs.akiyama.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputerNameTest {
	@Test
	fun aPlainNameStaysAsItIs() {
		assertEquals("cb-h34h6f3", tidyComputerName("cb-h34h6f3"))
	}

	@Test
	fun networkSuffixesComeOff() {
		assertEquals("workstation", tidyComputerName("workstation.local"))
		assertEquals("desk", tidyComputerName("desk.LAN"))
		assertEquals("arch", tidyComputerName("arch.localdomain"))
	}

	@Test
	fun otherDotsStay() {
		assertEquals("build.example.com", tidyComputerName("build.example.com"))
	}

	@Test
	fun noNameMeansNull() {
		assertNull(tidyComputerName(null))
		assertNull(tidyComputerName(""))
		assertNull(tidyComputerName("   "))
		assertNull(tidyComputerName(".local"))
	}

	@Test
	fun controlCharactersGo() {
		assertEquals("desktop", tidyComputerName("desk\ntop\u0000"))
	}

	@Test
	fun longNamesGetCut() {
		val name = tidyComputerName("x".repeat(200))!!
		assertEquals(32, name.length)
		assertEquals('…', name.last())
	}
}
