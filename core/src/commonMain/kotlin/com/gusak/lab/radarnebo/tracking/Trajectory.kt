package com.gusak.lab.radarnebo.tracking

import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.geo.Regions
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How precisely a report places its target: alerts name an area, never a point, so a report's position is the
 * area's centre with an error of about [sigmaKm].
 */
@Serializable
enum class SamplePrecision(val sigmaKm: Double) {
    /** A position fix (surveillance, the simulation). */
    Exact(1.0),
    City(7.0),
    Hromada(12.0),
    Raion(25.0),
    /** Somewhere in the oblast; a point is only an entry estimate. */
    Oblast(80.0),
}

/** The server's estimate of where a target was at [t], and how it was moving. */
@Serializable
data class TrackEstimate(
    val t: Long,
    val lat: Double,
    val lon: Double,
    /** Degrees clockwise from north; null until the track has moved. */
    val headingDeg: Double?,
    val speedKmh: Double?,
    /** One-sigma position error. */
    val sigmaKm: Double,
) {
    val point get() = GeoPoint(lat, lon)
}

/** A point of a smoothed past trajectory. */
@Serializable
data class PathPoint(val t: Long, val lat: Double, val lon: Double) {
    val point get() = GeoPoint(lat, lon)
}

/** Where the target is expected at [t] if it keeps its course, give or take [sigmaKm]. */
@Serializable
data class ForecastPoint(val t: Long, val lat: Double, val lon: Double, val sigmaKm: Double) {
    val point get() = GeoPoint(lat, lon)
}

/** A settlement on the forecast course and when the target would reach it. */
@Serializable
data class TrackArrival(val place: String, val lat: Double, val lon: Double, val distanceKm: Double, val at: Long)

/**
 * Constant-velocity Kalman filter in a local east/north plane (km, km/h) around [ref]. State `[x, y, vx, vy]`.
 * After every update the velocity is held to the target's speed band and turn rate ([TrackKinematics]), so the
 * estimate stays physically possible however coarse the reports are.
 */
