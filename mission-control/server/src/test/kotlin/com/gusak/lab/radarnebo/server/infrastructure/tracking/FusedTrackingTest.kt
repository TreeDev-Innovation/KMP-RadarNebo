package com.gusak.lab.radarnebo.server.infrastructure.tracking

import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.server.Fixtures
import com.gusak.lab.radarnebo.server.domain.tracking.TargetObservation
import com.gusak.lab.radarnebo.tracking.SamplePrecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class FusedTrackingTest {
    private val t0 = Fixtures.t0.toEpochMilliseconds()
    private val minute = 60_000L

    private fun sighting(at: Long, point: GeoPoint) = TargetObservation(
        targetId = "radar-7", type = ThreatType.Drones, observedAt = at, position = point, message = "Ударні БпЛА типу «Шахед»",
    )

    @Test
    fun anAlertAndASightingOfOneTargetMakeOneTrack() {
        val tracking = FusedTracking("t")
        val brovary = GeoPoint(50.511, 30.7909)
        // The alert for Brovary, and radar sighting the group 8 km short of it.
        tracking.update(listOf(Fixtures.alert(1, Fixtures.brovary)), listOf(sighting(t0, GeoMath.destination(brovary, 90.0, 8.0))), t0)
        val track = tracking.update(
            listOf(Fixtures.alert(1, Fixtures.brovary)),
            listOf(sighting(t0 + 10 * minute, GeoMath.destination(brovary, 270.0, 22.0))),
            t0 + 10 * minute,
        ).tracks.single()
        // Reported together and overlapping, the alert and the sighting are one report: the precise one is kept.
        assertEquals(listOf(SamplePrecision.Exact, SamplePrecision.Exact), track.samples.map { it.precision })
        assertTrue("1:drones" in track.markerIds, "the alert stays attached, so its marker is not drawn twice")
    }

    @Test
    fun sightingsBetweenAlertsKeepTheTrackOnItsCourse() {
        val tracking = FusedTracking("t")
        val start = GeoPoint(50.5, 32.0)
        // Sighted every two minutes flying west at 180 km/h for half an hour; no alerts at all.
        val tracks = (0..15).map { i ->
            val t = t0 + i * 2 * minute
            tracking.update(emptyList(), listOf(sighting(t, GeoMath.destination(start, 270.0, 180.0 * i * 2 / 60))), t).tracks
        }.last()
        val track = tracks.single()
        assertTrue(track.samples.size >= 15, "one sample per sighting interval, got ${track.samples.size}")
        assertEquals(Fixtures.t0.plus(30.minutes).toEpochMilliseconds(), track.lastSeen)
    }
}
