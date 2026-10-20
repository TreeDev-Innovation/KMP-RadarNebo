package com.gusak.lab.radarnebo.ui.map

import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.InterpolatedPosition
import kotlin.math.abs
import kotlin.math.exp

/**
 * Keeps drones moving smoothly on screen. Positions come from [com.gusak.lab.radarnebo.tracking.TrackInterpolator]
 * every frame (dead reckoning between reports); when a new report corrects a drone's position, the
 * marker glides to the new estimate over a fraction of a second instead of jumping.
 *
 * Plain (non-snapshot) state: it is updated while drawing and never triggers recomposition.
 */
class TrackSmoother(private val glideMs: Float = 700f) {
    private val shown = HashMap<String, GeoPoint>()
    private var lastFrameMs = 0L

    fun smooth(positions: List<Pair<DroneTrack, InterpolatedPosition>>, nowMs: Long): List<Pair<DroneTrack, InterpolatedPosition>> {
        val dt = if (lastFrameMs == 0L) 0L else (nowMs - lastFrameMs).coerceIn(0L, 250L)
        lastFrameMs = nowMs
        val k = 1f - exp(-dt / glideMs)
        val result = positions.map { (track, target) ->
            val previous = shown[track.id]
            val next = if (previous == null || farApart(previous, target.point)) {
                target.point
            } else {
                GeoPoint(previous.lat + (target.lat - previous.lat) * k, previous.lon + (target.lon - previous.lon) * k)
            }
            shown[track.id] = next
            track to target.copy(lat = next.lat, lon = next.lon)
        }
        shown.keys.retainAll(positions.mapTo(HashSet()) { it.first.id })
        return result
    }

    /** Big corrections (e.g. a track re-acquired far away) snap rather than slide across the map. */
    private fun farApart(a: GeoPoint, b: GeoPoint) = abs(a.lat - b.lat) > 1.5 || abs(a.lon - b.lon) > 2.0
}
