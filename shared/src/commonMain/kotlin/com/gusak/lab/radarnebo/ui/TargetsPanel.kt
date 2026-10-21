package com.gusak.lab.radarnebo.ui

import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.ui.preview.PreviewData
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.tracking.ArrivalPrediction
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.InterpolatedPosition
import com.gusak.lab.radarnebo.tracking.Place
import com.gusak.lab.radarnebo.tracking.PositionMode
import com.gusak.lab.radarnebo.tracking.TrackInterpolator
import com.gusak.lab.radarnebo.tracking.TrackPredictor
import com.gusak.lab.radarnebo.tracking.TrackSample
import com.gusak.lab.radarnebo.tracking.TargetClassifier
import com.gusak.lab.radarnebo.tracking.TargetProfile
import com.gusak.lab.radarnebo.tracking.TrackMetrics
import com.gusak.lab.radarnebo.ui.map.TargetIcon
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Instant

/** Short, stable display name for a track id such as `trk-12`. */
fun targetName(trackId: String) = "T-" + trackId.substringAfter("trk-")

/**
 * Tracked drone targets: a list of every target with its latest report and predicted arrival, and a
 * detail view with the timestamped history of reported positions.
 */
@Composable
fun TargetsPanel(
    tracks: List<DroneTrack>,
    clockOffsetMs: Long,
    places: List<Place>,
    selectedTrackId: String?,
    onSelect: (String?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Positions and ETAs move continuously; refresh the panel once a second.
    val now by produceState(Clock.System.now().toEpochMilliseconds() + clockOffsetMs, clockOffsetMs) {
        while (true) {
            value = Clock.System.now().toEpochMilliseconds() + clockOffsetMs
            delay(1_000)
        }
    }
    val selected = tracks.firstOrNull { it.id == selectedTrackId }

    Surface(
        modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 12.dp,
    ) {
        if (selected == null) {
            TargetList(tracks, now, places, onSelect, onClose)
        } else {
            TargetDetail(selected, now, places, onBack = { onSelect(null) }, onClose = onClose)
        }
    }
}

@Composable
private fun PanelHeader(title: String, subtitle: String, onClose: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp)) {
        leading?.invoke()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClose) { Text("✕", fontSize = 18.sp) }
        }
    }
}

@Composable
private fun TargetList(
    tracks: List<DroneTrack>,
    now: Long,
    places: List<Place>,
    onSelect: (String?) -> Unit,
    onClose: () -> Unit,
) {
    var activeOnly by remember { mutableStateOf(true) }
    val active = tracks.count { it.active }
    val shown = tracks
        .filter { !activeOnly || it.active }
        .sortedWith(compareByDescending<DroneTrack> { it.active }.thenByDescending { it.samples.last().t })

    Column {
        PanelHeader("Targets", "$active active · ${tracks.size} tracked", onClose)
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(activeOnly, onClick = { activeOnly = true }, label = { Text("Active ($active)") })
            FilterChip(!activeOnly, onClick = { activeOnly = false }, label = { Text("All (${tracks.size})") })
        }
        if (shown.isEmpty()) {
            Text(
                "No drone targets tracked yet. Targets appear when a drone alert is followed by another one nearby.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
            items(shown, key = { it.id }) { track ->
                TargetRow(track, now, TrackPredictor.predict(track, now, places)) { onSelect(track.id) }
            }
        }
    }
}

