package com.wonderhoylabs.akiyama.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Taken from the logo: its rose pink hair and pale blue sky.
private val Rose = Color(0xFFC2416B)
private val RoseLight = Color(0xFFFFB0C6)
private val Sky = Color(0xFF2F7FA8)
private val SkyLight = Color(0xFFA9DCF7)

private val LightColors = lightColorScheme(
    primary = Rose,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE1E9),
    onPrimaryContainer = Color(0xFF4F0A22),
    secondary = Sky,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9EFFB),
    onSecondaryContainer = Color(0xFF0B3347),
    background = Color(0xFFFFFAFB),
    onBackground = Color(0xFF22191C),
    surface = Color(0xFFFFFAFB),
    onSurface = Color(0xFF22191C),
    surfaceVariant = Color(0xFFF5E8EC),
    onSurfaceVariant = Color(0xFF524347),
    outline = Color(0xFF85737A),
)

private val DarkColors = darkColorScheme(
    primary = RoseLight,
    onPrimary = Color(0xFF5F1130),
    primaryContainer = Color(0xFF7A2A47),
    onPrimaryContainer = Color(0xFFFFE1E9),
    secondary = SkyLight,
    onSecondary = Color(0xFF0B3347),
    secondaryContainer = Color(0xFF1B4A62),
    onSecondaryContainer = Color(0xFFD9EFFB),
    background = Color(0xFF191315),
    onBackground = Color(0xFFEFDFE3),
    surface = Color(0xFF191315),
    onSurface = Color(0xFFEFDFE3),
    surfaceVariant = Color(0xFF3B2F33),
    onSurfaceVariant = Color(0xFFD7C2C8),
    outline = Color(0xFF9F8C92),
)

@Composable
fun AkiyamaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
