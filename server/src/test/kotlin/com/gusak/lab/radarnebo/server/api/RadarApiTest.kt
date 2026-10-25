package com.gusak.lab.radarnebo.server.api

import com.gusak.lab.radarnebo.data.ActiveAlertsResponse
import com.gusak.lab.radarnebo.data.RadarNeboApi
import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.server.Archives
import com.gusak.lab.radarnebo.server.Fixtures
import com.gusak.lab.radarnebo.server.Fixtures.alert
import com.gusak.lab.radarnebo.server.ServerComponents
import com.gusak.lab.radarnebo.server.application.AlertsDataSource
import com.gusak.lab.radarnebo.server.application.FetchResult
import com.gusak.lab.radarnebo.server.application.SituationReport
import com.gusak.lab.radarnebo.server.config.ClientApiConfig
import com.gusak.lab.radarnebo.server.config.DataSourceKind
import com.gusak.lab.radarnebo.server.config.ServerConfig
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.alert.AirRaidStatusMap
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A data source that reports whatever the test sets, like alerts.in.ua would. */
private class ScriptedSource(var clock: () -> Long) : AlertsDataSource {
    var alerts: List<AirAlert> = emptyList()
    override val kind = DataSourceKind.AlertsInUa
    override val description = "scripted"
    override val pollInterval: Duration = 15.seconds
    override val providesObservations = false
    override suspend fun fetch(lastModified: String?) = FetchResult.Fresh(SituationReport(clock(), alerts))
}

class RadarApiTest {
    private var now = Fixtures.t0.toEpochMilliseconds()
    private val source = ScriptedSource { now }
    private val json = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.server(clients: ClientApiConfig = ClientApiConfig(requestsPerMinute = 10_000, historyRequestsPerMinute = 100)): ServerComponents {
        val components = ServerComponents.create(ServerConfig(clients = clients), clock = { now }, source = source, archives = Archives.inMemory())
        application { radarServer(components, startPoller = false) }
        return components
    }

    private fun ServerComponents.poll() = runBlocking { poller.pollOnce() }

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
        assertEquals(HttpStatusCode.OK, client.get("/health").status, "health needs no key")
        assertEquals("""{"message":"Not found"}""", client.get("/v1/nope?token=app-key").bodyAsText())
    }

    @Test
    fun activeAlertsAreServedInTheAlertsInUaFormatWithConditionalRequests() = testApplication {
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

        val etag = response.headers[HttpHeaders.ETag]!!
        assertEquals(HttpStatusCode.NotModified, client.get(RadarNeboApi.ACTIVE_ALERTS_PATH) { header(HttpHeaders.IfNoneMatch, etag) }.status)
        val lastModified = response.headers[HttpHeaders.LastModified]!!
        assertEquals(HttpStatusCode.NotModified, client.get(RadarNeboApi.ACTIVE_ALERTS_PATH) { header(HttpHeaders.IfModifiedSince, lastModified) }.status)

        now += 1.minutes.inWholeMilliseconds
        server.poll()
        assertEquals(HttpStatusCode.NotModified, client.get(RadarNeboApi.ACTIVE_ALERTS_PATH) { header(HttpHeaders.IfNoneMatch, etag) }.status, "same content, same version")
        source.alerts = emptyList()
        server.poll()
        val changed = client.get(RadarNeboApi.ACTIVE_ALERTS_PATH) { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.OK, changed.status)
        assertEquals("""{"alerts":[]}""", changed.bodyAsText())
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
        assertEquals((track.samples[0].lat + track.samples[1].lat) / 2, p.lat, 1e-9)
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
    fun largeResponsesAreCompressed() = testApplication {
        val server = server()
        source.alerts = (1L..40L).map { alert(it, Fixtures.region(listOf(3, 4, 5, 8, 9, 10, 11, 12)[(it % 8).toInt()]).copy(uid = "region-$it")) }
        server.poll()
        val response: HttpResponse = client.get(RadarNeboApi.ACTIVE_ALERTS_PATH) { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
    }
}
