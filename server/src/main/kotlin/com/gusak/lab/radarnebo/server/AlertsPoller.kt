package com.gusak.lab.radarnebo.server

import com.gusak.lab.radarnebo.data.Alert
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.DroneTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.LoggerFactory

/** Latest payload from the data source. [rawJson] is served to clients byte-for-byte as received. */
data class AlertsSnapshot(
    val rawJson: String,
    val alerts: List<Alert>,
    /** Increments whenever the content changes; used as the client-facing ETag. */
    val version: Long,
    /** When the content last changed. */
    val changedAt: Long,
    /** When the source last confirmed the content (upstream: 200 or 304). */
    val confirmedAt: Long,
    val upstreamLastModified: String?,
)

/** Everything clients read. Written only by the poller; published as immutable values for lock-free reads. */
class ProxyState {
    @Volatile var snapshot: AlertsSnapshot? = null
    @Volatile var tracks: List<DroneTrack> = emptyList()
    @Volatile var lastError: String? = null
    @Volatile var lastAttemptAt: Long? = null
    @Volatile var nextPollAt: Long? = null
}

/**
 * Pulls alerts from an [AlertsDataSource] on the poll interval and publishes them, with the tracks
 * built from them, to [ProxyState]. Client traffic never triggers a fetch.
 */
class AlertsPoller(
    private val config: ProxyConfig,
    val source: AlertsDataSource,
    private val state: ProxyState,
    private val tracker: DroneTracker = DroneTracker(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger("AlertsPoller")
    private var backoffMs = 0L
    private var primed = false

    suspend fun run() {
        log.info("Serving alerts from {}, polled every {}", source.description, config.effectivePollInterval)
        while (currentCoroutineContext().isActive) {
            val waitMs = pollOnce()
            state.nextPollAt = clock() + waitMs
            delay(waitMs)
        }
    }

    /** Performs one fetch and returns how long to wait before the next one. */
    suspend fun pollOnce(): Long {
        if (!primed) {
            source.prime(tracker, clock())
            primed = true
        }
        val result = source.fetch(state.snapshot?.upstreamLastModified)
        state.lastAttemptAt = clock()
        val intervalMs = config.effectivePollInterval.inWholeMilliseconds
        return when (result) {
            is FetchResult.Fresh -> {
                publish(result.rawJson, result.alerts, result.lastModified)
                intervalMs
            }
            FetchResult.NotModified -> {
                state.snapshot?.let { snap ->
                    state.snapshot = snap.copy(confirmedAt = clock())
                    updateTracks(snap.alerts)
                }
                succeeded()
                intervalMs
            }
            is FetchResult.Failed -> failed(result.message, result.retryAfterMs, result.minWaitMs)
        }
    }

    private fun publish(raw: String, alerts: List<Alert>, lastModified: String?) {
        val now = clock()
        val previous = state.snapshot
        state.snapshot = if (previous != null && previous.rawJson == raw) {
            previous.copy(confirmedAt = now, upstreamLastModified = lastModified ?: previous.upstreamLastModified)
        } else {
            AlertsSnapshot(raw, alerts, (previous?.version ?: 0) + 1, now, now, lastModified)
        }
        updateTracks(alerts)
        succeeded()
    }

    private fun updateTracks(alerts: List<Alert>) {
        val now = clock()
        // Every threat class goes in; the tracker follows the ones that fly routes (drones, cruise missiles).
        tracker.update(ThreatMapper.toMarkers(alerts, dronesOnly = false), now)
        state.tracks = tracker.snapshot() + source.directTracks(now)
    }

    private fun succeeded() {
        backoffMs = 0
        state.lastError = null
    }

    /** Exponential backoff, capped at 10 minutes, never shorter than [minMs] or the server's Retry-After. */
    private fun failed(message: String, retryAfterMs: Long?, minMs: Long): Long {
        backoffMs = (if (backoffMs == 0L) minMs else backoffMs * 2).coerceIn(minMs, 10 * 60_000L)
        val wait = maxOf(backoffMs, retryAfterMs ?: 0)
        state.lastError = message
        log.warn("{} — next attempt in {} s", message, wait / 1000)
        return wait
    }
}
