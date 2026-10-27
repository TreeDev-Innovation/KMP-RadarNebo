package com.gusak.lab.radarnebo.console

import com.gusak.lab.radarnebo.debug.HttpExchange
import com.gusak.lab.radarnebo.server.config.ArchiveConfig
import com.gusak.lab.radarnebo.server.config.DataSourceKind
import com.gusak.lab.radarnebo.server.config.ServerConfig
import com.gusak.lab.radarnebo.server.config.UpstreamConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConsoleServerTest {
    private val simulation = ServerConfig(archive = ArchiveConfig(jdbcUrl = "memory"), dataSourceOverride = DataSourceKind.Simulation)

    private fun freePort() = ServerSocket(0).use { it.localPort }

    /** Runs [block] with a fresh console server and stops it afterwards, also when an assertion fails. */
    private suspend fun withConsoleServer(config: ServerConfig, block: suspend (ConsoleServer) -> Unit) {
        val server = ConsoleServer(config)
        try {
            block(server)
        } finally {
            withTimeout(20_000) { server.stop() }
        }
    }

    @Test
    fun startsServesRecordsAndStops(): Unit = runBlocking {
        withConsoleServer(simulation) { server ->
            val port = freePort()
            withTimeout(20_000) { server.start(port) }
            assertIs<ServerState.Running>(server.state.value)

            val body = URI("http://localhost:$port/health").toURL().readText()
            assertTrue("\"source\":\"Simulation\"" in body, body)
            // Recorded once the response has gone out, a moment after the client has it.
            val exchange = withTimeout(5_000) { server.traffic.exchanges.first { it.isNotEmpty() }.last() }
            assertEquals(HttpExchange.Direction.Incoming, exchange.direction)
            assertEquals("/health", exchange.url)
            assertEquals(1, server.clients.value.values.single().requests)

            withTimeout(20_000) { server.stop() }
            assertEquals(ServerState.Stopped, server.state.value)
            withTimeout(20_000) { server.start(port) }
            assertIs<ServerState.Running>(server.state.value, "starts again on the same port")
        }
    }

    @Test
    fun switchingTheDataSourceRestartsTheServerOnItsPort(): Unit = runBlocking {
        // A dummy token and a closed port: alerts.in.ua is selectable, but nothing leaves the machine.
        val upstream = UpstreamConfig(token = "test-token", baseUrl = "http://127.0.0.1:1")
        withConsoleServer(simulation.copy(upstream = upstream)) { server ->
            val port = freePort()
            withTimeout(20_000) { server.start(port) }

            withTimeout(20_000) { server.switchDataSource(DataSourceKind.AlertsInUa) }
            assertEquals(DataSourceKind.AlertsInUa, server.dataSource.value)
            assertEquals(port, assertIs<ServerState.Running>(server.state.value).port)
            assertTrue("\"source\":\"AlertsInUa\"" in URI("http://localhost:$port/health").toURL().readText())

            withTimeout(20_000) { server.switchDataSource(DataSourceKind.Simulation) }
            assertTrue("\"source\":\"Simulation\"" in URI("http://localhost:$port/health").toURL().readText())
        }
    }

    @Test
    fun alertsInUaNeedsAToken(): Unit = runBlocking {
        val server = ConsoleServer(ServerConfig(archive = ArchiveConfig(jdbcUrl = "memory")))
        assertFalse(server.alertsInUaAvailable)
        server.switchDataSource(DataSourceKind.AlertsInUa)
        assertEquals(DataSourceKind.Simulation, server.dataSource.value)
    }

    @Test
    fun aTakenPortFailsCleanly(): Unit = runBlocking {
        withConsoleServer(simulation) { server ->
            ServerSocket(0).use { taken ->
                withTimeout(20_000) { server.start(taken.localPort) }
                assertIs<ServerState.Failed>(server.state.value)
            }
        }
    }

    @Test
    fun aFailedStartLeavesTheConsoleAbleToStartAgain(): Unit = runBlocking {
        withConsoleServer(simulation) { server ->
            val port = ServerSocket(0).use { taken ->
                withTimeout(20_000) { server.start(taken.localPort) }
                assertIs<ServerState.Failed>(server.state.value)
                taken.localPort
            }
            // The port is free again; what the failed start built has been closed, so nothing stands in the way.
            withTimeout(20_000) { server.stop() }
            withTimeout(20_000) { server.start(port) }
            assertEquals(port, assertIs<ServerState.Running>(server.state.value).port)
            assertTrue("\"source\":\"Simulation\"" in URI("http://localhost:$port/health").toURL().readText())
        }
    }
}
