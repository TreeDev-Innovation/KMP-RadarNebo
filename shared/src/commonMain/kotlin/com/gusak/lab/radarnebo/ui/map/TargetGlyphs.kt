package com.gusak.lab.radarnebo.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.tracking.TargetKind
import com.gusak.lab.radarnebo.ui.RadarTheme
import com.gusak.lab.radarnebo.ui.radarColors

/** Air defense and unknown threats have no direction of travel; their icons are drawn upright. */
val TargetKind.isDirectional get() = this != TargetKind.AirDefense && this != TargetKind.Unknown

/**
 * Top-down silhouette of [kind], nose pointing along [headingDeg] (clockwise from north). [size] is
 * the distance from the centre to the nose. Shapes are laid out in unit coordinates, nose at (0, -1).
 */
fun DrawScope.drawTargetGlyph(
    kind: TargetKind,
    center: Offset,
    size: Float,
    headingDeg: Float,
    color: Color,
    outline: Color,
) {
    when (kind) {
        TargetKind.AttackDrone -> return drawDroneGlyph(center, size, headingDeg, color, outline, DroneGlyphStyle.Attack)
        TargetKind.DecoyDrone -> return drawDroneGlyph(center, size, headingDeg, color, outline, DroneGlyphStyle.Decoy)
        else -> Unit
    }
    val g = Glyph(this, center, size, color, outline)
    rotate(if (kind.isDirectional) headingDeg else 0f, pivot = center) {
        when (kind) {
            TargetKind.JetDrone -> g.jetDrone()
            TargetKind.ReconDrone -> g.reconDrone()
            TargetKind.CruiseMissile -> g.cruiseMissile()
            TargetKind.BallisticMissile -> g.ballisticMissile()
            TargetKind.AeroballisticMissile -> g.aeroballisticMissile()
            TargetKind.GuidedBomb -> g.guidedBomb()
            TargetKind.TacticalAircraft -> g.tacticalAircraft()
            TargetKind.StrategicBomber -> g.strategicBomber()
            TargetKind.MiG31K -> g.mig31k()
            TargetKind.AirDefense -> g.airDefense()
            TargetKind.Unknown -> g.unknown()
            TargetKind.AttackDrone, TargetKind.DecoyDrone -> Unit
        }
    }
}

/** Drawing helpers in the glyph's unit coordinates. */
private class Glyph(val scope: DrawScope, val center: Offset, val size: Float, val color: Color, val outline: Color) {
    val line = (size * 0.12f).coerceAtLeast(1f)

    fun p(x: Float, y: Float) = Offset(center.x + x * size, center.y + y * size)

    /** Closed polygon through (x, y) pairs. */
    fun poly(vararg xy: Float) = Path().apply {
        for (i in xy.indices step 2) {
            val o = p(xy[i], xy[i + 1])
            if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
        }
        close()
    }

    /** Polygon symmetric about the centre line, from its right half listed nose to tail. */
    fun mirrored(vararg rightHalf: Float): Path {
        val n = rightHalf.size / 2
        val full = FloatArray(n * 4)
        for (i in 0 until n) {
            full[i * 2] = rightHalf[i * 2]
            full[i * 2 + 1] = rightHalf[i * 2 + 1]
            full[(2 * n - 1 - i) * 2] = -rightHalf[i * 2]
            full[(2 * n - 1 - i) * 2 + 1] = rightHalf[i * 2 + 1]
        }
        return poly(*full)
    }

    /** Wing or fin pair: the right one given, the left one mirrored. */
    fun pair(vararg right: Float): List<Path> =
        listOf(poly(*right), poly(*FloatArray(right.size) { if (it % 2 == 0) -right[it] else right[it] }))

    /** Fills [parts] as one silhouette: outlines first, fills on top, so seams between parts vanish. */
    fun solid(parts: List<Path>, fill: Color = color) = with(scope) {
        parts.forEach { drawPath(it, outline, style = Stroke(line * 1.6f, join = StrokeJoin.Round)) }
        parts.forEach { drawPath(it, fill) }
    }

    fun stroke(from: Offset, to: Offset, width: Float = line, c: Color = color) =
        scope.drawLine(c, from, to, width, cap = StrokeCap.Round)

    // --- Drones -------------------------------------------------------------------------------------

    /** Shahed-238: sharper delta than the Shahed-136, with a jet engine on the spine instead of a propeller. */
    fun jetDrone() {
        solid(listOf(mirrored(0f, -1f, 0.1f, -0.78f, 0.92f, 0.46f, 0.92f, 0.6f, 0.16f, 0.6f, 0f, 0.6f)))
        solid(listOf(mirrored(0f, 0.05f, 0.15f, 0.12f, 0.15f, 0.92f, 0f, 0.92f)), outline)
        stroke(p(0.92f, 0.34f), p(0.92f, 0.68f), line * 1.3f)
        stroke(p(-0.92f, 0.34f), p(-0.92f, 0.68f), line * 1.3f)
    }