class TrackFilter private constructor(
    private val kinematics: TrackKinematics,
    private val ref: GeoPoint,
    private val x: DoubleArray,
    private val p: DoubleArray,
    var t: Long,
    var updates: Int,
) {
    constructor(kinematics: TrackKinematics, ref: GeoPoint) : this(kinematics, ref, DoubleArray(4), DoubleArray(16), 0L, 0)

    fun copy() = TrackFilter(kinematics, ref, x.copyOf(), p.copyOf(), t, updates)

    private val lonKm = cos(ref.lat * PI / 180.0) * KM_PER_DEGREE

    val position get() = toGeo(x[0], x[1])
    val sigmaKm get() = sqrt(max(0.0, (p[0] + p[5]) / 2))
    /** Known once two reports have been fused. */
    val speedKmh get() = if (updates >= 2) sqrt(x[2] * x[2] + x[3] * x[3]) else null
    val headingDeg get() = if (updates >= 2) heading(x[2], x[3]) else null
    internal val state get() = x.copyOf()
    internal val covariance get() = p.copyOf()

    /** Moves the state to [toT]; earlier times leave it where it is. */
    fun predict(toT: Long) {
        if (updates == 0) {
            t = toT
            return
        }
        val dt = (toT - t).coerceAtLeast(0) / HOUR_MS
        t = max(t, toT)
        if (dt == 0.0) return
        val f = transition(dt)
        x.indices.map { i -> (0..3).sumOf { f[i * 4 + it] * x[it] } }.forEachIndexed { i, v -> x[i] = v }
        val fp = mul(f, p)
        val fpft = mul(fp, transpose(f))
        val q = kinematics.processNoise
        val dt2 = dt * dt / 2
        val dt3 = dt * dt * dt / 3
        for (i in 0..1) {
            fpft[i * 4 + i] += q * dt3
            fpft[i * 4 + i + 2] += q * dt2
            fpft[(i + 2) * 4 + i] += q * dt2
            fpft[(i + 2) * 4 + i + 2] += q * dt
        }
        fpft.copyInto(p)
    }

    /** Where the target is expected at [at], with the one-sigma error; the filter itself is unchanged. */
    fun predictedAt(at: Long): Pair<GeoPoint, Double> = copy().apply { predict(at) }.let { it.position to it.sigmaKm }

    /**
     * Fuses a report at [at] of the target being at [point] give or take [sigmaKm]. Alerts are raised for areas
     * the target is about to reach, so an alert's area is moved back along the course by [leadMs] of flight
     * (see [TrackKinematics.leadMs]) once the course is known.
     */
    fun update(at: Long, point: GeoPoint, sigmaKm: Double, leadMs: Long = 0L) {
        val (zx0, zy0) = toLocal(point)
        if (updates == 0) {
            x[0] = zx0; x[1] = zy0; x[2] = 0.0; x[3] = 0.0
            p.fill(0.0)
            p[0] = sigmaKm * sigmaKm
            p[5] = sigmaKm * sigmaKm
            val v = kinematics.maxSpeedKmh / 2
            p[10] = v * v
            p[15] = v * v
            t = at
            updates = 1
            return
        }
        val previousHeading = headingDeg
        val dtH = (at - t).coerceAtLeast(0) / HOUR_MS
        predict(at)
        var zx = zx0
        var zy = zy0
        val speed = speedKmh
        if (leadMs > 0 && speed != null && speed > 0) {
            val lead = speed * leadMs / HOUR_MS
            zx -= x[2] / speed * lead
            zy -= x[3] / speed * lead
        }
        val r = sigmaKm * sigmaKm
        val s00 = p[0] + r
        val s01 = p[1]
        val s10 = p[4]
        val s11 = p[5] + r
        val det = s00 * s11 - s01 * s10
        if (det <= 0.0) return
        val i00 = s11 / det
        val i01 = -s01 / det
        val i10 = -s10 / det
        val i11 = s00 / det
        // K = P Hᵀ S⁻¹: the first two columns of P times S⁻¹.
        val k = DoubleArray(8)
        for (row in 0..3) {
            val a = p[row * 4]
            val b = p[row * 4 + 1]
            k[row * 2] = a * i00 + b * i10
            k[row * 2 + 1] = a * i01 + b * i11
        }
        val ix = zx - x[0]
        val iy = zy - x[1]
        for (row in 0..3) x[row] += k[row * 2] * ix + k[row * 2 + 1] * iy
        val newP = DoubleArray(16)
        for (row in 0..3) for (col in 0..3) {
            newP[row * 4 + col] = p[row * 4 + col] - k[row * 2] * p[col] - k[row * 2 + 1] * p[4 + col]
        }
        newP.copyInto(p)
        updates++
        constrain(previousHeading, dtH)
    }

    /** Fuses [sample]: its position (with its lead time) and, when its text names where it is heading, that course. */
    fun fuse(sample: TrackSample, sigmaKm: Double = sample.sigmaKm) {
        update(sample.t, sample.point, sigmaKm, kinematics.leadMs(sample.level))
        HeadingHints.target(sample.message)?.let { steer(it) }
    }

    /**
     * Turns the course [weight] of the way towards [target], keeping the speed: a weak heading measurement, since
     * "курсом на Київ" says where the group is heading, not that it flies straight there.
     */
    fun steer(target: GeoPoint, weight: Double = HINT_WEIGHT) {
        val heading = headingDeg ?: return
        val speed = speedKmh ?: return
        val towards = GeoMath.bearingDeg(position, target)
        val h = (heading + weight * angleDiff(heading, towards) + 360.0) % 360.0 * PI / 180.0
        x[2] = speed * sin(h)
        x[3] = speed * cos(h)
    }

    /** Holds the velocity to the speed band and, once a heading is known, to the kind's turn rate. */
    private fun constrain(previousHeading: Double?, dtH: Double) {
        val speed = sqrt(x[2] * x[2] + x[3] * x[3])
        var heading = heading(x[2], x[3])
        if (previousHeading != null && speed > 0) {
            val maxTurn = min(180.0, kinematics.maxTurnDegPerHour * dtH)
            val turn = angleDiff(previousHeading, heading)
            if (abs(turn) > maxTurn) heading = (previousHeading + (if (turn > 0) maxTurn else -maxTurn) + 360.0) % 360.0
        }
        val clamped = speed.coerceIn(kinematics.minSpeedKmh, kinematics.maxSpeedKmh)
        if (speed == 0.0 && previousHeading == null) return
        val h = heading * PI / 180.0
        x[2] = clamped * sin(h)
        x[3] = clamped * cos(h)
    }

    fun toLocal(point: GeoPoint) = (point.lon - ref.lon) * lonKm to (point.lat - ref.lat) * KM_PER_DEGREE
    fun toGeo(px: Double, py: Double) = GeoPoint(ref.lat + py / KM_PER_DEGREE, ref.lon + px / lonKm)

    internal companion object {
        const val HOUR_MS = 3_600_000.0
        private const val KM_PER_DEGREE = 111.2
        /** How far one "курсом на" turns the course towards the named place. */
        const val HINT_WEIGHT = 0.3

        fun heading(vx: Double, vy: Double) = (atan2(vx, vy) * 180.0 / PI + 360.0) % 360.0

        /** Signed turn from [from] to [to], in -180..180 degrees. */
        fun angleDiff(from: Double, to: Double) = ((to - from + 540.0) % 360.0) - 180.0

        fun transition(dt: Double) = DoubleArray(16).also {
            for (i in 0..3) it[i * 4 + i] = 1.0
            it[2] = dt
            it[7] = dt
        }

        fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(16).also { out ->
            for (r in 0..3) for (c in 0..3) out[r * 4 + c] = (0..3).sumOf { a[r * 4 + it] * b[it * 4 + c] }
        }

        fun transpose(a: DoubleArray) = DoubleArray(16).also { out -> for (r in 0..3) for (c in 0..3) out[c * 4 + r] = a[r * 4 + c] }

        /** Gauss-Jordan inverse of a 4×4 matrix; null when singular. */
        fun invert(a: DoubleArray): DoubleArray? {
            val m = a.copyOf()
            val inv = DoubleArray(16).also { for (i in 0..3) it[i * 4 + i] = 1.0 }
            for (col in 0..3) {
                val pivot = (col..3).maxBy { abs(m[it * 4 + col]) }
                if (abs(m[pivot * 4 + col]) < 1e-12) return null
                for (c in 0..3) {
                    m[col * 4 + c] = m[pivot * 4 + c].also { m[pivot * 4 + c] = m[col * 4 + c] }
                    inv[col * 4 + c] = inv[pivot * 4 + c].also { inv[pivot * 4 + c] = inv[col * 4 + c] }
                }
                val d = m[col * 4 + col]
                for (c in 0..3) {
                    m[col * 4 + c] /= d
                    inv[col * 4 + c] /= d
                }
                for (r in 0..3) if (r != col) {
                    val f = m[r * 4 + col]
                    for (c in 0..3) {
                        m[r * 4 + c] -= f * m[col * 4 + c]
                        inv[r * 4 + c] -= f * inv[col * 4 + c]
                    }
                }
            }
            return inv
        }
    }
}

