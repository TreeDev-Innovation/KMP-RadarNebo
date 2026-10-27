package com.gusak.lab.radarnebo.server.golden

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
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
import com.gusak.lab.radarnebo.server.golden.Golden.assertText
import com.gusak.lab.radarnebo.server.radarServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Reports whatever the test sets, like alerts.in.ua would; [failure], when set, instead. */
private class ScriptedSource(private val clock: () -> Long) : AlertsDataSource {
    var alerts: List<AirAlert> = emptyList()
    var failure: FetchResult.Failed? = null
    override val kind = DataSourceKind.AlertsInUa
    override val description = "scripted"
    override val pollInterval: Duration = 15.seconds
    override val providesObservations = false
    override suspend fun fetch(lastModified: String?): FetchResult = failure ?: FetchResult.Fresh(SituationReport(clock(), alerts))
    override fun close() {}
}

/**
 * Pins the HTTP contract: status, the headers clients rely on and the full body of every endpoint, for a
 * scripted situation and a fixed clock. Large bodies are pinned by length and SHA-256. Each test compares
 * one transcript; on a change the new transcript is written under `build/golden-actual/`.
 */
class ApiContractGoldenTest {
    private var now = Fixtures.t0.toEpochMilliseconds()
    private val source = ScriptedSource { now }

    private fun ApplicationTestBuilder.server(clients: ClientApiConfig = ClientApiConfig(requestsPerMinute = 10_000, historyRequestsPerMinute = 100)): ServerComponents {
        val components = ServerComponents.create(ServerConfig(clients = clients), clock = { now }, source = source, archives = Archives.inMemory())
        application { radarServer(components, startPoller = false) }
        return components
    }

    /** Requests [path] and appends the exchange to the transcript; bodies longer than [inlineLimit] are digested. */
    private suspend fun ApplicationTestBuilder.record(
        transcript: StringBuilder,
        path: String,
        headers: Map<String, String> = emptyMap(),
        inlineLimit: Int = 4_000,
    ) {
        val response = client.get(path) { headers.forEach { (name, value) -> header(name, value) } }
        val body = response.bodyAsText()
        transcript.append("GET ").append(path)
        headers.forEach { (name, value) -> transcript.append(" [").append(name).append(": ").append(value).append(']') }
        transcript.append('\n').append(response.status.value).append('\n')
        PINNED_HEADERS.forEach { name -> response.headers[name]?.let { transcript.append(name).append(": ").append(pinned(name, it)).append('\n') } }
        val pinnedBody = roundLongFractions(body)
        if (pinnedBody.length <= inlineLimit) transcript.append(pinnedBody) else transcript.append("<${body.length} chars, sha256 ${Golden.sha256(body)}>")
        transcript.append("\n\n")
    }

    /** The rate limiter's `Retry-After` counts down on the wall clock, so only its range is pinned. */
    private fun pinned(header: String, value: String): String =
        if (header == HttpHeaders.RetryAfter && value != "5") {
            assertTrue(value.toInt() in 1..60, "Retry-After $value")
            "<1..60>"
        } else {
            value
        }

    /**
     * Headings and speeds come from trigonometry, whose last bits may differ between JVMs and CPUs, so numbers
     * with more than six decimals are pinned to six.
     */
    private fun roundLongFractions(body: String): String =
        Regex("""-?\d+\.\d{7,}(?:[eE]-?\d+)?""").replace(body) { BigDecimal(it.value).setScale(6, RoundingMode.HALF_UP).toPlainString() }

    @Test
    fun whileWarmingUp() = testApplication {
        server()
        val transcript = StringBuilder()
        listOf(
            "/health",
            "/v1/alerts/active.json",
            "/v1/iot/active_air_raid_alerts_by_oblast.json",
            "/v2/iot/active_air_raid_alerts.json",
            "/v1/iot/active_air_raid_alerts/22.json",
            "/v1/regions/14/alerts/month_ago.json",
            "/v1/drones/tracks.json",
            "/v1/drones/positions.json",
            "/v1/drones/archive.json",
        ).forEach { record(transcript, it) }
        assertText("api-warming-up", ApiTranscripts.WARMING_UP, transcript.toString().trimEnd())
    }

