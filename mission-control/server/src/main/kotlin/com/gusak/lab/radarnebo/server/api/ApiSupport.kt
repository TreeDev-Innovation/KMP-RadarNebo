package com.gusak.lab.radarnebo.server.api

import com.gusak.lab.radarnebo.server.application.SituationService
import com.gusak.lab.radarnebo.server.domain.alert.AlertBoard
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Error body, as alerts.in.ua sends it: `{"message": "..."}`. */
@Serializable
data class ErrorBody(val message: String)

/** Ends a request with [status] and an [ErrorBody]; turned into the response by StatusPages. */
internal open class ApiException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

internal fun badRequest(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, message)

internal fun notFound(message: String): Nothing = throw ApiException(HttpStatusCode.NotFound, message)

/** Thrown until the first report from the data source has been published; answered with a `Retry-After`. */
internal class WarmingUpException : ApiException(HttpStatusCode.ServiceUnavailable, "The server is warming up, no data yet")

/** The board, or a 503 while the server has no data yet. */
internal fun AlertBoard?.orWarmingUp(): AlertBoard = this ?: throw WarmingUpException()

/** Answers 503 while the server has no data yet. */
internal fun SituationService.requireData() {
    board.orWarmingUp()
}

/** Tells the client how old [board]'s data is: seconds since the data source last confirmed it. */
internal fun ApplicationCall.dataAge(board: AlertBoard, nowMs: Long) {
    response.header(DATA_AGE_HEADER, ((nowMs - board.confirmedAt.toEpochMilliseconds()) / 1000).coerceAtLeast(0).toString())
}

/** Seconds since the data source last confirmed the data. */
internal const val DATA_AGE_HEADER = "X-Data-Age-Seconds"

/** Epoch milliseconds or ISO-8601. */
internal fun parseTime(value: String): Long? = value.toLongOrNull() ?: runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
