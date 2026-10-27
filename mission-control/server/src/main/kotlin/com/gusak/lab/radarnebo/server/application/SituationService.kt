package com.gusak.lab.radarnebo.server.application

import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.alert.AirRaidStatusMap
import com.gusak.lab.radarnebo.server.domain.alert.AlertArchive
import com.gusak.lab.radarnebo.server.domain.alert.AlertBoard
import com.gusak.lab.radarnebo.server.domain.alert.LocationDirectory
import com.gusak.lab.radarnebo.server.domain.tracking.TargetObservation
import com.gusak.lab.radarnebo.server.domain.tracking.TargetTracking
import com.gusak.lab.radarnebo.server.domain.tracking.TrackArchive
import com.gusak.lab.radarnebo.server.domain.tracking.TrackQuery
import com.gusak.lab.radarnebo.tracking.DroneTrack
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * The current air situation as clients see it: the active alerts and the tracked targets, plus their
 * archive. Reports from the data source come in through [publish]; client requests only read, from
 * immutable snapshots, so they never wait for the poller.
 */
class SituationService(
    private val tracking: TargetTracking,
    private val alertArchive: AlertArchive,
    private val trackArchive: TrackArchive,
    private val locations: LocationDirectory,
    /** True when the data is simulated; served to clients so they can label it. */
    val simulated: Boolean,
) {
    /** Null until the first report. */
    @Volatile
    var board: AlertBoard? = null
        private set

    @Volatile
    var tracks: List<DroneTrack> = emptyList()
        private set

    private val mutex = Mutex()
    private var reconciled = false

    /** Applies a fresh report and archives what changed. */
    suspend fun publish(report: SituationReport) = mutex.withLock { apply(report, archive = true) }

    /** Applies a report from before the server started: it builds up state but is not archived. */
    suspend fun replay(report: SituationReport) = mutex.withLock { apply(report, archive = false) }

    /** The source confirmed at [atMs] that nothing changed; tracks still age. */
    suspend fun confirm(atMs: Long) = mutex.withLock {
        val current = board ?: return@withLock
        board = current.confirmed(Instant.fromEpochMilliseconds(atMs))
        val ended = advanceTracks(current.alerts, emptyList(), atMs)
        if (ended.isNotEmpty()) trackArchive.save(ended)
    }

    fun activeAlerts(): List<AirAlert> = board?.alerts.orEmpty()

    fun airRaidStatus(board: AlertBoard): AirRaidStatusMap = AirRaidStatusMap(board.alerts, locations)

    /** Alerts for [uid] active at some point since [since], archived and current, newest first. */
    suspend fun alertHistory(uid: String, since: Instant, limit: Int): List<AirAlert> {
        val live = activeAlerts().filter { it.concerns(uid) }
        val liveIds = live.mapTo(HashSet()) { it.id }
        return (live + alertArchive.history(uid, since, limit).filter { it.id !in liveIds })
            .sortedByDescending { it.startedAt }
            .take(limit)
    }

    /** Tracks in the air at some point in [query]'s window, current and archived, newest first. */
    suspend fun trackHistory(query: TrackQuery): List<DroneTrack> {
        val live = tracks.filter(query::overlaps)
        val liveIds = live.mapTo(HashSet()) { it.id }
        return (live + trackArchive.history(query).filter { it.id !in liveIds })
            .sortedByDescending { it.lastSeen }
            .take(query.limit)
    }

    /** Deletes archived alerts and tracks that ended before [before]. */
    suspend fun pruneArchive(before: Instant): Int =
        alertArchive.prune(before) + trackArchive.prune(before.toEpochMilliseconds())

    private suspend fun apply(report: SituationReport, archive: Boolean) {
        val at = Instant.fromEpochMilliseconds(report.observedAt)
        locations.learn(report.alerts)
        val change = (board ?: AlertBoard.empty(at)).next(report.alerts, at)
        board = change.board
        val ended = advanceTracks(change.board.alerts, report.observations, report.observedAt)
        if (!archive) return

        if (!reconciled) {
            reconcile(change.board, change.finished, at)
            reconciled = true
        } else if (change.changed) {
            try {
                alertArchive.save(change.opened + change.updated + change.finished)
            } catch (e: Exception) {
                // The next publish then re-runs reconcile(), bringing the archive back in line with the board.
                reconciled = false
                throw e
            }
        }
        if (ended.isNotEmpty()) trackArchive.save(ended)
    }

    /** Feeds the report into tracking, serves the new tracks and returns those that ended, for the archive. */
    private fun advanceTracks(alerts: List<AirAlert>, observations: List<TargetObservation>, atMs: Long): List<DroneTrack> {
        val update = tracking.update(alerts, observations, atMs)
        tracks = update.tracks
        return update.ended
    }

    /**
     * First live report: alerts the archive still has open from before a restart, but that are gone now,
     * finished while the server was down; everything active now, or just finished, is saved.
     */
    private suspend fun reconcile(board: AlertBoard, finished: List<AirAlert>, at: Instant) {
        val active = board.alerts.mapTo(HashSet()) { it.id }
        val stale = alertArchive.unfinished().filter { it.id !in active }.map { it.finish(at) }
        alertArchive.save(stale + finished + board.alerts)
    }
}
