package com.gusak.lab.radarnebo

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.domain.ThreatMarker
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.DroneTracker
import com.gusak.lab.radarnebo.tracking.PositionMode
import com.gusak.lab.radarnebo.tracking.TrackInterpolator
import com.gusak.lab.radarnebo.tracking.TrackSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrackingTest {
    private val minute = 60_000L

    private fun marker(id: String, lat: Double, lon: Double, type: String = "raion") = ThreatMarker(
        id = id, type = ThreatType.Drones, level = ThreatLevel.Red, title = id, oblastUid = 14, oblastName = "Kyiv Oblast",
        locationType = type, point = GeoPoint(lat, lon), startedAt = null, message = null,
    )

    private fun track(vararg samples: Pair<Long, GeoPoint>, active: Boolean = true) = DroneTrack(
        id = "t", level = ThreatLevel.Red, oblastUid = null, active = active, firstSeen = 0, lastSeen = samples.last().first,
        markerIds = emptyList(), samples = samples.map { TrackSample(it.first, it.second.lat, it.second.lon, "") },
    )

    @Test
    fun interpolatesLinearlyBetweenSamples() {
        val t = track(0L to GeoPoint(50.0, 30.0), 20 * minute to GeoPoint(50.4, 31.0))
        val mid = TrackInterpolator.positionAt(t, 5 * minute)!!
        assertEquals(PositionMode.Interpolated, mid.mode)
        assertEquals(50.1, mid.lat, 1e-9)
        assertEquals(30.25, mid.lon, 1e-9)
        assertTrue(mid.headingDeg!! in 45.0..90.0, "north-east heading, got ${mid.headingDeg}")
    }

    @Test
    fun extrapolationIsCappedInSpeedTimeAndDistance() {
        // 100 km covered in one minute is unrealistic; extrapolation must use the speed cap instead.
        val a = GeoPoint(50.0, 30.0)
        val b = GeoPoint(50.9, 30.0)
        val t = track(0L to a, minute to b)
        val p = TrackInterpolator.positionAt(t, minute + 5 * minute)!!
        assertEquals(PositionMode.Extrapolated, p.mode)
        val travelled = GeoMath.distanceKm(b, p.point)
        assertEquals(TrackInterpolator.MAX_SPEED_KMH * 5 / 60, travelled, 0.5)

        // Dead reckoning stops after MAX_EXTRAPOLATION_MS: at most 220 km/h for 30 minutes.
        val muchLater = TrackInterpolator.positionAt(t, minute + 600 * minute)!!
        val maxKm = TrackInterpolator.MAX_SPEED_KMH * TrackInterpolator.MAX_EXTRAPOLATION_MS / 3_600_000.0
        assertEquals(maxKm, GeoMath.distanceKm(b, muchLater.point), 1.0)

        val ended = TrackInterpolator.positionAt(t.copy(active = false), 3 * minute)!!
        assertEquals(PositionMode.Held, ended.mode)
        assertEquals(b, ended.point)
    }

    @Test
    fun trackerFollowsThreatMovingToNeighbouringArea() {
        val tracker = DroneTracker()
        tracker.update(listOf(marker("a", 50.5, 30.8)), 0)
        // The next area lights up while the first one is still active, then the first clears.
        tracker.update(listOf(marker("a", 50.5, 30.8), marker("b", 50.45, 30.5)), 10 * minute)
        tracker.update(listOf(marker("b", 50.45, 30.5)), 12 * minute)
        val track = tracker.snapshot().single()
        assertEquals(2, track.samples.size)
        assertEquals(listOf("b"), track.markerIds)
        assertTrue(track.active)
    }

    @Test
    fun simultaneousAlertsStartSeparateTracks() {
        val tracker = DroneTracker()
        tracker.update(listOf(marker("brovary", 50.6, 31.0), marker("boryspil", 50.3, 31.0)), 0)
        assertEquals(2, tracker.snapshot().size, "every alert is its own report")
    }

    @Test
    fun distantAlertsRaisedTogetherAreSeparateGroups() {
        val tracker = DroneTracker(maxHopKm = 120.0)
        tracker.update(listOf(marker("a", 50.5, 30.8), marker("far", 46.48, 30.72)), 0)
        assertEquals(2, tracker.snapshot().size, "Odesa is too far from Kyiv to be the same group")
    }

    @Test
    fun trendFollowsTheCourseThroughCoarsePositions() {
        // Positions zig-zag north and south of a westward course, as raion centres do.
        val t = track(
            0L to GeoPoint(50.5, 32.0),
            15 * minute to GeoPoint(50.3, 31.6),
            30 * minute to GeoPoint(50.5, 31.2),
            45 * minute to GeoPoint(50.3, 30.8),
        )
        val (heading, speed) = TrackInterpolator.trend(t.samples)!!
        assertTrue(heading in 255.0..285.0, "westward, got $heading")
        assertTrue(speed in 90.0..130.0, "about 115 km/h, got $speed")
    }

    @Test
    fun clearedTracksAreKeptForReacquisitionThenPruned() {
        val tracker = DroneTracker(reacquireWindowMs = 30 * minute, retentionMs = 60 * minute)
        tracker.update(listOf(marker("a", 50.5, 30.8)), 0)
        tracker.update(emptyList(), minute)
        assertFalse(tracker.snapshot().single().active)
        tracker.update(listOf(marker("b", 50.6, 31.2)), 10 * minute)
        assertEquals(2, tracker.snapshot().single().samples.size, "reacquired within the window")
        tracker.update(emptyList(), 11 * minute)
        tracker.update(emptyList(), 80 * minute)
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun reAlertInTheSamePlaceRefreshesWithoutAddingAPoint() {
        val tracker = DroneTracker()
        tracker.update(listOf(marker("a", 50.5, 30.8)), 0)
        tracker.update(listOf(marker("a2", 50.5, 30.8)), 20 * minute)
        val track = tracker.snapshot().single()
        assertEquals(1, track.samples.size)
        assertEquals(listOf("a2"), track.markerIds)
        assertEquals(20 * minute, track.lastSeen)
    }

    @Test
    fun regionWideAlertsAreNotTracked() {
        val tracker = DroneTracker()
        tracker.update(listOf(marker("oblast", 50.3, 30.4, type = "oblast")), 0)
        assertTrue(tracker.snapshot().isEmpty())
        assertNotNull(TrackInterpolator.positionAt(track(0L to GeoPoint(1.0, 1.0)), 5))
    }
}
