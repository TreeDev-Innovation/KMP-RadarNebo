package com.gusak.lab.radarnebo.server

import com.gusak.lab.radarnebo.data.ActiveAlertsResponse
import com.gusak.lab.radarnebo.data.Alert
import com.gusak.lab.radarnebo.data.AlertsApi
import com.gusak.lab.radarnebo.data.DroneSimulator
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.DroneTracker
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.LoggerFactory

/** Latest upstream payload. [rawJson] is served to clients byte-for-byte as received. */
data class AlertsSnapshot(
    val rawJson: String,
    val alerts: List<Alert>,
    /** Increments whenever the content changes; used as the client-facing ETag. */
    val version: Long,
    /** When the content last changed. */
    val changedAt: Long,
    /** When upstream last confirmed the content (200 or 304). */
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
    @Volatile var upstreamRequests: Long = 0
}

/**
 * The only component that talks to alerts.in.ua. Client traffic never triggers upstream requests, so
 * the upstream rate is independent of how many apps are connected.
 */
class UpstreamPoller(
    private val config: ProxyConfig,
    private val client: HttpClient,
    private val state: ProxyState,
    private val limiter: UpstreamRateLimiter = UpstreamRateLimiter(config.effectivePerMinute),
    private val tracker: DroneTracker = DroneTracker(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger("UpstreamPoller")
    private var backoffMs = 0L
    private var simulationWarmedUp = false

    val requestsInLastMinute get() = limiter.requestsInWindow()

    suspend fun run() {
        log.info(
            "Polling {} every {} (max {} req/min){}",
            config.upstreamBaseUrl, config.effectivePollInterval, config.effectivePerMinute,
            if (config.demo) " — SIMULATION MODE (no ALERTS_API_TOKEN set): serving simulated drone attacks" else "",
        )
        while (currentCoroutineContext().isActive) {
            val waitMs = pollOnce()
            state.nextPollAt = clock() + waitMs
            delay(waitMs)
        }
    }

    /** Performs one upstream fetch and returns how long to wait before the next one. */
    suspend fun pollOnce(): Long {
        val intervalMs = config.effectivePollInterval.inWholeMilliseconds
        if (config.demo) {
            val now = clock()
            if (!simulationWarmedUp) {
                // Replay the recent past so tracks have a history from the first request.
                DroneSimulator.warmUp(tracker, now)
                simulationWarmedUp = true
            }
            val alerts = DroneSimulator.alertsAt(now)
            publish(AlertsApi.json.encodeToString(ActiveAlertsResponse.serializer(), ActiveAlertsResponse(alerts)), alerts, null)
            return intervalMs
        }

        limiter.acquire()
        val now = clock()
        state.lastAttemptAt = now
        state.upstreamRequests++
        return try {
            val response = client.get("${config.upstreamBaseUrl.trimEnd('/')}${AlertsApi.ACTIVE_ALERTS_PATH}") {
                header(HttpHeaders.Authorization, "Bearer ${config.upstreamToken}")
                state.snapshot?.upstreamLastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
            }
            when (response.status) {
                HttpStatusCode.OK -> {
                    val raw = response.bodyAsText()
                    val alerts = AlertsApi.json.decodeFromString(ActiveAlertsResponse.serializer(), raw).alerts
                    publish(raw, alerts, response.headers[HttpHeaders.LastModified])
                    intervalMs
                }
                HttpStatusCode.NotModified -> {
                    state.snapshot?.let { snap ->
                        state.snapshot = snap.copy(confirmedAt = clock())
                        updateTracks(snap.alerts)
                    }
                    succeeded()
                    intervalMs
                }
                HttpStatusCode.TooManyRequests -> {
                    val retryAfterMs = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.times(1000)
                    failed("Upstream rate limit hit (429)", retryAfterMs, minMs = 60_000)
                }
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                    failed("Upstream rejected credentials or IP (${response.status.value})", null, minMs = 5 * 60_000)
                else -> failed("Upstream returned ${response.status.value}", null, minMs = intervalMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed("Upstream request failed: ${e.message}", null, minMs = intervalMs)
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
        tracker.update(ThreatMapper.toMarkers(alerts, dronesOnly = true), clock())
        state.tracks = tracker.snapshot()
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