    /** Orlan-10 style: slim fuselage, long straight wing, small tailplane and a nose propeller. */
    fun reconDrone() {
        solid(
            listOf(
                mirrored(0f, -0.88f, 0.12f, -0.78f, 0.12f, 0.6f, 0.05f, 0.86f, 0f, 0.86f),
                poly(-1f, -0.34f, 1f, -0.34f, 1f, -0.1f, -1f, -0.1f),
                poly(-0.42f, 0.6f, 0.42f, 0.6f, 0.42f, 0.78f, -0.42f, 0.78f),
            ),
        )
        stroke(p(-0.3f, -0.98f), p(0.3f, -0.98f), line * 1.1f)
    }

    // --- Missiles and bombs ---------------------------------------------------------------------------

    /** Kh-101 / Kalibr: long slim body, small pop-out wings mid-body, tail fins. */
    fun cruiseMissile() {
        solid(
            listOf(mirrored(0f, -1f, 0.07f, -0.9f, 0.1f, -0.72f, 0.1f, 0.88f, 0f, 0.88f)) +
                pair(0.1f, -0.06f, 0.66f, 0.14f, 0.66f, 0.22f, 0.1f, 0.12f) +
                pair(0.1f, 0.6f, 0.32f, 0.86f, 0.32f, 0.94f, 0.1f, 0.86f),
        )
    }

    /** Iskander-M: thick body, pointed nose cone, fins at the base and an exhaust plume. */
    fun ballisticMissile() {
        scope.drawPath(mirrored(0f, 1.18f, 0.12f, 0.86f, 0f, 0.86f), color.copy(alpha = 0.55f))
        solid(
            listOf(mirrored(0f, -1f, 0.1f, -0.78f, 0.19f, -0.5f, 0.19f, 0.86f, 0f, 0.86f)) +
                pair(0.19f, 0.42f, 0.44f, 0.74f, 0.44f, 0.9f, 0.19f, 0.82f),
        )
        stroke(p(-0.19f, -0.5f), p(0.19f, -0.5f), line * 0.7f, outline)
    }

    /** Kinzhal: a slender dart with a long nose and broad delta tail fins. */
    fun aeroballisticMissile() {
        solid(
            listOf(mirrored(0f, -1f, 0.05f, -0.8f, 0.12f, -0.4f, 0.12f, 0.9f, 0f, 0.9f)) +
                pair(0.12f, 0.36f, 0.46f, 0.84f, 0.46f, 0.9f, 0.12f, 0.9f),
        )
    }

    /** KAB with UMPK kit: stubby bomb with long unfolded glide wings and a cross tail. */
    fun guidedBomb() {
        solid(
            listOf(
                mirrored(0f, -0.82f, 0.14f, -0.74f, 0.22f, -0.5f, 0.22f, 0.4f, 0.12f, 0.6f, 0f, 0.6f),
                poly(-1f, -0.1f, 1f, -0.1f, 0.96f, 0.02f, -0.96f, 0.02f),
            ) + pair(0.1f, 0.5f, 0.4f, 0.74f, 0.4f, 0.86f, 0.1f, 0.74f),
        )
    }

    // --- Aircraft ------------------------------------------------------------------------------------

    /** Su-34 / Su-35: swept wings, canards, tailplanes and twin fins. */
    fun tacticalAircraft() {
        solid(
            listOf(mirrored(0f, -1f, 0.08f, -0.82f, 0.14f, -0.5f, 0.16f, 0.9f, 0f, 0.96f)) +
                pair(0.14f, -0.24f, 0.96f, 0.32f, 0.96f, 0.44f, 0.16f, 0.34f) +
                pair(0.14f, -0.5f, 0.34f, -0.38f, 0.34f, -0.32f, 0.14f, -0.34f) +
                pair(0.16f, 0.56f, 0.52f, 0.82f, 0.52f, 0.92f, 0.16f, 0.86f),
        )
        stroke(p(0.12f, 0.46f), p(0.12f, 0.8f), line * 0.8f, outline)
        stroke(p(-0.12f, 0.46f), p(-0.12f, 0.8f), line * 0.8f, outline)
    }

    /** Tu-95MS: long fuselage, big swept wing with four engine nacelles, swept tailplane. */
    fun strategicBomber() {
        // Leading edge of the wing, which runs from (0.1, -0.28) at the root to (1, 0.22) at the tip.
        fun leadingEdge(x: Float) = -0.28f + (kotlin.math.abs(x) - 0.1f) * 0.5f / 0.9f
        val engines = listOf(0.4f, 0.7f, -0.4f, -0.7f).map { x ->
            val front = leadingEdge(x) - 0.12f
            poly(x - 0.05f, front, x + 0.05f, front, x + 0.05f, front + 0.24f, x - 0.05f, front + 0.24f)
        }
        solid(
            listOf(mirrored(0f, -1f, 0.07f, -0.9f, 0.11f, -0.7f, 0.11f, 0.9f, 0.04f, 1f, 0f, 1f)) +
                pair(0.1f, -0.28f, 1f, 0.22f, 1f, 0.34f, 0.1f, 0.1f) +
                pair(0.1f, 0.66f, 0.44f, 0.88f, 0.44f, 0.97f, 0.1f, 0.87f) +
                engines,
        )
    }

