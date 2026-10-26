package com.gusak.lab.radarnebo.ui

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import com.gusak.lab.radarnebo.data.ThemeMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Round map-mode toggle. Static mode: an outlined button with a camera icon. Cinematic mode: the
 * button fills with the primary colour, the camera turns into a sweeping radar and the whole button
 * fades to 50% so it stays out of the shot. Tapping again returns to the static map.
 */
@Composable
fun CinematicToggle(cinematic: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val progress by animateFloatAsState(if (cinematic) 1f else 0f, tween(durationMillis = 450))

    Box(
        modifier
            .size(48.dp)
            .alpha(1f - 0.5f * progress)
            .clip(CircleShape)
            .background(lerp(radarColors.overlay, primary, progress), CircleShape)
            .border(1.5.dp, primary.copy(alpha = 1f - progress), CircleShape)
            .toggleable(value = cinematic, role = Role.Switch, onValueChange = onToggle)
            .semantics { contentDescription = if (cinematic) "Exit cinematic mode" else "Enter cinematic mode" },
        contentAlignment = Alignment.Center,
    ) {
        if (progress < 1f) {
            Canvas(Modifier.size(24.dp).alpha(1f - progress)) { drawCamera(primary) }
        }
        if (progress > 0f) {
            RadarIcon(onPrimary, Modifier.fillMaxSize().padding(8.dp).alpha(progress))
        }
    }
}

@Composable
private fun RadarIcon(color: Color, modifier: Modifier) {
    val sweep by rememberInfiniteTransition()
        .animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)))
    Canvas(modifier) {
        val r = size.minDimension / 2
        val stroke = Stroke(1.5.dp.toPx())
        drawCircle(color.copy(alpha = 0.9f), r - stroke.width / 2, style = stroke)
        drawCircle(color.copy(alpha = 0.5f), r * 0.55f, style = stroke)
        // Fading wedge trailing the beam.
        val steps = 12
        for (i in 0 until steps) {
            drawArc(
                color = color.copy(alpha = 0.35f * (1f - i / steps.toFloat())),
                startAngle = sweep - (i + 1) * 5f,
                sweepAngle = 5.5f,
                useCenter = true,
            )
        }
        val rad = sweep * PI / 180.0
        drawLine(color, center, Offset(center.x + (r * cos(rad)).toFloat(), center.y + (r * sin(rad)).toFloat()), 2.dp.toPx())
        drawCircle(color, 2.dp.toPx())
    }
}

/** Outlined photo camera: body, viewfinder bump and lens. */
private fun DrawScope.drawCamera(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = 1.8.dp.toPx())
    val bodyTop = h * 0.28f
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.06f, bodyTop),
        size = Size(w * 0.88f, h * 0.62f),
        cornerRadius = CornerRadius(w * 0.12f),
        style = stroke,
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.32f, h * 0.12f),
        size = Size(w * 0.36f, bodyTop - h * 0.12f + stroke.width / 2),
        cornerRadius = CornerRadius(w * 0.05f),
        style = stroke,
    )
    drawCircle(color, radius = w * 0.18f, center = Offset(w / 2, bodyTop + h * 0.31f), style = stroke)
    drawCircle(color, radius = w * 0.04f, center = Offset(w * 0.78f, bodyTop + h * 0.13f))
}

