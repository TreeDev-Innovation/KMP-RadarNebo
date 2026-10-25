package com.gusak.lab.radarnebo.server.infrastructure.simulation

import com.gusak.lab.radarnebo.server.application.AlertsDataSource
import com.gusak.lab.radarnebo.server.application.FetchResult
import com.gusak.lab.radarnebo.server.application.SituationReport
import com.gusak.lab.radarnebo.server.config.DataSourceKind
import com.gusak.lab.radarnebo.server.config.SimulationConfig
import com.gusak.lab.radarnebo.server.domain.alert.AlertLocation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Simulated alerts for running the server without an alerts.in.ua token: the [AirSituationSimulator]'s
 * combined strikes and front-line activity, reported in real time. Never touches the network.
 *
 * Unlike alerts.in.ua it has air surveillance: it reports a sighting of every target, so the server tracks
 * targets from their real flight paths rather than reconstructing them from alerts.
 */
class SimulatedAlertsSource(
    private val config: SimulationConfig = SimulationConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AlertsDataSource {
    // Ids only grow, also across restarts: they start from the time the replayed history begins.
    private val simulator = AirSituationSimulator(config, firstAlertId = (clock() - config.warmUp.inWholeMilliseconds) / 100)

    override val kind = DataSourceKind.Simulation
    override val description = "SIMULATION (no ALERTS_API_TOKEN set): combined drone and missile strikes every " +
        "${config.strikePeriod}, front-line guided bombs, aviation and reconnaissance drones"
    override val pollInterval: Duration get() = config.tick
    override val providesObservations = true
    override val locations: Collection<AlertLocation> get() = simulator.locations

    /** The simulation at [nowMs], for tests that step through it. */
    fun reportAt(nowMs: Long): SituationReport = simulator.reportAt(nowMs)

    override suspend fun fetch(lastModified: String?): FetchResult = FetchResult.Fresh(simulator.reportAt(clock()))

    override fun history(nowMs: Long): Sequence<SituationReport> = sequence {
        val step = maxOf(config.tick, MIN_REPLAY_STEP).inWholeMilliseconds
        var t = nowMs - config.warmUp.inWholeMilliseconds
        while (t < nowMs) {
            yield(simulator.reportAt(t))
            t += step
        }
    }

    private companion object {
        /** Replay steps can be coarser than live ticks: only the resulting state matters. */
        val MIN_REPLAY_STEP = 30.seconds
    }
}
