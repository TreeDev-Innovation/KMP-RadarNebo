package com.gusak.lab.radarnebo.server

import com.gusak.lab.radarnebo.data.Alert
import com.gusak.lab.radarnebo.data.DroneSimulator
import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.geo.City
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.TrackSample
import kotlin.random.Random
import kotlin.time.Instant

/**
 * Randomised raids for the proxy's simulation, on top of [DroneSimulator]'s scripted flights:
 *
 * - Guided bombs (KAB with UMPK kits) released by Su-34s over Russia or the occupied territories glide
 *   25-80 km into front-line and border towns: a salvo of three every 15 minutes, plus one single
 *   launch every minute, each at a random moment and target. Each target hromada is alerted from the
 *   first release until shortly after the last impact, and every bomb's flight is published as a track.
 * - Shahed groups cross the border or the coast at random points, one or two every 20 minutes, and fly
 *   from settlement to settlement towards a random city, alerting each one on the way like the scripted
 *   flights, so the tracker follows them like real alerts.
 *
 * Every time slot seeds its own random generator, so the raids are a pure function of wall-clock time:
 * restarts and the warm-up replay see the same raids.
 */
object DemoRaids {
    private const val SALVO_PERIOD_MS = 15 * 60_000L
    private const val SALVO_SIZE = 3
    private const val SINGLE_PERIOD_MS = 60_000L
    /** Target alerts stay up this long after the last impact. */
    private const val KAB_ALERT_TAIL_MS = 3 * 60_000L
    /** Tracks stay published this long after impact, so clients see where the bomb came down. */
    private const val KAB_TRACK_TAIL_MS = 60_000L
    /** Longer than any glide plus the alert tail. */
    private const val KAB_LOOKBACK_MS = 15 * 60_000L
    /** How far back a target's alert looks for the attack that started it. */
    private const val KAB_ALERT_HISTORY_MS = 60 * 60_000L

    private const val SHAHED_PERIOD_MS = 20 * 60_000L
    /** Longer than any Shahed route takes to fly. */
    private const val SHAHED_LOOKBACK_SLOTS = 15
    /** Under the tracker's 120 km drone hop, so consecutive alerts link into one track. */
    private const val MAX_HOP_KM = 100.0
    private const val MIN_HOP_KM = 30.0

    /**
     * A town under KAB attack. Bombs are released [rangeKm] away at a bearing in [bearingDeg] (degrees
     * clockwise from north, as seen from the town), which puts the release point over [launchArea].
     */
    internal class KabTarget(
        val city: City,
        val bearingDeg: IntRange,
        val rangeKm: IntRange,
        val launchArea: String,
        val launchAreaUk: String,
        val weight: Int,
    )

    private fun kabTarget(name: String, bearingDeg: IntRange, rangeKm: IntRange, launchArea: String, launchAreaUk: String, weight: Int = 1) =
        KabTarget(city(name), bearingDeg, rangeKm, launchArea, launchAreaUk, weight)

    private const val BELGOROD = "Belgorod Oblast, Russia"
    private const val BELGOROD_UK = "Бєлгородської області"

    private val kabTargets = listOf(
        kabTarget("Kharkiv", 0..45, 55..70, BELGOROD, BELGOROD_UK, weight = 4),
        kabTarget("Sumy", 40..90, 48..60, "Kursk Oblast, Russia", "Курської області", weight = 3),
        kabTarget("Okhtyrka", 50..70, 55..65, BELGOROD, BELGOROD_UK),
        kabTarget("Trostianets", 60..90, 45..55, BELGOROD, BELGOROD_UK),
        kabTarget("Zolochiv", -30..30, 30..50, BELGOROD, BELGOROD_UK),
        kabTarget("Bohodukhiv", 0..30, 45..60, BELGOROD, BELGOROD_UK),
        kabTarget("Chuhuiv", 0..25, 65..80, BELGOROD, BELGOROD_UK),
        kabTarget("Kupiansk", 60..120, 40..60, "occupied Luhansk Oblast", "окупованої Луганщини", weight = 2),
        kabTarget("Izium", 80..130, 50..65, "occupied Luhansk Oblast", "окупованої Луганщини"),
        kabTarget("Sloviansk", 90..140, 50..65, "occupied Donetsk Oblast", "окупованої Донеччини"),
        kabTarget("Kramatorsk", 100..160, 45..65, "occupied Donetsk Oblast", "окупованої Донеччини", weight = 2),
        kabTarget("Zaporizhzhia", 150..200, 40..60, "occupied Zaporizhzhia Oblast", "окупованої Запорізької області", weight = 3),
        kabTarget("Nikopol", 150..210, 25..45, "occupied Zaporizhzhia Oblast", "окупованої Запорізької області"),
        kabTarget("Marhanets", 160..220, 25..40, "occupied Zaporizhzhia Oblast", "окупованої Запорізької області"),
        kabTarget("Kherson", 110..200, 25..45, "occupied left bank, Kherson Oblast", "окупованого лівобережжя Херсонщини", weight = 2),
    )
    private val kabWeights = kabTargets.flatMap { target -> List(target.weight) { target } }

