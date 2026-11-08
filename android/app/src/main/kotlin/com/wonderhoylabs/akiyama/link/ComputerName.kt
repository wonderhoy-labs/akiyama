package com.wonderhoylabs.akiyama.link

private const val MAX_LENGTH = 32
private val NETWORK_SUFFIXES = listOf(".localdomain", ".local", ".lan", ".home")

/**
 * Turns the name a computer reports into something to show, or null if it didn't report one. The
 * name is whatever the computer says, so it doesn't get to take over the screen either.
 */
fun tidyComputerName(reported: String?): String? {
	val cleaned = reported.orEmpty().filterNot { it.isISOControl() }.trim()
	val suffix = NETWORK_SUFFIXES.firstOrNull { cleaned.endsWith(it, ignoreCase = true) }
	val name = (if (suffix != null) cleaned.dropLast(suffix.length) else cleaned).trim()
	return when {
		name.isEmpty() -> null
		name.length > MAX_LENGTH -> name.take(MAX_LENGTH - 1).trimEnd() + "…"
		else -> name
	}
}
