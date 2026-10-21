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

/** One observed position of a drone group (or missile). [t] is epoch milliseconds. */
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
 * Position history of a drone group or cruise missile, reconstructed from consecutive area alerts:
 * when a threat appears close to where another of the same type was just reported, it is treated as
 * the same target moving.
 */
@Serializable
data class DroneTrack(
    val id: String,
    /** Threat class being tracked; older proxies only tracked drones and omit it. */
    val type: ThreatType = ThreatType.Drones,
    val level: ThreatLevel,
    val oblastUid: Int?,
    val active: Boolean,
    val firstSeen: Long,
    val lastSeen: Long,
    /** Alert threat ids (`alertId:threatType`) currently attached to this track. */
    val markerIds: List<String>,
    val samples: List<TrackSample>,
    /** How the flight ended, when the server knows; null while in flight or when only alerts are known. */
    val outcome: TrackOutcome? = null,
)

/** How a tracked flight ended. */
@Serializable
enum class TrackOutcome {
    /** Shot down or jammed by air defence. */
    Intercepted,

    /** Reached its target. */
    Impact,

    /** Contact lost: no more reports, fate unknown. */
    Lost,
}

@Serializable
data class DroneTracksResponse(
    /** Server clock, so clients can correct for clock skew before interpolating. */
    val serverTime: Long,
    val pollIntervalMs: Long,
    val lastUpstreamFetch: Long?,
    val tracks: List<DroneTrack>,
    /** True when the server serves simulated data; clients must label it as such. */
    val simulated: Boolean = false,
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

/**
 * How a tracked threat class moves, which sets how its alerts are linked into tracks and how far its
 * position is projected past the last report. Classes without kinematics (ballistic missiles, bombs,
 * aviation, which only come as area alerts) are shown as markers and not tracked from alerts.
 */
data class TrackKinematics(
    /** Faster apparent speeds are artefacts of coarse alert timing. */
    val maxSpeedKmh: Double,
    /** Furthest a new alert can be from a track's last position and still continue it. */
    val maxHopKm: Double,
    /** How long a track that lost its alerts can still be picked up again. */
    val reacquireWindowMs: Long,
    /** Dead reckoning past the newest sample stops after this long, to avoid inventing positions. */
    val maxExtrapolationMs: Long,
) {
    companion object {
        /** Shahed-type drones cruise at 150-185 km/h; reports come as a group crosses hromadas, 10-30 min apart. */
        val Drones = TrackKinematics(220.0, 120.0, 30 * 60_000L, 30 * 60_000L)

        /** Jet Shaheds (Geran-3) fly at 350-550 km/h; they come as drone alerts, recognised by the alert text. */
        val JetDrones = TrackKinematics(600.0, 120.0, 15 * 60_000L, 10 * 60_000L)

        /** Cruise missiles fly at 700-950 km/h and cross an oblast in minutes, so reports are further apart. */
        val CruiseMissiles = TrackKinematics(1_000.0, 300.0, 10 * 60_000L, 5 * 60_000L)

        /**
         * Guided bombs (KAB with UMPK kits) glide 40-70 km in a few minutes. Alerts are raised for the target,
         * not along the way, so they are never linked from alerts; a source that knows their flight path
         * (the proxy's simulation) publishes their tracks directly.
         */
        val GuidedBombs = TrackKinematics(900.0, 0.0, 0L, 60_000L)

        /**
         * Ballistic and aeroballistic missiles (Iskander-M, KN-23, Kinzhal) cover hundreds of km in minutes.
         * Alerts are raised for whole oblasts, so they are never linked from alerts; only a source that sees
         * the launch publishes their tracks.
         */
        val BallisticMissiles = TrackKinematics(12_000.0, 0.0, 0L, 30_000L)

        fun of(type: ThreatType): TrackKinematics? = when (type) {
            ThreatType.Drones -> Drones
            ThreatType.CruiseMissiles, ThreatType.UnspecifiedMissiles -> CruiseMissiles
            else -> null
        }

        fun of(track: DroneTrack): TrackKinematics = when {
            track.type == ThreatType.Drones && TargetClassifier.classify(track).kind == TargetKind.JetDrone -> JetDrones
            track.type == ThreatType.GuidedBombs -> GuidedBombs
            track.type == ThreatType.BallisticMissiles -> BallisticMissiles
            else -> of(track.type) ?: Drones
        }
    }
}

object TrackInterpolator {
    /** Drone speed cap; see [TrackKinematics.Drones]. */
    const val MAX_SPEED_KMH = 220.0
    /** Drone dead-reckoning limit; see [TrackKinematics.Drones]. */
    const val MAX_EXTRAPOLATION_MS = 30 * 60_000L

    /** Tracks worth drawing at [t]: active ones and those that ended less than their extrapolation limit ago. */
    fun isVisible(track: DroneTrack, t: Long) = track.active || t - track.lastSeen <= TrackKinematics.of(track).maxExtrapolationMs

    /**
     * Linear interpolation between the two samples surrounding [t]. Past the newest sample an active
     * track keeps moving along its last heading at its last (capped) speed, for at most
     * the type's [TrackKinematics.maxExtrapolationMs], so targets keep moving between reports.
     */
    fun positionAt(track: DroneTrack, t: Long): InterpolatedPosition? {
        val kinematics = TrackKinematics.of(track)
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
                speedKmh = min(speed, kinematics.maxSpeedKmh),
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
        val speed = min(rawSpeed, kinematics.maxSpeedKmh)
        val elapsedMs = min(t - b.t, kinematics.maxExtrapolationMs)
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
 * Builds [DroneTrack]s from successive snapshots of threats that fly routes: drones and cruise
 * missiles (see [TrackKinematics.of]).
 *
 * Alerts only say which area is under threat, so movement is inferred: a newly appearing threat is
 * attached to the nearest track of the same type and kind (a Gerbera decoy group never continues a
 * Shahed track) whose latest position is within that type's hop
 * distance and which was seen within its re-acquire window. A drone track also has to be able to get
 * there at the kind's top speed (with slack for coarse alert timing), so a group appearing next to a
 * track that just moved on is not mistaken for it. Each track absorbs at most one new alert
 * per update, so a wave of simultaneous alerts spawns separate tracks rather than one zig-zagging
 * track. Region-wide (oblast) alerts carry no useful position and are not tracked.
 * [maxHopKm] and [reacquireWindowMs] tune drones; missiles use [TrackKinematics.CruiseMissiles].
 */
class DroneTracker(
    private val maxHopKm: Double = TrackKinematics.Drones.maxHopKm,
    private val reacquireWindowMs: Long = TrackKinematics.Drones.reacquireWindowMs,
    private val retentionMs: Long = 3 * 60 * 60_000L,
    private val maxSamples: Int = 64,
    /** Prefix of track ids; a server that keeps tracks across restarts makes it unique per run. */
    private val idPrefix: String = "trk-",
) {
    private class MutableTrack(
        val id: String,
        val type: ThreatType,
        val kind: TargetKind,
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
        val tracked = markers.filter { TrackKinematics.of(it.type) != null && !it.isRegionWide }
        val currentIds = tracked.mapTo(HashSet()) { it.id }

        // Threats that cleared detach from their tracks first, so the track can "move" to a neighbour.
        tracks.values.forEach { it.markerIds.retainAll(currentIds) }
        markerToTrack.keys.retainAll(currentIds)

        val absorbed = HashSet<String>()
        tracked.sortedBy { it.startedAt?.toEpochMilliseconds() ?: nowMs }.forEach { marker ->
            val existing = markerToTrack[marker.id]?.let(tracks::get)
            if (existing != null) {
                existing.lastSeen = nowMs
                existing.markerIds += marker.id
                return@forEach
            }
            val observedAt = (marker.startedAt?.toEpochMilliseconds() ?: nowMs).coerceAtMost(nowMs)
            val kind = TargetClassifier.classify(marker).kind
            val (hopKm, reacquireMs) = if (marker.type == ThreatType.Drones) {
                maxHopKm to reacquireWindowMs
            } else {
                TrackKinematics.of(marker.type)!!.let { it.maxHopKm to it.reacquireWindowMs }
            }
            // Missile alerts are raised far ahead of the missile, so their timing says little about its speed.
            val maxSpeedKmh = when {
                marker.type != ThreatType.Drones -> Double.POSITIVE_INFINITY
                kind == TargetKind.JetDrone -> TrackKinematics.JetDrones.maxSpeedKmh
                else -> TrackKinematics.Drones.maxSpeedKmh
            }
            val track = tracks.values
                .asSequence()
                .filter { it.type == marker.type && it.kind == kind }
                // Tracks born in this same update are excluded: simultaneous alerts are separate groups.
                .filter { it.id !in absorbed && it.firstSeen < nowMs }
                .filter { it.active || nowMs - it.lastSeen <= reacquireMs }
                .map { it to GeoMath.distanceKm(it.samples.last().point, marker.point) }
                .filter { (track, km) -> km <= hopKm && km <= reachableKm(maxSpeedKmh, observedAt - track.samples.last().t) }
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
                val id = "$idPrefix${++sequence}"
                tracks[id] = MutableTrack(
                    id = id,
                    type = marker.type,
                    kind = kind,
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
        /** Alerts can come early or late; a hop is plausible up to this much over top speed, plus [TIMING_SLACK_KM]. */
        const val SPEED_TOLERANCE = 1.5
        const val TIMING_SLACK_KM = 25.0

        fun reachableKm(maxSpeedKmh: Double, elapsedMs: Long) =
            maxSpeedKmh * SPEED_TOLERANCE * elapsedMs.coerceAtLeast(0) / 3_600_000.0 + TIMING_SLACK_KM
    }

    fun snapshot(): List<DroneTrack> = tracks.values.map {
        DroneTrack(
            id = it.id,
            type = it.type,
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