    /** One guided bomb, released at [release] at [launchedAt] and gliding straight to its target. */
    class Kab internal constructor(
        val id: String,
        internal val target: KabTarget,
        val release: GeoPoint,
        val launchedAt: Long,
        val speedKmh: Double,
    ) {
        val city: City get() = target.city
        val impactAt: Long = launchedAt + (GeoMath.distanceKm(release, target.city.point) / speedKmh * 3_600_000).toLong()

        fun positionAt(t: Long): GeoPoint {
            val f = ((t - launchedAt).toDouble() / (impactAt - launchedAt)).coerceIn(0.0, 1.0)
            val to = target.city.point
            return GeoPoint(release.lat + (to.lat - release.lat) * f, release.lon + (to.lon - release.lon) * f)
        }

        val message get() = "Пуск КАБ (УМПК) з Су-34 з боку ${target.launchAreaUk}"
    }

    /** A Shahed group crossing in at the first settlement of [route] at [departedAt]. */
    class ShahedGroup internal constructor(
        val idBase: Long,
        val route: List<City>,
        val speedKmh: Double,
        val departedAt: Long,
        val message: String,
    )

    /** Every alert the raids raise at [nowMs]. */
    fun alertsAt(nowMs: Long): List<Alert> = kabAlerts(nowMs) + shahedAlerts(nowMs)

    /** Bombs released at or before [nowMs] that are gliding, or came down in the last few minutes. */
    fun kabsAround(nowMs: Long): List<Kab> =
        kabsLaunched(nowMs - KAB_LOOKBACK_MS, nowMs).filter { nowMs - it.impactAt <= KAB_ALERT_TAIL_MS }

    private fun kabsLaunched(from: Long, to: Long): List<Kab> {
        val salvos = (from / SALVO_PERIOD_MS..to / SALVO_PERIOD_MS).flatMap(::salvo)
        val singles = (from / SINGLE_PERIOD_MS..to / SINGLE_PERIOD_MS).map(::single)
        return (salvos + singles).filter { it.launchedAt in from..to }
    }

    /** The salvo of slot [slot]: three bombs on one target, released seconds apart at a random moment. */
    private fun salvo(slot: Long): List<Kab> {
        val random = Random(slot * 7_919 + 1)
        val target = kabWeights.random(random)
        val start = slot * SALVO_PERIOD_MS + random.nextLong(0, 12 * 60_000L)
        var offset = 0L
        return List(SALVO_SIZE) { i ->
            kab("kab-s$slot-$i", target, start + offset, random).also { offset += random.nextLong(8_000, 30_000) }
        }
    }

    /** The single launch of minute [slot]. */
    private fun single(slot: Long): Kab {
        val random = Random(slot * 104_729 + 2)
        return kab("kab-$slot", kabWeights.random(random), slot * SINGLE_PERIOD_MS + random.nextLong(0, SINGLE_PERIOD_MS), random)
    }

    private fun kab(id: String, target: KabTarget, launchedAt: Long, random: Random): Kab {
        val bearing = random.nextInt(target.bearingDeg.first, target.bearingDeg.last + 1).toDouble()
        val range = random.nextInt(target.rangeKm.first, target.rangeKm.last + 1).toDouble()
        return Kab(id, target, GeoMath.destination(target.city.point, bearing, range), launchedAt, speedKmh = random.nextDouble(550.0, 750.0))
    }

    /**
     * One alert per target hromada, from the first release until [KAB_ALERT_TAIL_MS] after the last
     * impact. Back-to-back attacks extend one alert; its id comes from when it started, so it stays the
     * same while the alert is up.
     */
    private fun kabAlerts(nowMs: Long): List<Alert> {
        val active = kabsAround(nowMs).map { it.target }.toSet()
        val history = kabsLaunched(nowMs - KAB_ALERT_HISTORY_MS, nowMs).groupBy { it.target }
        return active.map { target ->
            var startedAt = 0L
            var endsAt = Long.MIN_VALUE
            history.getValue(target).sortedBy { it.launchedAt }.forEach { kab ->
                if (kab.launchedAt > endsAt) startedAt = kab.launchedAt
                endsAt = maxOf(endsAt, kab.impactAt + KAB_ALERT_TAIL_MS)
            }
            DroneSimulator.cityAlert(
                id = 980_000L + kabTargets.indexOf(target) * 1_000L + (startedAt / 60_000L) % 1_000,
                city = target.city,
                startedAt = Instant.fromEpochMilliseconds(startedAt).toString(),
                message = "Пуски КАБ (УМПК) з Су-34 з боку ${target.launchAreaUk}",
                threatType = ThreatType.GuidedBombs,
            )
        }
    }

