package com.gusak.lab.radarnebo

import com.gusak.lab.radarnebo.data.DroneSimulator
import com.gusak.lab.radarnebo.tracking.DroneClassifier
import com.gusak.lab.radarnebo.tracking.DroneProfile
import com.gusak.lab.radarnebo.tracking.TrackInterpolator
import com.gusak.lab.radarnebo.tracking.TrackMetrics
import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.DroneTracker
import com.gusak.lab.radarnebo.tracking.Place
import com.gusak.lab.radarnebo.tracking.TrackPredictor
import com.gusak.lab.radarnebo.tracking.TrackSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PredictionTest {
    private val minute = 60_000L

    /** A group reported east of Kyiv 10 minutes apart, flying due west at ~150 km/h. */
    private fun westbound(active: Boolean = true) = DroneTrack(
        id = "t", level = ThreatLevel.Red, oblastUid = 14, active = active, firstSeen = 0, lastSeen = 10 * minute,
        markerIds = listOf("m"),
        samples = listOf(
            TrackSample(0, 50.45, 31.40, "A", 14),
            TrackSample(10 * minute, 50.45, 31.05, "B", 14),
        ),
    )

    @Test
    fun predictsNextCityAheadWithEta() {
        val p = assertNotNull(TrackPredictor.predict(westbound(), 10 * minute))
        // Brovary lies on the way to Kyiv, so it is the next settlement.
        assertEquals("Brovary", p.place.name)
        assertTrue(p.distanceKm in 15.0..25.0, "distance ${p.distanceKm}")
        // ~25 km in 10 min = ~150 km/h, so ~19 km takes ~8 min.
        assertTrue(p.etaMinutes in 6..10, "eta ${p.etaMinutes}")
    }

    @Test
    fun ignoresPlacesBehindTooFarOrOffTheCorridor() {
        val behind = Place("Behind", GeoPoint(50.45, 31.50))
        val far = Place("Far", GeoPoint(50.45, 30.0))
        val aside = Place("Aside", GeoPoint(50.75, 30.85))
        assertNull(TrackPredictor.predict(westbound(), 10 * minute, listOf(behind, far, aside)))
    }

    @Test
    fun favoritesCanBeTheTarget() {
        val home = Place("Home", GeoPoint(50.46, 30.85), isFavorite = true)
        val p = assertNotNull(TrackPredictor.predict(westbound(), 10 * minute, TrackPredictor.cityPlaces + home))
        assertEquals("Home", p.place.name, "the first place along the path wins")
    }

    @Test
    fun placeOnThePathBeatsAnEarlierOneOffToTheSide() {
        // Boryspil comes first along the path but ~11 km aside; Brovary is almost on the line.
        val boryspil = Place("Boryspil", GeoPoint(50.3527, 30.9550))
        val brovary = Place("Brovary", GeoPoint(50.5110, 30.7909))
        assertEquals("Brovary", TrackPredictor.predict(westbound(), 10 * minute, listOf(boryspil, brovary))?.place?.name)
        // With nothing on the line, the wider corridor still finds the town the drone passes.
        assertEquals("Boryspil", TrackPredictor.predict(westbound(), 10 * minute, listOf(boryspil))?.place?.name)
    }

    @Test
    fun noPredictionForClearedOrSinglePointTracks() {
        assertNull(TrackPredictor.predict(westbound(active = false), 10 * minute))
        val single = westbound().let { it.copy(samples = it.samples.take(1)) }
        assertNull(TrackPredictor.predict(single, 10 * minute))
    }

    @Test
    fun predictedRouteListsSettlementsAheadInOrder() {
        val route = TrackPredictor.route(westbound(), 10 * minute)
        assertTrue(route.size >= 2, "route: ${route.map { it.place.name }}")
        assertEquals("Brovary", route.first().place.name)
        assertTrue("Kyiv" in route.map { it.place.name })
        assertTrue(route.zipWithNext().all { (x, y) -> y.etaMinutes >= x.etaMinutes }, "ETAs increase along the route")
    }
}

