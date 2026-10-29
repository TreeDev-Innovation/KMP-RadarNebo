package com.gusak.lab.radarnebo.server.infrastructure

import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.server.Fixtures
import com.gusak.lab.radarnebo.server.ServerComponents
import com.gusak.lab.radarnebo.server.application.AlertsDataSource
import com.gusak.lab.radarnebo.server.application.FetchResult
import com.gusak.lab.radarnebo.server.application.SituationReport
import com.gusak.lab.radarnebo.server.config.DataSourceKind
import com.gusak.lab.radarnebo.server.config.ServerConfig
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.tracking.TargetObservation
import com.gusak.lab.radarnebo.server.Archives
import com.gusak.lab.radarnebo.tracking.SamplePrecision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Answers with whatever the test queues: a report, "not modified" or a failure; "not modified" once the queue is empty. */
private class QueuedSource(override val description: String, override val providesObservations: Boolean = false) : AlertsDataSource {
    val results = ArrayDeque<FetchResult>()
    override val kind = DataSourceKind.AlertsInUa
    override val pollInterval: Duration = 15.seconds
    override suspend fun fetch(lastModified: String?): FetchResult = results.removeFirstOrNull() ?: FetchResult.NotModified
}

class CompositeAlertsSourceTest {
    private val t0 = Fixtures.t0.toEpochMilliseconds()

    private fun fresh(alerts: List<AirAlert> = emptyList(), observations: List<TargetObservation> = emptyList()) =
        FetchResult.Fresh(SituationReport(t0, alerts, observations), lastModified = "v")

    private fun sighting(point: GeoPoint) = TargetObservation("radar-1", ThreatType.Drones, t0, point, message = "Ударні БпЛА типу «Шахед»")

    @Test
    fun extraAlertsAndSightingsFeedTrackingOnly() = runTest {
        val primary = QueuedSource("alerts.in.ua").apply { results += fresh(listOf(Fixtures.alert(1, Fixtures.brovary))) }
        val extra = QueuedSource("radar", providesObservations = true).apply {
            results += fresh(listOf(Fixtures.alert(1, Fixtures.kharkiv)), listOf(sighting(GeoPoint(50.5, 30.9))))
        }
        val report = assertIs<FetchResult.Fresh>(CompositeAlertsSource(primary, listOf(extra)).fetch(null)).report
        assertEquals(listOf(1L), report.alerts.map { it.id }, "clients are served the primary's alerts")
        val extraAlert = report.trackingAlerts.single()
        assertTrue(extraAlert.id != 1L, "the extra source's alert 1 is not the primary's alert 1")
        assertEquals(Fixtures.kharkiv, extraAlert.location)
        assertEquals(1, report.observations.size)
    }

    @Test
    fun aChangeInAnExtraSourceAloneIsANewReport() = runTest {
        val primary = QueuedSource("alerts.in.ua").apply { results += fresh(listOf(Fixtures.alert(1, Fixtures.brovary))) }
        val extra = QueuedSource("radar").apply { results += fresh() }
        val composite = CompositeAlertsSource(primary, listOf(extra))
        composite.fetch(null)
        assertEquals(FetchResult.NotModified, composite.fetch("v"), "nothing changed anywhere")
        extra.results += fresh(observations = listOf(sighting(GeoPoint(50.5, 30.9))))
        val report = assertIs<FetchResult.Fresh>(composite.fetch("v")).report
        assertEquals(listOf(1L), report.alerts.map { it.id }, "the primary's last report, unchanged")
        assertEquals(1, report.observations.size)
    }

    @Test
    fun anExtraSourceThatKeepsFailingIsLeftOut() = runTest {
        val primary = QueuedSource("alerts.in.ua").apply { results += fresh() }
        val extra = QueuedSource("radar").apply { results += fresh(observations = listOf(sighting(GeoPoint(50.5, 30.9)))) }
        val composite = CompositeAlertsSource(primary, listOf(extra))
        assertEquals(1, assertIs<FetchResult.Fresh>(composite.fetch(null)).report.observations.size)
        repeat(2) {
            extra.results += FetchResult.Failed("down", minWaitMs = 0)
            assertEquals(FetchResult.NotModified, composite.fetch("v"), "a failure or two keeps its last report")
        }
        extra.results += FetchResult.Failed("down", minWaitMs = 0)
        assertTrue(assertIs<FetchResult.Fresh>(composite.fetch("v")).report.observations.isEmpty(), "dropped after three")
    }

    @Test
    fun aServerWithAnExtraSourceFusesItIntoItsTracks() = runTest {
        val primary = QueuedSource("alerts.in.ua").apply { results += fresh(listOf(Fixtures.alert(1, Fixtures.brovary))) }
        val radar = QueuedSource("radar", providesObservations = true).apply { results += fresh(observations = listOf(sighting(GeoPoint(50.5, 30.9)))) }
        val server = ServerComponents.create(ServerConfig(), clock = { t0 }, archives = Archives.inMemory(), source = primary, extraSources = listOf(radar))
        server.poller.pollOnce()
        assertEquals(listOf(1L), server.situation.activeAlerts().map { it.id }, "served alerts are the primary's")
        val track = server.situation.tracks.single()
        assertTrue(track.id.startsWith("fus-"), "tracked by the fused strategy")
        assertEquals(SamplePrecision.Exact, track.samples.single().precision, "the sighting and the alert are one report")
        assertTrue("1:drones" in track.markerIds)
    }
}
