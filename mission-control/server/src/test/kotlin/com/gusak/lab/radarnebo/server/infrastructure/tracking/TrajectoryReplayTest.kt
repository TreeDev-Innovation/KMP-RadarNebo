package com.gusak.lab.radarnebo.server.infrastructure.tracking

import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.server.config.SimulationConfig
import com.gusak.lab.radarnebo.server.infrastructure.simulation.AirSituationSimulator
import com.gusak.lab.radarnebo.server.infrastructure.simulation.CombinedStrikePlanner
import com.gusak.lab.radarnebo.server.infrastructure.simulation.Munition
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.TrackInterpolator
import com.gusak.lab.radarnebo.tracking.TrackKinematics
import com.gusak.lab.radarnebo.tracking.TrajectoryEstimator
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Scores alert-derived tracking against the truth. The simulation knows where every drone group really is, and
 * raises the alerts those groups would cause; the tracker only gets the alerts, as it would from alerts.in.ua.
 */
class TrajectoryReplayTest {
    private val config = SimulationConfig()
    private val period = config.strikePeriod.inWholeMilliseconds
    private val minute = 60_000L
    private val strikeStart = generateSequence(1_800_000_000_000L / period) { it + 1 }
        .first { slot -> CombinedStrikePlanner(config.seed).plan(slot, slot * period).flights.count { it.munition == Munition.Shahed } >= 8 } * period

    private class Score(val errorsKm: List<Double>, val tracks: List<DroneTrack>, val tracksPerTarget: Double) {
        val median get() = errorsKm.sorted()[errorsKm.size / 2]
    }

    /**
     * Every minute of a strike period, every active drone track's position against the nearest true drone group,
     * with the server's estimate ([enrich]) or with the old client-side interpolation of the raw reports.
     */
    /** Surveillance fused with the alerts: none, or every [everyMin] minutes for the targets [sighted] picks. */
    private class Surveillance(val everyMin: Long, val sighted: (String) -> Boolean)

    private fun replay(enrich: Boolean, surveillance: Surveillance? = null): Score {
        val fused = surveillance != null
        val simulator = AirSituationSimulator(config, firstAlertId = 1)
        val tracking = if (fused) FusedTracking("replay") else AlertDerivedTracking("replay")
        val errors = mutableListOf<Double>()
        var tracks = emptyList<DroneTrack>()
        var activeTracks = 0
        var flyingTargets = 0
        for (step in 0 until period / minute) {
            val t = strikeStart + step * minute
            val report = simulator.reportAt(t)
            val sightings = surveillance
                ?.takeIf { step % it.everyMin == 0L }
                ?.let { s -> report.observations.filter { s.sighted(it.targetId) } }
                .orEmpty()
            tracks = tracking.update(report.alerts, sightings, t).tracks
                .map { if (enrich) TrajectoryEstimator.enrich(it, t) else it }
            // The truth: surveillance sightings of every drone-class flight in the air, strike and front line alike.
            val flying = report.observations.filter { it.type == ThreatType.Drones && it.outcome == null }.map { it.position }
            if (flying.isEmpty()) continue
            flyingTargets += flying.size
            activeTracks += tracks.count { it.type == ThreatType.Drones && it.active }
            tracks.filter { it.type == ThreatType.Drones && it.active }.forEach { track ->
                val position = TrackInterpolator.positionAt(track, t) ?: return@forEach
                errors += flying.minOf { GeoMath.distanceKm(it, position.point) }
            }
        }
        return Score(errors, tracks, activeTracks.toDouble() / flyingTargets)
    }

    @Test
    fun serverEstimatesAreCloserToTheTruthThanRawReports() {
        val fused = replay(enrich = true)
        val raw = replay(enrich = false)
        println("Drone position error, median: fused ${"%.1f".format(fused.median)} km, raw ${"%.1f".format(raw.median)} km (${fused.errorsKm.size} fixes)")
        assertTrue(fused.errorsKm.size > 100, "enough fixes to score: ${fused.errorsKm.size}")
        assertTrue(fused.median < raw.median, "fused ${fused.median} km vs raw ${raw.median} km")
        // Measured at about 41 km (raw reports: 43.5 km). Alerts only name an area ahead of the target, so a
        // single-report track alone is about 30 km off; this guards against regressions, it is not a target.
        assertTrue(fused.median < 45.0, "median error ${fused.median} km")
    }

    @Test
    fun sightingsFusedWithAlertsBeatAlertsAlone() {
        val alertsOnly = replay(enrich = true)
        // Sparse, partial surveillance: half the targets, once every 15 minutes; and a fix on every target every 5.
        val sparse = replay(enrich = true, Surveillance(15) { it.hashCode() % 2 == 0 })
        val dense = replay(enrich = true, Surveillance(5) { true })
        println(
            "Drone position error, median: alerts ${"%.1f".format(alertsOnly.median)} km, + sparse sightings " +
                "${"%.1f".format(sparse.median)} km, + dense sightings ${"%.1f".format(dense.median)} km; active tracks per " +
                "flying drone: ${listOf(alertsOnly, sparse, dense).joinToString(" / ") { "%.2f".format(it.tracksPerTarget) }}",
        )
        assertTrue(sparse.median < alertsOnly.median, "sparse ${sparse.median} km vs alerts alone ${alertsOnly.median} km")
        assertTrue(dense.median < sparse.median, "dense ${dense.median} km vs sparse ${sparse.median} km")
    }

    @Test
    fun everyServedPathIsFlyable() {
        replay(enrich = true).tracks.filter { it.type == ThreatType.Drones }.forEach { track ->
            val max = TrackKinematics.of(track).maxSpeedKmh * 1.25
            track.path.zipWithNext().filter { (a, b) -> b.t - a.t >= minute }.forEach { (a, b) ->
                val speed = GeoMath.distanceKm(a.point, b.point) / ((b.t - a.t) / 3_600_000.0)
                assertTrue(speed <= max, "${track.id} path implies $speed km/h")
            }
        }
    }
}
