package com.gusak.lab.radarnebo.server.infrastructure.tracking

import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.domain.ThreatMarker
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.server.contract.AlertsInUaContract.toDto
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.tracking.PlaceNamer
import com.gusak.lab.radarnebo.server.domain.tracking.TargetObservation
import com.gusak.lab.radarnebo.server.domain.tracking.TargetTracking
import com.gusak.lab.radarnebo.server.domain.tracking.TrackingUpdate
import com.gusak.lab.radarnebo.tracking.DroneTracker
import com.gusak.lab.radarnebo.tracking.SamplePrecision
import kotlin.time.Instant

/**
 * Tracks fused from every report a source gives: area alerts and position sightings go into one [DroneTracker]
 * as reports of the same kind, each with its own error (an area's, or about a kilometre for a sighting). The
 * tracker associates them by position and time, not by the source's target ids, so a source with sparse or
 * partial surveillance fills the gaps of the alerts and the other way round, and the same target reported by both
 * merges into one track. Track ids carry [runId], so they stay unique in the archive across restarts.
 */
class FusedTracking(runId: String, private val placeNamer: PlaceNamer = PlaceNamer.NearestCity) : TargetTracking {
    private val tracker = DroneTracker(idPrefix = "fus-$runId-")
    private var active = emptySet<String>()

    override fun update(alerts: List<AirAlert>, observations: List<TargetObservation>, nowMs: Long): TrackingUpdate {
        val markers = ThreatMapper.toMarkers(alerts.map { it.toDto() }, dronesOnly = false) + observations.map(::toMarker)
        tracker.update(markers, nowMs)
        val tracks = tracker.snapshot()
        val ended = tracks.filter { !it.active && it.id in active }
        active = tracks.filter { it.active }.mapTo(HashSet()) { it.id }
        return TrackingUpdate(tracks, ended)
    }

    /**
     * A sighting as a point report. Sightings of one target within [SIGHTING_SPACING_MS] share an id, so a
     * source that sights every few seconds adds a report every couple of minutes rather than flooding the track.
     */
    private fun toMarker(observation: TargetObservation): ThreatMarker {
        val oblastUid = Regions.oblastAt(observation.position)
        return ThreatMarker(
            id = "sighting:${observation.targetId}:${observation.observedAt / SIGHTING_SPACING_MS}",
            type = observation.type,
            level = observation.level,
            title = placeNamer.describe(observation.position),
            oblastUid = oblastUid,
            oblastName = oblastUid?.let { Regions.oblast(it)?.nameEn }.orEmpty(),
            locationType = "sighting",
            point = observation.position,
            startedAt = Instant.fromEpochMilliseconds(observation.observedAt),
            message = observation.message,
            precision = SamplePrecision.Exact,
        )
    }

    private companion object {
        const val SIGHTING_SPACING_MS = 2 * 60_000L
    }
}
