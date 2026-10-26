package com.gusak.lab.radarnebo.tracking

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.domain.ThreatMarker
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
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
        /**
         * Shahed-type drones cruise at 150-185 km/h; reports come as a group crosses raions, 10-30 min apart. Replaying
         * the alerts.in.ua archive, a 45 min re-acquire window links the most reports while courses stay coherent;
         * at 60 min unrelated groups start being chained into tracks that turn back on themselves.
         */
        val Drones = TrackKinematics(220.0, 120.0, 45 * 60_000L, 30 * 60_000L)

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
     * track keeps moving along its [trend] at its (capped) speed, for at most the type's
     * [TrackKinematics.maxExtrapolationMs], so targets keep moving between reports.
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

        val b = s.last()
        val (heading, rawSpeed) = trend(s) ?: motion(s[s.lastIndex - 1], b)
        if (!track.active || rawSpeed < MIN_MOVING_KMH) return held(track, b, heading to rawSpeed)
        val speed = min(rawSpeed, kinematics.maxSpeedKmh)
        val elapsedMs = min(t - b.t, kinematics.maxExtrapolationMs)
        val ahead = GeoMath.destination(b.point, heading, speed * elapsedMs / 3_600_000.0)
        return InterpolatedPosition(
            trackId = track.id,
            lat = ahead.lat,
            lon = ahead.lon,
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

    /** Slower than this the fitted motion is noise: the group is holding where it was last reported. */
    private const val MIN_MOVING_KMH = 1.0
    /** Reports [trend] fits, at most, and how far back from the newest one they may go. */
    private const val TREND_SAMPLES = 4
    private const val TREND_WINDOW_MS = 90 * 60_000L
    private const val KM_PER_DEGREE = 111.2

    /**
     * Heading (degrees clockwise from north) and speed (km/h) fitted by least squares through the latest few
     * reports. Positions are raion and settlement centres, so a single hop's direction is coarse; the fit over
     * several averages that out and follows the group's course rather than its last zig. Null below two reports.
     */
    fun trend(samples: List<TrackSample>): Pair<Double, Double>? {
        val last = samples.lastOrNull() ?: return null
        val recent = samples.takeLast(TREND_SAMPLES).filter { last.t - it.t <= TREND_WINDOW_MS }
        if (recent.size < 2) return null
        val lonKm = cos(last.lat * PI / 180.0) * KM_PER_DEGREE
        val ts = recent.map { (it.t - last.t) / 3_600_000.0 }
        val xs = recent.map { (it.lon - last.lon) * lonKm }
        val ys = recent.map { (it.lat - last.lat) * KM_PER_DEGREE }
        val tMean = ts.average()
        val variance = ts.sumOf { (it - tMean) * (it - tMean) }
        if (variance <= 0.0) return null
        fun slope(vs: List<Double>): Double {
            val mean = vs.average()
            return ts.indices.sumOf { (ts[it] - tMean) * (vs[it] - mean) } / variance
        }
        val vx = slope(xs)
        val vy = slope(ys)
        return (atan2(vx, vy) * 180.0 / PI + 360.0) % 360.0 to kotlin.math.sqrt(vx * vx + vy * vy)
    }

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
 * Alerts only say which area is under threat, so movement is inferred. alerts.in.ua alerts per raion, and
 * its archive shows a group passing as a chain of raion alerts 10-30 minutes apart, each often raised for two
 * or three neighbouring raions within the same minute or two: one warning zone around one group. So:
 *
 * 1. **Reports.** New alerts of the same type and kind that start within [MERGE_WINDOW_MS] of each other
 *    and lie within [MERGE_KM] of their centroid are one report of one group, placed at the centroid. A report
 *    that comes that soon after a track's latest one, and as close, widens that report instead of moving the
 *    track: it is the same warning zone filling in, not the group flying on.
 * 2. **Association.** Each report goes to the track whose *predicted* position (its latest report moved on
 *    along its fitted velocity, see [TrackInterpolator.trend]) is nearest, among tracks of the same type and
 *    kind (a Gerbera decoy group never continues a Shahed track) that last reported within the type's
 *    re-acquire window, within its hop distance and reachable at its top speed (with slack for coarse alert
 *    timing). A drone track that has a heading never turns back more than [MAX_TURN_DEG] on a real hop:
 *    Shaheds fly preplanned routes with gentle turns, and a reversal is another group.
 * 3. Each track takes at most one report per update, so separate groups appearing together stay separate.
 *
 * Eligibility counts from a track's latest *report*, not from its alerts: frontline raions keep drone alerts
 * up for hours, and a track must not keep absorbing whatever appears nearby just because its first alert is
 * still on. Region-wide (oblast) alerts carry no useful position and are not tracked.
 * [maxHopKm] and [reacquireWindowMs] tune drones; missiles use [TrackKinematics.CruiseMissiles].
 *
 * Without [smart] it links alerts the original, basic way, kept as a fallback the app's settings can choose:
 * every alert is its own report, it continues the nearest track's latest position, a track stays open while
 * any of its alerts is, and simultaneous alerts always start separate tracks.
 */
class DroneTracker(
    /** Same-group filtering, predicted-position association and the turn gate; false links alerts the basic way. */
    private val smart: Boolean = true,
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
        /** The alerts behind the latest sample, which sits at their centroid. */
        var latestReport: Report,
    ) {
        val active get() = markerIds.isNotEmpty()
    }

    /** Alerts that came together as one group's warning zone; positioned at their centroid. */
    private class Report(val type: ThreatType, val kind: TargetKind, val markers: List<ThreatMarker>, val t: Long) {
        val point = GeoPoint(markers.map { it.point.lat }.average(), markers.map { it.point.lon }.average())
        val newest get() = markers.maxBy { it.startedAt?.toEpochMilliseconds() ?: 0L }
        val title get() = markers.map { it.title }.distinct().joinToString(", ")

        fun plus(other: Report) = Report(type, kind, markers + other.markers, minOf(t, other.t))

        fun sample() = TrackSample(t, point.lat, point.lon, title, newest.oblastUid, newest.message)
    }

    private val tracks = LinkedHashMap<String, MutableTrack>()
    private val markerToTrack = HashMap<String, String>()
    private var sequence = 0L

    fun update(markers: List<ThreatMarker>, nowMs: Long) {
        if (!smart) return updateBasic(markers, nowMs)
        val tracked = markers.filter { TrackKinematics.of(it.type) != null && !it.isRegionWide }
        val currentIds = tracked.mapTo(HashSet()) { it.id }

        // Threats that cleared detach from their tracks first, so the track can "move" to a neighbour.
        tracks.values.forEach { it.markerIds.retainAll(currentIds) }
        markerToTrack.keys.retainAll(currentIds)

        val fresh = tracked.filter { marker ->
            val existing = markerToTrack[marker.id]?.let(tracks::get) ?: return@filter true
            existing.lastSeen = nowMs
            existing.markerIds += marker.id
            false
        }

        val absorbed = HashSet<String>()
        groupIntoReports(fresh, nowMs).sortedBy { it.t }.forEach { report ->
            val widened = tracks.values.firstOrNull { it.widens(report) }
            val track = widened ?: bestTrack(report, nowMs, absorbed)
            when {
                track == null -> startTrack(report, nowMs)
                widened != null -> {
                    // The same warning zone filling in: the latest sample moves to the centre of all its alerts.
                    track.latestReport = track.latestReport.plus(report)
                    track.samples[track.samples.lastIndex] = track.latestReport.sample()
                    track.attach(report, nowMs)
                }
                GeoMath.distanceKm(track.samples.last().point, report.point) < SAME_PLACE_KM -> {
                    // Re-alerted in the same area: the group is still here, which says nothing about movement.
                    track.attach(report, nowMs)
                }
                else -> {
                    val last = track.samples.last()
                    val t = report.t.coerceIn(last.t + 1, maxOf(nowMs, last.t + 1))
                    track.latestReport = Report(report.type, report.kind, report.markers, t)
                    track.samples.addLast(track.latestReport.sample())
                    while (track.samples.size > maxSamples) track.samples.removeFirst()
                    track.oblastUid = report.newest.oblastUid
                    track.attach(report, nowMs)
                }
            }
            track?.let { absorbed += it.id }
        }

        tracks.values.removeAll { !it.active && nowMs - it.lastSeen > retentionMs }
    }

    /** The basic linking: each new alert continues the nearest reachable track, or starts one. */
    private fun updateBasic(markers: List<ThreatMarker>, nowMs: Long) {
        val tracked = markers.filter { TrackKinematics.of(it.type) != null && !it.isRegionWide }
        val currentIds = tracked.mapTo(HashSet()) { it.id }
        tracks.values.forEach { it.markerIds.retainAll(currentIds) }
        markerToTrack.keys.retainAll(currentIds)

        val absorbed = HashSet<String>()
        tracked.sortedBy { observedAt(it, nowMs) }.forEach { marker ->
            val existing = markerToTrack[marker.id]?.let(tracks::get)
            if (existing != null) {
                existing.lastSeen = nowMs
                existing.markerIds += marker.id
                return@forEach
            }
            val report = Report(marker.type, TargetClassifier.classify(marker).kind, listOf(marker), observedAt(marker, nowMs))
            val (hopKm, reacquireMs) = if (marker.type == ThreatType.Drones) {
                maxHopKm to reacquireWindowMs
            } else {
                TrackKinematics.of(marker.type)!!.let { it.maxHopKm to it.reacquireWindowMs }
            }
            val maxSpeedKmh = when {
                marker.type != ThreatType.Drones -> Double.POSITIVE_INFINITY
                report.kind == TargetKind.JetDrone -> TrackKinematics.JetDrones.maxSpeedKmh
                else -> TrackKinematics.Drones.maxSpeedKmh
            }
            val track = tracks.values
                .asSequence()
                .filter { it.type == marker.type && it.kind == report.kind }
                .filter { it.id !in absorbed && it.firstSeen < nowMs }
                .filter { it.active || nowMs - it.lastSeen <= reacquireMs }
                .map { it to GeoMath.distanceKm(it.samples.last().point, marker.point) }
                .filter { (track, km) -> km <= hopKm && km <= reachableKm(maxSpeedKmh, report.t - track.samples.last().t) }
                .minByOrNull { it.second }
                ?.first
            when {
                track == null -> startTrack(report, nowMs)
                GeoMath.distanceKm(track.samples.last().point, marker.point) < SAME_PLACE_KM -> track.attach(report, nowMs)
                else -> {
                    val last = track.samples.last()
                    val t = report.t.coerceIn(last.t + 1, maxOf(nowMs, last.t + 1))
                    track.latestReport = Report(report.type, report.kind, report.markers, t)
                    track.samples.addLast(track.latestReport.sample())
                    while (track.samples.size > maxSamples) track.samples.removeFirst()
                    track.oblastUid = marker.oblastUid
                    track.attach(report, nowMs)
                }
            }
            track?.let { absorbed += it.id }
        }

        tracks.values.removeAll { !it.active && nowMs - it.lastSeen > retentionMs }
    }

    /** Clusters alerts that started together close to each other into reports, oldest first. */
    private fun groupIntoReports(markers: List<ThreatMarker>, nowMs: Long): List<Report> {
        val reports = ArrayList<Report>()
        markers.sortedBy { observedAt(it, nowMs) }.forEach { marker ->
            val kind = TargetClassifier.classify(marker).kind
            val single = Report(marker.type, kind, listOf(marker), observedAt(marker, nowMs))
            val index = reports.indexOfFirst { it.type == marker.type && it.kind == kind && it.joins(single) }
            if (index >= 0) reports[index] = reports[index].plus(single) else reports += single
        }
        return reports
    }

    /** Whether [other] came soon enough after this report, and close enough, to be the same warning zone. */
    private fun Report.joins(other: Report) =
        other.t - t in 0..MERGE_WINDOW_MS && GeoMath.distanceKm(point, other.point) <= MERGE_KM

    private fun MutableTrack.widens(report: Report) =
        type == report.type && kind == report.kind && latestReport.joins(report)

    private fun bestTrack(report: Report, nowMs: Long, absorbed: Set<String>): MutableTrack? {
        val kinematics = TrackKinematics.of(report.type)!!
        val (hopKm, reacquireMs) = if (report.type == ThreatType.Drones) {
            maxHopKm to reacquireWindowMs
        } else {
            kinematics.maxHopKm to kinematics.reacquireWindowMs
        }
        // Missile alerts are raised far ahead of the missile, so their timing says little about its speed.
        val maxSpeedKmh = when {
            report.type != ThreatType.Drones -> Double.POSITIVE_INFINITY
            report.kind == TargetKind.JetDrone -> TrackKinematics.JetDrones.maxSpeedKmh
            else -> TrackKinematics.Drones.maxSpeedKmh
        }
        return tracks.values
            .asSequence()
            .filter { it.type == report.type && it.kind == report.kind }
            // Tracks born in this same update are excluded: simultaneous reports are separate groups.
            .filter { it.id !in absorbed && it.firstSeen < nowMs }
            .filter { report.t - it.samples.last().t <= reacquireMs }
            .filter { track ->
                val last = track.samples.last()
                val km = GeoMath.distanceKm(last.point, report.point)
                km <= hopKm && km <= reachableKm(maxSpeedKmh, report.t - last.t) &&
                    (report.type != ThreatType.Drones || !track.turnsBack(report))
            }
            .minByOrNull { GeoMath.distanceKm(it.predictedAt(report.t, kinematics), report.point) }
    }

    /** Where the track's group should be at [t], moving on from its latest report along its fitted velocity. */
    private fun MutableTrack.predictedAt(t: Long, kinematics: TrackKinematics): GeoPoint {
        val last = samples.last()
        val (heading, speed) = TrackInterpolator.trend(samples) ?: return last.point
        val km = minOf(speed, kinematics.maxSpeedKmh) * (t - last.t).coerceIn(0, kinematics.maxExtrapolationMs) / 3_600_000.0
        return GeoMath.destination(last.point, heading, km)
    }

    private fun MutableTrack.turnsBack(report: Report): Boolean {
        val last = samples.last()
        if (GeoMath.distanceKm(last.point, report.point) < TURN_CHECK_MIN_KM) return false
        val (heading, _) = TrackInterpolator.trend(samples) ?: return false
        val turn = abs(((GeoMath.bearingDeg(last.point, report.point) - heading + 540.0) % 360.0) - 180.0)
        return turn > MAX_TURN_DEG
    }

    private fun MutableTrack.attach(report: Report, nowMs: Long) {
        level = report.newest.level
        lastSeen = nowMs
        report.markers.forEach {
            markerIds += it.id
            markerToTrack[it.id] = id
        }
    }

    private fun startTrack(report: Report, nowMs: Long) {
        val id = "$idPrefix${++sequence}"
        tracks[id] = MutableTrack(
            id = id,
            type = report.type,
            kind = report.kind,
            level = report.newest.level,
            oblastUid = report.newest.oblastUid,
            firstSeen = nowMs,
            lastSeen = nowMs,
            markerIds = mutableSetOf(),
            samples = ArrayDeque(listOf(report.sample())),
            latestReport = report,
        ).apply { attach(report, nowMs) }
    }

    private fun observedAt(marker: ThreatMarker, nowMs: Long) = (marker.startedAt?.toEpochMilliseconds() ?: nowMs).coerceAtMost(nowMs)

    private companion object {
        const val SAME_PLACE_KM = 1.0
        /** Alerts raised together for one group's warning zone: within this long of each other... */
        const val MERGE_WINDOW_MS = 3 * 60_000L
        /** ...and this close to their centroid, about the spacing of neighbouring raion centres. */
        const val MERGE_KM = 80.0
        /** Largest change of course accepted for a drone group, on hops long enough to have a direction. */
        const val MAX_TURN_DEG = 100.0
        const val TURN_CHECK_MIN_KM = 25.0
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
