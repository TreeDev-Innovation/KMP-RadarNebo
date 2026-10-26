package com.gusak.lab.radarnebo.debug

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Clock

/**
 * One HTTP exchange, as the request inspectors (the app's debug window, the server console) show it.
 * Credentials are masked before an exchange is created.
 */
data class HttpExchange(
    val startedAt: Long,
    val direction: Direction,
    val method: String,
    val url: String,
    /** Who sent an [Direction.Incoming] request; null for requests this side made. */
    val remoteAddress: String?,
    val status: Int?,
    val durationMs: Long,
    val requestHeaders: List<Pair<String, String>>,
    val responseHeaders: List<Pair<String, String>>,
    val contentType: String?,
    /** Text bodies only, cut at [MAX_BODY_CHARS]; null for binary, streamed or empty bodies. */
    val responseBody: String?,
    /** Why the exchange failed before a response arrived. */
    val error: String? = null,
    /** Assigned by [TrafficLog]; increases with each exchange. */
    val id: Long = 0,
) {
    enum class Direction {
        /** A request this side received (a client calling the server). */
        Incoming,

        /** A request this side made (the app calling its server, the server calling alerts.in.ua). */
        Outgoing,
    }

    val userAgent get() = header(requestHeaders, HttpHeaders.UserAgent)
    /** The URL without scheme and host; [url] already is one for incoming requests. */
    val path get() = if ("://" in url) "/" + url.substringAfter("://").substringAfter('/', "") else url
    val host get() = if ("://" in url) url.substringAfter("://").substringBefore('/') else null

    companion object {
        const val MAX_BODY_CHARS = 2_000_000

        private fun header(headers: List<Pair<String, String>>, name: String) =
            headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }
}

/** Receives every exchange. It runs on request threads, so it must be quick and thread-safe. */
fun interface TrafficRecorder {
    fun record(exchange: HttpExchange)
}

/** The latest [capacity] exchanges, newest last, for an inspector to show. */
@OptIn(ExperimentalAtomicApi::class)
class TrafficLog(private val capacity: Int = 200) : TrafficRecorder {
    private val _exchanges = MutableStateFlow<List<HttpExchange>>(emptyList())
    val exchanges: StateFlow<List<HttpExchange>> = _exchanges.asStateFlow()

    // Ids keep counting across clear(), so an inspector's list keys never repeat.
    private val lastId = AtomicLong(0)

    override fun record(exchange: HttpExchange) {
        val numbered = exchange.copy(id = lastId.incrementAndFetch())
        _exchanges.update { list -> (list + numbered).takeLast(capacity) }
    }

    fun clear() = _exchanges.update { emptyList() }
}

object TrafficMasking {
    const val MASK = "***"
    private val tokenParameter = Regex("([?&]token=)[^&]*")

    /** alerts.in.ua-style clients may pass their key as `?token=`. */
    fun url(url: String) = url.replace(tokenParameter, "$1$MASK")

    fun headers(headers: Headers) = headers.entries().flatMap { (name, values) ->
        values.map { name to if (name.equals(HttpHeaders.Authorization, ignoreCase = true)) MASK else it }
    }

    fun isText(type: ContentType?) = type != null &&
        (type.match(ContentType.Application.Json) || type.contentType == "text" || type.contentSubtype.endsWith("+json"))

    fun capped(body: String) =
        if (body.length > HttpExchange.MAX_BODY_CHARS) body.take(HttpExchange.MAX_BODY_CHARS) + "\n… (cut)" else body
}

/** Records each request a client makes, with its response or the error that stopped it. */
fun httpTrafficCapture(recorder: TrafficRecorder) = createClientPlugin("HttpTrafficCapture") {
    on(Send) { request ->
        val started = Clock.System.now().toEpochMilliseconds()
        val url = TrafficMasking.url(request.url.buildString())
        val method = request.method.value
        val requestHeaders = TrafficMasking.headers(request.headers.build())
        fun record(status: Int?, headers: List<Pair<String, String>>, type: ContentType?, body: String?, error: String?) =
            recorder.record(
                HttpExchange(
                    startedAt = started,
                    direction = HttpExchange.Direction.Outgoing,
                    method = method,
                    url = url,
                    remoteAddress = null,
                    status = status,
                    durationMs = Clock.System.now().toEpochMilliseconds() - started,
                    requestHeaders = requestHeaders,
                    responseHeaders = headers,
                    contentType = type?.toString(),
                    responseBody = body,
                    error = error,
                )
            )
        val call = try {
            proceed(request)
        } catch (e: Throwable) {
            record(null, emptyList(), null, null, e.message ?: e::class.simpleName)
            throw e
        }
        val response = call.response
        val type = response.contentType()
        // Ktor keeps the body in memory, so the caller can still read it afterwards.
        val body = if (TrafficMasking.isText(type)) runCatching { TrafficMasking.capped(response.bodyAsText()) }.getOrNull() else null
        record(response.status.value, TrafficMasking.headers(response.headers), type, body?.ifEmpty { null }, null)
        call
    }
}