/**
 * Turns a track's reports into what clients draw: the current [TrackEstimate], the smoothed past [PathPoint]s,
 * the [ForecastPoint]s ahead and the settlements on the way. Runs on the server; clients only draw the result.
 *
 * The reports go through a [TrackFilter] forward, then a Rauch-Tung-Striebel pass backwards, so every past
 * position also benefits from the reports after it. Between smoothed states the path is a cubic Hermite curve
 * whose tangents are the smoothed velocities, capped so the curve never loops or overshoots.
 */
object TrajectoryEstimator {
    /** Most path points served per track. */
    private const val MAX_PATH_POINTS = 100
    private const val FORECAST_STEP_MS = 5 * 60_000L

    fun enrich(track: DroneTrack, nowMs: Long, places: List<Place> = TrackPredictor.cityPlaces): DroneTrack {
        val samples = track.samples
        if (samples.isEmpty()) return track
        val kind = TargetClassifier.classify(track.copy(kind = null)).kind
        val kinematics = TrackKinematics.of(track.copy(kind = kind))
        val steps = filter(samples, kinematics)
        val last = steps.last().filter
        val estimate = TrackEstimate(
            t = last.t,
            lat = last.position.lat,
            lon = last.position.lon,
            headingDeg = last.headingDeg,
            speedKmh = last.speedKmh,
            sigmaKm = last.sigmaKm,
        )
        // Speed decides between a jet and a piston Shahed when the text does not say.
        val refinedKind = TargetClassifier.classify(track.copy(kind = null), estimate.speedKmh).kind
        val path = path(steps, kinematics, last)
        val moving = track.active && estimate.speedKmh != null && estimate.headingDeg != null
        val forecast = if (moving) forecast(last, kinematics, nowMs) else emptyList()
        val arrivals = if (moving) {
            val elapsed = min((nowMs - estimate.t).coerceAtLeast(0), kinematics.maxExtrapolationMs)
            val from = GeoMath.destination(estimate.point, estimate.headingDeg!!, estimate.speedKmh!! * elapsed / TrackFilter.HOUR_MS)
            TrackPredictor.routeFrom(track.id, from, estimate.headingDeg, estimate.speedKmh, places)
                .map { TrackArrival(it.place.name, it.place.point.lat, it.place.point.lon, it.distanceKm, nowMs + it.etaMinutes * 60_000L) }
        } else {
            emptyList()
        }
        return track.copy(kind = refinedKind, estimate = estimate, path = path, forecast = forecast, arrivals = arrivals)
    }

