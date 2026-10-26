package com.gusak.lab.radarnebo.server.domain

import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.server.domain.tracking.ObservationTracking
import com.gusak.lab.radarnebo.server.domain.tracking.PlaceNamer
import com.gusak.lab.radarnebo.server.domain.tracking.TargetObservation
import com.gusak.lab.radarnebo.tracking.TrackOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservationTrackingTest {
    private val second = 1_000L
    private val minute = 60_000L

    /** A Shahed flying east to west along 50.4°N at ~180 km/h, seen every 10 seconds. */
    private fun sighting(t: Long, outcome: TrackOutcome? = null, id: String = "d1") = TargetObservation(
        targetId = id,
        type = ThreatType.Drones,
        observedAt = t,
        position = GeoPoint(50.4, 31.5 - t / minute * 0.042),
        message = "Ударні БпЛА типу «Шахед»",
        outcome = outcome,
        alertMarkerIds = if (outcome == null) listOf("42:drones") else emptyList(),
    )

    @Test
    fun theNewestSightingIsAlwaysTheLastSampleOlderOnesAreSpacedOut() {
        val tracking = ObservationTracking()
        var update = tracking.update(emptyList(), emptyList(), 0)
        for (t in 0..10 * minute step 10 * second) update = tracking.update(emptyList(), listOf(sighting(t)), t)
        val track = update.tracks.single()
        assertEquals(10 * minute, track.samples.last().t, "the latest position is served")
        track.samples.dropLast(1).zipWithNext().forEach { (a, b) -> assertTrue(b.t - a.t >= minute, "history is a sample a minute") }
        assertEquals(11, track.samples.size)
        assertTrue(track.active && track.outcome == null)
        assertEquals(listOf("42:drones"), track.markerIds)
        assertEquals(14, track.oblastUid, "over Kyiv oblast")
    }

    @Test
    fun aTrackEndsWithItsOutcomeOrIsLostWithoutSightings() {
        val tracking = ObservationTracking(contactTimeoutMs = 5 * minute, endedRetentionMs = 15 * minute)
        tracking.update(emptyList(), listOf(sighting(0), sighting(0, id = "d2")), 0)
        val shotDown = tracking.update(emptyList(), listOf(sighting(2 * minute, TrackOutcome.Intercepted), sighting(2 * minute, id = "d2")), 2 * minute)
        assertEquals(listOf("d1"), shotDown.ended.map { it.id })
        val d1 = shotDown.tracks.single { it.id == "d1" }
        assertEquals(TrackOutcome.Intercepted, d1.outcome)
        assertTrue(!d1.active && d1.markerIds.isEmpty())

        // Later sightings of an ended track are ignored.
        tracking.update(emptyList(), listOf(sighting(3 * minute, id = "d1")), 3 * minute)
        val lost = tracking.update(emptyList(), emptyList(), 8 * minute)
        assertEquals(listOf("d2"), lost.ended.map { it.id }, "no sighting for over 5 minutes")
        assertEquals(TrackOutcome.Lost, lost.tracks.single { it.id == "d2" }.outcome)
        assertEquals(2 * minute, lost.tracks.single { it.id == "d1" }.lastSeen)

        assertTrue(tracking.update(emptyList(), emptyList(), 30 * minute).tracks.isEmpty(), "ended tracks are dropped after 15 minutes")
    }

    @Test
    fun longFlightsAreThinnedButKeepTheirStartAndLatestPosition() {
        val tracking = ObservationTracking(maxSamples = 20)
        var update = tracking.update(emptyList(), emptyList(), 0)
        for (t in 0..120 * minute step minute) update = tracking.update(emptyList(), listOf(sighting(t)), t)
        val samples = update.tracks.single().samples
        assertTrue(samples.size <= 20, "${samples.size} samples")
        assertEquals(0, samples.first().t)
        assertEquals(120 * minute, samples.last().t)
        assertTrue(samples.zipWithNext().all { (a, b) -> b.t > a.t })
    }

    @Test
    fun placesAreNamedAfterTheNearestCity() {
        assertEquals("Kyiv", PlaceNamer.NearestCity.describe(GeoPoint(50.4501, 30.5234)))
        val countryside = PlaceNamer.NearestCity.describe(GeoPoint(51.0, 33.2))
        assertTrue(Regex("""\d+ km (N|NE|E|SE|S|SW|W|NW) of .+""").matches(countryside), countryside)
    }
}
