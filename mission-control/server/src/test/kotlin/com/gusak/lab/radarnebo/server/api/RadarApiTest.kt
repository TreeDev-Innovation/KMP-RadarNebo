package com.gusak.lab.radarnebo.server.api

import kotlin.test.assertNotNull
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.tracking.PositionMode
import com.gusak.lab.radarnebo.data.ActiveAlertsResponse
import com.gusak.lab.radarnebo.data.RadarNeboApi
import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.server.Archives
import com.gusak.lab.radarnebo.server.Fixtures
import com.gusak.lab.radarnebo.server.Fixtures.alert
import com.gusak.lab.radarnebo.debug.HttpExchange
import com.gusak.lab.radarnebo.debug.TrafficLog
import com.gusak.lab.radarnebo.debug.TrafficRecorder
import com.gusak.lab.radarnebo.server.ServerComponents
import com.gusak.lab.radarnebo.server.application.AlertsDataSource
import com.gusak.lab.radarnebo.server.application.FetchResult
import com.gusak.lab.radarnebo.server.application.SituationReport
import com.gusak.lab.radarnebo.server.config.ClientApiConfig
import com.gusak.lab.radarnebo.server.config.DataSourceKind
import com.gusak.lab.radarnebo.server.config.ServerConfig
import com.gusak.lab.radarnebo.server.config.UpstreamConfig
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.alert.AirRaidStatusMap
import com.gusak.lab.radarnebo.server.domain.alert.AlertArchive
import com.gusak.lab.radarnebo.server.infrastructure.persistence.InMemoryAlertArchive
import com.gusak.lab.radarnebo.server.infrastructure.persistence.InMemoryTrackArchive
import com.gusak.lab.radarnebo.server.radarServer
import com.gusak.lab.radarnebo.tracking.DronePositionsResponse
import com.gusak.lab.radarnebo.tracking.DroneTracksResponse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A data source that reports whatever the test sets, like alerts.in.ua would; [failure], when set, instead. */
private class ScriptedSource(var clock: () -> Long) : AlertsDataSource {
    var alerts: List<AirAlert> = emptyList()
    var failure: FetchResult.Failed? = null
    var hang = false
    var closed = false
    /** The body alerts.in.ua would have sent for [alerts]; null for a source that sends none. */
    var raw: String? = null
    override val kind = DataSourceKind.AlertsInUa
    override val description = "scripted"
    override val pollInterval: Duration = 15.seconds
    override val providesObservations = false
    override suspend fun fetch(lastModified: String?): FetchResult {
        if (hang) awaitCancellation()
        return failure ?: FetchResult.Fresh(SituationReport(clock(), alerts, rawAlerts = raw))
    }
    override fun close() {
        closed = true
    }
}

class RadarApiTest {
    private var now = Fixtures.t0.toEpochMilliseconds()
    private val source = ScriptedSource { now }
    private val json = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.server(
        clients: ClientApiConfig = ClientApiConfig(requestsPerMinute = 10_000, historyRequestsPerMinute = 100),
        traffic: TrafficRecorder? = null,
        archives: Archives = Archives.inMemory(),
    ): ServerComponents {
        val components = ServerComponents.create(ServerConfig(clients = clients), clock = { now }, traffic = traffic, source = source, archives = archives)
        application { radarServer(components, startPoller = false) }
        return components
    }

    private suspend fun ServerComponents.poll() = poller.pollOnce()

    /** Runs the poll loop, as the server does, until it has scheduled a poll, then [block], then stops it. */
    private suspend fun ServerComponents.whilePolling(block: suspend () -> Unit) = coroutineScope {
        val loop = launch { poller.run() }
        withTimeout(5_000) { while (poller.status.nextPollAt == null) delay(10) }
        block()
        loop.cancel()
    }

    private suspend fun ApplicationTestBuilder.health() =
        RadarNeboApi.json.decodeFromString(Health.serializer(), client.get("/health").bodyAsText())

    private suspend fun ApplicationTestBuilder.text(path: String) = client.get(path).let { it.status to it.bodyAsText() }

