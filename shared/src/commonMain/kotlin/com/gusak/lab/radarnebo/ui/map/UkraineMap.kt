package com.gusak.lab.radarnebo.ui.map

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.Flow
import kotlin.math.exp
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.gusak.lab.radarnebo.data.FavoriteLocation
import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.domain.ThreatMarker
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.geo.MapCity
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.geo.UkraineCities
import com.gusak.lab.radarnebo.geo.UkraineGeometry
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.InterpolatedPosition
import com.gusak.lab.radarnebo.tracking.PositionMode
import com.gusak.lab.radarnebo.tracking.TrackInterpolator
import com.gusak.lab.radarnebo.tracking.ArrivalPrediction
import com.gusak.lab.radarnebo.tracking.Place
import com.gusak.lab.radarnebo.tracking.TrackPredictor
import com.gusak.lab.radarnebo.tracking.TargetClassifier
import com.gusak.lab.radarnebo.tracking.TargetKind
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.ui.formatTime
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import kotlin.time.Instant
import kotlin.time.Clock
import com.gusak.lab.radarnebo.ui.LocalRadarColors
import com.gusak.lab.radarnebo.ui.RadarColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Equirectangular projection centred on Ukraine. World units are degrees, with longitude squashed
 * by cos(48.5°) so shapes keep their proportions at Ukraine's latitude.
 */
object MapProjection {
    private const val K = 0.6626f
    private const val KM_PER_DEGREE = 111.2f

    val bounds = Rect(left = 22.0f * K, top = -52.5f, right = 40.3f * K, bottom = -44.2f)

    fun toWorld(p: GeoPoint) = Offset((p.lon * K).toFloat(), (-p.lat).toFloat())
    fun toGeo(world: Offset) = GeoPoint(lat = -world.y.toDouble(), lon = (world.x / K).toDouble())
    fun kmToWorld(km: Float) = km / KM_PER_DEGREE
}

data class MapCamera(val center: Offset, val zoom: Float) {

    fun scale(size: Size) = min(size.width / MapProjection.bounds.width, size.height / MapProjection.bounds.height) * 0.94f * zoom

    fun toScreen(world: Offset, size: Size): Offset {
        val s = scale(size)
        return Offset(size.width / 2 + (world.x - center.x) * s, size.height / 2 + (world.y - center.y) * s)
    }

    fun toWorld(screen: Offset, size: Size): Offset {
        val s = scale(size)
        return Offset(center.x + (screen.x - size.width / 2) / s, center.y + (screen.y - size.height / 2) / s)
    }

    /** Applies a pinch/pan gesture keeping the point under [centroid] fixed. */
    fun transformed(centroid: Offset, pan: Offset, zoomChange: Float, size: Size, maxZoom: Float): MapCamera {
        val anchor = toWorld(centroid, size)
        val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, maxZoom)
        val zoomed = copy(zoom = newZoom)
        val s = zoomed.scale(size)
        val newCenter = Offset(
            anchor.x - (centroid.x - size.width / 2) / s - pan.x / s,
            anchor.y - (centroid.y - size.height / 2) / s - pan.y / s,
        )
        val b = MapProjection.bounds
        return zoomed.copy(center = Offset(newCenter.x.coerceIn(b.left, b.right), newCenter.y.coerceIn(b.top, b.bottom)))
    }

    companion object {
        const val MIN_ZOOM = 0.8f
        const val MAX_ZOOM = 16f
        val Overview = MapCamera(MapProjection.bounds.center, 1f)

        /**
         * Camera that fits [points] plus [marginKm] around them inside [visible], the part of the
         * [viewport] not covered by other UI, centred in that area.
         */
        fun framing(points: List<GeoPoint>, viewport: Size, visible: Rect, marginKm: Float = 40f): MapCamera {
            if (points.isEmpty() || visible.isEmpty) return Overview
            val world = points.map(MapProjection::toWorld)
            val margin = MapProjection.kmToWorld(marginKm)
            val minX = world.minOf { it.x } - margin
            val maxX = world.maxOf { it.x } + margin
            val minY = world.minOf { it.y } - margin
            val maxY = world.maxOf { it.y } + margin
            val base = Overview.scale(viewport)
            val zoom = min(visible.width / ((maxX - minX) * base), visible.height / ((maxY - minY) * base)).coerceIn(1f, 10f)
            val s = base * zoom
            val focus = Offset((minX + maxX) / 2, (minY + maxY) / 2)
            return MapCamera(
                Offset(
                    focus.x - (visible.center.x - viewport.width / 2) / s,
                    focus.y - (visible.center.y - viewport.height / 2) / s,
                ),
                zoom,
            )
        }
    }
}

