package com.gusak.lab.radarnebo.server

import com.gusak.lab.radarnebo.debug.TrafficRecorder
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
import com.gusak.lab.radarnebo.server.infrastructure.upstream.UpstreamRateLimiter
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

fun main() {
    val config = ServerConfig.fromEnv()
    val server = embeddedServer(CIO, port = config.port) { radarServer(ServerComponents.create(config)) }
    SingleInstance.acquire {
        server.stop(gracePeriodMillis = 500, timeoutMillis = 3_000)
        exitProcess(0)
    }
    server.start(wait = true)
}

/** The archive, in [ArchiveDatabase] or, with `ARCHIVE_JDBC_URL=memory`, in memory. */
class Archives(val alerts: AlertArchive, val tracks: TrackArchive) {
    companion object {
        fun inMemory() = Archives(InMemoryAlertArchive(), InMemoryTrackArchive())

        fun create(config: ServerConfig): Archives {
            if (config.archive.inMemory) return inMemory()
            val db = ArchiveDatabase(config.archive.jdbcUrl)
            val partition = config.dataSource.key
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
    /** Receives the server's HTTP traffic, both ways; the server console sets it. */
    val traffic: TrafficRecorder? = null,
) : AutoCloseable {
    /** The alerts.in.ua budget `/health` reports; null for the simulation. Follows the source actually in use. */
    val upstreamBudgetPerMinute: Int?
        get() = config.upstream.effectivePerMinute.takeIf { poller.source.kind == DataSourceKind.AlertsInUa }

    /** Releases what the components hold, e.g. the alerts.in.ua HTTP client; safe to call more than once. */
    override fun close() = poller.source.close()

    companion object {
        /**
         * [upstreamLimiter] guards every alerts.in.ua request; pass the same one to components built one after
         * another (a server restarted by the console) so restarts can't exceed the budget.
         */
        fun create(
            config: ServerConfig,
            clock: () -> Long = System::currentTimeMillis,
            traffic: TrafficRecorder? = null,
            upstreamLimiter: UpstreamRateLimiter = UpstreamRateLimiter(config.upstream.effectivePerMinute),
            // Defaults are evaluated in order: the archive opens first, so a failure there can't leak the source's HttpClient.
            archives: Archives = Archives.create(config),
            source: AlertsDataSource = defaultSource(config, clock, traffic, upstreamLimiter),
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
            return ServerComponents(config, situation, SituationPoller(source, situation, config.archive.retention, clock), clock, traffic)
        }

        private fun defaultSource(
            config: ServerConfig,
            clock: () -> Long,
            traffic: TrafficRecorder?,
            limiter: UpstreamRateLimiter,
        ): AlertsDataSource = when (config.dataSource) {
            DataSourceKind.Simulation -> SimulatedAlertsSource(config.simulation, clock)
            DataSourceKind.AlertsInUa ->
                AlertsInUaSource(config.upstream, AlertsInUaSource.defaultClient(config.logHttp, traffic), limiter, clock)
        }
    }
}

/**
 * Wires the server. [startPoller] is false in tests that drive [SituationPoller.pollOnce] themselves.
 * Stopping the engine closes [components].
 */
fun Application.radarServer(components: ServerComponents, startPoller: Boolean = true) {
    val config = components.config
    radarApi(
        situation = components.situation,
        poller = components.poller,
        clients = config.clients,
        upstreamBudgetPerMinute = components.upstreamBudgetPerMinute,
        clock = components.clock,
        traffic = components.traffic,
    )
    monitor.subscribe(ApplicationStopped) { components.close() }
    if (startPoller) launch { components.poller.run() }
}