/** Opens the tracked-targets panel. Outlined crosshair when closed, filled when open; shows the active count. */
@Composable
fun TargetsButton(open: Boolean, activeCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val progress by animateFloatAsState(if (open) 1f else 0f, tween(durationMillis = 300))
    Box(modifier.size(48.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(lerp(radarColors.overlay, primary, progress), CircleShape)
                .border(1.5.dp, primary.copy(alpha = 1f - progress), CircleShape)
                .toggleable(value = open, role = Role.Switch, onValueChange = { onClick() })
                .semantics { contentDescription = if (open) "Hide targets" else "Show tracked targets" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(24.dp)) { drawCrosshair(lerp(primary, onPrimary, progress)) }
        }
        if (activeCount > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .background(radarColors.danger, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(activeCount.coerceAtMost(99).toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Opens the warning-radius control; the badge shows the current radius in km. */
@Composable
fun RadiusButton(open: Boolean, radiusKm: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val progress by animateFloatAsState(if (open) 1f else 0f, tween(durationMillis = 300))
    Box(modifier.size(48.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(lerp(radarColors.overlay, primary, progress), CircleShape)
                .border(1.5.dp, primary.copy(alpha = 1f - progress), CircleShape)
                .toggleable(value = open, role = Role.Switch, onValueChange = { onClick() })
                .semantics { contentDescription = "Warning radius, $radiusKm km" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(24.dp)) { drawRadius(lerp(primary, onPrimary, progress)) }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                // Hangs just below the button so the ring icon stays clear.
                .offset(y = 11.dp)
                .background(radarColors.favorite, CircleShape)
                .padding(horizontal = 5.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("$radiusKm km", color = radarColors.mapBackground, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Opens the request inspector: the requests the app sends to its server, with the responses. */
@Composable
fun RequestsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(radarColors.overlay, CircleShape)
            .border(1.5.dp, primary, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Requests to the server" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) { drawExchange(primary) }
    }
}

/** Two opposed arrows, a request going out and its response coming back. */
private fun DrawScope.drawExchange(color: Color) {
    val stroke = 1.8.dp.toPx()
    val w = size.width
    val h = size.height
    val head = w * 0.22f
    fun arrow(y: Float, toRight: Boolean) {
        val from = if (toRight) w * 0.08f else w * 0.92f
        val to = if (toRight) w * 0.92f else w * 0.08f
        drawLine(color, Offset(from, y), Offset(to, y), stroke, cap = StrokeCap.Round)
        val back = if (toRight) -head else head
        drawLine(color, Offset(to, y), Offset(to + back, y - head * 0.8f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(to, y), Offset(to + back, y + head * 0.8f), stroke, cap = StrokeCap.Round)
    }
    arrow(h * 0.32f, toRight = true)
    arrow(h * 0.68f, toRight = false)
}

/** A pin with a dashed ring around it, like the warning ring around favorites on the map. */
private fun DrawScope.drawRadius(color: Color) {
    val r = size.minDimension / 2
    val stroke = 1.8.dp.toPx()
    drawCircle(color, r - stroke / 2, style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * 0.38f, r * 0.24f))))
    drawCircle(color.copy(alpha = 0.25f), r * 0.55f)
    drawCircle(color, r * 0.2f)
}

private fun DrawScope.drawCrosshair(color: Color) {
    val r = size.minDimension / 2
    val stroke = 1.8.dp.toPx()
    drawCircle(color, r * 0.62f, style = Stroke(stroke))
    drawCircle(color, r * 0.16f)
    val gap = r * 0.32f
    drawLine(color, Offset(center.x, 0f), Offset(center.x, center.y - gap), stroke)
    drawLine(color, Offset(center.x, center.y + gap), Offset(center.x, size.height), stroke)
    drawLine(color, Offset(0f, center.y), Offset(center.x - gap, center.y), stroke)
    drawLine(color, Offset(center.x + gap, center.y), Offset(size.width, center.y), stroke)
}

@Preview(name = "Map buttons · dark")
@Composable
private fun MapButtonsDarkPreview() {
    RadarTheme(ThemeMode.Dark) {
        Row(Modifier.background(radarColors.mapBackground).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CinematicToggle(cinematic = false, onToggle = {})
            CinematicToggle(cinematic = true, onToggle = {})
            TargetsButton(open = false, activeCount = 4, onClick = {})
            TargetsButton(open = true, activeCount = 4, onClick = {})
            RadiusButton(open = false, radiusKm = 50, onClick = {})
            RadiusButton(open = true, radiusKm = 150, onClick = {})
        }
    }
}

@Preview(name = "Map buttons · light")
@Composable
private fun MapButtonsLightPreview() {
    RadarTheme(ThemeMode.Light) {
        Row(Modifier.background(radarColors.mapBackground).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CinematicToggle(cinematic = false, onToggle = {})
            CinematicToggle(cinematic = true, onToggle = {})
            TargetsButton(open = false, activeCount = 0, onClick = {})
            TargetsButton(open = true, activeCount = 2, onClick = {})
            RadiusButton(open = false, radiusKm = 25, onClick = {})
            RadiusButton(open = true, radiusKm = 25, onClick = {})
        }
    }
}
