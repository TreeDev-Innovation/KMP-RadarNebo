package com.gusak.lab.radarnebo.data

import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.DroneTracker
import kotlin.time.Clock

/** Active alerts and the tracks built from them, as of the latest successful poll. */
data class AlertsSnapshot(
    val alerts: List<Alert>,
    /** Tracks of every threat class; the UI filters them by settings. */
    val tracks: List<DroneTrack>,
    /** Server clock minus local clock, applied before interpolating track positions. */
    val clockOffsetMs: Long,
)

sealed interface AlertsUpdate {
    data class Updated(val snapshot: AlertsSnapshot) : AlertsUpdate
    data class Failed(val message: String) : AlertsUpdate
}

/**
 * Where alerts and tracks come from: alerts.in.ua directly, a RadarNebo proxy (which also serves
 * tracks), or the simulation in demo mode. Without a proxy, tracks are built on the device.
 */
class AlertsRepository(
    private val api: AlertsApi,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var alerts: List<Alert> = emptyList()
    /** Builds tracks on the device when there is no RadarNebo proxy to provide them. */
    private var tracker = DroneTracker()
    private var simulationWarmedUp = false
    private var proxyTracks: List<DroneTrack> = emptyList()
    private var proxyClockOffsetMs = 0L

    suspend fun refresh(settings: AppSettings): AlertsUpdate {
        val now = clock()
        if (settings.isDemo) {
            if (!simulationWarmedUp) {
                // Replay the recent past so tracks have a history from the first poll.
                DroneSimulator.warmUp(tracker, now)
                simulationWarmedUp = true
            }
            alerts = DroneSimulator.alertsAt(now)
        } else {
            when (val result = api.activeAlerts(settings.baseUrl, settings.apiToken)) {
                is FetchResult.Fresh -> alerts = result.alerts
                FetchResult.NotModified -> Unit
                is FetchResult.Failure -> return AlertsUpdate.Failed(result.message)
            }
        }
        return AlertsUpdate.Updated(snapshot(settings, now))
    }

    /** Forgets everything fetched, e.g. after switching to another server or token. */
    fun reset() {
        alerts = emptyList()
        tracker = DroneTracker()
        simulationWarmedUp = false
        proxyTracks = emptyList()
        proxyClockOffsetMs = 0L
    }

    /**
     * Tracks change even when alerts don't (last-seen times, extrapolation), so they are refreshed on
     * every poll: from the proxy when there is one, otherwise by tracking the alerts on the device.
     */
    private suspend fun snapshot(settings: AppSettings, now: Long): AlertsSnapshot {
        if (settings.usesProxy) {
            // A failed track request keeps the previous tracks rather than wiping the map.
            api.droneTracks(settings.baseUrl, settings.apiToken)?.let { response ->
                proxyTracks = response.tracks
                proxyClockOffsetMs = response.serverTime - now
            }
            return AlertsSnapshot(alerts, proxyTracks, proxyClockOffsetMs)
        }
        // The tracker sees every threat class, so turning "drones only" off shows missile tracks with history.
        tracker.update(ThreatMapper.toMarkers(alerts, dronesOnly = false), now)
        return AlertsSnapshot(alerts, tracker.snapshot(), 0L)
    }
}
