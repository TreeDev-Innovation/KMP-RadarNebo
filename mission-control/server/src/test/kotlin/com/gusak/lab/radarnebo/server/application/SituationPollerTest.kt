package com.gusak.lab.radarnebo.server.application

import com.gusak.lab.radarnebo.server.Fixtures
import com.gusak.lab.radarnebo.server.Fixtures.alert
import com.gusak.lab.radarnebo.server.config.DataSourceKind
import com.gusak.lab.radarnebo.server.domain.alert.LocationDirectory
import com.gusak.lab.radarnebo.server.domain.tracking.ObservationTracking
import com.gusak.lab.radarnebo.server.infrastructure.persistence.InMemoryAlertArchive
import com.gusak.lab.radarnebo.server.infrastructure.persistence.InMemoryTrackArchive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** Answers each fetch with the next of [results]; its history fails when [historyFails] is set. */
private class ScriptedSource(private val results: ArrayDeque<FetchResult>, private val historyFails: Boolean = false) : AlertsDataSource {
    var fetches = 0
    override val kind = DataSourceKind.AlertsInUa
    override val description = "scripted"
    override val pollInterval: Duration = 15.seconds
    override val providesObservations = false

    override suspend fun fetch(lastModified: String?): FetchResult {
        fetches++
        return results.removeFirst()
    }

    override fun history(nowMs: Long): Sequence<SituationReport> =
        if (historyFails) throw IllegalStateException("history unavailable") else emptySequence()
}

class SituationPollerTest {
    private val now = Fixtures.t0.toEpochMilliseconds()
    private val situation = SituationService(
        tracking = ObservationTracking(),
        alertArchive = InMemoryAlertArchive(),
        trackArchive = InMemoryTrackArchive(),
        locations = LocationDirectory(),
        simulated = false,
    )

    private fun poller(source: AlertsDataSource) = SituationPoller(source, situation, retention = 1.days, clock = { now })

    private fun fresh() = FetchResult.Fresh(SituationReport(now, listOf(alert(1, Fixtures.brovary))))

    @Test
    fun aFailedHistoryReplayDoesNotStopLiveFetches() = runTest {
        val source = ScriptedSource(ArrayDeque(listOf(fresh())), historyFails = true)
        val poller = poller(source)

        assertFailsWith<IllegalStateException> { poller.pollOnce() }
        assertEquals(0, source.fetches)

        assertEquals(15_000, poller.pollOnce())
        assertEquals(1, source.fetches)
        assertEquals(1, situation.board?.version, "the fetched report is published")
    }

    @Test
    fun aSuccessfulFetchResetsTheBackoff() = runTest {
        val failure = FetchResult.Failed("Upstream returned 500", minWaitMs = 1_000)
        val source = ScriptedSource(ArrayDeque(listOf(failure, failure, fresh(), failure)))
        val poller = poller(source)

        assertEquals(1_000, poller.pollOnce())
        assertEquals(2_000, poller.pollOnce(), "failures back off exponentially")
        assertEquals(15_000, poller.pollOnce())
        assertEquals(1_000, poller.pollOnce(), "the backoff starts over after a success")
    }
}
