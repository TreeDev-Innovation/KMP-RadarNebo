package com.gusak.lab.radarnebo.server

import com.gusak.lab.radarnebo.tracking.DronePositionsResponse
import com.gusak.lab.radarnebo.tracking.DroneTracksResponse
import com.gusak.lab.radarnebo.data.ActiveAlertsResponse
import com.gusak.lab.radarnebo.data.AlertsApi
import com.gusak.lab.radarnebo.data.ThreatType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProxyTest {

    private fun alertsJson(uid: String, title: String, oblastUid: Int, startedAt: String, type: String = "hromada") = """
        {"alerts":[{"id":${uid},"location_title":"$title","location_type":"$type","alert_type":"air_raid",
          "location_uid":"$uid","location_oblast":"x","location_oblast_uid":"$oblastUid","alert_level":"red",
          "started_at":"$startedAt",
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
        val reckless = ProxyConfig(upstreamToken = "t", maxUpstreamPerMinute = 50, pollInterval = 1.seconds)
        assertEquals(ProxyConfig.HARD_CEILING_PER_MINUTE, reckless.effectivePerMinute)
        assertEquals(7_500, reckless.effectivePollInterval.inWholeMilliseconds)
    }

    @Test
    fun pollerStaysUnderUpstreamLimitEvenWhenUpstreamFails() = runTest {
        val hits = mutableListOf<Long>()
        val engine = MockEngine {
            hits += testScheduler.currentTime
            respond("boom", HttpStatusCode.InternalServerError)
        }
        val config = ProxyConfig(upstreamToken = "t", maxUpstreamPerMinute = 50, pollInterval = 1.seconds)
        val poller = AlertsPoller(
            config,
            UpstreamAlertsSource(
                config, HttpClient(engine) { expectSuccess = false },
                UpstreamRateLimiter(config.effectivePerMinute, clock = { testScheduler.currentTime }),
            ),
            ProxyState(),
            clock = { testScheduler.currentTime },
        )
        val job = launch { poller.run() }
        testScheduler.advanceTimeBy(5 * 60_000L)
        job.cancel()
        hits.forEach { start -> assertTrue(hits.count { it in start until start + 60_000 } <= 8) }
        assertTrue(hits.size < 15, "failures must back off, got ${hits.size} requests")
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
                    alertsJson("1100", "Броварський район", 14, "2026-10-19T10:00:00Z"),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.LastModified to listOf("Mon, 19 Oct 2026 10:00:00 GMT"), HttpHeaders.ContentType to listOf("application/json")),
                )
                1 -> respond("", HttpStatusCode.NotModified)
                else -> respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "120"))
            }
        }
        val state = ProxyState()
        val config = ProxyConfig(upstreamToken = "secret")
        val poller = AlertsPoller(
            config,
            UpstreamAlertsSource(
                config, HttpClient(engine) { expectSuccess = false },
                UpstreamRateLimiter(config.effectivePerMinute, clock = { testScheduler.currentTime }),
            ),
            state,
            clock = { testScheduler.currentTime },
        )
        assertEquals(15_000, poller.pollOnce())
        assertEquals(1, state.snapshot?.version)
        assertEquals(15_000, poller.pollOnce())
        assertEquals(1, state.snapshot?.version, "304 keeps the cached version")
        assertEquals(120_000, poller.pollOnce(), "Retry-After is honoured")
        assertEquals(listOf(null, "Mon, 19 Oct 2026 10:00:00 GMT", "Mon, 19 Oct 2026 10:00:00 GMT"), sentIfModifiedSince)
        assertTrue(state.lastError!!.contains("429"))
        assertEquals(1, state.tracks.size)
    }

    @Test
    fun servesCacheTracksAndInterpolatedPositions() = testApplication {
        var now = 1_000_000L
        var payload = alertsJson("1100", "Броварська територіальна громада", 14, "2026-10-19T10:00:00Z")
        val upstream = MockEngine {
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val config = ProxyConfig(upstreamToken = "secret", clientKeys = setOf("app-key"))
        val state = ProxyState()
        val poller = AlertsPoller(
            config,
            UpstreamAlertsSource(
                config, HttpClient(upstream) { expectSuccess = false },
                UpstreamRateLimiter(config.effectivePerMinute, clock = { now }, sleep = { now += it }),
            ),
            state,
            clock = { now },
        )
        application { radarProxy(config, state, poller, startPoller = false, clock = { now }) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/alerts/active.json").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/v1/alerts/active.json") { header(HttpHeaders.Authorization, "Bearer app-key") }.status)

        poller.pollOnce()
        val first = client.get("/v1/alerts/active.json?token=app-key")
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(payload, first.bodyAsText())
        val etag = first.headers[HttpHeaders.ETag]!!
        val revalidated = client.get("/v1/alerts/active.json?token=app-key") { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, revalidated.status)

        // Twenty minutes later the threat moves on from Brovary to Kyiv.
        now += 20 * 60_000L
        payload = alertsJson("31", "м. Київ", 31, "2026-10-19T10:20:00Z", type = "city")
        poller.pollOnce()

        val tracks = AlertsApi.json.decodeFromString(
            DroneTracksResponse.serializer(),
            client.get("/v1/drones/tracks.json?token=app-key").bodyAsText(),
        )
        val track = tracks.tracks.single()
        assertEquals(2, track.samples.size, "the new alert continues the existing track")

        val midpoint = (track.samples[0].t + track.samples[1].t) / 2
        val positions = AlertsApi.json.decodeFromString(
            DronePositionsResponse.serializer(),
            client.get("/v1/drones/positions.json?token=app-key&at=$midpoint").bodyAsText(),
        )
        val p = positions.positions.single()
        assertEquals((track.samples[0].lat + track.samples[1].lat) / 2, p.lat, 1e-9)
        assertEquals((track.samples[0].lon + track.samples[1].lon) / 2, p.lon, 1e-9)

        val health = client.get("/health").bodyAsText()
        assertTrue("\"upstreamBudgetPerMinute\":6" in health, health)
    }

    @Test
    fun demoFeedServesEveryThreatClassAndTracksMissiles() = testApplication {
        var now = 1_800_000_000_000L
        // The test reads the feed hundreds of times in a few seconds; lift the per-client limit.
        val config = ProxyConfig(upstreamToken = null, clientRequestsPerMinute = 100_000)
        val state = ProxyState()
        val source = alertsSourceFor(config) { error("demo mode never creates an upstream client") }
        assertIs<SimulatedAlertsSource>(source)
        val poller = AlertsPoller(config, SimulatedAlertsSource(clock = { now }), state, clock = { now })
        application { radarProxy(config, state, poller, startPoller = false, clock = { now }) }

        // Three hours of the simulated feed, one poll a minute, as clients see it.
        val types = mutableSetOf<String>()
        val trackTypes = mutableSetOf<ThreatType>()
        repeat(180) {
            poller.pollOnce()
            val alerts = AlertsApi.json.decodeFromString(ActiveAlertsResponse.serializer(), client.get("/v1/alerts/active.json").bodyAsText())
            alerts.alerts.flatMapTo(types) { alert -> alert.threats.map { it.threatType } }
            val tracks = AlertsApi.json.decodeFromString(DroneTracksResponse.serializer(), client.get("/v1/drones/tracks.json").bodyAsText())
            tracks.tracks.mapTo(trackTypes) { it.type }
            now += 60_000L
        }
        assertEquals((ThreatType.entries - ThreatType.Unknown).map { it.apiValue }.toSet(), types)
        assertEquals(
            setOf(ThreatType.Drones, ThreatType.CruiseMissiles, ThreatType.GuidedBombs),
            trackTypes,
            "drones and cruise missiles fly tracked routes; the simulation publishes KAB flights",
        )
        val health = client.get("/health").bodyAsText()
        assertTrue("\"demo\":true" in health && "\"upstreamRequestsTotal\":0" in health, health)
    }
}
