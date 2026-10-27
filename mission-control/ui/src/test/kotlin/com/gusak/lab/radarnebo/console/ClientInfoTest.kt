package com.gusak.lab.radarnebo.console

import com.gusak.lab.radarnebo.debug.HttpExchange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientInfoTest {
    private val address = "192.168.1.20"

    private fun exchange(
        url: String = "/v1/alerts/active.json",
        status: Int? = 200,
        at: Long = 1_000,
        headers: List<Pair<String, String>> = emptyList(),
    ) = HttpExchange(
        startedAt = at,
        direction = HttpExchange.Direction.Incoming,
        method = "GET",
        url = url,
        remoteAddress = address,
        status = status,
        durationMs = 5,
        requestHeaders = headers,
        responseHeaders = emptyList(),
        contentType = null,
        responseBody = null,
    )

    private fun seen(vararg exchanges: HttpExchange): ClientInfo =
        exchanges.fold(null as ClientInfo?) { client, exchange -> client.after(exchange, address) }!!

    @Test
    fun countsRequestsAndErrors() {
        val client = seen(exchange(status = 200), exchange(status = null), exchange(status = 404), exchange(status = 399))
        assertEquals(4, client.requests)
        assertEquals(2, client.errors, "no status and 400+ are errors")
        assertEquals(399, client.lastStatus)
    }

    @Test
    fun keepsFirstSeenAndTracksTheLatestRequest() {
        val client = seen(exchange(at = 1_000), exchange(url = "/health", at = 5_000))
        assertEquals(1_000, client.firstSeen)
        assertEquals(5_000, client.lastSeen)
        assertEquals("GET /health", client.lastRequest)
    }

    @Test
    fun aKeyOnceUsedStaysUsed() {
        assertTrue(seen(exchange(headers = listOf("authorization" to "Bearer x")), exchange()).usedKey)
        assertTrue(seen(exchange(url = "/v1/alerts/active.json?token=x"), exchange()).usedKey)
        assertTrue(seen(exchange(url = "/v1/alerts/active.json?a=1&token=x")).usedKey)
        assertFalse(seen(exchange()).usedKey)
    }

    @Test
    fun aParameterEndingInTokenIsNotAKey() {
        assertFalse(seen(exchange(url = "/v1/alerts/active.json?mytoken=x")).usedKey)
    }

    @Test
    fun keepsThePreviousUserAgentWhenARequestHasNone() {
        val client = seen(exchange(headers = listOf("User-Agent" to "RadarNebo/1.0")), exchange())
        assertEquals("RadarNebo/1.0", client.userAgent)
        assertEquals("curl/8", seen(exchange(headers = listOf("User-Agent" to "RadarNebo/1.0")), exchange(headers = listOf("User-Agent" to "curl/8"))).userAgent)
    }
}