class SettlementsTest {
    /** Catches coordinate typos: each settlement must fall inside its oblast's (simplified) polygon. */
    @Test
    fun everySettlementLiesInItsOblast() {
        // Right on a border or river, where the 0.02° simplified outline is off by a few km.
        val knownBorderTowns = setOf("Kherson", "Zhovti Vody")
        val wrong = Regions.cities
            .filter { it.oblastUid !in setOf(Regions.KYIV_CITY_UID, Regions.SEVASTOPOL_UID) && it.name !in knownBorderTowns }
            .filter { Regions.oblastByPolygon(it.point) != it.oblastUid }
            .map { "${it.name}: expected ${it.oblastUid}, found ${Regions.oblastByPolygon(it.point)}" }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun borderTownsResolveToTheirOwnOblast() {
        assertEquals(23, Regions.oblastAt(GeoPoint(46.6354, 32.6169)), "Kherson")
        assertEquals(9, Regions.oblastAt(GeoPoint(48.3456, 33.5023)), "Zhovti Vody")
    }

    @Test
    fun sameNameInAnotherOblastIsNotConfused() {
        // "Золочів" exists in Kharkiv and Lviv oblasts.
        assertEquals("Zolochiv", Regions.cityForTitle("Золочівська громада", 22)?.name)
        assertNull(Regions.cityForTitle("Золочівська громада", 27))
    }
}

class SimulationTest {
    private val minute = 60_000L

    @Test
    fun warmUpGivesTracksWithHistoryRealisticSpeedAndModel() {
        val tracker = DroneTracker()
        val now = 1_800_000_000_000L
        DroneSimulator.warmUp(tracker, now, minutes = 120)
        val moving = tracker.snapshot().filter { it.active && it.samples.size >= 3 }
        assertTrue(moving.isNotEmpty(), "some groups are mid-route with history")
        moving.forEach { track ->
            assertTrue(track.samples.zipWithNext().all { (a, b) -> b.t > a.t }, "timestamps increase")
            val (_, speed) = TrackInterpolator.motion(track.samples[track.samples.size - 2], track.samples.last())
            assertTrue(speed in 140.0..190.0, "realistic speed for ${track.samples.map { it.title }}: $speed")
            assertEquals(DroneProfile.Source.Reported, DroneClassifier.classify(track).source)
        }
    }

    @Test
    fun simulationIsContinuousInTime() {
        // A group's alert stays put for the minutes it takes to fly a leg, then hands over to the next town.
        val now = 1_800_000_000_000L
        val a = DroneSimulator.alertsAt(now).map { it.id }.toSet()
        val b = DroneSimulator.alertsAt(now + minute).map { it.id }.toSet()
        assertTrue(a.intersect(b).size >= a.size / 2, "one minute later most alerts are unchanged")
    }
}

class ProfileTest {
    @Test
    fun modelComesFromAlertTextOrIsMarkedProbable() {
        assertEquals("Gerbera", DroneClassifier.classify(listOf(DroneSimulator.GERBERA_MESSAGE)).model)
        assertEquals("Shahed-136 / Geran-2", DroneClassifier.classify(listOf("Ударні БпЛА типу Шахед")).model)
        assertEquals("Shahed-238 / Geran-3", DroneClassifier.classify(listOf("Реактивний шахед на Київ")).model)
        val unnamed = DroneClassifier.classify(listOf("Загроза ударних БпЛА"))
        assertEquals(DroneProfile.Source.Probable, unnamed.source)
        assertEquals("Shahed-136 / Geran-2", unnamed.model)
    }

    @Test
    fun distanceFlownSumsReportedLegs() {
        val track = DroneTrack(
            id = "t", level = ThreatLevel.Red, oblastUid = null, active = false, firstSeen = 0, lastSeen = 0, markerIds = emptyList(),
            samples = listOf(
                TrackSample(0, 50.0, 30.0, "A"),
                TrackSample(1, 50.0, 31.0, "B"),
                TrackSample(2, 51.0, 31.0, "C"),
            ),
        )
        // ~71.5 km east at 50°N, then ~111 km north.
        assertEquals(71.5 + 111.2, TrackMetrics.distanceFlownKm(track), 1.5)
    }
}
