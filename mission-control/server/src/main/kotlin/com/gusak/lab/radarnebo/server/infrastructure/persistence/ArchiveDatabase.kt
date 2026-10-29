package com.gusak.lab.radarnebo.server.infrastructure.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.sql.Connection

/** Archived alerts, one row per alert record. [payload] is the alert in the alerts.in.ua format. */
internal object AlertsTable : Table("alerts") {
    /** Which data source the alert came from, so simulated and real ids never mix. */
    val partition = varchar("source", 32)
    val id = long("id")
    val locationUid = varchar("location_uid", 64)
    val oblastUid = integer("oblast_uid").nullable()
    val startedAt = long("started_at")
    val finishedAt = long("finished_at").nullable()
    val payload = text("payload")

    override val primaryKey = PrimaryKey(partition, id)

    init {
        index(isUnique = false, partition, locationUid, startedAt)
        index(isUnique = false, partition, oblastUid, startedAt)
        index(isUnique = false, partition, finishedAt)
    }
}

/** Archived tracks of finished flights. [payload] is the track as the tracks API serves it. */
internal object TracksTable : Table("tracks") {
    val partition = varchar("source", 32)
    val id = varchar("id", 128)
    val type = varchar("type", 64)
    val firstSeen = long("first_seen")
    val lastSeen = long("last_seen")
    val payload = text("payload")

    override val primaryKey = PrimaryKey(partition, id)

    init {
        index(isUnique = false, partition, lastSeen)
    }
}

/**
 * The archive database, SQLite by default (`jdbc:sqlite:data/radarnebo-archive.db`). The schema is created
 * on first use. Queries run on [Dispatchers.IO], so request and poller coroutines never block on JDBC.
 */
class ArchiveDatabase(jdbcUrl: String) {
    private val database: Database

    init {
        val isSqlite = jdbcUrl.startsWith(SQLITE_PREFIX)
        sqliteFile(jdbcUrl)?.absoluteFile?.parentFile?.mkdirs()
        database = Database.connect(
            url = jdbcUrl,
            setupConnection = { connection ->
                if (isSqlite) connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            },
            // SQLite supports only serializable transactions.
            databaseConfig = DatabaseConfig { defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE },
        )
        transaction(database) { SchemaUtils.create(AlertsTable, TracksTable) }
    }

    internal suspend fun <T> query(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { transaction(database) { block() } }

    private companion object {
        const val SQLITE_PREFIX = "jdbc:sqlite:"

        /** The database file of a SQLite URL; null for other databases and in-memory (`:memory:`) URLs. */
        fun sqliteFile(jdbcUrl: String): File? {
            if (!jdbcUrl.startsWith(SQLITE_PREFIX)) return null
            val path = jdbcUrl.removePrefix(SQLITE_PREFIX).substringBefore('?')
            return path.takeIf { it.isNotBlank() && !it.startsWith(":") }?.let(::File)
        }
    }
}
