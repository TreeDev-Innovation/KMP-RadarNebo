package com.gusak.lab.radarnebo.server.api

import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.server.application.SituationService
import com.gusak.lab.radarnebo.server.domain.tracking.TrackQuery
import com.gusak.lab.radarnebo.tracking.DronePositionsResponse
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.DroneTracksResponse
import com.gusak.lab.radarnebo.tracking.TrackInterpolator
import io.ktor.http.Parameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** Tracks that were in the air in a time window, from the archive. */
@Serializable
data class TrackHistoryResponse(
    val from: Long,
    val to: Long,
    val tracks: List<DroneTrack>,
    val simulated: Boolean,
)

/**
 * RadarNebo's own API on top of alerts.in.ua's: the targets the server tracks, where they are, and where
 * they flew. All times are epoch milliseconds; `at`, `from` and `to` also accept ISO-8601.
 */
internal fun Route.trackingRoutes(situation: SituationService, pollInterval: () -> Duration, clock: () -> Long) {
    /** Every tracked target with its position history; `?active=true` leaves out ended ones. */
    get("/v1/drones/tracks.json") {
        val board = situation.board.orWarmingUp()
        val activeOnly = call.request.queryParameters["active"] == "true"
        call.respond(
            DroneTracksResponse(
                serverTime = clock(),
                pollIntervalMs = pollInterval().inWholeMilliseconds,
                lastUpstreamFetch = board.confirmedAt.toEpochMilliseconds(),
                tracks = situation.tracks.filter { !activeOnly || it.active },
                simulated = situation.simulated,
            )
        )
    }

    /** Every visible target's position at `at` (default: now), interpolated between reports or dead-reckoned past the last. */
    get("/v1/drones/positions.json") {
        situation.requireData()
        val at = call.request.queryParameters.time("at") ?: clock()
        val positions = situation.tracks
            .filter { TrackInterpolator.isVisible(it, at) }
            .mapNotNull { TrackInterpolator.positionAt(it, at) }
        call.respond(DronePositionsResponse(at, positions))
    }
}

/** The track archive, on its own rate limit: each call can read a week of tracks. */
internal fun Route.trackArchiveRoutes(situation: SituationService, clock: () -> Long) {
    /** Archived tracks in the air between `from` (default: a day ago) and `to` (default: now), optionally of one `type`. */
    get("/v1/drones/archive.json") {
        situation.requireData()
        val params = call.request.queryParameters
        val to = params.time("to") ?: clock()
        val from = params.time("from") ?: (to - 1.days.inWholeMilliseconds)
        val type = params["type"]?.let { value ->
            ThreatType.entries.firstOrNull { it.apiValue == value } ?: badRequest("Unknown type '$value'")
        }
        val limit = params["limit"]?.let { it.toIntOrNull() ?: badRequest("'limit' must be a number") } ?: TrackQuery.DEFAULT_LIMIT
        val query = try {
            TrackQuery(from, to, type, limit)
        } catch (e: IllegalArgumentException) {
            badRequest(e.message ?: "Invalid query")
        }
        call.respond(TrackHistoryResponse(from, to, situation.trackHistory(query), situation.simulated))
    }
}

/** The time in parameter [name], or null when absent; answers 400 when it is neither epoch milliseconds nor ISO-8601. */
private fun Parameters.time(name: String): Long? =
    this[name]?.let { parseTime(it) ?: badRequest("'$name' must be epoch milliseconds or ISO-8601") }