    @Test
    fun clientsNeedAKeyWhenKeysAreConfiguredAndGetJsonErrors() = testApplication {
        val server = server(ClientApiConfig(keys = setOf("app-key")))
        val anonymous = client.get("/v1/alerts/active.json")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertEquals("""{"message":"Missing or invalid client key"}""", anonymous.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/alerts/active.json?token=wrong").status)

        val warmingUp = client.get("/v1/alerts/active.json") { header(HttpHeaders.Authorization, "Bearer app-key") }
        assertEquals(HttpStatusCode.ServiceUnavailable, warmingUp.status)
        assertEquals("5", warmingUp.headers[HttpHeaders.RetryAfter])

        server.poll()
        assertEquals(HttpStatusCode.OK, client.get("/v1/alerts/active.json?token=app-key").status, "the key also works as ?token=")
        assertEquals("ok", health().status, "health needs no key")
        assertEquals("""{"message":"Not found"}""", client.get("/v1/nope?token=app-key").bodyAsText())
    }

    @Test
    fun withoutConfiguredKeysTheApiStaysOpen() = testApplication {
        val server = server(ClientApiConfig(requestsPerMinute = 10_000))
        server.poll()
        assertEquals(HttpStatusCode.OK, client.get("/v1/alerts/active.json").status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/alerts/active.json?token=anything").status, "a stray key is ignored")
    }

    @Test
    fun aMalformedQueryTokenIsTheClientsMistakeNotAServerError() = testApplication {
        val server = server(ClientApiConfig(keys = setOf("app-key"), requestsPerMinute = 10_000))
        server.poll()
        for (path in listOf("/v1/alerts/active.json?token=", "/v1/alerts/active.json?token=bad!key")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.BadRequest, response.status, path)
            assertTrue(json.parseToJsonElement(response.bodyAsText()).jsonObject["message"] != null, "a JSON error for $path")
        }
        assertEquals(HttpStatusCode.OK, client.get("/v1/alerts/active.json?token=app-key").status)
    }

    @Test
    fun callersWithoutAValidKeyAreRateLimitedToo() = testApplication {
        server(ClientApiConfig(keys = setOf("app-key"), requestsPerMinute = 2))
        val statuses = List(3) { client.get("/v1/alerts/active.json?token=wrong").status }
        assertEquals(listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Unauthorized, HttpStatusCode.TooManyRequests), statuses)
    }

