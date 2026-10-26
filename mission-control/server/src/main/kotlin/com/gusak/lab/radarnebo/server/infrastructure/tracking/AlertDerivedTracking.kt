package com.gusak.lab.radarnebo.server.infrastructure.tracking

import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.server.contract.AlertsInUaContract.toDto
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.tracking.TargetObservation
import com.gusak.lab.radarnebo.server.domain.tracking.TargetTracking
import com.gusak.lab.radarnebo.server.domain.tracking.TrackingUpdate
import com.gusak.lab.radarnebo.tracking.DroneTracker

/**
 * Tracks reconstructed from alerts alone, for sources without surveillance (alerts.in.ua): the core
 * [DroneTracker] links consecutive raion and settlement alerts of drones and cruise missiles into tracks.
 * A second, basic tracker follows the same alerts with smart mapping off, for clients that ask for it;
 * only the smart tracks are archived. Track ids carry [runId], so they stay unique in the archive across restarts.
 */
class AlertDerivedTracking(runId: String) : TargetTracking {
    private val tracker = DroneTracker(idPrefix = "trk-$runId-")
    private val basicTracker = DroneTracker(smart = false, idPrefix = "trk-$runId-b")
    private var active = emptySet<String>()

    override fun update(alerts: List<AirAlert>, observations: List<TargetObservation>, nowMs: Long): TrackingUpdate {
        // Every threat class goes in; the tracker follows the ones that fly routes.
        val dtos = alerts.map { it.toDto() }
        tracker.update(ThreatMapper.toMarkers(dtos, dronesOnly = false), nowMs)
        basicTracker.update(ThreatMapper.toMarkers(dtos, dronesOnly = false, smartMapping = false), nowMs)
        val tracks = tracker.snapshot()
        val ended = tracks.filter { !it.active && it.id in active }
        active = tracks.filter { it.active }.mapTo(HashSet()) { it.id }
        return TrackingUpdate(tracks, ended, basicTracks = basicTracker.snapshot())
    }
}
