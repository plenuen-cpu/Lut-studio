package com.plenuen.filmcam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object FilmPalette {
    val Accent = Color(0xFFF5C518)
    val AccentSoft = Color(0xFFB08F10)
    val Background = Color(0xFF07070A)
    val Panel = Color(0xFF0C0C10)
    val Strip = Color(0xFF050507)
    val Slot = Color(0xFF15151B)
    val SlotIdle = Color(0xFF22222A)
    val Line = Color(0xFF2C2C34)
    val Text = Color(0xFFEDEDED)
    val TextDim = Color(0xFF8E8E96)
    val Hole = Color(0xFF232329)
}

private val FilmCamScheme = darkColorScheme(
    primary = FilmPalette.Accent,
    onPrimary = Color(0xFF141414),
    secondary = FilmPalette.Accent,
    onSecondary = Color(0xFF141414),
    background = FilmPalette.Background,
    onBackground = FilmPalette.Text,
    surface = FilmPalette.Panel,
    onSurface = FilmPalette.Text,
    surfaceVariant = FilmPalette.Slot,
    onSurfaceVariant = FilmPalette.TextDim,
)

@Composable
fun FilmCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FilmCamScheme,
        typography = Typography(),
        content = content,
    )
}
