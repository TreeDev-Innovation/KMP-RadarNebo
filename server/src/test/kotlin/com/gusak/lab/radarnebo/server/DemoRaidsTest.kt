package com.gusak.lab.radarnebo.server

import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.tracking.DroneTracker
import com.gusak.lab.radarnebo.tracking.PositionMode
import com.gusak.lab.radarnebo.tracking.TrackInterpolator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoRaidsTest {
    private val start = 1_800_000_000_000L - 1_800_000_000_000L % (15 * 60_000L)

    /** Every bomb released in [hours] hours from [start], polled every 20 s. */
    private fun kabsReleased(hours: Int): List<DemoRaids.Kab> =
        (0 until hours * 180).flatMap { DemoRaids.kabsAround(start + it * 20_000L) }
            .distinctBy { it.id }
            .filter { it.launchedAt in start until start + hours * 3_600_000L }

    @Test
    fun threeKabsEveryQuarterHourPlusOneEveryMinute() {
        val kabs = kabsReleased(hours = 3)
        val (salvoKabs, singles) = kabs.partition { it.id.startsWith("kab-s") }
        val salvos = salvoKabs.groupBy { (it.launchedAt - start) / (15 * 60_000L) }
        assertEquals(12, salvos.size, "one salvo per 15 minutes")
        salvos.values.forEach { salvo ->
            assertEquals(3, salvo.size)
            assertEquals(1, salvo.map { it.city }.distinct().size, "a salvo hits one target")
        }
        val minutes = singles.groupBy { (it.launchedAt - start) / 60_000L }
        assertEquals(180, minutes.size)
        assertTrue(minutes.values.all { it.size == 1 }, "one single launch per minute")
        assertTrue(singles.map { it.city }.distinct().size >= 8, "single launches hit random targets")
    }

    @Test
    fun kabsGlideInFromRussiaOrTheOccupiedTerritories() {
        kabsReleased(hours = 6).forEach { kab ->
            val range = GeoMath.distanceKm(kab.release, kab.city.point)
            assertTrue(range in 24.0..81.0, "${kab.id} released ${range.toInt()} km from ${kab.city.name}")
            val glideMinutes = (kab.impactAt - kab.launchedAt) / 60_000.0
            assertTrue(glideMinutes in 2.0..9.0, "${kab.id} glides $glideMinutes min")
            // Russia lies outside every oblast; occupied areas are inside Ukraine's borders.
            val releasedOver = Regions.oblastByPolygon(kab.release)
            when (kab.city.name) {
                "Kharkiv", "Sumy", "Okhtyrka", "Trostianets", "Zolochiv", "Bohodukhiv", "Chuhuiv" ->
                    assertEquals(null, releasedOver, "${kab.id} on ${kab.city.name} released over Russia")
                else -> assertTrue(releasedOver != null, "${kab.id} on ${kab.city.name} released over occupied Ukraine")
            }
        }
    }

    @Test
    fun kabTracksFollowTheGlideAndTargetsAreAlerted() {
        val kab = kabsReleased(hours = 1).first { !it.id.startsWith("kab-s") }
        val mid = (kab.launchedAt + kab.impactAt) / 2
        val track = DemoRaids.kabTracks(mid).single { it.id == kab.id }
        assertEquals(ThreatType.GuidedBombs, track.type)
        assertTrue(track.active)
        assertEquals(kab.release.lat, track.samples.first().lat, 1e-9)

        // Between polls, clients dead-reckon the bomb towards its target at its real speed.
        val ahead = TrackInterpolator.positionAt(track, mid + 15_000)!!
        assertEquals(PositionMode.Extrapolated, ahead.mode)
        assertEquals(kab.speedKmh, ahead.speedKmh!!, 1.0)
        val toTarget = GeoMath.bearingDeg(kab.release, kab.city.point)
        assertTrue(kotlin.math.abs(ahead.headingDeg!! - toTarget) < 2.0)
        assertTrue(GeoMath.distanceKm(ahead.point, kab.city.point) < GeoMath.distanceKm(track.samples.last().point, kab.city.point))

        val alert = DemoRaids.alertsAt(mid).single { it.locationTitleEn == kab.city.name && it.threats.single().threatType == "guided_aerial_bombs" }
        assertTrue("КАБ" in alert.threats.single().sourceMessage!!)
        assertEquals(alert.id, DemoRaids.alertsAt(mid + 20_000).single { it.locationTitleEn == kab.city.name }.id, "the alert id is stable between polls")

        // After impact the track ends at the target and soon disappears; the alert clears a few minutes later.
        val landed = DemoRaids.kabTracks(kab.impactAt + 30_000).single { it.id == kab.id }
        assertTrue(!landed.active)
        assertTrue(GeoMath.distanceKm(landed.samples.last().point, kab.city.point) < 0.1)
        assertTrue(DemoRaids.kabTracks(kab.impactAt + 2 * 60_000).none { it.id == kab.id })
        assertTrue(DemoRaids.kabsAround(kab.impactAt + 4 * 60_000).none { it.id == kab.id })
    }

    @Test
    fun shahedGroupsAppearAtRandomAndAreTracked() {
        val seen = (0 until 6 * 60).map { DemoRaids.shahedGroupsAround(start + it * 60_000L) }
        val groups = seen.flatten().distinctBy { it.idBase to it.departedAt }
        assertTrue(groups.size >= 18, "one or two groups every 20 minutes, got ${groups.size} in 6 h")
        assertTrue(groups.map { it.route.first() }.distinct().size >= 6, "groups cross in at different places")
        groups.forEach { group ->
            group.route.zipWithNext { a, b ->
                assertTrue(GeoMath.distanceKm(a.point, b.point) <= 100.0, "${a.name} → ${b.name} is a trackable hop")
            }
        }

        // The tracker links a group's settlement alerts into a track, as with real alerts. Where groups
        // cross, a later report can go to the nearer group, so only the entry hop is certain.
        val group = groups.first { it.route.size >= 5 && it.departedAt > start + 60 * 60_000L }
        val tracker = DroneTracker()
        val source = SimulatedAlertsSource()
        var t = group.departedAt - 60_000L
        while (t < group.departedAt + 3 * 3_600_000L) {
            tracker.update(ThreatMapper.toMarkers(source.alertsAt(t), dronesOnly = false), t)
            t += 60_000L
        }
        val titles = group.route.take(2).map { it.name }
        assertTrue(
            tracker.snapshot().any { track -> track.samples.map { it.title }.windowed(2).any { it == titles } },
            "a track follows ${titles.joinToString(" → ")}",
        )
    }
}