private class OblastPath(val uid: Int, val path: Path)

private fun buildOblastPaths(): List<OblastPath> = UkraineGeometry.oblasts.map { shape ->
    val path = Path()
    val coords = shape.lonLat
    for (i in 0 until coords.size / 2) {
        val p = MapProjection.toWorld(GeoPoint(coords[i * 2 + 1].toDouble(), coords[i * 2].toDouble()))
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    OblastPath(shape.uid, path)
}

/**
 * Pinch-to-zoom factors from a platform gesture source that Compose doesn't surface as pointer
 * events — on desktop, the macOS trackpad magnify gesture. Each value multiplies the zoom.
 */
val LocalPinchZoom = staticCompositionLocalOf<Flow<Float>?> { null }

/**
 * Map scroll speed per wheel "line" (or equivalent trackpad travel). Matches Compose's own macOS
 * scroll config so the map pans at the same speed as lists.
 */
private val ScrollStep = 10.dp

sealed interface MapSelection {
    data class Threat(val marker: ThreatMarker) : MapSelection
    data class Favorite(val favorite: FavoriteLocation) : MapSelection
    data class Track(val track: DroneTrack, val position: InterpolatedPosition) : MapSelection
}

@Composable
fun UkraineMap(
    camera: MapCamera,
    threats: List<ThreatMarker>,
    alertedOblasts: Map<Int, Boolean>,
    favorites: List<FavoriteLocation>,
    alertedFavoriteIds: Set<String>,
    proximityRadiusKm: Int,
    modifier: Modifier = Modifier,
    highlightedThreatId: String? = null,
    /** Track shown in detail: bold path with a timestamp at each reported position; others are dimmed. */
    selectedTrackId: String? = null,
    /** Places a drone can be predicted to reach (cities and favorites). Empty disables predictions. */
    predictionPlaces: List<Place> = emptyList(),
    tracks: List<DroneTrack> = emptyList(),
    clockOffsetMs: Long = 0,
    /** Spot being offered as a new favorite (after a long-press): marked with a pulsing pin. */
    pendingFavorite: GeoPoint? = null,
    onCameraChange: ((MapCamera) -> Unit)? = null,
    onSelect: (MapSelection?) -> Unit = {},
    onLongPress: (GeoPoint) -> Unit = {},
) {
    val paths = remember { buildOblastPaths() }
    val colors = LocalRadarColors.current
    val textMeasurer = rememberTextMeasurer()
    // City names are measured once, on first show, and recoloured per theme when drawn.
    val cityLabels = remember(textMeasurer) { mutableMapOf<MapCity, TextLayoutResult>() }
    val currentCamera by rememberUpdatedState(camera)
    val currentThreats by rememberUpdatedState(threats)
    val currentFavorites by rememberUpdatedState(favorites)
    val currentTracks by rememberUpdatedState(tracks)
    val currentOffset by rememberUpdatedState(clockOffsetMs)
    // Alerts that belong to a track are drawn as that track's moving drone instead of a static marker.
    val trackedMarkerIds = remember(tracks) { tracks.flatMap { it.markerIds }.toSet() }
    // Each target is drawn with its type's icon; classification only changes when the data does.
    val trackKinds = remember(tracks) { tracks.associate { it.id to TargetClassifier.classify(it).kind } }
    val markerKinds = remember(threats) { threats.associate { it.id to TargetClassifier.classify(it).kind } }
    val trackSmoother = remember { TrackSmoother() }
    val cityFader = remember { CityLabelFader() }
    // IDE previews render only the first frames, so anything that animates in must start finished there.
    val inspectionMode = LocalInspectionMode.current
    val cameraCallback by rememberUpdatedState(onCameraChange)
    val selectCallback by rememberUpdatedState(onSelect)
    val longPressCallback by rememberUpdatedState(onLongPress)

    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)))
    val blink by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse))

    // Last pointer position and map size, so pinch gestures without coordinates zoom around the cursor.
    var hover by remember { mutableStateOf<Offset?>(null) }
    var mapSize by remember { mutableStateOf(Size.Zero) }
    val pinch = LocalPinchZoom.current
    if (onCameraChange != null && pinch != null) {
        LaunchedEffect(pinch) {
            pinch.collect { factor ->
                if (mapSize != Size.Zero) {
                    val anchor = hover ?: mapSize.center
                    cameraCallback?.invoke(currentCamera.transformed(anchor, Offset.Zero, factor, mapSize, MapCamera.MAX_ZOOM))
                }
            }
        }
    }

    val scope = rememberCoroutineScope()
    val smoother = remember(scope) {
        ScrollSmoother(scope) { pan, zoom, anchor ->
            if (mapSize != Size.Zero) {
                cameraCallback?.invoke(currentCamera.transformed(anchor, pan, zoom, mapSize, MapCamera.MAX_ZOOM))
            }
        }
    }

    val gestures = if (onCameraChange != null) {
        Modifier
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    smoother.stop()
                    cameraCallback?.invoke(currentCamera.transformed(centroid, pan, zoom, size.toSize(), MapCamera.MAX_ZOOM))
                }
            }
            .pointerInput(Unit) {
                // Two-finger trackpad swipes and mouse wheels arrive as scroll events: pan the map.
                // Holding Ctrl/Cmd turns the wheel into zoom for mouse users.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Enter -> hover = change.position
                            PointerEventType.Exit -> hover = null
                            PointerEventType.Scroll -> {
                                val delta = change.scrollDelta
                                val mods = event.keyboardModifiers
                                if (mods.isCtrlPressed || mods.isMetaPressed) {
                                    smoother.zoom(exp(-delta.y * 0.12f), change.position)
                                } else {
                                    // Shift+wheel scrolls horizontally on mice without a horizontal wheel.
                                    val pan = if (mods.isShiftPressed && delta.x == 0f) Offset(-delta.y, 0f) else -delta
                                    smoother.pan(pan * ScrollStep.toPx())
                                }
                                change.consume()
                            }
                            else -> Unit
                        }
                    }
                }
            }
    } else Modifier

    Canvas(
        modifier
            .onSizeChanged { mapSize = it.toSize() }
            .then(gestures)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { cameraCallback?.invoke(MapCamera.Overview) },
                    onLongPress = { offset -> longPressCallback(MapProjection.toGeo(currentCamera.toWorld(offset, size.toSize()))) },
                    onTap = { offset ->
                        val positions = trackPositions(currentTracks, nowMs(currentOffset))
                        selectCallback(hitTest(offset, size, currentCamera, currentThreats, currentFavorites, positions))
                    },
                )
            }
    ) {
        drawRect(colors.mapBackground)
        val scale = camera.scale(size)

        withTransform({
            translate(size.width / 2, size.height / 2)
            scale(scale, scale, pivot = Offset.Zero)
            translate(-camera.center.x, -camera.center.y)
        }) {
            drawGrid(scale, colors)
            paths.forEach { oblast ->
                val fill = when (alertedOblasts[oblast.uid]) {
                    true -> colors.oblastAlertFull
                    false -> colors.oblastAlertPartial
                    null -> colors.oblastIdle
                }
                drawPath(oblast.path, fill)
                drawPath(oblast.path, colors.oblastBorder, style = Stroke(width = 1.1f / scale))
            }
            listOf(Regions.KYIV_CITY_UID, Regions.SEVASTOPOL_UID).forEach { uid ->
                val center = MapProjection.toWorld(Regions.center(uid)!!)
                drawCircle(colors.oblastIdle, radius = 0.09f, center = center)
                if (alertedOblasts.containsKey(uid)) drawCircle(colors.oblastAlertFull, radius = 0.09f, center = center)
                drawCircle(colors.oblastBorder, radius = 0.09f, center = center, style = Stroke(1.1f / scale))
            }
        }

        // Favorites draw their own name, so city names keep clear of the star and its label.
        val favoriteAreas = (favorites.map { it.point } + listOfNotNull(pendingFavorite)).map { point ->
            val p = camera.toScreen(MapProjection.toWorld(point), size)
            val glyph = 10.dp.toPx()
            Rect(p.x - glyph - 40.dp.toPx(), p.y - glyph, p.x + glyph + 40.dp.toPx(), p.y + 26.dp.toPx())
        }
        drawCities(camera, colors, favoriteAreas, cityFader, nowMs(clockOffsetMs), instantLabels = inspectionMode) { city ->
            cityLabels.getOrPut(city) { textMeasurer.measure(city.name, if (city.population >= MAJOR_CITY) MajorCityLabel else CityLabel) }
        }
        // Reading the animation clock keeps frames coming until every fade has finished.
        if (cityFader.isAnimating) pulse

        val radiusPx = MapProjection.kmToWorld(proximityRadiusKm.toFloat()) * scale
        favorites.forEach { favorite ->
            val p = camera.toScreen(MapProjection.toWorld(favorite.point), size)
            val alerted = favorite.id in alertedFavoriteIds
            val ringColor = if (alerted) colors.danger.copy(alpha = 0.18f + 0.25f * blink) else colors.favorite.copy(alpha = 0.16f)
            drawCircle(ringColor, radiusPx, p)
            drawCircle(
                if (alerted) colors.danger else colors.favorite.copy(alpha = 0.6f),
                radiusPx, p,
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )
            drawFavoriteGlyph(p, 9.dp.toPx(), if (alerted) colors.danger else colors.favorite, colors.glyphOutline)
            val label = textMeasurer.measure(favorite.name, TextStyle(color = colors.mapLabel, fontSize = 11.sp))
            drawText(label, topLeft = Offset(p.x - label.size.width / 2, p.y + 11.dp.toPx()))
        }

        pendingFavorite?.let { point ->
            val p = camera.toScreen(MapProjection.toWorld(point), size)
            val ring = 11.dp.toPx()
            drawCircle(colors.favorite.copy(alpha = (1f - pulse) * 0.6f), ring + pulse * ring * 1.8f, p, style = Stroke(2.dp.toPx()))
            drawCircle(colors.favorite.copy(alpha = 0.18f), ring, p)
            drawFavoriteGlyph(p, 10.dp.toPx(), colors.favorite, colors.glyphOutline)
        }

        val now = nowMs(clockOffsetMs)
        // Re-evaluated every frame (the pulse animation keeps the canvas drawing), so drones keep moving.
        val visibleTracks = trackSmoother.smooth(trackPositions(tracks, now), now)
        if (predictionPlaces.isNotEmpty()) {
            visibleTracks.forEach { (track, position) ->
                // The selected track shows its whole predicted route instead.
                if (track.id == selectedTrackId) {
                    drawPredictedRoute(track, position, now, predictionPlaces, camera, colors, textMeasurer)
                    return@forEach
                }
                val prediction = TrackPredictor.predict(track, now, predictionPlaces) ?: return@forEach
                val dimmed = selectedTrackId != null && track.id != selectedTrackId
                // At country scale labels pile up around big cities; show them once zoomed in or when selected.
                val labelled = !dimmed && (camera.zoom >= 2.5f || track.id == selectedTrackId)
                drawPrediction(position, prediction, camera, colors, textMeasurer, dimmed, labelled)
            }
        }
        // Selected track last, so its path and labels sit on top.
        visibleTracks.sortedBy { it.first.id == selectedTrackId }.forEach { (track, position) ->
            val highlighted = track.id == selectedTrackId || highlightedThreatId?.let { it in track.markerIds } == true
            val dimmed = selectedTrackId != null && track.id != selectedTrackId
            val kind = trackKinds[track.id] ?: TargetKind.AttackDrone
            drawTrack(track, position, camera, highlighted, dimmed, pulse, colors, kind)
            if (track.id == selectedTrackId) drawTrackTimestamps(track, camera, colors, textMeasurer)
        }

        // Threats reported for the same place (an oblast centre, Kyiv city) fan out so their icons don't stack.
        val spread = 16.dp.toPx()
        threats.filter { it.id !in trackedMarkerIds }.groupBy { it.point }.values.forEach { group ->
            group.forEachIndexed { index, marker ->
                val angle = 2 * PI * index / group.size - PI / 2
                val offset = if (group.size == 1) Offset.Zero else Offset((spread * cos(angle)).toFloat(), (spread * sin(angle)).toFloat())
                val p = camera.toScreen(MapProjection.toWorld(marker.point), size) + offset
                if (p.x < -40 || p.y < -40 || p.x > size.width + 40 || p.y > size.height + 40) return@forEachIndexed
                val color = if (marker.level == ThreatLevel.Red) colors.danger else colors.warning
                val base = (if (marker.isRegionWide) 13.dp else 8.dp).toPx()
                val highlighted = marker.id == highlightedThreatId
                val phase = (pulse + (abs(marker.id.hashCode()) % 100) / 100f) % 1f
                drawCircle(color.copy(alpha = (1f - phase) * 0.55f), base + phase * base * 2.4f, p, style = Stroke(1.5.dp.toPx()))
                if (marker.isRegionWide) {
                    drawCircle(color.copy(alpha = 0.6f), base * 1.6f, p, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
                }
                if (highlighted) {
                    drawCircle(colors.mapLabel, base * 2.2f, p, style = Stroke(2.dp.toPx()))
                }
                val glyphSize = base * (if (highlighted) 1.4f else 1f)
                drawTargetGlyph(markerKinds[marker.id] ?: TargetKind.Unknown, p, glyphSize * 1.15f, headingFor(marker), color, colors.glyphOutline)
            }
        }
    }
}

