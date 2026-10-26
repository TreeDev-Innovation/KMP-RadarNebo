package com.gusak.lab.radarnebo.server.api

import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.server.application.SituationService
import com.gusak.lab.radarnebo.server.domain.tracking.TrackQuery
import com.gusak.lab.radarnebo.tracking.DronePositionsResponse
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.DroneTracksResponse
import com.gusak.lab.radarnebo.tracking.TrackInterpolator
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlin.time.Duration

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
        situation.board.orWarmingUp()
        val at = call.request.queryParameters["at"]?.let { parseTime(it) ?: badRequest("'at' must be epoch milliseconds or ISO-8601") } ?: clock()
        val positions = situation.tracks
            .filter { TrackInterpolator.isVisible(it, at) }
            .mapNotNull { TrackInterpolator.positionAt(it, at) }
        call.respond(DronePositionsResponse(at, positions))
    }

    /** Archived tracks in the air between `from` (default: a day ago) and `to` (default: now), optionally of one `type`. */
    get("/v1/drones/archive.json") {
        situation.board.orWarmingUp()
        val params = call.request.queryParameters
        fun time(name: String) = params[name]?.let { parseTime(it) ?: badRequest("'$name' must be epoch milliseconds or ISO-8601") }
        val to = time("to") ?: clock()
        val from = time("from") ?: (to - DAY_MS)
        val type = params["type"]?.let { value ->
            ThreatType.entries.firstOrNull { it.apiValue == value } ?: badRequest("Unknown type '$value'")
        }
        val limit = params["limit"]?.let { it.toIntOrNull() ?: badRequest("'limit' must be a number") } ?: TrackQuery.DEFAULT_LIMIT
        val query = runCatching { TrackQuery(from, to, type, limit) }.getOrElse { badRequest(it.message ?: "Invalid query") }
        call.respond(TrackHistoryResponse(from, to, situation.trackHistory(query), situation.simulated))
    }
}

private const val DAY_MS = 24 * 3_600_000L
