package com.gusak.lab.radarnebo.server

import com.gusak.lab.radarnebo.server.api.radarApi
import com.gusak.lab.radarnebo.server.application.AlertsDataSource
import com.gusak.lab.radarnebo.server.application.SituationPoller
import com.gusak.lab.radarnebo.server.application.SituationService
import com.gusak.lab.radarnebo.server.config.DataSourceKind
import com.gusak.lab.radarnebo.server.config.ServerConfig
import com.gusak.lab.radarnebo.server.domain.alert.AlertArchive
import com.gusak.lab.radarnebo.server.domain.alert.LocationDirectory
import com.gusak.lab.radarnebo.server.domain.tracking.ObservationTracking
import com.gusak.lab.radarnebo.server.domain.tracking.TrackArchive
import com.gusak.lab.radarnebo.server.infrastructure.persistence.ArchiveDatabase
import com.gusak.lab.radarnebo.server.infrastructure.persistence.ExposedAlertArchive
import com.gusak.lab.radarnebo.server.infrastructure.persistence.ExposedTrackArchive
import com.gusak.lab.radarnebo.server.infrastructure.persistence.InMemoryAlertArchive
import com.gusak.lab.radarnebo.server.infrastructure.persistence.InMemoryTrackArchive
import com.gusak.lab.radarnebo.server.infrastructure.simulation.SimulatedAlertsSource
import com.gusak.lab.radarnebo.server.infrastructure.tracking.AlertDerivedTracking
import com.gusak.lab.radarnebo.server.infrastructure.upstream.AlertsInUaSource
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.launch

fun main() {
    val config = ServerConfig.fromEnv()
    embeddedServer(CIO, port = config.port) { radarServer(ServerComponents.create(config)) }.start(wait = true)
}

/** The archive, in [ArchiveDatabase] or, with `ARCHIVE_JDBC_URL=memory`, in memory. */
class Archives(val alerts: AlertArchive, val tracks: TrackArchive) {
    companion object {
        const val IN_MEMORY = "memory"

        fun inMemory() = Archives(InMemoryAlertArchive(), InMemoryTrackArchive())

        fun create(config: ServerConfig): Archives {
            if (config.archive.jdbcUrl == IN_MEMORY) return inMemory()
            val db = ArchiveDatabase(config.archive.jdbcUrl)
            val partition = config.dataSource.name
            return Archives(ExposedAlertArchive(db, partition), ExposedTrackArchive(db, partition))
        }
    }
}

/** The composition root: the object graph for one data source. */
class ServerComponents(
    val config: ServerConfig,
    val situation: SituationService,
    val poller: SituationPoller,
    val clock: () -> Long,
) {
    companion object {
        fun create(
            config: ServerConfig,
            clock: () -> Long = System::currentTimeMillis,
            source: AlertsDataSource = defaultSource(config, clock),
            archives: Archives = Archives.create(config),
        ): ServerComponents {
            // A source with air surveillance gives real flight paths; otherwise tracks are inferred from alerts.
            val tracking = if (source.providesObservations) ObservationTracking() else AlertDerivedTracking(runId = (clock() / 1000).toString(36))
            val situation = SituationService(
                tracking = tracking,
                alertArchive = archives.alerts,
                trackArchive = archives.tracks,
                locations = LocationDirectory(source.locations),
                simulated = source.kind == DataSourceKind.Simulation,
            )
            return ServerComponents(config, situation, SituationPoller(source, situation, config.archive.retention, clock), clock)
        }

        private fun defaultSource(config: ServerConfig, clock: () -> Long): AlertsDataSource = when (config.dataSource) {
            DataSourceKind.Simulation -> SimulatedAlertsSource(config.simulation, clock)
            DataSourceKind.AlertsInUa -> AlertsInUaSource(config.upstream, clock = clock)
        }
    }
}

/** Wires the server. [startPoller] is false in tests that drive [SituationPoller.pollOnce] themselves. */
fun Application.radarServer(components: ServerComponents, startPoller: Boolean = true) {
    val config = components.config
    radarApi(
        situation = components.situation,
        poller = components.poller,
        clients = config.clients,
        upstreamBudgetPerMinute = config.upstream.effectivePerMinute.takeIf { config.dataSource == DataSourceKind.AlertsInUa },
        clock = components.clock,
    )
    if (startPoller) launch { components.poller.run() }
}
