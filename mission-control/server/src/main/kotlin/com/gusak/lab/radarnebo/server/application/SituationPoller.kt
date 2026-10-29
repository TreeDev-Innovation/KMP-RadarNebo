package com.gusak.lab.radarnebo.server.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** What the poller last did, for the health check. */
data class PollerStatus(
    val lastAttemptAt: Long? = null,
    val nextPollAt: Long? = null,
    val lastError: String? = null,
)

/**
 * Pulls reports from an [AlertsDataSource] on its poll interval and publishes them to [SituationService].
 * Client traffic never triggers a fetch, so the upstream rate is independent of how many apps are connected.
 * Before the first fetch it replays the source's history; once an hour it prunes the archive to [retention].
 */
class SituationPoller(
    val source: AlertsDataSource,
    private val situation: SituationService,
    private val retention: Duration,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(SituationPoller::class.java)
    private var backoffMs = 0L
    private var primed = false
    private var lastModified: String? = null
    private var lastPruneAt = Long.MIN_VALUE

    @Volatile
    var status = PollerStatus()
        private set

    suspend fun run() {
        log.info("Serving alerts from {}, polled every {}", source.description, source.pollInterval)
        while (currentCoroutineContext().isActive) {
            val waitMs = try {
                pollOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A broken report or archive must not stop the feed; the next poll starts afresh.
                log.error("Poll failed", e)
                status = status.copy(lastError = "Internal error: ${e.message}")
                source.pollInterval.inWholeMilliseconds
            }
            status = status.copy(nextPollAt = clock() + waitMs)
            delay(waitMs)
        }
    }

    /** Performs one fetch and returns how long to wait before the next one. */
    suspend fun pollOnce(): Long {
        if (!primed) {
            // History is best effort and attempted once, so a failed replay can't stop live fetches.
            primed = true
            prime()
        }
        val result = source.fetch(lastModified)
        val now = clock()
        status = status.copy(lastAttemptAt = now)
        val intervalMs = source.pollInterval.inWholeMilliseconds
        return when (result) {
            is FetchResult.Fresh -> {
                situation.publish(result.report)
                lastModified = result.lastModified ?: lastModified
                succeeded(now)
                intervalMs
            }
            FetchResult.NotModified -> {
                situation.confirm(now)
                succeeded(now)
                intervalMs
            }
            is FetchResult.Failed -> failed(result)
        }
    }

    private suspend fun prime() {
        val started = clock()
        var replayed = 0
        source.history(started).forEach { report ->
            // A long replay suspends only if the mutex is contended, so check for shutdown explicitly.
            currentCoroutineContext().ensureActive()
            situation.replay(report)
            replayed++
        }
        if (replayed > 0) log.info("Replayed {} reports of history in {} ms", replayed, clock() - started)
    }

    private suspend fun succeeded(now: Long) {
        backoffMs = 0
        status = status.copy(lastError = null)
        // Not `now - lastPruneAt`: from Long.MIN_VALUE that overflows to a negative, and pruning would never run.
        if (now >= lastPruneAt + PRUNE_EVERY.inWholeMilliseconds) {
            lastPruneAt = now
            try {
                val pruned = situation.pruneArchive(Instant.fromEpochMilliseconds(now) - retention)
                if (pruned > 0) log.info("Pruned {} archived records older than {}", pruned, retention)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Archive pruning failed", e)
            }
        }
    }

    /** Exponential backoff, capped at 10 minutes, never shorter than the failure's minimum or Retry-After. */
    private fun failed(failure: FetchResult.Failed): Long {
        backoffMs = (if (backoffMs == 0L) failure.minWaitMs else backoffMs * 2).coerceIn(failure.minWaitMs, MAX_BACKOFF_MS)
        val wait = maxOf(backoffMs, failure.retryAfterMs ?: 0)
        status = status.copy(lastError = failure.message)
        log.warn("{} — next attempt in {} s", failure.message, wait / 1000)
        return wait
    }

    private companion object {
        const val MAX_BACKOFF_MS = 10 * 60_000L
        val PRUNE_EVERY = 1.hours
    }
}
