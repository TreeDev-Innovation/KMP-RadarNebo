package com.gusak.lab.radarnebo.server.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ServerConfigTest {

    @Test
    fun anEmptyEnvironmentGivesTheDefaults() {
        assertEquals(ServerConfig(), ServerConfig.fromEnv(emptyMap()))
    }

    @Test
    fun malformedValuesFallBackToTheDefaults() {
        assertEquals(ServerConfig(), ServerConfig.fromEnv(mapOf("PORT" to "eighty", "SIM_SEED" to "x", "ARCHIVE_RETENTION_DAYS" to " ")))
    }

    @Test
    fun configIsClampedBelowSoftLimit() {
        val reckless = UpstreamConfig(token = "t", maxPerMinute = 50, pollInterval = 1.seconds)
        assertEquals(UpstreamConfig.HARD_CEILING_PER_MINUTE, reckless.effectivePerMinute)
        assertEquals(7_500, reckless.effectivePollInterval.inWholeMilliseconds)
    }

    @Test
    fun theServerPicksItsSourceFromTheConfiguration() {
        assertEquals(DataSourceKind.Simulation, ServerConfig.fromEnv(emptyMap()).dataSource)
        assertEquals(DataSourceKind.AlertsInUa, ServerConfig.fromEnv(mapOf("ALERTS_API_TOKEN" to "t")).dataSource)
        assertEquals(DataSourceKind.Simulation, ServerConfig.fromEnv(mapOf("ALERTS_API_TOKEN" to "t", "DATA_SOURCE" to "simulation")).dataSource)
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
        assertEquals(null, ServerConfig.fromEnv(mapOf("DATA_SOURCE" to "carrier-pigeon")).dataSourceOverride)
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
