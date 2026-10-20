package com.gusak.lab.radarnebo.ui

import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.ui.preview.PreviewData
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.platform.LocalDensity
import com.gusak.lab.radarnebo.tracking.Place
import com.gusak.lab.radarnebo.tracking.TrackPredictor
import kotlin.time.Clock
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gusak.lab.radarnebo.data.MapMode
import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.ui.map.CinematicShot
import com.gusak.lab.radarnebo.ui.map.MapCamera
import com.gusak.lab.radarnebo.ui.map.MapSelection
import com.gusak.lab.radarnebo.ui.map.ShotPlanner
import com.gusak.lab.radarnebo.ui.map.UkraineMap
import com.gusak.lab.radarnebo.ui.map.rememberCinematicDirector

@Composable
fun MapScreen(
    state: UiState,
    onModeChange: (MapMode) -> Unit,
    onAddFavorite: (String, GeoPoint) -> Unit,
    /** Space taken by floating UI at the bottom (the glass navigation bar); the map still draws beneath it. */
    bottomInset: Dp = 0.dp,
) {
    var selection by remember { mutableStateOf<MapSelection?>(null) }
    var pendingFavorite by remember { mutableStateOf<GeoPoint?>(null) }
    var targetsOpen by rememberSaveable { mutableStateOf(false) }
    var selectedTrackId by rememberSaveable { mutableStateOf<String?>(null) }
    var staticCamera by remember { mutableStateOf(MapCamera.Overview) }
    val alertedFavoriteIds = remember(state.proximity) { state.proximity.map { it.favorite.id }.toSet() }
    // Favorites first so that, at equal distance, a favorite wins over the city it sits in.
    val predictionPlaces = remember(state.favorites) {
        state.favorites.map { Place(it.name, it.point, isFavorite = true) } + TrackPredictor.cityPlaces
    }
    val cinematic = state.settings.mapMode == MapMode.Cinematic
    val panelVisible = targetsOpen && !cinematic
    val onMapSelect: (MapSelection?) -> Unit = { picked ->
        if (picked is MapSelection.Track) {
            selectedTrackId = picked.track.id
            targetsOpen = true
            selection = null
        } else {
            selection = picked
            if (picked != null) targetsOpen = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(radarColors.mapBackground)) {
        val density = LocalDensity.current
        val wide = maxWidth >= 720.dp
        val panelHeight = minOf(maxHeight * 0.5f, 460.dp)
        val viewport = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        // Part of the map the targets panel leaves visible, where a selected target should be framed.
        val visibleArea = with(density) {
            if (wide) {
                Rect(0f, 120.dp.toPx(), (maxWidth - PanelWidth - 24.dp).toPx(), (maxHeight - bottomInset).toPx())
            } else {
                Rect(0f, 120.dp.toPx(), maxWidth.toPx(), (maxHeight - bottomInset - panelHeight - 12.dp).toPx())
            }
        }

        // Fly the static map to the selected target, framing its whole path and predicted destination.
        LaunchedEffect(selectedTrackId, panelVisible) {
            val track = state.tracks.firstOrNull { it.id == selectedTrackId } ?: return@LaunchedEffect
            if (!panelVisible || viewport.width <= 0f) return@LaunchedEffect
            val now = Clock.System.now().toEpochMilliseconds() + state.clockOffsetMs
            val points = track.samples.map { it.point } +
                listOfNotNull(TrackPredictor.predict(track, now, predictionPlaces)?.place?.point)
            val target = MapCamera.framing(points, viewport, visibleArea)
            val start = staticCamera
            animate(0f, 1f, animationSpec = tween(700, easing = FastOutSlowInEasing)) { f, _ ->
                staticCamera = MapCamera(lerp(start.center, target.center, f), start.zoom + (target.zoom - start.zoom) * f)
            }
        }

        Crossfade(state.settings.mapMode) { mode ->
            when (mode) {
                MapMode.Static -> UkraineMap(
                    camera = staticCamera,
                    threats = state.mapThreats,
                    alertedOblasts = state.alertedOblasts,
                    favorites = state.favorites,
                    alertedFavoriteIds = alertedFavoriteIds,
                    proximityRadiusKm = state.settings.proximityRadiusKm,
                    modifier = Modifier.fillMaxSize(),
                    selectedTrackId = selectedTrackId.takeIf { panelVisible },
                    predictionPlaces = predictionPlaces,
                    tracks = state.tracks,
                    clockOffsetMs = state.clockOffsetMs,
                    onCameraChange = { staticCamera = it },
                    onSelect = onMapSelect,
                    onLongPress = { pendingFavorite = it },
                )
                MapMode.Cinematic -> CinematicMap(state, alertedFavoriteIds, predictionPlaces, bottomInset, onMapSelect) { pendingFavorite = it }
            }
        }

        Row(Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                StatusStrip(state)
                if (!cinematic && state.proximity.isNotEmpty() && !(panelVisible && !wide)) {
                    Spacer(Modifier.height(8.dp))
                    ProximityBanner(state)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CinematicToggle(
                    cinematic = cinematic,
                    onToggle = { on -> onModeChange(if (on) MapMode.Cinematic else MapMode.Static) },
                )
                AnimatedVisibility(visible = !cinematic, enter = fadeIn(), exit = fadeOut()) {
                    TargetsButton(
                        open = targetsOpen,
                        activeCount = state.tracks.count { it.active },
                        onClick = {
                            targetsOpen = !targetsOpen
                            if (targetsOpen) selection = null
                        },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = panelVisible,
            enter = fadeIn() + slideInVertically { it / 8 },
            exit = fadeOut() + slideOutVertically { it / 8 },
            modifier = if (wide) {
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 128.dp, end = 12.dp, bottom = bottomInset + 12.dp)
                    .width(PanelWidth)
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = bottomInset + 12.dp)
                    .fillMaxWidth()
                    .height(panelHeight)
            },
        ) {
            TargetsPanel(
                tracks = state.tracks,
                clockOffsetMs = state.clockOffsetMs,
                places = predictionPlaces,
                selectedTrackId = selectedTrackId,
                onSelect = { selectedTrackId = it },
                onClose = { targetsOpen = false },
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(
            visible = selection != null && !panelVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 12.dp + bottomInset),
        ) {
            selection?.let { SelectionCard(it, state, onDismiss = { selection = null }) }
        }
    }

    pendingFavorite?.let { point ->
        AddFavoriteDialog(
            point = point,
            onConfirm = { name ->
                onAddFavorite(name, point)
                pendingFavorite = null
            },
            onDismiss = { pendingFavorite = null },
        )
    }
}

private val PanelWidth = 380.dp

@Composable
private fun CinematicMap(
    state: UiState,
    alertedFavoriteIds: Set<String>,
    predictionPlaces: List<Place>,
    bottomInset: Dp,
    onSelect: (MapSelection?) -> Unit,
    onLongPress: (GeoPoint) -> Unit,
) {
    val shots = remember(state.proximity, state.favorites, state.mapThreats, state.settings.proximityRadiusKm) {
        ShotPlanner.plan(state.proximity, state.favorites, state.mapThreats, state.settings.proximityRadiusKm)
    }
    val director = rememberCinematicDirector(shots)
    val shot = director.currentShot
    val danger = shot?.kind == CinematicShot.Kind.ThreatNearFavorite
    val letterbox by animateDpAsState(if (danger) 64.dp else 44.dp)

    Box(Modifier.fillMaxSize()) {
        UkraineMap(
            camera = director.camera,
            threats = state.mapThreats,
            alertedOblasts = state.alertedOblasts,
            favorites = state.favorites,
            alertedFavoriteIds = alertedFavoriteIds,
            proximityRadiusKm = state.settings.proximityRadiusKm,
            modifier = Modifier.fillMaxSize(),
            highlightedThreatId = shot?.threatId,
            predictionPlaces = predictionPlaces,
            tracks = state.tracks,
            clockOffsetMs = state.clockOffsetMs,
            onSelect = onSelect,
            onLongPress = onLongPress,
        )
        // Letterbox bars and a vignette frame sell the "camera" feel; they stay black in both themes.
        // The bottom bar extends under the floating navigation bar.
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(letterbox + 40.dp).background(Color.Black.copy(alpha = 0.55f)))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(letterbox + bottomInset).background(Color.Black.copy(alpha = 0.85f)))
        if (danger) {
            Box(Modifier.fillMaxSize().border(3.dp, radarColors.danger.copy(alpha = 0.8f)))
        }
        if (shot != null) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, end = 16.dp, bottom = letterbox + bottomInset + 12.dp)
                    .background(radarColors.overlay, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    shot.title,
                    color = if (danger) radarColors.danger else radarColors.ok,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Text(shot.subtitle, color = radarColors.onOverlay, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(
                    "SHOT ${director.shotIndex + 1}/${director.shotCount} · ${if (state.proximity.isEmpty()) "PATROL" else "${state.proximity.size} POI ALERT(S)"}",
                    color = radarColors.onOverlayMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun StatusStrip(state: UiState) {
    val text = buildString {
        if (state.settings.isDemo) append("DEMO DATA · add API token in Settings · ")
        append("${state.threats.size} ${if (state.settings.dronesOnly) "drone threats" else "threats"}")
        if (state.threats.size > state.mapThreats.size) append(" (showing ${state.mapThreats.size})")
        state.lastUpdated?.let { append(" · updated ${formatTime(it)}") }
    }
    Row(
        Modifier.background(radarColors.overlay, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (state.error != null) radarColors.danger else radarColors.ok, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text, color = radarColors.onOverlay, fontSize = 12.sp)
            state.error?.let { Text(it, color = radarColors.danger, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ProximityBanner(state: UiState) {
    Card(colors = CardDefaults.cardColors(containerColor = radarColors.danger.copy(alpha = 0.92f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Threats near your locations", fontWeight = FontWeight.Bold, color = Color.White)
            state.proximity.take(3).forEach { hit ->
                Text(
                    "${hit.favorite.name}: ${hit.threat.type.label} – " +
                        if (hit.insideRegion) "region-wide alert" else "${hit.distanceKm.toInt()} km",
                    color = Color.White,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun SelectionCard(selection: MapSelection, state: UiState, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(14.dp)) {
            when (selection) {
                is MapSelection.Threat -> {
                    val m = selection.marker
                    Text(m.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${m.type.label} · ${m.level.name} level",
                        color = if (m.level == ThreatLevel.Red) radarColors.danger else radarColors.warning,
                    )
                    Text("${m.oblastName} · ${m.locationType}", style = MaterialTheme.typography.bodySmall)
                    m.startedAt?.let { Text("Since ${formatTime(it)} (${formatAgo(it)})", style = MaterialTheme.typography.bodySmall) }
                    m.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (m.isRegionWide) Text("Region-wide: position shown at the oblast center", style = MaterialTheme.typography.labelSmall)
                }
                // Tracks open in the targets panel instead of this card.
                is MapSelection.Track -> Unit
                is MapSelection.Favorite -> {
                    val f = selection.favorite
                    val hit = state.proximity.firstOrNull { it.favorite.id == f.id }
                    Text(f.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (hit == null) "Clear within ${state.settings.proximityRadiusKm} km"
                        else "${hit.threat.type.label} – ${if (hit.insideRegion) "region-wide" else "${hit.distanceKm.toInt()} km away"}",
                        color = if (hit == null) radarColors.ok else radarColors.danger,
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}

@Composable
fun AddFavoriteDialog(point: GeoPoint, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add favorite location") },
        text = {
            Column {
                Text("${formatCoord(point.lat)}, ${formatCoord(point.lon)}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Preview(name = "Map · static · dark", widthDp = 420, heightDp = 860)
@Composable
private fun MapScreenStaticDarkPreview() {
    RadarTheme(ThemeMode.Dark) { MapScreen(PreviewData.state(), onModeChange = {}, onAddFavorite = { _, _ -> }) }
}

@Preview(name = "Map · static · light", widthDp = 420, heightDp = 860)
@Composable
private fun MapScreenStaticLightPreview() {
    RadarTheme(ThemeMode.Light) { MapScreen(PreviewData.state(ThemeMode.Light), onModeChange = {}, onAddFavorite = { _, _ -> }) }
}

@Preview(name = "Map · cinematic", widthDp = 420, heightDp = 860)
@Composable
private fun MapScreenCinematicPreview() {
    RadarTheme(ThemeMode.Dark) {
        MapScreen(PreviewData.state(mapMode = MapMode.Cinematic), onModeChange = {}, onAddFavorite = { _, _ -> })
    }
}

@Preview(name = "Map · tablet", widthDp = 1100, heightDp = 760)
@Composable
private fun MapScreenTabletPreview() {
    RadarTheme(ThemeMode.Dark) { MapScreen(PreviewData.state(), onModeChange = {}, onAddFavorite = { _, _ -> }) }
}

@Preview(name = "Add favorite dialog")
@Composable
private fun AddFavoriteDialogPreview() {
    RadarTheme(ThemeMode.Light) { AddFavoriteDialog(GeoPoint(50.45, 30.52), onConfirm = {}, onDismiss = {}) }
}