private fun Density.hitTest(
    offset: Offset,
    size: IntSize,
    camera: MapCamera,
    threats: List<ThreatMarker>,
    favorites: List<FavoriteLocation>,
    tracks: List<Pair<DroneTrack, InterpolatedPosition>>,
): MapSelection? {
    val s = size.toSize()
    val threshold = 28.dp.toPx()
    val threat = threats
        .map { it to (camera.toScreen(MapProjection.toWorld(it.point), s) - offset).getDistance() }
        .filter { it.second < threshold }
        .minByOrNull { it.second }
    val favorite = favorites
        .map { it to (camera.toScreen(MapProjection.toWorld(it.point), s) - offset).getDistance() }
        .filter { it.second < threshold }
        .minByOrNull { it.second }
    val track = tracks
        .map { it to (camera.toScreen(MapProjection.toWorld(it.second.point), s) - offset).getDistance() }
        .filter { it.second < threshold }
        .minByOrNull { it.second }
    if (track != null && (threat == null || track.second <= threat.second) && (favorite == null || track.second <= favorite.second)) {
        return MapSelection.Track(track.first.first, track.first.second)
    }
    return when {
        threat != null && (favorite == null || threat.second <= favorite.second) -> MapSelection.Threat(threat.first)
        favorite != null -> MapSelection.Favorite(favorite.first)
        else -> null
    }
}

