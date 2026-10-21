package com.gusak.lab.radarnebo.ui.map

import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.awaitCancellation
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.gusak.lab.radarnebo.data.FavoriteLocation
import com.gusak.lab.radarnebo.domain.ProximityHit
import com.gusak.lab.radarnebo.domain.ThreatMarker
import com.gusak.lab.radarnebo.geo.GeoPoint
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/** One camera "shot" in cinematic mode. */
data class CinematicShot(
    val key: String,
    val kind: Kind,
    val title: String,
    val subtitle: String,
    val focus: GeoPoint,
    val zoom: Float,
    val threatId: String? = null,
) {
    enum class Kind { ThreatNearFavorite, FavoriteClear, Hotspot, Overview }
}

object ShotPlanner {
    private const val UKRAINE_SPAN_KM = 1300f

    /**
     * Threats near points of interest come first. With nothing nearby, the camera patrols the
     * favorites and then the busiest threats so the screen keeps telling a story.
     */
    fun plan(
        proximity: List<ProximityHit>,
        favorites: List<FavoriteLocation>,
        threats: List<ThreatMarker>,
        radiusKm: Int,
    ): List<CinematicShot> {
        if (proximity.isNotEmpty()) {
            return proximity.map { hit ->
                val f = hit.favorite.point
                val t = hit.threat.point
                val span = max(max(hit.distanceKm.toFloat() * 3f, radiusKm * 2.6f), 60f)
                CinematicShot(
                    key = "hit:${hit.favorite.id}:${hit.threat.id}",
                    kind = CinematicShot.Kind.ThreatNearFavorite,
                    title = "THREAT NEAR ${hit.favorite.name.uppercase()}",
                    subtitle = buildString {
                        append(hit.threat.type.label)
                        append(" · ")
                        append(hit.threat.level.name.uppercase())
                        append(" · ")
                        append(if (hit.insideRegion) "region-wide (${hit.threat.oblastName})" else "${hit.distanceKm.toInt()} km – ${hit.threat.title}")
                    },
                    focus = if (hit.insideRegion) f else GeoPoint((f.lat + t.lat) / 2, (f.lon + t.lon) / 2),
                    zoom = zoomForSpan(span),
                    threatId = hit.threat.id,
                )
            }
        }
        val patrol = favorites.map {
            CinematicShot(
                key = "fav:${it.id}",
                kind = CinematicShot.Kind.FavoriteClear,
                title = "${it.name.uppercase()} · CLEAR",
                subtitle = "No threats within $radiusKm km",
                focus = it.point,
                zoom = zoomForSpan(radiusKm * 3.5f),
            )
        }
        val hotspots = threats.take(4).map {
            CinematicShot(
                key = "hot:${it.id}",
                kind = CinematicShot.Kind.Hotspot,
                title = "TRACKING · ${it.title.uppercase()}",
                subtitle = "${it.type.label} · ${it.level.name.uppercase()} · ${it.oblastName}",
                focus = it.point,
                zoom = zoomForSpan(if (it.isRegionWide) 320f else 160f),
                threatId = it.id,
            )
        }
        val overview = CinematicShot(
            key = "overview",
            kind = CinematicShot.Kind.Overview,
            title = "UKRAINE · OVERVIEW",
            subtitle = "${threats.size} active threats",
            focus = MapProjection.toGeo(MapProjection.bounds.center),
            zoom = 1f,
        )
        return listOf(overview) + patrol + hotspots
    }

    private fun zoomForSpan(spanKm: Float) = (UKRAINE_SPAN_KM / spanKm).coerceIn(1.5f, MapCamera.MAX_ZOOM)
}

/**
 * Runs the cinematic camera through [CinematicShot]s. With [start], it opens already framing that shot
 * and holds it: IDE previews and screenshots render only the first frames, before any fly-to.
 */
@Stable
class CinematicDirector(private val start: CinematicShot? = null) {
    private val startCenter = start?.let { MapProjection.toWorld(it.focus) } ?: MapCamera.Overview.center
    private val cx = Animatable(startCenter.x)
    private val cy = Animatable(startCenter.y)
    private val zoom = Animatable(start?.zoom ?: MapCamera.Overview.zoom)

    var currentShot by mutableStateOf(start)
        private set
    var shotIndex by mutableStateOf(0)
        private set
    var shotCount by mutableStateOf(0)
        private set

    val camera: MapCamera get() = MapCamera(Offset(cx.value, cy.value), zoom.value)

    suspend fun run(shots: State<List<CinematicShot>>) {
        if (start != null) {
            shotCount = shots.value.size
            shotIndex = shots.value.indexOf(start).coerceAtLeast(0)
            awaitCancellation()
        }
        var index = 0
        var lastKey: String? = null
        while (true) {
            val list = shots.value
            if (list.isEmpty()) {
                delay(500)
                continue
            }
            // A new threat near a point of interest preempts the patrol order.
            val urgent = list.firstOrNull { it.kind == CinematicShot.Kind.ThreatNearFavorite && it.key != lastKey }
            val shot = if (urgent != null && currentShot?.kind != CinematicShot.Kind.ThreatNearFavorite) urgent else list[index % list.size]
            shotIndex = list.indexOf(shot)
            shotCount = list.size
            currentShot = shot
            lastKey = shot.key
            flyTo(MapProjection.toWorld(shot.focus), shot.zoom)
            drift(shot)
            index = shotIndex + 1
        }
    }

    /** Fly-to with a zoom-out arc so long hops read as travel instead of a jump cut. */
    private suspend fun flyTo(target: Offset, targetZoom: Float) = coroutineScope {
        val distance = (target - Offset(cx.value, cy.value)).getDistance()
        val arcZoom = if (distance > 1.2f) max(1f, minOf(zoom.value, targetZoom) * 0.45f) else minOf(zoom.value, targetZoom)
        val duration = (1800 + distance * 260).toInt().coerceAtMost(3600)
        launch { cx.animateTo(target.x, tween(duration, easing = FastOutSlowInEasing)) }
        launch { cy.animateTo(target.y, tween(duration, easing = FastOutSlowInEasing)) }
        launch {
            zoom.animateTo(arcZoom, tween(duration / 2, easing = FastOutSlowInEasing))
            zoom.animateTo(targetZoom, tween(duration / 2, easing = FastOutSlowInEasing))
        }
    }

    /** Slow push-in while holding on the subject, like a documentary camera. */
    private suspend fun drift(shot: CinematicShot) = coroutineScope {
        val hold = if (shot.kind == CinematicShot.Kind.ThreatNearFavorite) 7000 else 5000
        val nudge = 0.15f / shot.zoom
        launch { zoom.animateTo(shot.zoom * 1.2f, tween(hold, easing = LinearEasing)) }
        launch { cx.animateTo(cx.value + nudge, tween(hold, easing = LinearEasing)) }
    }
}

@Composable
fun rememberCinematicDirector(shots: List<CinematicShot>): CinematicDirector {
    val preview = LocalInspectionMode.current
    val director = remember {
        // The shot the live director would pick first: a threat near a favorite, else the first in order.
        val first = shots.firstOrNull { it.kind == CinematicShot.Kind.ThreatNearFavorite } ?: shots.firstOrNull()
        CinematicDirector(start = if (preview) first else null)
    }
    val latest = rememberUpdatedState(shots)
    LaunchedEffect(director) { director.run(latest) }
    return director
}
