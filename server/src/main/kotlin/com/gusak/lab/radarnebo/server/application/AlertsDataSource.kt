package com.gusak.lab.radarnebo.server.application

import com.gusak.lab.radarnebo.server.config.DataSourceKind
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.alert.AlertLocation
import com.gusak.lab.radarnebo.server.domain.tracking.TargetObservation
import kotlin.time.Duration

/** Everything a data source knew at [observedAt]: the active alerts and, if it has surveillance, target sightings. */
data class SituationReport(
    val observedAt: Long,
    val alerts: List<AirAlert>,
    val observations: List<TargetObservation> = emptyList(),
)

/** What one fetch from an [AlertsDataSource] produced. */
sealed interface FetchResult {
    /** New content. [lastModified] is the source's validator for the next conditional fetch. */
    data class Fresh(val report: SituationReport, val lastModified: String? = null) : FetchResult

    /** The content is unchanged since the `lastModified` passed to [AlertsDataSource.fetch]. */
    data object NotModified : FetchResult

    /** Wait at least [minWaitMs] (or the source's [retryAfterMs]) before trying again. */
    data class Failed(val message: String, val retryAfterMs: Long?, val minWaitMs: Long) : FetchResult
}

/**
 * Where the server's alerts come from: alerts.in.ua or the simulation. Only the server knows; clients get
 * the same API either way.
 */
interface AlertsDataSource {
    val kind: DataSourceKind

    /** For the startup log line. */
    val description: String

    /** How often to fetch. */
    val pollInterval: Duration

    /** True when the source reports [SituationReport.observations], so tracks come from sightings rather than alerts. */
    val providesObservations: Boolean

    /** Locations the source declares alerts for, when it knows them in advance. */
    val locations: Collection<AlertLocation> get() = emptyList()

    /** Requests sent upstream in the last minute and in total; zero for sources that make none. */
    val requestsInLastMinute: Int get() = 0
    val requestsTotal: Long get() = 0

    suspend fun fetch(lastModified: String?): FetchResult

    /**
     * Reports leading up to [nowMs], oldest first, replayed before the first fetch so tracks and alerts
     * have a past from the first request. Empty for sources without history.
     */
    fun history(nowMs: Long): Sequence<SituationReport> = emptySequence()
}