private fun nowMs(clockOffsetMs: Long) = Clock.System.now().toEpochMilliseconds() + clockOffsetMs

/** Interpolated positions of tracks worth drawing: active ones and those that ended moments ago. */
private fun trackPositions(tracks: List<DroneTrack>, now: Long): List<Pair<DroneTrack, InterpolatedPosition>> = tracks
    .filter { TrackInterpolator.isVisible(it, now) }
    .mapNotNull { track -> TrackInterpolator.positionAt(track, now)?.let { track to it } }

/** Fading trail through the recorded samples up to the drone's current interpolated position. */
private fun DrawScope.drawTrack(
    track: DroneTrack,
    position: InterpolatedPosition,
    camera: MapCamera,
    highlighted: Boolean,
    dimmed: Boolean,
    pulse: Float,
    colors: RadarColors,
    kind: TargetKind,
) {
    val color = if (track.level == ThreatLevel.Red) colors.danger else colors.warning
    val alpha = (if (track.active) 1f else 0.45f) * (if (dimmed) 0.35f else 1f)
    val head = camera.toScreen(MapProjection.toWorld(position.point), size)
    val points = track.samples.map { camera.toScreen(MapProjection.toWorld(it.point), size) }
    if (points.size >= 2 || position.mode != PositionMode.Held) {
        val trail = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            lineTo(head.x, head.y)
        }
        if (highlighted) {
            drawPath(trail, color.copy(alpha = 0.25f), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(trail, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawPath(trail, color.copy(alpha = 0.55f * alpha), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
        }
    }
    points.forEach {
        drawCircle(color.copy(alpha = 0.7f * alpha), (if (highlighted) 4.5.dp else 2.5.dp).toPx(), it)
        if (highlighted) drawCircle(colors.mapLabel, 4.5.dp.toPx(), it, style = Stroke(1.5.dp.toPx()))
    }

    val base = 9.dp.toPx()
    drawCircle(color.copy(alpha = (1f - pulse) * 0.5f * alpha), base + pulse * base * 2.4f, head, style = Stroke(1.5.dp.toPx()))
    if (position.mode == PositionMode.Extrapolated) {
        // Dead-reckoned past the last report: show an uncertainty ring.
        drawCircle(color.copy(alpha = 0.35f), base * 2f, head, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))))
    }
    if (highlighted) drawCircle(colors.mapLabel, base * 2.2f, head, style = Stroke(2.dp.toPx()))
    drawTargetGlyph(
        kind,
        head,
        base * (if (highlighted) 1.6f else 1.2f),
        (position.headingDeg ?: 270.0).toFloat(),
        color.copy(alpha = alpha),
        colors.glyphOutline.copy(alpha = colors.glyphOutline.alpha * alpha),
    )
}

