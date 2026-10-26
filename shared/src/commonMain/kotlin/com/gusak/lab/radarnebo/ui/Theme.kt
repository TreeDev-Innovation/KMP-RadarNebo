package com.gusak.lab.radarnebo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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
import androidx.compose.ui.graphics.lerp
import com.gusak.lab.radarnebo.data.ColorPalette
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
    /** City dots and the city names shown once zoomed in. */
    val cityDot: Color,
    val cityLabel: Color,
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
            cityDot = Color(0xB39FB8CF),
            cityLabel = Color(0xCCC8D6E4),
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
            cityDot = Color(0xFF8396A9),
            cityLabel = Color(0xFF3A4D60),
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
 * The few tones a [ColorPalette] variant is made of; [radarColors] and [materialScheme] derive the rest the way
 * the hand-tuned Radar palette does.
 */
private class PaletteTones(
    val isDark: Boolean,
    /** Behind the map, and the app's background. */
    val background: Color,
    /** Cards, sheets and bars. */
    val surface: Color,
    /** Oblasts with no alert. */
    val land: Color,
    /** Oblast borders and outlines. */
    val border: Color,
    val text: Color,
    val mutedText: Color,
    /** Primary colour: buttons, selection, favorites. */
    val accent: Color,
    val onAccent: Color,
    val ok: Color,
    val danger: Color,
    val warning: Color,
) {
    fun radarColors() = RadarColors(
        isDark = isDark,
        mapBackground = background,
        grid = lerp(background, border, 0.3f),
        oblastIdle = land,
        oblastBorder = border,
        oblastAlertFull = danger.copy(alpha = if (isDark) 0.4f else 0.35f),
        oblastAlertPartial = warning.copy(alpha = if (isDark) 0.25f else 0.32f),
        danger = danger,
        warning = warning,
        favorite = accent,
        ok = ok,
        overlay = if (isDark) lerp(background, Color.Black, 0.5f).copy(alpha = 0.6f) else surface.copy(alpha = 0.85f),
        onOverlay = text,
        onOverlayMuted = text.copy(alpha = 0.6f),
        mapLabel = text,
        glyphOutline = if (isDark) Color(0x99000000) else Color(0xB3FFFFFF),
        cityDot = mutedText.copy(alpha = 0.7f),
        cityLabel = mutedText.copy(alpha = 0.85f),
        glassTint = if (isDark) Color(0x1FFFFFFF) else Color(0x73FFFFFF),
        glassRimLight = if (isDark) Color(0x80FFFFFF) else Color(0xF2FFFFFF),
        glassRimShade = if (isDark) Color(0x14FFFFFF) else text.copy(alpha = 0.1f),
        glassSelection = if (isDark) Color(0x26FFFFFF) else Color(0x14000000),
    )

    fun materialScheme(): ColorScheme {
        val primaryContainer = lerp(surface, accent, if (isDark) 0.35f else 0.2f)
        val onPrimaryContainer = if (isDark) lerp(accent, Color.White, 0.6f) else lerp(accent, Color.Black, 0.55f)
        val base = if (isDark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = lerp(accent, if (isDark) Color.Black else Color.White, 0.4f),
            secondary = ok,
            onSecondary = if (isDark) background else Color.White,
            secondaryContainer = primaryContainer,
            onSecondaryContainer = onPrimaryContainer,
            tertiary = warning,
            error = danger,
            background = background,
            onBackground = text,
            surface = surface,
            onSurface = text,
            surfaceVariant = lerp(surface, text, 0.08f),
            onSurfaceVariant = mutedText,
            surfaceTint = accent,
            surfaceBright = lerp(surface, text, 0.1f),
            surfaceDim = lerp(surface, background, 0.6f),
            surfaceContainerLowest = lerp(surface, background, 0.6f),
            surfaceContainerLow = lerp(surface, text, 0.02f),
            surfaceContainer = lerp(surface, text, 0.04f),
            surfaceContainerHigh = lerp(surface, text, 0.07f),
            surfaceContainerHighest = lerp(surface, text, 0.1f),
            outline = border,
            outlineVariant = lerp(surface, border, 0.5f),
            inverseSurface = text,
            inverseOnSurface = surface,
        )
    }
}

