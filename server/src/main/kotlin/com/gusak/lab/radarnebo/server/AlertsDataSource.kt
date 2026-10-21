package com.gusak.lab.radarnebo.server

import com.gusak.lab.radarnebo.data.ActiveAlertsResponse
import com.gusak.lab.radarnebo.data.Alert
import com.gusak.lab.radarnebo.data.AlertsApi
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.DroneTracker
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

/** What one fetch from an [AlertsDataSource] produced. */
sealed interface FetchResult {
    /** New content. [rawJson] is served to clients byte-for-byte. */
    data class Fresh(val rawJson: String, val alerts: List<Alert>, val lastModified: String?) : FetchResult

    /** The content is unchanged since [AlertsDataSource.fetch]'s `lastModified`. */
    data object NotModified : FetchResult

    /** Wait at least [minWaitMs] (or the source's [retryAfterMs]) before trying again. */
    data class Failed(val message: String, val retryAfterMs: Long?, val minWaitMs: Long) : FetchResult
}

/** Where the proxy's alerts come from: alerts.in.ua ([UpstreamAlertsSource]) or the simulation ([SimulatedAlertsSource]). */
interface AlertsDataSource {
    /** True when the data is simulated. */
    val demo: Boolean

    /** For the startup log line. */
    val description: String

    /** Requests sent upstream in the last minute and in total; zero for sources that make none. */
    val requestsInLastMinute: Int get() = 0
    val requestsTotal: Long get() = 0

    suspend fun fetch(lastModified: String?): FetchResult

    /** Called once before the first fetch, to give [tracker] a history. */
    fun prime(tracker: DroneTracker, nowMs: Long) {}

    /** Tracks the source knows first-hand rather than ones reconstructed from alerts, served next to the tracker's. */
    fun directTracks(nowMs: Long): List<DroneTrack> = emptyList()
}

/** The simulation when no upstream token is configured, alerts.in.ua otherwise. */
fun alertsSourceFor(config: ProxyConfig, client: () -> HttpClient = ::defaultUpstreamClient): AlertsDataSource =
    if (config.demo) SimulatedAlertsSource() else UpstreamAlertsSource(config, client())

/**
 * alerts.in.ua. The only component that talks to it; requests go through [limiter], so the upstream
 * rate is independent of how many apps are connected.
 */
class UpstreamAlertsSource(
    private val config: ProxyConfig,
    private val client: HttpClient,
    private val limiter: UpstreamRateLimiter = UpstreamRateLimiter(config.effectivePerMinute),
) : AlertsDataSource {
    override val demo = false
    override val description get() = "${config.upstreamBaseUrl} (max ${config.effectivePerMinute} req/min)"
    override val requestsInLastMinute get() = limiter.requestsInWindow()
    @Volatile override var requestsTotal = 0L
        private set

    override suspend fun fetch(lastModified: String?): FetchResult {
        limiter.acquire()
        requestsTotal++
        val intervalMs = config.effectivePollInterval.inWholeMilliseconds
        return try {
            val response = client.get("${config.upstreamBaseUrl.trimEnd('/')}${AlertsApi.ACTIVE_ALERTS_PATH}") {
                header(HttpHeaders.Authorization, "Bearer ${config.upstreamToken}")
                lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
            }
            when (response.status) {
                HttpStatusCode.OK -> {
                    val raw = response.bodyAsText()
                    val alerts = AlertsApi.json.decodeFromString(ActiveAlertsResponse.serializer(), raw).alerts
                    FetchResult.Fresh(raw, alerts, response.headers[HttpHeaders.LastModified])
                }
                HttpStatusCode.NotModified -> FetchResult.NotModified
                HttpStatusCode.TooManyRequests -> FetchResult.Failed(
                    "Upstream rate limit hit (429)",
                    response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.times(1000),
                    minWaitMs = 60_000,
                )
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                    FetchResult.Failed("Upstream rejected credentials or IP (${response.status.value})", null, minWaitMs = 5 * 60_000)
                else -> FetchResult.Failed("Upstream returned ${response.status.value}", null, minWaitMs = intervalMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FetchResult.Failed("Upstream request failed: ${e.message}", null, minWaitMs = intervalMs)
        }
    }
}