/**
 * Selected target's predicted course: a dashed line ahead along its heading, with each settlement on
 * the way marked and labelled with the estimated minutes to reach it.
 */
private fun DrawScope.drawPredictedRoute(
    track: DroneTrack,
    position: InterpolatedPosition,
    now: Long,
    places: List<Place>,
    camera: MapCamera,
    colors: RadarColors,
    textMeasurer: TextMeasurer,
) {
    val route = TrackPredictor.route(track, now, places)
    val horizon = (route.lastOrNull()?.distanceKm?.plus(25.0) ?: 60.0).coerceAtMost(TrackPredictor.ROUTE_HORIZON_KM)
    val end = TrackPredictor.courseEnd(track, now, horizon) ?: return
    val from = camera.toScreen(MapProjection.toWorld(position.point), size)
    val to = camera.toScreen(MapProjection.toWorld(end), size)
    drawLine(
        colors.warning.copy(alpha = 0.9f), from, to, 2.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())), cap = StrokeCap.Round,
    )
    // Arrow head at the end of the course.
    val angle = kotlin.math.atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
    val head = 9.dp.toPx()
    listOf(2.6, -2.6).forEach { da ->
        drawLine(
            colors.warning, to,
            Offset(to.x + (head * kotlin.math.cos(angle + da)).toFloat(), to.y + (head * kotlin.math.sin(angle + da)).toFloat()),
            2.5.dp.toPx(), cap = StrokeCap.Round,
        )
    }
    var lastLabelled: GeoPoint? = null
    route.forEachIndexed { index, stop ->
        val p = camera.toScreen(MapProjection.toWorld(stop.place.point), size)
        drawCircle(colors.warning, 7.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        drawCircle(colors.warning, 2.5.dp.toPx(), p)
        // Stops a few km apart (a favorite inside a city) would print over each other; the panel lists them all.
        if (lastLabelled?.let { GeoMath.distanceKm(it, stop.place.point) < 6.0 } == true) return@forEachIndexed
        lastLabelled = stop.place.point
        val label = textMeasurer.measure(
            "${index + 1}. ${stop.place.name} · ~${stop.etaMinutes} min",
            TextStyle(color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold),
        )
        // Alternate above/below the course line so neighbouring labels don't collide.
        val dy = if (index % 2 == 0) -label.size.height - 6.dp.toPx() else 6.dp.toPx()
        val topLeft = Offset(p.x + 6.dp.toPx(), p.y + dy)
        drawRoundRect(
            colors.warning,
            topLeft = topLeft - Offset(5.dp.toPx(), 2.dp.toPx()),
            size = Size(label.size.width + 10.dp.toPx(), label.size.height + 4.dp.toPx()),
            cornerRadius = CornerRadius(6.dp.toPx()),
        )
        drawText(label, topLeft = topLeft)
    }
}

