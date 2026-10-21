package com.gusak.lab.radarnebo

import com.gusak.lab.radarnebo.data.DroneSimulator
import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.domain.ThreatMarker
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.DroneTracker
import com.gusak.lab.radarnebo.tracking.TargetClassifier
import com.gusak.lab.radarnebo.tracking.TargetKind
import com.gusak.lab.radarnebo.tracking.TargetProfile
import com.gusak.lab.radarnebo.tracking.TrackInterpolator
import com.gusak.lab.radarnebo.tracking.TrackKinematics
import com.gusak.lab.radarnebo.tracking.TrackSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TargetTypesTest {
    private val minute = 60_000L

    private fun marker(id: String, lat: Double, lon: Double, type: ThreatType = ThreatType.Drones, message: String? = null, location: String = "hromada") =
        ThreatMarker(
            id = id, type = type, level = ThreatLevel.Red, title = id, oblastUid = 14, oblastName = "Kyiv Oblast",
            locationType = location, point = GeoPoint(lat, lon), startedAt = null, message = message,
        )

    @Test
    fun everyKindHasOneProfile() {
        assertEquals(TargetKind.entries, TargetClassifier.all.map { it.kind })
    }

    @Test
    fun everyThreatClassGetsAProfileAndNamedModelsAreReported() {
        fun kind(type: ThreatType, text: String = "") = TargetClassifier.classify(type, listOf(text)).kind
        assertEquals(TargetKind.CruiseMissile, kind(ThreatType.CruiseMissiles, DroneSimulator.KH101_MESSAGE))
        assertEquals(TargetKind.CruiseMissile, kind(ThreatType.CruiseMissiles, DroneSimulator.KALIBR_MESSAGE))
        assertEquals(TargetKind.BallisticMissile, kind(ThreatType.BallisticMissiles, "Загроза балістичних ракет «Іскандер-М»"))
        assertEquals(TargetKind.AeroballisticMissile, kind(ThreatType.BallisticMissiles, "Ракета «Кинджал» на Київ"))
        assertEquals(TargetKind.GuidedBomb, kind(ThreatType.GuidedBombs))
        assertEquals(TargetKind.TacticalAircraft, kind(ThreatType.TacticalAviation))
        assertEquals(TargetKind.StrategicBomber, kind(ThreatType.StrategicAviation))
        assertEquals(TargetKind.MiG31K, kind(ThreatType.Mig31k))
        assertEquals(TargetKind.AirDefense, kind(ThreatType.AirDefense))
        assertEquals(TargetKind.Unknown, kind(ThreatType.Unknown))
        assertEquals(TargetKind.ReconDrone, kind(ThreatType.Drones, DroneSimulator.RECON_MESSAGE))
        assertEquals(TargetKind.JetDrone, kind(ThreatType.Drones, DroneSimulator.JET_SHAHED_MESSAGE))

        assertEquals(TargetProfile.Source.Reported, TargetClassifier.classify(ThreatType.GuidedBombs, listOf("Пуски КАБ")).source)
        assertEquals(TargetProfile.Source.Probable, TargetClassifier.classify(ThreatType.GuidedBombs, listOf("Загроза авіабомб")).source)
        assertEquals(TargetProfile.Source.Probable, TargetClassifier.classify(ThreatType.CruiseMissiles, emptyList()).source)
    }

    @Test
    fun simulationCoversEveryThreatClass() {
        // Three hours of the simulated feed, sampled every minute.
        val start = 1_800_000_000_000L
        val seen = (0 until 180).flatMap { m -> DroneSimulator.alertsAt(start + m * minute) }
            .flatMap { it.threats }.map { ThreatType.from(it.threatType) }.toSet()
        assertEquals(ThreatType.entries.toSet() - ThreatType.Unknown, seen)
        val kinds = (0 until 180).flatMap { m -> ThreatMapper.toMarkers(DroneSimulator.alertsAt(start + m * minute), dronesOnly = false) }
            .map { TargetClassifier.classify(it).kind }.toSet()
        assertEquals(TargetKind.entries.toSet() - TargetKind.Unknown, kinds, "the sample feed shows every icon but Unknown")
    }

    @Test
    fun cruiseMissilesAreTrackedOnTheirOwnWithLongerHops() {
        val tracker = DroneTracker()
        // Kyiv, then 250 km west: too far for a drone group in a few minutes, a normal hop for a cruise missile.
        tracker.update(listOf(marker("d", 50.45, 30.52), marker("m", 50.45, 30.52, ThreatType.CruiseMissiles)), 0)
        tracker.update(listOf(marker("d", 50.45, 30.52), marker("m2", 50.62, 26.9, ThreatType.CruiseMissiles)), 5 * minute)
        val tracks = tracker.snapshot()
        assertEquals(2, tracks.size, "a drone and a missile at the same place are different targets")
        val missile = tracks.single { it.type == ThreatType.CruiseMissiles }
        assertEquals(2, missile.samples.size)
        assertEquals(TargetKind.CruiseMissile, TargetClassifier.classify(missile).kind)
    }

    @Test
    fun differentDroneKindsDoNotMerge() {
        val tracker = DroneTracker()
        tracker.update(listOf(marker("shahed", 50.5, 30.8, message = DroneSimulator.SHAHED_MESSAGE)), 0)
        tracker.update(
            listOf(
                marker("shahed", 50.5, 30.8, message = DroneSimulator.SHAHED_MESSAGE),
                marker("gerbera", 50.45, 30.5, message = DroneSimulator.GERBERA_MESSAGE),
            ),
            10 * minute,
        )
        assertEquals(2, tracker.snapshot().size)
    }

    @Test
    fun areaOnlyThreatsAreNotTracked() {
        val tracker = DroneTracker()
        tracker.update(
            listOf(
                marker("b", 50.0, 36.2, ThreatType.BallisticMissiles),
                marker("k", 49.7, 37.6, ThreatType.GuidedBombs),
                marker("ad", 50.45, 30.52, ThreatType.AirDefense),
            ),
            0,
        )
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun missilePositionsUseMissileSpeedsAndShortDeadReckoning() {
        val track = DroneTrack(
            id = "m", type = ThreatType.CruiseMissiles, level = ThreatLevel.Red, oblastUid = null, active = true,
            firstSeen = 0, lastSeen = 5 * minute, markerIds = listOf("x"),
            // ~70 km in 5 minutes: about 840 km/h.
            samples = listOf(TrackSample(0, 50.0, 30.0, "A"), TrackSample(5 * minute, 50.0, 30.98, "B")),
        )
        val p = TrackInterpolator.positionAt(track, 6 * minute)!!
        assertTrue(p.speedKmh!! in 800.0..900.0, "not capped at the drone limit: ${p.speedKmh}")
        val stale = TrackInterpolator.positionAt(track, 60 * minute)!!
        val maxKm = TrackKinematics.CruiseMissiles.maxSpeedKmh * TrackKinematics.CruiseMissiles.maxExtrapolationMs / 3_600_000.0
        assertTrue(stale.lon - 30.98 < maxKm / 70.0, "dead reckoning stops after a few minutes")
        assertTrue(!TrackInterpolator.isVisible(track.copy(active = false), 5 * minute + TrackKinematics.CruiseMissiles.maxExtrapolationMs + 1))
    }

    @Test
    fun jetDronesAreNotCappedAtShahedSpeed() {
        val track = DroneTrack(
            id = "j", level = ThreatLevel.Red, oblastUid = null, active = true, firstSeen = 0, lastSeen = 10 * minute, markerIds = listOf("x"),
            // ~70 km in 10 minutes: about 420 km/h, named as a jet Shahed.
            samples = listOf(
                TrackSample(0, 50.0, 30.0, "A", message = DroneSimulator.JET_SHAHED_MESSAGE),
                TrackSample(10 * minute, 50.0, 30.98, "B", message = DroneSimulator.JET_SHAHED_MESSAGE),
            ),
        )
        assertEquals(TrackKinematics.JetDrones, TrackKinematics.of(track))
        assertTrue(TrackInterpolator.positionAt(track, 11 * minute)!!.speedKmh!! > 400.0)
        val shahed = track.copy(samples = track.samples.map { it.copy(message = DroneSimulator.SHAHED_MESSAGE) })
        assertEquals(TrackInterpolator.MAX_SPEED_KMH, TrackInterpolator.positionAt(shahed, 11 * minute)!!.speedKmh!!, 1e-9)
    }
}
