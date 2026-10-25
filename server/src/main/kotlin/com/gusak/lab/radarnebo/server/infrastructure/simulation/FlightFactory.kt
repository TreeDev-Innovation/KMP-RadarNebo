package com.gusak.lab.radarnebo.server.infrastructure.simulation

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.tracking.TrackOutcome
import kotlin.random.Random

/**
 * Plans single flights: their routes, when surveillance first sees them and how they end. Everything is
 * drawn from [random], so a plan is a pure function of its seed.
 */
internal class FlightFactory(private val random: Random) {

    fun flight(
        id: String,
        munition: Munition,
        waypoints: List<GeoPoint>,
        departAt: Long,
        origin: LaunchSite?,
        target: String,
        targetOblastUid: Int?,
        count: Int = 1,
        level: ThreatLevel = ThreatLevel.Red,
    ): Flight {
        val path = FlightPath(waypoints, random.nextDouble(munition.speedKmh.start, munition.speedKmh.endInclusive), departAt)
        val detected = detectionFraction(munition, path)
        val roll = random.nextDouble()
        val (endFraction, earlyOutcome) = when {
            roll < munition.interceptChance ->
                random.nextDouble(munition.interceptWindow).coerceAtLeast(detected + MIN_TRACKED_FRACTION) to TrackOutcome.Intercepted
            roll < munition.interceptChance + munition.lossChance ->
                random.nextDouble(maxOf(detected + MIN_TRACKED_FRACTION, 0.3)..0.95) to TrackOutcome.Lost
            else -> 1.0 to munition.completesAs
        }
        // Seen too late to be stopped on the way: it reaches the end of its path.
        val outcome = if (endFraction >= 1.0) munition.completesAs else earlyOutcome
        return Flight(
            id = id,
            munition = munition,
            path = path,
            endAt = path.timeAtFraction(minOf(endFraction, 1.0)),
            outcome = outcome,
            detectedAt = path.timeAtFraction(detected),
            count = count,
            origin = origin?.originUk.orEmpty(),
            targetName = target,
            targetOblastUid = targetOblastUid,
            level = level,
        )
    }

    /**
     * A drone route: from the launch site to where it crosses the border ([gate]), then at the target with one
     * to three doglegs of 15-55 km on the way, as Shaheds fly to get around air defence.
     */
    fun droneRoute(from: GeoPoint, gate: GeoPoint, to: GeoPoint): List<GeoPoint> = listOf(from) + droneRoute(gate, to)

    /** A drone route straight from [from] at [to], doglegging on the way. */
    fun droneRoute(from: GeoPoint, to: GeoPoint): List<GeoPoint> {
        val totalKm = GeoMath.distanceKm(from, to)
        val doglegs = when {
            totalKm < 120 -> 0
            totalKm < 350 -> 1
            else -> 2 + random.nextInt(2)
        }
        val bearing = GeoMath.bearingDeg(from, to)
        val turns = List(doglegs) { random.nextDouble(0.25, 0.85) }.sorted().map { f ->
            val side = if (random.nextBoolean()) 90.0 else -90.0
            GeoMath.destination(FlightPath.between(from, to, f), bearing + side, random.nextDouble(15.0, 55.0))
        }
        return listOf(from) + turns + to
    }

    /**
     * A cruise missile route: from the launch area to an entry [gate], then over one to three turning points
     * near the line to the target, the long winding way cruise missiles fly to stay low and unpredictable.
     */
    fun cruiseRoute(from: GeoPoint, gate: GeoPoint, to: GeoPoint): List<GeoPoint> {
        val bearing = GeoMath.bearingDeg(gate, to)
        val lengthKm = GeoMath.distanceKm(gate, to)
        val turns = SimulatedLocations.settlements
            .map { it.city.point }
            .mapNotNull { p ->
                val km = GeoMath.distanceKm(gate, p)
                val offBearing = Math.toRadians(GeoMath.bearingDeg(gate, p) - bearing)
                val along = km * kotlin.math.cos(offBearing) / lengthKm
                val cross = kotlin.math.abs(km * kotlin.math.sin(offBearing))
                if (along in 0.15..0.85 && cross <= 80.0) along to p else null
            }
            .shuffled(random)
            .take(1 + random.nextInt(3))
            .sortedBy { it.first }
            .map { jitter(it.second, 8.0) }
        return listOf(from, gate) + turns + to
    }

    /** One of the three [gates] (settlement names) that make the shortest way from [from] to [to], give or take [jitterKm]. */
    fun gate(gates: List<String>, from: GeoPoint, to: GeoPoint, jitterKm: Double): GeoPoint {
        val gate = gates.map(StrikeGeography::point)
            .sortedBy { GeoMath.distanceKm(from, it) + GeoMath.distanceKm(it, to) }
            .take(3)
            .random(random)
        return jitter(gate, jitterKm)
    }

    /** A point within [km] of [point], so flights over the same settlement don't fly through one spot. */
    fun jitter(point: GeoPoint, km: Double): GeoPoint = GeoMath.destination(point, random.nextDouble(0.0, 360.0), random.nextDouble(0.0, km))

    /** How far along its path surveillance first sees the flight: ballistic launches and bomb releases at once. */
    private fun detectionFraction(munition: Munition, path: FlightPath): Double {
        if (munition.weaponClass == WeaponClass.BallisticMissile || munition.weaponClass == WeaponClass.GuidedBomb) return 0.0
        return (0..DETECTION_STEPS).map { it.toDouble() / DETECTION_STEPS }
            .firstOrNull { StrikeGeography.inCoverage(path.positionAtFraction(it)) }
            ?: 0.0
    }

    private fun Random.nextDouble(range: ClosedFloatingPointRange<Double>) =
        if (range.endInclusive <= range.start) range.start else nextDouble(range.start, range.endInclusive)

    private companion object {
        const val DETECTION_STEPS = 100
        /** An interception always leaves a few percent of the path tracked, so it shows on the map. */
        const val MIN_TRACKED_FRACTION = 0.05
    }
}