/** Time label next to every reported position of the selected track. */
private fun DrawScope.drawTrackTimestamps(track: DroneTrack, camera: MapCamera, colors: RadarColors, textMeasurer: TextMeasurer) {
    val style = TextStyle(color = colors.onOverlay, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    track.samples.forEach { sample ->
        val p = camera.toScreen(MapProjection.toWorld(sample.point), size)
        val label = textMeasurer.measure(formatTime(Instant.fromEpochMilliseconds(sample.t)), style)
        val topLeft = Offset(p.x + 8.dp.toPx(), p.y - label.size.height - 4.dp.toPx())
        drawRoundRect(
            colors.overlay,
            topLeft = topLeft - Offset(4.dp.toPx(), 1.dp.toPx()),
            size = Size(label.size.width + 8.dp.toPx(), label.size.height + 2.dp.toPx()),
            cornerRadius = CornerRadius(4.dp.toPx()),
        )
        drawText(label, topLeft = topLeft)
    }
}

/** Dashed projection from the drone to the place it is heading for, with the estimated time to reach it. */
private fun DrawScope.drawPrediction(
    position: InterpolatedPosition,
    prediction: ArrivalPrediction,
    camera: MapCamera,
    colors: RadarColors,
    textMeasurer: TextMeasurer,
    dimmed: Boolean,
    labelled: Boolean,
) {
    val alpha = if (dimmed) 0.3f else 1f
    val from = camera.toScreen(MapProjection.toWorld(position.point), size)
    val to = camera.toScreen(MapProjection.toWorld(prediction.place.point), size)
    val color = colors.warning.copy(alpha = alpha)
    drawLine(color, from, to, 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())), cap = StrokeCap.Round)
    drawCircle(color, 7.dp.toPx(), to, style = Stroke(2.dp.toPx()))
    drawCircle(color, 2.5.dp.toPx(), to)
    if (!labelled) return
    val text = "${prediction.place.name} · ~${prediction.etaMinutes} min"
    val label = textMeasurer.measure(text, TextStyle(color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold))
    val topLeft = Offset(to.x - label.size.width / 2, to.y + 10.dp.toPx())
    drawRoundRect(
        colors.warning,
        topLeft = topLeft - Offset(5.dp.toPx(), 2.dp.toPx()),
        size = Size(label.size.width + 10.dp.toPx(), label.size.height + 4.dp.toPx()),
        cornerRadius = CornerRadius(6.dp.toPx()),
    )
    drawText(label, topLeft = topLeft)
}

