package com.gusak.lab.radarnebo.server.api

import com.gusak.lab.radarnebo.server.application.SituationPoller
import com.gusak.lab.radarnebo.server.application.SituationService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/** For operators and load balancers; not part of the client API, so it needs no client key. */
@Serializable
data class Health(
    val status: String,
    val source: String,
    val simulated: Boolean,
    val upstreamBudgetPerMinute: Int?,
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

internal fun Route.healthRoutes(situation: SituationService, poller: SituationPoller, upstreamBudgetPerMinute: Int?) {
    get("/health") {
        val board = situation.board
        val status = poller.status
        val source = poller.source
        call.respond(
            Health(
                status = when {
                    board == null -> "warming_up"
                    status.lastError != null -> "degraded"
                    else -> "ok"
                },
                source = source.kind.name,
                simulated = situation.simulated,
                upstreamBudgetPerMinute = upstreamBudgetPerMinute,
                upstreamRequestsLastMinute = source.requestsInLastMinute,
                upstreamRequestsTotal = source.requestsTotal,
                pollIntervalSeconds = source.pollInterval.inWholeSeconds,
                lastAttemptAt = status.lastAttemptAt,
                nextPollAt = status.nextPollAt,
                dataVersion = board?.version,
                dataConfirmedAt = board?.confirmedAt?.toEpochMilliseconds(),
                alerts = board?.alerts?.size ?: 0,
                tracks = situation.tracks.size,
                activeTracks = situation.tracks.count { it.active },
                lastError = status.lastError,
            )
        )
    }
}
