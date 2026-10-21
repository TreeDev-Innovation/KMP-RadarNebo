package com.gusak.lab.radarnebo.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import com.gusak.lab.radarnebo.tracking.DroneTracksResponse
import kotlinx.serialization.json.Json

sealed interface FetchResult {
    data class Fresh(val alerts: List<Alert>) : FetchResult
    data object NotModified : FetchResult
    data class Failure(val message: String) : FetchResult
}

/** The RadarNebo server the app talks to. [accessKey] is only needed when the server requires client keys. */
data class ServerEndpoint(val url: String, val accessKey: String = "")

/**
 * Client for the RadarNebo server (`:server`), the app's only data source. The server decides where its
 * data comes from (alerts.in.ua or its simulation), tracks targets and archives history; the app only reads.
 *
 * Alerts are served in the alerts.in.ua format at the same path. The server caches them and answers
 * `If-Modified-Since` with a cheap 304 when nothing changed.
 */
class RadarNeboApi(
    private val client: HttpClient = defaultClient(),
) {
    private var lastModified: String? = null
    private var lastEndpoint: ServerEndpoint? = null

    suspend fun activeAlerts(endpoint: ServerEndpoint): FetchResult = try {
        if (endpoint != lastEndpoint) {
            lastModified = null
            lastEndpoint = endpoint
        }
        val response = client.get(endpoint.resolve(ACTIVE_ALERTS_PATH)) {
            authorize(endpoint)
            lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
        }
        when (response.status) {
            HttpStatusCode.OK -> {
                lastModified = response.headers[HttpHeaders.LastModified]
                FetchResult.Fresh(response.body<ActiveAlertsResponse>().alerts)
            }
            HttpStatusCode.NotModified -> FetchResult.NotModified
            HttpStatusCode.Unauthorized -> FetchResult.Failure("The server needs a valid access key (401)")
            HttpStatusCode.TooManyRequests -> FetchResult.Failure("Too many requests, slowing down (429)")
            HttpStatusCode.ServiceUnavailable -> FetchResult.Failure("The server is starting up, no data yet (503)")
            else -> FetchResult.Failure("Unexpected response ${response.status.value}")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        FetchResult.Failure(e.message ?: "Network error")
    }

    /** Tracked targets with their position history; null on any failure. */
    suspend fun tracks(endpoint: ServerEndpoint): DroneTracksResponse? = try {
        val response = client.get(endpoint.resolve(TRACKS_PATH)) { authorize(endpoint) }
        if (response.status == HttpStatusCode.OK) response.body<DroneTracksResponse>() else null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun ServerEndpoint.resolve(path: String) = "${url.trimEnd('/')}$path"

    private fun HttpRequestBuilder.authorize(endpoint: ServerEndpoint) {
        if (endpoint.accessKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${endpoint.accessKey}")
    }

    companion object {
        /** A server on the same machine; the Android emulator reaches the host as 10.0.2.2 instead. */
        const val DEFAULT_SERVER_URL = "http://localhost:8080"

        const val ACTIVE_ALERTS_PATH = "/v1/alerts/active.json"
        const val TRACKS_PATH = "/v1/drones/tracks.json"
        const val POSITIONS_PATH = "/v1/drones/positions.json"
        const val TRACK_ARCHIVE_PATH = "/v1/drones/archive.json"

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        fun defaultClient() = HttpClient { configure() }

        /** A client on [engine], configured like the default one; tests pass a mock engine. */
        fun client(engine: HttpClientEngine) = HttpClient(engine) { configure() }

        private fun HttpClientConfig<*>.configure() {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
            }
        }
    }
}
