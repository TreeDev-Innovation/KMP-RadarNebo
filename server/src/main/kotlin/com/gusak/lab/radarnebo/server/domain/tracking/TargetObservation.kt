package com.gusak.lab.radarnebo.server.domain.tracking

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.tracking.TrackOutcome

/**
 * A sighting of one airborne target (a drone group, a missile, a bomb) by air surveillance: where it was
 * at [observedAt]. Sightings of the same target share [targetId]. A sighting with an [outcome] is the
 * last one: the target was shot down, hit its target or was lost.
 */
data class TargetObservation(
    val targetId: String,
    val type: ThreatType,
    val observedAt: Long,
    val position: GeoPoint,
    val level: ThreatLevel = ThreatLevel.Red,
    /** Warning text naming the weapon, as the alerts word it. */
    val message: String?,
    val outcome: TrackOutcome? = null,
    /** Alert threats (`alertId:threatType`) raised because the target is there. */
    val alertMarkerIds: List<String> = emptyList(),
)
