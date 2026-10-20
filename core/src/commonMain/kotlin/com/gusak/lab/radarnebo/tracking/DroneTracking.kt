package com.gusak.lab.radarnebo.tracking

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.domain.ThreatMarker
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min

/** One observed position of a drone group. [t] is epoch milliseconds. */
@Serializable
data class TrackSample(
    val t: Long,
    val lat: Double,
    val lon: Double,
    /** Where the threat was reported: settlement, hromada or raion name. */
    val title: String,
    val oblastUid: Int? = null,
    /** Original alert text, which may name the drone type. */
    val message: String? = null,
) {
    val point get() = GeoPoint(lat, lon)
}

/**
 * Position history of a drone group, reconstructed from consecutive area alerts: when a drone
 * threat appears close to where another one was just reported, it is treated as the same group moving.
 */
@Serializable
data class DroneTrack(
    val id: String,
    val level: ThreatLevel,
    val oblastUid: Int?,
    val active: Boolean,
    val firstSeen: Long,
    val lastSeen: Long,
    /** alerts.in.ua threat ids (`alertId:threatType`) currently attached to this track. */
    val markerIds: List<String>,
    val samples: List<TrackSample>,
)

@Serializable
data class DroneTracksResponse(
    /** Server clock, so clients can correct for clock skew before interpolating. */
    val serverTime: Long,
    val pollIntervalMs: Long,
    val lastUpstreamFetch: Long?,
    val tracks: List<DroneTrack>,
)

@Serializable
enum class PositionMode { Held, Interpolated, Extrapolated }

@Serializable
data class InterpolatedPosition(
    val trackId: String,
    val lat: Double,
    val lon: Double,
    /** Degrees clockwise from north, when the track has moved at least once. */
    val headingDeg: Double?,
    val speedKmh: Double?,
    val mode: PositionMode,
    val level: ThreatLevel,
) {
    val point get() = GeoPoint(lat, lon)
}

@Serializable
data class DronePositionsResponse(
    val at: Long,
    val positions: List<InterpolatedPosition>,
)

object TrackInterpolator {
    /** Shahed-type drones cruise at roughly 150-185 km/h; anything faster is an artefact of coarse alert timing. */
    const val MAX_SPEED_KMH = 220.0
    /**
     * Dead reckoning past the newest sample stops after this long, to avoid inventing positions.
     * Reports arrive as a group crosses hromadas, typically 10-30 minutes apart.
     */
    const val MAX_EXTRAPOLATION_MS = 30 * 60_000L

    /**
     * Linear interpolation between the two samples surrounding [t]. Past the newest sample an active
     * track keeps moving along its last heading at its last (capped) speed, for at most
     * [MAX_EXTRAPOLATION_MS], so drones keep moving between reports.
     */
    fun positionAt(track: DroneTrack, t: Long): InterpolatedPosition? {
        val s = track.samples
        if (s.isEmpty()) return null
        if (s.size == 1 || t <= s.first().t) return held(track, s.first(), segmentMotion(s))

        val i = s.indexOfLast { it.t <= t }
        if (i < s.lastIndex) {
            val a = s[i]
            val b = s[i + 1]
            val f = (t - a.t).toDouble() / (b.t - a.t).coerceAtLeast(1)
            val (heading, speed) = motion(a, b)
            return InterpolatedPosition(
                trackId = track.id,
                lat = a.lat + (b.lat - a.lat) * f,
                lon = a.lon + (b.lon - a.lon) * f,
                headingDeg = heading,
                speedKmh = min(speed, MAX_SPEED_KMH),
                mode = PositionMode.Interpolated,
                level = track.level,
            )
        }

        val a = s[s.lastIndex - 1]
        val b = s.last()
        val (heading, rawSpeed) = motion(a, b)
        if (!track.active) return held(track, b, heading to rawSpeed)
        val segmentKm = GeoMath.distanceKm(a.point, b.point)
        if (segmentKm < 0.01) return held(track, b, heading to rawSpeed)
        val speed = min(rawSpeed, MAX_SPEED_KMH)
        val elapsedMs = min(t - b.t, MAX_EXTRAPOLATION_MS)
        val travelKm = speed * elapsedMs / 3_600_000.0
        val f = travelKm / segmentKm
        return InterpolatedPosition(
            trackId = track.id,
            lat = b.lat + (b.lat - a.lat) * f,
            lon = b.lon + (b.lon - a.lon) * f,
            headingDeg = heading,
            speedKmh = speed,
            mode = PositionMode.Extrapolated,
            level = track.level,
        )
    }

    private fun held(track: DroneTrack, sample: TrackSample, motion: Pair<Double, Double>?) = InterpolatedPosition(
        trackId = track.id,
        lat = sample.lat,
        lon = sample.lon,
        headingDeg = motion?.first,
        speedKmh = null,
        mode = PositionMode.Held,
        level = track.level,
    )

    private fun segmentMotion(s: List<TrackSample>) = if (s.size >= 2) motion(s[s.lastIndex - 1], s.last()) else null

