package com.gusak.lab.radarnebo.server.infrastructure.simulation

import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.server.application.SituationReport
import com.gusak.lab.radarnebo.server.config.SimulationConfig
import com.gusak.lab.radarnebo.server.domain.alert.AlertLocation
import com.gusak.lab.radarnebo.server.domain.tracking.TargetObservation
import kotlin.math.abs
import kotlin.random.Random

/**
 * The simulated air war: plans combined strikes and front-line activity, flies them, and reports at any
 * moment what Ukraine's warning system and air surveillance would: the alerts in force (in the alerts.in.ua
 * shape) and a sighting of every target in the air.
 *
 * Attacks are planned per time slot from the seed, so they are the same across restarts. The alert
 * registry is stateful, so [reportAt] must be called with times that never go back.
 */
internal class AirSituationSimulator(config: SimulationConfig, firstAlertId: Long) {
    private val strikes = CombinedStrikePlanner(config.seed)
    private val frontline = FrontlinePlanner(config.seed)
    private val warning = WarningSystem()
    private val registry = AlertRegistry(maxOf(firstAlertId, SimulatedLocations.FIRST_DYNAMIC_ALERT_ID))
    private val strikePeriodMs = config.strikePeriod.inWholeMilliseconds

    private val strikePlans = HashMap<Long, AttackPlan>()
    private val frontlinePlans = HashMap<Long, AttackPlan>()
    private var lastReportAt = Long.MIN_VALUE

    fun reportAt(t: Long): SituationReport {
        require(t >= lastReportAt) { "The simulation only runs forwards: $t is before $lastReportAt" }
        lastReportAt = t
        val plans = plansAround(t)
        val flights = plans.flatMap { it.flights }.filter { t >= it.departAt && t <= it.endAt + TERMINAL_REPORT_MS }
        val areas = plans.flatMap { it.areas }.filter { it.isActive(t) }

        val demands = warning.demands(t, flights, areas)
        val alerts = registry.update(demands, t) + SimulatedLocations.permanentAlerts

        val markerIds = markerIdsByTarget(demands)
        val observations = flights.mapNotNull { observe(it, t, markerIds[it.id].orEmpty()) }
        return SituationReport(t, alerts, observations)
    }

    /** Every location the simulation alerts. */
    val locations get() = SimulatedLocations.all

    /**
     * The markers of the alerts each target is in right now, by target id, so clients draw the target instead
     * of those alerts' markers.
     */
    private fun markerIdsByTarget(demands: Map<AlertLocation, List<ThreatDemand>>): Map<String, List<String>> =
        demands.flatMap { (location, locationDemands) ->
            val alertId = registry.alertId(location.uid) ?: return@flatMap emptyList<Pair<String, String>>()
            locationDemands.mapNotNull { demand -> demand.targetId?.let { it to markerId(alertId, demand.type) } }
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }

    /** Must match the marker ids core's ThreatMapper gives each alert threat (`"${alert.id}:${threat.threatType}"`). */
    private fun markerId(alertId: Long, type: ThreatType) = "$alertId:${type.apiValue}"

    /** The current and recent strikes and front-line slots, planned once and kept while they matter. */
    private fun plansAround(t: Long): List<AttackPlan> {
        val strikeSlots = ((t - MAX_STRIKE_SPAN_MS) / strikePeriodMs)..(t / strikePeriodMs)
        val frontlineSlots = ((t - MAX_FRONTLINE_SPAN_MS) / frontline.slotMs)..(t / frontline.slotMs)
        strikePlans.keys.retainAll { it in strikeSlots }
        frontlinePlans.keys.retainAll { it in frontlineSlots }
        return strikeSlots.map { slot -> strikePlans.getOrPut(slot) { strikes.plan(slot, slot * strikePeriodMs) } } +
            frontlineSlots.map { slot -> frontlinePlans.getOrPut(slot) { frontline.plan(slot) } }
    }

    /**
     * What surveillance reports of [flight] at [t]: its position, with a small error, while it is tracked;
     * where and how it ended, for a while after.
     */
    private fun observe(flight: Flight, t: Long, markerIds: List<String>): TargetObservation? = when {
        t < flight.detectedAt -> null
        t >= flight.endAt -> TargetObservation(
            targetId = flight.id,
            type = flight.threat,
            observedAt = flight.endAt,
            position = flight.endPoint,
            level = flight.level,
            message = flight.message,
            outcome = flight.outcome,
        )
        else -> TargetObservation(
            targetId = flight.id,
            type = flight.threat,
            observedAt = t,
            position = withError(flight.positionAt(t), flight.munition.positionErrorKm, flight.id, t),
            level = flight.level,
            message = flight.message,
            alertMarkerIds = markerIds,
        )
    }

    /** A position error that is the same for the same flight and time, so replays agree. */
    private fun withError(point: GeoPoint, errorKm: Double, flightId: String, t: Long): GeoPoint {
        if (errorKm <= 0.0) return point
        val random = Random(flightId.hashCode() * 31L + t / 1_000)
        return GeoMath.destination(point, random.nextDouble(0.0, 360.0), abs(random.nextDouble(-1.0, 1.0)) * errorKm)
    }

    private companion object {
        /** Longer than any strike lasts, from its first launch to its last drone coming down. */
        const val MAX_STRIKE_SPAN_MS = 10 * HOUR_MS
        /** Longer than any front-line flight (drones from the nearest launch sites, up to 600 km). */
        const val MAX_FRONTLINE_SPAN_MS = 5 * HOUR_MS
        /** How long after a flight ends its last sighting is still reported. */
        const val TERMINAL_REPORT_MS = 5 * MINUTE_MS
    }
}
