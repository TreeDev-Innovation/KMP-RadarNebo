package com.gusak.lab.radarnebo.server.domain.alert

import kotlin.time.Instant

/**
 * The alerts active right now. [version] changes exactly when the content does; [confirmedAt] is when the
 * source last vouched for the content.
 */
data class AlertBoard(
    val alerts: List<AirAlert>,
    val version: Long,
    val changedAt: Instant,
    val confirmedAt: Instant,
) {
    private val byId: Map<Long, AirAlert> by lazy { alerts.associateBy { it.id } }

    /**
     * The board after the source reported [next] at [at]. Alerts that disappeared are finished at [at]
     * unless the source already finished them. Alerts keep the source's order, so clients get the list as
     * alerts.in.ua sent it.
     */
    fun next(next: List<AirAlert>, at: Instant): BoardChange {
        val active = next.filter { it.isActive }
        if (active == alerts) return BoardChange(copy(confirmedAt = at))
        val nextIds = active.mapTo(HashSet()) { it.id }
        val finishedBySource = next.filterNot { it.isActive }.associateBy { it.id }
        return BoardChange(
            board = AlertBoard(active, version + 1, at, at),
            opened = active.filter { it.id !in byId },
            updated = active.filter { alert -> byId[alert.id]?.let { it != alert } == true },
            finished = alerts.filter { it.id !in nextIds }.map { finishedBySource[it.id] ?: it.finish(at) },
        )
    }

    fun confirmed(at: Instant): AlertBoard = copy(confirmedAt = at)

    companion object {
        fun empty(at: Instant) = AlertBoard(emptyList(), version = 0, changedAt = at, confirmedAt = at)
    }
}

/** What changed between two boards. */
data class BoardChange(
    val board: AlertBoard,
    val opened: List<AirAlert> = emptyList(),
    val updated: List<AirAlert> = emptyList(),
    val finished: List<AirAlert> = emptyList(),
) {
    val changed: Boolean get() = opened.isNotEmpty() || updated.isNotEmpty() || finished.isNotEmpty()
}
