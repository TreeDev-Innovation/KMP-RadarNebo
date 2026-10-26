package com.gusak.lab.radarnebo.server.domain.alert

import kotlin.time.Instant

/**
 * Every alert the server has served, active and finished. Active alerts are saved too, so a restart can
 * tell which alerts were still open and finish them ([unfinished]).
 */
interface AlertArchive {
    /** Inserts or replaces [alerts] by id. */
    suspend fun save(alerts: Collection<AirAlert>)

    /** Alerts saved as active: open when the server stopped, or still open now. */
    suspend fun unfinished(): List<AirAlert>

    /**
     * Alerts for the location [uid] that were active at some point since [since], newest first. For an
     * oblast, also the alerts of its raions, hromadas and cities.
     */
    suspend fun history(uid: String, since: Instant, limit: Int = 1_000): List<AirAlert>

    /** Deletes alerts that finished before [before]; returns how many. */
    suspend fun prune(before: Instant): Int
}
