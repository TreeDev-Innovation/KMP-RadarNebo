package com.gusak.lab.radarnebo.server.application

import com.gusak.lab.radarnebo.server.Fixtures
import com.gusak.lab.radarnebo.server.Fixtures.alert
import com.gusak.lab.radarnebo.server.config.DataSourceKind
import com.gusak.lab.radarnebo.server.domain.alert.AlertArchive
import com.gusak.lab.radarnebo.server.domain.alert.LocationDirectory
import com.gusak.lab.radarnebo.server.domain.tracking.ObservationTracking
import com.gusak.lab.radarnebo.server.infrastructure.persistence.InMemoryAlertArchive
import com.gusak.lab.radarnebo.server.infrastructure.persistence.InMemoryTrackArchive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Answers each fetch with the next of [results]; once they run out, fetch throws. Its history fails when
 * [historyFails] is set.
 */
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
    private var now = Fixtures.t0.toEpochMilliseconds()
    private val alertArchive = InMemoryAlertArchive()
    private val situation = situation(alertArchive)

    private fun situation(alertArchive: AlertArchive) = SituationService(
        tracking = ObservationTracking(),
        alertArchive = alertArchive,
        trackArchive = InMemoryTrackArchive(),
        locations = LocationDirectory(),
        simulated = false,
    )

    private fun poller(source: AlertsDataSource, situation: SituationService = this.situation) =
        SituationPoller(source, situation, retention = 1.days, clock = { now })

    private fun fresh() = FetchResult.Fresh(SituationReport(now, listOf(alert(1, Fixtures.brovary))))

    /** An alert that finished two days before [Fixtures.t0], past the one-day retention. */
    private fun oldAlert(id: Long) = alert(id, Fixtures.kharkiv, startedAt = Fixtures.t0 - 3.days).finish(Fixtures.t0 - 2.days)

    private suspend fun archivedIds(): List<Long> =
        alertArchive.history(Fixtures.kharkiv.uid, Fixtures.t0 - 10.days, limit = 10).map { it.id }

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

    @Test
    fun transientFailuresNeverBackOffPastAMinute() = runTest {
        val failure = FetchResult.Failed("Upstream returned 503", minWaitMs = 15_000)
        val poller = poller(ScriptedSource(ArrayDeque(List(10) { failure })))

        val waits = List(10) { poller.pollOnce() }
        assertEquals(listOf(15_000L, 30_000L, 60_000L), waits.take(3))
        assertTrue(waits.all { it <= 60_000 }, "was $waits")
    }

    @Test
    fun aRetryAfterLongerThanTheBackoffIsHonoured() = runTest {
        val rateLimited = FetchResult.Failed("Upstream rate limit hit (429)", minWaitMs = 60_000, retryAfterMs = 300_000, maxWaitMs = 600_000)
        val poller = poller(ScriptedSource(ArrayDeque(listOf(rateLimited))))

        assertEquals(300_000, poller.pollOnce())
    }

    @Test
    fun rejectionsBackOffUpToTenMinutesAndOutlastAShorterRetryAfter() = runTest {
        val failure = FetchResult.Failed("Upstream rejected credentials or IP (401)", minWaitMs = 60_000, maxWaitMs = 600_000)
        val source = ScriptedSource(ArrayDeque(List(6) { failure } + failure.copy(retryAfterMs = 1_000)))
        val poller = poller(source)

        assertEquals(listOf(60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L), List(6) { poller.pollOnce() })
        assertEquals(600_000, poller.pollOnce(), "a Retry-After shorter than the backoff does not shorten it")
    }

    @Test
    fun theFirstSuccessfulPollPrunesAndLaterOnesWaitAnHour() = runTest {
        alertArchive.save(listOf(oldAlert(10)))
        val source = ScriptedSource(ArrayDeque(listOf(fresh(), FetchResult.NotModified, FetchResult.NotModified)))
        val poller = poller(source)

        poller.pollOnce()
        assertEquals(emptyList<Long>(), archivedIds(), "the first successful poll prunes")

        alertArchive.save(listOf(oldAlert(11)))
        now += 30.minutes.inWholeMilliseconds
        poller.pollOnce()
        assertEquals(listOf(11L), archivedIds(), "pruning runs at most once an hour")

        now += 31.minutes.inWholeMilliseconds
        poller.pollOnce()
        assertEquals(emptyList<Long>(), archivedIds())
    }

    @Test
    fun aFailedPruneDoesNotFailThePoll() = runTest {
        val brokenArchive = object : AlertArchive by InMemoryAlertArchive() {
            override suspend fun prune(before: Instant): Int = throw IOException("archive unavailable")
        }
        val poller = poller(ScriptedSource(ArrayDeque(listOf(fresh()))), situation(brokenArchive))

        assertEquals(15_000, poller.pollOnce())
        assertNull(poller.status.lastError)
    }

    @Test
    fun aPollThatThrowsIsReportedAndRetriedAfterThePollInterval() = runTest {
        val poller = poller(ScriptedSource(ArrayDeque()))
        val job = launch { poller.run() }
        advanceTimeBy(1)

        assertTrue(poller.status.lastError.orEmpty().startsWith("Internal error"), "was ${poller.status.lastError}")
        assertEquals(now + 15_000, poller.status.nextPollAt)
        job.cancel()
    }

    @Test
    fun anInternalErrorIsReportedByClassOnly() = runTest {
        val source = object : AlertsDataSource by ScriptedSource(ArrayDeque()) {
            override suspend fun fetch(lastModified: String?): FetchResult =
                throw IllegalStateException("SELECT * FROM secret_table /data/x.db")
        }
        val poller = poller(source)
        val job = launch { poller.run() }
        advanceTimeBy(1)

        val error = poller.status.lastError.orEmpty()
        assertTrue("IllegalStateException" in error, error)
        assertFalse("SELECT" in error || "/data" in error, "the message stays in the server log: $error")
        job.cancel()
    }

    @Test
    fun aPollerKilledByAnErrorSaysSoInItsStatus() = runTest {
        val dying = object : AlertsDataSource {
            override val kind = DataSourceKind.AlertsInUa
            override val description = "dying"
            override val pollInterval: Duration = 15.seconds
            override val providesObservations = false
            override suspend fun fetch(lastModified: String?): FetchResult = throw NotImplementedError("boom")
        }
        val poller = poller(dying)
        assertFailsWith<NotImplementedError> { poller.run() }
        assertEquals("Poller stopped (NotImplementedError)", poller.status.lastError)
    }
}
