package com.cin.bilateralsleep.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Dimmed-phosphor palette: near-black backgrounds, low-contrast green/amber.
object Phosphor {
    val Background = Color(0xFF070A07)
    val Surface = Color(0xFF0C120C)
    val SurfaceHigh = Color(0xFF121A12)
    val Green = Color(0xFF6FB37A)       // primary phosphor
    val GreenBright = Color(0xFF8FCF99) // text
    val GreenDim = Color(0xFF3E5A45)    // inactive / hints
    val Amber = Color(0xFFC9A35B)       // secondary accent
    val AmberDim = Color(0xFF6B5A36)
}

private val PhosphorScheme = darkColorScheme(
    primary = Phosphor.Green,
    onPrimary = Phosphor.Background,
    secondary = Phosphor.Amber,
    onSecondary = Phosphor.Background,
    background = Phosphor.Background,
    onBackground = Phosphor.GreenBright,
    surface = Phosphor.Surface,
    onSurface = Phosphor.GreenBright,
    surfaceVariant = Phosphor.SurfaceHigh,
    onSurfaceVariant = Phosphor.GreenDim,
    outline = Phosphor.GreenDim,
)

// Monospace for the calm retro-terminal feel; generous sizing for readability.
private val mono = FontFamily.Monospace
private val PhosphorType = Typography(
    titleLarge = TextStyle(fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 22.sp, letterSpacing = 3.sp),
    titleMedium = TextStyle(fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 2.sp),
    bodyLarge = TextStyle(fontFamily = mono, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = mono, fontSize = 13.sp),
    labelLarge = TextStyle(fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 2.sp),
    labelSmall = TextStyle(fontFamily = mono, fontSize = 12.sp, letterSpacing = 1.sp),
)

@Composable
fun BilateralSleepTheme(content: @Composable () -> Unit) {
    // Always dark; @Suppress the unused dark-theme check — this app is dark-only.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(colorScheme = PhosphorScheme, typography = PhosphorType, content = content)
}
