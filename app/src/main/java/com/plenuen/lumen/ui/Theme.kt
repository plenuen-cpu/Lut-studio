package com.plenuen.lumen.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object LumenPalette {
    val Accent = Color(0xFFE8B95C)
    val AccentDim = Color(0xFF9A7734)
    val Background = Color(0xFF0A0A0C)
    val Panel = Color(0xFF121216)
    val PanelRaised = Color(0xFF1B1B21)
    val Line = Color(0xFF2A2A32)
    val Text = Color(0xFFF1F1EE)
    val TextDim = Color(0xFF9A9AA4)
    val Danger = Color(0xFFE07070)
}

private val scheme = darkColorScheme(
    primary = LumenPalette.Accent,
    onPrimary = Color(0xFF141414),
    secondary = LumenPalette.AccentDim,
    onSecondary = Color(0xFF141414),
    background = LumenPalette.Background,
    onBackground = LumenPalette.Text,
    surface = LumenPalette.Panel,
    onSurface = LumenPalette.Text,
    surfaceVariant = LumenPalette.PanelRaised,
    onSurfaceVariant = LumenPalette.TextDim,
    outline = LumenPalette.Line,
    error = LumenPalette.Danger,
)

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
