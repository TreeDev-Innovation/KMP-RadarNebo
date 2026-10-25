package com.gusak.lab.radarnebo.server.infrastructure.upstream

import com.gusak.lab.radarnebo.server.Archives
import com.gusak.lab.radarnebo.server.ServerComponents
import com.gusak.lab.radarnebo.server.config.ServerConfig
import com.gusak.lab.radarnebo.server.config.UpstreamConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun limiterNeverExceedsBudgetInAnyRollingMinute() = runTest {
        val limiter = UpstreamRateLimiter(maxPerWindow = 8, clock = { testScheduler.currentTime })
        val times = mutableListOf<Long>()
        // A caller hammering acquire() as fast as possible for 10 virtual minutes.
        val job = launch { while (true) { limiter.acquire(); times += testScheduler.currentTime } }
        testScheduler.advanceTimeBy(10 * 60_000L)
        job.cancel()
        assertTrue(times.size in 75..81, "expected ~80 requests in 10 min, got ${times.size}")
        times.forEach { start -> assertTrue(times.count { it in start until start + 60_000 } <= 8) }
    }

    @Test
    fun configIsClampedBelowSoftLimit() {
        val reckless = UpstreamConfig(token = "t", maxPerMinute = 50, pollInterval = 1.seconds)
        assertEquals(UpstreamConfig.HARD_CEILING_PER_MINUTE, reckless.effectivePerMinute)
        assertEquals(7_500, reckless.effectivePollInterval.inWholeMilliseconds)
    }

    @Test
    fun theServerPicksItsSourceFromTheConfiguration() {
        assertEquals("Simulation", ServerConfig.fromEnv(emptyMap()).dataSource.name)
        assertEquals("AlertsInUa", ServerConfig.fromEnv(mapOf("ALERTS_API_TOKEN" to "t")).dataSource.name)
        assertEquals("Simulation", ServerConfig.fromEnv(mapOf("ALERTS_API_TOKEN" to "t", "DATA_SOURCE" to "simulation")).dataSource.name)
        assertEquals(setOf("a", "b"), ServerConfig.fromEnv(mapOf("CLIENT_KEYS" to " a, b ,")).clients.keys)
    }

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
}
