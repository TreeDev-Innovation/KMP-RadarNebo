package com.gusak.lab.radarnebo.console

import com.gusak.lab.radarnebo.debug.HttpExchange
import com.gusak.lab.radarnebo.server.config.ArchiveConfig
import com.gusak.lab.radarnebo.server.config.DataSourceKind
import com.gusak.lab.radarnebo.server.config.ServerConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConsoleServerTest {
    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Test
    fun startsServesRecordsAndStops(): Unit = runBlocking {
        val server = ConsoleServer(ServerConfig(archive = ArchiveConfig(jdbcUrl = "memory"), dataSourceOverride = DataSourceKind.Simulation))
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
        server.stop()
    }

    @Test
    fun aTakenPortFailsCleanly(): Unit = runBlocking {
        ServerSocket(0).use { taken ->
            val server = ConsoleServer(ServerConfig(archive = ArchiveConfig(jdbcUrl = "memory"), dataSourceOverride = DataSourceKind.Simulation))
            withTimeout(20_000) { server.start(taken.localPort) }
            assertIs<ServerState.Failed>(server.state.value)
        }
    }
}
