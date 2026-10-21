package com.gusak.lab.radarnebo.tracking

import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.geo.Regions
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** A settlement or user favorite that a drone may be heading towards. */
data class Place(val name: String, val point: GeoPoint, val isFavorite: Boolean = false)

/** Where a tracked drone group is predicted to arrive next, and roughly when. */
data class ArrivalPrediction(
    val trackId: String,
    val place: Place,
    /** Estimated current position the prediction starts from. */
    val from: GeoPoint,
    val distanceKm: Double,
    val speedKmh: Double,
    val etaMinutes: Int,
)

/**
 * Dead-reckoning prediction: the drone keeps its current heading and speed. The first place inside
 * a corridor ahead of it, and within [DEFAULT_RANGE_KM], is the predicted next settlement.
 *
 * Headings and speeds come from area alerts, not radar, so this is a rough indication only.
 */
object TrackPredictor {
    const val DEFAULT_RANGE_KM = 50.0
    /**
     * Corridors ahead of the drone, as minimum half-width and growth per km travelled. Places the
     * path runs through (narrow, ~15°) win; the wide one (~27°) allows for coarse headings, since
     * positions come from settlement/raion centres, and is only used when the narrow one is empty.
     */
    private val corridors = listOf(8.0 to 0.27, 15.0 to 0.5)
    /** A place this close is where the drone already is, not where it is going. */
    private const val ARRIVED_KM = 3.0
    /** Alert timing can make a group look slow; Shahed-type drones rarely fly under this. */
    const val MIN_SPEED_KMH = 90.0
    private const val KM_PER_DEGREE = 111.2

    val cityPlaces: List<Place> by lazy { Regions.cities.map { Place(it.name, it.point) } }

    /** Next settlement on the drone's way within [rangeKm], with an estimated time of arrival. */
    fun predict(
        track: DroneTrack,
        now: Long,
        places: List<Place> = cityPlaces,
        rangeKm: Double = DEFAULT_RANGE_KM,
    ): ArrivalPrediction? {
        val ahead = candidatesAhead(track, now, places, rangeKm) ?: return null
        // The first place along the path, from the narrowest corridor that contains any.
        val next = corridors.firstNotNullOfOrNull { (minHalfWidth, slope) ->
            ahead.candidates.filter { it.inCorridor(minHalfWidth, slope) }.minByOrNull { it.along }
        } ?: return null
        return ahead.arrival(next)
    }

    /**
     * Predicted route: settlements the drone would pass within [horizonKm] if it keeps its heading
     * and speed, in order, each with an estimated time of arrival.
     */
    fun route(
        track: DroneTrack,
        now: Long,
        places: List<Place> = cityPlaces,
        horizonKm: Double = ROUTE_HORIZON_KM,
        maxStops: Int = 5,
    ): List<ArrivalPrediction> {
        val ahead = candidatesAhead(track, now, places, horizonKm) ?: return emptyList()
        val (minHalfWidth, slope) = corridors.first()
        return ahead.candidates
            .filter { it.inCorridor(minHalfWidth, slope) }
            .sortedBy { it.along }
            .take(maxStops)
            .map(ahead::arrival)
    }

    /** End of the predicted course after [horizonKm] (or the time it takes), for drawing the route line. */
    fun courseEnd(track: DroneTrack, now: Long, horizonKm: Double = ROUTE_HORIZON_KM): GeoPoint? {
        if (!track.active || track.samples.size < 2) return null
        val position = TrackInterpolator.positionAt(track, now) ?: return null
        val heading = position.headingDeg ?: return null
        val h = heading * PI / 180.0
        return GeoPoint(
            lat = position.lat + horizonKm * cos(h) / KM_PER_DEGREE,
            lon = position.lon + horizonKm * sin(h) / (cos(position.lat * PI / 180.0) * KM_PER_DEGREE),
        )
    }

    const val ROUTE_HORIZON_KM = 150.0

    private class Candidate(val place: Place, val along: Double, val cross: Double, val distance: Double) {
        fun inCorridor(minHalfWidth: Double, slope: Double) = cross <= max(minHalfWidth, along * slope)
    }

    private class Ahead(val trackId: String, val from: GeoPoint, val speed: Double, val candidates: List<Candidate>) {
        fun arrival(c: Candidate) = ArrivalPrediction(
            trackId = trackId,
            place = c.place,
            from = from,
            distanceKm = c.distance,
            speedKmh = speed,
            etaMinutes = ceil(c.distance / speed * 60).toInt().coerceAtLeast(1),
        )
    }

    /** Places ahead of the drone's current estimated position within [rangeKm], with along/cross-track offsets. */
    private fun candidatesAhead(track: DroneTrack, now: Long, places: List<Place>, rangeKm: Double): Ahead? {
        val s = track.samples
        if (!track.active || s.size < 2) return null
        val position = TrackInterpolator.positionAt(track, now) ?: return null
        val heading = position.headingDeg ?: return null
        val (_, rawSpeed) = TrackInterpolator.motion(s[s.lastIndex - 1], s.last())
        val speed = rawSpeed.coerceIn(MIN_SPEED_KMH, TrackKinematics.of(track).maxSpeedKmh)

        val h = heading * PI / 180.0
        val hx = sin(h)
        val hy = cos(h)
        val lonScale = cos(position.lat * PI / 180.0) * KM_PER_DEGREE
        val candidates = places.mapNotNull { place ->
            val dx = (place.point.lon - position.lon) * lonScale
            val dy = (place.point.lat - position.lat) * KM_PER_DEGREE
            val along = dx * hx + dy * hy
            val distance = GeoMath.distanceKm(position.point, place.point)
            if (along > 0 && distance in ARRIVED_KM..rangeKm) Candidate(place, along, abs(dx * hy - dy * hx), distance) else null
        }
        return Ahead(track.id, position.point, speed, candidates)
    }
}
