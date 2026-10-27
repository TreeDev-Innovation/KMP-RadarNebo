package com.gusak.lab.radarnebo.server.api

import com.gusak.lab.radarnebo.server.application.SituationService
import com.gusak.lab.radarnebo.server.domain.alert.AlertBoard
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaInstant

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

/**
 * Conditional GET over data derived from [board]: `ETag` from the board version, `Last-Modified` from when
 * it changed. Returns true (and has answered 304) when the client's copy is current.
 */
internal suspend fun ApplicationCall.notModifiedSince(board: AlertBoard, maxAge: Duration, nowMs: Long): Boolean {
    val etag = "\"v${board.version}\""
    val lastModified = httpDate(board.changedAt)
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.LastModified, lastModified)
    response.header(HttpHeaders.CacheControl, "max-age=${maxAge.inWholeSeconds}")
    response.header(DATA_AGE_HEADER, ((nowMs - board.confirmedAt.toEpochMilliseconds()) / 1000).coerceAtLeast(0).toString())
    val current = request.header(HttpHeaders.IfNoneMatch)?.let { etag in it.split(',').map(String::trim) }
        ?: (request.header(HttpHeaders.IfModifiedSince) == lastModified)
    if (current) respond(HttpStatusCode.NotModified)
    return current
}

/** Seconds since the data source last confirmed the data. */
internal const val DATA_AGE_HEADER = "X-Data-Age-Seconds"

internal fun httpDate(instant: Instant): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.toJavaInstant().atZone(ZoneOffset.UTC))

/** Epoch milliseconds or ISO-8601. */
internal fun parseTime(value: String): Long? = value.toLongOrNull() ?: runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