    /**
     * The flight of every bomb in the air, plus those that came down in the last minute: the release
     * point and where the bomb is at [nowMs] (its impact point once it landed).
     */
    fun kabTracks(nowMs: Long): List<DroneTrack> = kabsAround(nowMs)
        .filter { nowMs - it.impactAt <= KAB_TRACK_TAIL_MS }
        .map { kab ->
            val inFlight = nowMs < kab.impactAt
            val t = maxOf(minOf(nowMs, kab.impactAt), kab.launchedAt + 1)
            val here = kab.positionAt(t)
            DroneTrack(
                id = kab.id,
                type = ThreatType.GuidedBombs,
                level = ThreatLevel.Red,
                oblastUid = kab.city.oblastUid,
                active = inFlight,
                firstSeen = kab.launchedAt,
                lastSeen = t,
                // The target's alert stays on the map as its own marker.
                markerIds = emptyList(),
                samples = listOf(
                    TrackSample(kab.launchedAt, kab.release.lat, kab.release.lon, "Release point, ${kab.target.launchArea}", null, kab.message),
                    TrackSample(t, here.lat, here.lon, if (inFlight) "Gliding towards ${kab.city.name}" else kab.city.name, kab.city.oblastUid, kab.message),
                ),
            )
        }

    private val entryPoints = listOf(
        "Hlukhiv", "Shostka", "Koriukivka", "Sumy", "Trostianets", "Okhtyrka", "Zolochiv", "Chuhuiv", "Kupiansk",
        "Izium", "Zaporizhzhia", "Nikopol", "Kherson", "Mykolaiv", "Pivdenne", "Izmail",
    ).map(::city)

    private val shahedTargets = listOf(
        "Kyiv", "Vinnytsia", "Starokostiantyniv", "Khmelnytskyi", "Lviv", "Lutsk", "Rivne", "Zhytomyr", "Bila Tserkva",
        "Cherkasy", "Kropyvnytskyi", "Poltava", "Kremenchuk", "Dnipro", "Odesa", "Kryvyi Rih", "Uman", "Ternopil",
        "Chernihiv", "Pavlohrad",
    ).map(::city)

    /** Groups that crossed in during the last few hours, including ones that already finished their route. */
    fun shahedGroupsAround(nowMs: Long): List<ShahedGroup> {
        val slot = nowMs / SHAHED_PERIOD_MS
        return (slot - SHAHED_LOOKBACK_SLOTS..slot).flatMap(::shahedGroups).filter { it.departedAt <= nowMs }
    }

    private fun shahedGroups(slot: Long): List<ShahedGroup> {
        val random = Random(slot * 15_485_863 + 3)
        return List(random.nextInt(1, 3)) { i ->
            val entry = entryPoints.random(random)
            val target = shahedTargets.filter { GeoMath.distanceKm(entry.point, it.point) >= 200 }.random(random)
            ShahedGroup(
                idBase = 600_000L + (slot % 100) * 1_000 + i * 100,
                route = route(entry, target),
                speedKmh = random.nextDouble(150.0, 185.0),
                departedAt = slot * SHAHED_PERIOD_MS + random.nextLong(0, SHAHED_PERIOD_MS),
                message = if (random.nextInt(4) == 0) DroneSimulator.GERBERA_MESSAGE else DroneSimulator.SHAHED_MESSAGE,
            )
        }.filter { it.route.size >= 3 }
    }

    private fun shahedAlerts(nowMs: Long): List<Alert> = shahedGroupsAround(nowMs).flatMap { group ->
        DroneSimulator.routeAlerts(group.idBase, group.route, group.speedKmh, group.departedAt, group.message, ThreatType.Drones, nowMs)
    }

    private val routes = HashMap<Pair<City, City>, List<City>>()

    /**
     * Settlement-to-settlement path from [from] towards [to]: each hop picks a known settlement 30-100 km
     * ahead that keeps closest to the straight line, preferring longer hops.
     */
    internal fun route(from: City, to: City): List<City> = synchronized(routes) {
        routes.getOrPut(from to to) {
            val route = mutableListOf(from)
            while (route.size < 16) {
                val here = route.last()
                val remaining = GeoMath.distanceKm(here.point, to.point)
                if (remaining <= MAX_HOP_KM) {
                    route += to
                    break
                }
                route += Regions.cities
                    .filter { it !in route && it.oblastUid != Regions.SEVASTOPOL_UID }
                    .map { it to GeoMath.distanceKm(here.point, it.point) }
                    .filter { (city, hop) -> hop in MIN_HOP_KM..MAX_HOP_KM && GeoMath.distanceKm(city.point, to.point) < remaining - 20 }
                    .minByOrNull { (city, hop) -> hop + GeoMath.distanceKm(city.point, to.point) - 0.3 * hop }
                    ?.first
                    ?: break
            }
            route
        }
    }

    private fun city(name: String) = Regions.cities.first { it.name == name }
}
