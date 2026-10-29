package com.gusak.lab.radarnebo.server.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ServerConfigTest {

    @Test
    fun anEmptyEnvironmentGivesTheDefaults() {
        assertEquals(ServerConfig(), ServerConfig.fromEnv(emptyMap()))
    }

    @Test
    fun blankValuesGiveTheDefaults() {
        assertEquals(ServerConfig(), ServerConfig.fromEnv(mapOf("PORT" to " ", "SIM_SEED" to "", "TRUST_PROXY_HEADERS" to " ")))
    }

    @Test
    fun malformedValuesStopTheServer() {
        listOf(
            "PORT" to "abc",
            "POLL_INTERVAL_SECONDS" to "15s",
            "SIM_SEED" to "x",
            "DATA_SOURCE" to "sim",
            "TRUST_PROXY_HEADERS" to "1",
            "LOG_HTTP" to "yes",
        ).forEach { (name, value) ->
            val error = assertFailsWith<IllegalArgumentException>("$name=$value") { ServerConfig.fromEnv(mapOf(name to value)) }
            assertTrue("$name='$value'" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun everyMalformedValueIsReportedAtOnce() {
        val error = assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnv(mapOf("PORT" to "80a", "ARCHIVE_RETENTION_DAYS" to "30d"))
        }
        assertTrue("PORT='80a'" in error.message.orEmpty() && "ARCHIVE_RETENTION_DAYS='30d'" in error.message.orEmpty(), error.message)
    }

    @Test
    fun theArchiveHasItsOwnClientLimit() {
        assertEquals(10, ServerConfig.fromEnv(emptyMap()).clients.archiveRequestsPerMinute)
        assertEquals(30, ServerConfig.fromEnv(mapOf("ARCHIVE_REQUESTS_PER_MINUTE" to "30")).clients.archiveRequestsPerMinute)
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(mapOf("ARCHIVE_REQUESTS_PER_MINUTE" to "ten")) }
    }

    @Test
    fun booleansIgnoreCase() {
        assertTrue(ServerConfig.fromEnv(mapOf("TRUST_PROXY_HEADERS" to "TRUE")).clients.trustProxyHeaders)
        assertFalse(ServerConfig.fromEnv(mapOf("TRUST_PROXY_HEADERS" to "False")).clients.trustProxyHeaders)
    }

    @Test
    fun configIsClampedBelowSoftLimit() {
        val reckless = UpstreamConfig(token = "t", maxPerMinute = 50, pollInterval = 1.seconds)
        assertEquals(UpstreamConfig.HARD_CEILING_PER_MINUTE, reckless.effectivePerMinute)
        assertEquals(7_500, reckless.effectivePollInterval.inWholeMilliseconds)
    }

    @Test
    fun aBudgetOfNoRequestsIsRaisedToOnePerMinute() {
        assertEquals(1, UpstreamConfig(maxPerMinute = 0).effectivePerMinute)
        assertEquals(60.seconds, UpstreamConfig(maxPerMinute = 0).effectivePollInterval)
    }

    @Test
    fun wellFormedButUnsafeValuesAreRejected() {
        // These parse but are unsafe; ARCHIVE_RETENTION_DAYS=0 would empty the archive.
        listOf(
            "SIM_STRIKE_PERIOD_MINUTES" to "60",
            "SIM_TICK_SECONDS" to "0",
            "ARCHIVE_RETENTION_DAYS" to "0",
            "CLIENT_REQUESTS_PER_MINUTE" to "0",
            "HISTORY_REQUESTS_PER_MINUTE" to "-1",
            "ARCHIVE_REQUESTS_PER_MINUTE" to "0",
            "PORT" to "70000",
        ).forEach { (name, value) ->
            val error = assertFailsWith<IllegalArgumentException>("$name=$value") { ServerConfig.fromEnv(mapOf(name to value)) }
            assertTrue(name in error.message.orEmpty(), error.message)
        }
        assertEquals(0, ServerConfig.fromEnv(mapOf("PORT" to "0")).port, "an ephemeral port is allowed")
    }

    @Test
    fun theServerPicksItsSourceFromTheConfiguration() {
        assertEquals(DataSourceKind.Simulation, ServerConfig.fromEnv(emptyMap()).dataSource)
        assertEquals(DataSourceKind.AlertsInUa, ServerConfig.fromEnv(mapOf("ALERTS_API_TOKEN" to "t")).dataSource)
        assertEquals(DataSourceKind.Simulation, ServerConfig.fromEnv(mapOf("ALERTS_API_TOKEN" to "t", "DATA_SOURCE" to "simulation")).dataSource)
    }

    @Test
    fun theTrackingModeIsChosenByName() {
        assertEquals(TrackingMode.Auto, ServerConfig.fromEnv(emptyMap()).tracking)
        assertEquals(TrackingMode.Fused, ServerConfig.fromEnv(mapOf("TRACKING" to "FUSED")).tracking)
        assertEquals(TrackingMode.Alerts, ServerConfig.fromEnv(mapOf("TRACKING" to "alerts")).tracking)
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(mapOf("TRACKING" to "telepathy")) }
    }

    @Test
    fun everyDataSourceAliasIsRecognised() {
        val aliases = mapOf(
            "simulation" to DataSourceKind.Simulation,
            "SIMULATION" to DataSourceKind.Simulation,
            "alerts_in_ua" to DataSourceKind.AlertsInUa,
            "alerts.in.ua" to DataSourceKind.AlertsInUa,
            "Upstream" to DataSourceKind.AlertsInUa,
        )
        aliases.forEach { (alias, kind) ->
            assertEquals(kind, ServerConfig.fromEnv(mapOf("DATA_SOURCE" to alias)).dataSourceOverride, alias)
        }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(mapOf("DATA_SOURCE" to "carrier-pigeon")) }
    }

    @Test
    fun clientKeysAreTrimmedAndCommaSeparatedWithTheOldNameAsAFallback() {
        assertEquals(setOf("a", "b"), ServerConfig.fromEnv(mapOf("CLIENT_KEYS" to " a, b ,")).clients.keys)
        assertEquals(setOf("old"), ServerConfig.fromEnv(mapOf("PROXY_CLIENT_KEYS" to "old ,")).clients.keys)
        assertEquals(setOf("new"), ServerConfig.fromEnv(mapOf("CLIENT_KEYS" to "new", "PROXY_CLIENT_KEYS" to "old")).clients.keys)
    }

    @Test
    fun theArchiveCanBeKeptInMemory() {
        assertTrue(ServerConfig.fromEnv(mapOf("ARCHIVE_JDBC_URL" to ArchiveConfig.IN_MEMORY)).archive.inMemory)
        assertFalse(ServerConfig.fromEnv(emptyMap()).archive.inMemory)
    }
}
