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
 * feed costs a 304. Each fresh body is kept as received, for clients; alerts.in.ua reports alerts only, so
 * tracks are reconstructed from them.
 */
class AlertsInUaSource(
    private val config: UpstreamConfig,
    private val client: HttpClient = defaultClient(),
    private val limiter: UpstreamRateLimiter = UpstreamRateLimiter(config.effectivePerMinute),
    private val clock: () -> Long = System::currentTimeMillis,
) : AlertsDataSource {
    init {
        require(!config.token.isNullOrBlank()) { "alerts.in.ua needs ALERTS_API_TOKEN" }
    }

    override val kind = DataSourceKind.AlertsInUa
    override val description get() = "${config.baseUrl} (max ${config.effectivePerMinute} req/min)"
    override val pollInterval: Duration get() = config.effectivePollInterval
    override val providesObservations = false
    override val requestsInLastMinute get() = limiter.requestsInWindow()

    @Volatile
    override var requestsTotal = 0L
        private set

    override suspend fun fetch(lastModified: String?): FetchResult {
        limiter.acquire()
        requestsTotal++
        val intervalMs = pollInterval.inWholeMilliseconds
        return try {
            val response = client.get("${config.baseUrl.trimEnd('/')}$ACTIVE_ALERTS_PATH") {
                header(HttpHeaders.Authorization, "Bearer ${config.token}")
                lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
            }
            when (response.status) {
                HttpStatusCode.OK -> {
                    val now = clock()
                    val body = response.bodyAsText()
                    val alerts = AlertsInUaContract.decodeActive(body).map { it.toDomain(Instant.fromEpochMilliseconds(now)) }
                    FetchResult.Fresh(SituationReport(now, alerts, rawAlerts = body), response.headers[HttpHeaders.LastModified])
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

    companion object {
        const val ACTIVE_ALERTS_PATH = "/v1/alerts/active.json"

        /**
         * With [logHttp], each exchange is logged with its body under the `http.upstream` logger; [traffic] receives
         * them for the server console. The token is masked in both.
         */
        fun defaultClient(logHttp: Boolean = false, traffic: TrafficRecorder? = null) = HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
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
