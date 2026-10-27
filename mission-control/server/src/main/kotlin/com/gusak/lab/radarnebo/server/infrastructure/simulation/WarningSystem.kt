package com.gusak.lab.radarnebo.server.infrastructure.simulation

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.server.domain.alert.AlertLocation
import com.gusak.lab.radarnebo.tracking.TrackOutcome
import kotlin.math.abs

/** A threat the warning system wants declared for a location. [targetId] is set when the target is right there. */
internal data class ThreatDemand(
    val type: ThreatType,
    val level: ThreatLevel,
    val message: String,
    val targetId: String? = null,
)

/**
 * The simulated air raid warning system: decides from what is in the air which locations must be under
 * alert, and for what.
 *
 * - Drones and cruise missiles: settlements near the target, or on its way within a few minutes, get a red
 *   alert; settlements further along its way get a yellow (preliminary) one. See [WarningProfile].
 * - Ballistic missiles: the target region and the regions under the last part of the trajectory, from launch.
 * - Guided bombs: the target town, from release.
 * - Air defence at work: the settlement nearest an interception, for a few minutes around it.
 * - Regional threats ([AreaThreat]): their regions.
 */
internal class WarningSystem(private val settlements: List<Settlement> = SimulatedLocations.settlements) {

    fun demands(t: Long, flights: List<Flight>, areas: List<AreaThreat>): Map<AlertLocation, List<ThreatDemand>> {
        val demands = HashMap<AlertLocation, MutableList<ThreatDemand>>()
        fun demand(location: AlertLocation, demand: ThreatDemand) = demands.getOrPut(location) { mutableListOf() }.add(demand)

        flights.filter { it.isTracked(t) }.forEach { flight ->
            when (flight.munition.weaponClass) {
                WeaponClass.Drone, WeaponClass.CruiseMissile -> alongTheWay(flight, t, ::demand)
                WeaponClass.BallisticMissile -> ballisticRegions(flight).forEach { uid ->
                    demand(SimulatedLocations.region(uid), ThreatDemand(flight.threat, ThreatLevel.Red, flight.message))
                }
                WeaponClass.GuidedBomb -> nearest(flight.path.target, 15.0)?.let {
                    demand(it.location, ThreatDemand(flight.threat, ThreatLevel.Red, flight.message, flight.id))
                }
            }
        }
        flights.filter { it.outcome == TrackOutcome.Intercepted && t in it.endAt - AIR_DEFENSE_BEFORE_MS..it.endAt + AIR_DEFENSE_AFTER_MS }
            .forEach { flight ->
                nearest(flight.endPoint, AIR_DEFENSE_RADIUS_KM)?.let { demand(it.location, ThreatDemand(ThreatType.AirDefense, ThreatLevel.Red, AIR_DEFENSE_MESSAGE)) }
            }
        areas.filter { it.isActive(t) }.forEach { area ->
            area.oblastUids.forEach { uid -> demand(SimulatedLocations.region(uid), ThreatDemand(area.type, area.level, area.message)) }
        }
        return demands
    }

    private fun alongTheWay(flight: Flight, t: Long, demand: (AlertLocation, ThreatDemand) -> Unit) {
        val profile = flight.munition.warning
        val here = flight.positionAt(t)
        val redPath = flight.path.pointsBetween(t, t + profile.redLeadMs, STEP_KM)
        val yellowPath = if (profile.yellowLeadMs > profile.redLeadMs) flight.path.pointsBetween(t + profile.redLeadMs, t + profile.yellowLeadMs, STEP_KM) else emptyList()
        settlements.forEach { settlement ->
            val p = settlement.city.point
            val demandHere = when {
                isWithin(here, p, profile.redRadiusKm) -> ThreatDemand(flight.threat, flight.level, flight.message, flight.id)
                redPath.any { isWithin(it, p, profile.redRadiusKm) } -> ThreatDemand(flight.threat, flight.level, flight.message)
                yellowPath.any { isWithin(it, p, profile.corridorKm) } -> ThreatDemand(flight.threat, ThreatLevel.Yellow, flight.message)
                else -> null
            }
            demandHere?.let { demand(settlement.location, it) }
        }
    }

    /** The target's region (and Kyiv oblast around Kyiv) and the regions under the last [BALLISTIC_WARNED_KM]. */
    private fun ballisticRegions(flight: Flight): Set<Int> {
        val path = flight.path
        val fromFraction = (1.0 - BALLISTIC_WARNED_KM / path.lengthKm).coerceAtLeast(0.0)
        val overflown = (0..BALLISTIC_SAMPLES).mapNotNull { i ->
            Regions.oblastAt(path.positionAtFraction(fromFraction + (1.0 - fromFraction) * i / BALLISTIC_SAMPLES))
        }
        val target = listOf(flight.targetOblastUid) +
            if (flight.targetOblastUid == Regions.KYIV_CITY_UID) listOf(SimulatedLocations.KYIV_OBLAST_UID) else emptyList()
        return (target + overflown).filterTo(LinkedHashSet()) { it !in SimulatedLocations.OCCUPIED_REGIONS }
    }

    private fun nearest(point: GeoPoint, maxKm: Double): Settlement? = settlements
        .map { it to GeoMath.distanceKm(point, it.city.point) }
        .filter { it.second <= maxKm }
        .minByOrNull { it.second }
        ?.first

    /** Distance check with a cheap bounding-box test first: this runs for every settlement and flight each tick. */
    private fun isWithin(a: GeoPoint, b: GeoPoint, km: Double): Boolean {
        if (km <= 0.0) return false
        val degrees = km / KM_PER_DEGREE_LAT
        if (abs(a.lat - b.lat) > degrees || abs(a.lon - b.lon) > degrees * 2) return false
        return GeoMath.distanceKm(a, b) <= km
    }

    private companion object {
        const val STEP_KM = 5.0
        const val KM_PER_DEGREE_LAT = 111.0
        const val BALLISTIC_WARNED_KM = 300.0
        const val BALLISTIC_SAMPLES = 15
        const val AIR_DEFENSE_RADIUS_KM = 35.0
        const val AIR_DEFENSE_BEFORE_MS = 2 * MINUTE_MS
        const val AIR_DEFENSE_AFTER_MS = 3 * MINUTE_MS
        const val AIR_DEFENSE_MESSAGE = "Працює ППО"
    }
}
