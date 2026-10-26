package com.gusak.lab.radarnebo.server.domain.tracking

import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.tracking.DroneTrack

/**
 * Turns what the data source reports into tracks. Sources differ in what they report, so each comes with
 * its own strategy: alerts alone (alerts.in.ua) or surveillance sightings (the simulation).
 */
interface TargetTracking {
    /** Feeds in the source's latest report, made at [nowMs]. */
    fun update(alerts: List<AirAlert>, observations: List<TargetObservation>, nowMs: Long): TrackingUpdate
}

data class TrackingUpdate(
    /** Every track worth serving: active ones and recently ended ones. */
    val tracks: List<DroneTrack>,
    /** Tracks that ended in this update, to be archived. */
    val ended: List<DroneTrack>,
)
