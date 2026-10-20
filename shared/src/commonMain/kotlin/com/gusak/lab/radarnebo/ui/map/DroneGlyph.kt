package com.gusak.lab.radarnebo.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.ui.RadarTheme
import com.gusak.lab.radarnebo.ui.radarColors

/** How a drone glyph is rendered: attack drones solid, decoys hollow. */
enum class DroneGlyphStyle { Attack, Decoy }

/**
 * Top-down silhouette of a Shahed-type delta-wing drone, nose pointing along [headingDeg]
 * (clockwise from north). [size] is the distance from the centre to the nose.
 */
fun DrawScope.drawDroneGlyph(
    center: Offset,
    size: Float,
    headingDeg: Float,
    color: Color,
    outline: Color,
    style: DroneGlyphStyle = DroneGlyphStyle.Attack,
) {
    fun p(x: Float, y: Float) = Offset(center.x + x * size, center.y + y * size)
    // Delta wing with a rounded nose, swept leading edges and a straight trailing edge.
    val wing = Path().apply {
        moveTo(p(0f, -1f).x, p(0f, -1f).y)
        cubicTo(p(0.10f, -1f).x, p(0.10f, -1f).y, p(0.16f, -0.82f).x, p(0.16f, -0.82f).y, p(0.20f, -0.62f).x, p(0.20f, -0.62f).y)
        lineTo(p(0.98f, 0.52f).x, p(0.98f, 0.52f).y)
        lineTo(p(0.98f, 0.66f).x, p(0.98f, 0.66f).y)
        lineTo(p(0.20f, 0.66f).x, p(0.20f, 0.66f).y)
        lineTo(p(0.12f, 0.80f).x, p(0.12f, 0.80f).y)
        lineTo(p(-0.12f, 0.80f).x, p(-0.12f, 0.80f).y)
        lineTo(p(-0.20f, 0.66f).x, p(-0.20f, 0.66f).y)
        lineTo(p(-0.98f, 0.66f).x, p(-0.98f, 0.66f).y)
        lineTo(p(-0.98f, 0.52f).x, p(-0.98f, 0.52f).y)
        lineTo(p(-0.20f, -0.62f).x, p(-0.20f, -0.62f).y)
        cubicTo(p(-0.16f, -0.82f).x, p(-0.16f, -0.82f).y, p(-0.10f, -1f).x, p(-0.10f, -1f).y, p(0f, -1f).x, p(0f, -1f).y)
        close()
    }
    val line = (size * 0.12f).coerceAtLeast(1f)
    rotate(headingDeg, pivot = center) {
        when (style) {
            DroneGlyphStyle.Attack -> {
                drawPath(wing, color)
                drawPath(wing, outline, style = Stroke(line * 0.8f, join = StrokeJoin.Round))
                // Centre body ridge.
                drawLine(outline, p(0f, -0.7f), p(0f, 0.62f), line * 0.6f, cap = StrokeCap.Round)
            }
            DroneGlyphStyle.Decoy -> {
                drawPath(wing, color.copy(alpha = 0.25f))
                drawPath(wing, color, style = Stroke(line * 1.3f, join = StrokeJoin.Round))
            }
        }
        // Wingtip fins and the rear pusher propeller.
        drawLine(color, p(0.98f, 0.30f), p(0.98f, 0.74f), line * 1.4f, cap = StrokeCap.Round)
        drawLine(color, p(-0.98f, 0.30f), p(-0.98f, 0.74f), line * 1.4f, cap = StrokeCap.Round)
        drawLine(color, p(-0.30f, 0.92f), p(0.30f, 0.92f), line * 1.2f, cap = StrokeCap.Round)
    }
}

/** The drone glyph as a standalone icon, e.g. in the targets list. */
@Composable
fun DroneIcon(
    headingDeg: Float,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    style: DroneGlyphStyle = DroneGlyphStyle.Attack,
) {
    val outline = radarColors.glyphOutline
    Canvas(modifier.size(size)) {
        drawDroneGlyph(center, this.size.minDimension * 0.46f, headingDeg, color, outline, style)
    }
}

@Preview(name = "Drone icons")
@Composable
private fun DroneIconPreview() {
    RadarTheme(ThemeMode.Dark) {
        Row(
            Modifier.background(radarColors.mapBackground).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DroneIcon(0f, radarColors.danger, size = 48.dp)
            DroneIcon(270f, radarColors.warning, size = 48.dp)
            DroneIcon(315f, radarColors.danger, size = 48.dp, style = DroneGlyphStyle.Decoy)
        }
    }
}
