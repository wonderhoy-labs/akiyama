package com.wonderhoylabs.akiyama.ui

import java.util.Locale

private const val KILO = 1_000L
private const val MEGA = 1_000_000L
private const val GIGA = 1_000_000_000L

/** A rate in the unit that suits it, so a quiet link doesn't read "0.0 Mbit/s". */
fun formatRate(bitsPerSecond: Long, locale: Locale): String = when {
	bitsPerSecond < MEGA -> String.format(locale, "%d kbit/s", bitsPerSecond / KILO)
	bitsPerSecond < GIGA -> String.format(locale, "%.1f Mbit/s", bitsPerSecond / MEGA.toDouble())
	else -> String.format(locale, "%.2f Gbit/s", bitsPerSecond / GIGA.toDouble())
}

fun formatMillis(micros: Int, locale: Locale): String = String.format(locale, "%.1f", micros / KILO.toDouble())