    /** A filter step: the state predicted to a report's time, and after fusing it. */
    private class Step(val predictedX: DoubleArray, val predictedP: DoubleArray, val filter: TrackFilter)

    private fun filter(samples: List<TrackSample>, kinematics: TrackKinematics): List<Step> {
        val filter = TrackFilter(kinematics, samples.first().point)
        return samples.map { sample ->
            val predicted = filter.copy().apply { predict(sample.t) }
            filter.fuse(sample)
            Step(predicted.state, predicted.covariance, filter.copy())
        }
    }

    /** Smoothed states `[x, y, vx, vy]` at every report, oldest first. */
    private fun smooth(steps: List<Step>, kinematics: TrackKinematics): List<DoubleArray> {
        val smoothed = MutableList(steps.size) { steps[it].filter.state }
        for (k in steps.lastIndex - 1 downTo 0) {
            val dt = (steps[k + 1].filter.t - steps[k].filter.t) / TrackFilter.HOUR_MS
            val f = TrackFilter.transition(dt)
            val inverse = TrackFilter.invert(steps[k + 1].predictedP) ?: continue
            val c = TrackFilter.mul(TrackFilter.mul(steps[k].filter.covariance, TrackFilter.transpose(f)), inverse)
            val diff = DoubleArray(4) { smoothed[k + 1][it] - steps[k + 1].predictedX[it] }
            val xs = steps[k].filter.state
            for (r in 0..3) xs[r] += (0..3).sumOf { c[r * 4 + it] * diff[it] }
            holdSpeed(xs, kinematics)
            smoothed[k] = xs
        }
        return smoothed
    }

    private fun holdSpeed(state: DoubleArray, kinematics: TrackKinematics) {
        val speed = sqrt(state[2] * state[2] + state[3] * state[3])
        if (speed == 0.0) return
        val clamped = speed.coerceIn(kinematics.minSpeedKmh, kinematics.maxSpeedKmh)
        state[2] *= clamped / speed
        state[3] *= clamped / speed
    }