/** Zoom from which city names are shown; below it cities are dots only. */
private const val CITY_LABEL_ZOOM = 2.5f
private const val MAJOR_CITY = 200_000
private val CityLabel = TextStyle(fontSize = 10.sp)
private val MajorCityLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)

/**
 * Every settlement with city status as a dot, sized by population. Once zoomed in, names are added
 * largest city first, skipping any that would overlap a name already placed or a [reserved] area.
 * Names fade (and rise slightly) in when revealed and fade out when hidden, via [fader].
 */
private fun DrawScope.drawCities(
    camera: MapCamera,
    colors: RadarColors,
    reserved: List<Rect>,
    fader: CityLabelFader,
    nowMs: Long,
    instantLabels: Boolean,
    label: (MapCity) -> TextLayoutResult,
) {
    val showLabels = camera.zoom >= CITY_LABEL_ZOOM
    val placed = reserved.toMutableList()
    val gap = 3.dp.toPx()
    val wanted = HashSet<MapCity>()
    UkraineCities.all.forEach { city ->
        val p = cityOnScreen(city, camera)
        if (p.x < -120 || p.y < -20 || p.x > size.width + 20 || p.y > size.height + 20) return@forEach
        val r = cityDotRadius(city)
        drawCircle(colors.cityDot, r, p)
        if (!showLabels) return@forEach
        val text = label(city)
        val topLeft = Offset(p.x + r + gap, p.y - text.size.height / 2f)
        val box = Rect(topLeft, Size(text.size.width.toFloat(), text.size.height.toFloat())).inflate(gap)
        if (placed.any { it.overlaps(box) }) return@forEach
        placed += box
        wanted += city
    }
    val rise = 4.dp.toPx()
    fader.update(wanted, nowMs, instantLabels).forEach { (city, opacity) ->
        val p = cityOnScreen(city, camera)
        val text = label(city)
        // Ease out, so names settle gently; they rise into place and sink as they go.
        val eased = 1f - (1f - opacity) * (1f - opacity)
        val topLeft = Offset(p.x + cityDotRadius(city) + gap, p.y - text.size.height / 2f + (1f - eased) * rise)
        drawText(text, color = colors.cityLabel, topLeft = topLeft, alpha = eased)
    }
}

