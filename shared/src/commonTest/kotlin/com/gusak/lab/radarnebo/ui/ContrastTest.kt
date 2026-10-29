package com.gusak.lab.radarnebo.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.gusak.lab.radarnebo.data.ColorPalette
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.fail

/**
 * WCAG 2.1 AA contrast of every text colour against what it is drawn on, in every palette and theme: 4.5:1 for
 * text (1.4.3), 3:1 for icons and map marks (1.4.11). Translucent chips and glass are checked over the darkest
 * and lightest backdrop and the plain map, since a map, satellite imagery or an alert can be behind them.
 */
class ContrastTest {
    @Test
    fun everyPaletteMeetsWcagAa() {
        val failures = mutableListOf<String>()
        for (palette in ColorPalette.entries) for (dark in listOf(false, true)) {
            val (radar, m) = themeColors(palette, dark)
            fun check(what: String, text: Color, on: Color, min: Float = TEXT_CONTRAST) {
                // Translucent text is seen over what it sits on.
                val r = contrast(text.compositeOver(on), on)
                if (r < min) failures += "$palette ${if (dark) "dark" else "light"}: $what ${(r * 100).roundToInt() / 100f} < $min"
            }
            fun checkText(where: String, on: Color) {
                check("onSurface on $where", m.onSurface, on)
                check("onSurfaceVariant on $where", m.onSurfaceVariant, on)
                check("primary on $where", m.primary, on)
                check("dangerText on $where", radar.dangerText, on)
                check("okText on $where", radar.okText, on)
                check("warningText on $where", radar.warningText, on)
            }
            // Material surfaces: panels and cards.
            checkText("surface", m.surface)
            checkText("background", m.background)
            checkText("surfaceContainerHigh", m.surfaceContainerHigh)
            check("onSurface on surfaceContainerHighest", m.onSurface, m.surfaceContainerHighest)
            check("onPrimary on primary", m.onPrimary, m.primary)
            check("onPrimaryContainer on primaryContainer", m.onPrimaryContainer, m.primaryContainer)
            for ((name, under) in listOf("black" to Color.Black, "white" to Color.White, "map" to radar.mapBackground)) {
                // Glass: the navigation bar's labels, and dialogs with their translucent section cards.
                val bar = radar.glassTint.compositeOver(under)
                check("nav label on glass/$name", m.onSurface, bar)
                check("nav label on lens/$name", m.onSurface, radar.glassSelection.compositeOver(bar))
                val dialog = m.surface.copy(alpha = GlassDialogTint).compositeOver(bar)
                checkText("dialog/$name", dialog)
                checkText("dialog card/$name", m.surfaceContainerHigh.copy(alpha = 0.55f).compositeOver(dialog))
                // Tags and rows tinted with their own status colour: REPORTED / ACTIVE, a favorite under threat.
                for ((status, text) in listOf(radar.ok to radar.okText, radar.danger to radar.dangerText, radar.warning to radar.warningText)) {
                    check("status tag/$name", text, status.copy(alpha = STATUS_TINT).compositeOver(dialog))
                    check("onSurface on status row/$name", m.onSurface, status.copy(alpha = STATUS_TINT).compositeOver(dialog))
                }
                // Chips over the map: the status strip, cinematic captions, timestamps, attribution.
                val chip = radar.overlay.compositeOver(under)
                check("onOverlay on overlay/$name", radar.onOverlay, chip)
                check("onOverlayMuted on overlay/$name", radar.onOverlayMuted, chip)
                check("dangerText on overlay/$name", radar.dangerText, chip)
                check("okText on overlay/$name", radar.okText, chip)
                check("warningText on overlay/$name", radar.warningText, chip)
            }
            // Drawn straight on the plain map (over tiles and imagery they get a halo of their own).
            for ((name, under) in listOf("mapBackground" to radar.mapBackground, "oblastIdle" to radar.oblastIdle)) {
                check("cityLabel on $name", radar.cityLabel, under)
                check("mapLabel on $name", radar.mapLabel, under)
                check("cityDot on $name", radar.cityDot, under, GRAPHIC_CONTRAST)
                check("danger mark on $name", radar.danger, under, GRAPHIC_CONTRAST)
                check("warning mark on $name", radar.warning, under, GRAPHIC_CONTRAST)
            }
            check("onWarning on warning", radar.onWarning, radar.warning)
        }
        if (failures.isNotEmpty()) fail("${failures.size} pairs below WCAG AA:\n" + failures.joinToString("\n"))
    }
}
