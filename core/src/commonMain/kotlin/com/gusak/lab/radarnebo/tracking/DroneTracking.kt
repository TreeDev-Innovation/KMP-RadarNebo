package com.gusak.lab.radarnebo.tracking

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.domain.ThreatMarker
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.geo.Regions
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

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
    /** How precisely the report places the target; null from servers that did not say (treated as a raion). */
    val precision: SamplePrecision? = null,
    /** The alert level of the report: a yellow one names where the target is heading. Null when not an alert. */
    val level: ThreatLevel? = null,
    /** One-sigma position error in km when the reported area's size is known; else [precision] decides. */
    val errorKm: Double? = null,
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
    /**
     * Still in flight: an alert for it is open and its newest report is no older than the type's
     * [TrackKinematics.staleAfterMs]. alerts.in.ua leaves alerts open for hours (frontline ones for weeks),
     * long after the target that raised them has passed, so an open alert alone does not make a target active.
     */
    val active: Boolean,
    val firstSeen: Long,
    /** Time of the newest report of this target (alert or position), not of the last poll that still listed it. */
    val lastSeen: Long,
    /** Alert threat ids (`alertId:threatType`) currently attached to this track. */
    val markerIds: List<String>,
    val samples: List<TrackSample>,
    /** How the flight ended, when the server knows; null while in flight or when only alerts are known. */
    val outcome: TrackOutcome? = null,
    /** What the server takes the target for; null from older servers. */
    val kind: TargetKind? = null,
    /** The server's fused position and motion at the newest report (see [TrajectoryEstimator]); null from older servers. */
    val estimate: TrackEstimate? = null,
    /** Smoothed past trajectory, oldest first, ending at [estimate]. Empty from older servers: draw [samples]. */
    val path: List<PathPoint> = emptyList(),
    /** Expected course from the time the server published the track; empty once it ended or before it moved. */
    val forecast: List<ForecastPoint> = emptyList(),
    /** Settlements on the forecast course, nearest first. */
    val arrivals: List<TrackArrival> = emptyList(),
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
    /**
     * With no new report for this long the target is presumed gone (passed, shot down or landed), even while
     * the alert it raised stays open: a target still flying keeps raising alerts as it crosses areas.
     */
    val staleAfterMs: Long = maxOf(reacquireWindowMs, maxExtrapolationMs),
    /** Slowest believable cruise; alert timing makes groups look slower than they fly. */
    val minSpeedKmh: Double = 0.0,
    /** How fast the course can change, in degrees per hour. */
    val maxTurnDegPerHour: Double = 3_600.0,
    /** Process noise of the [TrackFilter] (km²/h³): how much the velocity may wander between reports. */
    val processNoise: Double = 2e4,
    /** How long before the target a yellow (preliminary) alert is typically raised for an area on its way. */
    val warningLeadMs: Long = 0L,
    /** How long before the target a red alert is typically raised: areas a few minutes ahead go red too. */
    val redLeadMs: Long = 0L,
    /** How far ahead the server forecasts the course. */
    val forecastHorizonMs: Long = maxExtrapolationMs,
    /** Spacing of the smoothed path's points. */
    val pathStepKm: Double = 2.0,
    /** Furthest it flies; a track that would grow longer is another target. */
    val rangeKm: Double = Double.POSITIVE_INFINITY,
    /** Circles and doubles back (decoys), so a reversed course does not mean another target. */
    val loiters: Boolean = false,
) {
    /** How far ahead of the target an alert of [level] is raised; nothing for reports that are not alerts. */
    fun leadMs(level: ThreatLevel?): Long = when (level) {
        ThreatLevel.Yellow -> warningLeadMs
        ThreatLevel.Red -> redLeadMs
        null -> 0L
    }

    companion object {
        /**
         * Shahed-type drones cruise at 150-185 km/h; reports come as a group crosses raions, 10-30 min apart. Replaying
         * the alerts.in.ua archive, a 45 min re-acquire window links the most reports while courses stay coherent;
         * at 60 min unrelated groups start being chained into tracks that turn back on themselves.
         */
        val Drones = TrackKinematics(
            220.0, 120.0, 45 * 60_000L, 30 * 60_000L, staleAfterMs = 60 * 60_000L,
            minSpeedKmh = 120.0, maxTurnDegPerHour = 360.0, processNoise = 5e3, warningLeadMs = 20 * 60_000L, redLeadMs = 4 * 60_000L,
            rangeKm = 2_500.0,
        )

        /** Gerbera decoys are slower and wander more: they are flown to draw air defence, not to a target. */
        val DecoyDrones = Drones.copy(
            maxSpeedKmh = 200.0, minSpeedKmh = 100.0, maxTurnDegPerHour = 540.0, processNoise = 4e4, rangeKm = 1_000.0, loiters = true,
        )

        /** Jet Shaheds (Geran-3) fly at 350-550 km/h; they come as drone alerts, recognised by the alert text. */
        val JetDrones = TrackKinematics(
            600.0, 120.0, 15 * 60_000L, 10 * 60_000L, staleAfterMs = 20 * 60_000L,
            minSpeedKmh = 350.0, maxTurnDegPerHour = 540.0, processNoise = 1e5, warningLeadMs = 7 * 60_000L, redLeadMs = 2 * 60_000L, pathStepKm = 5.0, rangeKm = 2_500.0,
        )

        /** Cruise missiles fly at 700-950 km/h and cross an oblast in minutes, so reports are further apart. */
        val CruiseMissiles = TrackKinematics(
            1_000.0, 300.0, 10 * 60_000L, 5 * 60_000L, staleAfterMs = 15 * 60_000L,
            minSpeedKmh = 650.0, maxTurnDegPerHour = 1_080.0, processNoise = 5e5, warningLeadMs = 8 * 60_000L, redLeadMs = 2 * 60_000L, pathStepKm = 10.0, rangeKm = 4_000.0,
        )

        /**
         * Guided bombs (KAB with UMPK kits) glide 40-70 km in a few minutes. Alerts are raised for the target,
         * not along the way, so they are never linked from alerts; a source that knows their flight path
         * (the proxy's simulation) publishes their tracks directly.
         */
        val GuidedBombs = TrackKinematics(900.0, 0.0, 0L, 60_000L, processNoise = 1e6)

        /**
         * Ballistic and aeroballistic missiles (Iskander-M, KN-23, Kinzhal) cover hundreds of km in minutes.
         * Alerts are raised for whole oblasts, so they are never linked from alerts; only a source that sees
         * the launch publishes their tracks.
         */
        val BallisticMissiles = TrackKinematics(12_000.0, 0.0, 0L, 30_000L, processNoise = 1e7, pathStepKm = 20.0)

        fun of(type: ThreatType, kind: TargetKind): TrackKinematics? = when {
            type == ThreatType.Drones && kind == TargetKind.JetDrone -> JetDrones
            type == ThreatType.Drones && kind == TargetKind.DecoyDrone -> DecoyDrones
            else -> of(type)
        }

        fun of(type: ThreatType): TrackKinematics? = when (type) {
            ThreatType.Drones -> Drones
            ThreatType.CruiseMissiles, ThreatType.UnspecifiedMissiles -> CruiseMissiles
            else -> null
        }

        fun of(track: DroneTrack): TrackKinematics = when {
            track.type == ThreatType.Drones -> of(track.type, track.kind ?: TargetClassifier.classify(track).kind)!!
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
    fun isVisible(track: DroneTrack, t: Long) = isActive(track, t) || t - track.lastSeen <= TrackKinematics.of(track).maxExtrapolationMs

    /** [DroneTrack.active], rechecked at [t]: a track the server called active goes stale between polls too. */
    fun isActive(track: DroneTrack, t: Long) = track.active && t - track.lastSeen <= TrackKinematics.of(track).staleAfterMs

    /**
     * Linear interpolation between the two samples surrounding [t]. Past the newest sample an active
     * track keeps moving along its [trend] at its (capped) speed, for at most the type's
     * [TrackKinematics.maxExtrapolationMs], so targets keep moving between reports.
     */
    fun positionAt(track: DroneTrack, t: Long): InterpolatedPosition? {
        val kinematics = TrackKinematics.of(track)
        track.estimate?.let { return estimated(track, it, t, kinematics) }
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

    /**
     * From the server's [TrackEstimate]: along the smoothed [DroneTrack.path] before it, along its course after it
     * (for at most the type's [TrackKinematics.maxExtrapolationMs]). The server already applied the target's
     * limits, so nothing is refitted here.
     */
    private fun estimated(track: DroneTrack, e: TrackEstimate, t: Long, kinematics: TrackKinematics): InterpolatedPosition {
        fun at(point: GeoPoint, heading: Double?, speed: Double?, mode: PositionMode) =
            InterpolatedPosition(track.id, point.lat, point.lon, heading, speed, mode, track.level)
        val path = track.path
        if (t < e.t && path.size >= 2) {
            if (t <= path.first().t) return at(path.first().point, e.headingDeg, null, PositionMode.Held)
            val i = path.indexOfLast { it.t <= t }.coerceAtMost(path.lastIndex - 1)
            val a = path[i]
            val b = path[i + 1]
            val f = ((t - a.t).toDouble() / (b.t - a.t).coerceAtLeast(1)).coerceIn(0.0, 1.0)
            val point = GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
            val speed = GeoMath.distanceKm(a.point, b.point) / ((b.t - a.t).coerceAtLeast(1) / 3_600_000.0)
            return at(point, GeoMath.bearingDeg(a.point, b.point), min(speed, kinematics.maxSpeedKmh), PositionMode.Interpolated)
        }
        val heading = e.headingDeg
        val speed = e.speedKmh
        if (!track.active || heading == null || speed == null || speed < MIN_MOVING_KMH || t <= e.t) {
            return at(e.point, heading, if (track.active) speed else null, PositionMode.Held)
        }
        val elapsedMs = min(t - e.t, kinematics.maxExtrapolationMs)
        return at(GeoMath.destination(e.point, heading, speed * elapsedMs / 3_600_000.0), heading, speed, PositionMode.Extrapolated)
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
 * Alerts only say which area is under threat, so movement is inferred. Every track runs a [TrackFilter]; a new
 * point report (city, hromada, raion) may continue a track of the same type and kind (a Gerbera decoy group never
 * continues a Shahed track) that is still open or ended within the type's re-acquire window, when it lies within
 * the type's hop distance, is reachable at its top speed (with slack for coarse alert timing) and falls inside
 * the gate around the filter's predicted position. Of those, the cheapest pairing wins (distance in units of the
 * combined error, plus a penalty for turning), assigned over all of an update's reports at once; otherwise the
 * report starts a track. A track takes at most one report per update, and reports appearing together always start
 * separate tracks.
 *
 * A report that only names an oblast (or a place that cannot be located inside it) never becomes a point of its
 * own: it keeps alive the track expected in or near that oblast, moving it to the oblast border when the filter
 * had it outside; with no such track it waits, for the re-acquire window, for a track to start in or next to
 * the oblast, which then counts as seen since the oblast report.
 *
 * A track is active while one of its alerts is open and its newest report (the threat's `started_at`, not
 * the poll that still lists it) is within the kind's [TrackKinematics.staleAfterMs]; a stale track is kept
 * while its alerts stay open, so they don't restart it, but is not continued by new alerts.
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
        var firstSeen: Long,
        /** Newest report time; see [DroneTrack.lastSeen]. */
        var lastSeen: Long,
        val markerIds: MutableSet<String>,
        val samples: ArrayDeque<TrackSample>,
    ) {
        val kinematics = TrackKinematics.of(type, kind) ?: TrackKinematics.Drones
        val filter = TrackFilter(kinematics, samples.first().point).apply { samples.forEach { fuse(it) } }
        /** Distance flown along the reports, for the range limit. */
        var flownKm = 0.0

        fun isActive(nowMs: Long) = markerIds.isNotEmpty() && nowMs - lastSeen <= kinematics.staleAfterMs
    }

    /** A new report, located or not. */
    private class Report(val marker: ThreatMarker, val kind: TargetKind, val t: Long) {
        val sigmaKm = marker.errorKm ?: (marker.precision ?: SamplePrecision.Raion).sigmaKm
    }

    private val tracks = LinkedHashMap<String, MutableTrack>()
    private val markerToTrack = HashMap<String, String>()
    /** Oblast-only reports no track explained yet, by marker id. */
    private val pendingRegions = LinkedHashMap<String, Report>()
    /** Oblast-only reports already handled, so a still-open one is not handled again. */
    private val seenRegionMarkers = HashSet<String>()
    private var openMarkerIds: Set<String> = emptySet()
    private var sequence = 0L
    private var lastUpdateMs = 0L

    fun update(markers: List<ThreatMarker>, nowMs: Long) {
        lastUpdateMs = nowMs
        val tracked = markers.filter { TrackKinematics.of(it.type) != null }
        val currentIds = tracked.mapTo(HashSet()) { it.id }

        // Threats that cleared detach from their tracks first, so the track can "move" to a neighbour.
        tracks.values.forEach { it.markerIds.retainAll(currentIds) }
        markerToTrack.keys.retainAll(currentIds)
        seenRegionMarkers.retainAll(currentIds)
        openMarkerIds = currentIds
        // A pending report outlives its alert: the target may have flown on into a neighbouring oblast.
        pendingRegions.values.removeAll { nowMs - it.t > reacquireMs(it.marker.type) }

        val fresh = tracked.filter { marker ->
            val existing = markerToTrack[marker.id]?.let(tracks::get) ?: return@filter true
            // Still listed says nothing new; only a newer report time from alerts.in.ua does.
            marker.startedAt?.let { existing.lastSeen = maxOf(existing.lastSeen, observedAt(marker, nowMs)) }
            existing.markerIds += marker.id
            false
        }
        val (regional, reports) = fresh
            .map { Report(it, TargetClassifier.classify(it).kind, observedAt(it, nowMs)) }
            .partition { isRegional(it.marker) }

        // The same target reported twice (by two sources, or for a hromada and the raion around it) is one report:
        // the most precise one is kept, the others only attach their alerts to its track.
        val twins = HashMap<Report, Report>()
        val located = mutableListOf<Report>()
        reports.sortedBy { it.sigmaKm }.forEach { report ->
            val twin = located.firstOrNull { kept ->
                kept.marker.type == report.marker.type && kept.kind == report.kind && abs(kept.t - report.t) <= MERGE_WINDOW_MS &&
                    GeoMath.distanceKm(kept.marker.point, report.marker.point) <= maxOf(kept.sigmaKm, report.sigmaKm)
            }
            if (twin != null) twins[report] = twin else located += report
        }

        // Tracks that took a report in this update, and tracks born in it: simultaneous reports are separate groups.
        val absorbed = HashSet<String>()
        val assigned = HashMap<Report, MutableTrack>()
        located
            .flatMap { report -> candidates(report, nowMs).map { (track, cost) -> Triple(report, track, cost) } }
            .sortedBy { it.third }
            .forEach { (report, track, _) ->
                if (report !in assigned && track.id !in absorbed) {
                    assigned[report] = track
                    absorbed += track.id
                }
            }
        val trackOf = HashMap<Report, MutableTrack>()
        located.sortedBy { it.t }.forEach { report ->
            val track = assigned[report] ?: reacquireInland(report, nowMs, absorbed)
            when {
                track == null -> startTrack(report, nowMs).also { absorbed += it.id; trackOf[report] = it }
                // Re-alerted in the same area: the group is still here, which says nothing about movement.
                GeoMath.distanceKm(track.samples.last().point, report.marker.point) < SAME_PLACE_KM -> track.attach(report.marker, report.t)
                else -> {
                    track.add(sample(report.marker, report.t, track), report.sigmaKm)
                    track.attach(report.marker, report.t)
                }
            }
            track?.let { trackOf[report] = it }
        }
        twins.forEach { (report, twin) -> trackOf[twin]?.attach(report.marker, report.t) }
        regional.sortedBy { it.t }.forEach { explainRegional(it, nowMs, absorbed) }

        // Stale tracks whose alerts are still open stay, so those alerts don't start new tracks every update.
        tracks.values.removeAll { it.markerIds.isEmpty() && nowMs - it.lastSeen > retentionMs }
    }

    /** Oblast-wide threats, and places that could only be put somewhere in their oblast. */
    private fun isRegional(marker: ThreatMarker) = marker.isRegionWide || marker.precision == SamplePrecision.Oblast

    private fun hopKm(type: ThreatType) = if (type == ThreatType.Drones) maxHopKm else TrackKinematics.of(type)!!.maxHopKm

    private fun reacquireMs(type: ThreatType) = if (type == ThreatType.Drones) reacquireWindowMs else TrackKinematics.of(type)!!.reacquireWindowMs

    /** Missile alerts are raised far ahead of the missile, so their timing says little about its speed. */
    private fun maxSpeedKmh(type: ThreatType, kind: TargetKind) =
        if (type != ThreatType.Drones) Double.POSITIVE_INFINITY else TrackKinematics.of(type, kind)!!.maxSpeedKmh

    private fun openTracks(report: Report, nowMs: Long) = tracks.values.asSequence()
        .filter { it.type == report.marker.type && it.kind == report.kind }
        .filter { it.isActive(nowMs) || nowMs - it.lastSeen <= reacquireMs(it.type) }

    /** Tracks [report] may continue, with the cost of pairing them; see the class description. */
    private fun candidates(report: Report, nowMs: Long): List<Pair<MutableTrack, Double>> {
        val point = report.marker.point
        val maxSpeed = maxSpeedKmh(report.marker.type, report.kind)
        return openTracks(report, nowMs).mapNotNull { track ->
            val last = track.samples.last()
            val km = GeoMath.distanceKm(last.point, point)
            if (km > hopKm(track.type) || km > reachableKm(maxSpeed, report.t - last.t)) return@mapNotNull null
            if (track.flownKm + km > track.kinematics.rangeKm) return@mapNotNull null
            val (predicted, sigma) = track.filter.predictedAt(report.t)
            val combined = sqrt(sigma * sigma + report.sigmaKm * report.sigmaKm)
            // Gated where the filter would fuse it: an alert's area moved back along the course by its warning lead.
            val miss = GeoMath.distanceKm(predicted, track.leadCorrected(point, report.marker.level, report.marker.precision))
            // An alert-built track runs up to a warning lead ahead of its target, so a report may lie that far behind.
            val leadKm = if (last.level != null) (track.filter.speedKmh ?: 0.0) * track.kinematics.warningLeadMs / 3_600_000.0 else 0.0
            // Missiles are gated by hop distance only, for the same reason they have no speed limit here.
            if (maxSpeed.isFinite() && miss > GATE_SIGMAS * combined + leadKm) return@mapNotNull null
            val heading = track.filter.headingDeg
            val turn = if (heading != null && km >= SAME_PLACE_KM) abs(TrackFilter.angleDiff(heading, GeoMath.bearingDeg(last.point, point))) else 0.0
            if (track.reverses(point, report.sigmaKm)) return@mapNotNull null
            track to miss / combined + turn / TURN_COST_DEG
        }.toList()
    }

    /**
     * Drones are launched from Russia, Belarus, occupied land and the sea, so a group first reported in an inland
     * oblast is far more likely one that was lost on its way there than a new one. Before such a report starts a
     * track, it takes the nearest track lost within twice the re-acquire window that could have reached it.
     */
    private fun reacquireInland(report: Report, nowMs: Long, absorbed: MutableSet<String>): MutableTrack? {
        if (report.marker.type != ThreatType.Drones || report.marker.oblastUid !in Regions.INLAND_OBLAST_UIDS) return null
        val point = report.marker.point
        return tracks.values
            .filter { it.type == report.marker.type && it.kind == report.kind && it.id !in absorbed }
            .filter { nowMs - it.lastSeen <= 2 * reacquireMs(it.type) }
            .map { it to GeoMath.distanceKm(it.samples.last().point, point) }
            .filter { (track, km) ->
                !track.reverses(point, report.sigmaKm) &&
                    km <= hopKm(track.type) * INLAND_HOP_FACTOR &&
                    km <= reachableKm(track.kinematics.maxSpeedKmh, report.t - track.samples.last().t) &&
                    track.flownKm + km <= track.kinematics.rangeKm
            }
            .minByOrNull { it.second }
            ?.first
            ?.also { absorbed += it.id }
    }

    /** Oblast-only [report]: keeps the track expected there alive, or waits for one to start there. */
    private fun explainRegional(report: Report, nowMs: Long, absorbed: MutableSet<String>) {
        val marker = report.marker
        seenRegionMarkers += marker.id
        val uid = marker.oblastUid ?: return
        val maxSpeed = maxSpeedKmh(marker.type, report.kind)
        val best = openTracks(report, nowMs)
            .mapNotNull { track ->
                val reach = reachableKm(maxSpeed, report.t - track.samples.last().t)
                val fromLast = Regions.distanceToRegionKm(uid, track.samples.last().point) ?: return@mapNotNull null
                if (fromLast > reach) return@mapNotNull null
                val predicted = track.filter.predictedAt(report.t).first
                Triple(track, predicted, Regions.distanceToRegionKm(uid, predicted) ?: return@mapNotNull null)
            }
            .minByOrNull { it.third }
        if (best == null) {
            pendingRegions[marker.id] = report
            return
        }
        val (track, predicted, outsideKm) = best
        val entry = Regions.nearestPointInRegion(uid, predicted) ?: predicted
        if (outsideKm > 0 && track.id !in absorbed && !track.reverses(entry)) {
            // The filter had it short of (or past) the oblast: the target is at least at its border.
            track.add(
                TrackSample(clampTime(report.t, track), entry.lat, entry.lon, marker.oblastName, uid, marker.message, SamplePrecision.Oblast, marker.level),
                SamplePrecision.Oblast.sigmaKm / 2,
            )
            absorbed += track.id
        }
        track.attach(marker, report.t)
    }

    /** An oblast-only report waiting for [report]'s track: same type and kind, earlier, in or next to the oblast. */
    private fun pendingFor(report: Report): Report? = pendingRegions.values
        .filter { it.marker.type == report.marker.type && it.kind == report.kind }
        .filter { it.t <= report.t && report.t - it.t <= reacquireMs(it.marker.type) }
        .firstOrNull { pending ->
            val uid = pending.marker.oblastUid ?: return@firstOrNull false
            uid == report.marker.oblastUid || (Regions.distanceToRegionKm(uid, report.marker.point) ?: Double.MAX_VALUE) <= NEIGHBOUR_KM
        }

    /**
     * A Shahed does not double back between two reports, so a report behind it is another group: a course from
     * the last report to [point] reversing the last reported leg, or the filter's course, by more than
     * [MAX_REVERSAL_DEG]. Decoys loiter and may. A report far more precise than the track's estimate (a sighting
     * behind an alert-built track, whose alerts run ahead of the target) corrects the track rather than reverses it.
     */
    private fun MutableTrack.reverses(point: GeoPoint, reportSigmaKm: Double = Double.MAX_VALUE): Boolean {
        if (kinematics.loiters || reportSigmaKm <= filter.sigmaKm * CORRECTION_RATIO) return false
        val last = samples.last()
        if (GeoMath.distanceKm(last.point, point) < SAME_PLACE_KM) return false
        val next = GeoMath.bearingDeg(last.point, point)
        val leg = samples.getOrNull(samples.lastIndex - 1)
            ?.takeIf { GeoMath.distanceKm(it.point, last.point) >= SAME_PLACE_KM }
            ?.let { GeoMath.bearingDeg(it.point, last.point) }
        return listOfNotNull(leg, filter.headingDeg).any { abs(TrackFilter.angleDiff(it, next)) > MAX_REVERSAL_DEG }
    }

    /** Where the target was when an alert of [level] was raised for [point]: see [TrackFilter.update]. Fixes are where they are. */
    private fun MutableTrack.leadCorrected(point: GeoPoint, level: ThreatLevel, precision: SamplePrecision?): GeoPoint {
        val heading = filter.headingDeg ?: return point
        val speed = filter.speedKmh ?: return point
        if (precision == SamplePrecision.Exact) return point
        return GeoMath.destination(point, (heading + 180.0) % 360.0, speed * kinematics.leadMs(level) / 3_600_000.0)
    }

    private fun MutableTrack.add(sample: TrackSample, sigmaKm: Double) {
        flownKm += GeoMath.distanceKm(samples.last().point, sample.point)
        samples.addLast(sample)
        while (samples.size > maxSamples) samples.removeFirst()
        oblastUid = sample.oblastUid ?: oblastUid
        filter.fuse(sample, sigmaKm)
    }

    /** [reportedAt] is when alerts.in.ua reported [marker]. */
    private fun MutableTrack.attach(marker: ThreatMarker, reportedAt: Long) {
        level = marker.level
        lastSeen = maxOf(lastSeen, reportedAt)
        markerIds += marker.id
        markerToTrack[marker.id] = id
    }

    private fun startTrack(report: Report, nowMs: Long): MutableTrack {
        val marker = report.marker
        val id = "$idPrefix${++sequence}"
        val pending = pendingFor(report)
        return MutableTrack(
            id = id,
            type = marker.type,
            kind = report.kind,
            level = marker.level,
            oblastUid = marker.oblastUid,
            firstSeen = pending?.t?.coerceAtMost(nowMs) ?: nowMs,
            lastSeen = report.t,
            markerIds = mutableSetOf(),
            samples = ArrayDeque(listOf(sample(marker, report.t, null))),
        ).also {
            tracks[id] = it
            it.attach(marker, report.t)
            if (pending != null) {
                pendingRegions.remove(pending.marker.id)
                if (pending.marker.id in openMarkerIds) it.attach(pending.marker, pending.t)
            }
        }
    }

    /** Reports can come out of order; a track's samples stay in time order and no later than now. */
    private fun clampTime(t: Long, track: MutableTrack): Long {
        val last = track.samples.last()
        return t.coerceIn(last.t + 1, maxOf(lastUpdateMs, last.t + 1))
    }

    private fun sample(marker: ThreatMarker, t: Long, track: MutableTrack?) = TrackSample(
        t = if (track == null) t else clampTime(t, track),
        lat = marker.point.lat,
        lon = marker.point.lon,
        title = marker.title,
        oblastUid = marker.oblastUid,
        message = marker.message,
        precision = marker.precision,
        level = marker.level,
        errorKm = marker.errorKm,
    )

    private fun observedAt(marker: ThreatMarker, nowMs: Long) = (marker.startedAt?.toEpochMilliseconds() ?: nowMs).coerceAtMost(nowMs)

    private companion object {
        const val SAME_PLACE_KM = 1.0
        /** Alerts can come early or late; a hop is plausible up to this much over top speed, plus [TIMING_SLACK_KM]. */
        const val SPEED_TOLERANCE = 1.5
        const val TIMING_SLACK_KM = 25.0
        /** A report further from the predicted position than this many combined sigmas is another target. */
        const val GATE_SIGMAS = 3.0
        /** A turn this sharp costs as much as missing the prediction by one sigma. */
        const val TURN_COST_DEG = 90.0
        /** A track starting this close to an oblast is taken as the target an oblast-only report there announced. */
        const val NEIGHBOUR_KM = 50.0
        /** Reports of one target from two sources (or two nested areas) come within this of each other. */
        const val MERGE_WINDOW_MS = 2 * 60_000L
        /** A course change sharper than this between two reports is a reversal. */
        const val MAX_REVERSAL_DEG = 150.0
        /** A report this much more precise than the track's estimate is a correction, never a reversal. */
        const val CORRECTION_RATIO = 0.25
        /** Re-acquiring a lost group inland may bridge a longer gap than continuing a track. */
        const val INLAND_HOP_FACTOR = 1.5

        fun reachableKm(maxSpeedKmh: Double, elapsedMs: Long) =
            maxSpeedKmh * SPEED_TOLERANCE * elapsedMs.coerceAtLeast(0) / 3_600_000.0 + TIMING_SLACK_KM
    }

    /** Tracks as of [nowMs], by default the latest [update]. */
    fun snapshot(nowMs: Long = lastUpdateMs): List<DroneTrack> = tracks.values.map {
        DroneTrack(
            id = it.id,
            type = it.type,
            level = it.level,
            oblastUid = it.oblastUid,
            active = it.isActive(nowMs),
            firstSeen = it.firstSeen,
            lastSeen = it.lastSeen,
            markerIds = it.markerIds.toList(),
            samples = it.samples.toList(),
            kind = it.kind,
        )
    }
}