@Composable
private fun TargetRow(track: DroneTrack, now: Long, prediction: ArrivalPrediction?, onClick: () -> Unit) {
    val colors = radarColors
    val last = track.samples.last()
    val motion = TrackInterpolator.positionAt(track, now)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        val profile = TargetClassifier.classify(track, motion?.speedKmh)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TargetIcon(
                kind = profile.kind,
                headingDeg = (motion?.headingDeg ?: 270.0).toFloat(),
                color = levelColor(track.level),
                size = 26.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(targetName(track.id), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            StatusTag(track.active)
            Spacer(Modifier.weight(1f))
            Text(formatTime(Instant.fromEpochMilliseconds(last.t)), style = MaterialTheme.typography.labelLarge)
        }
        Row {
            Text(locationLabel(last), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(formatAgo(Instant.fromEpochMilliseconds(last.t)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            listOfNotNull(profile.model + if (profile.source == TargetProfile.Source.Probable) " (probable)" else "", motionLabel(motion), reportsLabel(track.samples.size)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        prediction?.let {
            Text(
                "→ ${it.place.name} in ~${it.etaMinutes} min (${it.distanceKm.toInt()} km)",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.warning,
            )
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun TargetDetail(track: DroneTrack, now: Long, places: List<Place>, onBack: () -> Unit, onClose: () -> Unit) {
    val colors = radarColors
    val position = TrackInterpolator.positionAt(track, now)
    val prediction = TrackPredictor.predict(track, now, places)
    val route = TrackPredictor.route(track, now, places)
    val profile = TargetClassifier.classify(track, position?.speedKmh)
    val trackedMinutes = ((now.coerceAtLeast(track.samples.last().t) - track.samples.first().t) / 60_000).coerceAtLeast(0)
    val flownKm = TrackMetrics.distanceFlownKm(track, position)

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            PanelHeader(
                title = "Target ${targetName(track.id)}",
                subtitle = listOfNotNull(
                    track.oblastUid?.let { Regions.oblast(it)?.nameEn },
                    "${track.level.name} level",
                    if (track.active) "active" else "cleared",
                ).joinToString(" · "),
                onClose = onClose,
                leading = { TextButton(onClick = onBack) { Text("‹ All targets") } },
            )
        }
        item { ProfileCard(track, profile, position) }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Speed", position?.speedKmh?.let { "~${it.toInt()} km/h" } ?: "–", Modifier.weight(1f))
                    Stat("Direction", position?.headingDeg?.let { "${compass(it)} · ${it.toInt()}°" } ?: "–", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Distance flown", "${flownKm.toInt()} km", Modifier.weight(1f))
                    Stat("Tracked for", "$trackedMinutes min", Modifier.weight(1f))
                }
            }
        }
        item {
            Column(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .background(
                        if (prediction != null) colors.warning.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(12.dp),
            ) {
                Text("Prediction", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (prediction != null) {
                    Text(
                        "${prediction.place.name} in ~${prediction.etaMinutes} min",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${prediction.distanceKm.toInt()} km ahead at ~${prediction.speedKmh.toInt()} km/h" +
                            if (prediction.place.isFavorite) " · one of your favorites" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        if (track.active) "No settlement within ${TrackPredictor.DEFAULT_RANGE_KM.toInt()} km on its current heading"
                        else "Target cleared",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (route.isNotEmpty()) {
            item {
                Text(
                    "Predicted route",
                    Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(route.size) { index -> RouteStopRow(index, route[index], now) }
        }
        item {
            Text(
                "Timeline",
                Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        if (track.active && position != null) {
            item { EstimatedNowRow(position, now) }
        }
        val samples = track.samples.asReversed()
        items(samples.size) { index ->
            TimelineRow(samples[index], isLatest = index == 0, isOldest = index == samples.lastIndex, level = track.level)
        }
        item {
            Text(
                "Positions are inferred from area alerts, not radar. Predictions assume the target keeps its current heading and speed.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EstimatedNowRow(position: InterpolatedPosition, now: Long) {
    TimelineItem(
        dotColor = radarColors.warning,
        hollow = true,
        drawLineBelow = true,
        drawLineAbove = false,
    ) {
        Text("Now · ${formatTime(Instant.fromEpochMilliseconds(now))}", fontWeight = FontWeight.SemiBold)
        Text(
            when (position.mode) {
                PositionMode.Interpolated -> "Estimated between reports"
                PositionMode.Extrapolated -> "Estimated past the last report"
                PositionMode.Held -> "At the last reported area"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(coordinates(position.lat, position.lon), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TimelineRow(sample: TrackSample, isLatest: Boolean, isOldest: Boolean, level: ThreatLevel) {
    TimelineItem(
        dotColor = levelColor(level),
        hollow = false,
        drawLineAbove = true,
        drawLineBelow = !isOldest,
    ) {
        Row {
            Text(formatClock(sample.t), fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(formatAgo(Instant.fromEpochMilliseconds(sample.t)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isLatest) {
                Spacer(Modifier.width(8.dp))
                Text("latest report", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(locationLabel(sample), style = MaterialTheme.typography.bodyMedium)
        Text(coordinates(sample.lat, sample.lon), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

/** A row of the vertical timeline: a dot on a connecting line, with content to the right. */
@Composable
private fun TimelineItem(
    dotColor: Color,
    hollow: Boolean,
    drawLineAbove: Boolean,
    drawLineBelow: Boolean,
    content: @Composable () -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.padding(horizontal = 16.dp).height(IntrinsicSize.Min)) {
        Box(Modifier.width(20.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(2.dp).height(6.dp).background(if (drawLineAbove) lineColor else Color.Transparent))
                Box(
                    Modifier
                        .size(12.dp)
                        .then(if (hollow) Modifier.border(2.dp, dotColor, CircleShape) else Modifier.background(dotColor, CircleShape)),
                )
                Box(Modifier.width(2.dp).weight(1f).background(if (drawLineBelow) lineColor else Color.Transparent))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.padding(bottom = 12.dp)) { content() }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusTag(active: Boolean) {
    val color = if (active) radarColors.danger else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        if (active) "ACTIVE" else "CLEARED",
        Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun levelColor(level: ThreatLevel) = if (level == ThreatLevel.Red) radarColors.danger else radarColors.warning

/** Target type card: icon, model, maker, role and whether the model was reported or is inferred. */
@Composable
private fun ProfileCard(track: DroneTrack, profile: TargetProfile, position: InterpolatedPosition?) {
    val alertIds = track.markerIds.map { it.substringBefore(':') }.distinct()
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TargetIcon(
            kind = profile.kind,
            headingDeg = (position?.headingDeg ?: 270.0).toFloat(),
            color = levelColor(track.level),
            size = 44.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile.model, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                val reported = profile.source == TargetProfile.Source.Reported
                Text(
                    if (reported) "REPORTED" else "PROBABLE",
                    Modifier
                        .background((if (reported) radarColors.ok else radarColors.warning).copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    color = if (reported) radarColors.ok else radarColors.warning,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            LabelValue("Class", profile.kind.label)
            LabelValue("Make", profile.make)
            LabelValue("Role", profile.role)
            profile.cruiseSpeedKmh?.let { LabelValue("Typical speed", "${it.first}–${it.last} km/h") }
            LabelValue("ID", "${targetName(track.id)} · track ${track.id}")
            if (alertIds.isNotEmpty()) LabelValue("Alerts", alertIds.joinToString(", ") { "#$it" })
            if (profile.source == TargetProfile.Source.Probable) {
                Text(
                    "alerts.in.ua doesn't identify models; inferred from the threat class and speed.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row {
        Text("$label  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RouteStopRow(index: Int, stop: ArrivalPrediction, now: Long) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).background(radarColors.warning, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("${index + 1}", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stop.place.name + if (stop.place.isFavorite) " ★" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text("${stop.distanceKm.toInt()} km ahead", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("~${stop.etaMinutes} min", fontWeight = FontWeight.Bold, color = radarColors.warning)
            Text(
                "at " + formatTime(Instant.fromEpochMilliseconds(now + stop.etaMinutes * 60_000L)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun locationLabel(sample: TrackSample): String =
    listOfNotNull(sample.title, sample.oblastUid?.let { Regions.oblast(it)?.nameEn }).distinct().joinToString(" · ")

private fun motionLabel(position: InterpolatedPosition?): String? {
    val speed = position?.speedKmh ?: return null
    val heading = position.headingDeg?.let { " ${compass(it)}" } ?: ""
    return "~${speed.toInt()} km/h$heading"
}

private fun reportsLabel(count: Int) = if (count == 1) "1 report" else "$count reports"

private fun coordinates(lat: Double, lon: Double) = "${formatCoord(lat)}° N, ${formatCoord(lon)}° E"

fun compass(deg: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 382.5) / 45).toInt() % 8]

@Preview(name = "Targets · list · dark", widthDp = 380, heightDp = 640)
@Composable
private fun TargetsListDarkPreview() {
    val state = PreviewData.state()
    RadarTheme(ThemeMode.Dark) {
        TargetsPanel(state.tracks, 0, TrackPredictor.cityPlaces, selectedTrackId = null, onSelect = {}, onClose = {})
    }
}

@Preview(name = "Targets · list · light", widthDp = 380, heightDp = 640)
@Composable
private fun TargetsListLightPreview() {
    val state = PreviewData.state(ThemeMode.Light)
    RadarTheme(ThemeMode.Light) {
        TargetsPanel(state.tracks, 0, TrackPredictor.cityPlaces, selectedTrackId = null, onSelect = {}, onClose = {})
    }
}

@Preview(name = "Targets · list · all types", widthDp = 380, heightDp = 900)
@Composable
private fun TargetsListAllTypesPreview() {
    val state = PreviewData.state(dronesOnly = false)
    RadarTheme(ThemeMode.Dark) {
        TargetsPanel(state.tracks, 0, TrackPredictor.cityPlaces, selectedTrackId = null, onSelect = {}, onClose = {})
    }
}

@Preview(name = "Targets · detail · cruise missile", widthDp = 380, heightDp = 1100)
@Composable
private fun TargetDetailMissilePreview() {
    val state = PreviewData.state(dronesOnly = false)
    val missile = state.tracks.filter { it.type == ThreatType.CruiseMissiles }.maxByOrNull { it.samples.size }?.id
    RadarTheme(ThemeMode.Dark) {
        TargetsPanel(state.tracks, 0, TrackPredictor.cityPlaces, missile ?: PreviewData.busiestTrackId(state), onSelect = {}, onClose = {})
    }
}

@Preview(name = "Targets · detail · dark", widthDp = 380, heightDp = 1100)
@Composable
private fun TargetDetailDarkPreview() {
    val state = PreviewData.state()
    RadarTheme(ThemeMode.Dark) {
        TargetsPanel(state.tracks, 0, TrackPredictor.cityPlaces, PreviewData.busiestTrackId(state), onSelect = {}, onClose = {})
    }
}

@Preview(name = "Targets · detail · light", widthDp = 380, heightDp = 1100)
@Composable
private fun TargetDetailLightPreview() {
    val state = PreviewData.state(ThemeMode.Light)
    RadarTheme(ThemeMode.Light) {
        TargetsPanel(state.tracks, 0, TrackPredictor.cityPlaces, PreviewData.busiestTrackId(state), onSelect = {}, onClose = {})
    }
}
