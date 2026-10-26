package com.gusak.lab.radarnebo.console

import com.gusak.lab.radarnebo.server.config.ArchiveConfig
import com.gusak.lab.radarnebo.server.config.ServerConfig
import java.io.File

/**
 * The server's settings as `:server:run` gets them: the environment, then `.env` in the server's directory
 * (the `radarnebo.server.dir` system property the build passes), with the archive kept under that directory.
 * Variables set in the environment win over the file.
 */
fun consoleConfig(
    serverDir: File? = System.getProperty("radarnebo.server.dir")?.let(::File),
    environment: Map<String, String> = System.getenv(),
): ServerConfig {
    val fromFile = serverDir?.resolve(".env")?.takeIf { it.isFile }?.let(::readEnvFile).orEmpty()
    val env = fromFile + environment
    val config = ServerConfig.fromEnv(env)
    if (serverDir == null || "ARCHIVE_JDBC_URL" in env) return config
    val archive = serverDir.resolve(ArchiveConfig.DEFAULT_JDBC_URL.removePrefix("jdbc:sqlite:")).absolutePath
    return config.copy(archive = config.archive.copy(jdbcUrl = "jdbc:sqlite:$archive"))
}

/** `KEY=value` lines; blank values, comments and lines without `=` are skipped, surrounding quotes removed. */
internal fun readEnvFile(file: File): Map<String, String> = file.readLines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
    .map { it.substringBefore("=").trim() to it.substringAfter("=").trim().removeSurrounding("\"") }
    .filter { (_, value) -> value.isNotEmpty() }
    .toMap()
