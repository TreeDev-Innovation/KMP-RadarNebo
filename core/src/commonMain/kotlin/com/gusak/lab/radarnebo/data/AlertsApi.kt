package com.gusak.lab.radarnebo.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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

/**
 * Thin client for the alerts.in.ua public API.
 *
 * The API is rate limited to ~8-10 requests per minute per IP, so callers should poll no faster
 * than every 15 seconds. `If-Modified-Since` is sent so unchanged data costs a cheap 304.
 */
class AlertsApi(
    private val client: HttpClient = defaultClient(),
) {
    private var lastModified: String? = null
    private var lastKey: String? = null

    suspend fun activeAlerts(baseUrl: String, token: String): FetchResult = try {
        val key = "$baseUrl|$token"
        if (key != lastKey) {
            lastModified = null
            lastKey = key
        }
        val response = client.get("${baseUrl.trimEnd('/')}$ACTIVE_ALERTS_PATH") {
            if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
            lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
        }
        when (response.status) {
            HttpStatusCode.OK -> {
                lastModified = response.headers[HttpHeaders.LastModified]
                FetchResult.Fresh(response.body<ActiveAlertsResponse>().alerts)
            }
            HttpStatusCode.NotModified -> FetchResult.NotModified
            HttpStatusCode.Unauthorized -> FetchResult.Failure("API token is missing, invalid or expired (401)")
            HttpStatusCode.Forbidden -> FetchResult.Failure("Access forbidden: IP blocked or API unavailable in your country (403)")
            HttpStatusCode.TooManyRequests -> FetchResult.Failure("Rate limit exceeded, slowing down (429)")
            else -> FetchResult.Failure("Unexpected response ${response.status.value}")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        FetchResult.Failure(e.message ?: "Network error")
    }

    /**
     * Drone position history served by the RadarNebo proxy (`:server`). Returns null when talking to
     * alerts.in.ua directly, which has no such endpoint, or on any failure.
     */
    suspend fun droneTracks(baseUrl: String, token: String): DroneTracksResponse? = try {
        val response = client.get("${baseUrl.trimEnd('/')}$TRACKS_PATH") {
            if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (response.status == HttpStatusCode.OK) response.body<DroneTracksResponse>() else null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.alerts.in.ua"
        const val ACTIVE_ALERTS_PATH = "/v1/alerts/active.json"
        const val TRACKS_PATH = "/v1/drones/tracks.json"
        const val POSITIONS_PATH = "/v1/drones/positions.json"

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        fun defaultClient() = HttpClient {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
            }
        }
    }
}