    private fun path(steps: List<Step>, kinematics: TrackKinematics, frame: TrackFilter): List<PathPoint> {
        val states = smooth(steps, kinematics)
        val times = steps.map { it.filter.t }
        fun point(t: Long, px: Double, py: Double) = frame.toGeo(px, py).let { PathPoint(t, it.lat, it.lon) }
        if (states.size == 1) return listOf(point(times[0], states[0][0], states[0][1]))
        val chords = states.zipWithNext { a, b -> sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1])) }
        val stepKm = max(kinematics.pathStepKm, chords.sum() / MAX_PATH_POINTS)
        val out = mutableListOf(point(times[0], states[0][0], states[0][1]))
        for (i in chords.indices) {
            val a = states[i]
            val b = states[i + 1]
            val chord = chords[i]
            val dt = (times[i + 1] - times[i]) / TrackFilter.HOUR_MS
            val pieces = max(1, (chord / stepKm).toInt())
            // Tangents no longer than the chord keep the curve from looping between close reports.
            fun tangent(s: DoubleArray, c: Int): Double {
                val length = sqrt(s[2] * s[2] + s[3] * s[3]) * dt
                return if (length <= chord || length == 0.0) s[c] * dt else s[c] * dt * chord / length
            }
            val t0x = tangent(a, 2); val t0y = tangent(a, 3)
            val t1x = tangent(b, 2); val t1y = tangent(b, 3)
            for (j in 1..pieces) {
                val s = j.toDouble() / pieces
                val h00 = 2 * s * s * s - 3 * s * s + 1
                val h10 = s * s * s - 2 * s * s + s
                val h01 = -2 * s * s * s + 3 * s * s
                val h11 = s * s * s - s * s
                out += point(
                    times[i] + ((times[i + 1] - times[i]) * s).toLong(),
                    h00 * a[0] + h10 * t0x + h01 * b[0] + h11 * t1x,
                    h00 * a[1] + h10 * t0y + h01 * b[1] + h11 * t1y,
                )
            }
        }
        return out
    }

    /** The course ahead from [nowMs] over the kind's horizon, with the error growing with the velocity's. */
    private fun forecast(filter: TrackFilter, kinematics: TrackKinematics, nowMs: Long): List<ForecastPoint> {
        val p = filter.covariance
        val sigmaV = sqrt(max(0.0, (p[10] + p[15]) / 2))
        val start = max(nowMs, filter.t)
        val end = start + kinematics.forecastHorizonMs
        val heading = filter.headingDeg!!
        val speed = filter.speedKmh!!
        return generateSequence(start) { it + FORECAST_STEP_MS }.takeWhile { it <= end }.map { t ->
            val dt = (t - filter.t) / TrackFilter.HOUR_MS
            val point = GeoMath.destination(filter.position, heading, speed * dt)
            ForecastPoint(t, point.lat, point.lon, sqrt(filter.sigmaKm * filter.sigmaKm + sigmaV * sigmaV * dt * dt))
        }.toList()
    }
}

/**
 * Where an alert says the target is heading: monitoring channels write "курсом на Полтаву", "у напрямку Житомира",
 * "курс на м. Біла Церква". Only places the map knows (cities) count.
 */
object HeadingHints {
    private val phrase = Regex("""(?:курсом|курс|у напрямку|в напрямку|напрямок)\s+(?:на\s+)?(?:м\.\s*)?([^,.;()!]{2,40})""", RegexOption.IGNORE_CASE)

    fun target(message: String?): GeoPoint? {
        val place = phrase.find(message ?: return null)?.groupValues?.get(1) ?: return null
        return Regions.cityForTitle(place)?.point
    }
}

/** The report's position error: its area's, else its precision's, or a raion's for reports from servers that did not say. */
val TrackSample.sigmaKm get() = errorKm ?: (precision ?: SamplePrecision.Raion).sigmaKm
