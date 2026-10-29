package com.gusak.lab.radarnebo.server.infrastructure.simulation

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.server.application.SituationReport
import com.gusak.lab.radarnebo.server.config.SimulationConfig
import com.gusak.lab.radarnebo.server.contract.AlertsInUaContract.toDto
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.tracking.ObservationTracking
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.TargetClassifier
import com.gusak.lab.radarnebo.tracking.TargetKind
import com.gusak.lab.radarnebo.tracking.TargetProfile
import com.gusak.lab.radarnebo.tracking.TrackInterpolator
import com.gusak.lab.radarnebo.tracking.TrackOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SimulationTest {
    private val config = SimulationConfig()
    private val period = config.strikePeriod.inWholeMilliseconds
    private val minute = 60_000L

    /** The start of a strike that uses every weapon; three in ten MiG-31K take-offs launch no Kinzhal. */
    private val strikeStart = generateSequence(1_800_000_000_000L / period) { it + 1 }
        .first { slot -> CombinedStrikePlanner(config.seed).plan(slot, slot * period).flights.any { it.munition == Munition.Kinzhal } } * period
    private val strike = CombinedStrikePlanner(config.seed).plan(strikeStart / period, strikeStart)

    /** One strike period of the simulated feed from [strikeStart], one report a minute. */
    private val reports: List<SituationReport> by lazy {
        val simulator = AirSituationSimulator(config, firstAlertId = 1)
        (0 until period / minute).map { simulator.reportAt(strikeStart + it * minute) }
    }

    @Test
    fun plansAreAPureFunctionOfSeedAndSlot() {
        val again = CombinedStrikePlanner(config.seed).plan(strikeStart / period, strikeStart)
        assertEquals(strike.flights.map { it.id to it.endAt }, again.flights.map { it.id to it.endAt })
        val other = CombinedStrikePlanner(config.seed + 1).plan(strikeStart / period, strikeStart)
        assertTrue(other.flights.map { it.endAt } != strike.flights.map { it.endAt }, "another seed plans another strike")
    }

    @Test
    fun aCombinedStrikeUsesDronesCruiseAndBallisticMissilesAtRealisticSpeeds() {
        val byMunition = strike.flights.groupBy { it.munition }
        assertTrue(byMunition.getValue(Munition.Shahed).size >= 8, "Shahed groups make up the bulk")
        assertTrue(byMunition.getValue(Munition.Kh101).size >= 8, "a Kh-101 salvo")
        assertTrue(byMunition.getValue(Munition.Kalibr).size >= 3, "Kalibrs from the Black Sea")
        assertTrue(byMunition.keys.any { it == Munition.IskanderM || it == Munition.Kn23 }, "ballistic missiles")
        strike.flights.forEach { flight ->
            assertTrue(flight.path.speedKmh in flight.munition.speedKmh, "${flight.id} flies at ${flight.path.speedKmh} km/h")
        }
        // Drones take hours, cruise missiles about one to two, ballistic missiles minutes (from Belgorod to Kharkiv, one).
        val hours = { m: Munition -> byMunition.getValue(m).map { it.path.durationMs / 3_600_000.0 } }
        assertTrue(hours(Munition.Shahed).all { it in 0.5..7.0 }, "Shahed flight times ${hours(Munition.Shahed)}")
        assertTrue(hours(Munition.Kh101).all { it in 0.8..2.5 }, "Kh-101 flight times ${hours(Munition.Kh101)}")
        byMunition.filterKeys { it.weaponClass == WeaponClass.BallisticMissile }.values.flatten().forEach {
            assertTrue(it.path.durationMs in minute / 2..15 * minute, "${it.id} flies ${it.path.durationMs / minute} min")
        }
        // Drones launch first, then cruise missiles, then the ballistic missiles.
        val firstLaunch = { c: WeaponClass -> strike.flights.filter { it.munition.weaponClass == c }.minOf { it.departAt } }
        assertTrue(firstLaunch(WeaponClass.Drone) < firstLaunch(WeaponClass.CruiseMissile))
        assertTrue(firstLaunch(WeaponClass.CruiseMissile) < firstLaunch(WeaponClass.BallisticMissile))
    }

    @Test
    fun launchesComeFromOutsideGovernmentControlledUkraineAndAreSeenNearTheBorder() {
        strike.flights.filter { it.munition.weaponClass != WeaponClass.GuidedBomb }.forEach { flight ->
            val launch = flight.path.waypoints.first()
            val launchOblast = Regions.oblastByPolygon(launch)
            assertTrue(launchOblast == null || launchOblast in SimulatedLocations.OCCUPIED_REGIONS || launchOblast == SimulatedLocations.DONETSK_UID, "${flight.id} launched over $launchOblast")
            if (flight.munition.weaponClass == WeaponClass.BallisticMissile) {
                assertEquals(flight.departAt, flight.detectedAt, "ballistic launches are seen at once")
            } else {
                assertTrue(StrikeGeography.inCoverage(flight.path.positionAt(flight.detectedAt)), "${flight.id} is seen inside coverage")
            }
        }
    }

    @Test
    fun mostDronesAndCruiseMissilesAreShotDownBallisticOnesLessOften() {
        val flights = (0L until 12).flatMap { CombinedStrikePlanner(config.seed).plan(it, it * period).flights }
        fun rate(vararg munitions: Munition) = flights.filter { it.munition in munitions }.let { group -> group.count { it.outcome == TrackOutcome.Intercepted }.toDouble() / group.size }
        assertTrue(rate(Munition.Shahed) in 0.7..0.92, "Shahed interception rate ${rate(Munition.Shahed)}")
        assertTrue(rate(Munition.Kh101, Munition.Kalibr) in 0.7..0.92, "cruise missile interception rate")
        assertTrue(rate(Munition.IskanderM, Munition.Kn23) < rate(Munition.Shahed), "ballistic missiles are harder to stop")
        flights.filter { it.outcome == TrackOutcome.Intercepted }.forEach { flight ->
            assertTrue(flight.endAt < flight.path.arriveAt && flight.endAt > flight.detectedAt, "${flight.id} is shot down on the way, after it was seen")
        }
        flights.filter { it.outcome == TrackOutcome.Impact }.forEach { assertEquals(it.path.arriveAt, it.endAt) }
    }

    @Test
    fun theFeedReportsEveryThreatClassAlertsInUaKnows() {
        val types = reports.flatMap { it.alerts }.flatMap { it.threats }.map { it.type }.toSet()
        assertEquals(ThreatType.entries.toSet() - ThreatType.Unknown, types)
        // Every kind of target gets its own icon in the app.
        val markerKinds = reports.flatMap { report -> ThreatMapper.toMarkers(report.alerts.map { it.toDto() }, dronesOnly = false) }
            .map { TargetClassifier.classify(it).kind }.toSet()
        assertEquals(TargetKind.entries.toSet() - TargetKind.Unknown, markerKinds)
    }

    @Test
    fun alertsFollowTheAlertsInUaLifecycle() {
        val seen = HashMap<Long, Pair<String, Instant>>()
        reports.forEach { report ->
            val now = Instant.fromEpochMilliseconds(report.observedAt)
            assertEquals(report.alerts.size, report.alerts.map { it.location.uid }.distinct().size, "one alert per location")
            report.alerts.forEach { alert ->
                assertTrue(alert.isActive && alert.startedAt <= now)
                assertTrue(alert.threats.all { it.startedAt >= alert.startedAt || alert.id < SimulatedLocations.FIRST_DYNAMIC_ALERT_ID }, "threats open within their alert")
                val previous = seen.putIfAbsent(alert.id, alert.location.uid to alert.startedAt)
                if (previous != null) assertEquals(previous, alert.location.uid to alert.startedAt, "an alert keeps its location and start")
                if (alert.threats.isNotEmpty()) {
                    val red = alert.threats.any { it.level == ThreatLevel.Red }
                    assertEquals(if (red) ThreatLevel.Red else ThreatLevel.Yellow, alert.level)
                }
            }
        }
        // Luhansk oblast and Crimea are on alert throughout, as they really are.
        assertTrue(reports.all { report -> report.alerts.any { it.location.uid == "16" } && report.alerts.any { it.location.uid == "29" } })
        // A massive strike puts the whole country under alert at some point.
        assertTrue(reports.any { report -> SimulatedLocations.freeRegionUids.all { uid -> report.alerts.any { it.location.uid == uid.toString() } } })
    }

    @Test
    fun everyAlertedSettlementIsLocatedByTheApp() {
        SimulatedLocations.settlements.forEach { settlement ->
            val alert = AirAlert(id = 1, location = settlement.location, startedAt = Instant.fromEpochMilliseconds(0))
            val point = ThreatMapper.locate(alert.toDto())!!
            assertTrue(GeoMath.distanceKm(point, settlement.city.point) < 1.0, "${settlement.location.title} is located at ${settlement.city.name}")
        }
    }

    @Test
    fun ballisticMissilesAlertTheirTargetRegionFromLaunch() {
        val simulator = AirSituationSimulator(config, firstAlertId = 1)
        val missile = strike.flights.first { it.munition.weaponClass == WeaponClass.BallisticMissile }
        var t = missile.departAt - 10 * minute
        while (t < missile.departAt) { simulator.reportAt(t); t += minute }
        val report = simulator.reportAt(missile.departAt + 30_000)
        val region = report.alerts.single { it.location.uid == missile.targetOblastUid.toString() }
        assertTrue(region.threats.any { it.type == ThreatType.BallisticMissiles && it.sourceMessage == missile.message })
    }

    @Test
    fun tracksFollowTheFlightsAtTheirSpeedsAndEndHowTheyEnded() {
        val tracking = ObservationTracking()
        var tracks = emptyList<DroneTrack>()
        val ended = mutableListOf<DroneTrack>()
        var mostInTheAir = 0
        reports.forEach { report ->
            val update = tracking.update(report.alerts, report.observations, report.observedAt)
            tracks = update.tracks
            ended += update.ended
            mostInTheAir = maxOf(mostInTheAir, tracks.count { it.active })
        }
        assertTrue(mostInTheAir >= 20, "at the height of the strike $mostInTheAir targets are tracked at once")
        val flights = (strike.flights + CombinedStrikePlanner(config.seed).plan(strikeStart / period - 1, strikeStart - period).flights).associateBy { it.id }
        val strikeTracks = (ended + tracks).distinctBy { it.id }.filter { it.id in flights }
        assertTrue(strikeTracks.size >= strike.flights.size / 2, "the strike's flights are tracked")
        strikeTracks.filter { it.samples.size >= 3 }.forEach { track ->
            val flight = flights.getValue(track.id)
            val (_, speed) = TrackInterpolator.motion(track.samples[track.samples.size - 3], track.samples[track.samples.size - 2])
            val range = flight.munition.speedKmh
            assertTrue(speed in range.start * 0.6..range.endInclusive * 1.4, "${track.id} (${flight.munition}) tracked at $speed km/h")
            val profile = TargetClassifier.classify(track)
            assertEquals(TargetProfile.Source.Reported, profile.source, "the warning text names ${flight.munition}")
        }
        ended.filter { it.id in flights }.forEach { track ->
            val flight = flights.getValue(track.id)
            assertEquals(flight.outcome, track.outcome, "${track.id} ends as planned")
            assertTrue(GeoMath.distanceKm(track.samples.last().point, flight.endPoint) < 0.1)
            assertTrue(!track.active && track.markerIds.isEmpty())
        }
    }

    @Test
    fun guidedBombsFallAllDayOnTheFrontLine() {
        val frontline = FrontlinePlanner(config.seed)
        val bombs = (0L until 6 * 24).flatMap { frontline.plan(strikeStart / frontline.slotMs + it).flights }.filter { it.munition == Munition.Kab }
        assertTrue(bombs.size in 300..1_200, "${bombs.size} KABs in 24 hours")
        bombs.forEach { kab ->
            val rangeKm = GeoMath.distanceKm(kab.path.waypoints.first(), kab.path.target)
            assertTrue(rangeKm in 20.0..85.0, "${kab.id} glides $rangeKm km")
            assertTrue(kab.path.durationMs in 2 * minute..10 * minute)
        }
    }

    /** Alert ids follow the order locations are demanded in, so that order must not depend on hash codes. */
    @Test
    fun demandsKeepTheOrderTheyAreMadeIn() {
        val uids = Regions.oblasts.map { it.uid }.reversed()
        val area = AreaThreat(ThreatType.BallisticMissiles, uids, from = 0, until = minute, message = "test")
        val demanded = WarningSystem().demands(0, emptyList(), listOf(area)).keys.map { it.oblastUid }
        assertEquals(uids, demanded)
    }
}
