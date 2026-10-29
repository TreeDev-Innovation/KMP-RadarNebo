package com.gusak.lab.radarnebo.console

import com.gusak.lab.radarnebo.server.config.DataSourceKind
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleConfigTest {
    private fun serverDir(env: String): File = Files.createTempDirectory("server").toFile().apply {
        resolve(".env").writeText(env)
        deleteOnExit()
    }

    @Test
    fun readsTheServerEnvFileAndKeepsTheArchiveInTheServerDirectory() {
        // Only the first '=' splits, so a base64 token keeps its padding; keys and values are trimmed.
        val dir = serverDir("# comment\nALERTS_API_TOKEN=ab==\nJUNK\n PORT = 9090 \nSIM_SEED=\"42\"\nCLIENT_KEYS=\n")
        val config = consoleConfig(dir, environment = emptyMap())
        assertEquals("ab==", config.upstream.token)
        assertEquals(DataSourceKind.AlertsInUa, config.dataSource)
        assertEquals(9090, config.port)
        assertEquals(42L, config.simulation.seed)
        assertEquals(emptySet(), config.clients.keys)
        assertEquals("jdbc:sqlite:${dir.resolve("data/radarnebo-archive.db").absolutePath}", config.archive.jdbcUrl)
    }

    @Test
    fun theEnvironmentWinsOverTheFile() {
        val dir = serverDir("PORT=9090\nARCHIVE_JDBC_URL=jdbc:sqlite:/tmp/x.db\n")
        val config = consoleConfig(dir, environment = mapOf("PORT" to "7070"))
        assertEquals(7070, config.port)
        assertEquals("jdbc:sqlite:/tmp/x.db", config.archive.jdbcUrl)
        assertTrue(consoleConfig(dir, mapOf("ARCHIVE_JDBC_URL" to "memory")).archive.inMemory)
    }

    @Test
    fun withoutAServerDirectoryOnlyTheEnvironmentCounts() {
        val config = consoleConfig(serverDir = null, environment = mapOf("PORT" to "7070"))
        assertEquals(7070, config.port)
        assertEquals(DataSourceKind.Simulation, config.dataSource)
    }
}
