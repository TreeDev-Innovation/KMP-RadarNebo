package com.gusak.lab.radarnebo.server.infrastructure.tracking

import com.gusak.lab.radarnebo.data.Alert
import com.gusak.lab.radarnebo.data.RadarNeboApi
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.DroneTracker
import com.gusak.lab.radarnebo.tracking.TrackKinematics
import com.gusak.lab.radarnebo.tracking.TrajectoryEstimator
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Real alerts.in.ua history through the tracker: every drone and missile alert open on 2026-10-26, taken from
 * the server's archive. Replayed a minute at a time as the poller would have seen it, then every track is held to
 * the realism rules of `docs/trajectory-tracking.md` §4. There is no truth here; the simulation replay scores
 * accuracy, this one checks that real data does not produce impossible tracks.
 */
class RealAlertsReplayTest {
    @Serializable
    private class Archived(val finishedAt: Long? = null, val alert: Alert)

    private val minute = 60_000L
    private val dayStart = Instant.parse("2026-10-26T00:00:00Z").toEpochMilliseconds()

    private val archived: List<Archived> by lazy {
        javaClass.getResourceAsStream("/alerts-in-ua-drones-2026-10-26.jsonl")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .map { RadarNeboApi.json.decodeFromString(Archived.serializer(), it) }
    }

    private fun Alert.startMs() = startedAt?.let { Instant.parse(it).toEpochMilliseconds() } ?: Long.MAX_VALUE

    /** The alerts open at [t], each with the threats that had started by then: what `/v1/alerts/active.json` listed. */
    private fun activeAt(t: Long): List<Alert> = archived
        .filter { it.alert.startMs() <= t && (it.finishedAt == null || it.finishedAt > t) }
        .map { a -> a.alert.copy(threats = a.alert.threats.filter { th -> th.startedAt?.let { Instant.parse(it).toEpochMilliseconds() <= t } ?: true }) }

    private val tracks: List<DroneTrack> by lazy {
        val tracker = DroneTracker()
        val seen = LinkedHashMap<String, DroneTrack>()
        for (t in dayStart until dayStart + 24 * 60 * minute step minute) {
            tracker.update(ThreatMapper.toMarkers(activeAt(t), dronesOnly = false), t)
            tracker.snapshot(t).forEach { seen[it.id] = TrajectoryEstimator.enrich(it, t) }
        }
        seen.values.toList()
    }

    @Test
    fun aRealDayOfAlertsMakesOnlyFlyableTracks() {
        val drones = tracks.filter { it.type == ThreatType.Drones }
        val moving = drones.filter { it.samples.size >= 2 }
        println(
            "Real 2026-10-26: ${archived.size} alerts, ${drones.size} drone tracks, ${moving.size} with 2+ reports, " +
                "longest ${drones.maxOfOrNull { it.samples.size }} reports",
        )
        assertTrue(moving.isNotEmpty(), "a day of drone alerts links some reports into routes")
        drones.forEach { track ->
            val k = TrackKinematics.of(track)
            val flown = track.samples.zipWithNext { a, b -> GeoMath.distanceKm(a.point, b.point) }.sum()
            assertTrue(flown <= k.rangeKm, "${track.id} flew $flown km")
            // The smoothed path never implies a speed the kind cannot fly (25 % slack for the curve).
            track.path.zipWithNext().filter { (a, b) -> b.t - a.t >= minute }.forEach { (a, b) ->
                val speed = GeoMath.distanceKm(a.point, b.point) / ((b.t - a.t) / 3_600_000.0)
                assertTrue(speed <= k.maxSpeedKmh * 1.25, "${track.id} path implies $speed km/h")
            }
            track.estimate?.speedKmh?.let { assertTrue(it in k.minSpeedKmh - 1e-6..k.maxSpeedKmh + 1e-6, "${track.id} estimated at $it km/h") }
            // No doubling back between consecutive legs unless the kind loiters.
            if (!k.loiters) {
                track.samples.windowed(3).forEach { (a, b, c) ->
                    if (GeoMath.distanceKm(a.point, b.point) < 1.0 || GeoMath.distanceKm(b.point, c.point) < 1.0) return@forEach
                    val turn = abs(((GeoMath.bearingDeg(b.point, c.point) - GeoMath.bearingDeg(a.point, b.point) + 540.0) % 360.0) - 180.0)
                    assertTrue(turn <= 150.0, "${track.id} reverses by $turn° at ${b.title}")
                }
            }
        }
    }

    @Test
    fun noDroneTrackStartsInlandWhileALostOneCouldHaveFlownThere() {
        val drones = tracks.filter { it.type == ThreatType.Drones }
        val window = 2 * TrackKinematics.Drones.reacquireWindowMs
        fun bearingTurn(from: Double, to: Double) = abs(((to - from + 540.0) % 360.0) - 180.0)
        val inlandStarts = drones.filter { it.samples.first().oblastUid in Regions.INLAND_OBLAST_UIDS }
        val violations = inlandStarts.filter { track ->
            val first = track.samples.first()
            drones.any { lost ->
                val last = lost.samples.last()
                val km = GeoMath.distanceKm(last.point, first.point)
                val k = TrackKinematics.of(lost)
                val reverses = !k.loiters && listOfNotNull(
                    lost.samples.getOrNull(lost.samples.lastIndex - 1)?.let { GeoMath.bearingDeg(it.point, last.point) },
                    lost.estimate?.headingDeg,
                ).any { bearingTurn(it, GeoMath.bearingDeg(last.point, first.point)) > 150.0 }
                // Tracks born in the same update are never linked: reports appearing together are separate groups.
                lost.id != track.id && lost.firstSeen != track.firstSeen && lost.kind == track.kind && lost.lastSeen < first.t && first.t - lost.lastSeen <= window &&
                    km <= TrackKinematics.Drones.maxHopKm * 1.5 && km <= k.maxSpeedKmh * 1.5 * (first.t - last.t) / 3_600_000.0 + 25.0 &&
                    !reverses
            }
        }
        println("Real 2026-10-26: ${inlandStarts.size} drone tracks start inland, ${violations.size} while a lost one could have flown there")
        assertTrue(violations.isEmpty(), "started inland next to a lost track: ${violations.map { it.id to it.samples.first().title }}")
    }
}