private fun DrawScope.cityOnScreen(city: MapCity, camera: MapCamera) =
    camera.toScreen(MapProjection.toWorld(GeoPoint(city.lat.toDouble(), city.lon.toDouble())), size)

private fun DrawScope.cityDotRadius(city: MapCity) = when {
    city.population >= MAJOR_CITY -> 3.dp
    city.population >= 50_000 -> 2.4.dp
    else -> 1.8.dp
}.toPx()

/** Shahed-type drones are usually launched from the east and south, so headings point roughly west/north-west. */
private fun headingFor(marker: ThreatMarker): Float = 235f + (abs(marker.id.hashCode()) % 90)

private fun DrawScope.drawGrid(scale: Float, colors: RadarColors) {
    val b = MapProjection.bounds
    val stroke = 0.8f / scale
    for (lat in 44..53) {
        val y = -lat.toFloat()
        drawLine(colors.grid, Offset(b.left - 2, y), Offset(b.right + 2, y), stroke)
    }
    for (lon in 21..41) {
        val x = MapProjection.toWorld(GeoPoint(0.0, lon.toDouble())).x
        drawLine(colors.grid, Offset(x, b.top - 2), Offset(x, b.bottom + 2), stroke)
    }
}


private fun DrawScope.drawFavoriteGlyph(center: Offset, outer: Float, color: Color, outline: Color) {
    val inner = outer * 0.45f
    val path = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outer else inner
        val a = -PI / 2 + i * PI / 5
        val x = center.x + (r * cos(a)).toFloat()
        val y = center.y + (r * sin(a)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
    drawPath(path, outline, style = Stroke(1.2f))
}
