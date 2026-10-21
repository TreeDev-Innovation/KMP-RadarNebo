package com.gusak.lab.radarnebo.server

import com.gusak.lab.radarnebo.data.AlertsApi
import com.gusak.lab.radarnebo.tracking.DronePositionsResponse
import com.gusak.lab.radarnebo.tracking.DroneTracksResponse
import com.gusak.lab.radarnebo.tracking.TrackInterpolator
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes

fun main() {
    val config = ProxyConfig.fromEnv()
    embeddedServer(CIO, port = config.port) { radarProxy(config) }.start(wait = true)
}

/**
 * Wires the proxy. Alerts come from alerts.in.ua, or from the simulation when no upstream token is set
 * ([alertsSourceFor]). [startPoller] is false in tests that drive [AlertsPoller.pollOnce] themselves.
 */
fun Application.radarProxy(
    config: ProxyConfig,
    state: ProxyState = ProxyState(),
    poller: AlertsPoller = AlertsPoller(config, alertsSourceFor(config), state),
    startPoller: Boolean = true,
    clock: () -> Long = System::currentTimeMillis,
) {
    install(ContentNegotiation) { json(AlertsApi.json) }
    install(CallLogging)
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.IfNoneMatch)
    }
    install(RateLimit) {
        register(CLIENT_LIMIT) {
            rateLimiter(limit = config.clientRequestsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }
    if (startPoller) launch { poller.run() }

    routing {
        get("/health") {
            val snap = state.snapshot
            call.respond(
                Health(
                    status = if (snap == null) "warming_up" else if (state.lastError != null) "degraded" else "ok",
                    demo = poller.source.demo,
                    upstreamBudgetPerMinute = config.effectivePerMinute,
                    upstreamRequestsLastMinute = poller.source.requestsInLastMinute,
                    upstreamRequestsTotal = poller.source.requestsTotal,
                    pollIntervalSeconds = config.effectivePollInterval.inWholeSeconds,
                    lastAttemptAt = state.lastAttemptAt,
                    nextPollAt = state.nextPollAt,
                    dataVersion = snap?.version,
                    dataConfirmedAt = snap?.confirmedAt,
                    alerts = snap?.alerts?.size ?: 0,
                    tracks = state.tracks.size,
                    activeTracks = state.tracks.count { it.active },
                    lastError = state.lastError,
                )
            )
        }

        rateLimit(CLIENT_LIMIT) {
            route("/v1") {
                install(ClientAuth) { keys = config.clientKeys }

                get("/alerts/active.json") {
                    val snap = state.snapshot ?: return@get call.warmingUp()
                    val etag = "\"v${snap.version}\""
                    call.response.header(HttpHeaders.ETag, etag)
                    call.response.header(HttpHeaders.LastModified, httpDate(snap.changedAt))
                    call.response.header(HttpHeaders.CacheControl, "max-age=${config.effectivePollInterval.inWholeSeconds}")
                    call.response.header("X-Data-Age-Seconds", ((clock() - snap.confirmedAt) / 1000).toString())
                    val notModified = call.request.header(HttpHeaders.IfNoneMatch) == etag ||
                        call.request.header(HttpHeaders.IfModifiedSince) == httpDate(snap.changedAt)
                    if (notModified) {
                        call.respond(HttpStatusCode.NotModified)
                    } else {
                        call.respondText(snap.rawJson, ContentType.Application.Json)
                    }
                }

                get("/drones/tracks.json") {
                    if (state.snapshot == null) return@get call.warmingUp()
                    val activeOnly = call.request.queryParameters["active"] == "true"
                    call.respond(
                        DroneTracksResponse(
                            serverTime = clock(),
                            pollIntervalMs = config.effectivePollInterval.inWholeMilliseconds,
                            lastUpstreamFetch = state.snapshot?.confirmedAt,
                            tracks = state.tracks.filter { !activeOnly || it.active },
                        )
                    )
                }

                get("/drones/positions.json") {
                    if (state.snapshot == null) return@get call.warmingUp()
                    val at = call.request.queryParameters["at"]?.let(::parseTime) ?: clock()
                    val positions = state.tracks
                        .filter { TrackInterpolator.isVisible(it, at) }
                        .mapNotNull { TrackInterpolator.positionAt(it, at) }
                    call.respond(DronePositionsResponse(at, positions))
                }
            }
        }
    }
}

private val CLIENT_LIMIT = RateLimitName("clients")

@Serializable
data class Health(
    val status: String,
    val demo: Boolean,
    val upstreamBudgetPerMinute: Int,
    val upstreamRequestsLastMinute: Int,
    val upstreamRequestsTotal: Long,
    val pollIntervalSeconds: Long,
    val lastAttemptAt: Long?,
    val nextPollAt: Long?,
    val dataVersion: Long?,
    val dataConfirmedAt: Long?,
    val alerts: Int,
    val tracks: Int,
    val activeTracks: Int,
    val lastError: String?,
)

@Serializable
private data class ErrorBody(val message: String)

private class ClientAuthConfig {
    var keys: Set<String> = emptySet()
}

/** Requires `Authorization: Bearer <key>` or `?token=<key>` when client keys are configured. */
private val ClientAuth = createRouteScopedPlugin("ClientAuth", ::ClientAuthConfig) {
    val keys = pluginConfig.keys
    onCall { call ->
        if (keys.isEmpty()) return@onCall
        val bearer = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
        val key = bearer ?: call.request.queryParameters["token"]
        if (key !in keys) call.respond(HttpStatusCode.Unauthorized, ErrorBody("Missing or invalid client key"))
    }
}

private suspend fun ApplicationCall.warmingUp() {
    response.header(HttpHeaders.RetryAfter, "5")
    respond(HttpStatusCode.ServiceUnavailable, ErrorBody("Proxy is warming up, no upstream data yet"))
}

/** Accepts epoch milliseconds or ISO-8601. */
private fun parseTime(value: String): Long? =
    value.toLongOrNull() ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

private fun httpDate(epochMs: Long): String =
    DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC))

fun defaultUpstreamClient() = HttpClient(ClientCIO) {
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
    }
}
