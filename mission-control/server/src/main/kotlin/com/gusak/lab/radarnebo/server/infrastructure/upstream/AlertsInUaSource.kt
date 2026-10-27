package com.gusak.lab.radarnebo.server.infrastructure.upstream

import com.gusak.lab.radarnebo.debug.TrafficRecorder
import com.gusak.lab.radarnebo.debug.httpTrafficCapture
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import org.slf4j.LoggerFactory
import com.gusak.lab.radarnebo.server.application.AlertsDataSource
import com.gusak.lab.radarnebo.server.application.FetchResult
import com.gusak.lab.radarnebo.server.application.SituationReport
import com.gusak.lab.radarnebo.server.config.DataSourceKind
import com.gusak.lab.radarnebo.server.config.UpstreamConfig
import com.gusak.lab.radarnebo.server.contract.AlertsInUaContract
import com.gusak.lab.radarnebo.server.contract.AlertsInUaContract.toDomain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * alerts.in.ua, the only component that talks to it. Requests go through [limiter], so the upstream rate is
 * independent of how many clients are connected, and are conditional (`If-Modified-Since`), so an unchanged
 * feed costs a 304. alerts.in.ua reports alerts only, so tracks are reconstructed from them. The source owns
 * the [client] it is given and closes it.
 */
class AlertsInUaSource(
    private val config: UpstreamConfig,
    private val client: HttpClient = defaultClient(),
    private val limiter: UpstreamRateLimiter = UpstreamRateLimiter(config.effectivePerMinute),
    private val clock: () -> Long = System::currentTimeMillis,
) : AlertsDataSource {
    private val log = LoggerFactory.getLogger(AlertsInUaSource::class.java)
    private val token: String = requireNotNull(config.token?.takeIf { it.isNotBlank() }) { "alerts.in.ua needs ALERTS_API_TOKEN" }

    override val kind = DataSourceKind.AlertsInUa
    override val description get() = "${config.baseUrl} (max ${config.effectivePerMinute} req/min)"
    override val pollInterval: Duration get() = config.effectivePollInterval
    override val providesObservations = false
    override val requestsInLastMinute get() = limiter.requestsInWindow()

    @Volatile
    override var requestsTotal = 0L
        private set

    @Volatile
    override var rateLimitedTotal = 0L
        private set

    @Volatile
    override var rejectedTotal = 0L
        private set

    @Volatile
    override var otherFailuresTotal = 0L
        private set

    @Volatile
    override var lastSuccessAt: Long? = null
        private set

    override suspend fun fetch(lastModified: String?): FetchResult {
        limiter.acquire()
        requestsTotal++
        val intervalMs = pollInterval.inWholeMilliseconds
        return try {
            val response = client.get("${config.baseUrl.trimEnd('/')}$ACTIVE_ALERTS_PATH") {
                header(HttpHeaders.Authorization, "Bearer $token")
                lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
            }
            when (response.status) {
                HttpStatusCode.OK -> {
                    val now = clock()
                    val alerts = AlertsInUaContract.decodeActive(response.bodyAsText())
                        .map { it.toDomain(Instant.fromEpochMilliseconds(now)) }
                    // After decoding, so a body that doesn't decode counts as a failure, not a success.
                    lastSuccessAt = now
                    FetchResult.Fresh(SituationReport(now, alerts), response.headers[HttpHeaders.LastModified])
                }
                HttpStatusCode.NotModified -> {
                    lastSuccessAt = clock()
                    FetchResult.NotModified
                }
                HttpStatusCode.TooManyRequests -> {
                    rateLimitedTotal++
                    FetchResult.Failed(
                        "Upstream rate limit hit (429)",
                        minWaitMs = RATE_LIMITED_WAIT_MS,
                        retryAfterMs = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.times(1000),
                        maxWaitMs = PENALTY_MAX_WAIT_MS,
                    )
                }
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> {
                    rejectedTotal++
                    FetchResult.Failed(
                        "Upstream rejected credentials or IP (${response.status.value})",
                        minWaitMs = REJECTED_WAIT_MS,
                        maxWaitMs = PENALTY_MAX_WAIT_MS,
                    )
                }
                else -> {
                    otherFailuresTotal++
                    FetchResult.Failed("Upstream returned ${response.status.value}", minWaitMs = intervalMs)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("Upstream request failed", e)
            otherFailuresTotal++
            FetchResult.Failed("Upstream request failed: ${e.message ?: e::class.simpleName}", minWaitMs = intervalMs)
        }
    }

    override fun close() = client.close()

    companion object {
        const val ACTIVE_ALERTS_PATH = "/v1/alerts/active.json"
        private const val RATE_LIMITED_WAIT_MS = 60_000L
        private const val REJECTED_WAIT_MS = 5 * 60_000L

        // Only a rate limit or a rejection warrants backing off this far; other failures stop at the default 60 s.
        private const val PENALTY_MAX_WAIT_MS = 10 * 60_000L
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L

        /**
         * With [logHttp], each exchange is logged with its body under the `http.upstream` logger; [traffic] receives
         * them for the server console. The token is masked in both.
         */
        fun defaultClient(logHttp: Boolean = false, traffic: TrafficRecorder? = null) = HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
            }
            if (logHttp) {
                val log = LoggerFactory.getLogger("http.upstream")
                install(Logging) {
                    logger = object : Logger {
                        override fun log(message: String) = log.info(message)
                    }
                    level = LogLevel.ALL
                    sanitizeHeader { it == HttpHeaders.Authorization }
                }
            }
            if (traffic != null) install(httpTrafficCapture(traffic))
        }
    }
}