private class PaletteVariants(val dark: PaletteTones, val light: PaletteTones)

private fun dark(
    background: Long, surface: Long, land: Long, border: Long, text: Long, mutedText: Long,
    accent: Long, onAccent: Long, ok: Long, danger: Long, warning: Long,
) = PaletteTones(
    true, Color(background), Color(surface), Color(land), Color(border), Color(text), Color(mutedText),
    Color(accent), Color(onAccent), Color(ok), Color(danger), Color(warning),
)

private fun light(
    background: Long, surface: Long, land: Long, border: Long, text: Long, mutedText: Long,
    accent: Long, onAccent: Long, ok: Long, danger: Long, warning: Long,
) = dark(background, surface, land, border, text, mutedText, accent, onAccent, ok, danger, warning).let {
    PaletteTones(false, it.background, it.surface, it.land, it.border, it.text, it.mutedText, it.accent, it.onAccent, it.ok, it.danger, it.warning)
}

/**
 * Tones of every palette but [ColorPalette.Radar], which keeps its hand-tuned colours. The popular ones follow
 * their published palettes (light variants: Nord's Snow Storm, Dracula's Alucard, Solarized Light, Catppuccin
 * Latte), with danger and warning kept a clear red and amber so alerts read the same in every palette.
 */
private val paletteVariants: Map<ColorPalette, PaletteVariants> = mapOf(
    ColorPalette.Nord to PaletteVariants(
        dark = dark(0xFF242933, 0xFF2E3440, 0xFF3B4252, 0xFF616E88, 0xFFECEFF4, 0xFFD8DEE9, 0xFF88C0D0, 0xFF2E3440, 0xFFA3BE8C, 0xFFBF616A, 0xFFEBCB8B),
        light = light(0xFFE5E9F0, 0xFFECEFF4, 0xFFF8F9FB, 0xFFA5B1C2, 0xFF2E3440, 0xFF4C566A, 0xFF5E81AC, 0xFFFFFFFF, 0xFF6E8F55, 0xFFBF616A, 0xFFC9922E),
    ),
    ColorPalette.Dracula to PaletteVariants(
        dark = dark(0xFF1E1F29, 0xFF282A36, 0xFF343746, 0xFF6272A4, 0xFFF8F8F2, 0xFFBFC3D9, 0xFFBD93F9, 0xFF282A36, 0xFF50FA7B, 0xFFFF5555, 0xFFFFB86C),
        light = light(0xFFEFEDDC, 0xFFFFFBEB, 0xFFFFFDF4, 0xFFA9A6C4, 0xFF1F1F1F, 0xFF635D97, 0xFF644AC9, 0xFFFFFFFF, 0xFF14710A, 0xFFCB3A2A, 0xFFA34D14),
    ),
    ColorPalette.Solarized to PaletteVariants(
        dark = dark(0xFF00212B, 0xFF002B36, 0xFF073642, 0xFF4F6B73, 0xFFEEE8D5, 0xFF93A1A1, 0xFF268BD2, 0xFFFDF6E3, 0xFF859900, 0xFFDC322F, 0xFFB58900),
        light = light(0xFFEEE8D5, 0xFFFDF6E3, 0xFFFFFBF0, 0xFF93A1A1, 0xFF073642, 0xFF586E75, 0xFF268BD2, 0xFFFFFFFF, 0xFF859900, 0xFFDC322F, 0xFFB58900),
    ),
    ColorPalette.Catppuccin to PaletteVariants(
        dark = dark(0xFF11111B, 0xFF1E1E2E, 0xFF313244, 0xFF6C7086, 0xFFCDD6F4, 0xFFA6ADC8, 0xFF89B4FA, 0xFF1E1E2E, 0xFFA6E3A1, 0xFFF38BA8, 0xFFFAB387),
        light = light(0xFFDCE0E8, 0xFFEFF1F5, 0xFFFFFFFF, 0xFF9CA0B0, 0xFF4C4F69, 0xFF6C6F85, 0xFF1E66F5, 0xFFFFFFFF, 0xFF40A02B, 0xFFD20F39, 0xFFDF8E1D),
    ),
    // Near-black violet with a plum accent.
    ColorPalette.ObsidianPlum to PaletteVariants(
        dark = dark(0xFF0D0A10, 0xFF16111B, 0xFF221A2A, 0xFF5A4468, 0xFFEFE6F4, 0xFFB8A6C4, 0xFFB565D9, 0xFF1A0F20, 0xFF6FD3A0, 0xFFFF5470, 0xFFF2B44B),
        light = light(0xFFEEE8F1, 0xFFFAF7FC, 0xFFFFFFFF, 0xFFB9A6C6, 0xFF22162A, 0xFF5E4B6B, 0xFF7E3A9E, 0xFFFFFFFF, 0xFF24875A, 0xFFD02F4E, 0xFFB9770E),
    ),
    // Warm charcoal with burnished copper.
    ColorPalette.SmokedCopper to PaletteVariants(
        dark = dark(0xFF0F0D0C, 0xFF1A1614, 0xFF26201C, 0xFF6B5446, 0xFFF2E9E2, 0xFFC2AFA2, 0xFFD08452, 0xFF2A140A, 0xFF8CC084, 0xFFFF5A4E, 0xFFF0B840),
        light = light(0xFFEDE6E0, 0xFFFAF6F2, 0xFFFFFFFF, 0xFFC3AE9E, 0xFF2A1E17, 0xFF6A5546, 0xFFA0522D, 0xFFFFFFFF, 0xFF3E7D3A, 0xFFC9302C, 0xFFB57A0A),
    ),
    // Deep navy with royal blue and a gold warning.
    ColorPalette.BlueHonor to PaletteVariants(
        dark = dark(0xFF070B18, 0xFF0E1530, 0xFF17224A, 0xFF3D4E8A, 0xFFEAF0FF, 0xFFA9B6DA, 0xFF5B8CFF, 0xFF0A1430, 0xFF4CD98F, 0xFFFF5263, 0xFFF5C542),
        light = light(0xFFE6ECF7, 0xFFF7F9FE, 0xFFFFFFFF, 0xFFA3B3D6, 0xFF0E1838, 0xFF45547E, 0xFF1F4FC8, 0xFFFFFFFF, 0xFF1E8A52, 0xFFD3283A, 0xFFB8860B),
    ),
)