    @Test
    fun theHealthCheckIsRateLimitedButNeedsNoKey() = testApplication {
        val server = server(ClientApiConfig(keys = setOf("app-key"), requestsPerMinute = 2))
        server.poll()
        val statuses = listOf("/health", "/", "/health").map { client.get(it).status }
        assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.TooManyRequests), statuses)
    }

    @Test
    fun behindAProxyEachClientIpHasItsOwnLimitAndTheProxysEntryWins() = testApplication {
        val server = server(ClientApiConfig(requestsPerMinute = 1, trustProxyHeaders = true))
        server.poll()
        suspend fun from(forwardedFor: String) =
            client.get("/v1/alerts/active.json") { header(HttpHeaders.XForwardedFor, forwardedFor) }.status
        assertEquals(HttpStatusCode.OK, from("203.0.113.1"))
        assertEquals(HttpStatusCode.OK, from("203.0.113.2"), "another client behind the same proxy")
        assertEquals(HttpStatusCode.TooManyRequests, from("198.51.100.9, 203.0.113.1"), "a client-supplied entry doesn't count")
    }

    @Test
    fun forwardedHeadersAreIgnoredUnlessTrusted() = testApplication {
        val server = server(ClientApiConfig(requestsPerMinute = 1))
        server.poll()
        suspend fun from(forwardedFor: String) =
            client.get("/v1/alerts/active.json") { header(HttpHeaders.XForwardedFor, forwardedFor) }.status
        assertEquals(HttpStatusCode.OK, from("203.0.113.1"))
        assertEquals(HttpStatusCode.TooManyRequests, from("203.0.113.2"), "a spoofed header must not reset the limit")
    }

    fun alertsInUaBodyIsServedExactlyAsReceived() = testApplication {
        val server = server()
        // Unknown keys, alerts.in.ua's own meta and its key order all pass through.
        val upstream = """{"alerts":[],"meta":{"last_updated_at":"2026/10/26 21:58:07 +0000","type":"full"},"disclaimer":"x","new_key":1}"""
        source.raw = upstream
        server.poll()
        assertEquals(upstream, client.get(RadarNeboApi.ACTIVE_ALERTS_PATH).bodyAsText())
    }

    @Test
    fun activeAlertsAreServedInTheAlertsInUaFormatWithoutCaching() = testApplication {
        val server = server()
        source.alerts = listOf(alert(42, Fixtures.brovary))
        server.poll()

        val response = client.get(RadarNeboApi.ACTIVE_ALERTS_PATH)
        val body = response.bodyAsText()
        val alert = json.parseToJsonElement(body).jsonObject["alerts"]!!.jsonArray.single().jsonObject
        assertEquals("1100", alert["location_uid"]!!.jsonPrimitive.content)
        assertEquals("hromada", alert["location_type"]!!.jsonPrimitive.content)
        assertEquals(14, alert["location_oblast_uid"]!!.jsonPrimitive.int, "a number, as alerts.in.ua sends it")
        assertEquals("2026-10-19T10:00:00.000Z", alert["started_at"]!!.jsonPrimitive.content)
        assertTrue("\"finished_at\":null" in body, "nulls are written out, as alerts.in.ua does")
        assertEquals("drones", alert["threats"]!!.jsonArray.single().jsonObject["threat_type"]!!.jsonPrimitive.content)
        // The app's own client reads it.
        assertEquals(42, RadarNeboApi.json.decodeFromString(ActiveAlertsResponse.serializer(), body).alerts.single().id)

        // No validators, and conditional requests still get the whole list.
        assertEquals(null, response.headers[HttpHeaders.ETag])
        assertEquals(null, response.headers[HttpHeaders.LastModified])
        assertEquals(null, response.headers[HttpHeaders.CacheControl])
        val conditional = client.get(RadarNeboApi.ACTIVE_ALERTS_PATH) {
            header(HttpHeaders.IfNoneMatch, "\"v1\"")
            header(HttpHeaders.IfModifiedSince, "Mon, 19 Oct 2026 10:00:00 GMT")
        }
        assertEquals(HttpStatusCode.OK, conditional.status)
        assertEquals(body, conditional.bodyAsText())

        now += 30.seconds.inWholeMilliseconds
        assertEquals("30", client.get(RadarNeboApi.ACTIVE_ALERTS_PATH).headers[DATA_AGE_HEADER], "seconds since the source confirmed the data")
        now += 30.seconds.inWholeMilliseconds
        source.alerts = emptyList()
        server.poll()
        val changed = client.get(RadarNeboApi.ACTIVE_ALERTS_PATH)
        assertEquals(HttpStatusCode.OK, changed.status)
        // Like alerts.in.ua: the alerts and when they last changed (now, in the test's clock).
        assertEquals("""{"alerts":[],"meta":{"last_updated_at":"2026/10/19 10:01:00 +0000","type":"full"}}""", changed.bodyAsText())
    }

    @Test
    fun iotEndpointsEncodeAirRaidStatusAsDocumented() = testApplication {
        val server = server()
        source.alerts = listOf(alert(1, Fixtures.region(22)), alert(2, Fixtures.brovary, level = ThreatLevel.Yellow))
        server.poll()
        val kharkiv = AirRaidStatusMap.OBLAST_ORDER.indexOf(22)
        val kyivOblast = AirRaidStatusMap.OBLAST_ORDER.indexOf(14)

        val (status, v1) = text("/v1/iot/active_air_raid_alerts_by_oblast.json")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(29, v1.length, "a JSON string of 27 characters")
        assertEquals('A', v1[kharkiv + 1])
        assertEquals('P', v1[kyivOblast + 1])
        assertEquals('R', text("/v2/iot/active_air_raid_alerts_by_oblast.json").second[kharkiv + 1])

        assertEquals("\"A\"", text("/v1/iot/active_air_raid_alerts/22.json").second)
        assertEquals("\"R\"", text("/v2/iot/active_air_raid_alerts/22.json").second)
        assertEquals("\"Y\"", text("/v2/iot/active_air_raid_alerts/1100.json").second)
        assertEquals("\"P\"", text("/v2/iot/active_air_raid_alerts/14.json").second)
        assertEquals("\"N\"", text("/v1/iot/active_air_raid_alerts/31.json").second)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/iot/active_air_raid_alerts/kyiv.json").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/iot/active_air_raid_alerts/22").status)

        val all = text("/v2/iot/active_air_raid_alerts.json").second.removeSurrounding("\"")
        assertEquals('R', all[22])
        assertEquals('Y', all[1100])
        assertEquals(' ', all[7], "UID 7 belongs to no location")
    }

    @Test
    fun historyComesFromTheArchiveIncludingFinishedAlerts() = testApplication {
        val server = server(ClientApiConfig(requestsPerMinute = 10_000, historyRequestsPerMinute = 3))
        source.alerts = listOf(alert(1, Fixtures.brovary), alert(2, Fixtures.kharkiv))
        server.poll()
        now += 30.minutes.inWholeMilliseconds
        source.alerts = listOf(alert(2, Fixtures.kharkiv))
        server.poll()

        fun alerts(body: String) = RadarNeboApi.json.decodeFromString(ActiveAlertsResponse.serializer(), body).alerts
        val kyivOblast = alerts(text("/v1/regions/14/alerts/month_ago.json").second)
        assertEquals(listOf(1L), kyivOblast.map { it.id }, "an oblast's history includes its hromadas")
        assertEquals("2026-10-19T10:30:00.000Z", kyivOblast.single().finishedAt)
        val kharkiv = alerts(text("/v1/regions/1293/alerts/day_ago.json").second)
        assertEquals(listOf(2L), kharkiv.map { it.id })
        assertEquals(null, kharkiv.single().finishedAt, "still active")

        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/regions/14/alerts/year_ago.json").status)
        val limited = client.get("/v1/regions/14/alerts/month_ago.json")
        assertEquals(HttpStatusCode.TooManyRequests, limited.status, "history has its own, stricter limit")
        assertEquals(HttpStatusCode.OK, client.get(RadarNeboApi.ACTIVE_ALERTS_PATH).status, "which leaves the other endpoints alone")
    }

    @Test
    fun tracksAreBuiltOnTheServerAndPositionsInterpolated() = testApplication {
        val server = server()
        source.alerts = listOf(alert(1, Fixtures.brovary))
        server.poll()
        // Twenty minutes later the threat moves on from Brovary to Kyiv.
        now += 20.minutes.inWholeMilliseconds
        source.alerts = listOf(alert(2, Fixtures.region(31), startedAt = Fixtures.t0 + 20.minutes))
        server.poll()

        val tracks = RadarNeboApi.json.decodeFromString(DroneTracksResponse.serializer(), text(RadarNeboApi.TRACKS_PATH).second)
        assertEquals(now, tracks.serverTime)
        assertEquals(false, tracks.simulated)
        val track = tracks.tracks.single()
        assertEquals(listOf("Brovary", "Kyiv City"), track.samples.map { it.title }, "the new alert continues the track")

        val midpoint = (track.samples[0].t + track.samples[1].t) / 2
        val positions = RadarNeboApi.json.decodeFromString(DronePositionsResponse.serializer(), text("${RadarNeboApi.POSITIONS_PATH}?at=$midpoint").second)
        val p = positions.positions.single()
        // Between reports the target follows the server's smoothed path, not a straight line through them.
        assertEquals(PositionMode.Interpolated, p.mode)
        assertTrue(track.path.size > 2 && track.path.minOf { GeoMath.distanceKm(it.point, p.point) } < 3.0, "on the smoothed path")
        assertNotNull(track.estimate)
        assertEquals(HttpStatusCode.BadRequest, client.get("${RadarNeboApi.POSITIONS_PATH}?at=yesterday").status)

        // Once the alert clears the track ends; the archive keeps it.
        now += 40.minutes.inWholeMilliseconds
        source.alerts = emptyList()
        server.poll()
        val archive = json.decodeFromString(TrackHistoryResponse.serializer(), text("${RadarNeboApi.TRACK_ARCHIVE_PATH}?from=${Fixtures.t0.toEpochMilliseconds()}&type=drones").second)
        assertEquals(listOf(track.id), archive.tracks.map { it.id })
        assertEquals(HttpStatusCode.BadRequest, client.get("${RadarNeboApi.TRACK_ARCHIVE_PATH}?type=balloons").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("${RadarNeboApi.TRACK_ARCHIVE_PATH}?from=2&to=1").status)
    }

    @Test
    fun theTrackArchiveIsBoundedAndHasItsOwnLimit() = testApplication {
        val server = server(ClientApiConfig(requestsPerMinute = 10_000, archiveRequestsPerMinute = 3))
        server.poll()
        val archive = RadarNeboApi.TRACK_ARCHIVE_PATH
        assertEquals(HttpStatusCode.BadRequest, client.get("$archive?limit=201").status)
        val (status, body) = text("$archive?from=${now - 8.days.inWholeMilliseconds}&to=$now")
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue("at most 7 days" in body, body)
        assertEquals(HttpStatusCode.OK, client.get("$archive?limit=200").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.get(archive).status, "the archive has its own limit")
        assertEquals(HttpStatusCode.OK, client.get(RadarNeboApi.TRACKS_PATH).status, "which leaves the other endpoints alone")
    }

    @Test
    fun largeResponsesAreCompressed() = testApplication {
        val server = server()
        source.alerts = (1L..40L).map { alert(it, Fixtures.region(listOf(3, 4, 5, 8, 9, 10, 11, 12)[(it % 8).toInt()]).copy(uid = "region-$it")) }
        server.poll()
        val response: HttpResponse = client.get(RadarNeboApi.ACTIVE_ALERTS_PATH) { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
    }

    @Test
    fun theConsoleSeesEachCallWithItsUncompressedBodyAndMaskedKey() = testApplication {
        val log = TrafficLog()
        val server = server(ClientApiConfig(keys = setOf("app-key"), requestsPerMinute = 10_000), traffic = log)
        source.alerts = (1L..40).map { alert(it, Fixtures.brovary) }
        server.poll()

        client.get(RadarNeboApi.ACTIVE_ALERTS_PATH + "?token=app-key") { header(HttpHeaders.AcceptEncoding, "gzip") }
        // The capture may record the call just after the client has its response.
        val exchange = withTimeout(5_000) { log.exchanges.first { it.isNotEmpty() } }.single()
        assertEquals(HttpExchange.Direction.Incoming, exchange.direction)
        assertEquals(RadarNeboApi.ACTIVE_ALERTS_PATH + "?token=***", exchange.url)
        assertEquals(200, exchange.status)
        assertTrue(exchange.responseBody!!.startsWith("{\"alerts\""), "recorded before compression")
    }

    @Test
    fun healthTurnsDegradedWhenTheSourceStartsFailing() = testApplication {
        val server = server()
        assertEquals("warming_up", health().status)
        server.poll()
        assertEquals("ok", health().status)

        source.failure = FetchResult.Failed(message = "boom", retryAfterMs = null, minWaitMs = 1_000)
        server.poll()
        val health = health()
        assertEquals("degraded", health.status)
        assertEquals("boom", health.lastError)
        assertEquals(DataSourceKind.AlertsInUa.key, health.source)
        assertEquals(UpstreamConfig().effectivePerMinute, health.upstreamBudgetPerMinute, "the source in use is alerts.in.ua")
    }

    @Test
    fun anUnexpectedFailureIsAGenericJsonErrorThatRevealsNothing() = testApplication {
        val failingArchive = object : AlertArchive by InMemoryAlertArchive() {
            override suspend fun history(uid: String, since: Instant, limit: Int): List<AirAlert> = throw IllegalStateException("db path /secret")
        }
        val server = server(archives = Archives(failingArchive, InMemoryTrackArchive()))
        server.poll()
        val response = client.get("/v1/regions/14/alerts/day_ago.json")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertEquals("""{"message":"API error"}""", body)
        assertFalse("secret" in body)
    }

    @Test
    fun theStatusPageEscapesTheSourcesError() = testApplication {
        val server = server()
        server.poll() // a board first: without one the page reports warming up, not the failure
        source.failure = FetchResult.Failed("<script>x</script>", minWaitMs = 1_000)
        server.poll()
        val page = text("/").second
        assertTrue("its data source is failing" in page)
        assertTrue("&lt;script&gt;" in page)
        assertFalse("<script>x" in page)
    }

    @Test
    fun stoppingTheServerClosesItsSource() {
        testApplication {
            server()
            startApplication()
            assertFalse(source.closed)
        }
        assertTrue(source.closed, "the HTTP client of a real source would be released")
    }

    @Test
    fun healthIsStaleWhenTheDataIsOldButStill200() = testApplication {
        val server = server()
        server.poll()
        assertEquals(0, health().dataAgeSeconds)
        now += 10.minutes.inWholeMilliseconds
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status, "liveness stays 200, so container health checks pass")
        val health = RadarNeboApi.json.decodeFromString(Health.serializer(), response.bodyAsText())
        assertEquals("stale", health.status)
        assertEquals(600, health.dataAgeSeconds)
    }

    @Test
    fun healthIsStaleWhenThePollerIsOverdue() = testApplication {
        val server = server()
        server.whilePolling {
            assertEquals("ok", health().status)
            // The next fetch never returns: the poller is hung.
            source.hang = true
            // 50 s: under the 60 s age limit, but more than two intervals past the scheduled poll.
            now += 50.seconds.inWholeMilliseconds
            val health = health()
            assertEquals("stale", health.status)
            assertEquals(50, health.dataAgeSeconds)
        }
    }

    @Test
    fun anUpstreamOutageWithAKnownBackoffStaysDegraded() = testApplication {
        val server = server()
        server.poll()
        source.failure = FetchResult.Failed(message = "boom", minWaitMs = 1_000)
        server.whilePolling {
            val health = health()
            assertEquals("degraded", health.status)
            assertEquals(now + 1_000, health.nextPollAt)
        }
    }
}