    /** MiG-31K: broad twin-engine interceptor with a Kinzhal under the belly. */
    fun mig31k() {
        solid(
            listOf(mirrored(0f, -1f, 0.08f, -0.84f, 0.16f, -0.5f, 0.22f, -0.1f, 0.22f, 0.92f, 0f, 0.92f)) +
                pair(0.22f, -0.06f, 0.86f, 0.3f, 0.86f, 0.44f, 0.22f, 0.36f) +
                pair(0.22f, 0.58f, 0.58f, 0.84f, 0.58f, 0.94f, 0.22f, 0.9f),
        )
        // The missile it carries, in the outline colour so it stands out against the airframe.
        solid(listOf(mirrored(0f, -0.62f, 0.07f, -0.48f, 0.07f, 0.5f, 0f, 0.5f)), outline)
    }

    // --- Not flying ----------------------------------------------------------------------------------

    /** Air defence: a shield with an interceptor rising from it. */
    fun airDefense() = with(scope) {
        val shield = Path().apply {
            moveTo(p(-0.78f, -0.72f).x, p(-0.78f, -0.72f).y)
            lineTo(p(0.78f, -0.72f).x, p(0.78f, -0.72f).y)
            cubicTo(p(0.78f, 0.1f).x, p(0.78f, 0.1f).y, p(0.5f, 0.66f).x, p(0.5f, 0.66f).y, p(0f, 0.98f).x, p(0f, 0.98f).y)
            cubicTo(p(-0.5f, 0.66f).x, p(-0.5f, 0.66f).y, p(-0.78f, 0.1f).x, p(-0.78f, 0.1f).y, p(-0.78f, -0.72f).x, p(-0.78f, -0.72f).y)
            close()
        }
        drawPath(shield, outline, style = Stroke(line * 1.8f, join = StrokeJoin.Round))
        drawPath(shield, color.copy(alpha = 0.3f))
        drawPath(shield, color, style = Stroke(line * 1.1f, join = StrokeJoin.Round))
        solid(listOf(mirrored(0f, -1f, 0.1f, -0.8f, 0.1f, 0.42f, 0f, 0.42f)) + pair(0.1f, 0.18f, 0.28f, 0.46f, 0.1f, 0.4f))
    }

    /** Unknown: a diamond with an exclamation mark. */
    fun unknown() = with(scope) {
        val diamond = poly(0f, -1f, 0.92f, 0f, 0f, 1f, -0.92f, 0f)
        drawPath(diamond, outline, style = Stroke(line * 1.6f, join = StrokeJoin.Round))
        drawPath(diamond, color)
        stroke(p(0f, -0.5f), p(0f, 0.18f), line * 1.6f, outline)
        drawCircle(outline, line * 1.0f, p(0f, 0.46f))
    }
}

/** A target's icon on its own, e.g. in lists and cards. */
@Composable
fun TargetIcon(
    kind: TargetKind,
    headingDeg: Float,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    val outline = radarColors.glyphOutline
    Canvas(modifier.size(size)) {
        drawTargetGlyph(kind, center, this.size.minDimension * 0.46f, headingDeg, color, outline)
    }
}

/** Every target icon with its name, in both threat-level colours. */
@Composable
internal fun TargetIconGallery(modifier: Modifier = Modifier) {
    val colors = radarColors
    Column(modifier.background(colors.mapBackground).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TargetKind.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { kind ->
                    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row {
                            TargetIcon(kind, 0f, colors.danger, size = 34.dp)
                            TargetIcon(kind, 300f, colors.warning, size = 34.dp)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            kind.label,
                            color = colors.mapLabel,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Target icons · dark", widthDp = 440)
@Composable
private fun TargetIconsDarkPreview() {
    RadarTheme(ThemeMode.Dark) { TargetIconGallery() }
}

@Preview(name = "Target icons · light", widthDp = 440)
@Composable
private fun TargetIconsLightPreview() {
    RadarTheme(ThemeMode.Light) { TargetIconGallery() }
}

@Preview(name = "Target icons · large", widthDp = 440)
@Composable
private fun TargetIconsLargePreview() {
    RadarTheme(ThemeMode.Dark) {
        Column(Modifier.background(radarColors.mapBackground).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TargetKind.entries.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { TargetIcon(it, 0f, radarColors.danger, size = 72.dp) }
                }
            }
        }
    }
}