private class ResolvedTheme(val radar: RadarColors, val material: ColorScheme)

private val radarDark = ResolvedTheme(RadarColors.Dark, darkScheme)
private val radarLight = ResolvedTheme(RadarColors.Light, lightScheme)
private val resolvedPalettes: Map<Pair<ColorPalette, Boolean>, ResolvedTheme> = paletteVariants.flatMap { (palette, variants) ->
    listOf(variants.dark, variants.light).map { tones -> (palette to tones.isDark) to ResolvedTheme(tones.radarColors(), tones.materialScheme()) }
}.toMap()

private fun resolve(palette: ColorPalette, dark: Boolean): ResolvedTheme =
    resolvedPalettes[palette to dark] ?: if (dark) radarDark else radarLight

/** Accent and background of [palette], for swatches that preview it. */
fun paletteSwatch(palette: ColorPalette, dark: Boolean): Pair<Color, Color> =
    resolve(palette, dark).radar.let { it.favorite to it.mapBackground }

/**
 * App theme in [palette]. [ThemeMode.System] follows the OS setting. [onApplied] lets the platform host match
 * system chrome (status bar icons, window appearance) to the resolved theme.
 */
@Composable
fun RadarTheme(
    mode: ThemeMode = ThemeMode.System,
    palette: ColorPalette = ColorPalette.Radar,
    onApplied: (mode: ThemeMode, dark: Boolean) -> Unit = { _, _ -> },
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    LaunchedEffect(mode, dark) { onApplied(mode, dark) }
    val theme = resolve(palette, dark)
    CompositionLocalProvider(LocalRadarColors provides theme.radar) {
        MaterialTheme(colorScheme = theme.material, content = content)
    }
}

/** Shorthand for the current [RadarColors] inside composables. */
val radarColors: RadarColors
    @Composable @ReadOnlyComposable get() = LocalRadarColors.current
