package com.novaghost.family.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

object NovaColors {
    val Midnight = Color(0xFF05060B)
    val Deep = Color(0xFF0A0820)
    val Panel = Color(0xFF0B0E1A)
    val Cyan = Color(0xFF00F0FF)
    val Violet = Color(0xFF8B5CF6)
    val Mint = Color(0xFF3DFFA8)
    val Danger = Color(0xFFFF4D6D)
    val Warn = Color(0xFFFFC857)
    val Text = Color(0xFFE8F1FF)
    val Muted = Color(0xFF8C9BB5)
}

enum class Accent(val label: String, val primary: Color, val secondary: Color) {
    GHOST("Ghost Cyan", NovaColors.Cyan, NovaColors.Violet),
    PULSE("Violet Pulse", NovaColors.Violet, NovaColors.Cyan),
    TOXIC("Toxic Mint", NovaColors.Mint, NovaColors.Violet)
}

val LocalMotion = compositionLocalOf { true }
val LocalHighContrast = compositionLocalOf { false }

@Composable
fun NovaTheme(accent: Accent, reduceMotion: Boolean, largeText: Boolean, highContrast: Boolean, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent.primary, onPrimary = NovaColors.Midnight,
        secondary = accent.secondary, onSecondary = NovaColors.Midnight,
        background = NovaColors.Midnight, onBackground = NovaColors.Text,
        surface = NovaColors.Panel, onSurface = NovaColors.Text,
        surfaceVariant = Color(0xFF141A2E), onSurfaceVariant = if (highContrast) Color.White else NovaColors.Muted,
        error = NovaColors.Danger, outline = if (highContrast) Color.White else Color(0xFF2B3550)
    )
    val mono = FontFamily.Monospace
    val type = Typography(
        headlineMedium = TextStyle(fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 1.sp),
        titleLarge = TextStyle(fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 20.sp),
        titleMedium = TextStyle(fontFamily = mono, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        bodyLarge = TextStyle(fontSize = 16.sp), bodyMedium = TextStyle(fontSize = 14.sp),
        bodySmall = TextStyle(fontSize = 12.sp),
        labelLarge = TextStyle(fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.5.sp),
        labelSmall = TextStyle(fontFamily = mono, fontSize = 11.sp, letterSpacing = 1.sp)
    )
    val d = LocalDensity.current
    val dens = if (largeText) Density(d.density, 1.3f) else d
    CompositionLocalProvider(LocalDensity provides dens, LocalMotion provides !reduceMotion, LocalHighContrast provides highContrast) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
