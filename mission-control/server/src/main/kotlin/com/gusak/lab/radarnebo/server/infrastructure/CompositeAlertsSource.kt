package com.gusak.lab.radarnebo.server.infrastructure

import com.gusak.lab.radarnebo.server.application.AlertsDataSource
import com.gusak.lab.radarnebo.server.application.FetchResult
import com.gusak.lab.radarnebo.server.application.SituationReport
import com.gusak.lab.radarnebo.server.config.DataSourceKind
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.alert.AlertLocation
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * Several sources as one, for fused tracking (`docs/trajectory-tracking.md` §2.6). The [primary] source decides
 * what clients are served (alerts, raw body, metrics, poll interval); every [extra] source is fetched on each poll
 * too, and its alerts and sightings are added to the report for tracking only, as [SituationReport.trackingAlerts]
 * and [SituationReport.observations]. Extra alerts get ids of their own (see [namespaced]), since ids from two
 * providers can repeat.
 *
 * An extra source that fails or has nothing new keeps its last report; one that failed [MAX_STALE_POLLS] times in a
 * row is left out until it recovers, so a dead feed does not hold old positions in the picture.
 */
class CompositeAlertsSource(
    private val primary: AlertsDataSource,
    private val extra: List<AlertsDataSource>,
) : AlertsDataSource {
    private val log = LoggerFactory.getLogger(CompositeAlertsSource::class.java)

    private class ExtraState(var lastModified: String? = null, var report: SituationReport? = null, var failures: Int = 0)

    private val states = extra.map { ExtraState() }
    /** The primary's last fresh report and validator, to merge into when only an extra source changed. */
    private var primaryReport: SituationReport? = null
    private var primaryLastModified: String? = null

    override val kind: DataSourceKind get() = primary.kind
    override val description: String get() = (listOf(primary) + extra).joinToString(" + ") { it.description }
    override val pollInterval: Duration get() = primary.pollInterval
    override val providesObservations: Boolean get() = primary.providesObservations || extra.any { it.providesObservations }
    override val locations: Collection<AlertLocation> get() = primary.locations
    override val requestsInLastMinute: Int get() = primary.requestsInLastMinute
    override val requestsTotal: Long get() = primary.requestsTotal
    override val rateLimitedTotal: Long get() = primary.rateLimitedTotal
    override val rejectedTotal: Long get() = primary.rejectedTotal
    override val otherFailuresTotal: Long get() = primary.otherFailuresTotal
    override val lastSuccessAt: Long? get() = primary.lastSuccessAt

    override suspend fun fetch(lastModified: String?): FetchResult {
        val result = primary.fetch(lastModified)
        val extraChanged = extra.indices.map { fetchExtra(it) }.any { it }
        return when (result) {
            is FetchResult.Failed -> result
            is FetchResult.Fresh -> {
                primaryReport = result.report
                primaryLastModified = result.lastModified
                FetchResult.Fresh(merged(result.report), result.lastModified)
            }
            FetchResult.NotModified -> {
                val last = primaryReport
                if (!extraChanged || last == null) FetchResult.NotModified else FetchResult.Fresh(merged(last), primaryLastModified)
            }
        }
    }

    /** Fetches extra source [i]; true when what it contributes changed. */
    private suspend fun fetchExtra(i: Int): Boolean {
        val state = states[i]
        return when (val result = extra[i].fetch(state.lastModified)) {
            is FetchResult.Fresh -> {
                state.report = result.report
                state.lastModified = result.lastModified
                state.failures = 0
                true
            }
            FetchResult.NotModified -> false
            is FetchResult.Failed -> {
                state.failures++
                log.warn("Extra source {} failed ({} in a row): {}", extra[i].description, state.failures, result.message)
                state.failures == MAX_STALE_POLLS
            }
        }
    }

    private fun merged(report: SituationReport): SituationReport {
        val live = states.withIndex().filter { (_, s) -> s.failures < MAX_STALE_POLLS }.mapNotNull { (i, s) -> s.report?.let { i to it } }
        return report.copy(
            trackingAlerts = report.trackingAlerts + live.flatMap { (i, r) -> (r.alerts + r.trackingAlerts).map { namespaced(it, i) } },
            observations = report.observations + live.flatMap { (_, r) -> r.observations },
        )
    }

    /** Extra source [index]'s alert ids moved into a range of their own: the source number in the top bits. */
    private fun namespaced(alert: AirAlert, index: Int) = alert.copy(id = ((index + 1).toLong() shl NAMESPACE_SHIFT) or alert.id)

    override fun history(nowMs: Long): Sequence<SituationReport> = primary.history(nowMs)

    override fun close() {
        (listOf(primary) + extra).forEach { runCatching { it.close() } }
    }

    private companion object {
        const val MAX_STALE_POLLS = 3
        /** alerts.in.ua ids are around 3·10⁵; 2⁴⁸ leaves room for any provider's. */
        const val NAMESPACE_SHIFT = 48
    }
}
