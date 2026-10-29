package com.gusak.lab.radarnebo.server.domain.tracking

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.SamplePrecision
import com.gusak.lab.radarnebo.tracking.TrackOutcome
import com.gusak.lab.radarnebo.tracking.TrackSample
import kotlin.math.roundToInt

/** Describes a position for people: "Konotop", or "12 km NE of Konotop". */
fun interface PlaceNamer {
    fun describe(point: GeoPoint): String

    companion object {
        private val compass = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        /** Relative to the nearest settlement with city status. */
        val NearestCity = PlaceNamer { point ->
            val (city, km) = Regions.nearestCity(point)
            if (km < 3.0) {
                city.name
            } else {
                val bearing = GeoMath.bearingDeg(GeoPoint(city.lat.toDouble(), city.lon.toDouble()), point)
                "${km.roundToInt()} km ${compass[((bearing + 22.5) / 45).toInt() % 8]} of ${city.name}"
            }
        }
    }
}

/**
 * Tracks built from surveillance sightings, one track per target. The newest sighting is always the
 * track's last sample, so clients interpolate from the freshest position; older samples are kept at least
 * [sampleSpacingMs] apart, and thinned out once a track has more than [maxSamples], so long flights keep
 * their whole route at a size clients can poll.
 *
 * A track ends with the sighting that carries an outcome, or as [TrackOutcome.Lost] when no sighting came
 * for [contactTimeoutMs]. Ended tracks stay served for [endedRetentionMs], so clients see where they ended.
 */
class ObservationTracking(
    private val placeNamer: PlaceNamer = PlaceNamer.NearestCity,
    private val contactTimeoutMs: Long = 5 * 60_000L,
    private val endedRetentionMs: Long = 15 * 60_000L,
    private val maxSamples: Int = 60,
) : TargetTracking {

    private class Track(
        val id: String,
        val type: ThreatType,
        val firstSeen: Long,
    ) {
        var level: ThreatLevel = ThreatLevel.Red
        var lastSeen: Long = firstSeen
        var outcome: TrackOutcome? = null
        var ended = false
        var markerIds: List<String> = emptyList()
        val samples = ArrayList<TrackSample>()

        /** The last sample is provisional: replaced by newer sightings until it is [spacing] past the one before. */
        fun add(sample: TrackSample, spacing: Long) {
            val provisional = samples.size >= 2 && samples[samples.lastIndex].t - samples[samples.lastIndex - 1].t < spacing
            if (provisional) samples[samples.lastIndex] = sample else samples += sample
        }

        fun toDroneTrack() = DroneTrack(
            id = id,
            type = type,
            level = level,
            oblastUid = samples.lastOrNull()?.oblastUid,
            active = !ended,
            firstSeen = firstSeen,
            lastSeen = lastSeen,
            markerIds = if (ended) emptyList() else markerIds,
            samples = samples.toList(),
            outcome = outcome,
        )
    }

    private val tracks = LinkedHashMap<String, Track>()

    override fun update(alerts: List<AirAlert>, observations: List<TargetObservation>, nowMs: Long): TrackingUpdate {
        val ended = mutableListOf<Track>()
        observations.sortedBy { it.observedAt }.forEach { observation ->
            val track = tracks.getOrPut(observation.targetId) { Track(observation.targetId, observation.type, observation.observedAt) }
            if (track.ended || observation.observedAt < track.lastSeen) return@forEach
            track.add(sample(observation), sampleSpacingMs(track.type))
            track.level = observation.level
            track.lastSeen = observation.observedAt
            track.markerIds = observation.alertMarkerIds
            if (observation.outcome != null) {
                track.outcome = observation.outcome
                track.ended = true
                ended += track
            }
        }
        tracks.values.filter { !it.ended && nowMs - it.lastSeen > contactTimeoutMs }.forEach { track ->
            track.outcome = TrackOutcome.Lost
            track.ended = true
            ended += track
        }
        tracks.values.forEach { thin(it.samples) }
        tracks.values.removeAll { it.ended && nowMs - it.lastSeen > endedRetentionMs }
        return TrackingUpdate(tracks.values.map { it.toDroneTrack() }, ended.map { it.toDroneTrack() })
    }

    private fun sample(observation: TargetObservation) = TrackSample(
        t = observation.observedAt,
        lat = observation.position.lat,
        lon = observation.position.lon,
        title = placeNamer.describe(observation.position),
        oblastUid = Regions.oblastAt(observation.position),
        message = observation.message,
        precision = SamplePrecision.Exact,
    )

    /** Drops every other sample from the older half, keeping the first and the newest ones. */
    private fun thin(samples: MutableList<TrackSample>) {
        if (samples.size <= maxSamples) return
        val older = samples.subList(1, samples.size / 2)
        val kept = older.filterIndexed { i, _ -> i % 2 == 1 }
        older.clear()
        older.addAll(kept)
    }

    private companion object {
        /** Faster targets need denser history for their route to keep its shape. */
        fun sampleSpacingMs(type: ThreatType): Long = when (type) {
            ThreatType.Drones -> 60_000L
            ThreatType.CruiseMissiles, ThreatType.UnspecifiedMissiles -> 20_000L
            ThreatType.GuidedBombs -> 15_000L
            else -> 10_000L
        }
    }
}
