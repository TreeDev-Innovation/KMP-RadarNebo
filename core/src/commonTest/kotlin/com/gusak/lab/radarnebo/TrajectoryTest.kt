package com.gusak.lab.radarnebo

import com.gusak.lab.radarnebo.data.Alert
import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.domain.ThreatMarker
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.DroneTracker
import com.gusak.lab.radarnebo.tracking.PositionMode
import com.gusak.lab.radarnebo.tracking.SamplePrecision
import com.gusak.lab.radarnebo.tracking.TrackFilter
import com.gusak.lab.radarnebo.tracking.TrackInterpolator
import com.gusak.lab.radarnebo.tracking.TrackKinematics
import com.gusak.lab.radarnebo.tracking.TrackSample
import com.gusak.lab.radarnebo.tracking.TrajectoryEstimator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TrajectoryTest {
    private val minute = 60_000L
    private val kyiv = GeoPoint(50.45, 30.52)

    private fun marker(
        id: String,
        point: GeoPoint,
        t: Long,
        location: String = "raion",
        precision: SamplePrecision? = SamplePrecision.Raion,
        level: ThreatLevel = ThreatLevel.Red,
        oblastUid: Int? = Regions.oblastAt(point),
    ) = ThreatMarker(
        id = id, type = ThreatType.Drones, level = level, title = id, oblastUid = oblastUid, oblastName = "oblast $oblastUid",
        locationType = location, point = point, startedAt = Instant.fromEpochMilliseconds(t), message = null, precision = precision,
    )

    private fun track(samples: List<TrackSample>, active: Boolean = true) = DroneTrack(
        id = "t", level = ThreatLevel.Red, oblastUid = null, active = active, firstSeen = samples.first().t,
        lastSeen = samples.last().t, markerIds = emptyList(), samples = samples,
    )

    /** Raion-centre reports of a group flying west at 180 km/h, off its course by up to [jitterKm] either side. */
    private fun westbound(count: Int, jitterKm: Double = 15.0, everyMin: Long = 10): List<TrackSample> = (0 until count).map { i ->
        val t = i * everyMin * minute
        val along = GeoMath.destination(GeoPoint(50.0, 34.0), 270.0, 180.0 * t / 3_600_000.0)
        val p = GeoMath.destination(along, if (i % 2 == 0) 0.0 else 180.0, jitterKm)
        TrackSample(t, p.lat, p.lon, "r$i", precision = SamplePrecision.Raion)
    }

    @Test
    fun raionAlertsSitAtTheRaionCentre() {
        val alert = Alert(id = 1, locationTitle = "Баштанський район", locationType = "raion", locationUid = "96", locationOblast = "Миколаївська область")
        val location = assertNotNull(ThreatMapper.locateWithPrecision(alert))
        val raion = Regions.raionForTitle("Баштанський район")!!
        assertEquals(raion.point, location.point)
        assertEquals(SamplePrecision.Raion, location.precision)
        assertEquals(raion.radiusKm / 2, location.errorKm)
        val unknown = alert.copy(locationTitle = "Невідома громада", locationType = "hromada")
        assertEquals(SamplePrecision.Oblast, ThreatMapper.locateWithPrecision(unknown)!!.precision, "placed anywhere in the oblast")
    }

    @Test
    fun hromadaAlertsSitAtTheHromadaCentre() {
        // As alerts.in.ua words it; OSM calls it "Червоногригорівська сільська громада".
        val alert = Alert(
            id = 257385, locationTitle = "Червоногригорівська територіальна громада", locationType = "hromada",
            locationUid = "356", locationOblast = "Дніпропетровська область",
        )
        val location = assertNotNull(ThreatMapper.locateWithPrecision(alert))
        assertEquals(SamplePrecision.Hromada, location.precision)
        assertEquals(9, Regions.oblastAt(location.point))
        assertTrue(location.errorKm!! < 20.0, "a hromada is small: ${location.errorKm}")
    }

    @Test
    fun filterHoldsSpeedAndTurnToTheKind() {
        val k = TrackKinematics.Drones
        val filter = TrackFilter(k, kyiv)
        filter.update(0, kyiv, 25.0)
        // 100 km in 10 minutes would be 600 km/h, far beyond a Shahed.
        filter.update(10 * minute, GeoMath.destination(kyiv, 90.0, 100.0), 25.0)
        assertTrue(filter.speedKmh!! <= k.maxSpeedKmh + 1e-6, "speed ${filter.speedKmh}")
        val heading = filter.headingDeg!!
        // A report straight behind it 5 minutes later: it cannot turn around that fast.
        filter.update(15 * minute, GeoMath.destination(filter.position, (heading + 180) % 360, 10.0), 25.0)
        val turn = abs(TrackFilter.angleDiff(heading, filter.headingDeg!!))
        assertTrue(turn <= k.maxTurnDegPerHour * 5 / 60 + 1e-6, "turned $turn°")
        assertTrue(filter.speedKmh!! >= k.minSpeedKmh - 1e-6)
    }

    @Test
    fun smoothedPathIsRealisticAndStaysNearTheReports() {
        val samples = westbound(8)
        val enriched = TrajectoryEstimator.enrich(track(samples), samples.last().t)
        val path = enriched.path
        assertTrue(path.size > samples.size, "densified, got ${path.size}")
        val max = TrackKinematics.Drones.maxSpeedKmh * 1.25
        path.zipWithNext().filter { (a, b) -> b.t > a.t }.forEach { (a, b) ->
            val speed = GeoMath.distanceKm(a.point, b.point) / ((b.t - a.t) / 3_600_000.0)
            assertTrue(speed <= max, "path implies $speed km/h")
        }
        samples.forEach { s ->
            val nearest = path.minOf { GeoMath.distanceKm(it.point, s.point) }
            assertTrue(nearest <= 2 * SamplePrecision.Raion.sigmaKm, "path is $nearest km from a report")
        }
        // The zig-zag is gone: the path wanders much less across the course than the reports do.
        val acrossReports = samples.maxOf { it.lat } - samples.minOf { it.lat }
        val inner = path.filter { it.t in samples[1].t..samples[samples.lastIndex - 1].t }
        assertTrue(inner.maxOf { it.lat } - inner.minOf { it.lat } < acrossReports * 0.6)
        val estimate = assertNotNull(enriched.estimate)
        assertTrue(abs(TrackFilter.angleDiff(270.0, estimate.headingDeg!!)) < 25, "westward, got ${estimate.headingDeg}")
        assertTrue(estimate.speedKmh!! in 120.0..220.0)
    }

    @Test
    fun forecastWidensAheadAndStopsWhenTheTrackEnds() {
        val samples = westbound(6)
        val now = samples.last().t + 2 * minute
        val forecast = TrajectoryEstimator.enrich(track(samples), now).forecast
        assertTrue(forecast.size >= 2)
        assertEquals(now, forecast.first().t)
        assertTrue(forecast.zipWithNext().all { (a, b) -> b.sigmaKm > a.sigmaKm })
        assertTrue(TrajectoryEstimator.enrich(track(samples, active = false), now).forecast.isEmpty())
    }

    @Test
    fun clientPositionsFollowTheServerEstimate() {
        val samples = westbound(6)
        val enriched = TrajectoryEstimator.enrich(track(samples), samples.last().t)
        val e = enriched.estimate!!
        val later = TrackInterpolator.positionAt(enriched, e.t + 6 * minute)!!
        assertEquals(PositionMode.Extrapolated, later.mode)
        assertEquals(e.speedKmh!! / 10, GeoMath.distanceKm(e.point, later.point), 0.5)
        val past = TrackInterpolator.positionAt(enriched, samples[2].t + minute)!!
        assertEquals(PositionMode.Interpolated, past.mode)
        assertTrue(enriched.path.minOf { GeoMath.distanceKm(it.point, past.point) } < 3.0, "on the smoothed path")
    }

    @Test
    fun yellowReportCountsAsWhereTheTargetIsHeading() {
        val k = TrackKinematics.Drones
        fun after(level: ThreatLevel): GeoPoint {
            val f = TrackFilter(k, kyiv)
            f.update(0, kyiv, 7.0)
            f.update(10 * minute, GeoMath.destination(kyiv, 270.0, 30.0), 7.0)
            f.update(20 * minute, GeoMath.destination(kyiv, 270.0, 60.0), 7.0, k.leadMs(level))
            return f.position
        }
        assertTrue(after(ThreatLevel.Yellow).lon > after(ThreatLevel.Red).lon, "a yellow report pulls the estimate less far west")
    }

    @Test
    fun oblastReportKeepsTheTrackAliveWithoutAPointAtTheCentre() {
        val tracker = DroneTracker()
        val a = GeoPoint(50.5, 31.4)
        val b = GeoMath.destination(a, 270.0, 30.0)
        tracker.update(listOf(marker("a", a, 0)), 0)
        tracker.update(listOf(marker("b", b, 10 * minute)), 10 * minute)
        val uid = Regions.oblastAt(b)!!
        val region = marker("oblast", Regions.center(uid)!!, 50 * minute, location = "oblast", precision = SamplePrecision.Oblast)
        tracker.update(listOf(region), 50 * minute)
        val track = tracker.snapshot().single()
        assertEquals(50 * minute, track.lastSeen, "kept alive by the oblast report")
        assertTrue(track.active)
        assertTrue(track.samples.none { GeoMath.distanceKm(it.point, Regions.center(uid)!!) < 1.0 }, "no point at the oblast centre")
    }

    @Test
    fun oblastReportPullsATrackThatWouldBeOutsideToTheBorder() {
        val tracker = DroneTracker()
        // Westbound through Poltava oblast; then an oblast report for Kyiv oblast, which it has not reached yet.
        val a = GeoPoint(49.9, 34.2)
        val b = GeoMath.destination(a, 300.0, 30.0)
        tracker.update(listOf(marker("a", a, 0)), 0)
        tracker.update(listOf(marker("b", b, 10 * minute)), 10 * minute)
        val kyivOblast = Regions.oblastAt(GeoPoint(50.1, 31.0))!!
        val before = tracker.snapshot().single().samples.size
        tracker.update(listOf(marker("kyiv-oblast", Regions.center(kyivOblast)!!, 40 * minute, location = "oblast", precision = SamplePrecision.Oblast)), 40 * minute)
        val track = tracker.snapshot().single()
        assertEquals(before + 1, track.samples.size)
        val entry = track.samples.last()
        assertEquals(SamplePrecision.Oblast, entry.precision)
        assertTrue((Regions.distanceToRegionKm(kyivOblast, entry.point) ?: 99.0) < 1.0, "on the Kyiv oblast border")
    }

    @Test
    fun unexplainedOblastReportDatesTheTrackThatStartsThere() {
        val tracker = DroneTracker()
        val uid = Regions.oblastAt(kyiv)!!
        tracker.update(listOf(marker("oblast", Regions.center(uid)!!, 0, location = "oblast", precision = SamplePrecision.Oblast, oblastUid = uid)), 0)
        assertTrue(tracker.snapshot().isEmpty(), "no icon for an oblast-only report")
        val region = marker("oblast", Regions.center(uid)!!, 0, location = "oblast", precision = SamplePrecision.Oblast, oblastUid = uid)
        tracker.update(listOf(region, marker("r", GeoPoint(50.6, 30.9), 15 * minute)), 15 * minute)
        val track = tracker.snapshot().single()
        assertEquals(0, track.firstSeen, "seen since the oblast report")
        assertTrue("oblast" in track.markerIds)
    }

    @Test
    fun aReportBehindAFastTrackIsAnotherGroup() {
        val tracker = DroneTracker()
        val a = GeoPoint(50.0, 33.0)
        tracker.update(listOf(marker("a", a, 0)), 0)
        tracker.update(listOf(marker("b", GeoMath.destination(a, 270.0, 30.0), 10 * minute)), 10 * minute)
        // Expected ~30 km further west by now; this one is 100 km east, behind where it started.
        tracker.update(listOf(marker("c", GeoMath.destination(a, 90.0, 100.0), 20 * minute)), 20 * minute)
        assertEquals(2, tracker.snapshot().size)
        assertNull(tracker.snapshot().firstOrNull { it.samples.size == 3 })
    }

    @Test
    fun aHromadaAndItsRaionReportedTogetherAreOneTarget() {
        val tracker = DroneTracker()
        val hromada = marker("hromada", GeoPoint(50.5, 31.0), 0, location = "hromada", precision = SamplePrecision.Hromada)
        val raion = marker("raion", GeoPoint(50.55, 31.1), minute)
        tracker.update(listOf(hromada, raion), minute)
        val track = tracker.snapshot().single()
        assertEquals(setOf("hromada", "raion"), track.markerIds.toSet())
        assertEquals(SamplePrecision.Hromada, track.samples.single().precision, "the more precise report is kept")
    }

    @Test
    fun aTrackDoesNotOutflyItsRange() {
        val decoys = TrackKinematics.DecoyDrones
        val tracker = DroneTracker()
        var point = GeoPoint(51.0, 34.0)
        // A Gerbera group reported every 20 minutes 60 km further west: 1 000 km takes 17 hops.
        val gerbera = { id: String, p: GeoPoint, t: Long -> marker(id, p, t).copy(message = "Гербера") }
        for (i in 0..20) {
            tracker.update(listOf(gerbera("g$i", point, i * 20 * minute)), i * 20 * minute)
            point = GeoMath.destination(point, 270.0, 60.0)
        }
        tracker.snapshot().forEach { track ->
            val flown = track.samples.zipWithNext { a, b -> GeoMath.distanceKm(a.point, b.point) }.sum()
            assertTrue(flown <= decoys.rangeKm, "${track.id} flew $flown km")
        }
        assertTrue(tracker.snapshot().size >= 2, "the rest is another group")
    }

    @Test
    fun aGroupFirstReportedInlandIsTheOneLostOnItsWay() {
        val tracker = DroneTracker()
        // Westbound over Kharkiv oblast, then nothing for an hour (longer than the re-acquire window) ...
        val a = GeoPoint(49.9, 36.0)
        tracker.update(listOf(marker("a", a, 0)), 0)
        tracker.update(listOf(marker("b", GeoMath.destination(a, 270.0, 30.0), 10 * minute)), 10 * minute)
        tracker.update(emptyList(), 11 * minute)
        // ... then a report in Poltava oblast, where no drone enters first.
        val poltava = GeoPoint(49.6, 34.55)
        assertEquals(19, Regions.oblastAt(poltava))
        tracker.update(listOf(marker("c", poltava, 70 * minute)), 70 * minute)
        assertEquals(3, tracker.snapshot().single().samples.size)
    }

    @Test
    fun aNamedCourseTurnsTheEstimate() {
        val samples = westbound(3, jitterKm = 0.0)
        val kyiv = GeoPoint(50.45, 30.52)
        val plain = TrajectoryEstimator.enrich(track(samples), samples.last().t).estimate!!.headingDeg!!
        val hinted = samples.mapIndexed { i, s -> if (i == samples.lastIndex) s.copy(message = "Група ударних БпЛА курсом на Київ") else s }
        val towards = GeoMath.bearingDeg(samples.last().point, kyiv)
        val heading = TrajectoryEstimator.enrich(track(hinted), samples.last().t).estimate!!.headingDeg!!
        assertTrue(abs(TrackFilter.angleDiff(heading, towards)) < abs(TrackFilter.angleDiff(plain, towards)), "turned towards Kyiv: $plain -> $heading")
    }
}
