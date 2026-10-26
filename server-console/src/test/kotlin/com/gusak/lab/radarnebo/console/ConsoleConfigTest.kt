package com.gusak.lab.radarnebo.console

import com.gusak.lab.radarnebo.server.config.DataSourceKind
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ConsoleConfigTest {
    private fun serverDir(env: String): File = Files.createTempDirectory("server").toFile().apply {
        resolve(".env").writeText(env)
        deleteOnExit()
    }

    @Test
    fun readsTheServerEnvFileAndKeepsTheArchiveInTheServerDirectory() {
        val dir = serverDir("# comment\nALERTS_API_TOKEN=\"abc\"\nPORT=9090\nCLIENT_KEYS=\n")
        val config = consoleConfig(dir, environment = emptyMap())
        assertEquals("abc", config.upstream.token)
        assertEquals(DataSourceKind.AlertsInUa, config.dataSource)
        assertEquals(9090, config.port)
        assertEquals(emptySet(), config.clients.keys)
        assertEquals("jdbc:sqlite:${dir.resolve("data/radarnebo-archive.db").absolutePath}", config.archive.jdbcUrl)
    }

    @Test
    fun theEnvironmentWinsOverTheFile() {
        val dir = serverDir("PORT=9090\nARCHIVE_JDBC_URL=jdbc:sqlite:/tmp/x.db\n")
        val config = consoleConfig(dir, environment = mapOf("PORT" to "7070"))
        assertEquals(7070, config.port)
        assertEquals("jdbc:sqlite:/tmp/x.db", config.archive.jdbcUrl)
    }

    @Test
    fun withoutAServerDirectoryOnlyTheEnvironmentCounts() {
        val config = consoleConfig(serverDir = null, environment = mapOf("PORT" to "7070"))
        assertEquals(7070, config.port)
        assertEquals(DataSourceKind.Simulation, config.dataSource)
    }
}
