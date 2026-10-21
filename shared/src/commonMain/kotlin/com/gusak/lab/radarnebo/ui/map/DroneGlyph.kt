package com.gusak.lab.radarnebo.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

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
