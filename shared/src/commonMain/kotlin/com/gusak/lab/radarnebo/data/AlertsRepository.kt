package com.gusak.lab.radarnebo.data

import com.gusak.lab.radarnebo.tracking.DroneTrack
import kotlin.time.Clock

/** Active alerts and the server's tracks, as of the latest successful poll. */
data class AlertsSnapshot(
    val alerts: List<Alert>,
    /** Tracks of every threat class; the UI filters them by settings. */
    val tracks: List<DroneTrack>,
    /** Server clock minus local clock, applied before interpolating track positions. */
    val clockOffsetMs: Long,
    /** The server says its data is simulated; the UI must label it. */
    val simulated: Boolean,
    /** The server's own report on its data source; null if it didn't answer. */
    val health: ServerHealth? = null,
)

sealed interface AlertsUpdate {
    data class Updated(val snapshot: AlertsSnapshot) : AlertsUpdate
    /** [backOff] as in [FetchResult.Failure]. */
    data class Failed(val message: String, val backOff: Boolean = false) : AlertsUpdate
}

/**
 * Alerts and tracks from the RadarNebo server, the app's only data source. The server tracks targets and
 * decides where its data comes from; the app only reads and, between polls, interpolates positions.
 */
class AlertsRepository(
    private val api: RadarNeboApi,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var alerts: List<Alert> = emptyList()
    private var tracks: List<DroneTrack> = emptyList()
    private var clockOffsetMs = 0L
    private var simulated = false

    /** Polls [endpoint] once; [smartMapping] picks which of the server's tracks to fetch. */
    suspend fun refresh(endpoint: ServerEndpoint, smartMapping: Boolean = true): AlertsUpdate {
        when (val result = api.activeAlerts(endpoint)) {
            is FetchResult.Fresh -> alerts = result.alerts
            FetchResult.NotModified -> Unit
            is FetchResult.Failure -> return AlertsUpdate.Failed(result.message, result.backOff)
        }
        // Tracks move even when alerts don't, so they are fetched on every poll. A failed request keeps the
        // previous tracks rather than wiping the map.
        api.tracks(endpoint, smartMapping)?.let { response ->
            tracks = response.tracks
            clockOffsetMs = response.serverTime - clock()
            simulated = response.simulated
        }
        return AlertsUpdate.Updated(AlertsSnapshot(alerts, tracks, clockOffsetMs, simulated, api.health(endpoint)))
    }

    /** Forgets everything fetched, e.g. after switching to another server. */
    fun reset() {
        alerts = emptyList()
        tracks = emptyList()
        clockOffsetMs = 0L
        simulated = false
    }
}
