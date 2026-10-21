package com.gusak.lab.radarnebo.server

import com.gusak.lab.radarnebo.data.ActiveAlertsResponse
import com.gusak.lab.radarnebo.data.Alert
import com.gusak.lab.radarnebo.data.AlertsApi
import com.gusak.lab.radarnebo.data.DroneSimulator
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.DroneTracker

/**
 * Simulated alerts for running the proxy without an upstream token: [DroneSimulator]'s scripted
 * flights and area alerts, with [DemoRaids]' KAB launches and random Shahed groups on top. Guided
 * bombs come only from [DemoRaids], which also publishes their flight paths as tracks.
 *
 * Never touches the network. Like the simulator, the feed is a pure function of [clock].
 */
class SimulatedAlertsSource(private val clock: () -> Long = System::currentTimeMillis) : AlertsDataSource {
    override val demo = true
    override val description = "SIMULATION (no ALERTS_API_TOKEN set): simulated drones, missiles, KABs and aviation"

    fun alertsAt(nowMs: Long): List<Alert> =
        DroneSimulator.alertsAt(nowMs).filterNot { alert -> alert.threats.all { it.threatType == ThreatType.GuidedBombs.apiValue } } +
            DemoRaids.alertsAt(nowMs)

    override suspend fun fetch(lastModified: String?): FetchResult {
        val alerts = alertsAt(clock())
        return FetchResult.Fresh(AlertsApi.json.encodeToString(ActiveAlertsResponse.serializer(), ActiveAlertsResponse(alerts)), alerts, null)
    }

    /** Replays the last [minutes] of the feed, one snapshot a minute, so tracks have a past from the first request. */
    override fun prime(tracker: DroneTracker, nowMs: Long) {
        for (m in WARM_UP_MINUTES downTo 1) {
            val t = nowMs - m * 60_000L
            tracker.update(ThreatMapper.toMarkers(alertsAt(t), dronesOnly = false), t)
        }
    }

    override fun directTracks(nowMs: Long): List<DroneTrack> = DemoRaids.kabTracks(nowMs)

    private companion object {
        const val WARM_UP_MINUTES = 90
    }
}
