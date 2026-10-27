package com.gusak.lab.radarnebo.server.api

import com.gusak.lab.radarnebo.server.application.SituationPoller
import com.gusak.lab.radarnebo.server.application.SituationService
import com.gusak.lab.radarnebo.server.config.ClientApiConfig
import com.gusak.lab.radarnebo.debug.TrafficRecorder
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
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.RateLimitProviderConfig
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
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
    /** Receives every call with its response, for the server console. */
    traffic: TrafficRecorder? = null,
) {
    if (clients.trustProxyHeaders) install(XForwardedHeaders) { useLastProxy() }
    if (traffic != null) install(ServerTrafficCapture) { recorder = traffic }
    install(ContentNegotiation) { json(AlertsInUaContract.json) }
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
    val log = LoggerFactory.getLogger("RadarApi")
    installErrorPages(log)
    installRateLimits(clients)
    val authRequired = clients.keys.isNotEmpty()
    if (authRequired) installClientAuth(clients) else log.warn("Client API is open: CLIENT_KEYS is not set")

    val pollInterval = { poller.source.pollInterval }
    routing {
        // Throttled like everything else but never authenticated, so container health checks and monitors work.
        rateLimit(CLIENTS) {
            healthRoutes(situation, poller, upstreamBudgetPerMinute)
        }
        // The limits wrap authentication, so callers guessing keys are throttled too.
        rateLimit(CLIENTS) {
            clientRoute(authRequired) {
                alertsInUaRoutes(situation, pollInterval, clock)
                trackingRoutes(situation, pollInterval, clock)
            }
        }
        rateLimit(HISTORY) {
            clientRoute(authRequired) {
                alertHistoryRoutes(situation, clock)
            }
        }
    }
}

/** Every error as `{"message": "..."}`, like alerts.in.ua. */
private fun Application.installErrorPages(log: Logger) {
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorBody(e.message)) }
        exception<BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorBody(e.message ?: "Bad request")) }
        // Ktor picks the most specific handler, so this one wins over ApiException's and adds Retry-After.
        exception<WarmingUpException> { call, e ->
            call.response.header(HttpHeaders.RetryAfter, "5")
            call.respond(e.status, ErrorBody(e.message))
        }
        exception<Throwable> { call, e ->
            // A cancelled call isn't a failure to report; let the engine see the cancellation.
            if (e is CancellationException) throw e
            // The path only: the query may hold a client key (`?token=`).
            log.error("Request failed: {}", call.request.path(), e)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("API error"))
        }
        status(HttpStatusCode.Unauthorized) { call, status -> call.respond(status, ErrorBody("Missing or invalid client key")) }
        status(HttpStatusCode.TooManyRequests) { call, status -> call.respond(status, ErrorBody("Too many requests")) }
        status(HttpStatusCode.NotFound) { call, status -> call.respond(status, ErrorBody("Not found")) }
    }
}

/** Per-IP limits; history has its own, stricter one. */
private fun Application.installRateLimits(clients: ClientApiConfig) {
    install(RateLimit) {
        register(CLIENTS) { perClientIp(clients.requestsPerMinute) }
        register(HISTORY) { perClientIp(clients.historyRequestsPerMinute) }
    }
}

private fun RateLimitProviderConfig.perClientIp(limit: Int) {
    rateLimiter(limit = limit, refillPeriod = 1.minutes)
    requestKey { call -> call.request.origin.remoteAddress }
}

/** Bearer client keys, from [ClientApiConfig.keys]. */
private fun Application.installClientAuth(clients: ClientApiConfig) {
    install(Authentication) {
        bearer(CLIENT_AUTH) {
            // alerts.in.ua accepts the token as a query parameter as well as a header.
            // Ktor rejects a token that isn't token68; that is the client's mistake (400), as for the header.
            authHeader { call ->
                call.request.queryParameters["token"]?.let { token ->
                    runCatching { HttpAuthHeader.Single("Bearer", token) }.getOrElse { throw BadRequestException("Invalid client key") }
                } ?: call.request.parseAuthorizationHeader()
            }
            authenticate { credential -> if (credential.token in clients.keys) UserIdPrincipal("client") else null }
        }
    }
}

/** Routes clients reach with a key when [authRequired], freely otherwise. */
private fun Route.clientRoute(authRequired: Boolean, build: Route.() -> Unit) {
    if (authRequired) authenticate(CLIENT_AUTH) { build() } else build()
}
