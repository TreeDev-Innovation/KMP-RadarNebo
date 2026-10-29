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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.gusak.lab.radarnebo.data.MapBackground
import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.ui.RadarTheme
import com.gusak.lab.radarnebo.ui.preview.PreviewData
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
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
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
import kotlin.math.log2
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
         * [viewport] not covered by other UI. It is centred on [focus] when given (the points then fit
         * around it), otherwise on the middle of the points.
         */
        fun framing(points: List<GeoPoint>, viewport: Size, visible: Rect, marginKm: Float = 40f, focus: GeoPoint? = null): MapCamera {
            if (points.isEmpty() || visible.isEmpty) return Overview
            val world = points.map(MapProjection::toWorld)
            val margin = MapProjection.kmToWorld(marginKm)
            val minX = world.minOf { it.x } - margin
            val maxX = world.maxOf { it.x } + margin
            val minY = world.minOf { it.y } - margin
            val maxY = world.maxOf { it.y } + margin
            val center = focus?.let(MapProjection::toWorld) ?: Offset((minX + maxX) / 2, (minY + maxY) / 2)
            // Half the extent on each side of the centre, so every point stays in view around it.
            val halfWidth = maxOf(center.x - minX, maxX - center.x)
            val halfHeight = maxOf(center.y - minY, maxY - center.y)
            val base = Overview.scale(viewport)
            val zoom = min(visible.width / (2 * halfWidth * base), visible.height / (2 * halfHeight * base)).coerceIn(1f, 10f)
            return centeredOn(center, zoom, viewport, visible)
        }

        /** Camera at [zoom] that puts the world point [focus] in the middle of [visible], a part of [viewport]. */
        fun centeredOn(focus: Offset, zoom: Float, viewport: Size, visible: Rect): MapCamera {
            val s = Overview.scale(viewport) * zoom
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

/** The camera as of the latest change, ahead of the `camera` parameter until the next recomposition. */
private class LiveCamera(var value: MapCamera) {
    private var given = value

    /** Adopts a camera the caller passed in, e.g. its own animation, or our last change coming back. */
    fun sync(camera: MapCamera) {
        if (camera != given) {
            given = camera
            value = camera
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

/** Every oblast in one path: the whole country. */
private fun buildCountryPath(paths: List<OblastPath>) = Path().apply { paths.forEach { addPath(it.path) } }

/**
 * Everything outside Ukraine within [area] (world coordinates): the area with the country cut out.
 * The oblasts meet without gaps or overlaps, so even-odd filling leaves exactly the country unfilled.
 */
private fun outsideUkraine(country: Path, area: Rect) = Path().apply {
    fillType = PathFillType.EvenOdd
    addRect(area)
    addPath(country)
}

/** How much of the satellite imagery outside Ukraine stays visible; the rest is darkened away. */
private const val OUTSIDE_IMAGERY_ALPHA = 0.2f

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
    /** Real map drawn under the oblasts, from [LocalMapTiles]; null draws the plain background. */
    tileSource: TileSource? = null,
    onCameraChange: ((MapCamera) -> Unit)? = null,
    onSelect: (MapSelection?) -> Unit = {},
    onLongPress: (GeoPoint) -> Unit = {},
) {
    val paths = remember { buildOblastPaths() }
    val country = remember(paths) { buildCountryPath(paths) }
    val mapTiles = LocalMapTiles.current?.takeIf { tileSource != null }
    val colors = LocalRadarColors.current
    val textMeasurer = rememberTextMeasurer()
    // City names are measured once, on first show, and recoloured per theme when drawn.
    val cityLabels = remember(textMeasurer) { mutableMapOf<MapCity, TextLayoutResult>() }
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
    // Gesture steps arrive faster than recompositions (a trackpad pinch sends several per frame), so each
    // builds on the camera the previous step produced; [camera] only catches up at the next recomposition.
    val liveCamera = remember { LiveCamera(camera) }
    liveCamera.sync(camera)
    val moveCamera = { next: MapCamera ->
        liveCamera.value = next
        cameraCallback?.invoke(next)
    }
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
                    moveCamera(liveCamera.value.transformed(anchor, Offset.Zero, factor, mapSize, MapCamera.MAX_ZOOM))
                }
            }
        }
    }

    val scope = rememberCoroutineScope()
    val smoother = remember(scope) {
        ScrollSmoother(scope) { pan, zoom, anchor ->
            if (mapSize != Size.Zero) {
                moveCamera(liveCamera.value.transformed(anchor, pan, zoom, mapSize, MapCamera.MAX_ZOOM))
            }
        }
    }

    val gestures = if (onCameraChange != null) {
        Modifier
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    smoother.stop()
                    moveCamera(liveCamera.value.transformed(centroid, pan, zoom, size.toSize(), MapCamera.MAX_ZOOM))
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
                    onDoubleTap = { moveCamera(MapCamera.Overview) },
                    onLongPress = { offset -> longPressCallback(MapProjection.toGeo(liveCamera.value.toWorld(offset, size.toSize()))) },
                    onTap = { offset ->
                        val positions = trackPositions(currentTracks, nowMs(currentOffset))
                        selectCallback(hitTest(offset, size, liveCamera.value, currentThreats, currentFavorites, positions))
                    },
                )
            }
    ) {
        drawRect(colors.mapBackground)
        val ink = MapInk.over(tileSource, colors)
        val scale = camera.scale(size)
        if (mapTiles != null && tileSource != null) {
            // Reading it makes the canvas redraw as tiles arrive.
            mapTiles.loaded
            drawMapTiles(mapTiles, tileSource, camera)
        }
        // Over a real map the oblasts are tinted rather than painted, so the map shows through.
        val idleFill = when {
            tileSource == null -> colors.oblastIdle
            tileSource.isImagery -> colors.oblastIdle.copy(alpha = 0.12f)
            else -> colors.oblastIdle.copy(alpha = 0.3f)
        }

        withTransform({
            translate(size.width / 2, size.height / 2)
            scale(scale, scale, pivot = Offset.Zero)
            translate(-camera.center.x, -camera.center.y)
        }) {
            if (mapTiles == null) drawGrid(scale, colors)
            // Over satellite imagery the country stands out: the land around it is darkened.
            if (mapTiles != null && tileSource?.isImagery == true) {
                val area = Rect(camera.toWorld(Offset.Zero, size), camera.toWorld(Offset(size.width, size.height), size))
                drawPath(outsideUkraine(country, area), Color.Black.copy(alpha = 1f - OUTSIDE_IMAGERY_ALPHA))
            }
            paths.forEach { oblast ->
                val fill = when (alertedOblasts[oblast.uid]) {
                    true -> colors.oblastAlertFull
                    false -> colors.oblastAlertPartial
                    null -> idleFill
                }
                drawPath(oblast.path, fill)
                drawPath(oblast.path, colors.oblastBorder, style = Stroke(width = 1.1f / scale))
            }
            listOf(Regions.KYIV_CITY_UID, Regions.SEVASTOPOL_UID).forEach { uid ->
                val center = MapProjection.toWorld(Regions.center(uid)!!)
                drawCircle(idleFill, radius = 0.09f, center = center)
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
        drawCities(camera, ink, favoriteAreas, cityFader, nowMs(clockOffsetMs), instantLabels = inspectionMode) { city ->
            cityLabels.getOrPut(city) {
                val style = when {
                    city in UkraineCities.oblastCentres -> OblastCentreLabel
                    city.population >= MAJOR_CITY -> MajorCityLabel
                    else -> CityLabel
                }
                textMeasurer.measure(city.name, style)
            }
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
            val label = textMeasurer.measure(favorite.name, FavoriteLabel)
            drawHaloText(label, ink.label, ink.halo, Offset(p.x - label.size.width / 2, p.y + 11.dp.toPx()))
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
        // Trajectories (where a target came from and where it is heading) only for the target in focus: the
        // selected one, or the one the cinematic camera is on. With dozens of targets they would bury the map.
        val focused = { track: DroneTrack -> track.id == selectedTrackId || highlightedThreatId?.let { it in track.markerIds } == true }
        if (predictionPlaces.isNotEmpty()) {
            visibleTracks.filter { focused(it.first) }.forEach { (track, position) ->
                if (track.id == selectedTrackId) {
                    drawPredictedRoute(track, position, now, predictionPlaces, camera, colors, textMeasurer)
                } else {
                    TrackPredictor.predict(track, now, predictionPlaces)?.let { drawPrediction(position, it, camera, colors, textMeasurer) }
                }
            }
        }
        // The selected target's course over the ground, under everything in the air.
        visibleTracks.firstOrNull { it.first.id == selectedTrackId }?.let { (track, position) ->
            drawTrail(track, position, camera)
            drawTrackTimestamps(track, camera, colors, textMeasurer)
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

        // Air layer: targets fly over everything on the ground, the higher ones over the lower ones.
        visibleTracks
            .map { Triple(it.first, it.second, trackKinds[it.first.id] ?: TargetKind.AttackDrone) }
            .sortedWith(compareBy({ it.third.typicalAltitudeM ?: 0 }, { it.first.id == selectedTrackId }))
            .forEach { (track, position, kind) ->
                val dimmed = selectedTrackId != null && track.id != selectedTrackId
                drawTarget(track, position, camera, focused(track), dimmed, pulse, colors, kind)
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
    // Targets are tapped where they are drawn, in the air.
    val track = tracks
        .map {
            val ground = camera.toScreen(MapProjection.toWorld(it.second.point), s)
            it to (elevate(ground, TargetClassifier.classify(it.first).kind.typicalAltitudeM, s).air - offset).getDistance()
        }
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

/** Interpolated positions of [tracks], already narrowed to active ones and those within the targets timeline. */
private fun trackPositions(tracks: List<DroneTrack>, now: Long): List<Pair<DroneTrack, InterpolatedPosition>> = tracks
    .mapNotNull { track -> TrackInterpolator.positionAt(track, now)?.let { track to it } }

/**
 * The selected target's trail over the ground through the reported positions up to where it is now: a dashed
 * white line that fades out towards the oldest report, so the recent course reads first.
 */
private fun DrawScope.drawTrail(track: DroneTrack, position: InterpolatedPosition, camera: MapCamera) {
    val points = track.samples.map { camera.toScreen(MapProjection.toWorld(it.point), size) }
    val trail = points + camera.toScreen(MapProjection.toWorld(position.point), size)
    val fade = trailFade(trail)
    if (trail.size >= 2) drawFadingDashes(trail, fade)
    points.forEachIndexed { i, p ->
        drawCircle(Color.Black.copy(alpha = 0.35f * fade[i]), 3.5.dp.toPx(), p)
        drawCircle(Color.White.copy(alpha = fade[i]), 2.5.dp.toPx(), p)
    }
}

/** The target's icon, up in the air at its class's typical altitude, as the camera above the map sees it (see [elevate]). */
private fun DrawScope.drawTarget(
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
    val ground = camera.toScreen(MapProjection.toWorld(position.point), size)
    val elevated = elevate(ground, kind.typicalAltitudeM, size)
    val air = elevated.air
    val scaled = 9.dp.toPx() * elevated.scale
    drawCircle(color.copy(alpha = (1f - pulse) * 0.5f * alpha), scaled + pulse * scaled * 2.4f, air, style = Stroke(1.5.dp.toPx()))
    if (position.mode == PositionMode.Extrapolated) {
        // Dead-reckoned past the last report: show an uncertainty ring.
        drawCircle(color.copy(alpha = 0.35f), scaled * 2f, air, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))))
    }
    if (highlighted) drawCircle(colors.mapLabel, scaled * 2.2f, air, style = Stroke(2.dp.toPx()))
    drawTargetGlyph(
        kind,
        air,
        scaled * (if (highlighted) 1.6f else 1.2f),
        (position.headingDeg ?: 270.0).toFloat(),
        color.copy(alpha = alpha),
        colors.glyphOutline.copy(alpha = colors.glyphOutline.alpha * alpha),
    )
}

/** Where a target at some altitude appears: [ground] is its spot on the map, [air] where it is drawn, [scale] times larger. */
private class Elevated(val ground: Offset, val air: Offset, val scale: Float)

/**
 * The map's perspective camera: it looks straight down on the map from above the centre of the view. A target
 * [altitudeM] high is nearer to it than the ground, by a height that grows with the log of the altitude (a 50 m
 * cruise missile and a 10 km bomber both stay readable), so it shows up pushed out from the centre of the view
 * and a little larger. Panning changes that push, so targets glide over the map at their own pace: parallax
 * that shows how high they fly.
 */
private fun Density.elevate(ground: Offset, altitudeM: Int?, viewport: Size): Elevated {
    val magnification = magnification(altitudeM, viewport)
    val air = viewport.center + (ground - viewport.center) * magnification
    return Elevated(ground, air, 1f + (magnification - 1f) * SIZE_EXAGGERATION)
}

/** Where on the screen the ground under a target [altitudeM] high must be for the target to show up at [air]. */
fun Density.groundUnder(air: Offset, altitudeM: Int?, viewport: Size): Offset =
    viewport.center + (air - viewport.center) / magnification(altitudeM, viewport)

/** Pinhole projection: what is some height closer to the camera spreads out by cameraHeight / (cameraHeight - height). */
private fun Density.magnification(altitudeM: Int?, viewport: Size): Float {
    val altitude = altitudeM ?: 0
    if (altitude <= 0) return 1f
    val height = (HEIGHT_PER_DOUBLING.toPx() * log2(1f + altitude / HEIGHT_REFERENCE_M)).coerceAtMost(MAX_HEIGHT.toPx())
    val cameraHeight = maxOf(viewport.width, viewport.height) * CAMERA_DISTANCE
    return cameraHeight / (cameraHeight - height)
}

private val HEIGHT_PER_DOUBLING = 22.dp
private const val HEIGHT_REFERENCE_M = 250f
private val MAX_HEIGHT = 110.dp
private const val CAMERA_DISTANCE = 0.9f
/** Targets grow with height more than true perspective would make them, so altitude also reads from their size. */
private const val SIZE_EXAGGERATION = 2f

/** Opacity along a trail, by distance from its newest end: 1 there, [TRAIL_MIN_ALPHA] at the oldest point. */
private fun trailFade(points: List<Offset>): List<Float> {
    val fromHead = FloatArray(points.size)
    for (i in points.lastIndex - 1 downTo 0) fromHead[i] = fromHead[i + 1] + (points[i + 1] - points[i]).getDistance()
    val total = fromHead.firstOrNull()?.takeIf { it > 0f } ?: return List(points.size) { 1f }
    return fromHead.map { 1f - (1f - TRAIL_MIN_ALPHA) * it / total }
}

/**
 * White dashes along [points], each segment as opaque as [fade] says at its newer end. The dash phase carries on
 * from segment to segment, so the pattern runs unbroken through the reported positions.
 */
private fun DrawScope.drawFadingDashes(points: List<Offset>, fade: List<Float>) {
    val dash = 7.dp.toPx()
    val gap = 5.dp.toPx()
    var travelled = 0f
    for (i in 0 until points.lastIndex) {
        val a = points[i]
        val b = points[i + 1]
        val effect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), phase = travelled % (dash + gap))
        val alpha = (fade[i] + fade[i + 1]) / 2f
        // A dark underlay keeps the white line readable on the light map and over bright imagery.
        drawLine(Color.Black.copy(alpha = 0.3f * alpha), a, b, 4.dp.toPx(), pathEffect = effect, cap = StrokeCap.Butt)
        drawLine(Color.White.copy(alpha = alpha), a, b, 2.dp.toPx(), pathEffect = effect, cap = StrokeCap.Butt)
        travelled += (b - a).getDistance()
    }
}

/** How faint the oldest part of a trail gets. */
private const val TRAIL_MIN_ALPHA = 0.12f

/** A solid line from [from] to [to] ending in a filled arrowhead, for a predicted course. */
private fun DrawScope.drawCourseArrow(from: Offset, to: Offset, color: Color) {
    val length = (to - from).getDistance()
    if (length < 1f) return
    val direction = (to - from) / length
    val normal = Offset(-direction.y, direction.x)
    val headLength = minOf(14.dp.toPx(), length * 0.6f)
    val headHalfWidth = 7.dp.toPx()
    val base = to - direction * headLength
    val width = 3.dp.toPx()
    drawLine(Color.Black.copy(alpha = 0.3f), from, base, width + 2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, from, base, width, cap = StrokeCap.Round)
    val head = Path().apply {
        moveTo(to.x, to.y)
        lineTo(base.x + normal.x * headHalfWidth, base.y + normal.y * headHalfWidth)
        lineTo(base.x - normal.x * headHalfWidth, base.y - normal.y * headHalfWidth)
        close()
    }
    drawPath(head, color)
    drawPath(head, Color.Black.copy(alpha = 0.3f), style = Stroke(1.dp.toPx(), join = StrokeJoin.Round))
}

/**
 * Selected target's predicted course: a solid arrow ahead along its fitted heading, with each settlement on
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
    drawCourseArrow(from, to, colors.warning)
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
            TextStyle(color = colors.onWarning, fontSize = 11.sp, fontWeight = FontWeight.Bold),
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

/** Solid arrow from the drone to the place it is heading for, with the estimated time to reach it. */
private fun DrawScope.drawPrediction(
    position: InterpolatedPosition,
    prediction: ArrivalPrediction,
    camera: MapCamera,
    colors: RadarColors,
    textMeasurer: TextMeasurer,
) {
    val from = camera.toScreen(MapProjection.toWorld(position.point), size)
    val to = camera.toScreen(MapProjection.toWorld(prediction.place.point), size)
    val color = colors.warning
    // The arrow stops at the place's ring rather than covering its dot.
    val ring = 7.dp.toPx()
    val length = (to - from).getDistance()
    if (length > ring * 2) drawCourseArrow(from, to - (to - from) * (ring / length), color)
    drawCircle(color, ring, to, style = Stroke(2.dp.toPx()))
    drawCircle(color, 2.5.dp.toPx(), to)
    val text = "${prediction.place.name} · ~${prediction.etaMinutes} min"
    val label = textMeasurer.measure(text, TextStyle(color = colors.onWarning, fontSize = 11.sp, fontWeight = FontWeight.Bold))
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
private val FavoriteLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
private val CityLabel = TextStyle(fontSize = 10.sp)
private val MajorCityLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
private val OblastCentreLabel = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

/** Oblast centres in the order their names claim space: the largest city wins a collision. */
private val CentresByPopulation = UkraineCities.oblastCentres.sortedByDescending { it.population }

/**
 * Every settlement with city status as a dot, sized by population. Oblast centres are named at every zoom,
 * largest first; one whose name would overlap a name already placed or a [reserved] area collapses into a
 * bigger, brighter dot instead. Once zoomed in, other names are added largest city first, skipping any that
 * would overlap. Names fade (and rise slightly) in when revealed and fade out when hidden, via [fader]; an
 * oblast centre's dot grows as its name fades out.
 */
private fun DrawScope.drawCities(
    camera: MapCamera,
    ink: MapInk,
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
    fun onScreen(p: Offset) = p.x >= -120 && p.y >= -20 && p.x <= size.width + 20 && p.y <= size.height + 20
    fun labelBox(city: MapCity, p: Offset): Rect {
        val text = label(city)
        val topLeft = Offset(p.x + cityDotRadius(city) + gap, p.y - text.size.height / 2f)
        return Rect(topLeft, Size(text.size.width.toFloat(), text.size.height.toFloat())).inflate(gap)
    }
    // Oblast centres first, so other names keep clear of them.
    CentresByPopulation.forEach { city ->
        val p = cityOnScreen(city, camera)
        if (!onScreen(p)) return@forEach
        val box = labelBox(city, p)
        if (placed.any { it.overlaps(box) }) return@forEach
        placed += box
        wanted += city
    }
    UkraineCities.all.forEach { city ->
        if (city in UkraineCities.oblastCentres) return@forEach
        val p = cityOnScreen(city, camera)
        if (!onScreen(p)) return@forEach
        drawCityDot(ink, cityDotRadius(city), p)
        if (!showLabels) return@forEach
        val box = labelBox(city, p)
        if (placed.any { it.overlaps(box) }) return@forEach
        placed += box
        wanted += city
    }
    val labels = fader.update(wanted, nowMs, instantLabels)
    // Ease out, so names settle gently; they rise into place and sink as they go.
    fun eased(city: MapCity) = (labels[city] ?: 0f).let { 1f - (1f - it) * (1f - it) }
    val collapsedRadius = 5.5.dp.toPx()
    CentresByPopulation.forEach { city ->
        val p = cityOnScreen(city, camera)
        if (!onScreen(p)) return@forEach
        val named = eased(city)
        val r = collapsedRadius + (cityDotRadius(city) - collapsedRadius) * named
        drawCircle(ink.halo.copy(alpha = ink.halo.alpha * (1f - named)), r + 1.5.dp.toPx(), p)
        drawCircle(androidx.compose.ui.graphics.lerp(ink.cityLabel, ink.cityDot, named), r, p)
    }
    val rise = 4.dp.toPx()
    labels.keys.forEach { city ->
        val p = cityOnScreen(city, camera)
        val text = label(city)
        val shown = eased(city)
        val topLeft = Offset(p.x + cityDotRadius(city) + gap, p.y - text.size.height / 2f + (1f - shown) * rise)
        drawHaloText(text, ink.cityLabel, ink.halo, topLeft, alpha = shown)
    }
}

/**
 * Colours of what is drawn straight onto the map: names, city dots and the halo around them. Over satellite
 * imagery, which is dark and busy in either theme, names are light on a dark halo; over the drawn map they
 * take the theme's colours, haloed in the map's background.
 */
private class MapInk(val label: Color, val cityLabel: Color, val cityDot: Color, val halo: Color) {
    companion object {
        private val Imagery = MapInk(
            label = Color.White,
            cityLabel = Color(0xFFF4F7FA),
            cityDot = Color(0xFFE4EAF0),
            halo = Color(0xD9000000),
        )

        fun over(tileSource: TileSource?, colors: RadarColors) =
            if (tileSource?.isImagery == true) Imagery else MapInk(colors.mapLabel, colors.cityLabel, colors.cityDot, colors.mapBackground.copy(alpha = 0.85f))
    }
}

/** [text] with a solid outline in [halo], so it stays legible over tiles, imagery, oblast fills and borders. */
private fun DrawScope.drawHaloText(text: TextLayoutResult, color: Color, halo: Color, topLeft: Offset, alpha: Float = 1f) {
    drawText(text, color = halo, topLeft = topLeft, alpha = alpha, drawStyle = Stroke(HaloWidth.toPx(), join = StrokeJoin.Round))
    // Fill must be explicit: on Android the text paint keeps the last draw style, so without it the stroke above
    // carries over and the name is drawn as a thick outline too.
    drawText(text, color = color, topLeft = topLeft, alpha = alpha, drawStyle = Fill)
}

/** A city dot ringed in the halo colour, so it shows on any background. */
private fun DrawScope.drawCityDot(ink: MapInk, radius: Float, center: Offset) {
    drawCircle(ink.halo, radius + 1.dp.toPx(), center)
    drawCircle(ink.cityDot, radius, center)
}

private val HaloWidth = 3.dp

private fun DrawScope.cityOnScreen(city: MapCity, camera: MapCamera) =
    camera.toScreen(MapProjection.toWorld(GeoPoint(city.lat.toDouble(), city.lon.toDouble())), size)

private fun DrawScope.cityDotRadius(city: MapCity) = when {
    city in UkraineCities.oblastCentres -> 3.5.dp
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

// --- Previews ------------------------------------------------------------------------------------------

/** The map alone, with the scenario's threats, tracks and favorites; [streets] draws the bundled street map. */
@Composable
private fun UkraineMapPreview(theme: ThemeMode, camera: MapCamera = MapCamera.Overview, streets: Boolean = true) {
    val state = PreviewData.scenario(theme)
    val tiles = remember(streets) { if (streets) MapTiles(client = null, blocking = true) else null }
    RadarTheme(theme) {
        CompositionLocalProvider(LocalMapTiles provides tiles) {
            UkraineMap(
                camera = camera,
                threats = state.mapThreats,
                alertedOblasts = state.alertedOblasts,
                favorites = state.favorites,
                alertedFavoriteIds = state.proximity.map { it.favorite.id }.toSet(),
                proximityRadiusKm = state.settings.proximityRadiusKm,
                modifier = Modifier.fillMaxSize(),
                predictionPlaces = state.favorites.map { Place(it.name, it.point, isFavorite = true) } + TrackPredictor.cityPlaces,
                tracks = state.tracks,
                clockOffsetMs = state.clockOffsetMs,
                tileSource = if (streets) TileSource.of(MapBackground.Streets, dark = theme == ThemeMode.Dark) else null,
            )
        }
    }
}

@Preview(name = "Ukraine map · street map · dark", widthDp = 1100, heightDp = 760)
@Composable
private fun UkraineMapDarkPreview() = UkraineMapPreview(ThemeMode.Dark)

@Preview(name = "Ukraine map · street map · light", widthDp = 1100, heightDp = 760)
@Composable
private fun UkraineMapLightPreview() = UkraineMapPreview(ThemeMode.Light)

@Preview(name = "Ukraine map · street map · Kyiv close-up", widthDp = 420, heightDp = 860)
@Composable
private fun UkraineMapKyivPreview() = UkraineMapPreview(ThemeMode.Dark, PreviewData.camera(PreviewData.city("Kyiv"), 4f))

@Preview(name = "Ukraine map · no background", widthDp = 1100, heightDp = 760)
@Composable
private fun UkraineMapPlainPreview() = UkraineMapPreview(ThemeMode.Dark, streets = false)
