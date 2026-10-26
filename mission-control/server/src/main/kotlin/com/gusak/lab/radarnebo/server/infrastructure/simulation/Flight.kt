package com.gusak.lab.radarnebo.server.infrastructure.simulation

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.tracking.TrackOutcome

/** A route flown at constant speed, starting at [departAt]. Positions between waypoints are linear. */
internal class FlightPath(val waypoints: List<GeoPoint>, val speedKmh: Double, val departAt: Long) {
    init {
        require(waypoints.size >= 2) { "A path needs at least two waypoints" }
        require(speedKmh > 0)
    }

    /** Distance from the first waypoint to each waypoint, km. */
    private val cumulativeKm: DoubleArray = waypoints.zipWithNext { a, b -> GeoMath.distanceKm(a, b) }
        .runningFold(0.0) { acc, d -> acc + d }.toDoubleArray()

    val lengthKm: Double get() = cumulativeKm.last()
    val durationMs: Long = (lengthKm / speedKmh * 3_600_000).toLong().coerceAtLeast(1)
    val arriveAt: Long get() = departAt + durationMs
    val target: GeoPoint get() = waypoints.last()

    fun timeAtFraction(fraction: Double): Long = departAt + (durationMs * fraction.coerceIn(0.0, 1.0)).toLong()

    fun positionAt(t: Long): GeoPoint = positionAtKm(((t - departAt).coerceIn(0, durationMs)) * speedKmh / 3_600_000)

    fun positionAtFraction(fraction: Double): GeoPoint = positionAtKm(lengthKm * fraction.coerceIn(0.0, 1.0))

    /** Points every [stepKm] along the path from [from] to [to] (times), both ends included. */
    fun pointsBetween(from: Long, to: Long, stepKm: Double): List<GeoPoint> {
        val start = (from - departAt).coerceIn(0, durationMs) * speedKmh / 3_600_000
        val end = (to - departAt).coerceIn(0, durationMs) * speedKmh / 3_600_000
        if (end <= start) return listOf(positionAtKm(start))
        val steps = ((end - start) / stepKm).toInt().coerceAtLeast(1)
        return (0..steps).map { positionAtKm(start + (end - start) * it / steps) }
    }

    private fun positionAtKm(km: Double): GeoPoint {
        val i = cumulativeKm.indexOfLast { it <= km }.coerceIn(0, waypoints.lastIndex - 1)
        val legKm = cumulativeKm[i + 1] - cumulativeKm[i]
        val f = if (legKm <= 0.0) 0.0 else ((km - cumulativeKm[i]) / legKm).coerceIn(0.0, 1.0)
        return between(waypoints[i], waypoints[i + 1], f)
    }

    companion object {
        fun between(a: GeoPoint, b: GeoPoint, f: Double) = GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }
}

/**
 * One airborne target: a drone group, a missile or a bomb. Its fate is decided when it is planned: it
 * ends at [endAt], shot down, lost or on its target ([outcome]). Air surveillance sees it from [detectedAt].
 */
internal class Flight(
    val id: String,
    val munition: Munition,
    val path: FlightPath,
    val endAt: Long,
    val outcome: TrackOutcome,
    val detectedAt: Long,
    /** Drones in the group; 1 for missiles and bombs. */
    val count: Int = 1,
    /** Where it was launched from, as the warnings name it ("з Курської області"). */
    val origin: String,
    /** The settlement or region it is aimed at. */
    val targetName: String,
    val targetOblastUid: Int?,
    val level: ThreatLevel = ThreatLevel.Red,
) {
    val departAt: Long get() = path.departAt
    val threat: ThreatType get() = munition.threat

    val message: String = buildString {
        append(munition.message)
        if (count > 1) append(", група $count од.")
        if (origin.isNotEmpty()) append(", ").append(fromPreposition(origin)).append(' ').append(origin)
    }

    /** "з Курської області", but "зі Смоленської області": Ukrainian uses "зі" before з, с, ш, щ, ж and a consonant. */
    private fun fromPreposition(word: String): String {
        val first = word.firstOrNull()?.lowercaseChar()
        val second = word.getOrNull(1)?.lowercaseChar()
        return if (first != null && first in "зсшщж" && second != null && second !in "аеєиіїоуюя") "зі" else "з"
    }

    /** In the air and seen by air surveillance at [t]. */
    fun isTracked(t: Long) = t in detectedAt until endAt

    fun positionAt(t: Long): GeoPoint = path.positionAt(minOf(t, endAt))

    val endPoint: GeoPoint get() = path.positionAt(endAt)
}

/**
 * A threat over whole regions for a while: aviation activity, a MiG-31K in the air, missile carriers at
 * sea. [oblastUids] are alerted from [from] until [until].
 */
internal class AreaThreat(
    val type: ThreatType,
    val oblastUids: Collection<Int>,
    val from: Long,
    val until: Long,
    val message: String,
    val level: ThreatLevel = ThreatLevel.Red,
) {
    fun isActive(t: Long) = t in from until until
}

/** What an attack consists of: the flights it launches and the regional threats around them. */
internal class AttackPlan(val flights: List<Flight>, val areas: List<AreaThreat>) {
    /** When the last flight or regional threat is over. */
    val endsAt: Long = maxOf(flights.maxOfOrNull { it.endAt } ?: Long.MIN_VALUE, areas.maxOfOrNull { it.until } ?: Long.MIN_VALUE)

    companion object {
        val Empty = AttackPlan(emptyList(), emptyList())
    }
}