    /** Heading in degrees clockwise from north and average speed in km/h between two samples. */
    fun motion(a: TrackSample, b: TrackSample): Pair<Double, Double> {
        val dx = (b.lon - a.lon) * cos(a.lat * PI / 180.0)
        val dy = b.lat - a.lat
        val heading = (atan2(dx, dy) * 180.0 / PI + 360.0) % 360.0
        val hours = (b.t - a.t).coerceAtLeast(1) / 3_600_000.0
        return heading to GeoMath.distanceKm(a.point, b.point) / hours
    }
}

/**
 * Builds [DroneTrack]s from successive snapshots of drone threats.
 *
 * Alerts only say which area is under threat, so movement is inferred: a newly appearing drone threat
 * is attached to the nearest track whose latest position is within [maxHopKm] and which was seen
 * within [reacquireWindowMs]. Each track absorbs at most one new alert per update, so a wave of
 * simultaneous alerts spawns separate tracks rather than one zig-zagging track. Region-wide (oblast)
 * alerts carry no useful position and are not tracked.
 */
class DroneTracker(
    private val maxHopKm: Double = 120.0,
    private val reacquireWindowMs: Long = 30 * 60_000L,
    private val retentionMs: Long = 3 * 60 * 60_000L,
    private val maxSamples: Int = 64,
) {
    private class MutableTrack(
        val id: String,
        var level: ThreatLevel,
        var oblastUid: Int?,
        val firstSeen: Long,
        var lastSeen: Long,
        val markerIds: MutableSet<String>,
        val samples: ArrayDeque<TrackSample>,
    ) {
        val active get() = markerIds.isNotEmpty()
    }

    private val tracks = LinkedHashMap<String, MutableTrack>()
    private val markerToTrack = HashMap<String, String>()
    private var sequence = 0L

    fun update(markers: List<ThreatMarker>, nowMs: Long) {
        val drones = markers.filter { it.type == ThreatType.Drones && !it.isRegionWide }
        val currentIds = drones.mapTo(HashSet()) { it.id }

        // Threats that cleared detach from their tracks first, so the track can "move" to a neighbour.
        tracks.values.forEach { it.markerIds.retainAll(currentIds) }
        markerToTrack.keys.retainAll(currentIds)

        val absorbed = HashSet<String>()
        drones.sortedBy { it.startedAt?.toEpochMilliseconds() ?: nowMs }.forEach { marker ->
            val existing = markerToTrack[marker.id]?.let(tracks::get)
            if (existing != null) {
                existing.lastSeen = nowMs
                existing.markerIds += marker.id
                return@forEach
            }
            val observedAt = (marker.startedAt?.toEpochMilliseconds() ?: nowMs).coerceAtMost(nowMs)
            val track = tracks.values
                .asSequence()
                // Tracks born in this same update are excluded: simultaneous alerts are separate groups.
                .filter { it.id !in absorbed && it.firstSeen < nowMs }
                .filter { it.active || nowMs - it.lastSeen <= reacquireWindowMs }
                .map { it to GeoMath.distanceKm(it.samples.last().point, marker.point) }
                .filter { it.second <= maxHopKm }
                .minByOrNull { it.second }
                ?.first
            if (track != null && GeoMath.distanceKm(track.samples.last().point, marker.point) < SAME_PLACE_KM) {
                // Re-alerted in the same area: the group is still here, which says nothing about movement.
                track.level = marker.level
                track.lastSeen = nowMs
                track.markerIds += marker.id
                absorbed += track.id
                markerToTrack[marker.id] = track.id
            } else if (track != null) {
                val t = observedAt.coerceIn(track.samples.last().t + 1, maxOf(nowMs, track.samples.last().t + 1))
                track.samples.addLast(TrackSample(t, marker.point.lat, marker.point.lon, marker.title, marker.oblastUid, marker.message))
                while (track.samples.size > maxSamples) track.samples.removeFirst()
                track.level = marker.level
                track.oblastUid = marker.oblastUid
                track.lastSeen = nowMs
                track.markerIds += marker.id
                absorbed += track.id
                markerToTrack[marker.id] = track.id
            } else {
                val id = "trk-${++sequence}"
                tracks[id] = MutableTrack(
                    id = id,
                    level = marker.level,
                    oblastUid = marker.oblastUid,
                    firstSeen = nowMs,
                    lastSeen = nowMs,
                    markerIds = mutableSetOf(marker.id),
                    samples = ArrayDeque(listOf(TrackSample(observedAt, marker.point.lat, marker.point.lon, marker.title, marker.oblastUid, marker.message))),
                )
                markerToTrack[marker.id] = id
            }
        }

        tracks.values.removeAll { !it.active && nowMs - it.lastSeen > retentionMs }
    }

    private companion object {
        const val SAME_PLACE_KM = 1.0
    }

    fun snapshot(): List<DroneTrack> = tracks.values.map {
        DroneTrack(
            id = it.id,
            level = it.level,
            oblastUid = it.oblastUid,
            active = it.active,
            firstSeen = it.firstSeen,
            lastSeen = it.lastSeen,
            markerIds = it.markerIds.toList(),
            samples = it.samples.toList(),
        )
    }
}
