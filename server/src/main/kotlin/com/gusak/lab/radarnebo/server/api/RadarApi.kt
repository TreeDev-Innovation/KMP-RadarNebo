package com.gusak.lab.radarnebo.server.api

import com.gusak.lab.radarnebo.server.application.SituationPoller
import com.gusak.lab.radarnebo.server.application.SituationService
import com.gusak.lab.radarnebo.server.config.ClientApiConfig
import com.gusak.lab.radarnebo.server.contract.AlertsInUaContract
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

private val CLIENTS = RateLimitName("clients")
private val HISTORY = RateLimitName("history")
private const val CLIENT_AUTH = "client-key"

/**
 * The server's HTTP API: the alerts.in.ua API ([alertsInUaRoutes], [alertHistoryRoutes]) served from the
 * server's own data, RadarNebo's tracking API ([trackingRoutes]) and a health check.
 *
 * Like alerts.in.ua, clients authenticate with `Authorization: Bearer <key>` or `?token=<key>` when
 * [ClientApiConfig.keys] is set, are rate limited per IP (history separately and more strictly) and get
 * errors as `{"message": "..."}`. Clients never learn where the data comes from, apart from a `simulated`
 * flag so they can label simulated data.
 */
fun Application.radarApi(
    situation: SituationService,
    poller: SituationPoller,
    clients: ClientApiConfig,
    upstreamBudgetPerMinute: Int?,
    clock: () -> Long = System::currentTimeMillis,
) {
    val log = LoggerFactory.getLogger("RadarApi")
    install(ContentNegotiation) { json(AlertsInUaContract.json) }
    install(CallLogging)
    install(Compression) {
        gzip { minimumSize(1_024) }
        deflate { minimumSize(1_024) }
    }
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.IfNoneMatch)
        allowHeader(HttpHeaders.IfModifiedSince)
        exposeHeader(HttpHeaders.ETag)
        exposeHeader(DATA_AGE_HEADER)
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorBody(e.message)) }
        exception<BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorBody(e.message ?: "Bad request")) }
        exception<WarmingUpException> { call, e ->
            call.response.header(HttpHeaders.RetryAfter, "5")
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorBody(e.message!!))
        }
        exception<Throwable> { call, e ->
            log.error("Request failed: ${call.request.local.uri}", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("API error"))
        }
        status(HttpStatusCode.Unauthorized) { call, status -> call.respond(status, ErrorBody("Missing or invalid client key")) }
        status(HttpStatusCode.TooManyRequests) { call, status -> call.respond(status, ErrorBody("Too many requests")) }
        status(HttpStatusCode.NotFound) { call, status -> call.respond(status, ErrorBody("Not found")) }
    }
    install(RateLimit) {
        register(CLIENTS) {
            rateLimiter(limit = clients.requestsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteAddress }
        }
        register(HISTORY) {
            rateLimiter(limit = clients.historyRequestsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }
    if (clients.keys.isNotEmpty()) {
        install(Authentication) {
            bearer(CLIENT_AUTH) {
                // alerts.in.ua accepts the token as a query parameter as well as a header.
                authHeader { call ->
                    call.request.queryParameters["token"]?.let { HttpAuthHeader.Single("Bearer", it) } ?: call.request.parseAuthorizationHeader()
                }
                authenticate { credential -> if (credential.token in clients.keys) UserIdPrincipal("client") else null }
            }
        }
    }

    val pollInterval = { poller.source.pollInterval }
    routing {
        healthRoutes(situation, poller, upstreamBudgetPerMinute)
        clientRoute(clients) {
            rateLimit(CLIENTS) {
                alertsInUaRoutes(situation, pollInterval, clock)
                trackingRoutes(situation, pollInterval, clock)
            }
            rateLimit(HISTORY) {
                alertHistoryRoutes(situation, clock)
            }
        }
    }
}

/** Routes clients reach with a key when keys are configured, freely otherwise. */
private fun Route.clientRoute(clients: ClientApiConfig, build: Route.() -> Unit) {
    if (clients.keys.isEmpty()) build() else authenticate(CLIENT_AUTH) { build() }
}