    @Test
    fun servingData() = testApplication {
        val server = server()
        source.alerts = listOf(
            alert(1, Fixtures.region(22)),
            alert(2, Fixtures.brovary, level = ThreatLevel.Yellow),
            alert(3, Fixtures.kharkiv, threat = ThreatType.CruiseMissiles, message = "Крилаті ракети"),
        )
        server.poller.pollOnce()
        // Twenty minutes later Brovary clears and Kyiv city is alerted: the drone track moves on.
        now += 20.minutes.inWholeMilliseconds
        source.alerts = listOf(
            alert(1, Fixtures.region(22)),
            alert(3, Fixtures.kharkiv, threat = ThreatType.CruiseMissiles, message = "Крилаті ракети"),
            alert(4, Fixtures.region(31), startedAt = Fixtures.t0 + 20.minutes),
        )
        server.poller.pollOnce()
        now += 45.seconds.inWholeMilliseconds

        val transcript = StringBuilder()
        record(transcript, "/health")
        record(transcript, "/v1/alerts/active.json", mapOf(HttpHeaders.Origin to "https://example.org"))
        record(transcript, "/v1/alerts/active.json", mapOf(HttpHeaders.IfNoneMatch to "\"v2\""))
        record(transcript, "/v1/alerts/active.json", mapOf(HttpHeaders.IfModifiedSince to "Mon, 19 Oct 2026 10:20:00 GMT"))
        listOf("v1", "v2").forEach { v ->
            record(transcript, "/$v/iot/active_air_raid_alerts_by_oblast.json")
            record(transcript, "/$v/iot/active_air_raid_alerts.json", inlineLimit = 0)
            listOf("22", "1293", "14", "1100", "31", "7").forEach { uid -> record(transcript, "/$v/iot/active_air_raid_alerts/$uid.json") }
        }
        record(transcript, "/v1/regions/14/alerts/month_ago.json")
        record(transcript, "/v1/regions/22/alerts/week_ago.json")
        record(transcript, "/v1/regions/1293/alerts/day_ago.json")
        record(transcript, "/v1/drones/tracks.json")
        record(transcript, "/v1/drones/tracks.json?active=true")
        record(transcript, "/v1/drones/positions.json")
        record(transcript, "/v1/drones/positions.json?at=2026-10-19T10:10:00Z")
        // Kyiv city clears: its track ends and goes to the archive.
        now += 40.minutes.inWholeMilliseconds
        source.alerts = listOf(alert(1, Fixtures.region(22)))
        server.poller.pollOnce()
        record(transcript, "/v1/drones/tracks.json")
        record(transcript, "/v1/drones/archive.json?from=${Fixtures.t0.toEpochMilliseconds()}")
        record(transcript, "/v1/drones/archive.json?from=2026-10-19T09:00:00Z&to=2026-10-19T12:00:00Z&type=drones&limit=5")
        record(transcript, "/v1/drones/archive.json?type=cruise_missiles")
        record(transcript, "/v1/regions/31/alerts/day_ago.json")
        record(transcript, "/v1/alerts/active.json")

        // The source fails: the last data is still served, and health says so.
        source.failure = FetchResult.Failed(message = "HTTP 502 from upstream", minWaitMs = 1_000, retryAfterMs = 30_000)
        now += 15.seconds.inWholeMilliseconds
        server.poller.pollOnce()
        record(transcript, "/health")
        record(transcript, "/v1/alerts/active.json")
        assertText("api-serving", ApiTranscripts.SERVING, transcript.toString().trimEnd())
    }

    @Test
    fun badRequestsAndUnknownPaths() = testApplication {
        val server = server()
        source.alerts = listOf(alert(1, Fixtures.brovary))
        server.poller.pollOnce()
        val transcript = StringBuilder()
        listOf(
            "/v1/iot/active_air_raid_alerts/kyiv.json",
            "/v2/iot/active_air_raid_alerts/22",
            "/v1/regions/kyiv/alerts/month_ago.json",
            "/v1/regions/14/alerts/year_ago.json",
            "/v1/regions/14/alerts/month_ago",
            "/v1/drones/positions.json?at=yesterday",
            "/v1/drones/archive.json?type=balloons",
            "/v1/drones/archive.json?from=2&to=1",
            "/v1/drones/archive.json?limit=many",
            "/v1/drones/archive.json?limit=0",
            "/v1/drones/archive.json?to=soon",
            "/v1/nope",
            "/v3/iot/active_air_raid_alerts.json",
        ).forEach { record(transcript, it) }
        assertText("api-errors", ApiTranscripts.ERRORS, transcript.toString().trimEnd())
    }

    @Test
    fun clientKeysAndRateLimits() = testApplication {
        val server = server(ClientApiConfig(keys = setOf("app-key"), requestsPerMinute = 10_000, historyRequestsPerMinute = 1))
        source.alerts = listOf(alert(1, Fixtures.brovary))
        server.poller.pollOnce()
        val transcript = StringBuilder()
        record(transcript, "/v1/alerts/active.json")
        record(transcript, "/v1/alerts/active.json?token=wrong")
        record(transcript, "/v1/alerts/active.json", mapOf(HttpHeaders.Authorization to "Bearer wrong"))
        record(transcript, "/v1/drones/tracks.json")
        record(transcript, "/v1/nope")
        record(transcript, "/v1/iot/active_air_raid_alerts/14.json", mapOf(HttpHeaders.Authorization to "Bearer app-key"))
        record(transcript, "/v1/regions/14/alerts/day_ago.json?token=app-key")
        record(transcript, "/v1/regions/14/alerts/day_ago.json?token=app-key")
        record(transcript, "/health")
        assertText("api-auth", ApiTranscripts.AUTH, transcript.toString().trimEnd())
    }

    private companion object {
        val PINNED_HEADERS = listOf(
            HttpHeaders.ContentType,
            HttpHeaders.ETag,
            HttpHeaders.LastModified,
            HttpHeaders.CacheControl,
            "X-Data-Age-Seconds",
            HttpHeaders.RetryAfter,
            HttpHeaders.WWWAuthenticate,
            HttpHeaders.AccessControlAllowOrigin,
            HttpHeaders.AccessControlExposeHeaders,
        )
    }
}
