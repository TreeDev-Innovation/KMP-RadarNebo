package com.gusak.lab.radarnebo.server.infrastructure.upstream

import com.gusak.lab.radarnebo.server.Archives
import com.gusak.lab.radarnebo.server.ServerComponents
import com.gusak.lab.radarnebo.server.application.FetchResult
import com.gusak.lab.radarnebo.server.config.ServerConfig
import com.gusak.lab.radarnebo.server.config.UpstreamConfig
import com.gusak.lab.radarnebo.debug.HttpExchange
import com.gusak.lab.radarnebo.debug.TrafficLog
import com.gusak.lab.radarnebo.debug.httpTrafficCapture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AlertsInUaSourceTest {

    private fun alertsJson(uid: String, title: String, oblastUid: Int, startedAt: String) = """
        {"alerts":[{"id":${uid},"location_title":"$title","location_type":"hromada","alert_type":"air_raid",
          "location_uid":"$uid","location_oblast":"x","location_oblast_uid":$oblastUid,"alert_level":"red",
          "started_at":"$startedAt","some_new_field":1,
          "threats":[{"threat_type":"drones","level":"red","started_at":"$startedAt"}]}]}
    """.trimIndent()

    @Test
    fun pollerStaysUnderUpstreamLimitEvenWhenUpstreamFails() = runBlocking {
        // Virtual time: the limiter sleeps by advancing it, the poller's waits are added to it.
        var now = 0L
        val hits = mutableListOf<Long>()
        val engine = MockEngine {
            hits += now
            respond("boom", HttpStatusCode.InternalServerError)
        }
        val upstream = UpstreamConfig(token = "t", maxPerMinute = 50, pollInterval = 1.seconds)
        val source = AlertsInUaSource(
            upstream, HttpClient(engine) { expectSuccess = false },
            UpstreamRateLimiter(upstream.effectivePerMinute, clock = { now }, sleep = { now += it }),
            clock = { now },
        )
        val server = ServerComponents.create(ServerConfig(upstream = upstream), clock = { now }, source = source, archives = Archives.inMemory())
        while (now < 30 * 60_000L) now += server.poller.pollOnce()

        hits.forEach { start -> assertTrue(hits.count { it in start until start + 60_000 } <= 8) }
        assertTrue(hits.size in 5..10, "failures back off exponentially up to 10 minutes, got ${hits.size} requests in 30 min")
        assertTrue(hits.zipWithNext { a, b -> b - a }.zipWithNext().all { (a, b) -> b >= a }, "waits only grow")
        assertTrue(server.poller.status.lastError!!.contains("500"))
    }

    @Test
    fun pollerUsesConditionalRequestsAndBacksOffOn429() = runTest {
        val sentIfModifiedSince = mutableListOf<String?>()
        var call = 0
        val engine = MockEngine { request ->
            sentIfModifiedSince += request.headers[HttpHeaders.IfModifiedSince]
            assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
            when (call++) {
                0 -> respond(
                    alertsJson("1100", "Броварська територіальна громада", 14, "2026-10-19T10:00:00Z"),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.LastModified to listOf("Mon, 19 Oct 2026 10:00:00 GMT"), HttpHeaders.ContentType to listOf("application/json")),
                )
                1 -> respond("", HttpStatusCode.NotModified)
                else -> respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "120"))
            }
        }
        val upstream = UpstreamConfig(token = "secret")
        val source = AlertsInUaSource(
            upstream, HttpClient(engine) { expectSuccess = false },
            UpstreamRateLimiter(upstream.effectivePerMinute, clock = { testScheduler.currentTime }),
            clock = { testScheduler.currentTime },
        )
        val server = ServerComponents.create(ServerConfig(upstream = upstream), clock = { testScheduler.currentTime }, source = source, archives = Archives.inMemory())
        val situation = server.situation

        assertEquals(15_000, server.poller.pollOnce())
        assertEquals(1, situation.board?.version)
        assertEquals("1100", situation.activeAlerts().single().location.uid, "unknown upstream fields are ignored")
        assertEquals(15_000, server.poller.pollOnce())
        assertEquals(1, situation.board?.version, "304 keeps the cached version")
        assertEquals(120_000, server.poller.pollOnce(), "Retry-After is honoured")
        assertEquals(listOf(null, "Mon, 19 Oct 2026 10:00:00 GMT", "Mon, 19 Oct 2026 10:00:00 GMT"), sentIfModifiedSince)
        assertTrue(server.poller.status.lastError!!.contains("429"))
        assertEquals(1, situation.tracks.size, "tracked from the alerts")
        assertTrue(situation.tracks.single().id.startsWith("trk-"), situation.tracks.single().id)
    }

    @Test
    fun upstreamTrafficIsRecordedAndTheBodyStillParses() = runBlocking {
        val log = TrafficLog()
        val engine = MockEngine {
            respond(alertsJson("7", "Бровари", 14, "2026-10-25T10:00:00.000Z"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val upstream = UpstreamConfig(token = "secret-token")
        val source = AlertsInUaSource(
            upstream, HttpClient(engine) { expectSuccess = false; install(httpTrafficCapture(log)) },
            UpstreamRateLimiter(upstream.effectivePerMinute, sleep = {}),
        )
        val result = source.fetch(null)
        assertIs<FetchResult.Fresh>(result, "the source still reads the body")
        val exchange = log.exchanges.value.single()
        assertEquals(HttpExchange.Direction.Outgoing, exchange.direction)
        assertEquals(200, exchange.status)
        assertTrue("Бровари" in exchange.responseBody!!)
        assertEquals("***", exchange.requestHeaders.single { it.first == HttpHeaders.Authorization }.second, "the token is masked")
    }
}
