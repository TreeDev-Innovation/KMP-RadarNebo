package com.gusak.lab.radarnebo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.gusak.lab.radarnebo.data.ThemeMode

/** Map and overlay colours that Material's colour scheme doesn't cover. Read via [LocalRadarColors]. */
@Immutable
data class RadarColors(
    val isDark: Boolean,
    val mapBackground: Color,
    val grid: Color,
    val oblastIdle: Color,
    val oblastBorder: Color,
    val oblastAlertFull: Color,
    val oblastAlertPartial: Color,
    val danger: Color,
    val warning: Color,
    val favorite: Color,
    /** "All clear" / healthy state. */
    val ok: Color,
    /** Translucent chip behind text drawn over the map, and the colours on it. */
    val overlay: Color,
    val onOverlay: Color,
    val onOverlayMuted: Color,
    /** Labels and selection rings drawn straight onto the map. */
    val mapLabel: Color,
    val glyphOutline: Color,
    /** Liquid glass: tint over the blurred backdrop, rim highlight and the selected-item lens. */
    val glassTint: Color,
    val glassRimLight: Color,
    val glassRimShade: Color,
    val glassSelection: Color,
) {
    companion object {
        val Dark = RadarColors(
            isDark = true,
            mapBackground = Color(0xFF070D14),
            grid = Color(0xFF13202E),
            oblastIdle = Color(0xFF152536),
            oblastBorder = Color(0xFF3B5A78),
            oblastAlertFull = Color(0x66E5484D),
            oblastAlertPartial = Color(0x40F5A524),
            danger = Color(0xFFFF4D4F),
            warning = Color(0xFFFFB020),
            favorite = Color(0xFF3DD9EB),
            ok = Color(0xFF3DEB8A),
            overlay = Color(0x8C000000),
            onOverlay = Color.White,
            onOverlayMuted = Color(0x99FFFFFF),
            mapLabel = Color.White,
            glyphOutline = Color(0x99000000),
            glassTint = Color(0x1FFFFFFF),
            glassRimLight = Color(0x80FFFFFF),
            glassRimShade = Color(0x14FFFFFF),
            glassSelection = Color(0x26FFFFFF),
        )

        val Light = RadarColors(
            isDark = false,
            mapBackground = Color(0xFFE9EEF3),
            grid = Color(0xFFD7DFE8),
            oblastIdle = Color(0xFFFFFFFF),
            oblastBorder = Color(0xFF9DB2C7),
            oblastAlertFull = Color(0x59E5484D),
            oblastAlertPartial = Color(0x52F5A524),
            danger = Color(0xFFE03B40),
            warning = Color(0xFFD98200),
            favorite = Color(0xFF0B93A6),
            ok = Color(0xFF1E9E58),
            overlay = Color(0xD9FFFFFF),
            onOverlay = Color(0xFF0E1822),
            onOverlayMuted = Color(0x990E1822),
            mapLabel = Color(0xFF0E1822),
            glyphOutline = Color(0xB3FFFFFF),
            glassTint = Color(0x73FFFFFF),
            glassRimLight = Color(0xF2FFFFFF),
            glassRimShade = Color(0x1A0E1822),
            glassSelection = Color(0x14000000),
        )
    }
}

val LocalRadarColors = staticCompositionLocalOf { RadarColors.Dark }

private val darkScheme = darkColorScheme(
    primary = RadarColors.Dark.favorite,
    onPrimary = Color(0xFF00252B),
    primaryContainer = Color(0xFF0F4C57),
    onPrimaryContainer = Color(0xFFB9F2FA),
    secondary = RadarColors.Dark.ok,
    secondaryContainer = Color(0xFF16414A),
    onSecondaryContainer = Color(0xFFB9F2FA),
    error = RadarColors.Dark.danger,
    background = RadarColors.Dark.mapBackground,
    surface = Color(0xFF0E1822),
    surfaceContainer = Color(0xFF111D29),
    surfaceContainerHigh = Color(0xFF172634),
    surfaceContainerHighest = Color(0xFF1E2F40),
    surfaceVariant = Color(0xFF1A2A3A),
)

private val lightScheme = lightColorScheme(
    primary = RadarColors.Light.favorite,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFF4),
    onPrimaryContainer = Color(0xFF00363D),
    secondary = RadarColors.Light.ok,
    secondaryContainer = Color(0xFFCDEFF4),
    onSecondaryContainer = Color(0xFF00363D),
    error = RadarColors.Light.danger,
    background = Color(0xFFF4F7FA),
    surface = Color(0xFFF9FBFD),
    surfaceContainer = Color(0xFFEDF2F6),
    surfaceContainerHigh = Color(0xFFE4EBF1),
    surfaceContainerHighest = Color(0xFFDDE5EC),
    surfaceVariant = Color(0xFFDCE4EC),
)

/**
 * App theme. [ThemeMode.System] follows the OS setting. [onApplied] lets the platform host match
 * system chrome (status bar icons, window appearance) to the resolved theme.
 */
@Composable
fun RadarTheme(
    mode: ThemeMode = ThemeMode.System,
    onApplied: (mode: ThemeMode, dark: Boolean) -> Unit = { _, _ -> },
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    LaunchedEffect(mode, dark) { onApplied(mode, dark) }
    CompositionLocalProvider(LocalRadarColors provides if (dark) RadarColors.Dark else RadarColors.Light) {
        MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, content = content)
    }
}

/** Shorthand for the current [RadarColors] inside composables. */
val radarColors: RadarColors
    @Composable @ReadOnlyComposable get() = LocalRadarColors.current
